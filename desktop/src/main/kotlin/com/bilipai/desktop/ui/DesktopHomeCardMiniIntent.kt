package com.bilipai.desktop.ui

import com.bilipai.desktop.data.VideoCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The accepted native request of the current ordinary player, as Home reads it. */
internal class DesktopHomeCardMiniAccepted(val bvid: String, val cid: Long, val sourceVersion: Long)

/** The existing Root player as the Home mini action needs it. The Root adapter reads the single Shell owner;
 * nothing here owns or retires a player, a queue, an assembly or a window. S is the playlist session type. */
internal interface DesktopHomeCardMiniPlayback<S : Any> {
    /** True when a mini window is open or opening: this slice never replaces its content. */
    fun pipActiveOrPending(): Boolean
    /** The original playlist session, or null when the Root has no ready ordinary player. */
    fun captureSession(): S?
    fun isSessionCurrent(session: S): Boolean
    /** bvid and cid of the playlist's current item. */
    fun currentItem(): Pair<String, Long>?
    /** True when the playlist source is ExternalPlaylistSource.NONE (the ordinary click semantics). */
    fun sourceIsNone(): Boolean
    fun accepted(): DesktopHomeCardMiniAccepted?
    /** The native source version of the current player before the open. */
    fun sourceVersion(): Long?
    /** The existing Root media exclusion of an ordinary click (Home -> VideoDetail, not fullscreen). */
    fun excludeOtherMedia(target: DesktopHomeCardTarget)
    /** openVideoDetail(card, 0, keepMatchingSource = true) with owner = null; true when the same source was reused. */
    fun open(card: VideoCard): Boolean
    /** True when the player's current load of bvid failed (403 and other errors). */
    fun loadFailed(bvid: String): Boolean
    /** One presentation-admitted HolderMini entry of the matching Success; false when it is not ready yet. */
    fun enterMini(accepted: DesktopHomeCardMiniAccepted): Boolean
}

internal sealed interface DesktopHomeCardMiniResult {
    /** The ordinary request was submitted; correlation waits for its accepted source. */
    class Waiting<S : Any>(val after: S, val reused: Boolean, val baselineVersion: Long?) : DesktopHomeCardMiniResult
    data object Entered : DesktopHomeCardMiniResult
    /** Another click, account or source replaced the request: the wait ends silently, nothing is stopped. */
    data object Withdrawn : DesktopHomeCardMiniResult
    class Rejected(val message: String) : DesktopHomeCardMiniResult
}

internal const val DESKTOP_HOME_MINI_PIP_BUSY = "已有小窗在播放，请先关闭当前小窗"
internal const val DESKTOP_HOME_MINI_NOT_READY = "播放器尚未就绪，未进入小窗"
internal const val DESKTOP_HOME_MINI_REJECTED = "播放请求已被替换，未进入小窗"
internal const val DESKTOP_HOME_MINI_FAILED = "视频加载失败，未进入小窗"

/** One Home mini request. start() runs on the EDT inside the Root navigation admission, in the order of an
 * ordinary click: baseline session and source, media exclusion, then the ordinary request. await() then only
 * accepts the source this request produced and enters the existing HolderMini once. */
internal class DesktopHomeCardMiniIntent<S : Any>(
    private val target: DesktopHomeCardTarget,
    private val playback: DesktopHomeCardMiniPlayback<S>,
) {
    /** admission is Root's navigationAdmission: false means nothing ran, so nothing plays and no mini opens. */
    fun start(admission: ((() -> Unit) -> Boolean)): DesktopHomeCardMiniResult {
        if (playback.pipActiveOrPending()) return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_PIP_BUSY)
        var result: DesktopHomeCardMiniResult = DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_NOT_READY)
        val admitted = admission { result = submit() }
        return if (admitted) result else DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_NOT_READY)
    }

    private fun submit(): DesktopHomeCardMiniResult {
        val baseline = playback.captureSession() ?: return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_NOT_READY)
        val baselineVersion = playback.sourceVersion()
        playback.excludeOtherMedia(target)
        // A Root that cannot take the ordinary request (retired between the checks) rejects it, never crashes.
        val reused = try { playback.open(target.card) } catch (_: IllegalStateException) {
            return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_NOT_READY)
        }
        return correlate(baseline, reused, baselineVersion)
    }

    /** Same EDT turn as the open: the queue must be this card, with the ordinary NONE source, in a new session
     * (or the reused session that already plays this card). Anything else is rejected now, never awaited. */
    private fun correlate(baseline: S, reused: Boolean, baselineVersion: Long?): DesktopHomeCardMiniResult {
        val after = playback.captureSession() ?: return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_REJECTED)
        val sessionOk = if (reused) playback.isSessionCurrent(baseline) && playback.accepted()?.let(::matches) == true
            else !playback.isSessionCurrent(baseline)
        val item = playback.currentItem()
        val itemOk = item != null && item.first == target.bvid && item.second == target.card.preferredCid
        if (!sessionOk || !itemOk || !playback.sourceIsNone())
            return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_REJECTED)
        return DesktopHomeCardMiniResult.Waiting(after, reused, baselineVersion)
    }

    private fun matches(accepted: DesktopHomeCardMiniAccepted): Boolean =
        accepted.bvid == target.bvid && accepted.cid != 0L &&
            (target.card.preferredCid <= 0L || accepted.cid == target.card.preferredCid)

    /** One check of the waiting request. null keeps waiting; it never stops or pauses anything. */
    fun check(waiting: DesktopHomeCardMiniResult.Waiting<S>): DesktopHomeCardMiniResult? {
        if (!playback.isSessionCurrent(waiting.after)) return DesktopHomeCardMiniResult.Withdrawn
        if (playback.pipActiveOrPending()) return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_PIP_BUSY)
        if (playback.loadFailed(target.bvid)) return DesktopHomeCardMiniResult.Rejected(DESKTOP_HOME_MINI_FAILED)
        val accepted = playback.accepted()?.takeIf(::matches) ?: return null
        // A source of this request is newer than every source the player had when it was submitted (the native
        // version only grows), so the publication of an earlier A of A -> B -> A is never taken for it.
        if (!waiting.reused && waiting.baselineVersion != null && accepted.sourceVersion <= waiting.baselineVersion) return null
        return if (playback.enterMini(accepted)) DesktopHomeCardMiniResult.Entered else null
    }

    /** Waits on the Root's own state changes (no polling loop). Cancelling the caller only withdraws the wait. */
    suspend fun await(waiting: DesktopHomeCardMiniResult.Waiting<S>, changes: Flow<Unit>): DesktopHomeCardMiniResult {
        var outcome: DesktopHomeCardMiniResult? = check(waiting)
        if (outcome != null) return outcome
        changes.first { outcome = check(waiting); outcome != null }
        return checkNotNull(outcome)
    }
}
