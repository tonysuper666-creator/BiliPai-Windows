package com.bilipai.desktop.ux

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.purebilibili.feature.video.ui.overlay.LocalPlayerPageBackdrop
import com.android.purebilibili.feature.video.ui.overlay.PlayerControlGlassDock
import com.android.purebilibili.feature.video.ui.overlay.PlayerControlsBackdropHost
import com.bilipai.desktop.ui.DesktopOriginalVideoSectionPlatform
import com.bilipai.desktop.ux.DesktopCardContextMenu as AppDropdownMenu
import com.bilipai.desktop.ux.DesktopCardContextMenuItem as AppDropdownMenuItem
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.basic.PlainTooltip as MiuixPlainTooltip
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox as MiuixTooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults as MiuixTooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState as rememberMiuixTooltipState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/*
 * THROWAWAY compile-only API probe (desktop UX program, slice XP, spec 9.2 and 10.2).
 *
 * Purpose. One Linux compile of the test source set must surface every API mismatch of the uncertain Compose,
 * Skiko, Miuix and generated-source symbols that the UX program will use, before any dependent slice is written.
 * Nothing in this file runs: the only test is trivially true, and every function below is compiled and never
 * called. Each section starts with a `// probe: <key>` line; test_ux_api_probe_source.py maps the keys to the
 * pinned jars (class, member, descriptor, opt-in markers), to the vendored Miuix sources and to the real generated
 * Home and player text.
 *
 * Lifetime. Delete this file after the first Linux compile of the test source set has passed and its findings are
 * fixed in the dependents. Only Root may delete it, together with desktop/tools/ux/test_ux_api_probe_source.py and
 * desktop/tools/ux/ux_api_probe.py (the Python suite asserts that this file exists) and the suite's line in the
 * validation plan. No other source may depend on anything declared here.
 *
 * Conventions. Every API shape is copied from a real declaration (a class-file descriptor of the pinned jars, the
 * vendored Miuix source, the generated player or Home text) or from a call that already compiles in
 * desktop/src/main. Text is ASCII on purpose. The test source set is associated with main, so internal
 * declarations of main and of the generated sources are visible here.
 */
class UxApiProbeTest {
    @Test fun theProbeCompilesAndThatIsAllItProves() {
        assertTrue(true)
    }
}

// probe: pointer-buttons-back
// Back and forward mouse buttons are extension properties of PointerButtons (PointerEvent_skikoKt in ui-desktop).
private fun probeBackButtonReading(): Boolean {
    val pressed: PointerButtons = PointerButtons(isBackPressed = true)
    return pressed.isBackPressed && !pressed.isForwardPressed
}

// probe: pointer-event-handler
// Modifier.onPointerEvent is marked ExperimentalComposeUiApi in the jar; production code already opts in the same way.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.probeBackButtonHandler(onBack: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isBackPressed) onBack() }

// probe: blank-pointer-icon
// PointerIcon(Cursor) wraps an AWT cursor. Toolkit.createCustomCursor throws HeadlessException on a headless JVM, so a
// dependent must build this lazily and never in a Linux unit test.
private fun probeBlankPointerIcon(): PointerIcon {
    val blank: Cursor = Toolkit.getDefaultToolkit()
        .createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")
    return PointerIcon(blank)
}

// probe: pointer-hover-icon
private fun Modifier.probeCursor(hidden: Boolean, blank: PointerIcon): Modifier =
    if (hidden) pointerHoverIcon(blank, overrideDescendants = true) else pointerHoverIcon(PointerIcon.Hand)

// probe: miuix-tooltip-box
// The exact shape of DesktopWindowsPlayerIconButton in DesktopWindowsVideoControlBar.kt, which compiles today.
@Composable
private fun ProbeTooltip(tooltip: String, visible: Boolean, content: @Composable () -> Unit) {
    val tooltipState = rememberMiuixTooltipState(isPersistent = true)
    LaunchedEffect(visible) { if (visible) tooltipState.show() else tooltipState.dismiss() }
    MiuixTooltipBox(
        positionProvider = MiuixTooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above, 4.dp),
        tooltip = { MiuixPlainTooltip { BasicText(tooltip) } },
        state = tooltipState, focusable = false, enableUserInput = false,
        modifier = Modifier,
        content = content,
    )
}

