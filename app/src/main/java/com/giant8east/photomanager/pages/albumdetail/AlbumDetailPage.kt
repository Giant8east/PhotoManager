/** 相册详情整页滚动，画廊使用完整图片索引，缩略图由惰性网格按需加载。 */
package com.giant8east.photomanager.pages.albumdetail

import android.annotation.SuppressLint
import com.giant8east.photomanager.ui.state.PageContent
import android.text.format.Formatter
import com.giant8east.photomanager.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.imageLoader
import coil.memory.MemoryCache
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.media.PhotoFormat
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.navigation.LocalIsNavTransitioning
import com.giant8east.photomanager.pages.common.AppLazyColumnScrollbar
import com.giant8east.photomanager.pages.common.HeroCoverImage
import com.giant8east.photomanager.pages.common.ThumbnailImage
import com.giant8east.photomanager.ui.format.rememberAlbumModifiedTimeText
import com.giant8east.photomanager.ui.theme.appColors
import com.giant8east.photomanager.pages.photoviewer.PhotoViewerPage
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val THUMBNAIL_DECODE_WIDTH_PX = 360
private const val THUMBNAIL_DECODE_HEIGHT_PX = 540
// 详情页分阶段错峰加载：网格固定在 Hero 显示后再延后 1s 加载；Hero 延后时长由调用方按本地/远端分别指定
private const val GRID_REVEAL_DELAY_MILLIS = 500L

private enum class DetailRevealStage {
    LOADING,
    HERO_ONLY,
    GRID_READY
}

private fun photoThumbnailCacheKey(thumbnailUri: String, modifiedAt: String?): String =
    "$thumbnailUri|$modifiedAt|gallery-static"

@Composable
fun AlbumDetailPage(
    navigator: AppNavigator,
    album: AlbumEntity,
    viewModel: AlbumDetailViewModel = hiltViewModel<AlbumDetailViewModel, AlbumDetailViewModel.Factory>(
        creationCallback = { it.create(album.id, album.albumPath) }
    )
) {
    BackHandler { navigator.pop() }
    PageContent(viewModel.state, onRetry = viewModel::retryLoad) { content ->
        AlbumDetailContent(
            navigator = navigator,
            content = content,
            // 局域网下载来的相册显示来源设备名，本机相册显示"本地"
            sourceLabel = content.album.sourceName.ifEmpty { stringResource(R.string.album_detail_local) },
            onRefreshDirectory = viewModel::refreshDirectory,
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleHidden = viewModel::toggleHidden,
            onOpenViewer = { index ->
                navigator.pushTo(useSafeArea = false) {
                    PhotoViewerPage(navigator, content.album.id, index)
                }
            }
        )
    }
}

