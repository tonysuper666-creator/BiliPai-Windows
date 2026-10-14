package com.android.purebilibili.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.theme.resolveAndroidNativeChromeTokens
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import com.android.purebilibili.core.ui.motion.AppMotionTokens

data class AdaptiveBottomSheetVisualSpec(
    val cornerRadiusDp: Int,
    val useMaterialDragHandle: Boolean
)

enum class AppModalPresentation {
    BottomSheet,
    CenteredDialog,
}

data class AppModalLayoutSpec(
    val presentation: AppModalPresentation,
    val maxWidthDp: Int,
    val maxHeightFraction: Float,
)

fun resolveAppModalLayoutSpec(
    windowWidthDp: Int,
    miuixNonGlass: Boolean = false,
): AppModalLayoutSpec = when {
    windowWidthDp < 600 -> AppModalLayoutSpec(
        presentation = AppModalPresentation.BottomSheet,
        maxWidthDp = windowWidthDp,
        maxHeightFraction = 1f,
    )
    miuixNonGlass -> AppModalLayoutSpec(
        presentation = AppModalPresentation.CenteredDialog,
        maxWidthDp = 420,
        maxHeightFraction = 0.86f,
    )
    windowWidthDp < 1200 -> AppModalLayoutSpec(
        presentation = AppModalPresentation.CenteredDialog,
        maxWidthDp = 640,
        maxHeightFraction = 0.86f,
    )
    else -> AppModalLayoutSpec(
        presentation = AppModalPresentation.CenteredDialog,
        maxWidthDp = 720,
        maxHeightFraction = 0.86f,
    )
}

internal data class AdaptiveBottomSheetMotionSpec(
    val scrimEnterDurationMillis: Int,
    val scrimExitDurationMillis: Int,
    val contentEnterFadeDurationMillis: Int,
    val contentExitFadeDurationMillis: Int
)

fun resolveAdaptiveBottomSheetVisualSpec(
    uiStyle: AppUiStyle,
    miuixNonGlass: Boolean = false,
): AdaptiveBottomSheetVisualSpec {
    val level = if (miuixNonGlass) ContainerLevel.Sheet else ContainerLevel.Pill
    val cornerRadiusDp = AppShapes.resolveContainerCornerDp(
        level = level,
        uiStyle = uiStyle,
        liquidGlassEnabled = !miuixNonGlass,
    ).value.toInt()
    return AdaptiveBottomSheetVisualSpec(
        cornerRadiusDp = cornerRadiusDp,
        useMaterialDragHandle = true,
    )
}

internal fun resolveAdaptiveBottomSheetMotionSpec(
    uiStyle: AppUiStyle,
): AdaptiveBottomSheetMotionSpec {
    val tokens = resolveAndroidNativeChromeTokens(uiStyle)
    return AdaptiveBottomSheetMotionSpec(
        scrimEnterDurationMillis = tokens.motionEmphasizedMillis,
        scrimExitDurationMillis = tokens.expressiveMotionDurationMillis,
        contentEnterFadeDurationMillis = tokens.motionEmphasizedMillis,
        contentExitFadeDurationMillis = tokens.expressiveMotionDurationMillis
    )
}

/** Window-level hosts do not depend on an AdaptiveScaffold overlay host. */
enum class BottomSheetHost {
    MIUIX_WINDOW,
    MATERIAL3,
}

fun resolveBottomSheetHost(uiStyle: AppUiStyle): BottomSheetHost = when (uiStyle) {
    AppUiStyle.MIUIX -> BottomSheetHost.MIUIX_WINDOW
    AppUiStyle.MATERIAL3 -> BottomSheetHost.MATERIAL3
}

internal fun bottomSheetScrimEnterTransition(
    uiStyle: AppUiStyle,
): EnterTransition = fadeIn(
    AppMotionTokens.resolveBottomSheetFadeEnterSpec(uiStyle)
)

internal fun bottomSheetScrimExitTransition(
    uiStyle: AppUiStyle,
): ExitTransition = fadeOut(
    AppMotionTokens.resolveBottomSheetFadeExitSpec(uiStyle)
)

internal fun bottomSheetContentEnterTransition(
    uiStyle: AppUiStyle,
): EnterTransition {
    return slideInVertically(
        initialOffsetY = { it },
        animationSpec = AppMotionTokens.resolveBottomSheetSlideSpec(uiStyle)
    ) + fadeIn(
        AppMotionTokens.resolveBottomSheetFadeEnterSpec(uiStyle)
    )
}

