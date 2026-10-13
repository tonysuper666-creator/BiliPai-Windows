@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.components.appDesktopInteractionVisuals

internal enum class DesktopLargeScreenFocusAxis { VERTICAL, HORIZONTAL }

internal enum class DesktopLargeScreenFocusStep { PREVIOUS, NEXT }

internal enum class DesktopLargeScreenEscapeAction {
    EXIT_FULLSCREEN,
    CLOSE_DYNAMIC_DRAWER,
    SYSTEM_BACK,
}

internal const val DesktopShellSearchStringKey = "common_search"
internal const val DesktopShellBackStringKey = "common_back"
internal const val DesktopShellRetryStringKey = "common_retry"
internal const val DesktopShellRefreshStringKey = "common_refresh"

/** No simplified/traditional/English entry exists for the offline destination. */
internal const val DesktopShellDownloadsDescription = "下载与离线"

/** Same destination words as the original independent now-playing bar. */
internal fun desktopShellNowPlayingExpandLabel(opensAudioMode: Boolean): String =
    if (opensAudioMode) "听视频" else "视频详情页"

/** Sidebar arrows stay vertical. The bottom dock stays horizontal. Hidden chrome has no shell axis. */
internal fun resolveDesktopLargeScreenFocusAxis(
    placement: DesktopShellNavigationPlacement,
): DesktopLargeScreenFocusAxis? = when (placement) {
    DesktopShellNavigationPlacement.SIDEBAR -> DesktopLargeScreenFocusAxis.VERTICAL
    DesktopShellNavigationPlacement.BOTTOM_DOCK -> DesktopLargeScreenFocusAxis.HORIZONTAL
    DesktopShellNavigationPlacement.HIDDEN -> null
}

/** Tab is left to the focus system. Cross-axis arrows are not shell traversal. */
internal fun resolveDesktopLargeScreenFocusStep(
    key: Key,
    axis: DesktopLargeScreenFocusAxis,
): DesktopLargeScreenFocusStep? = when (axis) {
    DesktopLargeScreenFocusAxis.VERTICAL -> when (key) {
        Key.DirectionUp -> DesktopLargeScreenFocusStep.PREVIOUS
        Key.DirectionDown -> DesktopLargeScreenFocusStep.NEXT
        else -> null
    }
    DesktopLargeScreenFocusAxis.HORIZONTAL -> when (key) {
        Key.DirectionLeft -> DesktopLargeScreenFocusStep.PREVIOUS
        Key.DirectionRight -> DesktopLargeScreenFocusStep.NEXT
        else -> null
    }
}

internal fun desktopLargeScreenFocusDirection(
    step: DesktopLargeScreenFocusStep,
    axis: DesktopLargeScreenFocusAxis,
): FocusDirection = when (axis) {
    DesktopLargeScreenFocusAxis.VERTICAL -> when (step) {
        DesktopLargeScreenFocusStep.PREVIOUS -> FocusDirection.Up
        DesktopLargeScreenFocusStep.NEXT -> FocusDirection.Down
    }
    DesktopLargeScreenFocusAxis.HORIZONTAL -> when (step) {
        DesktopLargeScreenFocusStep.PREVIOUS -> FocusDirection.Left
        DesktopLargeScreenFocusStep.NEXT -> FocusDirection.Right
    }
}

/** Fullscreen still leaves first. An open dynamic drawer closes before system back. */
internal fun resolveDesktopLargeScreenEscapeAction(
    fullscreen: Boolean,
    dynamicDrawerExpanded: Boolean,
): DesktopLargeScreenEscapeAction = when {
    fullscreen -> DesktopLargeScreenEscapeAction.EXIT_FULLSCREEN
    dynamicDrawerExpanded -> DesktopLargeScreenEscapeAction.CLOSE_DYNAMIC_DRAWER
    else -> DesktopLargeScreenEscapeAction.SYSTEM_BACK
}

