/** 阅读交互入口，组装固定视口、手势识别、页面布局与边缘绘制。 */
package com.giant8east.photomanager.pages.photoviewer.reader

import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import com.giant8east.photomanager.data.settings.PhotoViewerSettings
import com.giant8east.photomanager.data.settings.ReadingDirection
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.pages.photoviewer.edge.PhotoViewerEdgeView

internal class PhotoViewerPager(
    private val layout: PhotoViewerLayoutManager
) {
    private val gestures = PhotoViewerGestureRecognizer(
        layout::onDown, layout::onPosition, layout::onDrag,
        layout::onScale, layout::onUp, layout::onDoubleTap
    )
    val currentPage get() = layout.currentPage
    val settledPage get() = layout.settledPage
    val pageCount get() = layout.pageCount
    val isContinuous get() = layout.isContinuous

    fun isPageVisible(index: Int) = layout.isPageVisible(index)
    fun attachImage(index: Int, transform: PhotoTransform) = layout.attachImage(index, transform)
    fun onImageSizeChanged(index: Int, photo: PhotoEntity, size: Size) =
        layout.onImageSizeChanged(index, photo, size)
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

private class ReaderPositionMemory(var index: Int, var verticalOffset: Int = 0)

@Composable
internal fun rememberPhotoViewerPager(
    initialIndex: Int,
    photos: List<PhotoEntity>,
    readingDirection: ReadingDirection,
    onEndReached: () -> Unit
): PhotoViewerPager {
    // 仅用于在切换阅读方向重建布局时继承当前页码与纵向偏移，无需使用可变 Compose State 触发额外重组。
    val memory = remember { ReaderPositionMemory(initialIndex) }
    val animationScope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = ViewConfiguration.get(context)
    val minFlingVelocity = configuration.scaledMinimumFlingVelocity.toFloat()
    val maxFlingVelocity = configuration.scaledMaximumFlingVelocity.toFloat()
    val currentOnEndReached by rememberUpdatedState(onEndReached)
    val currentPhotos by rememberUpdatedState(photos)
    return key(readingDirection) {
        val layout: PhotoViewerLayoutManager =
            if (readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
                val listState = rememberLazyListState(memory.index, memory.verticalOffset)
                remember(listState) {
                    PhotoViewerScrollLayoutManager(
                        listState = listState,
                        photos = { currentPhotos },
                        scope = animationScope,
                        onEndReached = { currentOnEndReached() },
                        minimumVelocity = minFlingVelocity,
                        maximumVelocity = maxFlingVelocity
                    )
                }
            } else {
                val pagerState =
                    rememberPagerState(initialPage = memory.index, pageCount = { currentPhotos.size })
                remember(pagerState) {
                    PhotoViewerPagerLayoutManager(
                        pagerState = pagerState,
                        readingDirection = readingDirection,
                        animationScope = animationScope,
                        minimumVelocity = minFlingVelocity,
                        maximumVelocity = maxFlingVelocity,
                        onEndReached = { currentOnEndReached() }
                    )
                }
            }
        val reader = remember(layout) { PhotoViewerPager(layout) }
        val currentIndex = layout.currentPage
        val unscaledOffset = layout.unscaledItemOffset
        SideEffect {
            memory.index = currentIndex
            memory.verticalOffset = unscaledOffset
        }
        DisposableEffect(reader) { onDispose { reader.dispose() } }
        reader
    }
}