internal fun bottomSheetContentExitTransition(
    uiStyle: AppUiStyle,
): ExitTransition {
    return slideOutVertically(
        targetOffsetY = { it },
        animationSpec = AppMotionTokens.resolveBottomSheetSlideExitSpec(uiStyle)
    ) + fadeOut(
        AppMotionTokens.resolveBottomSheetFadeExitSpec<Float>(uiStyle)
    )
}

/**
 * 把返回事件绑到弹层所在 Dialog 窗口，而不是下层路由。
 *
 * 系统侧边/预测返回落在 Dialog 自己的 NavigationEventDispatcher 上；若不重绑，
 * handler 会挂在底层 Activity/路由 dispatcher，侧滑无法关闭弹层（点 scrim 仍可关）。
 */
@Composable
private fun ModalSheetNavigationHost(
    dismissOnBackPress: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val owner = LocalView.current.findViewTreeNavigationEventDispatcherOwner()
    val host: @Composable () -> Unit = {
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = dismissOnBackPress,
            onBackCompleted = onDismissRequest,
        )
        content()
    }
    if (owner == null) {
        host()
    } else {
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            host()
        }
    }
}

/**
 * Compact windows use the theme's native sheet; wide windows use a centered dialog.
 * Material SheetState controls the Material branch only. Miuix owns its drag/exit state.
 * Hinge-safe presentation keeps the existing region host to avoid spanning a fold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    shape: Shape? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    presentationProgress: Float = 1f,
    dismissOnBackPress: Boolean = true,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    windowInsets: androidx.compose.foundation.layout.WindowInsets = androidx.compose.material3.BottomSheetDefaults.modalWindowInsets,
    presentationOverride: AppModalPresentation? = null,
    sheetSurfaceModifier: Modifier = Modifier,
    backgroundBlurBehind: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val uiStyle = LocalAppUiStyle.current
    val miuix = uiStyle == AppUiStyle.MIUIX
    // Window blur is independent of theme and the header's haze/glass preferences.
    val blurBehind = backgroundBlurBehind
    val configuration = LocalConfiguration.current
    val hingeSafeRegions = LocalHingeSafeOverlayRegions.current.sheet
    val layoutSpec = remember(configuration.screenWidthDp, miuix) {
        resolveAppModalLayoutSpec(configuration.screenWidthDp, miuixNonGlass = miuix)
    }
    val centered = (presentationOverride ?: layoutSpec.presentation) == AppModalPresentation.CenteredDialog
    val resolvedColor = if (containerColor == MaterialTheme.colorScheme.surface) {
        if (miuix) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow
    } else containerColor

    if (miuix && hingeSafeRegions == null) {
        var show by remember { mutableStateOf(true) }
        val latestDismiss by rememberUpdatedState(onDismissRequest)
        val body: @Composable () -> Unit = {
            val blurMotionModifier = rememberSheetBlurMotionModifier(blurBehind, trackPosition = !centered)
            // Consume back when the caller handles it inside the sheet (e.g. reply navigation).
            // Drag/outside dismissal remains available independently of this back policy.
            if (!dismissOnBackPress) {
                NavigationBackHandler(
                    state = rememberNavigationEventState(NavigationEventInfo.None),
                    onBackCompleted = {},
                )
            }
            Column(Modifier.fillMaxWidth().then(blurMotionModifier).then(sheetSurfaceModifier), content = content)
        }
        if (centered) {
            WindowDialog(
                show = show,
                modifier = modifier.heightIn(max = (configuration.screenHeightDp * layoutSpec.maxHeightFraction).dp),
                backgroundColor = resolvedColor,
                maxWidth = layoutSpec.maxWidthDp.dp,
                largeScreen = true,
                insideMargin = DpSize(0.dp, 0.dp),
                onDismissRequest = { show = false },
                onDismissFinished = { latestDismiss() },
                content = body,
            )
        } else {
            WindowBottomSheet(
                show = show,
                modifier = modifier,
                backgroundColor = resolvedColor,
                insideMargin = DpSize(0.dp, 0.dp),
                onDismissRequest = { show = false },
                onDismissFinished = { latestDismiss() },
                content = body,
            )
        }
        return
    }
    if (hingeSafeRegions != null || centered) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                dismissOnBackPress = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            ModalWindowBlurBehindEffect(enabled = blurBehind)
            ModalSheetNavigationHost(dismissOnBackPress, onDismissRequest) {
                val surface: @Composable () -> Unit = {
                    BoxWithConstraints {
                        val surfaceModifier = modifier.widthIn(max = layoutSpec.maxWidthDp.dp)
                            .heightIn(max = minOf(maxHeight, (configuration.screenHeightDp * layoutSpec.maxHeightFraction).dp))
                            .fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }
                        val surfaceContent: @Composable () -> Unit = {
                            Column(Modifier.then(sheetSurfaceModifier), content = content)
                        }
                        if (miuix) {
                            AppPopupSurface(
                                type = AppPopupSurfaceType.DIALOG,
                                modifier = surfaceModifier,
                                shape = shape ?: RoundedCornerShape(28.dp),
                                containerColor = resolvedColor,
                                contentColor = contentColor,
                                content = surfaceContent,
                            )
                        } else {
                            Surface(
                                modifier = surfaceModifier,
                                shape = shape ?: MaterialTheme.shapes.extraLarge,
                                color = resolvedColor,
                                contentColor = contentColor,
                                tonalElevation = tonalElevation,
                                content = surfaceContent,
                            )
                        }
                    }
                }
                if (hingeSafeRegions != null) {
                    HingeSafeOverlayHost(
                        regionProvider = hingeSafeRegions,
                        modifier = Modifier.fillMaxSize().imePadding(),
                        onDismissRequest = onDismissRequest,
                    ) { surface() }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { surface() }
                }
            }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        shape = shape ?: BottomSheetDefaults.ExpandedShape,
        containerColor = resolvedColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = { windowInsets },
    ) {
        val blurMotionModifier = rememberSheetBlurMotionModifier(blurBehind)
        ModalSheetNavigationHost(dismissOnBackPress, onDismissRequest) {
            Column(Modifier.fillMaxWidth().then(blurMotionModifier).then(sheetSurfaceModifier), content = content)
        }
    }
}

data class AppBottomSheetMotion(
    val scrimEnter: EnterTransition,
    val scrimExit: ExitTransition,
    val contentEnter: EnterTransition,
    val contentExit: ExitTransition,
    val scrimEnterDurationMillis: Int,
    val scrimExitDurationMillis: Int,
    val contentEnterFadeDurationMillis: Int,
    val contentExitFadeDurationMillis: Int,
)

@Composable
fun rememberAppBottomSheetMotion(): AppBottomSheetMotion {
    val uiStyle = LocalAppUiStyle.current
    return remember(uiStyle) {
        val motionSpec = resolveAdaptiveBottomSheetMotionSpec(uiStyle)
        AppBottomSheetMotion(
            scrimEnter = bottomSheetScrimEnterTransition(uiStyle),
            scrimExit = bottomSheetScrimExitTransition(uiStyle),
            contentEnter = bottomSheetContentEnterTransition(uiStyle),
            contentExit = bottomSheetContentExitTransition(uiStyle),
            scrimEnterDurationMillis = motionSpec.scrimEnterDurationMillis,
            scrimExitDurationMillis = motionSpec.scrimExitDurationMillis,
            contentEnterFadeDurationMillis = motionSpec.contentEnterFadeDurationMillis,
            contentExitFadeDurationMillis = motionSpec.contentExitFadeDurationMillis,
        )
    }
}

@Composable
fun AppBottomSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/** Follow the native sheet motion without a second animation or frame-rate recomposition. */
@Composable
private fun rememberSheetBlurMotionModifier(enabled: Boolean, trackPosition: Boolean = true): Modifier {
    val view = LocalView.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    ModalWindowBlurBehindEffect(enabled = enabled, progress = {
        if (!trackPosition) 1f else {
            val layout = coordinates[0]
            if (layout == null || !layout.isAttached || layout.size.height == 0) 0f
            else {
                val top = layout.localToWindow(Offset.Zero).y
                ((view.rootView.height - top) / layout.size.height.toFloat()).coerceIn(0f, 1f)
            }
        }
    })
    return Modifier.onGloballyPositioned { coordinates[0] = it }
}
