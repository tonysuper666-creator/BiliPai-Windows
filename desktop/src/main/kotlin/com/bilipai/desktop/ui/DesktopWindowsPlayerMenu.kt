package com.bilipai.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.android.purebilibili.core.ui.components.AppSurface
import java.awt.Dialog
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.WindowConstants
import kotlin.math.roundToInt

internal val LocalDesktopWindowsPlayerWindow = staticCompositionLocalOf<Window?> { null }

/** A fixed-size, modeless native popover over the heavyweight video. No content or
 * draw-bounds callback resizes a Skia layer. Actions keep their existing source gates. */
@Composable
internal fun DesktopWindowsPlayerMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    preferredHeight: Dp = 320.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    var anchor by remember { mutableStateOf<Offset?>(null) }
    // The surrounding button Box is measured while closed as well. Its right/top
    // screen coordinate includes OS DPI; app zoom must not divide screen coordinates.
    Box(Modifier.size(0.dp).onGloballyPositioned { coordinates ->
        val parent = coordinates.parentLayoutCoordinates
        if (parent != null && parent.isAttached) {
            val actual = parent.localToScreen(Offset(parent.size.width.toFloat(), 0f))
            if (actual.x.isFinite() && actual.y.isFinite() && anchor != actual) anchor = actual
        }
    })
    if (!expanded) return
    val owner = LocalDesktopWindowsPlayerWindow.current ?: return
    val actualAnchor = anchor ?: return
    if (!owner.isShowing) return
    val parentDensity = LocalDensity.current
    val parentInfo = LocalWindowInfo.current
    val parentSize = parentInfo.containerSize
    val systemDensity = parentSize.width / parentInfo.containerDpSize.width.value.coerceAtLeast(1f)
    val windowScale = parentDensity.density / systemDensity.coerceAtLeast(.1f)
    val availableWidth = (parentSize.width / parentDensity.density - 16f).coerceAtLeast(1f)
    val availableHeight = (parentSize.height / parentDensity.density - 16f).coerceAtLeast(1f)
    val requestedHeight = preferredHeight.value.takeIf { it.isFinite() && it > 0f } ?: 320f
    // This snapshot is taken once per opening. Main move/resize/hide dismisses it.
    val popupBounds = remember(owner) {
        val gc = owner.graphicsConfiguration
        val osScale = gc.defaultTransform.scaleX.takeIf { it.isFinite() && it > 0.0 } ?: systemDensity.toDouble()
        val screen = Rectangle(gc.bounds)
        val screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        screen.setBounds(screen.x + screenInsets.left, screen.y + screenInsets.top,
            (screen.width - screenInsets.left - screenInsets.right).coerceAtLeast(1),
            (screen.height - screenInsets.top - screenInsets.bottom).coerceAtLeast(1))
        val location = owner.locationOnScreen
        val insets = owner.insets
        val client = Rectangle(location.x + insets.left, location.y + insets.top,
            (owner.width - insets.left - insets.right).coerceAtLeast(1),
            (owner.height - insets.top - insets.bottom).coerceAtLeast(1))
        val intersection = client.intersection(screen)
        val usable = if (intersection.width > 0 && intersection.height > 0) intersection else screen
        val width = (minOf(320f, availableWidth) * windowScale).roundToInt().coerceIn(1, usable.width)
        val height = (minOf(requestedHeight, availableHeight) * windowScale).roundToInt().coerceIn(1, usable.height)
        val right = (actualAnchor.x / osScale).roundToInt()
        val top = (actualAnchor.y / osScale).roundToInt()
        val gap = (4f * windowScale).roundToInt()
        Rectangle((right - width).coerceIn(usable.x, usable.x + usable.width - width),
            (top - height - gap).coerceIn(usable.y, usable.y + usable.height - height), width, height)
    }
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    DialogWindow(
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                title = "播放操作"
                isUndecorated = true
                isResizable = false
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                setBounds(popupBounds)
            }
        },
        dispose = { it.dispose() },
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                latestDismiss.value(); true
            } else false
        },
    ) {
        DisposableEffect(window, owner) {
            var alive = true
            var gainedFocus = window.isFocused
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent) { if (alive) gainedFocus = true }
                override fun windowLostFocus(event: WindowEvent) {
                    if (alive && gainedFocus) latestDismiss.value()
                }
            }
            val closeListener = object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { if (alive) latestDismiss.value() }
            }
            val ownerGeometry = object : ComponentAdapter() {
                override fun componentMoved(event: ComponentEvent) { if (alive) latestDismiss.value() }
                override fun componentResized(event: ComponentEvent) { if (alive) latestDismiss.value() }
                override fun componentHidden(event: ComponentEvent) { if (alive) latestDismiss.value() }
            }
            val ownerClosing = object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { if (alive) latestDismiss.value() }
                override fun windowClosed(event: WindowEvent) { if (alive) latestDismiss.value() }
                override fun windowIconified(event: WindowEvent) { if (alive) latestDismiss.value() }
            }
            window.addWindowFocusListener(focusListener)
            window.addWindowListener(closeListener)
            owner.addComponentListener(ownerGeometry)
            owner.addWindowListener(ownerClosing)
            onDispose {
                alive = false
                window.removeWindowFocusListener(focusListener)
                window.removeWindowListener(closeListener)
                owner.removeComponentListener(ownerGeometry)
                owner.removeWindowListener(ownerClosing)
            }
        }
        CompositionLocalProvider(LocalDensity provides parentDensity) {
            AppSurface(modifier = modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                val menuScroll = rememberScrollState()
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(menuScroll)
                        .padding(top = 4.dp, bottom = 4.dp, end = 12.dp), content = content)
                    if (menuScroll.maxValue > 0) VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(menuScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            .padding(vertical = 4.dp, horizontal = 3.dp),
                        style = defaultScrollbarStyle().copy(thickness = 4.dp,
                            unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f),
                            hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)),
                    )
                }
            }
        }
    }
}
