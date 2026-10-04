package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.ui.AppAlertDialog
import kotlinx.coroutines.flow.Flow

/** An interface to the existing actor's actual clock and completed seek readback.
 * Root implements this for its captured accepted source; this is not a media core.
 * Listener registration/removal and callbacks run on the composition/EDT thread.
 * A queued seek intent must not be reported as a completed discontinuity. */
interface DesktopHotDanmakuPlayer {
    val currentPosition: Long
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    data class PositionInfo(val positionMs: Long)
    interface Listener {
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int)
    }
}

/** No preference cache, account, player, transport or coroutine scope is created here.
 * Provide the existing Store flow and an immutable complete accepted-source lease.
 * Root must key the WHOLE HotDanmakuBar composition by that lease, and guard both
 * original like/send callbacks with the same account/source ownership admission.
 * owns must be a pure bounded check, not a settings/account/native lock operation. */
internal class DesktopHotDanmakuBindings(
    val expandedMode: Flow<Boolean>,
    val sourceLease: Any,
    val owns: () -> Boolean,
)

internal val LocalDesktopHotDanmakuBindings = staticCompositionLocalOf<DesktopHotDanmakuBindings> {
    error("Original hot-danmaku bindings require the current Windows playback Root")
}

/** The original alert and all its slots remain inside a fixed owned native child.
 * Its ordinary Compose alert layer has no heavyweight video Canvas beneath it.
 * Global compose.layers.type=WINDOW is deliberately not enabled; that popup path
 * synchronously reshapes/redraws the pinned Direct3D Skia layer during drawing.
 * Closing/retiring the source removes this entire composition and owned dialog. */
@Composable
internal fun DesktopHotDanmakuAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val bindings = LocalDesktopHotDanmakuBindings.current
    if (!bindings.owns()) return
    key(bindings.sourceLease) {
        DesktopWindowsPlayerDialog(
            title = "发送同款弹幕",
            onDismissRequest = onDismissRequest,
            preferredHeightDp = 320,
        ) {
            AppAlertDialog(
                onDismissRequest = onDismissRequest,
                title = title,
                text = text,
                confirmButton = confirmButton,
                dismissButton = dismissButton,
            )
        }
    }
}
