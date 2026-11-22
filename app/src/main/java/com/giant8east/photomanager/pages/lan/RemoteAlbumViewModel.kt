/** 远端相册页的 ViewModel：经 LanClient 配对后分页拉取对方相册，只在线浏览、不做下载。 */
package com.giant8east.photomanager.pages.lan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.giant8east.photomanager.lan.LocalAddress
import com.giant8east.photomanager.lan.WifiAddress
import com.giant8east.photomanager.model.AlbumSortField
import com.giant8east.photomanager.lan.client.LanClient
import com.giant8east.photomanager.lan.client.LanEventStream
import com.giant8east.photomanager.lan.client.RemoteAlbumPagingSource
import com.giant8east.photomanager.lan.client.RemoteAlbumQuery
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanGroupDto
import com.giant8east.photomanager.ui.state.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 与对端的连接状态。页面只在不是 [CONNECTED] 时显示一行提示，正常浏览时不打扰。 */
enum class RemoteLinkState {
    /** 正在建立推送连接（首次进入页面、或断线后的重连过程中）。 */
    CONNECTING,

    /** 通道活着：刚收到过对端的心跳。 */
    CONNECTED,

    /** 连不上：对端关共享、进程退出、掉线，或本机没有 Wi-Fi。 */
    OFFLINE
}

