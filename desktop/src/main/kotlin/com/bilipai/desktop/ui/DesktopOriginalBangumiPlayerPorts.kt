package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.PlayerInfoData
import com.android.purebilibili.data.model.response.SendDanmakuData
import com.android.purebilibili.feature.bangumi.BangumiFollowSession
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** The two danmaku operations the v0.4.6 PGC view model uses. Root binds them to the ordinary video
 * owner's existing send protocol, so Bangumi sends reuse the same CSRF, account epoch and accepted
 * native source admission as an ordinary video; no second danmaku writer exists. */
internal interface DesktopOriginalBangumiPlayerDanmaku {
    /** True when the server closed danmaku for this CID, or when the answer cannot be trusted. */
    fun isDanmakuServerDisabled(cid: Long): Boolean
    suspend fun sendDanmaku(aid: Long, cid: Long, message: String, progress: Long, color: Int,
        fontSize: Int, mode: Int, colorful: Boolean): Result<SendDanmakuData>
}

/** Default of a port set that was built without a danmaku binding: nothing can be sent. */
internal object DesktopOriginalBangumiUnavailableDanmaku : DesktopOriginalBangumiPlayerDanmaku {
    override fun isDanmakuServerDisabled(cid: Long): Boolean = true
    override suspend fun sendDanmaku(aid: Long, cid: Long, message: String, progress: Long, color: Int,
        fontSize: Int, mode: Int, colorful: Boolean): Result<SendDanmakuData> =
        Result.failure(IllegalStateException("弹幕发送端口未提供"))
}

/** Presenter-lifetime view over the ordinary owner's danmaku binding. The ordinary binding validates the
 * accepted native source and the subject on every call and throws when either changed; here a changed
 * source reads as "closed" and a failed send becomes a failed Result, while cancellation always
 * propagates and a retired presenter never receives a late result. */
internal class DesktopOriginalBangumiPlayerDanmakuView(
    private val serverDisabled: (Long) -> Boolean,
    private val send: suspend (aid: Long, cid: Long, message: String, progress: Long, color: Int,
        fontSize: Int, mode: Int, colorful: Boolean) -> Result<SendDanmakuData>,
    private val assertOwned: () -> Unit,
) : DesktopOriginalBangumiPlayerDanmaku {
    override fun isDanmakuServerDisabled(cid: Long): Boolean {
        assertOwned()
        val disabled = try {
            serverDisabled(cid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
        assertOwned()
        return disabled
    }

    override suspend fun sendDanmaku(aid: Long, cid: Long, message: String, progress: Long, color: Int,
        fontSize: Int, mode: Int, colorful: Boolean): Result<SendDanmakuData> {
        currentCoroutineContext().ensureActive()
        assertOwned()
        val result = try {
            send(aid, cid, message, progress, color, fontSize, mode, colorful)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        currentCoroutineContext().ensureActive()
        assertOwned()
        return result
    }
}

/** Windows has no process-wide TokenManager. The PGC presenter belongs to one account epoch, so the
 * epoch is the opaque identity the v0.4.6 follow caches compare; it never carries a credential. */
internal fun desktopOriginalBangumiFollowSession(accountEpoch: Long): BangumiFollowSession =
    BangumiFollowSession(null, "account-epoch:$accountEpoch")

/** What the v0.4.6 PGC subtitle state holder reads and writes besides the player itself. The Windows
 * Bangumi screen supplies it from the existing subtitle preferences, the muted state of the shared
 * native player and the ordinary owner's player-info and subtitle-cue requests, so that the carried
 * state holder neither touches Android settings nor a global video repository. */
internal interface DesktopOriginalBangumiSubtitlePort {
    val autoPreference: Flow<SubtitleAutoPreference>
    val positionLocked: Flow<Boolean>
    val verticalOffsetFraction: Flow<Float>
    val isMuted: Flow<Boolean>
    suspend fun getPlayerInfo(bvid: String, cid: Long): Result<PlayerInfoData>
    suspend fun getSubtitleCues(subtitleUrl: String, bvid: String, cid: Long, subtitleId: Long,
        subtitleIdStr: String, subtitleLan: String): Result<List<SubtitleCue>>
    suspend fun setPositionLocked(locked: Boolean)
    suspend fun setVerticalOffsetFraction(value: Float)
}
