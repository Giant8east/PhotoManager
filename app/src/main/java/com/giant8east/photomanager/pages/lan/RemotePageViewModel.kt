/** 远程连接页的 ViewModel：下载目录配置、本机共享开关与局域网设备发现。 */
package com.giant8east.photomanager.pages.lan

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.data.settings.SettingData
import com.giant8east.photomanager.lan.LanDevice
import com.giant8east.photomanager.lan.LanDiscovery
import com.giant8east.photomanager.lan.LanShareController
import com.giant8east.photomanager.lan.LanShareState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RemotePageViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingData: SettingData,
    private val lanShareController: LanShareController
) : ViewModel() {
    private val discovery = LanDiscovery(context)

    var downloadPath by mutableStateOf("")
        private set
    var shareState by mutableStateOf(LanShareState())
        private set
    var devices by mutableStateOf<List<LanDevice>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            settingData.remoteDownloadPath.collect { downloadPath = it }
        }
        viewModelScope.launch {
            lanShareController.state.collect { shareState = it }
        }
        viewModelScope.launch {
            discovery.devices.collect { devices = it }
        }
    }

    /** 目录选择结果先解析成绝对路径再持久化，与扫描目录使用同一套规范化规则。 */
    fun setDownloadPath(uri: Uri) {
        viewModelScope.launch {
            runCatching { settingData.resolvePath(uri) }
                .onSuccess { settingData.setRemoteDownloadPath(it) }
        }
    }

    fun setSharing(enabled: Boolean) = lanShareController.setEnabled(enabled)

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()
}
