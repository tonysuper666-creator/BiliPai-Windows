package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Root provides the existing DesktopNativeTextShare::share. This callback owns no native session. */
internal fun interface DesktopTextShareBindings {
    suspend fun share(title: String, text: String, stillOwned: () -> Boolean): Boolean
}

/** Null is an explicit unmounted platform boundary for standalone source consumers. */
internal val LocalDesktopTextShareBindings = staticCompositionLocalOf<DesktopTextShareBindings?> { null }

/** Async admission uses the existing caller page scope, so closing that page cancels a
 * queued/native share through the existing actor's cancellation cleanup. The Root callback
 * receives this same live owner predicate, including the caller's captured account epoch.
 */
internal fun requestDesktopTextShare(
    bindings: DesktopTextShareBindings?,
    scope: CoroutineScope?,
    title: String,
    text: String,
    stillOwned: () -> Boolean,
    feedback: (String) -> Unit,
): Job? {
    if (!stillOwned() || scope?.isActive == false) return null
    if (bindings == null || scope == null) {
        if (stillOwned()) feedback("Windows 系统文字分享尚未接入，可使用复制")
        return null
    }
    return scope.launch {
        try {
            ensureActive()
            if (!stillOwned()) return@launch
            val shown = bindings.share(title, text, stillOwned)
            ensureActive()
            if (!shown && stillOwned()) feedback("无法打开 Windows 系统分享面板")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            ensureActive()
            if (stillOwned()) feedback(failure.message ?: "系统分享失败")
        }
    }
}
