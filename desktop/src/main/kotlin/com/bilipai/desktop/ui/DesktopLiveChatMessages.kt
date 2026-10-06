package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.feature.live.shouldRenderLiveDanmakuImageEmoticon
import com.android.purebilibili.feature.live.components.DesktopOriginalLiveChatImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The production live message list. Its caller owns the room session and reply/send state. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopLiveChatMessages(
    sessionKey: Any,
    messages: List<LiveDanmakuItem>,
    isLoggedIn: Boolean,
    onReply: (LiveDanmakuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = remember(sessionKey) { LazyListState() }
    val timeline = remember(sessionKey) { DesktopLiveChatTimeline<LiveDanmakuItem>() }
    val rows = remember(sessionKey, messages) { timeline.update(messages) }
    val currentRows by rememberUpdatedState(rows)
    var follow by remember(sessionKey) { mutableStateOf(DesktopLiveChatFollowState()) }
    var pointerHeld by remember(sessionKey) { mutableStateOf(false) }
    var automaticScroll by remember(sessionKey) { mutableStateOf(false) }
    var followJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var inputJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    fun nearBottom(): Boolean {
        val layout = listState.layoutInfo
        return desktopLiveChatNearBottom(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
    }
    fun userInput() {
        follow = follow.userInput()
        followJob?.cancel()
        inputJob?.cancel()
        inputJob = scope.launch {
            // Observe the scroll/layout produced by this input, not the preceding frame.
            withFrameNanos { }
            do {
                snapshotFlow { !pointerHeld && !listState.isScrollInProgress }.first { it }
                withFrameNanos { }
            } while (pointerHeld || listState.isScrollInProgress)
            follow = follow.userInputSettled(nearBottom())
        }
    }
    LaunchedEffect(sessionKey) {
        snapshotFlow { listState.isScrollInProgress to automaticScroll }.collect { (scrolling, automatic) ->
            if (scrolling && !automatic) userInput()
        }
    }
    LaunchedEffect(sessionKey) {
        // collect + delay batches a continuous burst; collectLatest would keep restarting it.
        snapshotFlow { currentRows.lastOrNull()?.key to follow.revision }.collect {
            delay(300L)
            if (currentRows.isNotEmpty() && follow.mayFollow(pointerHeld, listState.isScrollInProgress)) {
                val target = currentRows.lastIndex
                automaticScroll = true
                val animation = scope.launch { listState.animateScrollToItem(target) }
                followJob = animation
                try { animation.join() } finally {
                    automaticScroll = false
                    if (followJob === animation) followJob = null
                }
            }
        }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val chatInput = Modifier
        .pointerInput(sessionKey, windowFocused) {
            if (!windowFocused) return@pointerInput
            awaitEachGesture {
                try {
                    // Observe every mouse button without consuming the actual click/selection.
                    var event = awaitPointerEvent(PointerEventPass.Initial)
                    while (event.changes.none { it.pressed }) event = awaitPointerEvent(PointerEventPass.Initial)
                    pointerHeld = true
                    userInput()
                    while (event.changes.any { it.pressed }) event = awaitPointerEvent(PointerEventPass.Initial)
                } finally {
                    // Also runs on lost focus, gesture cancellation, session change and disposal.
                    pointerHeld = false
                }
            }
        }
        .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { userInput() }
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in setOf(Key.DirectionUp, Key.DirectionDown,
                    Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)) userInput()
            false
        }
    DisposableEffect(sessionKey) { onDispose { followJob?.cancel(); inputJob?.cancel() } }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().then(chatInput), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.key }) { entry ->
                val item = entry.item
                Column(Modifier.fillMaxWidth().clickable(enabled = isLoggedIn && item.uid > 0) { onReply(item) }) {
                    Text(buildList {
                        if (item.isSuperChat) add("SC ${item.superChatPrice}")
                        if (item.medalName.isNotBlank()) add("${item.medalName} ${item.medalLevel}")
                        add(item.uname.ifBlank { "用户 ${item.uid}" })
                    }.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = if (item.isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.replyToName.isNotBlank()) Text("回复 ${item.replyToName}", style = MaterialTheme.typography.labelSmall)
                    var imageFailed by remember(sessionKey, item.emoticonUrl) { mutableStateOf(false) }
                    if (shouldRenderLiveDanmakuImageEmoticon(item.emoticonUrl) && !imageFailed) {
                        key(sessionKey, item.emoticonUrl) {
                            DesktopOriginalLiveChatImage(item, onError = { imageFailed = true })
                        }
                    } else {
                        Text(item.text.ifBlank { if (item.emoticonUrl != null) "[表情]" else "" },
                            color = Color(item.color or (0xff shl 24)))
                    }
                }
            }
        }
        if (rows.isNotEmpty() && !follow.following && !nearBottom()) {
            FilledTonalButton(onClick = {
                inputJob?.cancel()
                follow = follow.resume()
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) { Text("回到底部") }
        }
    }
}
