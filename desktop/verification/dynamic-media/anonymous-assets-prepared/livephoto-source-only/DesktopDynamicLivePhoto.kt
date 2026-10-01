package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.android.purebilibili.core.util.HapticType
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Root supplies its EXISTING host-visible/non-iconified state. No new Window
 * or independently persisted lifecycle owner. This default fails explicitly. */
internal val LocalDesktopLivePhotoWindowStarted = staticCompositionLocalOf<Boolean> {
    error("LivePhoto requires the existing Root Window lifecycle binding")
}

internal class DesktopDynamicLivePhotoPlayer(internal val native:MpvPlayer) {
    fun seekTo(milliseconds:Long)=native.seekTo(milliseconds.coerceAtLeast(0)/1000.0)
    fun play()=native.setPaused(false)
}
/** Separate media owner, no account Cookie and no main player/SMTC takeover.
 * Static compile does not prove HWND transforms or actual device decoding. */
@Composable internal fun DesktopDynamicLivePhotoPlayback(
    videoUrl:String,modifier:Modifier=Modifier,isPlaying:Boolean=true,isMuted:Boolean=false,
    playerRef:((DesktopDynamicLivePhotoPlayer?)->Unit)?=null,onClick:(()->Unit)?=null,
    onLongPress:(()->Unit)?=null,
) {
    val platform=LocalDesktopDynamicCardBindings.current
    val windowStarted=LocalDesktopLivePhotoWindowStarted.current
    val native=remember(videoUrl,platform){MpvPlayer()}
    val wrapper=remember(native){DesktopDynamicLivePhotoPlayer(native)}
    val state by native.state.collectAsState()
    // Existing native adapter signal: MPV_EVENT_PLAYBACK_RESTART + video output
    // dimensions/codec. It has NOT been proven as actual displayed HWND pixels.
    val hasFrame=state.firstVideoFrameReady&&state.error==null&&state.failure==null
    val failed=state.error!=null||state.failure!=null
    val latestClick by rememberUpdatedState(onClick)
    val latestLongPress by rememberUpdatedState(onLongPress)
    DisposableEffect(native,platform) {
        // A SwingPanel's video Canvas receives native mouse events rather than
        // Compose pointer events. Bind the original callbacks to that surface.
        val components=mutableListOf<Component>()
        var longPressed=false
        var pressed=false
        val timer=Timer(500) {
            if(pressed&&platform.isOwned()&&latestLongPress!=null){longPressed=true;latestLongPress?.invoke()}
        }.apply{isRepeats=false}
        val listener=object:MouseAdapter(){
            override fun mousePressed(event:MouseEvent){
                if(event.button!=MouseEvent.BUTTON1||!platform.isOwned())return
                pressed=true;longPressed=false;timer.restart()
            }
            override fun mouseReleased(event:MouseEvent){
                val click=pressed&&!longPressed&&event.button==MouseEvent.BUTTON1
                pressed=false;timer.stop()
                if(click&&platform.isOwned())latestClick?.invoke()
            }
            override fun mouseExited(event:MouseEvent){pressed=false;timer.stop()}
        }
        fun bind(component:Component){
            components+=component;component.addMouseListener(listener)
            if(component is Container)component.components.forEach(::bind)
        }
        fun mount(){bind(native.surface)}
        if(SwingUtilities.isEventDispatchThread())mount()else SwingUtilities.invokeAndWait(::mount)
        onDispose{
            val detach={pressed=false;timer.stop();components.forEach{it.removeMouseListener(listener)};components.clear()}
            if(SwingUtilities.isEventDispatchThread())detach()else SwingUtilities.invokeAndWait(detach)
        }
    }
    DisposableEffect(native){
        if(platform.isOwned()){
            native.setLoop(true);native.setMuted(isMuted)
            native.load(PlaybackSource(videoUrl=videoUrl,referer="https://www.bilibili.com/",cookieHeader="",title="实况照片",startPaused=!isPlaying||!windowStarted))
            playerRef?.invoke(wrapper)
        }
        onDispose{playerRef?.invoke(null);native.close()}
    }
    // Original pause/stop/resume intent mapped only to the existing Window's
    // started state; a stopped/minimized host cannot be resumed by a UI update.
    LaunchedEffect(native,isPlaying,isMuted,windowStarted){if(platform.isOwned()){
        native.setPaused(!isPlaying||!windowStarted);native.setMuted(isMuted)
    }}
    Box(modifier=modifier){
        // Exact original first-frame alpha decision. SwingPanel/native HWND
        // alpha and Compose overlay stacking remain UNVERIFIED platform work.
        SwingPanel(factory={native.surface},background=Color.Black,
            modifier=Modifier.fillMaxSize().graphicsLayer { alpha=if(hasFrame)1f else 0f })
        if(failed){
            AppTextButton(onClick={
                if(platform.isOwned()&&windowStarted){
                    // Existing owned native reload maps original seek(0), prepare,
                    // play. No main player, Cookie, SMTC or replacement owner.
                    native.recoverSource(native.currentSourceVersion,positionSeconds=0.0,paused=false,
                        expectedFailureAttemptId=state.failure?.attemptId)
                }
            },modifier=Modifier.align(Alignment.BottomStart).padding(16.dp)){
                AppText("实况加载失败 · 点击重试",color=Color.White)
            }
        }
    }
}
@Composable internal fun rememberDynamicPlatformHaptic():(HapticType)->Unit {
    val feedback=LocalHapticFeedback.current
    return remember(feedback){{type:HapticType->feedback.performHapticFeedback(if(type==HapticType.LIGHT||type==HapticType.SELECTION)HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)}}
}