@HiltViewModel
class RemoteAlbumViewModel @Inject constructor(
    private val lanClient: LanClient,
    private val eventStream: LanEventStream,
    private val localAddress: LocalAddress
) : ViewModel() {
    var device by mutableStateOf<LanDevice?>(null)
        private set
    var groups by mutableStateOf<List<LanGroupDto>>(emptyList())
        private set
    var ungroupedCount by mutableIntStateOf(0)
        private set
    /** null 表示未分组视图，与本地相册页的语义一致。 */
    var selectedGroupId by mutableStateOf<Long?>(null)
        private set
    /** 分组加载失败时的一行提示；相册列表自己的失败由 Paging 的 LoadState 表达。 */
    var groupsMessage by mutableStateOf<UiText?>(null)
        private set

    /** 搜索框内容：只在输入停止 [SEARCH_DEBOUNCE_MILLIS] 后才真正换查询，避免每敲一个字发一次请求。 */
    var searchInput by mutableStateOf("")
        private set

    /** 远端默认按"自定义"（对方的手动顺序）排序，与本地相册页的默认一致；换字段时字段与方向一起发。 */
    var sortField by mutableStateOf(AlbumSortField.CUSTOM)
        private set
    var isDescending by mutableStateOf(AlbumSortField.CUSTOM.defaultDescending)
        private set

    private var searchJob: Job? = null
    private var groupsJob: Job? = null

    /** 与对端的连接状态；只在页面可见时维护。 */
    var linkState by mutableStateOf(RemoteLinkState.CONNECTING)
        private set

    private var watchJob: Job? = null

    /** null = 还没选设备，此时不建 Pager，页面自然停在加载态。 */
    private val queries = MutableStateFlow<RemoteAlbumQuery?>(null)

    /**
     * 分页数据源：查询条件一变就换一个 Pager（flatMapLatest 会取消旧页的在途加载）。
     * cachedIn 让配置变化（旋转屏幕）后不重新拉第一页。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val albums: Flow<PagingData<LanAlbumDto>> = queries
        .filterNotNull()
        .flatMapLatest { query ->
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = { RemoteAlbumPagingSource(lanClient, query) }
            ).flow
        }
        .cachedIn(viewModelScope)

    fun load(target: LanDevice) {
        if (device == target && queries.value != null) return
        device = target
        selectedGroupId = null
        refreshGroups()
        rebuildQuery()
    }

    fun onSearchInputChange(value: String) {
        searchInput = value
        // 每敲一个字都取消上一个等待中的任务：只有最后一次输入会真正发出去
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            rebuildQuery()
        }
    }

    fun selectSortField(field: AlbumSortField) {
        sortField = field
        isDescending = field.defaultDescending
        rebuildQuery()
    }

    fun setSortDescending(descending: Boolean) {
        isDescending = descending
        rebuildQuery()
    }

    /** 换分组、换排序、换关键词都走这里：查询条件一变就换一个 Pager，旧的那页自动取消。 */
    private fun rebuildQuery() {
        val target = device ?: return
        queries.value = RemoteAlbumQuery(
            device = target,
            groupId = selectedGroupId,
            query = searchInput.trim().takeIf { it.isNotEmpty() },
            sortField = sortField,
            descending = isDescending
        )
    }

    /** 分组（右抽屉）单独加载：它失败不该挡住相册列表本身。 */
    fun refreshGroups() {
        val target = device ?: return
        groupsJob?.cancel()
        groupsMessage = null
        groupsJob = viewModelScope.launch {
            runCatching { lanClient.groups(target) }
                .onSuccess { result ->
                    groups = result.groups
                    ungroupedCount = result.ungroupedCount
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    groupsMessage = error.toLanUiText()
                }
        }
    }

    fun selectGroup(groupId: Long?) {
        if (selectedGroupId == groupId) return
        selectedGroupId = groupId
        // 换分组 = 换查询条件：新 Pager 从 offset 0 开始，不再复用上一组的已加载页
        rebuildQuery()
    }

    /**
     * 开始盯住 [target] 是否还在线：一条 SSE 连接，断了就按退避重连。
     *
     * 幂等：页面反复可见（切后台再回来）不会叠出第二条连接。
     */
    fun startWatching(target: LanDevice) {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                linkState = RemoteLinkState.CONNECTING
                try {
                    watchRound(target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 断线原因不影响处理：掉线、读超时、配对失败，接下来都是"重连"
                }
                // 这一轮连上过就从最短间隔重新退避；一直连不上才逐步拉长
                failures = if (linkState == RemoteLinkState.CONNECTED) 0 else failures + 1
                linkState = RemoteLinkState.OFFLINE
                delay(reconnectDelay(failures))
            }
        }
    }

    /** 页面不可见时断开：一条每秒都在收字节的连接没必要在后台耗电。 */
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        // 阻塞在 socket 读上的线程不会自己检查协程状态，必须显式关掉连接
        eventStream.cancel()
    }

    /**
     * 一轮监听，三种结束方式：对端关闭连接、读超时、本机 Wi-Fi 断开。
     *
     * 本机 Wi-Fi 断开要单独盯：那时 socket 是静默的，要等 4 秒读超时才报错，
     * 而系统回调几十毫秒就能告诉我们。
     */
    private suspend fun watchRound(target: LanDevice) = coroutineScope {
        val stream = launch {
            eventStream.watch(target).collect { linkState = RemoteLinkState.CONNECTED }
        }
        val networkGone = launch {
            // 热点主机端没有 Wi-Fi STA（localAddress.ipv4 恒为 Absent），只盯 ipv4 会把热点连接秒杀；
            // 有 Wi-Fi STA 时先借系统回调秒级感知断开，若连热点也没有就立刻掐断，否则回退到 watch() 继续盯热点。
            if (localAddress.ipv4.value is WifiAddress.Present) {
                localAddress.ipv4.first { it !is WifiAddress.Present }
                if (localAddress.currentAddress() == null) {
                    stream.cancel()
                    // 等价于直接结束当前 launch 协程
                    return@launch
                }
            }
            localAddress.watch().first { it == null }
            stream.cancel()
        }
        // 语法糖：join() 只等结束，不取返回值；stream 抛异常时由 coroutineScope 这里重新抛出
        stream.join()
        networkGone.cancel()
    }

    /** 重连退避 1s → 2s → 4s，最多 5s：对端重启一般几秒内就回来。 */
    private fun reconnectDelay(failures: Int): Long =
        (BASE_RECONNECT_DELAY_MILLIS shl failures.coerceAtMost(3)).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)

    override fun onCleared() {
        stopWatching()
        super.onCleared()
    }

    private companion object {
        /** 与服务端默认页大小无关：Paging 每页 30 条，服务端只是上限 200。 */
        const val PAGE_SIZE = 30

        /** 输入停顿多久才发请求：太短会在打字中途反复换 Pager，太长会显得卡。 */
        const val SEARCH_DEBOUNCE_MILLIS = 300L

        /** 第一次重连的间隔，之后每次翻倍。 */
        const val BASE_RECONNECT_DELAY_MILLIS = 1_000L

        /** 重连间隔上限：再长用户就会觉得"卡住了"。 */
        const val MAX_RECONNECT_DELAY_MILLIS = 5_000L
    }
}