package com.bilipai.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** Fresh input owner for this real Compose modal layer. Windows Escape is a
 * completed event. No fabricated OS predictive gesture or underlying route owner. */
internal class DesktopCommentDialogNavigation : NavigationEventDispatcherOwner, AutoCloseable {
    override val navigationEventDispatcher = NavigationEventDispatcher()
    val input = DirectNavigationEventInput()
    private var closed = false
    init { navigationEventDispatcher.addInput(input) }
    fun onKey(event: KeyEvent): Boolean {
        if (closed || event.key != Key.Escape || event.type != KeyEventType.KeyDown) return false
        input.backCompleted()
        return true
    }
    override fun close() {
        if (closed) return
        closed = true
        navigationEventDispatcher.removeInput(input)
        navigationEventDispatcher.dispose()
    }
}

internal val LocalDesktopCommentDialogOwner = staticCompositionLocalOf<DesktopCommentDialogNavigation?> { null }

@Composable internal fun DesktopCommentDialogNavigationHost(content: @Composable () -> Unit) {
    val owner = remember { DesktopCommentDialogNavigation() }
    val focus = remember { FocusRequester() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(focus) { focus.requestFocus() }
    CompositionLocalProvider(LocalDesktopCommentDialogOwner provides owner,
        LocalNavigationEventDispatcherOwner provides owner) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent(owner::onKey).focusRequester(focus).focusable()) { content() }
    }
}
