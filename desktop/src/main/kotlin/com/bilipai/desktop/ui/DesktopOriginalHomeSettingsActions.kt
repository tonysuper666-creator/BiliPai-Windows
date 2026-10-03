package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A Home UI request uses the existing original preference journal and Store final publisher.
 * Scope and ownership are required references to the actual mounted settings page.
 * There is no copied settings authority. Original notices publish only after successful CAS.
 */
internal class DesktopOriginalHomeSettingsActions(
    private val scope: CoroutineScope,
    private val context: DesktopOriginalPlayerSettingsContext,
    private val owns: () -> Boolean,
    private val notice: (String) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    init { require(scope.coroutineContext[Job] != null) { "Actual Home settings page Job is required" } }

    internal class Action {
        internal val committedNotices = mutableListOf<String>()
        internal var resetBackToTop: DesktopFavoritePreferences? = null
        fun afterCommitNotice(message: String) { committedNotices += message }
        fun afterCommitResetBackToTopOffset(preferences: DesktopFavoritePreferences) { resetBackToTop = preferences }
    }

    fun launch(block: suspend Action.() -> Unit): Job = scope.launch {
        val action = Action()
        try {
            DesktopOriginalPlaybackPreferenceOperation.run(context, owns) { action.block() }
            if (action.committedNotices.isNotEmpty() || action.resetBackToTop != null) context.commit {
                if (owns()) {
                    action.resetBackToTop?.updateBackToTopOffset(0f, 0f)
                    action.committedNotices.forEach(notice)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (owns()) failure(error)
        }
    }
}
