from pathlib import Path
import hashlib,json,os,textwrap
P=Path(__file__).resolve().parent
M=P.parents[2]; C=M.parent/'BiliPai-v023'
def wide(p):
 s=str(p)
 return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
H=[]
def change(rel,edits):
 t=read(C/rel);base=t
 for name,before,after in edits:
  assert t.count(before)==1,(rel,name,t.count(before)); t=t.replace(before,after)
  H.append(dict(path=rel,name=name,before=before,after=after,beforeSha256LF=sha(before),afterSha256LF=sha(after)))
 write(P/'prepared/existing'/rel,t)
 return base,t

vmrel='desktop/build/generated/original-video-full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
vm=read(C/vmrel)
a=vm.index('                        val readyState = VideoPlaybackUiState.Success(',vm.index('override') if 'override' in vm else 0)
b=vm.index('                        _uiState.value = readyState',a)
ready=textwrap.dedent(vm[a:b]).replace('val readyState =','val readyState =')
# Same complete original Success constructor, with the already resolved/captured
# CDN fields. No second selection or raw detail/playURL HTTP request occurs.
ready=ready.replace('cdnSelection.playUrl','result.playUrl').replace('cdnSelection.audioUrl','result.audioUrl').replace('cdnSelection.adaptiveDashSource','result.adaptiveDashSource')
ready=ready.replace('cdnSelection.allVideoUrls','allVideoUrls').replace('cdnSelection.allAudioUrls','allAudioUrls').replace('cdnSelection.candidateSources','candidateSources').replace('cdnSelection.lineDiagnostics','lineDiagnostics')
members='''
    /** Windows Pager starts the SAME original session before capturing its raw
     * Binding. Cancellation never creates another load job/native producer. */
    internal fun beginDesktopPortraitLoad(request: PlaybackRequest): Long {
        environment.assertCurrent()
        if (currentBvid.isNotBlank() && (currentBvid != request.bvid ||
                request.cid > 0L && currentCid != request.cid)) {
            flushPlaybackHeartbeatSnapshot(reason = "switch_video")
            recordCreatorWatchProgressSnapshot()
            saveCurrentPosition()
        }
        cancelDesktopPendingPlayback()
        heartbeatJob?.cancel()
        pluginCheckJob?.cancel()
        hasUserStartedPlayback = false
        clearInteractiveChoiceRuntime()
        var token: Long? = null
        check(environment.commit {
            token = playbackSessionStore.beginLoadRequest(request).requestToken
            _uiState.value = VideoPlaybackUiState.Loading.Initial
        }) { "Portrait session admission retired" }
        return checkNotNull(token)
    }

    /** Consume actual Pager metadata AFTER that exact source was accepted. The
     * original Session/UI/subject/Mini/post-load/playlist sequence is preserved;
     * native preparation and raw detail/playURL fetching are deliberately absent.
     * [admit] is the captured Binding Store -> entry gate, never native-held. */
    internal fun adoptDesktopPortraitLoad(
        requestToken: Long,
        result: com.android.purebilibili.feature.video.usecase.VideoLoadResult.Success,
        isAcceptedCurrent: () -> Boolean,
        admit: ((() -> Unit) -> Boolean),
    ): Boolean {
        environment.assertCurrent()
        val allVideoUrls = buildPlaybackVideoUrlCandidates(result.playUrl, result.quality, result.cachedDashVideos)
        val allAudioUrls = buildPlaybackAudioUrlCandidates(result.audioUrl, result.cachedDashAudios)
        val candidateSources = com.android.purebilibili.feature.plugin.buildPlaybackCdnCandidates(allVideoUrls, allAudioUrls).map { it.source }
        val lineDiagnostics = candidateSources.map { candidate ->
            com.android.purebilibili.feature.plugin.toCdnLineDiagnostic(candidate)
        }
'''+textwrap.indent(ready,'        ')+'''
        var applied = false
        if (!admit {
                if (isAcceptedCurrent() && shouldApplyVideoLoadResult(currentLoadRequestToken,
                        requestToken, result.info.bvid, currentBvid)) {
                    currentCid = result.info.cid
                    _uiState.value = readyState
                    publishSubjectSnapshot(readyState)
                    applied = true
                }
            } || !applied) return false
        // Original non-blocking operations stay outside the short admission.
        // Their original requestToken/subject guards reject a subsequent swipe.
        if (!isAcceptedCurrent()) return false
        environment.mini.syncCurrentVideoInfo(readyState)
        scheduleDeferredPostLoadWork(result.info.bvid, result.info.cid, result.info.aid,
            result.info.owner.mid, result.isLoggedIn, requestToken)
        val videoNoteEnabled = appContext?.let {
            com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings.getVideoNoteEnabledSync(it)
        } ?: true
        if (shouldLoadVideoNote(videoNoteEnabled, result.info.aid))
            loadVideoNote(loadedBvid = result.info.bvid, loadedAid = result.info.aid)
        updatePlaylist(result.info, result.related)
        environment.analytics.logVideoPlay(result.info.bvid, result.info.title, result.info.owner.name)
        return true
    }

'''
# Diagnostic builder is an existing private helper in the VM; use its canonical
# original expression rather than introducing another DTO/projection authority.
members=members.replace('val lineDiagnostics = candidateSources.map { candidate ->\n            com.android.purebilibili.feature.plugin.toCdnLineDiagnostic(candidate)\n        }','val lineDiagnostics = buildCdnLineDiagnostics(urls = allVideoUrls, healthByHost = emptyMap(), sources = candidateSources)')
write(P/'fragments/portrait-vm-members.kt.txt',members)
change(vmrel,[('same-original-session-and-ready-publication','    override fun close() {\n',members+'    override fun close() {\n')])

