package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/** Keeps the newest counted-bar presentation until the overlay has taken it.
 *
 * The v0.4.6 HotDanmakuBar reports its shown entries once and drops identical reports, while
 * DanmakuOverlay.updateHotBarPresentation answers false when the document of the source is not owned at that moment or
 * the reservation of the mount is not the current one. A rejected report would be lost, and the ordinary renderer would
 * draw the banner entries a second time until the bar reports something different. The old Windows host re-sent its
 * height on every recomposition and healed itself; the relay does the same, but only while a report is waiting.
 *
 * [deliver] returns true when the report was taken or is no longer wanted (the mount lost its source) and false when it
 * must be offered again. A newer report replaces an older waiting one. A report that makes [deliver] throw is not kept,
 * so a retry never repeats an invalid value. [resend] offers the last report once more after the overlay started a new
 * reservation, which begins without a selection.
 */
internal class DesktopHotPresentationRelay(private val deliver: (List<DanmakuItem>, Float) -> Boolean) {
    private val lock = Any()
    private var items: List<DanmakuItem> = emptyList()
    private var heightPx = 0f
    private var hasReport = false
    private val waiting = MutableStateFlow(false)

    /** True while the newest report has not been taken. */
    val isWaiting: StateFlow<Boolean> = waiting.asStateFlow()

    fun report(items: List<DanmakuItem>, heightPx: Float): Boolean = synchronized(lock) {
        val accepted = deliver(items, heightPx)
        this.items = items
        this.heightPx = heightPx
        hasReport = true
        waiting.value = !accepted
        accepted
    }

    /** Offers the newest report again when it is waiting; true when nothing is waiting afterwards. */
    fun retry(): Boolean = synchronized(lock) { if (waiting.value) offerStored() else true }

    /** Offers the last report again, taken or not, after a new reservation; true when nothing is waiting afterwards. */
    fun resend(): Boolean = synchronized(lock) { if (hasReport) offerStored() else true }

    private fun offerStored(): Boolean {
        val accepted = deliver(items, heightPx)
        waiting.value = !accepted
        return accepted
    }

    /** Runs for the life of the mount; it only works while a report is waiting. */
    suspend fun redeliverWhileWaiting(intervalMs: Long = RETRY_INTERVAL_MS) {
        waiting.collectLatest { rejected ->
            while (rejected && !retry()) delay(intervalMs)
        }
    }

    companion object {
        const val RETRY_INTERVAL_MS = 250L
    }
}
