from pathlib import Path
import importlib.util,json,re,os
H=Path(__file__).resolve().parent
def mod(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
p=mod('usecaseModels',H/'prepare-models.py')
sel=mod('usecaseSelect',p.REPO/'desktop/tools/extract-upstream-video-detail-full-units.py')
sel.parser=mod('usecaseTokens',p.REPO/'desktop/tools/sync-upstream.py')
decls=mod('usecaseDecls',p.REPO/'desktop/tools/extract-appearance-platform.py')
rel='feature/video/usecase/VideoPlaybackUseCase.kt';original=p.original(p.BASE+rel)
s=original[original.index('class VideoPlaybackUseCase('):];replacements=[]
def swap(a,b,label):
 global s
 assert s.count(a)==1,(label,s.count(a));s=s.replace(a,b,1);replacements.append(dict(label=label,before=a,after=b))
swap('''class VideoPlaybackUseCase(
    private var progressManager: PlaybackProgressManager = PlaybackProgressManager(),
    private val qualityManager: QualityManager = QualityManager()
) {''','''class VideoPlaybackUseCase internal constructor(
    private val environment: com.bilipai.desktop.ui.DesktopOriginalVideoPlaybackUseCaseEnvironment,
    private val qualityManager: QualityManager = QualityManager()
) : com.bilipai.desktop.ui.DesktopOriginalVideoLoadPort {
    private val progressManager get() = environment.progress
    private val VideoRepository get() = environment.repository
    private val ActionRepository get() = environment.actions''','Required same-root usecase environment replaces Android defaults')
swap('appContext = context.applicationContext\n        progressManager = PlaybackProgressManager.getInstance(context)','require(context === environment.context) { "Original video context must use the same global store" }\n        appContext = context','Actual context/progress authority, no second manager')
s=s.replace('com.android.purebilibili.core.player.PlayerVolumeController.applyPreferredVolume(player)','environment.applyPreferredVolume(player)')
s=s.replace('com.android.purebilibili.core.util.MediaUtils.','environment.capabilities.')
s=s.replace('com.android.purebilibili.data.repository.CommentRepository.getEmoteMap()','environment.emoteMap()')
s=s.replace('com.android.purebilibili.data.repository.VideoRepository.','VideoRepository.')
s=s.replace('com.android.purebilibili.core.store.TokenManager.isVipCache = isVip','environment.updatePrimaryVip(isVip)')
s=s.replace('PlaybackMediaCache.logSeek(','environment.logSeek(')
s=s.replace('android.content.Context','Context')
s=s.replace('    suspend fun loadVideo(\n','    override suspend fun loadVideo(\n',1)
a=s.index('override suspend fun loadVideo(');b=s.index('): VideoLoadResult',a);h=s[a:b]
for before,after in [('aid: Long = 0','aid: Long'),('cid: Long = 0L','cid: Long'),('defaultQuality: Int = 64','defaultQuality: Int'),('audioQualityPreference: Int = -1','audioQualityPreference: Int'),('videoCodecPreference: String = "hev1"','videoCodecPreference: String'),('videoSecondCodecPreference: String = "avc1"','videoSecondCodecPreference: String'),('audioLang: String? = null','audioLang: String?'),('playWhenReady: Boolean = true','playWhenReady: Boolean'),('isAv1SupportedOverride: Boolean? = null','isAv1SupportedOverride: Boolean?'),('isHdrSupportedOverride: Boolean? = null','isHdrSupportedOverride: Boolean?'),('isDolbyVisionSupportedOverride: Boolean? = null','isDolbyVisionSupportedOverride: Boolean?'),('onProgress: (String) -> Unit = {}','onProgress: (String) -> Unit')]:h=h.replace(before,after)
s=s[:a]+h+s[b:]
swap('''    ): VideoLoadResult {
        try {''','''    ): VideoLoadResult {
        environment.assertOwned()
        try {''','Task-owned entry admission before original cooldown/network work')
swap('''            return detailResult.fold(''','''            environment.assertOwned()
            return detailResult.fold(''','Retired task cannot apply original load result/cooldown success')
s=s.replace('@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','')
s=s.replace('player.setMediaSource(finalSource)','environment.media.accept(finalSource)')
s=s.replace('val mediaItem = MediaItem.fromUri(url)\n        player.setMediaItem(mediaItem)','val mediaItem = environment.media.prepareProgressive(url)\n        environment.media.accept(mediaItem)')
bodies={
 'createLegacyDashMediaSource':'''private fun createLegacyDashMediaSource(videoUrl:String,audioUrl:String?,cdnCacheKeysByUrl:Map<String,String>):com.bilipai.desktop.player.PlaybackSource =
        environment.media.prepareLegacyDash(videoUrl,audioUrl,cdnCacheKeysByUrl)''',
 'createAdaptiveDashMediaSource':'''private fun createAdaptiveDashMediaSource(adaptiveDashSource:AdaptiveDashPlaybackSource?,cdnCacheKeysByUrl:Map<String,String>):com.bilipai.desktop.player.PlaybackSource? =
        adaptiveDashSource?.let { environment.media.prepareAdaptiveDash(it,cdnCacheKeysByUrl) }''',
 'buildCachedPlaybackDataSourceFactory':'',
 'resolveDashSegmentRequestsEnabled':'''private fun resolveDashSegmentRequestsEnabled():Boolean = environment.dashSegmentRequestsEnabled()''',
 'writeAdaptiveDashManifest':''}
for name,body in bodies.items():
 a,b=sel.function_range(s,name);before=s[a:b];s=s[:a]+body+s[b:];replacements.append(dict(label='Android media boundary '+name,before=before,after=body))
helpers=[]
for name in ['resolveVideoLoadDurationMs','shouldPreparePlayerOnLoad','PlaybackBootstrapMode','resolvePlaybackBootstrapMode','shouldFetchRelatedVideosAfterVideoDetail','resolveRelatedVideosRequestBvid','applyPlaybackIntentAfterSourceChange','shouldUseAdaptiveDashPlayback']:helpers.append(decls.declarations(sel.parser,original,[name]))
a=original.index('private fun SegmentBase?.hasCompleteDashByteRanges');b=original.index('\ninternal fun resolveLocalDashManifestFileName',a);helpers.append(original[a:b].rstrip())
header='''package com.android.purebilibili.feature.video.usecase
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer
import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player
import com.android.purebilibili.core.cooldown.*
import android.util.Log as Logger
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.dash.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.policy.*
import com.android.purebilibili.feature.video.controller.QualityManager
import kotlinx.coroutines.*
'''
p.put(H/'prepared/usecase/com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt',header+'\n\n'.join(helpers)+'\n\n'+s)
p.put(H/'original-stable/VideoPlaybackUseCase.kt',original)
p.put(H/'usecase-adaptations.json',json.dumps(replacements,ensure_ascii=False,indent=2)+'\n')
p.put(H/'prepared/usecase/com/android/purebilibili/core/cooldown/PlaybackCooldownManager.kt',p.original(p.BASE+'core/cooldown/PlaybackCooldownManager.kt').replace('import com.android.purebilibili.core.util.Logger','import android.util.Log as Logger'))
print('whole original UseCase candidate prepared, platform adaptations',len(replacements))
