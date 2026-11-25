/** 远程连接页：配置下载目录、开启本机共享，并列出同网段已开启共享的设备。 */
package com.giant8east.photomanager.pages.setting

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.giant8east.photomanager.ui.theme.appColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.giant8east.photomanager.R
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.common.AppPageScaffold
import com.giant8east.photomanager.pages.lan.RemoteAlbumPage
import com.giant8east.photomanager.ui.theme.appColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePage(navigator: AppNavigator, viewModel: RemotePageViewModel = hiltViewModel()) {
    BackHandler { navigator.pop() }
    val context = LocalContext.current
    // 从系统的"后台运行"设置返回时要重读一次，否则那行提示会一直挂着
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshBatteryOptimizations()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::setDownloadPath) }
    // 只有"用户刚点了开关、正在等权限结果"时才继续开共享。
    // 少了这个标记，Activity 重建/进程恢复时重放的上一次权限结果也会走进回调，
    // 把开关莫名打开——用户没点任何东西，共享却开了。
    var pendingSharing by remember { mutableStateOf(false) }
    // Android 13 起前台服务通知需要运行时权限。被拒也继续开启共享：
    // 服务照常运行，只是系统不显示那条通知。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingSharing) {
            pendingSharing = false
            viewModel.setSharing(true)
        }
    }
    AppPageScaffold(
        title = stringResource(R.string.setting_remote_connect),
        onBack = navigator::pop,
        floatingActionButton = {
            FloatingActionButton(
                // 等价于 onClick = { viewModel.refreshDevices() }
                onClick = viewModel::refreshDevices,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
        }
    ) {
        // 整页统一放入单个 LazyColumn，避免横屏或已记住设备过多时把发现设备列表挤成 0 高
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "download_path") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { directoryPicker.launch(null) }
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_eh_folder_move),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.remote_download_path),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            viewModel.downloadPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "lan_share") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.setting_lan_share),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            viewModel.shareState.address
                                ?: stringResource(R.string.setting_lan_share_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.shareState.isRunning,
                        onCheckedChange = { enabled ->
                            val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            if (enabled && needsNotification) {
                                pendingSharing = true
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setSharing(enabled)
                            }
                        }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            // 共享要在后台长期在线；被电量优化限制时系统会把进程压进 background cpuset，
            // 对端心跳超时后就判定断连。允许过就不再打扰。
            if (!viewModel.ignoresBatteryOptimizations) {
                item(key = "battery_optimization") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openBatteryOptimizationSettings(context) }
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.lan_allow_background_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.lan_allow_background_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (viewModel.trustedDevices.isNotEmpty()) {
                item(key = "trusted_header") {
                    Text(
                        stringResource(R.string.lan_trusted_devices),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                // 已记住的设备：撤销会同时踢掉它正在用的 token 并删掉白名单记录
                items(viewModel.trustedDevices, key = { "trusted_${it.deviceId}" }) { device ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                device.deviceId.take(8),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { viewModel.revokeDevice(device.deviceId) }) {
                            Text(stringResource(R.string.lan_revoke))
                        }
                    }
                }
                item(key = "trusted_divider") {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item(key = "devices_header") {
                Text(
                    stringResource(R.string.lan_devices_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            if (viewModel.devices.isEmpty()) {
                item(key = "devices_empty") {
                    Text(
                        stringResource(R.string.lan_no_devices),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(viewModel.devices, key = { "device_${it.address}" }) { device ->
                    DeviceRow(device) {
                        // 先请求配对，对端确认后再跳转
                        viewModel.requestOpen(device)
                    }
                }
            }
        }
    }

    PairingRequestDialog(viewModel)

    // 对端确认通过后才跳转：请求与跳转分开，避免点进去才知道被拒
    val approved = viewModel.approvedDevice
    LaunchedEffect(approved) {
        if (approved != null) {
            navigator.pushTo { RemoteAlbumPage(navigator, approved) }
            viewModel.consumeApprovedDevice()
        }
    }

    // 等待对端确认：点空白与返回键都不关闭，否则用户退出去只是白等 60 秒超时
    viewModel.pairingDevice?.let { device ->
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.lan_pairing_waiting_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.lan_pairing_waiting_message, device.name))
                    }
                }
            }
        }
    }
}

/**
 * 有设备请求浏览本机相册时弹出，三个结论：允许并记住（进白名单，下次静默放行）、
 * 允许（只本次有效）、拒绝（对端收到 403）。
 */
@Composable
private fun PairingRequestDialog(viewModel: RemotePageViewModel) {
    // 没有待处理请求就不显示；处理掉或 60 秒超时后这里变成 null，对话框随即消失
    val request = viewModel.pairingRequest ?: return
    AlertDialog(
        // 不允许点外面关掉：对端在等一个明确结论，不处理就让它按超时结束
        onDismissRequest = {},
        title = { Text(stringResource(R.string.lan_pair_request_title)) },
        text = { Text(stringResource(R.string.lan_pair_request_message, request.name)) },
        dismissButton = {
            TextButton(
                onClick = { viewModel.rejectPairing(request) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(R.string.lan_pair_reject))
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { viewModel.approvePairing(request, remember = true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = appColors.actionText)
                ) {
                    Text(stringResource(R.string.lan_pair_allow_remember))
                }
                TextButton(
                    onClick = { viewModel.approvePairing(request, remember = false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = appColors.actionText)
                ) {
                    Text(stringResource(R.string.lan_pair_allow))
                }
            }
        }
    )
}

@Composable
private fun DeviceRow(device: LanDevice, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium)
            Text(
                device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 拉起系统的"是否允许后台运行"对话框。少数精简 ROM 没有这个对话框，
 * 退回到电量优化列表让用户自己找；两个都打不开时给一条 toast。
 */
@SuppressLint("BatteryLife")
private fun openBatteryOptimizationSettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData("package:${context.packageName}".toUri())
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(fallback) }
        .onFailure {
            Toast.makeText(context, R.string.lan_open_settings_failed, Toast.LENGTH_SHORT).show()
        }
}
