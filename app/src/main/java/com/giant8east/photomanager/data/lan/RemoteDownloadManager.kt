/** 局域网远端相册下载调度器：串行下载队列、增量对比同步、原子 .part 落盘与实时进度/速度流。 */
package com.giant8east.photomanager.data.lan

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.giant8east.photomanager.data.album.AlbumIndexer
import com.giant8east.photomanager.data.database.AlbumDao
import com.giant8east.photomanager.data.media.PhotoFormat
import com.giant8east.photomanager.lan.client.LanClient
import com.giant8east.photomanager.lan.client.LanDownloadService
import com.giant8east.photomanager.lan.client.LanHttp
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.util.LocalPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 远端相册唯一键：不同局域网主机各有自增 albumId，必须带设备标识。 */
data class RemoteAlbumKey(
    val deviceKey: String,
    val albumId: Long
) {
    companion object {
        fun of(device: LanDevice, albumId: Long): RemoteAlbumKey =
            RemoteAlbumKey(deviceKey = device.deviceId ?: device.address, albumId = albumId)
    }
}

sealed interface RemoteDownloadStatus {
    data object Queued : RemoteDownloadStatus
    data class Downloading(
        val finished: Int,
        val total: Int,
        val bytesPerSecond: Long
    ) : RemoteDownloadStatus
    /** 中途暂停/停止：已落盘的图片保留并入库，再次点击播放从断点续传。 */
    data class Paused(val finished: Int, val total: Int) : RemoteDownloadStatus
    data class Failed(val reason: String) : RemoteDownloadStatus
    data object Finished : RemoteDownloadStatus
}

data class RemoteDownloadTask(
    val key: RemoteAlbumKey,
    val albumName: String,
    val status: RemoteDownloadStatus
)

/** 给前台通知栏服务消费的全局汇总快照。 */
data class DownloadServiceSummary(
    val isActive: Boolean = false,
    val currentAlbumName: String = "",
    val finished: Int = 0,
    val total: Int = 0,
    val bytesPerSecond: Long = 0L,
    val remainingAlbums: Int = 0,
    val sessionCompleted: Int = 0,
    val sessionFailed: Int = 0
)

private data class QueuedRequest(
    val key: RemoteAlbumKey,
    val device: LanDevice,
    val album: LanAlbumDto
)

