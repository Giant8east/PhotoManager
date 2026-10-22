/** 阅读内容布局，按阅读方向选择横向或纵向 Pager，逻辑图片顺序与索引不随方向反转。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import com.example.photomanager.data.database.PhotoEntity

/** 开启页面间隔时相邻图片之间的距离。 */
private val PageGap = 12.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoViewerContent(
    photos: List<PhotoEntity>,
    pagerState: PagerState,
    settings: PhotoViewerSettings
) {
    val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
    else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val modifier = Modifier.fillMaxSize().windowInsetsPadding(imageInsets).clipToBounds()
    val pageSpacing = if (settings.showPageGap) PageGap else 0.dp
    val photoAt: @Composable (Int) -> Unit = { index ->
        PhotoViewerImage(photos[index], settings.pageScale)
    }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        if (settings.readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
            VerticalPager(
                state = pagerState,
                beyondBoundsPageCount = 1,
                pageSpacing = pageSpacing,
                modifier = modifier
            ) { index -> photoAt(index) }
        } else {
            HorizontalPager(
                state = pagerState,
                beyondBoundsPageCount = 1,
                reverseLayout = settings.readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                pageSpacing = pageSpacing,
                modifier = modifier
            ) { index -> photoAt(index) }
        }
    }
}
