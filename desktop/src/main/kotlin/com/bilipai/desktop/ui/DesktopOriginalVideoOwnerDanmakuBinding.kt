package com.bilipai.desktop.ui

import com.android.purebilibili.data.repository.DesktopOriginalDanmakuProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoDanmakuSendProtocol
import com.android.purebilibili.data.repository.DanmakuThumbupState
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import kotlinx.coroutines.*

/** Request-local views over Root's existing primary API, CSRF and raw Overlay.
 * Each suspend action captures its actual Job through the supplied factory; no
 * completed Binding, HTTP client, MID state or danmaku CID cache is retained. */
internal class DesktopOriginalVideoOwnerDanmakuBinding(
    private val requestBinding: () -> DesktopOriginalVideoRepositoryBinding,
    private val requireNative: () -> DesktopOriginalVideoNativeOwner,
    private val overlay: DanmakuOverlay,
    private val currentSubject: () -> VideoSubjectSnapshot?,
    private val stillOwned: () -> Boolean,
) : DesktopOriginalVideoOwnerDanmaku {
    private fun checkEntry() { if (!stillOwned()) throw CancellationException("Video danmaku entry retired") }
    override fun isDanmakuServerDisabled(cid: Long): Boolean {
        checkEntry()
        val native = requireNative()
        val accepted = checkNotNull(native.current()) { "No accepted danmaku source" }
        check(accepted.request.cid == cid && native.isCurrent(accepted)) { "Danmaku CID/source changed" }
        val disabled = overlay.serverDisabledFor(cid, accepted.sourceVersion)
        checkEntry(); check(native.isCurrent(accepted)) { "Danmaku source retired" }
        return disabled
    }
    private class Operation(val binding: DesktopOriginalVideoRepositoryBinding, val check: () -> Unit)
    private suspend fun operation(cid: Long, aid: Long? = null): Operation {
        currentCoroutineContext().ensureActive(); checkEntry()
        val native = requireNative()
        val accepted = checkNotNull(native.current()) { "No accepted danmaku mutation source" }
        val expected = DesktopOriginalDanmakuExpectedSubmission.current()
        expected?.assertCurrent(accepted.nativeSource)
        val subject = checkNotNull(currentSubject()) { "No original video subject" }
        if (subject.cid != cid || (aid != null && subject.aid != aid) ||
            accepted.request.cid != cid || accepted.request.bvid != subject.bvid)
            throw CancellationException("Danmaku mutation subject changed")
        // Consume the SAME fixed invocation's binding. The required getter must
        // fail without that invocation; it cannot capture latest credentials.
        val binding = requestBinding()
        val caller = checkNotNull(currentCoroutineContext()[Job])
        val check = {
            caller.ensureActive(); checkEntry(); binding.assertCurrent()
            expected?.assertCurrent(accepted.nativeSource)
            val now = currentSubject()
            if (now == null || now.bvid != subject.bvid || now.cid != subject.cid ||
                now.aid != subject.aid || now.generation != subject.generation || !native.isCurrent(accepted))
                throw CancellationException("Danmaku mutation source/subject retired")
        }
        check()
        return Operation(binding, check)
    }
    private fun send(value: Operation) =
        DesktopOriginalVideoDanmakuSendProtocol(value.binding.primaryApi, value.binding::primaryCsrf, value.check)
    private fun menu(value: Operation) =
        DesktopOriginalDanmakuProtocol(value.binding.primaryApi, value.binding::primaryCsrf, value.check)
    override suspend fun sendDanmaku(aid: Long, cid: Long, message: String, progress: Long, color: Int,
        fontSize: Int, mode: Int, colorful: Boolean, upIdentity: Boolean): Result<SendDanmakuData> =
        send(operation(cid, aid)).sendDanmaku(aid, cid, message, progress, color, fontSize, mode, colorful, upIdentity)
    override suspend fun sendAttentionCommandDanmaku(aid: Long, cid: Long, progress: Long): Result<CommandDanmakuData> =
        send(operation(cid, aid)).sendAttentionCommandDanmaku(aid, cid, progress)
    override suspend fun getDanmakuThumbupState(cid: Long, dmid: Long): Result<DanmakuThumbupState> =
        menu(operation(cid)).getDanmakuThumbupState(cid, dmid)
    override suspend fun recallDanmaku(cid: Long, dmid: Long): Result<String> = menu(operation(cid)).recallDanmaku(cid, dmid)
    override suspend fun likeDanmaku(cid: Long, dmid: Long, like: Boolean): Result<Unit> = menu(operation(cid)).likeDanmaku(cid, dmid, like)
    override suspend fun reportDanmaku(cid: Long, dmid: Long, reason: Int, content: String): Result<Unit> =
        menu(operation(cid)).reportDanmaku(cid, dmid, reason, content)
}
