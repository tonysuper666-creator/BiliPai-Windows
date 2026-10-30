package com.bilipai.desktop.player

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** A desktop list at its edge can decline wheel events before nested post-scroll.
 * Compose normalizes remaining wheel/trackpad events at this parent; the existing
 * collapse connection still decides whether and how much to consume.
 */
@Composable
fun Modifier.desktopInlineCollapseScrollBoundary(connection: NestedScrollConnection): Modifier =
    scrollable(rememberScrollableState { delta ->
        val available = Offset(0f, delta)
        val pre = connection.onPreScroll(available, NestedScrollSource.UserInput)
        val post = connection.onPostScroll(pre, available - pre, NestedScrollSource.UserInput)
        pre.y + post.y
    }, Orientation.Vertical)
