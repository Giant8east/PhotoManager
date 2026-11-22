/** 远程连接页的 ViewModel：下载目录配置、本机共享开关与局域网设备发现。 */
package com.giant8east.photomanager.pages.setting

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.lan.RemoteDownloadData
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.server.LanTrustedDevice
import com.giant8east.photomanager.lan.client.LanClient
import com.giant8east.photomanager.lan.client.MdnsBrowser
import com.giant8east.photomanager.lan.server.LanShareController
import com.giant8east.photomanager.lan.server.LanShareEvent
import com.giant8east.photomanager.lan.server.LanShareResult
import com.giant8east.photomanager.lan.server.LanShareState
import com.giant8east.photomanager.lan.server.LanPairing
import com.giant8east.photomanager.pages.lan.toLanUiText
import com.giant8east.photomanager.ui.state.resolve
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.net.toUri

@HiltViewModel
class RemotePageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteDownloadData: RemoteDownloadData,
    private val lanShareController: LanShareController,
    private val lanClient: LanClient,
    // 进程级单例：发现随应用前台生命周期开关，页面只订阅结果
    private val browser: MdnsBrowser
) : ViewModel() {
    var downloadPath by mutableStateOf("")
        private set
    var shareState by mutableStateOf(LanShareState())
        private set
    var devices by mutableStateOf<List<LanDevice>>(emptyList())
        private set

    /** 已记住的设备：下次配对免确认，可在这里撤销。 */
    var trustedDevices by mutableStateOf<List<LanTrustedDevice>>(emptyList())
        private set

    /**
     * 系统是否已允许本应用不受电量优化限制。没允许时，ROM 会把后台进程压进
     * `cpuset:/background`，连 accept 都跑不动——对端心跳超时后判定断连。
     */
    var ignoresBatteryOptimizations by mutableStateOf(false)
        private set

    /** 当前要用户拍板的那一条配对请求；多条时按到达顺序一个个处理。 */
    var pairingRequest by mutableStateOf<LanPairing.Pending?>(null)
        private set

    /** 正在等对端确认的设备；非 null 时页面显示不可取消的等待弹窗。 */
    var pairingDevice by mutableStateOf<LanDevice?>(null)
        private set

    /** 对端已确认、等待页面跳转的设备；页面处理完调用 [consumeApprovedDevice] 清空。 */
    var approvedDevice by mutableStateOf<LanDevice?>(null)
        private set

    init {
        refreshBatteryOptimizations()
        viewModelScope.launch {
            downloadPath = remoteDownloadData.getPath()
        }
        viewModelScope.launch {
            lanShareController.state.collect { shareState = it }
        }
        viewModelScope.launch {
            // 待确认的配对请求：只弹最前面那一条，处理掉（允许/拒绝/超时）后自动轮下一条
            lanShareController.pairingRequests.collect { pairingRequest = it.firstOrNull() }
        }
        viewModelScope.launch {
            // 白名单只增删不用自己维护：DAO 的 Flow 会把最新列表推过来
            lanShareController.trustedDevices.collect { trustedDevices = it }
        }
        viewModelScope.launch {
            // 事件流：共享被控制器主动关掉时提示一次（用户自己关开关不走这里）
            lanShareController.events.collect { event ->
                when (event) {
                    LanShareEvent.AddressLost ->
                        Toast.makeText(context, R.string.lan_share_network_lost, Toast.LENGTH_SHORT).show()

                    LanShareEvent.AdvertiseFailed ->
                        Toast.makeText(context, R.string.lan_share_advertise_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewModelScope.launch {
            // 发现结果是一张 Map（key 是 mDNS 实例名），界面要的是排好序的列表：
            // 排序属于展示逻辑，留在 ViewModel，MdnsBrowser 只负责"现在有哪些设备"。
            browser.services
                .map { services -> services.values.sortedBy { device -> device.name } }
                .collect { devices = it }
        }
    }

    /** 目录选择结果先解析成绝对路径再持久化，与扫描目录使用同一套规范化规则。 */
    fun setDownloadPath(uri: Uri) {
        viewModelScope.launch {
            try {
                val resolved = remoteDownloadData.resolvePath(uri)
                remoteDownloadData.setPath(resolved)
                downloadPath = resolved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to set download path: $uri", error)
                // SafPathResolver 抛出的 message 已是本地化资源文案；无 message 时回退到无效目录提示
                val message = error.message ?: context.getString(R.string.invalid_directory_path)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 开共享前控制器会先确认有没有可用 Wi-Fi 地址、再等引擎启动与 mDNS 注册结果；
     * 三种失败（无地址、引擎起不来、广播不上）都不改变状态（开关保持 off），这里负责把原因说出来。
     */
    fun setSharing(enabled: Boolean) {
        viewModelScope.launch {
            when (lanShareController.setEnabled(enabled)) {
                LanShareResult.NoAddress ->
                    Toast.makeText(context, R.string.lan_share_no_address, Toast.LENGTH_SHORT).show()

                LanShareResult.StartFailed ->
                    Toast.makeText(context, R.string.lan_share_start_failed, Toast.LENGTH_SHORT).show()

                LanShareResult.AdvertiseFailed ->
                    Toast.makeText(context, R.string.lan_share_advertise_failed, Toast.LENGTH_SHORT).show()

                LanShareResult.Started, LanShareResult.Closed -> Unit
            }
        }
    }

    /** 从系统设置返回时重新读一次，页面据此决定那行提示还在不在。 */
    fun refreshBatteryOptimizations() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        ignoresBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /**
     * 拉起系统的"是否允许后台运行"对话框。少数精简 ROM 没有这个对话框，
     * 退回到电量优化列表让用户自己找；两个都打不开时给一条 toast，
     * 否则用户点了没反应会以为按钮坏了。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(fallback) }
            .onFailure {
                Toast.makeText(context, R.string.lan_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
    }

    fun approvePairing(request: LanPairing.Pending, remember: Boolean) {
        viewModelScope.launch { lanShareController.approvePairing(request.requestId, remember) }
    }

    fun rejectPairing(request: LanPairing.Pending) {
        viewModelScope.launch { lanShareController.rejectPairing(request.requestId) }
    }

    /** 撤销授权：控制器会同时作废该设备现有的 token（立刻断）并删掉白名单记录（忘记）。 */
    fun revokeDevice(deviceId: String) {
        viewModelScope.launch { lanShareController.revokeDevice(deviceId) }
    }

    /**
     * 点设备后先让对端确认，确认通过才允许跳转；等待期间 [pairingDevice] 非空，
     * 页面据此显示不可取消的弹窗（对端在等人点按钮，用户中途退出只会白等 60 秒）。
     */
    fun requestOpen(device: LanDevice) {
        viewModelScope.launch {
            pairingDevice = device
            try {
                lanClient.connect(device)
                approvedDevice = device
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 拒绝/超时/连不上在这里各自给出对应文案，不再一律说"无法连接"
                Toast.makeText(context, error.toLanUiText().resolve(context), Toast.LENGTH_SHORT).show()
            } finally {
                pairingDevice = null
            }
        }
    }

    /** 主动重开一次 mDNS 发现会话，刷新当前局域网内的共享设备。 */
    fun refreshDevices() = browser.refresh()

    /** 页面跳转完成后清空，避免重组时重复跳转。 */
    fun consumeApprovedDevice() {
        approvedDevice = null
    }

    // 伴生对象（等价于 Java 的类静态常量区），仅本文件可见
    private companion object {
        const val TAG = "RemotePageViewModel"
    }
}
