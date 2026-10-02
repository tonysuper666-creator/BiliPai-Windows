package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.pager.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.viewmodel.shouldApplyVideoLoadResult
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.PlaybackSource
import java.nio.file.Path

/** Original DTO/Session shape test. No VM, UI Success, API proxy, native source,
 * HTTP, primary user account or Window is constructed or seeded by this fixture. */
fun main() {
    var checks = 0
    fun verify(value: Boolean) { check(value); checks++ }
    val session = DesktopSessionStore(Path.of("unused-fixture-session"), persistent=false)
    val authorization = session.capturePlaybackAuthorization(session.generation) { true }
    val video=DashVideo(id=64,baseUrl="https://fixture.invalid/video",backupUrl=listOf("https://mirror.invalid/video"),codecid=7)
    val audio=DashAudio(id=30280,baseUrl="https://fixture.invalid/audio",codecid=0)
    val decision=AudioSelectionDecision(AUDIO_QUALITY_AUTO,AUDIO_QUALITY_AUTO,
        AudioStreamCandidate(AUDIO_QUALITY_AUTO,AudioStreamKind.STANDARD,"fixture",audio),emptyList(),null)
    val info=ViewInfo(bvid="BV1Fixture",aid=101,cid=202,title="original DTO fixture",owner=Owner(mid=303),
        pages=listOf(Page(cid=202,duration=123)), dimension=Dimension(720,1280))
    val dash=Dash(video=listOf(video),audio=listOf(audio))
    val data=PlayUrlData(quality=80,timelength=123000,acceptQuality=listOf(80,64,64),dash=dash,
        curLanguage="zh",aiAudio=AiAudioInfo(title="actual fixture field"))
    val source=PlaybackSource(videoUrl="https://rewritten.invalid/video",audioUrl=audio.baseUrl,
        title=info.title,referer="https://www.bilibili.com",authorizationReceipt=authorization.receipt)
    val selected=PortraitPlaybackStreamUrls(video.backupUrl!!.single(),audio.baseUrl,decision)
    val related=listOf(RelatedVideo(bvid="BVRelated",aid=404))
    val resolved=desktopOriginalPortraitResolvedPayload(info,data,source,selected,related,false,false)
    verify(resolved.info === info && resolved.info.cid == 202L) // Resolved CID, never seed CID 0.
    verify(resolved.playUrl == source.videoUrl && resolved.audioUrl == source.audioUrl)
    verify(resolved.quality==64 && resolved.resolvedTargetQuality==64) // Actual selected track.
    verify(resolved.cachedDash === dash && resolved.cachedDashVideos.single() === video)
    verify(resolved.cachedDashAudios.single() === audio)
    verify(resolved.related === related && resolved.videoCodecId==7)
    verify(resolved.duration==123000L && resolved.curAudioLang=="zh" && resolved.aiAudio === data.aiAudio)
    verify(resolved.qualityIds==listOf(80,64) && resolved.qualityLabels.size==2)
    verify(resolved.adaptiveDashSource==null && !resolved.isLiked && !resolved.isFavorited && resolved.coinCount==0)
    verify(runCatching { desktopOriginalPortraitResolvedPayload(info.copy(cid=0),data,source,selected,related,false,false) }.isFailure)
    verify(runCatching { desktopOriginalPortraitResolvedPayload(info,data,source.copy(authorizationReceipt=null),selected,related,false,false) }.isFailure)

    val original=PlaybackSessionStore()
    val first=original.beginLoadRequest(PlaybackRequest.create(info.bvid,info.aid,0))
    verify(first.requestToken>0 && original.state.value.currentBvid==info.bvid)
    verify(shouldApplyVideoLoadResult(original.state.value.currentLoadRequestToken,first.requestToken,info.bvid,original.state.value.currentBvid))
    original.updateCurrentMedia(cid=resolved.info.cid)
    verify(original.state.value.currentCid==202L)
    val next=original.beginLoadRequest(PlaybackRequest.create("BVNext",505,606))
    verify(next.requestToken>first.requestToken && !shouldApplyVideoLoadResult(original.state.value.currentLoadRequestToken,first.requestToken,info.bvid,original.state.value.currentBvid))
    verify(shouldApplyVideoLoadResult(original.state.value.currentLoadRequestToken,next.requestToken,"BVNext",original.state.value.currentBvid))
    val sameVideoNext=original.beginLoadRequest(PlaybackRequest.create("BVNext",505,707))
    verify(!shouldApplyVideoLoadResult(original.state.value.currentLoadRequestToken,next.requestToken,"BVNext","BVNext") && sameVideoNext.requestToken>next.requestToken)
    session.logout()
    verify(!session.isPlaybackAuthorizationCurrent(authorization.receipt))
    println("PASS $checks original DTO/Session/receipt assertions; no Root UI Success/native ACK/HTTP claim")
}
