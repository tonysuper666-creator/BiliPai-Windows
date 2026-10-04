package com.bilipai.desktop.ui

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlayerFailure
import com.bilipai.desktop.player.PlayerFailureKind
import com.bilipai.desktop.player.platform.DesktopOfflineMedia3ErrorCodes
import com.android.purebilibili.feature.download.OfflinePlaybackFailure
import com.android.purebilibili.feature.download.resolveOfflinePlaybackFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Media3 state numbers are used by original pure policies; the state is read from the actual owned MPV. */
internal object DesktopOfflinePlaybackState { const val IDLE=1;const val BUFFERING=2;const val READY=3;const val ENDED=4 }
internal data class DesktopOfflineVideoSize(val width:Int,val height:Int)
/** Native failure metadata is retained; the number is only input to the original pure policy. */
internal data class DesktopOfflinePlaybackError(val errorCode:Int,val sourceVersion:Long,val nativeFailure:PlayerFailure?)
internal fun desktopOfflinePlaybackErrorCode(failure:PlayerFailure?):Int = when(failure?.kind) {
    PlayerFailureKind.DECODER -> DesktopOfflineMedia3ErrorCodes.ERROR_CODE_DECODING_FAILED
    PlayerFailureKind.UNSUPPORTED -> DesktopOfflineMedia3ErrorCodes.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    // MPV's FILE_IO groups missing and denied. Do not invent a distinction from error prose.
    else -> 0
}
internal fun desktopOfflineLoadErrorCode(failure:Exception):Int = when(failure) {
    is java.nio.file.NoSuchFileException,is java.io.FileNotFoundException -> DesktopOfflineMedia3ErrorCodes.ERROR_CODE_IO_FILE_NOT_FOUND
    is java.nio.file.AccessDeniedException,is SecurityException -> DesktopOfflineMedia3ErrorCodes.ERROR_CODE_IO_NO_PERMISSION
    else -> 0
}
internal interface DesktopOfflineMpvListener {
    fun onIsPlayingChanged(isPlaying:Boolean) {}
    fun onPlaybackStateChanged(playbackState:Int) {}
    fun onPlayerError(error:DesktopOfflinePlaybackError) {}
}

