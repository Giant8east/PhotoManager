/** 阅读内容布局，按阅读方向选择横向或纵向 Pager，逻辑图片顺序与索引不随方向反转。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import com.example.photomanager.data.database.PhotoEntity

/** 开启页面间隔时相邻图片之间的距离。 */
private val PageGap = 12.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerContent(
    photos: List<PhotoEntity>,
    pagerState: PagerState,
    settings: PhotoViewerSettings,
    modifier: Modifier = Modifier
) {
    val pageSpacing = if (settings.showPageGap) PageGap else 0.dp
    val photoAt: @Composable (Int) -> Unit = { index ->
        PhotoViewerImage(photos[index], settings.pageScale)
    }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        if (settings.readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
            VerticalPager(
                state = pagerState,
                pageSpacing = pageSpacing,
                modifier = modifier
            ) { index -> photoAt(index) }
        } else {
            HorizontalPager(
                state = pagerState,
                reverseLayout = settings.readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                pageSpacing = pageSpacing,
                modifier = modifier
            ) { index -> photoAt(index) }
        }
    }
}
