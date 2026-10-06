package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import javax.swing.SwingUtilities
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Actual pool/HotBar default-style command. Boolean means admitted, never remote success. */
internal fun dispatchDesktopOriginalDanmakuSameSend(
    text: String, playback: VideoPlaybackViewModel,
    expectedSource: OwnedPlaybackSourceSnapshot, sourceCurrent: () -> Boolean,
    stillOwned: () -> Boolean, admit: ((() -> Unit) -> Boolean),
): Boolean {
    check(SwingUtilities.isEventDispatchThread()) { "Danmaku same-send requires the desktop UI dispatcher" }
    if (text.isBlank() || !stillOwned() || playback.isSendingDanmaku.value) return false
    var allowed = false
    if (!admit { allowed = stillOwned() && !playback.isSendingDanmaku.value } || !allowed) return false
    // Original synchronous source/token capture and launch run after Store/entry/native
    // monitors return, on the same UI event turn; no additional actor or network owner.
    if (!stillOwned() || playback.isSendingDanmaku.value) return false
    playback.sendDanmaku(text, 16777215, 1, 25, false,
        desktopExpectedNativeSource = expectedSource, desktopExpectedCurrent = sourceCurrent)
    return true
}

/** A transient view of THIS admitted source only, borrowed by the original
 * invocation and actual danmaku protocol. It cannot resolve a latest owner.
 * Normal callers install no element. The ThreadLocal is restored by coroutines. */
internal class DesktopOriginalDanmakuExpectedSubmission private constructor(
    val source: OwnedPlaybackSourceSnapshot, private val current: () -> Boolean,
) : ThreadContextElement<DesktopOriginalDanmakuExpectedSubmission?>,
    AbstractCoroutineContextElement(Key) {
    fun isCurrentSource(actual: OwnedPlaybackSourceSnapshot?): Boolean = current() && actual != null &&
        actual.sourceVersion == source.sourceVersion && actual.source == source.source
    fun assertCurrent(actual: OwnedPlaybackSourceSnapshot?) {
        if (!isCurrentSource(actual)) throw CancellationException("Danmaku admitted source retired")
    }
    override fun updateThreadContext(context: CoroutineContext): DesktopOriginalDanmakuExpectedSubmission? =
        dispatched.get().also { dispatched.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: DesktopOriginalDanmakuExpectedSubmission?) {
        if (oldState == null) dispatched.remove() else dispatched.set(oldState)
    }
    companion object Key : CoroutineContext.Key<DesktopOriginalDanmakuExpectedSubmission> {
        private val dispatched = ThreadLocal<DesktopOriginalDanmakuExpectedSubmission?>()
        fun current(): DesktopOriginalDanmakuExpectedSubmission? = dispatched.get()
        fun capture(source: OwnedPlaybackSourceSnapshot?, current: (() -> Boolean)?): DesktopOriginalDanmakuExpectedSubmission? =
            source?.let { DesktopOriginalDanmakuExpectedSubmission(it, checkNotNull(current)) }
    }
}
