package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.store.TodayWatchFeedbackStore
import com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One detail-entry view of the existing global settings, account feedback and actions.
 * The recommendation context is the existing retained Home/Discovery context for the
 * captured account. This class does not construct a store, repository or network client.
 */
internal class DesktopOriginalVideoContentBindings(
    val context: DesktopOriginalPlayerSettingsContext,
    val homeSettings: DesktopHomeSettingsPort,
    private val recommendationContext: DesktopPluginContext,
    val blockedUps: DesktopHomeBlockedRequests,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val watchLater: suspend (Long, Boolean) -> Result<Boolean>,
    private val feedbackPort: (String) -> Unit,
    private val shareTextPort: (String, String) -> Unit,
    private val todayWatchFeedback: DesktopTodayWatchFeedbackWriteBinding,
    private val sourceWatchLater: suspend (DesktopOriginalVideoAcceptedPublication, () -> Boolean, Long, Boolean) -> Result<Boolean>,
    private val sourceBlockedUps: (DesktopOriginalVideoAcceptedPublication, () -> Boolean) -> DesktopHomeBlockedRequests,
) {
    /** Stateless source view of the same account/store/settings/actions. The metadata lease
     * is retained through transport, local write permits and final result/UI admission. */
    fun forSource(expected: DesktopOriginalVideoAcceptedPublication, stillSourceOwned: () -> Boolean,
        sourceAdmission: ((() -> Unit) -> Boolean)): DesktopOriginalVideoContentBindings {
        fun current() = isCurrent() && stillSourceOwned()
        fun commit(action: () -> Unit): Boolean {
            var applied = false
            return commitIfCurrent {
                if (current()) sourceAdmission {
                    if (current()) { action(); applied = true }
                }
            } && applied
        }
        return DesktopOriginalVideoContentBindings(context, homeSettings, recommendationContext,
            sourceBlockedUps(expected, ::current), ::current, ::commit,
            { aid, add -> sourceWatchLater(expected, ::current, aid, add) },
            feedbackPort, shareTextPort, todayWatchFeedback.forSource(::current, sourceAdmission),
            sourceWatchLater, sourceBlockedUps)
    }

    fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("Original video content owner retired")
        context.requireCurrent()
    }
    suspend fun toggleWatchLater(aid: Long, add: Boolean): Result<Boolean> {
        currentCoroutineContext().ensureActive(); requireCurrent()
        val result = watchLater(aid, add)
        currentCoroutineContext().ensureActive(); requireCurrent()
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }
    fun feedback(message: String) {
        requireCurrent()
        if (!commitIfCurrent { requireCurrent(); feedbackPort(message) })
            throw CancellationException("Original video feedback owner retired")
    }
    fun shareText(subject: String, text: String) {
        requireCurrent()
        // The supplied callback only admits a command to Root's one existing text actor.
        // Native waits and pane observation execute outside the SessionStore admission.
        if (!commitIfCurrent { requireCurrent(); shareTextPort(subject, text) })
            throw CancellationException("Original video sharing owner retired")
    }
    fun getFeedbackSnapshot(): TodayWatchFeedbackSnapshot {
        requireCurrent()
        return todayWatchFeedback.getSnapshot()
    }
    suspend fun saveFeedbackSnapshot(snapshot: TodayWatchFeedbackSnapshot) {
        currentCoroutineContext().ensureActive(); requireCurrent()
        todayWatchFeedback.saveSnapshot(snapshot)
        currentCoroutineContext().ensureActive(); requireCurrent()
    }
    suspend fun commitUi(block: () -> Unit) {
        val caller = currentCoroutineContext()
        caller.ensureActive(); requireCurrent()
        if (!commitIfCurrent { caller.ensureActive(); requireCurrent(); block() })
            throw CancellationException("Original video result owner retired")
    }
    fun cleanupUi(block: () -> Unit) {
        // Only busy cleanup may run after this caller was canceled, while the same entry
        // remains alive. The caller additionally compares its captured request identity.
        if (isCurrent()) commitIfCurrent { if (isCurrent()) block() }
    }
}

internal val LocalDesktopOriginalVideoContentBindings = staticCompositionLocalOf<DesktopOriginalVideoContentBindings> {
    error("Original complete video content requires the current detail entry bindings")
}