// probe: input-mode-manager
@Composable
private fun probeKeyboardModality(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

// probe: layer-backdrop-ondraw
// rememberLayerBackdrop(onDraw) records the video backdrop frame instead of the node's own content. The dock is
// PlayerControlGlassDock (internal, generated into com.android.purebilibili.feature.video.ui.overlay). Its own
// LocalPlayerControlBackdrop is private: the backdrop reaches the dock only through LocalPlayerPageBackdrop and
// PlayerControlsBackdropHost.
@Composable
private fun ProbeCarrierGlass(platform: DesktopOriginalVideoSectionPlatform, modifier: Modifier) {
    val backdrop: LayerBackdrop = rememberLayerBackdrop(onDraw = { with(platform) { drawVideoBackdrop() } })
    Box(modifier.layerBackdrop(backdrop)) {
        // probe: player-control-glass-dock
        CompositionLocalProvider(LocalPlayerPageBackdrop provides backdrop) {
            PlayerControlsBackdropHost(enabled = true) {
                PlayerControlGlassDock(enabled = true, modifier = Modifier, shape = RoundedCornerShape(24.dp)) {
                    Box(Modifier)
                }
            }
        }
    }
}

// probe: scrollbar-adapter-v2
// Compose 1.12.1 has no adapter for LazyStaggeredGridState; a dependent implements the four members.
private class ProbeStaggeredGridScrollbarAdapter : ScrollbarAdapter {
    override val scrollOffset: Double get() = 0.0
    override val contentSize: Double get() = 1.0
    override val viewportSize: Double get() = 1.0
    override suspend fun scrollTo(scrollOffset: Double) {}
}

@Composable
private fun ProbeFeedScrollbar(modifier: Modifier) {
    val adapter = remember { ProbeStaggeredGridScrollbarAdapter() }
    VerticalScrollbar(adapter = adapter, modifier = modifier)
}

// probe: popup-position-provider
// androidx.compose.ui.window.PopupPositionProvider (the interface that TooltipBox takes), not the unrelated Miuix
// interface of the same simple name in top.yukonga.miuix.kmp.basic.
private class ProbePopupPositionProvider(private val pointer: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        pointer.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        pointer.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

// probe: compose-dialog
// The flyout window shape: the same calls as DesktopCommandPopupWindow and DesktopWindowsPlayerMenu.
private fun probeFlyoutDialog(owner: Window, context: CompositionLocalContext, content: @Composable () -> Unit): ComposeDialog =
    ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
        title = "probe"
        isUndecorated = true
        isResizable = false
        isAlwaysOnTop = owner.isAlwaysOnTop
        compositionLocalContext = context
        setContent { content() }
    }

// probe: card-context-menu-alias
// Stand-ins with the planned desktop-owned shape. The generator swaps only the two imports, as aliases
// (import ...DesktopCardContextMenu as AppDropdownMenu), so the call text below is the call text of the real
// generated VideoCard (argument names and the ColumnScope trailing lambda), compiled against the stand-ins.
@Composable
internal fun DesktopCardContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val shift = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    Popup(
        popupPositionProvider = ProbePopupPositionProvider(shift),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(modifier = modifier, content = content)
    }
}

@Composable
internal fun DesktopCardContextMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier = modifier.clickable(enabled = enabled, onClick = onClick)) { text() }
}

@Composable
private fun ProbeVideoCardMenuCallShape(
    onWatchLater: (() -> Unit)?,
    onUnfavorite: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    dismissMenuText: String,
) {
    var showDismissMenu by remember { mutableStateOf(false) }
    var showUnfavoriteDialog by remember { mutableStateOf(false) }
    Box(modifier = Modifier) {
        AppDropdownMenu(
            expanded = showDismissMenu,
            onDismissRequest = { showDismissMenu = false },
            offset = DpOffset.Zero
        ) {
            if (onWatchLater != null) {
                AppDropdownMenuItem(
                    text = { BasicText("watch later") },
                    onClick = {
                        showDismissMenu = false
                        onWatchLater.invoke()
                    }
                )
            }
            if (onUnfavorite != null) {
                AppDropdownMenuItem(
                    text = { BasicText("unfavorite") },
                    onClick = {
                        showDismissMenu = false
                        showUnfavoriteDialog = true
                    }
                )
            }
            if (onDismiss != null) {
                AppDropdownMenuItem(
                    text = { BasicText(dismissMenuText) },
                    onClick = {
                        showDismissMenu = false
                        onDismiss.invoke()
                    }
                )
            }
        }
    }
    if (showUnfavoriteDialog) BasicText("confirm")
}
