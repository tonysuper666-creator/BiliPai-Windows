package com.bilipai.desktop.cast
import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import okhttp3.Call
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import su.litvak.chromecast.api.v2.DesktopCastPublication

public class DesktopCastMediaPublication internal constructor(private val frame: DesktopCastPublicationFrame,
    private val resolve: suspend () -> CastPluginMediaRequest) {
    /** Control-only view of this captured source; no media resolver or new LAN URL. */
    internal suspend fun <T> onSource(block: suspend () -> T): T {
        val caller = currentCoroutineContext()[Job]
        val operation = frame.forCaller(caller)
        return withContext(operation) {
            currentCoroutineContext().ensureActive(); operation.admit { Unit }
            val result = block()
            currentCoroutineContext().ensureActive(); operation.admit { Unit }
            result
        }
    }
    internal suspend fun <T> use(block: suspend (CastPluginMediaRequest) -> T): T = withContext(frame) {
        currentCoroutineContext().ensureActive();frame.admit { Unit }
        val media=resolve();frame.admit { Unit };block(media)
    }
}
internal class DesktopCastPublicationFrame(publication: DesktopPlaybackPublication,
    private val source: PlaybackSource, private val stillOwned: () -> Boolean, private val callerJob: Job? = null,
    private val nativeAdmission: DesktopNativePlaybackPublication? = null) :
    ThreadContextElement<DesktopCastPublication.Frame?>,AbstractCoroutineContextElement(Key),DesktopCastPublication.Frame {
    // An optional final facet of the existing source authority, never a second Store/session.
    // Only short enqueue/publication runs here; response waits and device IO stay outside.
    private val basePublication = publication
    private val publication: DesktopPlaybackPublication = if (nativeAdmission == null) basePublication else
        object : DesktopPlaybackPublication by basePublication {
            override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T =
                basePublication.admit(source, stillOwned) {
                    var result: Result<T>? = null
                    val accepted = checkNotNull(nativeAdmission).admit { result = runCatching(block) }
                    if (!accepted || result == null) throw CancellationException("Cast native source retired")
                    result!!.getOrThrow()
                }
            override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean,
                callerJob: Job?): Call.Factory = admittedPlaybackCalls(
                    basePublication.calls(delegate, source, stillOwned, callerJob), this, source, stillOwned, callerJob)
        }
    companion object Key:CoroutineContext.Key<DesktopCastPublicationFrame>
    override fun updateThreadContext(context:CoroutineContext)=DesktopCastPublication.replace(this)
    override fun restoreThreadContext(context:CoroutineContext,oldState:DesktopCastPublication.Frame?){DesktopCastPublication.replace(oldState)}
    override fun isCurrent()=stillOwned()&&publication.isCurrent(source)
    override fun admit(publication:Runnable){this.publication.admit(source,stillOwned){publication.run()}}
    fun <T> admit(block:()->T):T=publication.admit(source,stillOwned,block)
    // Borrow the same immutable authority with this actual control job's cancellation hook.
    fun forCaller(caller: Job?) = DesktopCastPublicationFrame(basePublication, source,
        { caller?.isCancelled != true && isCurrent() }, caller, nativeAdmission)
    fun calls(delegate:Call.Factory):Call.Factory=publication.calls(delegate,source,stillOwned,callerJob)
}
