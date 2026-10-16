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
import androidx.compose.ui.unit.isSpecified
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.home.components.resolveFloatingDockCaptureInsets
import com.android.purebilibili.feature.home.components.FloatingBottomBarGeometryMode

/**
 * Keep the visible dock viewport rounded for every chrome mode.
 *
 * A long liquid rail is wider than the viewport that hosts it. Its shared renderer draws a
 * fixed capsule behind the moving content. Expand its drawing clip for indicator bloom
 * in both axes while retaining the original scroll layout and pointer viewport.
 */
@Composable
internal fun Modifier.liquidDockViewport(
    verticalOverflow: Dp = Dp.Unspecified,
    horizontalOverflow: Dp = Dp.Unspecified,
): Modifier {
    val uiStyle = LocalAppUiStyle.current
    val liquidGlassEnabled = LocalAppThemeConfig.current.liquidGlassEnabled
    val shape = if (liquidGlassEnabled) {
        CircleShape
    } else if (uiStyle == AppUiStyle.MIUIX) {
        AppShapes.container(ContainerLevel.Card)
    } else {
        CircleShape
    }
    if (!liquidGlassEnabled || (verticalOverflow.isSpecified && verticalOverflow <= 0.dp)) {
        return this.clip(shape)
    }
    // Expand only the drawing clip, not the scroll layout or its pointer viewport.
    return this.drawWithCache {
        val overflowPx = if (verticalOverflow.isSpecified) {
            verticalOverflow.toPx()
        } else {
            val shellHeight = size.height.toDp()
            resolveFloatingDockCaptureInsets(
                shellHeightDp = shellHeight.value,
                requestedIndicatorHeightDp = shellHeight.value,
                indicatorWidthDp = size.width.toDp().value,
                geometryMode = FloatingBottomBarGeometryMode.Segmented,
            ).verticalDp.dp.toPx()
        }
        val horizontalOverflowPx = if (horizontalOverflow.isSpecified) {
            horizontalOverflow.toPx().coerceAtLeast(0f)
        } else {
            val shellHeight = size.height.toDp()
            resolveFloatingDockCaptureInsets(
                shellHeightDp = shellHeight.value,
                requestedIndicatorHeightDp = shellHeight.value,
                indicatorWidthDp = minOf(size.width.toDp().value, shellHeight.value * 1.8f),
                geometryMode = FloatingBottomBarGeometryMode.Segmented,
            ).horizontalDp.dp.toPx()
        }
        val outline = shape.createOutline(
            Size(size.width + horizontalOverflowPx * 2f, size.height + overflowPx * 2f),
            layoutDirection,
            this,
        )
        val path = Path().apply {
            addOutline(outline)
            translate(Offset(-horizontalOverflowPx, -overflowPx))
        }
        onDrawWithContent {
            clipPath(path) { this@onDrawWithContent.drawContent() }
        }
    }
}