platform='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitPlatform.kt'
change(platform,[('required-page-capture','    suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding\n','    suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding\n    suspend fun capturePageRequest(bvid: String, aid: Long, cid: Long): DesktopOriginalVideoRepositoryBinding\n'),('actual-selection-input','        playData: PlayUrlData, videoUrl: String, audioUrl: String?, mediaId: String): PlaybackSource','        playData: PlayUrlData, videoUrl: String, audioUrl: String?, mediaId: String,\n        selection: com.android.purebilibili.feature.video.ui.pager.PortraitPlaybackStreamUrls,\n        recommendations: List<RelatedVideo>): PlaybackSource')])

binding='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitPlatformBinding.kt'
change(binding,[
 ('actual-session-token','    private class Capture(val job: Job, val nativeBaseline: Long) {','    private class Capture(val job: Job, val nativeBaseline: Long, val requestToken: Long) {'),
 ('actual-raw-payload','        val preparation: DesktopOriginalMediaCachePreparation)','        val preparation: DesktopOriginalMediaCachePreparation,\n        val payload: com.android.purebilibili.feature.video.usecase.VideoLoadResult.Success)'),
 ('no-new-owner-capture','    override suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding {','    override suspend fun capturePageRequest(bvid: String, aid: Long, cid: Long): DesktopOriginalVideoRepositoryBinding {\n        currentCoroutineContext().ensureActive(); assertOwned()\n        val expectedToken = assembly.playback.beginDesktopPortraitLoad(PlaybackRequest.create(bvid, aid, cid))\n        val captured = capturePlaybackRequest()\n        if (assembly.captureLoadState().currentLoadRequestToken != expectedToken ||\n                synchronized(captures) { captures[captured]?.requestToken } != expectedToken)\n            throw CancellationException("Portrait page replaced during request capture")\n        return captured\n    }\n\n    override suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding {'),
 ('retain-original-load-token','        val captured = Capture(job, checkNotNull(baseline))','        val captured = Capture(job, checkNotNull(baseline), assembly.captureLoadState().currentLoadRequestToken)'),
 ('selected-raw-input','        playData: PlayUrlData, videoUrl: String, audioUrl: String?, mediaId: String): PlaybackSource {','        playData: PlayUrlData, videoUrl: String, audioUrl: String?, mediaId: String,\n        selection: PortraitPlaybackStreamUrls, recommendations: List<RelatedVideo>): PlaybackSource {'),
 ('actual-payload-builder','        val prepared = Prepared(source, PlaybackRequest.create(info.bvid, info.aid, info.cid), preparation)','        val payload = desktopOriginalPortraitResolvedPayload(info, playData, source, selection, recommendations,\n            request.protocol.isPlaybackLoggedIn(), request.protocol.isPlaybackVip())\n        val prepared = Prepared(source, PlaybackRequest.create(info.bvid, info.aid, info.cid), preparation, payload)'),
 ('fixed-session-before-native-publication','        if (!stillCurrentLoad()) {','        val sameSession = { assembly.captureLoadState().let {\n            it.currentLoadRequestToken == capture.requestToken && it.currentBvid == prepared.request.bvid\n        } }\n        if (!stillCurrentLoad() || !sameSession()) {'),
 ('fixed-session-queued-load','capture.nativeBaseline, capture.job) { !capture.job.isCancelled && owns() && stillCurrentLoad() }','capture.nativeBaseline, capture.job) { !capture.job.isCancelled && owns() && stillCurrentLoad() && sameSession() }'),
 ('same-native-and-original-state-publication','            cached?.accepted(accepted)\n            return true','            cached?.accepted(accepted)\n            return assembly.playback.adoptDesktopPortraitLoad(capture.requestToken, prepared.payload,\n                { !capture.job.isCancelled && owns() && stillCurrentLoad() && assembly.native.isCurrent(accepted) },\n                request::admitCurrentMutation)'),
])

