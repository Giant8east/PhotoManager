/** 远程相册详情页内存 LRU 缓存：参考 EhViewer，退出详情重进且元数据未变时秒开不重复请求。 */
package com.giant8east.photomanager.data.lan

import androidx.collection.LruCache
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanPhotoDto
import javax.inject.Inject
import javax.inject.Singleton

/** 缓存键必须包含主机标识：不同局域网主机各有自增 albumId，单靠 albumId 会碰撞。 */
data class RemoteAlbumCacheKey(
    val hostKey: String,
    val albumId: Long
)

data class CachedRemoteAlbum(
    val photos: List<LanPhotoDto>,
    val photoCount: Int,
    val modifiedAt: String?
)

@Singleton
class RemoteAlbumDetailCache @Inject constructor() {
    private val cache = LruCache<RemoteAlbumCacheKey, CachedRemoteAlbum>(MAX_ENTRIES)

    /** 命中且张数与修改时间均未变化时返回缓存，否则视为过期返回 null。 */
    fun getIfFresh(device: LanDevice, album: LanAlbumDto): List<LanPhotoDto>? {
        val cached = cache.get(keyOf(device, album.id)) ?: return null
        val isFresh = cached.photoCount == album.photoCount && cached.modifiedAt == album.modifiedAt
        return if (isFresh) cached.photos else null
    }

    fun put(device: LanDevice, album: LanAlbumDto, photos: List<LanPhotoDto>) {
        cache.put(
            keyOf(device, album.id),
            CachedRemoteAlbum(
                photos = photos,
                photoCount = album.photoCount,
                modifiedAt = album.modifiedAt
            )
        )
    }

    private fun keyOf(device: LanDevice, albumId: Long): RemoteAlbumCacheKey =
        // 等价于 if (device.deviceId != null) device.deviceId else device.address
        RemoteAlbumCacheKey(hostKey = device.deviceId ?: device.address, albumId = albumId)

    private companion object {
        const val MAX_ENTRIES = 30
    }
}
