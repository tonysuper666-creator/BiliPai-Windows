package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.DynamicVoteInfo
import com.android.purebilibili.data.model.response.GradeDanmakuSummary
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.danmaku.VoteOption
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlay
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Required request/presentation adapter; its lease is the captured source, not a new authority. */
internal interface DesktopWindowsCommandVotePlatform {
    val sourceLease: Any
    fun publish(caller: Job?, action: () -> Unit): Boolean
    /** Only release pending/loading in this captured UI state; never confirm a result. */
    fun capturePending(key: Any): DesktopWindowsCommandVotePending
    fun releasePending(pending: DesktopWindowsCommandVotePending, action: () -> Unit): Boolean
    fun feedback(message: String, caller: Job?)
    suspend fun getVoteInfo(voteId: Long): Result<DynamicVoteInfo>
    suspend fun submitVote(voteId: Long, optionIndexes: List<Int>): Result<Unit>
    suspend fun submitGradeDanmaku(aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int): Result<Unit>
    suspend fun getGradeDanmakuSummary(cid: Long, aid: Long, gradeId: String): Result<GradeDanmakuSummary>
}

/** UI-only request ticket: it grants no source, account or server permission. */
internal class DesktopWindowsCommandVotePending internal constructor(
    internal val sourceLease: Any, internal val key: Any, internal val token: Any,
)

internal val LocalDesktopWindowsCommandVotePlatform = staticCompositionLocalOf<DesktopWindowsCommandVotePlatform> {
    error("Command cards require their captured video source request/presentation adapter")
}

/** Every request uses the existing owned Operations. Gates contain no suspend, HTTP or native calls. */
internal class DesktopWindowsCommandVoteBinding(
    override val sourceLease: Any,
    private val aid: Long,
    private val cid: Long,
    private val current: () -> Boolean,
    private val cleanupCurrent: () -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
    private val readVote: suspend (Job, Long) -> Result<DynamicVoteInfo>,
    private val writeVote: suspend (Job, Long, List<Int>) -> Result<Unit>,
    private val writeGrade: suspend (Job, Long, Long, Long, String, Int) -> Result<Unit>,
    private val readGrade: suspend (Job, Long, Long, String) -> Result<GradeDanmakuSummary>,
    private val onFeedback: (String) -> Unit,
) : DesktopWindowsCommandVotePlatform {
    private val pendingLock = Any()
    private val pending = mutableMapOf<Any, Any>()

    override fun capturePending(key: Any): DesktopWindowsCommandVotePending {
        val token = Any()
        synchronized(pendingLock) { pending[key] = token }
        return DesktopWindowsCommandVotePending(sourceLease, key, token)
    }

    private fun checkpoint() {
        if (!current()) throw CancellationException("Video command source retired")
    }

    private suspend fun <T> request(action: suspend (Job) -> Result<T>): Result<T> {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        checkpoint()
        val result = action(caller.job)
        caller.ensureActive()
        checkpoint()
        return result
    }

    override fun publish(caller: Job?, action: () -> Unit): Boolean {
        if (!current() || caller?.isActive == false) return false
        var applied = false
        return admission {
            if (current() && caller?.isActive != false) {
                action()
                applied = true
            }
        } && applied
    }

    override fun releasePending(pending: DesktopWindowsCommandVotePending, action: () -> Unit): Boolean {
        if (pending.sourceLease !== sourceLease || !cleanupCurrent()) return false
        var applied = false
        return admission {
            if (cleanupCurrent()) synchronized(pendingLock) {
                if (this.pending[pending.key] === pending.token) {
                    this.pending.remove(pending.key)
                    action()
                    applied = true
                }
            }
        } && applied
    }

    override fun feedback(message: String, caller: Job?) {
        publish(caller) { onFeedback(message) }
    }

    override suspend fun getVoteInfo(voteId: Long): Result<DynamicVoteInfo> = request { job ->
        if (voteId <= 0L) Result.failure(IllegalArgumentException("缺少有效投票信息")) else readVote(job, voteId)
    }

    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>): Result<Unit> = request { job ->
        if (voteId <= 0L || optionIndexes.isEmpty() || optionIndexes.any { it <= 0 })
            Result.failure(IllegalArgumentException("缺少有效投票信息"))
        else writeVote(job, voteId, optionIndexes.toList())
    }

    override suspend fun submitGradeDanmaku(
        aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int,
    ): Result<Unit> = request { job ->
        if (aid != this.aid || cid != this.cid || aid <= 0L || cid <= 0L ||
            (gradeId.toLongOrNull() ?: 0L) <= 0L || gradeScore !in setOf(2, 4, 6, 8, 10))
            Result.failure(IllegalArgumentException("缺少有效打分信息"))
        else writeGrade(job, aid, cid, progress, gradeId, gradeScore)
    }

    override suspend fun getGradeDanmakuSummary(cid: Long, aid: Long, gradeId: String): Result<GradeDanmakuSummary> = request { job ->
        if (aid != this.aid || cid != this.cid || (gradeId.toLongOrNull() ?: 0L) <= 0L)
            Result.failure(IllegalArgumentException("缺少有效打分信息"))
        else readGrade(job, cid, aid, gradeId)
    }
}

/** Existing complete Section uses the same cards and source-bound port as Windows video. */
@Composable
internal fun DesktopWindowsSectionCommandOverlay(
    commandPort: DesktopWindowsCommandVotePlatform?,
    items: List<CommandDanmakuItem>,
    player: MpvPlayer,
    viewport: DanmakuViewport,
    state: CommandDanmakuOverlayState,
    fontScale: Float,
    onFollowClick: () -> Unit,
    onTripleClick: () -> Unit,
    onVoteSubmit: (CommandDanmakuItem, VoteOption, Int) -> Unit,
    isFollowing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (commandPort == null) return
    CompositionLocalProvider(LocalDesktopWindowsCommandVotePlatform provides commandPort) {
        CommandDanmakuOverlay(
            items = items, player = player, viewport = viewport, bottomInsetPx = 0,
            state = state, fontScale = fontScale, onFollowClick = onFollowClick,
            onTripleClick = onTripleClick, onVoteSubmit = onVoteSubmit,
            isFollowing = isFollowing, modifier = modifier,
        )
    }
}