/** Control facade, never an ExoPlayer alias or a decoder. Every write reuses the same guarded Root MPV. */
internal class DesktopOfflineMpvControl(
    private val bindings:DesktopOriginalOfflinePlayerBindings,
    val taskId:String,
) {
    private var closed=false
    private val listeners=mutableMapOf<DesktopOfflineMpvListener,Job>()
    val nativePlayer:MpvPlayer get()=checkNotNull(bindings.backend.player){"Root offline MPV is unavailable"}
    val sourceVersion:Long? get()=bindings.backend.memory.sourceVersion.takeIf {isOwned()}
    fun isOwned()=!closed && bindings.isOwned() && bindings.backend.memory.current==taskId && bindings.backend.ownsAcceptedSource()
    /** Loading cover/control permission is separate from native-write permission. Foreign accepted tokens never become loading. */
    fun isForegroundOwned()=!closed && bindings.isOwned() && (isOwned() || bindings.backend.opening ||
        bindings.backend.memory.current==null && bindings.backend.memory.sourceVersion==null)
    private fun snapshot():PlayerState? = if(isOwned())nativePlayer.state.value else null
    val currentPosition:Long get()=(snapshot()?.positionSeconds?.takeIf {it.isFinite()}?.coerceAtLeast(0.0)?.times(1000))?.toLong() ?: 0L
    val duration:Long get()=(snapshot()?.durationSeconds?.takeIf {it.isFinite()}?.coerceAtLeast(0.0)?.times(1000))?.toLong() ?: 0L
    val bufferedPosition:Long get()=snapshot()?.let {state ->
        // Unknown native buffer is represented by the known current position, never a claimed full buffer.
        val position=state.positionSeconds.takeIf {it.isFinite()}?.coerceAtLeast(0.0) ?: 0.0
        val forward=state.bufferedForwardSeconds?.takeIf {it.isFinite()&&it>=0.0} ?: 0.0
        val capped=(position+forward).let {if(state.durationSeconds>0.0)it.coerceAtMost(state.durationSeconds) else it}
        (capped*1000).toLong()
    } ?: 0L
    val playbackState:Int get()=snapshot()?.let {state -> when {
        state.ended->DesktopOfflinePlaybackState.ENDED
        state.error!=null->DesktopOfflinePlaybackState.IDLE
        state.loading||state.pausedForCache->DesktopOfflinePlaybackState.BUFFERING
        state.firstVideoFrameReady||state.videoCodec!=null||state.audioCodec!=null->DesktopOfflinePlaybackState.READY
        else->DesktopOfflinePlaybackState.IDLE
    }} ?: DesktopOfflinePlaybackState.IDLE
    val isPlaying:Boolean get()=snapshot()?.let {state ->
        (state.nativePaused ?: state.paused).not() && !state.ended && state.error==null && !state.loading && !state.pausedForCache &&
            (state.firstVideoFrameReady||state.videoCodec!=null||state.audioCodec!=null)
    } ?: false
    val videoSize:DesktopOfflineVideoSize get()=snapshot()?.let {DesktopOfflineVideoSize(it.videoWidth,it.videoHeight)} ?: DesktopOfflineVideoSize(0,0)
    val playerError:DesktopOfflinePlaybackError? get() {
        val state=snapshot() ?: return null
        val version=sourceVersion ?: return null
        if(state.error==null)return null
        if(state.failure!=null && state.failure.sourceVersion!=version)return null
        val failure=state.failure?.takeIf {it.sourceVersion==version}
        return DesktopOfflinePlaybackError(desktopOfflinePlaybackErrorCode(failure),version,failure)
    }
    var playWhenReady:Boolean
        get()=snapshot()?.let {!(it.nativePaused ?: it.paused)} ?: false
        set(value){action{it.setPaused(!value)}}
    val playbackSpeed:Float get()=snapshot()?.speed?.toFloat() ?: 1f
    var volumePercent:Int
        get()=snapshot()?.volume?.toInt()?.coerceIn(0,100) ?: 0
        set(value){action{it.setVolume(value.coerceIn(0,100).toDouble())}}
    val viewportBrightness:Float get()=sourceVersion?.let {bindings.backend.overlay?.viewportBrightnessFor(it)} ?: 1f
    fun setViewportBrightness(value:Float):Boolean {
        val version=sourceVersion ?: return false
        var accepted=false
        action {accepted=bindings.backend.overlay?.setViewportBrightness(version,value)==true}
        return accepted
    }
    private fun action(block:(MpvPlayer)->Unit){if(isOwned())bindings.backend.runPlayerAction{if(isOwned())block(it)}}
    fun seekTo(positionMs:Long)=action{it.seekTo(positionMs.coerceAtLeast(0L)/1000.0)}
    fun play()=action{it.setPaused(false)}
    fun pause()=action{it.setPaused(true)}
    fun setPlaybackSpeed(speed:Float)=action{it.setSpeed(speed.toDouble())}
    suspend fun load(forceReload:Boolean=false,resumePositionMs:Long?=null,onEpisode:(String)->Unit):OfflinePlaybackFailure? {
        if(closed||!bindings.isOwned())throw CancellationException("Offline owner retired")
        val pending=bindings.backend.openOriginal(taskId,forceReload,resumePositionMs,onEpisode) ?: throw CancellationException("Offline owner retired")
        try {
            pending.join();currentCoroutineContext().ensureActive()
            if(pending.isCancelled)throw CancellationException("Offline request retired")
            if(closed||!bindings.isOwned())throw CancellationException("Offline owner retired")
            bindings.backend.loadErrorCode?.let {return resolveOfflinePlaybackFailure(it)}
            return playerError?.let {resolveOfflinePlaybackFailure(it.errorCode)}
        } catch(cancelled:CancellationException){pending.cancel();throw cancelled}
    }
    fun addListener(listener:DesktopOfflineMpvListener) {
        if(closed)return
        removeListener(listener)
        listeners[listener]=bindings.entryScope.launch {
            var previousPlaying:Boolean?=null;var previousState:Int?=null;var previousSize:DesktopOfflineVideoSize?=null
            var previousError:DesktopOfflinePlaybackError?=null
            nativePlayer.state.collect {
                if(!isOwned())return@collect
                val playing=isPlaying;val status=playbackState;val size=videoSize
                val error=playerError
                if(error!=null && error!=previousError)listener.onPlayerError(error)
                previousError=error
                if(playing!=previousPlaying){previousPlaying=playing;listener.onIsPlayingChanged(playing)}
                if(status!=previousState||size!=previousSize){previousState=status;previousSize=size;listener.onPlaybackStateChanged(status)}
            }
        }
    }
    fun removeListener(listener:DesktopOfflineMpvListener){listeners.remove(listener)?.cancel()}
    fun release(){
        if(closed)return
        val version=sourceVersion
        version?.let {bindings.backend.overlay?.clearViewportBrightness(it)}
        closed=true;listeners.values.forEach(Job::cancel);listeners.clear()
        bindings.backend.releaseOriginal(taskId,version)
    }
}
