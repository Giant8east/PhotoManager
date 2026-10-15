/** 图片浏览页面，支持分页滑动、缩放、拖拽和长按菜单入口。 */
package com.example.photomanager.pages.photoviewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.example.photomanager.MainActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.data.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerPage(
    navigator: AppNavigator,
    albumId: Long,
    initialIndex: Int,
    viewModel: PhotoViewerViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(albumId) { viewModel.load(albumId, initialIndex) }
    val photos = viewModel.photos.collectAsLazyPagingItems()
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.itemCount }
    val pagerScope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isMenuVisible by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var batteryLevel by remember { mutableIntStateOf(-1) }
    val settings by viewModel.settings.collectAsState(initial = PhotoViewerSettings())
    val activity = LocalContext.current as Activity
    val systemController = remember { PhotoViewerSystemController(activity) }
    (activity as? MainActivity)?.photoViewerSystemController = systemController
    DisposableEffect(settings, pagerState) {
        systemController.apply(settings, {
            if (pagerState.currentPage < photos.itemCount - 1) {
                pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }
        }, {
            if (pagerState.currentPage > 0) {
                pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            }
        })
        onDispose {
            systemController.restore()
            (activity as? MainActivity)?.photoViewerSystemController = null
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collectLatest { viewModel.moveTo(it) }
    }
    LaunchedEffect(settings.autoPageEnabled, settings.autoPageSeconds, isMenuVisible, pagerState.currentPage, photos.itemCount) {
        if (settings.autoPageEnabled && settings.autoPageSeconds > 0 && !isMenuVisible) {
            kotlinx.coroutines.delay(settings.autoPageSeconds * 1000L)
            if (pagerState.currentPage < photos.itemCount - 1) {
                pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }
        }
    }
    LaunchedEffect(settings.showClock) {
        while (settings.showClock) {
            currentTime = LocalTime.now()
            kotlinx.coroutines.delay(30_000)
        }
    }
    DisposableEffect(settings.showBattery) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            }
        }
        if (settings.showBattery) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            activity.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { activity.unregisterReceiver(receiver) } }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.scrim,
        topBar = {
            TopAppBar(
                title = { if (settings.showProgress) Text("${pagerState.currentPage + 1} / ${photos.itemCount}") },
                navigationIcon = { TextButton(onClick = navigator::pop) { Text("返回") } },
                actions = { IconButton(onClick = { isMenuVisible = true }) { Icon(Icons.Default.MoreVert, "菜单") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (settings.readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
                VerticalPager(state = pagerState, reverseLayout = false, modifier = Modifier.fillMaxSize()) { index ->
                    ViewerPhoto(photos[index], settings.pageScale, scale, offsetX, offsetY) { zoom, panX, panY ->
                        scale = zoom; offsetX += panX; offsetY += panY
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    reverseLayout = settings.readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                    pageSpacing = if (settings.showPageGap) 12.dp else 0.dp,
                    modifier = Modifier.fillMaxSize()
                ) { index ->
                    ViewerPhoto(photos[index], settings.pageScale, scale, offsetX, offsetY) { zoom, panX, panY ->
                        scale = zoom; offsetX += panX; offsetY += panY
                    }
                }
            }
            Column(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (settings.showClock) Text(currentTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")), color = MaterialTheme.colorScheme.onSurface)
                if (settings.showBattery && batteryLevel >= 0) Text("电量 $batteryLevel%", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (isMenuVisible) {
        PhotoViewerMenu(
            settings = settings,
            onConfirm = { viewModel.saveSettings(it); isMenuVisible = false },
            onDismiss = { isMenuVisible = false }
        )
    }
}

@Composable
private fun ViewerPhoto(
    photo: com.example.photomanager.data.database.PhotoEntity?,
    pageScale: PageScale,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (Float, Float, Float) -> Unit
) {
    if (photo == null) return
    val contentScale = when (pageScale) {
        PageScale.ORIGINAL -> ContentScale.None
        PageScale.FIT_WIDTH -> ContentScale.FillWidth
        PageScale.FIT_HEIGHT -> ContentScale.FillHeight
        else -> ContentScale.Fit
    }
    AsyncImage(
        model = photo.contentUri,
        contentDescription = photo.name,
        modifier = Modifier.fillMaxSize().pointerInput(photo.id) {
            detectTransformGestures { _, pan, zoom, _ ->
                onTransform((scale * zoom).coerceIn(1f, 4f), pan.x, pan.y)
            }
        }.graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offsetX; translationY = offsetY
        },
        contentScale = contentScale
    )
}