payload='''package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.pager.*
import com.android.purebilibili.feature.video.usecase.*

/** Metadata from the actual resolved Pager response/selection. The selected URLs
 * are the SAME Source published to MPV; no success, codec, credential or CID is
 * inferred from a seed. First-load interaction defaults match original
 * shouldFetchInteractionStatusOnVideoLoad=false and deferred status refresh.
 */
internal fun desktopOriginalPortraitResolvedPayload(info: ViewInfo, data: PlayUrlData,
    source: com.bilipai.desktop.data.PlaybackSource, selected: PortraitPlaybackStreamUrls,
    recommendations: List<RelatedVideo>, loggedIn: Boolean, vip: Boolean): VideoLoadResult.Success {
    require(info.bvid.isNotBlank() && info.aid > 0 && info.cid > 0)
    require(source.videoUrl.isNotBlank() && source.authorizationReceipt != null)
    val audio = selected.audioSelection
    val videos = data.dash?.video.orEmpty()
    val video = videos.firstOrNull { it.getValidUrl() == selected.videoUrl || selected.videoUrl in it.backupUrl.orEmpty() }
    val qualities = resolvePortraitAvailableQualityIds(data.acceptQuality, videos.map { it.id }.distinct())
    return VideoLoadResult.Success(
        info=info, playUrl=source.videoUrl, audioUrl=source.audioUrl, related=recommendations,
        quality=video?.id ?: data.quality, resolvedTargetQuality=video?.id ?: data.quality,
        qualityIds=qualities, qualityLabels=resolvePortraitQualityMenuLabels(qualities),
        switchableQualityIds=videos.map { it.id }.distinct(), cachedDashVideos=videos,
        cachedDashAudios=data.dash?.let { com.android.purebilibili.feature.video.playback.audio.collectAudioStreamCandidates(it).map { candidate -> candidate.track } }.orEmpty(),
        cachedDash=data.dash, requestedAudioQuality=audio?.requestedPreferenceId ?: -1,
        selectedAudioQuality=audio?.selectedPreferenceId ?: -1,
        availableAudioQualities=audio?.availableOptions.orEmpty(), audioFallbackReason=audio?.fallbackReason,
        emoteMap=emptyMap(), isLoggedIn=loggedIn, isVip=vip, isFollowing=false,
        isFavorited=false, isLiked=false, coinCount=0,
        duration=resolveVideoLoadDurationMs(data.timelength,info),
        videoCodecId=video?.codecid ?: data.videoCodecid,
        audioCodecId=audio?.selected?.track?.codecid ?: 0,
        aiAudio=data.aiAudio, curAudioLang=data.curLanguage,
        adaptiveDashSource=null // Pager published a legacy selected video/audio pair, not adaptive MPD.
    )
}
'''
write(P/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitResolvedPayload.kt',payload)

pager='desktop/build/generated/original-video-fullscreen-pager/com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'
change(pager,[
 ('save-before-native-clear','        platform.clearPlaybackForReplacement(exoPlayer)\n',''),
 ('fixed-page-request','                val playbackRequest = platform.capturePlaybackRequest()','                val playbackRequest = platform.capturePageRequest(bvid, aid, requestedCid)\n                platform.clearPlaybackForReplacement(exoPlayer)'),
 ('raw-selection-and-related','                            mediaId = mediaId,','                            mediaId = mediaId, selection = streamUrls,\n                            recommendations = recommendationItems.toList(),'),
 ('same-subject-interaction','        bvid = bvid\n    )\n\n    LaunchedEffect(favoriteSaveEvent','        bvid = bvid\n    ) && currentSuccess?.info?.bvid == bvid &&\n        currentSuccess.info.cid == currentPlayingCid && engagementState.subject?.let {\n            it.bvid == bvid && it.aid == activeAid && it.cid == currentPlayingCid\n        } == true\n\n    LaunchedEffect(favoriteSaveEvent'),
 ('same-subject-comment','            onCommentClick = { showCommentSheet = true },','            onCommentClick = { if (canHandlePortraitInteraction) showCommentSheet = true },'),
])

# Producer supplements own generated bodies; the generated previews are compile
# references, never installer payloads.
def supplement(rel,name,body):
 prod=read(C/rel)
 if 'full-owner' in rel:anchor='  body=captured_audio_download_delta(recipe[\'output\'],body)\n'; call="  body=story_portrait_adoption_delta(recipe['output'],body)\n";target='com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
 else:
  anchor="  generated=standalone or spec['mode']!='direct';rows.append(dict(path=spec['output'],origin=spec['origin'],mode=spec['mode'],sha256LF=digest(text),generated=generated))\n"
  call="  text=story_portrait_adoption_delta(spec['output'],text)\n";target='com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'
 edits=[h for h in H if h['path'].endswith(target)]
 fn="\ndef story_portrait_adoption_delta(path,body):\n if path=="+repr(target)+":\n"
 for h in edits:fn+='  before='+repr(h['before'])+'\n  after='+repr(h['after'])+'\n  assert body.count(before)==1\n  body=body.replace(before,after)\n'
 fn+=' return body\n\n'
 top='def generate('
 # Selected producers differ in their generate function name/parameter spelling.
 pos=prod.index(top)
 prefix=prod[:pos]; rest=prod[pos:]
 assert rest.count(anchor)==1,(rel,anchor)
 after=prefix+fn+rest.replace(anchor,anchor+call if 'full-owner' in rel else call+anchor)
 write(P/'prepared/existing'/rel,after)
 H.append(dict(path=rel,name=name,before=prod[pos:pos+len(top)],after=fn+prod[pos:pos+len(top)],beforeSha256LF=sha(prod[pos:pos+len(top)]),afterSha256LF=sha(fn+prod[pos:pos+len(top)])))
 H.append(dict(path=rel,name=name+'-apply',before=anchor,after=anchor+call if 'full-owner' in rel else call+anchor,beforeSha256LF=sha(anchor),afterSha256LF=sha(anchor+call if 'full-owner' in rel else call+anchor)))
supplement('desktop/tools/extract-upstream-video-full-owner.py','sole-vm-adoption-supplement',members)
supplement('desktop/tools/extract-upstream-video-fullscreen-pager.py','sole-pager-adoption-supplement','')
write(P/'exact-hunks.json',json.dumps(H,ensure_ascii=False,indent=2)+'\n')
print('Prepared real resolved-page adoption:',len(H),'hunks; no Candidate writes.')
