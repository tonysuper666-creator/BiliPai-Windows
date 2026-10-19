package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/*
 * The shared overlay scrollbar of the desktop app (spec 5.1), replacing the two private copies of the rail
 * (DesktopOriginalRootChrome) and of the player menu (DesktopWindowsPlayerMenu), which are not edited by this slice.
 * Both copies used a 4 dp thumb at alpha 0.28 and 0.60; the kit thumb is 8 dp wide at the same two alphas.
 *
 * The Compose scrollbar sizes its layout box and its thumb with one number, so the hit strip is made the thumb: the
 * style thickness is the 12 dp strip (a drag or a track press anywhere in it counts) and the thumb shape draws only
 * the centred 8 dp, snapped to whole pixels. The scrollbar is an overlay: place it as a child of the Box that holds the
 * scrolling content, and it aligns itself to the end edge. It composes nothing while the content fits.
 */

/** The thumb: the layout box is the whole 12 dp strip and only the centred 8 dp is drawn, in whole pixels. */
private object DesktopKitScrollbarThumbShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val thumb = desktopKitScrollbarThumbPx(density.density, size.width)
        val inset = desktopKitScrollbarThumbInsetPx(size.width, thumb)
        return Outline.Rounded(
            RoundRect(rect = Rect(inset, 0f, inset + thumb, size.height), cornerRadius = CornerRadius(thumb / 2f)),
        )
    }
}

/** The kit scrollbar style: 12 dp strip, 8 dp thumb, onSurface at the idle and hover alphas, no fade under reduced motion. */
@Composable
internal fun desktopKitScrollbarStyle(): ScrollbarStyle {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalDesktopKitReducedMotion.current
    return defaultScrollbarStyle().copy(
        minimalHeight = DESKTOP_KIT_HIT_FLOOR_DP.dp,
        thickness = DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP.dp,
        shape = DesktopKitScrollbarThumbShape,
        hoverDurationMillis = desktopKitMotionMs(reduced, DESKTOP_KIT_MOTION_SLOW_MS),
        unhoverColor = colors.onSurface.copy(alpha = DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT / 100f),
        hoverColor = colors.onSurface.copy(alpha = DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT / 100f),
    )
}

/** The overlay scrollbar for any adapter. The adapter is the v2 interface, which a staggered grid can implement itself. */
@Composable
internal fun BoxScope.DesktopKitVerticalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    val needed by remember(adapter) { derivedStateOf { desktopKitScrollbarNeeded(adapter.contentSize, adapter.viewportSize) } }
    if (!needed) return
    VerticalScrollbar(
        adapter = adapter,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().then(modifier),
        style = desktopKitScrollbarStyle(),
    )
}

@Composable
internal fun BoxScope.DesktopKitVerticalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    DesktopKitVerticalScrollbar(adapter = rememberScrollbarAdapter(scrollState), modifier = modifier)
}

@Composable
internal fun BoxScope.DesktopKitVerticalScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    DesktopKitVerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = modifier)
}
