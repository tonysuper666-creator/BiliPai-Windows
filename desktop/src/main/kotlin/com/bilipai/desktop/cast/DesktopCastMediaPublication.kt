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
    internal suspend fun <T> use(block: suspend (CastPluginMediaRequest) -> T): T = withContext(frame) {
        currentCoroutineContext().ensureActive();frame.admit { Unit }
        val media=resolve();frame.admit { Unit };block(media)
    }
}
internal class DesktopCastPublicationFrame(private val publication: DesktopPlaybackPublication,
    private val source: PlaybackSource, private val stillOwned: () -> Boolean, private val callerJob: Job? = null) :
    ThreadContextElement<DesktopCastPublication.Frame?>,AbstractCoroutineContextElement(Key),DesktopCastPublication.Frame {
    companion object Key:CoroutineContext.Key<DesktopCastPublicationFrame>
    override fun updateThreadContext(context:CoroutineContext)=DesktopCastPublication.replace(this)
    override fun restoreThreadContext(context:CoroutineContext,oldState:DesktopCastPublication.Frame?){DesktopCastPublication.replace(oldState)}
    override fun isCurrent()=stillOwned()&&publication.isCurrent(source)
    override fun admit(publication:Runnable){this.publication.admit(source,stillOwned){publication.run()}}
    fun <T> admit(block:()->T):T=publication.admit(source,stillOwned,block)
    fun calls(delegate:Call.Factory):Call.Factory=publication.calls(delegate,source,stillOwned,callerJob)
}
