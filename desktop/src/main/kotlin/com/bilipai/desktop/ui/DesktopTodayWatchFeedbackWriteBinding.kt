package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot
import com.android.purebilibili.core.store.TodayWatchFeedbackStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A stateless write view of the SAME retained Home recommendation context.
 * isCurrent/commitIfCurrent are its actual Home gate, never the ordinary-video entry.
 * A permit accepts one global commit; stage/fsync/rename are outside Root admission.
 */
internal class DesktopTodayWatchFeedbackWriteBinding(
    private val context: DesktopPluginContext,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
) {
    /** Keep the actual Home gate before source admission. Only permit minting enters
     * these monitors; original snapshot staging/fsync/rename remain outside them. */
    fun forSource(stillSourceOwned: () -> Boolean,
        sourceAdmission: ((() -> Unit) -> Boolean)): DesktopTodayWatchFeedbackWriteBinding {
        fun current() = isCurrent() && stillSourceOwned()
        fun commit(action: () -> Unit): Boolean {
            var applied = false
            return commitIfCurrent {
                if (current()) sourceAdmission {
                    if (current()) { action(); applied = true }
                }
            } && applied
        }
        return DesktopTodayWatchFeedbackWriteBinding(context, ::current, ::commit)
    }

    private fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("TodayWatch recommendation owner retired")
    }
    fun getSnapshot(): TodayWatchFeedbackSnapshot {
        requireCurrent()
        return TodayWatchFeedbackStore.getSnapshot(context).also { requireCurrent() }
    }
    suspend fun saveSnapshot(snapshot: TodayWatchFeedbackSnapshot) = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); requireCurrent() }
        fun acquirePermit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            lateinit var permit: DesktopPluginStore.OriginalPreferenceWritePermit
            checkRequest()
            if (!commitIfCurrent {
                    checkRequest()
                    permit = DesktopPluginStore.OriginalPreferenceWritePermit(context.store)
                }) throw CancellationException("TodayWatch recommendation commit retired")
            return permit
        }
        TodayWatchFeedbackStore.saveSnapshot(context, snapshot, ::checkRequest, ::acquirePermit)
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); requireCurrent() }
        fun acquirePermit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            lateinit var permit: DesktopPluginStore.OriginalPreferenceWritePermit
            checkRequest()
            if (!commitIfCurrent {
                    checkRequest()
                    permit = DesktopPluginStore.OriginalPreferenceWritePermit(context.store)
                }) throw CancellationException("TodayWatch recommendation clear retired")
            return permit
        }
        TodayWatchFeedbackStore.clear(context, ::checkRequest, ::acquirePermit)
    }
}