/**
 * 详情页正文，本地与远端共用同一套布局：封面、标题、操作卡、统计、画廊网格。
 * 远端相册只把数据换成在线地址，并通过 [actionsEnabled] 关闭收藏与刷新。
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun AlbumDetailContent(
    navigator: AppNavigator,
    content: AlbumDetailUiState,
    sourceLabel: String,
    onRefreshDirectory: ((Boolean) -> Unit) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenViewer: (Int) -> Unit,
    actionsEnabled: Boolean = true,
    onToggleHidden: () -> Unit = {},
    // 远端详情页可将左侧"收藏"按钮替换为"下载 / 已下载"按钮
    primaryActionLabel: String? = null,
    primaryActionEnabled: Boolean = actionsEnabled,
    onPrimaryAction: (() -> Unit)? = null,
    // 未命中缓存时转场结束后展示 Hero 区域的等待时长（本地 0ms，远端 1000ms）
    heroRevealDelayMillis: Long = 0L,
    // 网格预览图地址：默认直接使用原图路径，远端页通过 ?preview=true 请求降采样缩略图
    thumbnailUriFor: (PhotoEntity) -> String = { it.contentUri }
) {
    val currentAlbum = content.album
    val photos = content.photos
    val colors = MaterialTheme.colorScheme

    // 普通正文不继承宿主或按钮的文字颜色；标题栏与操作区域再按背景覆盖。
    CompositionLocalProvider(
        LocalContentColor provides colors.onBackground,
        LocalTextStyle provides TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 手动计算列数
            val density = LocalDensity.current
            val columns = with(density) {
                galleryColumnCount(
                    maxWidth.roundToPx() - GALLERY_HORIZONTAL_PADDING.roundToPx() * 2,
                    GALLERY_TARGET_COLUMN_WIDTH.roundToPx()
                )
            }

            val context = LocalContext.current
            val isTransitioning = LocalIsNavTransitioning.current
            // 分级检查 Coil 内存缓存：
            // 1. 首张网格缩略图已缓存 -> 直接 GRID_READY（Hero + 网格带图入场）；
            // 2. 网格未缓存但 Hero 封面已缓存（上次只看到 Hero 就退出）-> 直接 HERO_ONLY，不再重新 loading；
            // 3. 均未缓存 -> LOADING，转场结束后按 heroRevealDelayMillis（本地 0ms / 远端 1s）显 Hero，再过 1s 显网格。
            var revealStage by remember {
                val memoryCache = context.imageLoader.memoryCache
                val firstPhoto = photos.firstOrNull()
                val hasGridCache = firstPhoto != null &&
                    memoryCache?.get(
                        MemoryCache.Key(
                            photoThumbnailCacheKey(thumbnailUriFor(firstPhoto), firstPhoto.modifiedAt)
                        )
                    ) != null
                val hasHeroCache = currentAlbum.coverPath.isNotEmpty() &&
                    memoryCache?.get(
                        MemoryCache.Key("${currentAlbum.coverPath}|detail-cover-animated")
                    ) != null
                mutableStateOf(
                    when {
                        hasGridCache -> DetailRevealStage.GRID_READY
                        hasHeroCache -> DetailRevealStage.HERO_ONLY
                        else -> DetailRevealStage.LOADING
                    }
                )
            }
            LaunchedEffect(isTransitioning) {
                if (isTransitioning) return@LaunchedEffect
                if (revealStage == DetailRevealStage.LOADING) {
                    if (heroRevealDelayMillis > 0L) delay(heroRevealDelayMillis.milliseconds)
                    revealStage = DetailRevealStage.HERO_ONLY
                }
                if (revealStage == DetailRevealStage.HERO_ONLY) {
                    delay(GRID_REVEAL_DELAY_MILLIS.milliseconds)
                    revealStage = DetailRevealStage.GRID_READY
                }
            }

            if (revealStage == DetailRevealStage.LOADING) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val listState = rememberLazyListState()
                AppLazyColumnScrollbar(state = listState) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        item(key = "header") {
                            AlbumDetailHeader(
                                album = currentAlbum,
                                sourceLabel = sourceLabel,
                                navigator = navigator,
                                onRefreshDirectory = onRefreshDirectory,
                                onToggleFavorite = onToggleFavorite,
                                onToggleHidden = onToggleHidden,
                                primaryActionLabel = primaryActionLabel,
                                primaryActionEnabled = primaryActionEnabled,
                                onPrimaryAction = onPrimaryAction,
                                onOpenViewer = onOpenViewer,
                                actionsEnabled = actionsEnabled
                            )
                        }

                        if (revealStage == DetailRevealStage.GRID_READY) {
                            photoGridRows(
                                items = photos,
                                columns = columns,
                                itemKey = { it.id }
                            ) { index, photo ->
                                Column(
                                    Modifier.clickable { onOpenViewer(index) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    PhotoThumbnail(
                                        photo = photo,
                                        thumbnailUri = thumbnailUriFor(photo)
                                    )
                                    Text(
                                        stringResource(R.string.album_detail_photo_index, index + 1),
                                        color = colors.onBackground, fontSize = 14.sp, lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun PhotoThumbnail(
    photo: PhotoEntity,
    thumbnailUri: String
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2 / 3f)
    ) {
        ThumbnailImage(
            uri = thumbnailUri,
            decodeWidthPx = THUMBNAIL_DECODE_WIDTH_PX,
            decodeHeightPx = THUMBNAIL_DECODE_HEIGHT_PX,
            cacheVersionKey = photo.modifiedAt,
            memoryCacheKey = photoThumbnailCacheKey(thumbnailUri, photo.modifiedAt),
            // 等价于 { w, h -> galleryThumbnailScale(w, h) }
            contentScaleFor = ::galleryThumbnailScale,
            modifier = Modifier.fillMaxSize()
        )
        PhotoFormatBadge(
            format = photo.format,
            isAnimated = photo.isAnimated || photo.format == PhotoFormat.GIF,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
        )
    }
}

@Composable
private fun AlbumDetailHeader(
    album: AlbumEntity,
    sourceLabel: String,
    navigator: AppNavigator,
    onRefreshDirectory: ((Boolean) -> Unit) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHidden: () -> Unit,
    primaryActionLabel: String?,
    primaryActionEnabled: Boolean,
    onPrimaryAction: (() -> Unit)?,
    onOpenViewer: (Int) -> Unit,
    actionsEnabled: Boolean
) {
    val lastViewedIndex = album.lastViewedPhotoIndex

    Column {
        Box {
            Column(Modifier.matchParentSize()
            ) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .shadow(elevation = 4.dp, clip = false)
                        .background(appColors.headerBackground)
                )
                Spacer(Modifier.height(24.dp))
            }
            Column { Row(Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    HeroCoverImage(
                        uri = album.coverPath,
                        localAlbumPath = album.albumPath,
                        coverVersionKey = album.modifiedAt,
                        modifier = Modifier.size(width = 128.dp, height = 192.dp)
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp)
                    ) {
                        Text(
                            album.name, Modifier.fillMaxWidth().padding(end = 16.dp),
                            color = appColors.headerContent,
                            style = TextStyle(fontSize = 20.sp, lineHeight = 24.sp),
                            maxLines = 5, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                sourceLabel,
                                color = appColors.headerContent,
                                style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
                            )
                            if (album.isHidden) {
                                Icon(
                                    imageVector = Icons.Outlined.VisibilityOff,
                                    contentDescription = stringResource(R.string.album_hidden_badge),
                                    tint = appColors.headerContent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(2.dp),
                    colors = CardDefaults.cardColors(containerColor = appColors.cardBackground),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onPrimaryAction ?: onToggleFavorite,
                            enabled = primaryActionEnabled,
                            shape = RoundedCornerShape(0.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Text(
                                primaryActionLabel ?: stringResource(
                                    if (album.isFavorite) R.string.album_favorite_remove
                                    else R.string.album_favorite_add
                                ),
                                color = appColors.actionText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        TextButton(
                            onClick = { onOpenViewer(lastViewedIndex) },
                            shape = RoundedCornerShape(0.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Text(stringResource(R.string.album_detail_read),
                                color = appColors.accentActionText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }


            if (actionsEnabled) {
                DropdownMenuButton(
                    modifier = Modifier.align(Alignment.TopEnd), tint = appColors.headerContent
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_detail_refresh)) },
                        onClick = { dismiss()
                            onRefreshDirectory { isAlbumAvailable ->
                                if (!isAlbumAvailable) navigator.pop()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (album.isHidden) R.string.album_action_unhide
                                    else R.string.album_action_hide
                                )
                            )
                        },
                        onClick = {
                            dismiss()
                            onToggleHidden()
                        }
                    )
                }
            }

        }
        DetailDivider()
    }

    Column(
        Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(
                R.string.album_detail_progress,
                lastViewedIndex + 1,
                album.photoCount
                )
            )
            Text(Formatter.formatFileSize(LocalContext.current, album.totalSizeBytes))
        }
        Text(stringResource(
                R.string.album_detail_modified,
                rememberAlbumModifiedTimeText(album.modifiedAt)
            ),
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        )
    }

    Column {
        DetailDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.album_detail_no_tags),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = appColors.accentActionText,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
            )
        }
        DetailDivider()
    }
    Spacer(Modifier.height(16.dp))
}