internal fun resolveDynamicSidebarHeaderDescription(expanded: Boolean): String =
    if (expanded) "收起关注列表" else "展开关注列表"

internal fun resolveDynamicHiddenUsersDescription(showingHidden: Boolean): String =
    if (showingHidden) "隐藏已隐藏用户" else "显示隐藏用户"

internal fun resolveDynamicDrawerHandleDescription(onRight: Boolean): String =
    if (onRight) "展开右侧关注列表" else "展开左侧关注列表"

internal fun resolveDynamicUserContentDescription(
    name: String,
    hidden: Boolean,
    pinned: Boolean,
): String = buildString {
    append(name.ifBlank { "关注用户" })
    if (pinned) append("，已置顶")
    if (hidden) append("，已隐藏")
}

internal fun resolveDynamicActionDockDescription(collapsed: Boolean): String =
    if (collapsed) "展开顶部操作" else "折叠顶部操作"

internal fun resolveDynamicDisplayModeDescription(): String = "关注列表位置"

internal fun resolveDynamicLiveFoldDescription(expanded: Boolean, count: Int): String =
    if (expanded) "收起直播，$count 人" else "展开直播，$count 人"

/**
 * Active dynamic page publishes the drawer. Inactive pages keep their own token so a
 * composed-but-hidden host cannot clear the visible one.
 */
internal class DesktopLargeScreenEscapeHandlers {
    var token: Any? = null
    var dynamicDrawerExpanded: Boolean = false
    var closeDynamicDrawer: (() -> Unit)? = null

    fun bind(owner: Any, expanded: Boolean, close: (() -> Unit)?) {
        token = owner
        dynamicDrawerExpanded = expanded
        closeDynamicDrawer = if (expanded) close else null
    }

    fun unbind(owner: Any) {
        if (token !== owner) return
        token = null
        dynamicDrawerExpanded = false
        closeDynamicDrawer = null
    }
}

internal val LocalDesktopLargeScreenEscapeHandlers = staticCompositionLocalOf {
    DesktopLargeScreenEscapeHandlers()
}

@Composable
internal fun BindDesktopDynamicDrawerEscape(
    active: Boolean,
    expanded: Boolean,
    onClose: () -> Unit,
) {
    val handlers = LocalDesktopLargeScreenEscapeHandlers.current
    val owner = remember { Any() }
    val latestClose by rememberUpdatedState(onClose)
    DisposableEffect(handlers, owner) { onDispose { handlers.unbind(owner) } }
    SideEffect {
        if (active) handlers.bind(owner, expanded, { latestClose() }) else handlers.unbind(owner)
    }
}

@Composable
internal fun Modifier.largeScreenFocusTraversal(axis: DesktopLargeScreenFocusAxis): Modifier {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    return largeScreenFocusTraversal(axis, focusManager)
}

internal fun Modifier.largeScreenFocusTraversal(
    axis: DesktopLargeScreenFocusAxis,
    focusManager: FocusManager,
): Modifier = this
    .focusGroup()
    .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val step = resolveDesktopLargeScreenFocusStep(event.key, axis) ?: return@onKeyEvent false
        focusManager.moveFocus(desktopLargeScreenFocusDirection(step, axis))
    }

@Composable
internal fun Modifier.largeScreenControlTarget(
    contentDescription: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val activation = if (onLongClick == null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = indication,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = indication,
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    return this
        .appDesktopInteractionVisuals(interaction, shape = shape)
        .defaultMinSize(
            minWidth = desktopLargeScreenMinimumTarget(),
            minHeight = desktopLargeScreenMinimumTarget(),
        )
        .semantics {
            this.contentDescription = contentDescription
            this.role = Role.Button
            this.selected = isSelected
        }
        .then(activation)
}

internal fun desktopLargeScreenMinimumTarget() = AppChromeSizeTokens.MinimumTouchTarget
