package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import java.awt.Dialog
import java.awt.Window
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** UI pixels only. A fresh existing binding gets a fresh projection; this
 * grants no source/account authority and retains no LayoutCoordinates. */
internal class DesktopWindowsVideoFeedbackBounds {
    var video: Rect? by mutableStateOf(null)
        private set
    var like: Rect? by mutableStateOf(null)
        private set
    private var likeMount: Any? = null
    fun reportVideo(rect: Rect) { video = desktopWindowsFeedbackFiniteRect(rect) }
    fun mountLike(token: Any) { likeMount = token; like = null }
    fun reportLike(token: Any, rect: Rect?) {
        if (likeMount === token) like = rect?.let(::desktopWindowsFeedbackFiniteRect)
    }
    fun releaseLike(token: Any) {
        if (likeMount === token) { likeMount = null; like = null }
    }
}

internal fun desktopWindowsFeedbackFiniteRect(rect: Rect): Rect? =
    rect.takeIf { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
        it.width > 0f && it.height > 0f }

@Composable
internal fun Modifier.desktopWindowsFeedbackLikeAnchor(bounds: DesktopWindowsVideoFeedbackBounds): Modifier {
    val token = remember(bounds) { Any() }
    DisposableEffect(bounds, token) {
        bounds.mountLike(token)
        onDispose { bounds.releaseLike(token) }
    }
    return onGloballyPositioned { coordinates ->
        val start = coordinates.positionInWindow()
        val rect = Rect(start.x, start.y, start.x + coordinates.size.width, start.y + coordinates.size.height)
        val visible = coordinates.boundsInWindow()
        // A scrolled/clipped button cannot leave a phantom anchor.
        bounds.reportLike(token, rect.takeIf {
            visible.width > 0f && visible.height > 0f && visible.left <= rect.left + .5f &&
                visible.top <= rect.top + .5f && visible.right >= rect.right - .5f &&
                visible.bottom >= rect.bottom - .5f
        })
    }
}

internal fun desktopWindowsFeedbackClippedRect(rect: Rect?, widthPx: Int, heightPx: Int): Rect? {
    val actual = rect?.let(::desktopWindowsFeedbackFiniteRect) ?: return null
    if (widthPx <= 0 || heightPx <= 0) return null
    val clipped = Rect(actual.left.coerceIn(0f, widthPx.toFloat()), actual.top.coerceIn(0f, heightPx.toFloat()),
        actual.right.coerceIn(0f, widthPx.toFloat()), actual.bottom.coerceIn(0f, heightPx.toFloat()))
    return desktopWindowsFeedbackFiniteRect(clipped)
}

/** Same physical window pixels as the measured button. There is no OS-DPI or
 * app-density division. Original animation dp size uses its own density once. */
@Composable
internal fun DesktopWindowsFeedbackRectFrame(rect: Rect, content: @Composable BoxScope.() -> Unit) {
    Layout(content = { Box(Modifier.fillMaxSize().clipToBounds(), content = content) }) { children, limits ->
        val left = rect.left.roundToInt().coerceIn(0, limits.maxWidth)
        val top = rect.top.roundToInt().coerceIn(0, limits.maxHeight)
        val right = rect.right.roundToInt().coerceIn(left, limits.maxWidth)
        val bottom = rect.bottom.roundToInt().coerceIn(top, limits.maxHeight)
        val child = children.single().measure(Constraints.fixed(right - left, bottom - top))
        layout(limits.maxWidth, limits.maxHeight) { child.place(left, top) }
    }
}

/** Only this exact Java owner chain. Compose DOCUMENT_MODAL, Swing chooser
 * JDialog and AWT FileDialog are Dialogs; external share HWNDs are not scanned. */
internal fun desktopWindowsFeedbackHasOwnedModal(owner: Window): Boolean {
    check(SwingUtilities.isEventDispatchThread())
    fun contains(parent: Window): Boolean = parent.ownedWindows.any { child ->
        (child is Dialog && child.isShowing && child.isModal) || contains(child)
    }
    return contains(owner)
}
