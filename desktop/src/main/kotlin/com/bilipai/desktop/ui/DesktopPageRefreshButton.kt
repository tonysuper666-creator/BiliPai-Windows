package com.bilipai.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge
import com.bilipai.desktop.ui.kit.DesktopTooltipBox
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import com.bilipai.desktop.ui.kit.desktopKitHairlineBorder
import com.bilipai.desktop.ui.kit.desktopKitInteraction
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition

/*
 * The desktop refresh button (TON-1026). Pull to refresh is a touch gesture: a mouse wheel or a mouse drag does not produce the
 * overscroll it waits for, so on Windows it is unreliable. Every list that upstream refreshes with a pull gets this button in the
 * bottom right corner instead, and the gesture itself is switched off. Two places draw it and nothing else does:
 *
 *   - the build-time patch of the upstream AdaptivePullToRefreshBox (upstream-core/patches/design-system/.../AdaptivePullToRefreshBox.kt.patch)
 *     calls DesktopRefreshButton inside the box content, so every page that used the pull box has the button, and a page that
 *     migrates from a legacy copy to the upstream file keeps it;
 *   - the desktop-owned dynamic feed (DesktopDynamicTimeline.kt), which never had a pull box.
 *
 * The model below is pure so DesktopPageRefreshButtonTest can check it without a window: a click is ignored while a refresh runs
 * and during the short window between the click and the moment the owner reports isRefreshing, so a double click starts one refresh.
 */

internal const val DESKTOP_REFRESH_BUTTON_SIZE_DP = 44
internal const val DESKTOP_REFRESH_BUTTON_MARGIN_DP = 20
internal const val DESKTOP_REFRESH_BUTTON_GAP_DP = 12

/**
 * Work to do before the list's own refresh starts, provided by a host that knows how to scroll its lists (Home: the pager's
 * scroll to top). The refreshed rows then appear at the top. Null where the list is not behind such a host.
 */
internal val LocalDesktopRefreshPrelude = staticCompositionLocalOf<(() -> Unit)?> { null }

/** What the back-to-top buttons and the refresh button share: the lift that puts the back-to-top button above the refresh slot. */
internal const val DESKTOP_REFRESH_STACK_LIFT_DP = DESKTOP_REFRESH_BUTTON_SIZE_DP + DESKTOP_REFRESH_BUTTON_GAP_DP

/** How long a click is held as "refreshing" while the owner has not yet reported isRefreshing (a refresh that the owner declines never reports it). */
internal const val DESKTOP_REFRESH_PENDING_TIMEOUT_MS = 1_500L

internal const val DESKTOP_REFRESH_LABEL = "刷新"
internal const val DESKTOP_REFRESH_SHORTCUT = "F5"
internal const val DESKTOP_REFRESH_SPIN_PERIOD_MS = 900

/**
 * The click bookkeeping of one button. pendingSinceMs is the time of an accepted click that the owner has not yet answered with
 * isRefreshing = true; a running refresh (refreshing) or a click younger than the timeout makes the button busy.
 */
internal data class DesktopRefreshButtonState(val pendingSinceMs: Long? = null) {
    fun busy(refreshing: Boolean, nowMs: Long): Boolean =
        refreshing || (pendingSinceMs != null && nowMs - pendingSinceMs in 0 until DESKTOP_REFRESH_PENDING_TIMEOUT_MS)

    /** The state after a click, and whether the click starts a refresh. */
    fun click(refreshing: Boolean, nowMs: Long): Pair<DesktopRefreshButtonState, Boolean> =
        if (busy(refreshing, nowMs)) this to false else copy(pendingSinceMs = nowMs) to true

    /** The owner took the refresh over (isRefreshing became true), so the pending hold is no longer needed. */
    fun onRefreshing(refreshing: Boolean): DesktopRefreshButtonState =
        if (refreshing && pendingSinceMs != null) copy(pendingSinceMs = null) else this
}

/**
 * The one switch of the pull gesture (TON-1026, item 3). The patched AdaptivePullToRefreshBox asks it with the caller's `enabled`
 * and mounts the pull container only when the answer is true. A mouse wheel that keeps scrolling up at the top of a list sends
 * no overscroll, so on the desktop the gesture can neither fire nor hang half way; it stays off and the button does the work.
 */
@Suppress("UNUSED_PARAMETER")
internal fun desktopPullGestureEnabled(requested: Boolean): Boolean = false

/** The toast text of a refresh that failed: the first line of the reason when there is one. */
internal fun desktopRefreshFailureMessage(failure: Throwable?): String {
    val reason = failure?.message.orEmpty().lineSequence().firstOrNull().orEmpty().trim().take(60)
    return if (reason.isEmpty()) "刷新失败，请稍后重试" else "刷新失败：$reason"
}

/** The diagnostic log lines of a refresh (tag DesktopRefresh): who asked, and how long the owner reported isRefreshing. */
internal const val DESKTOP_REFRESH_LOG_TAG = "DesktopRefresh"
internal fun desktopRefreshStartLog(source: String): String = "刷新开始：来源=$source"
internal fun desktopRefreshShortcutStartLog(page: String): String = desktopRefreshStartLog("F5/Ctrl+R，页面=$page")
internal fun desktopRefreshRunningLog(): String = "刷新进行中：页面已报告正在刷新"
internal fun desktopRefreshEndLog(startedMs: Long, endedMs: Long): String = "刷新结束：用时=${(endedMs - startedMs).coerceAtLeast(0)}ms"

