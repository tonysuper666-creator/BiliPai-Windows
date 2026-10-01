package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Actual NavigationEvent progress. Windows Escape completes a flow without invented samples. */
@Composable
fun DesktopSubscriptionPredictiveBackHandler(enabled: Boolean, onBack: suspend (Flow<Float>) -> Unit) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onBack)
    class Gesture(val samples: Channel<Float>, var job: Job? = null)
    val holder = remember { arrayOfNulls<Gesture>(1) }
    fun start(): Gesture {
        holder[0]?.let { return it }
        val gesture = Gesture(Channel(Channel.CONFLATED))
        holder[0] = gesture
        gesture.job = scope.launch {
            try { callback(gesture.samples.receiveAsFlow()) }
            finally { if (holder[0] === gesture) holder[0] = null; gesture.samples.cancel() }
        }
        return gesture
    }
    fun cancel() {
        val gesture = holder[0] ?: return
        holder[0] = null
        gesture.samples.cancel(CancellationException("Navigation gesture cancelled"))
    }
    LaunchedEffect(state, enabled) {
        if (!enabled) { cancel(); return@LaunchedEffect }
        snapshotFlow { state.transitionState }.collect { transition ->
            if (transition is NavigationEventTransitionState.InProgress)
                start().samples.trySend(transition.latestEvent.progress)
        }
    }
    DisposableEffect(state) { onDispose { cancel(); holder[0]?.job?.cancel() } }
    NavigationBackHandler(state = state, isBackEnabled = enabled,
        onBackCancelled = { cancel() }, onBackCompleted = { start().samples.close() })
}
