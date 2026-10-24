/** 阅读交互入口，组装固定视口、手势识别、页面布局与边缘绘制。 */
package com.example.photomanager.pages.photoviewer.reader

import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdgeView

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import android.view.ViewConfiguration
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import com.example.photomanager.data.PhotoMetadataReader
import com.example.photomanager.data.database.PhotoEntity

internal class PhotoViewerPager(
    private val layout: PhotoViewerLayoutManager<*>
) {
    private val gestures = PhotoViewerGestureRecognizer(
        layout::onDown, layout::onPosition, layout::onDrag,
        layout::onScale, layout::onUp, layout::onDoubleTap
    )
    val currentPage get() = layout.currentPage
    val settledPage get() = layout.settledPage
    val pageCount get() = layout.pageCount
    val isContinuous get() = layout is PhotoViewerScrollLayoutManager
    val currentItemOffset
        get() = (layout as? PhotoViewerScrollLayoutManager)?.currentItemOffset ?: 0

    fun isPageVisible(index: Int) = layout.isPageVisible(index)
    fun attachImage(index: Int, transform: PhotoTransform) = layout.attachImage(index, transform)
    fun detachImage(index: Int, transform: PhotoTransform) = layout.detachImage(index, transform)
    suspend fun goToPage(index: Int) = layout.goToPage(index)
    suspend fun nextPage() = layout.nextPage()
    suspend fun previousPage() = layout.previousPage()
    fun dispose() = layout.dispose()

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content(
        settings: PhotoViewerSettings,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
        pageContent: @Composable (Int) -> Unit
    ) {
        val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
        else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        val currentOnTap by rememberUpdatedState(onTap)
        val currentOnLongPress by rememberUpdatedState(onLongPress)
        val tap = remember { { currentOnTap() } }
        val longPress = remember { { currentOnLongPress() } }
        val viewport = with(gestures) {
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(imageInsets)
                .clipToBounds()
                .onSizeChanged(layout::resizeViewport)
                .recognize(tap, longPress)
        }
        Box(viewport) {
            layout.Content(settings, pageContent)
            PhotoViewerEdgeView(Modifier.fillMaxSize(), layout.edges)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPhotoViewerPager(
    initialIndex: Int,
    photos: List<PhotoEntity>,
    readingDirection: ReadingDirection,
    onEndReached: () -> Unit
): PhotoViewerPager {
    var lastIndex by remember { mutableIntStateOf(initialIndex) }
    var verticalIndex by remember { mutableIntStateOf(initialIndex) }
    var verticalOffset by remember { mutableIntStateOf(0) }
    val animationScope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = ViewConfiguration.get(context)
    val metadata =
        remember(context.applicationContext) { PhotoMetadataReader(context.applicationContext) }
    val currentOnEndReached by rememberUpdatedState(onEndReached)
    val currentPhotos by rememberUpdatedState(photos)
    return key(readingDirection) {
        val layout: PhotoViewerLayoutManager<*> =
            if (readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
                val listState = rememberLazyListState(
                    lastIndex,
                    if (verticalIndex == lastIndex) verticalOffset else 0
                )
                remember(listState) {
                    PhotoViewerScrollLayoutManager(
                        listState,
                        { currentPhotos },
                        metadata,
                        animationScope,
                        { currentOnEndReached() },
                        configuration.scaledMinimumFlingVelocity.toFloat(),
                        configuration.scaledMaximumFlingVelocity.toFloat()
                    )
                }
            } else {
                val pagerState =
                    rememberPagerState(initialPage = lastIndex, pageCount = { currentPhotos.size })
                remember(pagerState) {
                    PhotoViewerPagerLayoutManager(
                        pagerState,
                        readingDirection,
                        animationScope
                    ) { currentOnEndReached() }.apply {
                        minimumFlingVelocity = configuration.scaledMinimumFlingVelocity.toFloat()
                        maximumFlingVelocity = configuration.scaledMaximumFlingVelocity.toFloat()
                    }
                }
            }
        val reader = remember(layout) { PhotoViewerPager(layout) }
        val currentIndex = layout.currentPage
        val itemOffset = (layout as? PhotoViewerScrollLayoutManager)?.unscaledItemOffset ?: 0
        SideEffect {
            lastIndex = currentIndex
            if (layout is PhotoViewerScrollLayoutManager) {
                verticalIndex = currentIndex
                verticalOffset = itemOffset
            }
        }
        DisposableEffect(reader) { onDispose { reader.dispose() } }
        reader
    }
}