/** The wording of the tooltip and the accessible name, one place for the button and for the tests. */
internal fun desktopRefreshButtonName(busy: Boolean): String = if (busy) "正在刷新" else DESKTOP_REFRESH_LABEL

/**
 * Number of refresh buttons that are composed right now. The back-to-top button of the legacy list pages sits in the same corner,
 * so it reads desktopRefreshStackLift() and rises above the refresh button while one exists (a patch of its offset).
 */
internal object DesktopRefreshButtonPresence {
    private val count = mutableIntStateOf(0)
    val present: Boolean get() = count.intValue > 0
    fun enter() { count.intValue += 1 }
    fun leave() { count.intValue = (count.intValue - 1).coerceAtLeast(0) }
}

/** Extra offset for a floating button that shares the bottom right corner: zero without a refresh button, one button height and gap with it. */
@Composable
internal fun desktopRefreshStackLift(): Dp = if (DesktopRefreshButtonPresence.present) DESKTOP_REFRESH_STACK_LIFT_DP.dp else 0.dp

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * The button. [refreshing] is the owner's flag, [onRefresh] the owner's refresh. Placed in the bottom right corner of the
 * enclosing box; the caller does not position it.
 */
@Composable
internal fun BoxScope.DesktopRefreshButton(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        DesktopRefreshButtonPresence.enter()
        onDispose { DesktopRefreshButtonPresence.leave() }
    }
    var state by remember { mutableStateOf(DesktopRefreshButtonState()) }
    // The clock only has to wake the composition when a pending hold runs out; isRefreshing itself is snapshot state.
    var clock by remember { mutableStateOf(nowMs()) }
    val pendingSince = state.pendingSinceMs
    LaunchedEffect(pendingSince) {
        if (pendingSince != null) {
            kotlinx.coroutines.delay(DESKTOP_REFRESH_PENDING_TIMEOUT_MS)
            clock = nowMs()
        }
    }
    LaunchedEffect(refreshing) { state = state.onRefreshing(refreshing) }
    // Evidence that a refresh really ran, whoever asked for it (button, F5 or Ctrl+R): the owner's flag going up and coming down.
    var runningSinceMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            runningSinceMs = nowMs()
            DesktopDiagnosticsBridge.record("I", DESKTOP_REFRESH_LOG_TAG, desktopRefreshRunningLog())
        } else {
            runningSinceMs?.let { DesktopDiagnosticsBridge.record("I", DESKTOP_REFRESH_LOG_TAG, desktopRefreshEndLog(it, nowMs())) }
            runningSinceMs = null
        }
    }
    val busy = state.busy(refreshing, if (pendingSince != null) clock.coerceAtLeast(pendingSince) else clock)
    val latestOnRefresh = rememberUpdatedState(onRefresh)
    val latestRefreshing = rememberUpdatedState(refreshing)
    val latestPrelude = rememberUpdatedState(LocalDesktopRefreshPrelude.current)
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val name = desktopRefreshButtonName(busy)
    DesktopTooltipBox(
        text = name,
        shortcut = if (busy) null else DESKTOP_REFRESH_SHORTCUT,
        positioning = TooltipAnchorPosition.Above,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(end = DESKTOP_REFRESH_BUTTON_MARGIN_DP.dp, bottom = DESKTOP_REFRESH_BUTTON_MARGIN_DP.dp),
    ) {
        Box(
            Modifier
                .size(DESKTOP_REFRESH_BUTTON_SIZE_DP.dp)
                .shadow(4.dp, CircleShape)
                .background(colors.surfaceContainerHigh, CircleShape)
                .desktopKitHairlineBorder(colors.outlineVariant, (DESKTOP_REFRESH_BUTTON_SIZE_DP / 2).dp)
                .desktopKitInteraction(source, (DESKTOP_REFRESH_BUTTON_SIZE_DP / 2).dp, enabled = true)
                .clickable(interactionSource = source, indication = null, role = Role.Button) {
                    val (next, start) = state.click(latestRefreshing.value, nowMs())
                    state = next
                    if (start) {
                        clock = nowMs()
                        DesktopDiagnosticsBridge.record("I", DESKTOP_REFRESH_LOG_TAG, desktopRefreshStartLog("按钮"))
                        latestPrelude.value?.invoke()
                        latestOnRefresh.value()
                    }
                }
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            if (busy && !LocalDesktopKitReducedMotion.current) {
                SpinningGlyph()
            } else {
                Icon(
                    Icons.Rounded.Refresh, contentDescription = null,
                    modifier = Modifier.size(22.dp).alpha(if (busy) 0.6f else 1f),
                    tint = colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SpinningGlyph() {
    val transition = rememberInfiniteTransition(label = "desktopRefreshSpin")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(DESKTOP_REFRESH_SPIN_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "desktopRefreshSpinAngle",
    )
    Icon(
        Icons.Rounded.Refresh, contentDescription = null,
        modifier = Modifier.size(22.dp).rotate(angle),
        tint = MaterialTheme.colorScheme.primary,
    )
}
