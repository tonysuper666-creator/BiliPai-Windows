package com.bilipai.desktop.ui

import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import okhttp3.Call
import java.awt.Font
import java.lang.reflect.Proxy
import kotlin.test.*

/** Actual existing NativeOwner and OwnerDanmakuBinding, before any API capture.
 * Reconstruct a same-CID/same-version accepted recovery at that exact boundary. */
class DesktopOriginalDanmakuSameSendProtocolAdmissionTest {
    @Test fun transientExpectedContextCannotRetagAtRealProtocolCaptureAndDoesNotLeak()=runBlocking<Unit> {
        withTimeout(5_000) {
            val player=MpvPlayer();val caller=Job()
            val publication=object:DesktopPlaybackPublication {
                override val requiresAccountReceipt=true
                override fun isCurrent(source:PlaybackSource)=source.authorizationReceipt?.accountEpoch==7L
                override fun <T> admit(source:PlaybackSource,stillOwned:()->Boolean,block:()->T):T {
                    if(!isCurrent(source)||!stillOwned())throw CancellationException("Synthetic receipt retired")
                    return block()
                }
                override fun calls(delegate:Call.Factory,source:PlaybackSource,stillOwned:()->Boolean,callerJob:Job?)=
                    error("No HTTP")
            }
            val owner=DesktopOriginalVideoNativeOwner(player,publication,{7L},{false},{true},
                {action->action();true},{},{_,_->error("No byte transport")})
            val noTransport=Proxy.newProxyInstance(DesktopDanmakuSource::class.java.classLoader,
                arrayOf(DesktopDanmakuSource::class.java)) {_,method,_->error("No transport ${method.name}")} as DesktopDanmakuSource
            val overlay=DanmakuOverlay(player,object:DesktopOriginalDanmakuRenderPlatform {
                override fun resolveTypeface(fontWeight:Int)=Font("Dialog",Font.PLAIN,15)
                override fun systemChromeInsetPx()=0
                override fun maximumDisplayShortSidePx()=1080f
            },source=noTransport)
            try {
                val source=PlaybackSource("file:///C:/private-pool-protocol.avi",
                    authorizationReceipt=DesktopPlaybackAuthorizationReceipt(7,1))
                val accepted=owner.publish(PlaybackRequest.create("BVfixture",17,70),source,
                    player.currentSourceVersion,caller) {true}
                val expected=assertNotNull(DesktopOriginalDanmakuExpectedSubmission.capture(accepted.nativeSource) {true})
                var capturedBindings=0;var replace=true
                val binding=DesktopOriginalVideoOwnerDanmakuBinding(
                    {capturedBindings++;error("No raw/API binding requested")},
                    {
                        if(replace) {
                            replace=false
                            val media=owner.acceptedMedia({lease->object:DesktopOriginalVideoMediaPort {
                                override fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit)=action()
                                override fun prepareLegacyDash(videoUrl:String,audioUrl:String?,cdnCacheKeysByUrl:Map<String,String>)=lease.nativeSource.source
                                override fun prepareAdaptiveDash(source:AdaptiveDashPlaybackSource,cdnCacheKeysByUrl:Map<String,String>)=error("No DASH")
                                override fun prepareProgressive(url:String)=lease.nativeSource.source.copy(videoUrl=url)
                                override fun accept(source:PlaybackSource)=error("Owner performs acceptance")
                            }})
                            media.accept(accepted.nativeSource.source.copy(startPositionSeconds=2.0,startPaused=true))
                        }
                        owner
                    },overlay,{VideoSubjectSnapshot("BVfixture",70,17,42,"fixture","",100000,1)},{true})
                assertFailsWith<CancellationException> {
                    withContext(expected) {binding.sendDanmaku(17,70,"同款",73125,16777215,25,1,false,false)}
                }
                assertEquals(0,capturedBindings)
                val next=assertNotNull(owner.current())
                assertEquals(accepted.sourceVersion,next.sourceVersion);assertEquals(accepted.request.cid,next.request.cid)
                assertNotSame(accepted,next);assertNotEquals(accepted.nativeSource.source,next.nativeSource.source)
                assertNull(DesktopOriginalDanmakuExpectedSubmission.current())
                // Normal callers keep their original capture path after context restoration.
                assertFailsWith<IllegalStateException> {binding.sendDanmaku(17,70,"normal",0,16777215,25,1,false,false)}
                assertEquals(1,capturedBindings)
            } finally {overlay.close();owner.close();caller.cancel();player.close()}
        }
    }
}
