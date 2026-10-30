@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.bilipai.desktop.player

import androidx.compose.foundation.gestures.LocalScrollConfig
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

/** At a real list top, take reverse wheel input before an active child can consume it.
 * Use Compose's platform ScrollConfig rather than reproducing wheel/trackpad scaling.
 * Callers must supply the active list's state, including nested vertical lists when present.
 * The internal Compose API is intentionally isolated here; no patched dependency is installed.
 */
@Composable
fun Modifier.desktopInlineCollapseScrollBoundary(connection: NestedScrollConnection, atTop: () -> Boolean): Modifier {
    val config = LocalScrollConfig.current
    val density = LocalDensity.current
    val latestTop by rememberUpdatedState(atTop)
    val latestConnection by rememberUpdatedState(connection)
    return pointerInput(config, density) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed } || !latestTop()) continue
                val delta = with(config) { with(density) { calculateMouseWheelScroll(event, size) } }
                if (delta.y <= 0f || abs(delta.x) >= abs(delta.y)) continue
                val used = latestConnection.onPostScroll(Offset.Zero, Offset(0f, delta.y), NestedScrollSource.UserInput)
                if (used.y != 0f) event.changes.forEach { it.consume() }
            }
        }
    }.scrollable(rememberScrollableState { delta ->
        val available = Offset(0f, delta)
        val pre = connection.onPreScroll(available, NestedScrollSource.UserInput)
        val post = connection.onPostScroll(pre, available - pre, NestedScrollSource.UserInput)
        pre.y + post.y
    }, Orientation.Vertical)
}
