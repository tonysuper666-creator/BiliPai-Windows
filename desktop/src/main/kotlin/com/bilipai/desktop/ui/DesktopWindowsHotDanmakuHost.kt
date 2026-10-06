package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalDanmakuSession
import com.android.purebilibili.feature.video.ui.overlay.HotDanmakuBar
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences

/** Only a reference to the already mounted source session. No second liked set, Store or VM. */
internal class DesktopWindowsHotDanmakuAttachment(
    val lease:DesktopOriginalVideoAcceptedPublication,
    val environment:DesktopDanmakuSessionEnvironment,
    val session:DesktopOriginalDanmakuSession,
    val preferences:DesktopOriginalDanmakuPreferences,
)
internal class DesktopWindowsHotDanmakuLink {
    var current by mutableStateOf<DesktopWindowsHotDanmakuAttachment?>(null)
        private set
    fun bind(value:DesktopWindowsHotDanmakuAttachment) {current=value}
    fun release(value:DesktopWindowsHotDanmakuAttachment) {if(current===value)current=null}
}

/** Readback events from the sole native actor; enqueue acknowledgements never enter this bridge. */
internal class DesktopHotDanmakuNativeClock(
    private val position:()->Long,
    private val owned:()->Boolean,
    initial:PlayerState,
):DesktopHotDanmakuPlayer {
    private val listeners=linkedSetOf<DesktopHotDanmakuPlayer.Listener>()
    private var completed=initial.seekCompletedId
    private var previous=(initial.positionSeconds*1000.0).toLong()
    override val currentPosition:Long get()=if(owned())position() else 0L
    override fun addListener(listener:DesktopHotDanmakuPlayer.Listener) {if(owned())listeners+=listener}
    override fun removeListener(listener:DesktopHotDanmakuPlayer.Listener) {listeners-=listener}
    fun accept(state:PlayerState) {
        if(!owned())return
        val current=(state.positionSeconds*1000.0).toLong().coerceAtLeast(0L)
        val readback=state.seekCompletedPositionSeconds
        if(state.seekCompletedId>completed && readback!=null && readback.isFinite() && readback>=0.0) {
            completed=state.seekCompletedId
            val old=DesktopHotDanmakuPlayer.PositionInfo(previous)
            val next=DesktopHotDanmakuPlayer.PositionInfo((readback*1000.0).toLong())
            listeners.toList().forEach {if(owned())it.onPositionDiscontinuity(old,next,1)}
        }
        previous=current
    }
}

/** Mounted only inside the existing native command popup over the actual video surface. */
@Composable internal fun DesktopWindowsHotDanmakuHost(
    link:DesktopWindowsHotDanmakuLink,
    lease:DesktopOriginalVideoAcceptedPublication,
    overlay:DanmakuOverlay,
    player:MpvPlayer,
    assembly:DesktopOriginalVideoOwnerAssembly,
    stillOwned:()->Boolean,
    sourceCurrent:()->Boolean,
    admit:(()->Unit)->Boolean,
) {
    val attachment=link.current?.takeIf {it.lease===lease} ?: return
    key(attachment) {
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestAdmission by rememberUpdatedState(admit)
        fun owned()=latestOwned() && attachment.environment.isOwned() && assembly.native.isCurrent(lease)
        val preferences=attachment.preferences
        val enabled by remember(preferences){preferences.getDanmakuHotBarEnabled()}.collectAsState(preferences.currentDanmakuHotBarEnabled())
        val liked by attachment.session.likedDanmakuIds.collectAsState()
        val sending by assembly.playback.isSendingDanmaku.collectAsState()
        if(enabled && owned()) {
            val reservation=remember{Any()}
            var visible by remember{mutableStateOf(false)}
            var height by remember{mutableIntStateOf(0)}
            val clock=remember{DesktopHotDanmakuNativeClock(
                {(player.state.value.positionSeconds*1000.0).toLong().coerceAtLeast(0L)},::owned,player.state.value)}
            val bindings=remember{DesktopHotDanmakuBindings(preferences.getHotDanmakuExpandedMode(),lease,::owned)}
            DisposableEffect(overlay,lease,reservation) {
                overlay.reserveHotBar(lease.sourceVersion,reservation,0f)
                onDispose {overlay.releaseHotBarReservation(reservation)}
            }
            SideEffect {if(owned())overlay.updateHotBarReservation(lease.sourceVersion,reservation,if(visible)height.toFloat() else 0f)}
            LaunchedEffect(clock,player) {player.state.collect(clock::accept)}
            CompositionLocalProvider(LocalDesktopHotDanmakuBindings provides bindings) {
                HotDanmakuBar(
                    getDanmakuList={if(owned())overlay.hotItemsFor(lease.request.cid,lease.sourceVersion) else emptyList()},
                    player=clock,likedDanmakuIds=liked,
                    onLikeDanmaku={id,like->if(owned())attachment.session.likeDanmaku(id,like)},
                    onSendSame={text->
                        dispatchDesktopOriginalDanmakuSameSend(text,assembly.playback,lease.nativeSource,
                            sourceCurrent,::owned,latestAdmission)},
                    isSending=sending,modifier=Modifier.onSizeChanged {height=it.height},
                    onVisibilityChange={visible=it},
                )
            }
        }
    }
}