@Singleton
class RemoteDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lanClient: LanClient,
    private val lanHttp: LanHttp,
    private val remoteDownloadData: RemoteDownloadData,
    private val albumIndexer: AlbumIndexer,
    private val albumDao: AlbumDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val pendingQueue = ArrayDeque<QueuedRequest>()

    private var workerJob: Job? = null
    private var activeKey: RemoteAlbumKey? = null
    private var activeItemJob: Job? = null
    private var sessionCompleted = 0
    private var sessionFailed = 0

    private val mutableTasks = MutableStateFlow<Map<RemoteAlbumKey, RemoteDownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<RemoteAlbumKey, RemoteDownloadTask>> = mutableTasks.asStateFlow()

    private val mutableSummary = MutableStateFlow(DownloadServiceSummary())
    val summary: StateFlow<DownloadServiceSummary> = mutableSummary.asStateFlow()

    /** 将选中的远端相册加入串行下载队列（已在排队或下载中的自动跳过）。 */
    fun enqueue(device: LanDevice, albums: List<LanAlbumDto>) {
        if (albums.isEmpty()) return
        var addedAny = false
        synchronized(queueLock) {
            val currentMap = mutableTasks.value
            for (album in albums) {
                val key = RemoteAlbumKey.of(device, album.id)
                val existingStatus = currentMap[key]?.status
                if (existingStatus is RemoteDownloadStatus.Queued || existingStatus is RemoteDownloadStatus.Downloading) {
                    continue
                }
                pendingQueue.addLast(QueuedRequest(key, device, album))
                mutableTasks.update { map ->
                    map + (key to RemoteDownloadTask(key, album.name, RemoteDownloadStatus.Queued))
                }
                addedAny = true
            }
            if (addedAny && workerJob?.isActive != true) {
                sessionCompleted = 0
                sessionFailed = 0
                startService()
                workerJob = scope.launch { runQueueLoop() }
            }
        }
    }

    /** 取消单个相册任务（排队中直接移出；下载中取消当前协程，已下好的图片保留并切为已暂停）。 */
    fun cancelOne(key: RemoteAlbumKey) {
        synchronized(queueLock) {
            pendingQueue.removeAll { it.key == key }
            if (activeKey == key) {
                activeItemJob?.cancel()
            } else {
                mutableTasks.update { map -> map - key }
            }
        }
    }

    /** 全部停止：取消当前下载、清空队列并复位通知栏状态（正在下载的那本保留已下好图片并切为已暂停）。 */
    fun stopAll() {
        synchronized(queueLock) {
            pendingQueue.clear()
            activeItemJob?.cancel()
            workerJob?.cancel()
            workerJob = null
            activeItemJob = null
            mutableTasks.update { map ->
                map.filterValues { task -> task.status !is RemoteDownloadStatus.Queued }
            }
            mutableSummary.value = DownloadServiceSummary(isActive = false)
        }
    }

    private suspend fun runQueueLoop() {
        while (true) {
            val next = synchronized(queueLock) {
                if (pendingQueue.isEmpty()) {
                    activeKey = null
                    null
                } else {
                    val req = pendingQueue.removeFirst()
                    activeKey = req.key
                    req
                }
            } ?: break

            val job = scope.launch { downloadSingleAlbum(next) }
            synchronized(queueLock) { activeItemJob = job }
            job.join()
            synchronized(queueLock) {
                activeItemJob = null
                activeKey = null
            }
        }

        val (completed, failed) = synchronized(queueLock) {
            workerJob = null
            sessionCompleted to sessionFailed
        }
        mutableSummary.value = DownloadServiceSummary(
            isActive = false,
            sessionCompleted = completed,
            sessionFailed = failed
        )
    }

    private suspend fun downloadSingleAlbum(request: QueuedRequest) {
        val key = request.key
        var currentPartFile: File? = null
        var localDirRef: File? = null
        var total = request.album.photoCount
        var finished = 0
        try {
            updateProgress(key, request.album.name, finished = 0, total = total, speed = 0L)
            val remotePhotos = lanClient.photos(request.device, request.album.id)
            val localDir = resolveTargetDirectory(request.device, request.album)
            localDirRef = localDir
            if (!localDir.exists() && !localDir.mkdirs()) {
                error("Cannot create directory: ${localDir.path}")
            }

            // 增量对比：以文件名为键，本地多余图片删除，同名且大小一致直接跳过
            val remoteByName = remotePhotos.associateBy { sanitizeFileName(it.name) }
            localDir.listFiles()?.forEach { existing ->
                if (!existing.isFile) return@forEach
                if (existing.name.endsWith(PART_SUFFIX)) {
                    existing.delete()
                    return@forEach
                }
                if (PhotoFormat.fromFileName(existing.name) != PhotoFormat.OTHER && existing.name !in remoteByName) {
                    existing.delete()
                }
            }

            total = remotePhotos.size
            var windowBytes = 0L
            var windowStartMs = SystemClock.elapsedRealtime()
            var currentSpeed = 0L

            updateProgress(key, request.album.name, finished = 0, total = total, speed = 0L)

            for (photo in remotePhotos) {
                val safeName = sanitizeFileName(photo.name)
                val targetFile = File(localDir, safeName)
                if (targetFile.isFile && targetFile.length() == photo.sizeBytes) {
                    finished++
                    updateProgress(key, request.album.name, finished = finished, total = total, speed = currentSpeed)
                    continue
                }

                val partFile = File(localDir, "$safeName$PART_SUFFIX")
                currentPartFile = partFile
                val url = "http://${request.device.address}${photo.mediaUrl}"
                downloadFileAtomic(url, partFile, targetFile) { bytesRead ->
                    windowBytes += bytesRead
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - windowStartMs
                    if (elapsed >= SPEED_WINDOW_MILLIS) {
                        currentSpeed = windowBytes * 1_000L / elapsed
                        windowBytes = 0L
                        windowStartMs = now
                        updateProgress(key, request.album.name, finished = finished, total = total, speed = currentSpeed)
                    }
                }
                currentPartFile = null
                finished++
                updateProgress(key, request.album.name, finished = finished, total = total, speed = currentSpeed)
            }

            indexDownloadedAlbum(localDir, request)

            synchronized(queueLock) { sessionCompleted++ }
            mutableTasks.update { map ->
                map + (key to RemoteDownloadTask(key, request.album.name, RemoteDownloadStatus.Finished))
            }
        } catch (cancelled: CancellationException) {
            // 只删未写完的 .part 临时文件；已经完整落盘的图片原样保留并立即入库，状态切为 Paused 方便续传
            currentPartFile?.delete()
            val dir = localDirRef
            if (finished > 0 && dir != null) {
                withContext(NonCancellable) {
                    runCatching { indexDownloadedAlbum(dir, request) }
                        .onFailure { Log.w(TAG, "Failed to index partial album ${dir.path}", it) }
                }
                mutableTasks.update { map ->
                    map + (
                        key to RemoteDownloadTask(
                            key = key,
                            albumName = request.album.name,
                            status = RemoteDownloadStatus.Paused(finished, total)
                        )
                    )
                }
            } else {
                mutableTasks.update { map -> map - key }
            }
            throw cancelled
        } catch (error: Exception) {
            currentPartFile?.delete()
            val dir = localDirRef
            if (finished > 0 && dir != null) {
                runCatching { indexDownloadedAlbum(dir, request) }
                    .onFailure { Log.w(TAG, "Failed to index partial album after error ${dir.path}", it) }
            }
            Log.w(TAG, "Failed to download album ${request.album.name} (${key.albumId})", error)
            synchronized(queueLock) { sessionFailed++ }
            mutableTasks.update { map ->
                map + (
                    key to RemoteDownloadTask(
                        key = key,
                        albumName = request.album.name,
                        status = RemoteDownloadStatus.Failed(error.message ?: "Download failed")
                    )
                )
            }
        }
    }

    /** 将落地目录扫描入库（同步生成 .thumb），并打上来源设备与远端相册 ID 标记。 */
    private suspend fun indexDownloadedAlbum(localDir: File, request: QueuedRequest) {
        val normalizedPath = LocalPaths.normalize(localDir.path)
        albumIndexer.scanAndWrite(normalizedPath)
        albumDao.updateAlbumSourceByPath(
            albumPath = normalizedPath,
            sourceName = request.device.name,
            remoteDeviceId = request.key.deviceKey,
            remoteAlbumId = request.album.id
        )
    }

    /** 原子落盘：先流式写 `.part`，完整写完后重命名为目标文件，避免中途停止留下半截坏图。 */
    private suspend fun downloadFileAtomic(
        url: String,
        partFile: File,
        targetFile: File,
        onBytes: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val call = lanHttp.client.newCall(request)
        call.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for $url" }
            val body = checkNotNull(response.body) { "Empty response body for $url" }
            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        onBytes(read)
                    }
                    output.flush()
                }
            }
        }
        if (targetFile.exists()) targetFile.delete()
        check(partFile.renameTo(targetFile)) { "Failed to rename ${partFile.path} to ${targetFile.path}" }
    }

    /**
     * 查找落地目录：若这本远端相册以前下载过，直接复用原目录（即便用户已挪动分组）；
     * 否则落到 `<下载根目录>/<相册名>`，若撞到别的相册目录则追加 `_<albumId>` 避免混写。
     */
    private suspend fun resolveTargetDirectory(device: LanDevice, album: LanAlbumDto): File {
        val deviceKey = device.deviceId ?: device.address
        val existing = albumDao.queryAlbumByRemote(deviceKey, album.id)
        if (existing != null) {
            return File(existing.albumPath)
        }
        val rootDir = File(remoteDownloadData.getPath())
        val safeAlbumName = sanitizeFileName(album.name).ifEmpty { "album_${album.id}" }
        val candidate = File(rootDir, safeAlbumName)
        val normalizedCandidate = LocalPaths.normalize(candidate.path)
        val occupant = albumDao.queryAlbumByPath(normalizedCandidate)
        val belongsToOther = occupant != null &&
            (occupant.remoteDeviceId != deviceKey || occupant.remoteAlbumId != album.id)
        return if (belongsToOther) {
            File(rootDir, "${safeAlbumName}_${album.id}")
        } else {
            candidate
        }
    }

    private fun updateProgress(
        key: RemoteAlbumKey,
        albumName: String,
        finished: Int,
        total: Int,
        speed: Long
    ) {
        mutableTasks.update { map ->
            map + (
                key to RemoteDownloadTask(
                    key = key,
                    albumName = albumName,
                    status = RemoteDownloadStatus.Downloading(finished, total, speed)
                )
            )
        }
        val remaining = synchronized(queueLock) { pendingQueue.size }
        mutableSummary.value = DownloadServiceSummary(
            isActive = true,
            currentAlbumName = albumName,
            finished = finished,
            total = total,
            bytesPerSecond = speed,
            remainingAlbums = remaining,
            sessionCompleted = sessionCompleted,
            sessionFailed = sessionFailed
        )
    }

    private fun startService() {
        val intent = Intent(context, LanDownloadService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private companion object {
        const val TAG = "RemoteDownloadManager"
        const val PART_SUFFIX = ".part"
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val SPEED_WINDOW_MILLIS = 500L
    }
}
