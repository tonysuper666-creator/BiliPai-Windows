package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.LocalAppThemeConfig

/**
 * Keep the visible dock viewport rounded for every chrome mode.
 *
 * A long liquid rail is wider than the viewport that hosts it. Its shared renderer draws a
 * fixed capsule behind the moving content; this clip keeps labels and the indicator inside
 * that visible capsule instead of leaking across its rounded ends. [verticalOverflow] reserves
 * the shared indicator geometry's bloom without increasing the horizontal viewport.
 */
@Composable
internal fun Modifier.liquidDockViewport(verticalOverflow: Dp = 0.dp): Modifier {
    val uiStyle = LocalAppUiStyle.current
    val liquidGlassEnabled = LocalAppThemeConfig.current.liquidGlassEnabled
    val shape = if (liquidGlassEnabled) {
        CircleShape
    } else if (uiStyle == AppUiStyle.MIUIX) {
        AppShapes.container(ContainerLevel.Card)
    } else {
        CircleShape
    }
    if (!liquidGlassEnabled || verticalOverflow <= 0.dp) return this.clip(shape)
    // Keep the horizontal viewport fixed, but include the indicator's press bloom vertically.
    // A canvas clip avoids introducing another shell-height graphicsLayer around the tall rail.
    return this.drawWithCache {
        val overflowPx = verticalOverflow.toPx()
        val outline = shape.createOutline(
            Size(size.width, size.height + overflowPx * 2f), layoutDirection, this,
        )
        val path = Path().apply {
            addOutline(outline)
            translate(Offset(0f, -overflowPx))
        }
        onDrawWithContent {
            clipPath(path) { this@onDrawWithContent.drawContent() }
        }
    }
}
