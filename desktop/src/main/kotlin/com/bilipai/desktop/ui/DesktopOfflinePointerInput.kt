package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import com.android.purebilibili.feature.download.resolveOfflineSeekPositionFromTouch
import com.android.purebilibili.feature.download.resolveOfflineSeekProgressFromTouch

/** One owner for click and drag. Two competing recognizers can cancel a mouse
 * click before the original timeline commits it. Mapping stays in the complete
 * original offline policy; source admission remains in the existing player. */
internal suspend fun PointerInputScope.detectDesktopOfflineSeekGestures(
    durationMs: Long,
    isCurrent: () -> Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    if (durationMs <= 0L) return
    awaitEachGesture {
        val down = awaitFirstDown()
        if (!isCurrent() || !down.position.x.isFinite() ||
            down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        var committed = false
        down.consume()
        try {
            onPreview(resolveOfflineSeekProgressFromTouch(down.position.x, size.width.toFloat()))
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed || !isCurrent() || !change.position.x.isFinite()) return@awaitEachGesture
                if (change.type == PointerType.Mouse && change.pressed && !event.buttons.isPrimaryPressed) return@awaitEachGesture
                change.consume()
                onPreview(resolveOfflineSeekProgressFromTouch(change.position.x, size.width.toFloat()))
                if (!change.pressed) {
                    if (!isCurrent()) return@awaitEachGesture
                    onCommit(resolveOfflineSeekPositionFromTouch(change.position.x, size.width.toFloat(), durationMs))
                    committed = true
                    return@awaitEachGesture
                }
            }
        } finally {
            if (!committed) onCancel()
        }
    }
}

/** Rekey on the captured native source, not on changing progress/UI callbacks.
 * A source replacement cancels an in-flight gesture instead of seeking its successor. */
@Composable
internal fun Modifier.desktopOfflineSeekPointerInput(
    sourceIdentity: Any?,
    durationMs: Long,
    isCurrent: () -> Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val latestCurrent = rememberUpdatedState(isCurrent)
    val latestPreview = rememberUpdatedState(onPreview)
    val latestCommit = rememberUpdatedState(onCommit)
    val latestCancel = rememberUpdatedState(onCancel)
    return pointerInput(sourceIdentity, durationMs) {
        if (sourceIdentity != null) detectDesktopOfflineSeekGestures(durationMs,
            { latestCurrent.value() }, { latestPreview.value(it) },
            { latestCommit.value(it) }, { latestCancel.value() })
    }
}
