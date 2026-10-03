package com.android.bilipai.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

private fun httpsUrl(raw: String): String = if (raw.startsWith("//")) "https:$raw" else raw

/**
 * 全页氛围背景：取当前轮播封面做高斯模糊铺底（Apple TV 式环境光）。
 * 以极小尺寸请求原图再拉伸，低版本系统没有 RenderEffect 时拉伸插值本身就是柔和的，
 * API 31+ 叠加 blur 获得真正的高斯效果；上盖深色渐变保证前景对比度。
 */
@Composable
internal fun TvAmbientBackdrop(imageUrl: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (imageUrl.isNullOrBlank()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        } else {
            val context = LocalContext.current
            if (LocalTvReduceMotion.current) {
                AmbientImage(imageUrl, context, Modifier.fillMaxSize())
            } else {
                Crossfade(targetState = imageUrl, label = "ambient") { url ->
                    AmbientImage(url, context, Modifier.fillMaxSize())
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x730B0D14), 0.55f to Color(0xB80B0D14), 1f to Color(0xF60B0D14),
            )))
        }
    }
}

@Composable
private fun AmbientImage(url: String, context: android.content.Context, modifier: Modifier) {
    val model = remember(url) {
        ImageRequest.Builder(context).data(httpsUrl(url)).size(48).build()
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        // 与 hero 同为 FillWidth + 顶对齐:两份图层逐像素对位,hero 渐隐处无缝衔接
        contentScale = ContentScale.FillWidth,
        alignment = Alignment.TopStart,
        modifier = modifier.blur(48.dp),
    )
}
