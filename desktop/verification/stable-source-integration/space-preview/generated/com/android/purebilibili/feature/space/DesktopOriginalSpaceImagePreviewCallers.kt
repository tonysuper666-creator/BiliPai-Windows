// GENERATED from app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt; do not edit.
// LF-normalized SHA-256: 2c7063c8c9b112b10f7b5394b34ddfc4362fcf2984d3eae3a3364469cc3557ca
package com.android.purebilibili.feature.space
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppLinearProgressIndicator
import com.android.purebilibili.feature.dynamic.components.resolveImagePreviewPlaceholderCacheKey
import com.android.purebilibili.data.model.response.SpaceTopImageItem
import com.android.purebilibili.feature.dynamic.components.ImagePreviewDialog
@Composable
internal fun DesktopOriginalSpaceAvatarPreview(showAvatarPreview:Boolean, avatarPreviewUrl:String, avatarSourceRect:Rect?, sourceKey:String?=null, onDismiss:()->Unit) {
    val density = LocalDensity.current
    val avatarCornerDp = avatarSourceRect?.let { rect ->
        with(density) { (minOf(rect.width, rect.height) / 2f).toDp().value }
    } ?: 40f
    if (showAvatarPreview && avatarPreviewUrl.isNotBlank()) {
        ImagePreviewDialog(
            images = listOf(avatarPreviewUrl),
            initialIndex = 0,
            sourceRect = avatarSourceRect,
            // 头像源是圆形，回位圆角取短边一半
            sourceCornerRadiusDp = avatarCornerDp,
            sourceKey = sourceKey,
            onDismiss = onDismiss
        )
    }
}
@Composable
internal fun DesktopOriginalSpaceTopPhotoPreview(showTopPhotoPreview:Boolean, topPhotoBannerUrl:String?, topImages:List<SpaceTopImageItem>?, previewUrl:String, topPhotoSourceRect:Rect?, sourceKey:String?=null, onDismiss:()->Unit) {
    val topPhotoPreviewImages = remember(topPhotoBannerUrl, topImages, previewUrl) {
        val topImages = topImages.orEmpty()
        if (topImages.isNotEmpty()) {
            topImages.map { normalizeSpaceTopPhotoUrl(it.header) }.filter { it.isNotBlank() }
        } else {
            listOf(previewUrl)
        }
    }
    val topPhotoPreviewIndex = topPhotoPreviewImages.indexOf(topPhotoBannerUrl).takeIf { it >= 0 } ?: 0
    val topPhotoPreviewEnabled = topPhotoPreviewImages.any { shouldEnableSpaceTopPhotoPreview(it) }
    if (showTopPhotoPreview && topPhotoPreviewEnabled) {
        ImagePreviewDialog(
            images = topPhotoPreviewImages,
            initialIndex = topPhotoPreviewIndex,
            sourceRect = topPhotoSourceRect,
            // hero 封面全出血无圆角
            sourceCornerRadiusDp = 0f,
            sourceKey = sourceKey,
            onDismiss = onDismiss
        )
    }
}
@Composable
internal fun DesktopOriginalSpaceHeaderBanner(
    topImages: List<com.android.purebilibili.data.model.response.SpaceTopImageItem>,
    fallbackTopPhotoUrl: String,
    onCurrentBannerUrlChange: (String?) -> Unit = {},
    skinBackgroundPaths: List<String> = emptyList(),
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    // 与 PiliPlus 一致：所有背景图统一做亮/暗色调色，保证顶栏与头像在任意封面上可读。
    val bannerColorFilter = resolveSpaceBannerColorFilter(isLight = !isDarkTheme)
    if (skinBackgroundPaths.isNotEmpty()) {
        LaunchedEffect(skinBackgroundPaths) { onCurrentBannerUrlChange(null) }
        val pagerState = rememberPagerState { skinBackgroundPaths.size }
        Box(modifier = modifier) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(skinBackgroundPaths[page])
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (skinBackgroundPaths.size > 1) {
                AppLinearProgressIndicator(
                    progress = { (pagerState.currentPage + 1f) / skinBackgroundPaths.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .align(Alignment.BottomCenter),
                    color = Color.White,
                    trackColor = Color(0x669E9E9E),
                )
            }
        }
    } else if (topImages.size > 1) {
        val pagerState = rememberPagerState { topImages.size }
        LaunchedEffect(pagerState.currentPage, topImages) {
            onCurrentBannerUrlChange(topImages.getOrNull(pagerState.currentPage)?.header)
        }
        Box(modifier = modifier) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = topImages[page]
                val alignment = resolveSpaceBannerAlignment(item.dy)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.header)
                        .memoryCacheKey(resolveImagePreviewPlaceholderCacheKey(item.header) ?: item.header)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = alignment,
                    colorFilter = bannerColorFilter,
                    modifier = Modifier.fillMaxSize()
                )
            }

            val currentTitle = topImages.getOrNull(pagerState.currentPage)?.title
            if (currentTitle != null && currentTitle.title.isNotBlank()) {
                SpaceHeaderTitleBadge(
                    title = currentTitle,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 4.dp)
                )
            }

            AppLinearProgressIndicator(
                progress = { (pagerState.currentPage + 1f) / topImages.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .align(Alignment.BottomCenter),
                color = Color.White,
                trackColor = Color(0x669E9E9E)
            )
        }
    } else if (topImages.size == 1) {
        val item = topImages[0]
        LaunchedEffect(item.header) { onCurrentBannerUrlChange(item.header) }
        val alignment = resolveSpaceBannerAlignment(item.dy)
        Box(modifier = modifier) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.header)
                    .memoryCacheKey(resolveImagePreviewPlaceholderCacheKey(item.header) ?: item.header)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                colorFilter = bannerColorFilter,
                modifier = Modifier.fillMaxSize()
            )
            if (item.title != null && item.title.title.isNotBlank()) {
                SpaceHeaderTitleBadge(
                    title = item.title,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 4.dp)
                )
            }
        }
    } else if (fallbackTopPhotoUrl.isNotBlank()) {
        LaunchedEffect(fallbackTopPhotoUrl) { onCurrentBannerUrlChange(fallbackTopPhotoUrl) }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(fallbackTopPhotoUrl)
                .memoryCacheKey(
                    resolveImagePreviewPlaceholderCacheKey(fallbackTopPhotoUrl) ?: fallbackTopPhotoUrl
                )
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            colorFilter = bannerColorFilter,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.56f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
        )
    }
}

@Composable
private fun SpaceHeaderTitleBadge(
    title: com.android.purebilibili.data.model.response.SpaceCollectionTopTitle,
    modifier: Modifier = Modifier,
) {
    val subTitleColor = remember(title.subTitleColorFormat) {
        val colorHex = title.subTitleColorFormat?.colors?.lastOrNull()
        if (!colorHex.isNullOrBlank()) {
            try {
                val hex = colorHex.removePrefix("#")
                if (hex.length == 6) {
                    Color(hex.toLong(16) or 0xFF000000)
                } else if (hex.length == 8) {
                    Color(hex.toLong(16))
                } else Color.White
            } catch (_: Exception) {
                Color.White
            }
        } else {
            Color.White
        }
    }

    Box(
        modifier = modifier
            .widthIn(max = 140.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.12f),
                        Color.Black.copy(alpha = 0.38f),
                        Color.Black.copy(alpha = 0.45f),
                    )
                )
            )
            .padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.End) {
            AppText(
                text = title.title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (title.subTitle.isNotBlank()) {
                AppText(
                    text = title.subTitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = subTitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
