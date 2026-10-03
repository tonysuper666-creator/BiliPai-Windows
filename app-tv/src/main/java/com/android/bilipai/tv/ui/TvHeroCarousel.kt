package com.android.bilipai.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.android.bilipai.tv.ui.components.TvAppButton
import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val HERO_AUTO_ADVANCE_MS = 7_000L
private val HeroHeight = 292.dp

/**
 * 首页全幅轮播 banner：推荐流前若干个视频，底部渐变上叠标题与主操作。
 * 自动轮播 7s；banner 区域持有焦点或减少动画时暂停。切换向上层上报封面用于氛围背景。
 * 播放按钮是内容区左边界：按左键经 [navigationFocus] 呼出悬浮侧栏。
 */
@Composable
internal fun TvHeroCarousel(
    items: List<VideoItem>,
    navigationFocus: FocusRequester,
    onPlay: (VideoItem) -> Unit,
    onOpen: (VideoItem) -> Unit,
    onAmbientChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalTvReduceMotion.current
    val heroItems = remember(items) { items.take(6) }
    var index by remember(heroItems) { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    val current = heroItems.getOrNull(index)

    LaunchedEffect(current?.pic) { onAmbientChange(current?.pic) }

    LaunchedEffect(heroItems, reduceMotion, heroFocused) {
        if (reduceMotion || heroFocused || heroItems.size < 2) return@LaunchedEffect
        while (isActive) {
            delay(HERO_AUTO_ADVANCE_MS)
            index = (index + 1) % heroItems.size
        }
    }

    Box(modifier.fillMaxWidth().height(HeroHeight).testTag("tv-hero")) {
        // hero 是全屏氛围图上的一个"窗口":两侧同为 FillWidth 顶对齐,几何逐像素对位;
        // 图像底部经 DstIn alpha 渐隐露出模糊层,不再叠加终止性暗色渐变,断层即消失
        HeroImage(current?.pic, reduceMotion, Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(0.42f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            })
        Column(Modifier.align(Alignment.BottomStart).padding(start = TvUiTokens.pagePadding, end = TvUiTokens.pagePadding, bottom = 22.dp)) {
            Text(
                text = current?.title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    shadow = Shadow(color = Color(0xB3000000), blurRadius = 18f),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
                TvAppButton(
                    onClick = { current?.let(onPlay) },
                    modifier = Modifier
                        .onFocusChanged { heroFocused = it.hasFocus }
                        .focusProperties { left = navigationFocus },
                ) { Text("播放") }
                TvAppButton(onClick = { current?.let(onOpen) }) { Text("查看详情") }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            heroItems.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .size(width = if (i == index) 20.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(if (i == index) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF)),
                )
            }
        }
    }
}

@Composable
private fun HeroImage(url: String?, reduceMotion: Boolean, modifier: Modifier) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    if (reduceMotion) {
        HeroAsyncImage(url, context, modifier)
    } else {
        Crossfade(targetState = url, animationSpec = tween(600, easing = EaseOut), label = "hero") { target ->
            HeroAsyncImage(target, context, modifier)
        }
    }
}

@Composable
private fun HeroAsyncImage(url: String, context: android.content.Context, modifier: Modifier) {
    val model = remember(url) {
        ImageRequest.Builder(context)
            .data(if (url.startsWith("//")) "https:$url" else url)
            .build()
    }
    // FillWidth + 顶对齐:与氛围层同几何,hero 成为全屏图上的裁切窗口
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        alignment = Alignment.TopStart,
        modifier = modifier,
    )
}
