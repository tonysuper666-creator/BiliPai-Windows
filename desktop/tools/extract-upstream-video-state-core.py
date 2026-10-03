"""Original stable video state/load core and raw playback/Supplement selected source.
No independent network/store/native player. DIRECT sources are Sync-owned in production.
Full original PlaybackVM/5464 Holder remain next; this core is not a mounted page.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,types
from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
REPO=None;OUTPUT=None;STANDALONE=False;OUTPUTS=[];SOURCES={};AUDITS={};H=Path('/source-output')
SHARED_PLAYBACK_SOURCE_RECIPES=[{'source': 'core-data/src/main/java/com/android/purebilibili/data/repository/SharedContentRepository.kt', 'sourceSha256LF': '6e141358cb554152ed172d1e63c54cb9fa760c1db10064cdb2d07696a9dd31c7', 'target': 'com/android/purebilibili/data/repository/DesktopOriginalSharedContentRequests.kt', 'mode': 'policy-extract', 'outputSha256LF': '2c5e31dd1bc108ba7ca4452758f0b432ba01b5ff6ef54b55af190d6b176c3411', 'edits': [{'start': 0, 'end': 2, 'beforeSha256LF': '9310be7f254ff7c1c0a7c0fb4016daf33b7185af0cc882075b73e676c8ebf2af', 'after': None}, {'start': 2, 'end': 4, 'beforeSha256LF': '7ec956eab4c83e0e0e6149ef47823fd7dde4e774aed23da4c5d78538e234c121', 'after': 'import com.android.purebilibili.core.network.BilibiliApi\n'}, {'start': 4, 'end': 23, 'beforeSha256LF': 'ebb337ad5a52eedd38daddd8f0be2e7159dd6a4c169272a92e25df9d77c1be45', 'after': None}, {'start': 23, 'end': 24, 'beforeSha256LF': '19cedba7627d0b67cb5c35f862ed82bede6b3fe77431218c76e62e8219ef25e0', 'after': 'internal class DesktopOriginalSharedContentRequests(private val api: () -> BilibiliApi) {\n'}, {'start': 24, 'end': 25, 'beforeSha256LF': 'bb9c2225b4a80b2769fdc2d268aba6bdace5eab6f56402e42d587857bac4ea47', 'after': None}, {'start': 25, 'end': 41, 'beforeSha256LF': 'adb28dd43d28167eee2e66aeae3028d11ea2c9a94f08a27a1ce0d0ec2538c035', 'after': '        api().getRecommendParams(signedParams)\n'}, {'start': 41, 'end': 44, 'beforeSha256LF': 'f5d7d843f2593dbca3f89f40c49f9dd7637db4facdc67cfbf8e568d2c9d2c6db', 'after': None}, {'start': 44, 'end': 46, 'beforeSha256LF': '1764c445d95b33de51334afe1790791fe7b80afaa470f50c87667c3881aac4db', 'after': '        val response = if (lookup.bvid.isNotEmpty()) api().getVideoInfo(lookup.bvid)\n        else api().getVideoInfoByAid(lookup.aid)\n'}, {'start': 46, 'end': 63, 'beforeSha256LF': 'ebb1c8ef464edd0418f227d508980485d307fe817e3cef4ce968784b0f950017', 'after': None}], 'fullOriginalInverse': True}, {'source': 'core-data/src/main/java/com/android/purebilibili/data/repository/PlaybackStreamDataSource.kt', 'sourceSha256LF': '26389490037506c2614c7f88df54ba68ff409ed098c603eae6887c3d2d701032', 'target': 'com/android/purebilibili/data/repository/DesktopOriginalPlaybackStreamDataSource.kt', 'mode': 'policy-extract', 'outputSha256LF': 'd9f116d53745848a4a312cf2f778b95eb6f89597ced83ba57f0939872dd304e0', 'edits': [{'start': 0, 'end': 3, 'beforeSha256LF': 'ed18b86fb5e3ee9583fa9e5f1a0184276288836976844405efc8e9d5584d26a8', 'after': None}, {'start': 3, 'end': 5, 'beforeSha256LF': '61d34647ac8bb18033f2e52a158537511446653b853829fdbfe1894f79345a80', 'after': 'import com.android.purebilibili.core.network.BilibiliApi\nimport com.android.purebilibili.core.network.DesktopTokenPlatform as TokenManager\n'}, {'start': 5, 'end': 27, 'beforeSha256LF': '863a0cbe311da83e791a03fbe9c05237fab402b88dde7a043652f89b434f7f9e', 'after': None}, {'start': 27, 'end': 28, 'beforeSha256LF': '2cc676804d06ad06eca980471018c5343dbeaa70111c34ee1146dcc4bca20153', 'after': 'internal class DesktopOriginalPlaybackStreamDataSource(private val api: () -> BilibiliApi) {\n'}, {'start': 28, 'end': 31, 'beforeSha256LF': 'd56a9a20acce16662ff4ea3b94e6dadf7f590931d2f5cf84ea615e8c56c57a90', 'after': None}, {'start': 31, 'end': 32, 'beforeSha256LF': 'caeb10e512bb9c6f5c680b7f41683d89cb6e0ce3c363340f578301a8cab4263f', 'after': '    ): PlayUrlResponse = api().getPlayUrlApp(\n'}, {'start': 32, 'end': 35, 'beforeSha256LF': 'bac9f9970a67128a07b006cde9375cf47445a0f85dc11eaa32abd26a87e8957f', 'after': None}], 'fullOriginalInverse': True}]

def generate_shared_playback_sources(repo,output):
 rows=[]
 for r in SHARED_PLAYBACK_SOURCE_RECIPES:
  raw=wide(_desktop_canonical_source(repo,r['source'])).read_text(encoding='utf8').replace('\r\n','\n');assert digest(raw)==r['sourceSha256LF'];lines=raw.splitlines(keepends=True);cursor=0;body=[];inverse=[]
  for e in r['edits']:
   assert e['start']==cursor;cursor=e['end'];before=''.join(lines[e['start']:e['end']]);assert digest(before)==e['beforeSha256LF'];body.append(before if e['after']is None else e['after']);inverse.append(before)
  assert cursor==len(lines)and''.join(inverse)==raw;body=''.join(body);assert digest(body)==r['outputSha256LF'];emit(r['target'],body,r['source'],'selected-original-platform-adapt')
 return rows

def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def digest(t):return hashlib.sha256(t.encode() if isinstance(t,str) else t).hexdigest()
def original(path):
 path=_desktop_canonical_relative(REPO,path)
 raw=wide(_desktop_canonical_source(REPO, path)).read_bytes().replace(b'\r\n',b'\n');assert digest(raw)==SOURCE_PINS[path],path+' differs from fixed stable source'
 SOURCES[path]=dict(path=path,sha256LF=digest(raw),commit=COMMIT);return raw.decode('utf-8')
def emit(path,text,origin,mode):
 generated=STANDALONE or mode!='direct';OUTPUTS.append(dict(path=path,origin=origin,mode=mode,sha256LF=digest(text),generated=generated))
 if generated:
  target=wide(OUTPUT/path);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
def put(path,text):
 parts=path.parts
 if 'prepared' not in parts:
  if path.suffix=='.json':AUDITS[path.name]=json.loads(text)
  return
 i=parts.index('prepared');relative=Path(*parts[i+2:]).as_posix()
 if not relative.startswith('com/'):relative='com/android/purebilibili/'+relative
 directOrigin=DIRECT_ORIGINS.get(relative)
 origin=directOrigin or SELECTED_ORIGINS[relative]
 emit(relative,text,origin,'direct' if directOrigin else 'selected-original-platform-adapt')
def declaration(t,name,body=False):
 mask=lex.masked(t);m=re.search(r'(?m)^(?:(?:internal|private|public|sealed|data|enum)\s+)*(?:class|interface)\s+'+re.escape(name)+r'\b',mask);assert m,name
 end=lex.balanced(mask,mask.index('{',m.end()),'{','}') if body else lex.balanced(mask,mask.index('(',m.end()))
 return t[m.start():end]
def mod(n,path):
 sp=importlib.util.spec_from_file_location(n,path);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/core/cooldown/PlaybackCooldownManager.kt': 'f14bec14a699587e36fbaa7e94636f1274fcd0e69b3bbb3d0c8309aa0170e440', 'app/src/main/java/com/android/purebilibili/data/model/VideoLoadError.kt': 'd13abdfe7ad6ec158e4046f30ea1ad38d1c68202c08dba73df0936284695a3df', 'app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt': 'acf05cba9a89666533378eef35a21609484a02ccd874a460c259c3f3363d1e64', 'app/src/main/java/com/android/purebilibili/feature/video/controller/QualityManager.kt': '4e2895dfd69d83c398b0e1719267513598fd3f3c33fd157f169d0c6f7b66a496', 'app/src/main/java/com/android/purebilibili/feature/video/playback/coordinator/PlaybackCoordinator.kt': '1968820cd98d97cbe745363a799e0ae08e172b3ba7881a0d1696777b799a7454', 'core-player/src/main/java/com/android/purebilibili/core/player/dash/AdaptiveDashPlaybackSource.kt': '1e808b21c3f26062eaa9bdb377a82410381b80275f7e69eac487588e1fd1c84d', 'app/src/main/java/com/android/purebilibili/feature/video/playback/loader/PlaybackLoadResult.kt': 'a527931bc3236db1f4c103adfc1e193521423369e0bbbf9b5801764d254b2ed1', 'app/src/main/java/com/android/purebilibili/feature/video/playback/loader/PlaybackLoader.kt': 'ab9cce54896ecdb0a05c5632cc0e0db6730e0cf6a3589e3d3e665e160e7e6b49', 'app/src/main/java/com/android/purebilibili/feature/video/playback/loader/PlaybackRequest.kt': 'ff75b5af9a8cd4cec42f393a40a3e45bff20d52c1dc821f333076da377b6eb4e', 'app/src/main/java/com/android/purebilibili/feature/video/playback/session/PlaybackSessionStore.kt': '09d952f46d7eaa505aa3d23dd6435a24e87d44b45fcd47dad0cf29185c868655', 'app/src/main/java/com/android/purebilibili/feature/video/policy/ResumePlaybackPolicy.kt': '4f5d3bddd5281e611179aa6c93ae8c81e68a74cd64cd13e30ed6ae691a6b2fdd', 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPresentationState.kt': '450cd98e1b0e6e03c87a1f6e0c0e38e85cd3955e82e7aec2387d711c2e066250', 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailRenderContracts.kt': 'c539036601eda781beef18b84e1776187f9a0656559ab937e95cb1279ade0418', 'app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoPlaybackUseCase.kt': 'bb0f36c9d2bb6aa8dfecc2a6bfe7ad70a09d97acc10e2d4928348c0c257ca36c', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/PlaybackCompletionPolicy.kt': '083a8ed64ab0f63bc7d86c96c1f55b453ce0df075a7acad4a47f7dd7edc1447d', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt': '62c0eae9b9447cfd615a5972ab8f58be906ca2a0cee184ccd57cf8b997ebbb1f', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt': '94f0f773e693e430f0a4be8e0206b57f7f74dd00a84f5af571151b3278637426', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoSubjectSnapshot.kt': 'e266056182d40e924535401011071fc61fcfc59738e9263076717f49765c47fa', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoSupplementViewModel.kt': 'e5fb1f278eba66228597dfc631b275b835b11a577c23c0eebfc460821b839918'}
CORE_DIRECT=['feature/video/playback/loader/PlaybackRequest.kt', 'feature/video/playback/loader/PlaybackLoadResult.kt', 'feature/video/playback/session/PlaybackSessionStore.kt', 'feature/video/playback/coordinator/PlaybackCoordinator.kt', 'feature/video/policy/ResumePlaybackPolicy.kt', 'feature/video/controller/QualityManager.kt', 'feature/video/screen/VideoDetailPresentationState.kt', 'feature/video/screen/VideoDetailRenderContracts.kt']
DIRECT_ORIGINS={'com/android/purebilibili/feature/video/playback/loader/PlaybackRequest.kt': 'app/src/main/java/com/android/purebilibili/feature/video/playback/loader/PlaybackRequest.kt', 'com/android/purebilibili/feature/video/playback/loader/PlaybackLoadResult.kt': 'app/src/main/java/com/android/purebilibili/feature/video/playback/loader/PlaybackLoadResult.kt', 'com/android/purebilibili/feature/video/playback/session/PlaybackSessionStore.kt': 'app/src/main/java/com/android/purebilibili/feature/video/playback/session/PlaybackSessionStore.kt', 'com/android/purebilibili/feature/video/playback/coordinator/PlaybackCoordinator.kt': 'app/src/main/java/com/android/purebilibili/feature/video/playback/coordinator/PlaybackCoordinator.kt', 'com/android/purebilibili/feature/video/policy/ResumePlaybackPolicy.kt': 'app/src/main/java/com/android/purebilibili/feature/video/policy/ResumePlaybackPolicy.kt', 'com/android/purebilibili/feature/video/controller/QualityManager.kt': 'app/src/main/java/com/android/purebilibili/feature/video/controller/QualityManager.kt', 'com/android/purebilibili/feature/video/screen/VideoDetailPresentationState.kt': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPresentationState.kt', 'com/android/purebilibili/feature/video/screen/VideoDetailRenderContracts.kt': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailRenderContracts.kt', 'com/android/purebilibili/data/model/VideoLoadError.kt': 'app/src/main/java/com/android/purebilibili/data/model/VideoLoadError.kt', 'com/android/purebilibili/feature/video/playback/dash/AdaptiveDashPlaybackSource.kt': 'core-player/src/main/java/com/android/purebilibili/core/player/dash/AdaptiveDashPlaybackSource.kt'}

SELECTED_ORIGINS={'com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoPlaybackUiState.kt': ['app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'], 'com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSuccessExtensions.kt': ['app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoSubjectSnapshot.kt', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt', 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoSupplementViewModel.kt'], 'com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt': ['app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoPlaybackUseCase.kt'], 'com/android/purebilibili/core/cooldown/PlaybackCooldownManager.kt': ['app/src/main/java/com/android/purebilibili/core/cooldown/PlaybackCooldownManager.kt'], 'com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt': ['app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt'], 'com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSupplementViewModel.kt': ['app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoSupplementViewModel.kt']}

def build_models():
 code=r"""path=BASE+'feature/video/viewmodel/VideoPlaybackViewModel.kt';vm=original(path)
blocks=[declaration(vm,'VideoPlaybackUiState',True),declaration(vm,'SponsorSkipUiState'),declaration(vm,'SponsorContributionPhase',True),declaration(vm,'SponsorContributionUiState',True),declaration(vm,'QualitySwitchFailureDialogState')]
header='''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.core.player.policy.PlaybackQualityMode
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.feature.video.note.VideoNoteUiState
import com.android.purebilibili.feature.plugin.CdnLineDiagnostic
'''
put(H/'prepared/selected/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoPlaybackUiState.kt',header+'\n\n'.join(blocks)+'\n')
for path in [BASE+'data/model/VideoLoadError.kt',BASE+'feature/video/playback/dash/AdaptiveDashPlaybackSource.kt']:
 put(H/'prepared/direct'/path.removeprefix(BASE),original(path))
# Existing subject schema, Engagement VM/model/actions stay owned by their sole installed producer.
subjectPath=BASE+'feature/video/viewmodel/VideoSubjectSnapshot.kt';subject=original(subjectPath)
engagementPath=BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt';engagement=original(engagementPath)
supplementPath=BASE+'feature/video/viewmodel/VideoSupplementViewModel.kt';supplement=original(supplementPath)
extensions=subject[subject.index('internal fun VideoPlaybackUiState.Success.toSubjectSnapshot'):].rstrip()+'\n'+engagement[engagement.index('internal fun VideoPlaybackUiState.Success.toEngagementSeed'):].rstrip()+'\n'+supplement[supplement.index('internal fun VideoPlaybackUiState.Success.toSupplementSeed'):].rstrip()+'\n'
supplementModels=declaration(supplement,'VideoSupplementSeed')+'\n'+declaration(supplement,'VideoSupplementUiState')
put(H/'prepared/selected/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSuccessExtensions.kt','''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.data.model.response.VideoTag
import com.android.purebilibili.feature.video.note.VideoNoteUiState
'''+supplementModels+'\n'+extensions)
"""
 exec(code,globals().copy())

def build_usecase():
 code=r"""rel='feature/video/usecase/VideoPlaybackUseCase.kt';original=p.original(p.BASE+rel)
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
# Original Exo assigns a source before prepare. MPV enqueues Load at accept:
# capture the EXACT original play call arguments first, preserving source policy
# and every subsequent original property/seek/prepare expression and order.
swap('        val dashSegmentRequestsEnabled = resolveDashSegmentRequestsEnabled()\n',
     '        environment.media.withPlaybackIntent(seekTo, playWhenReady) {\n        val dashSegmentRequestsEnabled = resolveDashSegmentRequestsEnabled()\n',
     'Windows native initial Load original lexical intent begin DASH')
swap('        environment.media.accept(finalSource)\n        player.playWhenReady = playWhenReady',
     '        environment.media.accept(finalSource)\n        }\n        player.playWhenReady = playWhenReady',
     'Windows native original lexical intent end DASH')
swap('        val mediaItem = environment.media.prepareProgressive(url)\n        environment.media.accept(mediaItem)',
     '        environment.media.withPlaybackIntent(seekTo, playWhenReady) {\n        val mediaItem = environment.media.prepareProgressive(url)\n        environment.media.accept(mediaItem)\n        }',
     'Windows native initial Load original lexical intent progressive')
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
import com.android.purebilibili.core.player.dash.*
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.core.player.policy.PlaybackQualityMode
import com.android.purebilibili.core.player.PlaybackProgressManager
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
"""
 exec(code,globals().copy())

def build_raw_protocol():
 code=r"""orig=p.original(p.BASE+"data/repository/VideoRepository.kt")
methods=['getVideoInfoOnly','getInitialPlayUrlData','getVideoDetails','getPlaybackNavInfo','getPlayUrlData','getPlayUrlDataForPlaybackTransition','getExactPremiumPlayUrl','fetchPlayUrlRecursive','hasPlayableStreams','fetchDashWithFallback','fetchAsGuestFallback','fetchGuestPlaybackWithFallback','fetchPlayUrlWithWbiInternal','fetchPlayUrlWithAccessToken','getRelatedVideos','classifyPlayUrlError','getWbiKeys','getTvCastPlayData']
parts=['    suspend fun getVideoInfoOnly(\n        bvid: String,\n        aid: Long = 0L,\n        requestedCid: Long = 0L\n    ): Result<ViewInfo> = SharedContentRepository.detail(bvid, aid, requestedCid)' if n=='getVideoInfoOnly' else sel.func(orig,n,False) for n in methods]
a=orig.index('    private data class PlayUrlFetchResult(');b=p.lex.balanced(p.lex.masked(orig),orig.index('(',a));result=orig[a:b]
s='\n\n'.join([result]+parts);changes=[]
assert s.count('    suspend fun getVideoInfoOnly(\n        bvid: String,\n        aid: Long = 0L,\n        requestedCid: Long = 0L\n    ): Result<ViewInfo> = SharedContentRepository.detail(bvid, aid, requestedCid)')==1
s=s.replace('    suspend fun getVideoInfoOnly(\n        bvid: String,\n        aid: Long = 0L,\n        requestedCid: Long = 0L\n    ): Result<ViewInfo> = SharedContentRepository.detail(bvid, aid, requestedCid)','    suspend fun getVideoInfoOnly(\n        bvid: String,\n        aid: Long = 0L,\n        requestedCid: Long = 0L\n    ): Result<ViewInfo> = run {\n        environment.assertOwned()\n        val result = SharedContentRepository.detail(bvid, aid, requestedCid)\n        environment.assertOwned()\n        result\n    }',1)
s+=''
def change(a,b,label,count=None):
 global s
 n=s.count(a);assert n and (count is None or n==count),(label,n);s=s.replace(a,b);changes.append(dict(label=label,before=a,after=b,count=n))
# Root's same authorized APIs replace only original global access. No HTTP graph is created here.
change('NetworkModule.playbackApi()','environment.playbackApi','Captured SAME playback authorization API')
change('NetworkModule.guestApi','environment.guestApi','Captured visitor-only original guest API')
change('com.android.purebilibili.core.util.Logger','Logger','Existing safe desktop diagnostics logger')
# The latest complete PlaybackStreamDataSource owns signed app params.
change('applicationContext != null && playbackAccount() == null','environment.canRefreshPrimaryToken() && playbackAccount() == null','Captured primary account refresh admission')
change('com.android.purebilibili.core.network.TokenRefreshHelper.refresh(applicationContext!!)','environment.refreshPrimaryToken()','SAME session original refresh actor')
# These are reads of the original key/default, not a new settings model.
initial='val auto1080pEnabled = try {\n            val context = NetworkModule.appContext\n            context?.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)\n                ?.getBoolean("exp_auto_1080p", true) ?: true\n        } catch (e: Exception) {\n            true\n        }'
change(initial,'val auto1080pEnabled = environment.auto1080pEnabled()','Original exp_auto_1080p same-global read',1)
detail='val auto1080pEnabled = try {\n                val context = com.android.purebilibili.core.network.NetworkModule.appContext\n                context?.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)\n                    ?.getBoolean("exp_auto_1080p", true) ?: true // 默认开启\n            } catch (e: Exception) {\n                true // 出错时默认开启\n            }'
change(detail,'val auto1080pEnabled = environment.auto1080pEnabled()','Original exp_auto_1080p same-global read',1)
change('val auto1080pEnabled = NetworkModule.appContext?.let { context ->\n            runCatching { SettingsManager.getAuto1080p(context).first() }.getOrDefault(true)\n        } ?: true','val auto1080pEnabled = environment.auto1080pEnabled()','Original flow same-global read',1)
change('NetworkModule.appContext?.let {\n                SettingsManager.getBiliDirectedTrafficEnabledSync(it)\n            } ?: false','environment.directedTrafficEnabled()','Original directed traffic consent same-global',1)
change('NetworkModule.appContext?.let {\n                NetworkUtils.isMobileData(it)\n            } ?: false','environment.isMobileData()','Real Windows WWAN query',1)
change('PlayUrlCache.','environment.cache.','Sole existing Root playback cache port')
change('e.printStackTrace()','Logger.e("VideoRepo", "Original video detail failed", e)','Safe logging, no raw stdout exception',1)
# Task cancellation/retirement must not be downgraded to a normal original API failure.
change('catch (e: Exception)', 'catch (e: Exception)', 'marker') if False else None
s=s.replace('} catch (e: Exception) {','} catch (e: Exception) {\n            if (e is kotlinx.coroutines.CancellationException) throw e\n            environment.assertOwned()\n')
for name in ['getVideoInfoOnly','getInitialPlayUrlData','getVideoDetails','getPlaybackNavInfo','getPlayUrlData','getRelatedVideos']:
 a,b=sel.function_range(s,name);piece=s[a:b]
 piece=re.sub(r'^(\s*)(?:internal )?suspend fun',r'\1override suspend fun',piece,count=1)
 # Overrides own no default: canonical defaults are retained on the required interface.
 hs=piece.index('(');he=p.lex.balanced(p.lex.masked(piece),hs)
 head=piece[hs:he]
 head=re.sub(r' = (?:0L|0|null)', '',head)
 piece=piece[:hs]+head+piece[he:]
 s=s[:a]+piece+s[b:]
# A late raw response/cache read must not re-enter retired UI state.
s=s.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n        environment.assertOwned()\n')
s=s.replace('Result.success(info)','environment.assertOwned()\n            Result.success(info)')
s=s.replace('Result.success(Pair(info, playData))','environment.assertOwned()\n            Result.success(Pair(info, playData))')
s=s.replace('return@withContext cachedPlayData','environment.assertOwned()\n                return@withContext cachedPlayData')
# env APIs/cache are already admitted; these public response checks are supplementary, not the network ownership authority.
header='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.core.network.WbiUtils\nimport com.android.purebilibili.core.network.AppSignUtils\nimport android.util.Log as Logger\nimport kotlinx.coroutines.*\nimport com.bilipai.desktop.ui.DesktopOriginalVideoLoadRepository\nimport com.bilipai.desktop.ui.DesktopOriginalVideoLoadProtocolEnvironment\n\ninternal class DesktopOriginalVideoLoadProtocol(private val environment:DesktopOriginalVideoLoadProtocolEnvironment) : DesktopOriginalVideoLoadRepository {\n    private val api get() = environment.api\n    private val SharedContentRepository = DesktopOriginalSharedContentRequests { environment.api }\n    private val PlaybackStreamDataSource = DesktopOriginalPlaybackStreamDataSource { environment.playbackApi }\n    private val APP_API_COOLDOWN_MS = 120_000L\n    private var appApiCooldownUntilMs:Long\n        get() = environment.state.appApiCooldownUntilMs\n        set(value) { environment.state.appApiCooldownUntilMs = value }\n    private var wbiKeysCache:Pair<String,String>?\n        get() = environment.state.wbiKeys\n        set(value) { environment.state.wbiKeys = value }\n    private var wbiKeysTimestamp:Long\n        get() = environment.state.wbiKeysTimestamp\n        set(value) { environment.state.wbiKeysTimestamp = value }\n    private var last412Time:Long\n        get() = environment.state.last412Time\n        set(value) { environment.state.last412Time = value }\n    private val WBI_CACHE_DURATION = 1000 * 60 * 30\n    private suspend fun ensureBuvid3FromSpi() = environment.ensureBuvid()\n    private fun playbackAccount() = environment.playbackAccount()\n    private fun hasPlaybackSessionCookie() = environment.hasPlaybackSessionCookie()\n    private fun playbackAccessToken() = environment.playbackAccessToken()\n    private fun playbackAccessTokenPlatform() = environment.playbackAccessTokenPlatform()\n    override fun isUsingDedicatedPlaybackAccount() = playbackAccount() != null\n    override fun isPlaybackLoggedIn() = resolveVideoPlaybackAuthState(hasPlaybackSessionCookie(), !playbackAccessToken().isNullOrEmpty())\n    override fun isPlaybackVip() = environment.isPlaybackVip()\n    override fun isAppApiCoolingDown() = (appApiCooldownUntilMs-System.currentTimeMillis()).coerceAtLeast(0L)>0L\n    private fun isDirectedTrafficModeActive() = shouldEnableDirectedTrafficMode(environment.directedTrafficEnabled(),environment.isMobileData())\n'
pager=mod('original_portrait_protocol_members',REPO/'desktop/tools/extract-upstream-video-fullscreen-pager.py')
header=header.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\nimport com.android.purebilibili.feature.video.ui.pager.PORTRAIT_PLAYBACK_TARGET_QUALITY\nimport com.android.purebilibili.feature.video.ui.pager.shouldUsePortraitParallelPlaybackBootstrap')
s+='\n'+pager.protocol_members(REPO)
s=s.replace('private suspend fun getWbiKeys()', 'internal suspend fun getWbiKeys()', 1)
p.put(H/'prepared/protocol/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt',header+s+'\n}\n')
p.put(H/'raw-protocol-adaptations.json',json.dumps(changes,ensure_ascii=False,indent=2)+'\n')
p.put(H/'original-stable/VideoRepository.kt',orig)
print('Full original raw playback selected protocol methods',len(methods),'lines',len((header+s).splitlines()))
"""
 exec(code,globals().copy())

def build_supplement():
 code=r"""original=p.original(p.BASE+'feature/video/viewmodel/VideoSupplementViewModel.kt')
a=original.index('fun interface VideoSupplementLoader');b=original.index('private val EmptyVideoSupplementLoader');loader=original[a:b]
a=original.index('class VideoSupplementViewModel(');b=original.index('internal fun VideoPlaybackUiState.Success.toSupplementSeed');body=original[a:b]
before=body
body=body.replace('class VideoSupplementViewModel(\n    private val loader: VideoSupplementLoader = EmptyVideoSupplementLoader,\n    private val startDelayMs: Long = 300L\n) : ViewModel() {','internal class VideoSupplementViewModel(\n    private val environment:com.bilipai.desktop.ui.DesktopOriginalVideoSupplementEnvironment,\n    private val loader:VideoSupplementLoader,\n    private val startDelayMs:Long=300L,\n) : AutoCloseable {\n    private val viewModelScope get() = environment.scope\n    private fun <T> MutableStateFlow(initial:T):MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)\n')
body=body.replace('    override fun onCleared() {','    override fun close() {').replace('        super.onCleared()','')
header='package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.feature.video.note.VideoNoteUiState\nimport kotlinx.coroutines.*\nimport kotlinx.coroutines.flow.*\n'
p.put(H/'prepared/supplement/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSupplementViewModel.kt',header+loader+body)
p.put(H/'supplement-adaptations.json',json.dumps(dict(before=before,after=body,originalSha=p.digest(original),boundary='Required actual loader+same entry scope/owned flow; no default EmptyLoader/ViewModel base'),ensure_ascii=False,indent=2)+'\n')
"""
 exec(code,globals().copy())

def build_core():
 for rel in CORE_DIRECT:emit('com/android/purebilibili/'+rel,original(BASE+rel),BASE+rel,'direct')
 rel='feature/video/viewmodel/PlaybackCompletionPolicy.kt'
 emit('com/android/purebilibili/'+rel,original(BASE+rel).replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player'),BASE+rel,'full-original-native-constant-alias')
 rel='feature/video/playback/loader/PlaybackLoader.kt'
 emit('com/android/purebilibili/'+rel,original(BASE+rel).replace('import com.android.purebilibili.feature.video.usecase.VideoPlaybackUseCase','import com.bilipai.desktop.ui.DesktopOriginalVideoLoadPort as VideoPlaybackUseCase'),BASE+rel,'full-original-required-port-alias')
 t=original(BASE+'feature/video/usecase/VideoPlaybackUseCase.kt')
 body='\n\n'.join([declaration(t,'VideoLoadResult',True),declaration(t,'QualitySwitchResult'),declaration(t,'PlaybackSelectionResult')])
 header='package com.android.purebilibili.feature.video.usecase\nimport com.android.purebilibili.data.model.VideoLoadError\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.feature.video.playback.audio.*\nimport com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource\n'
 emit('com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoLoadModels.kt',header+body+'\n',BASE+'feature/video/usecase/VideoPlaybackUseCase.kt','full-original-result-contracts')
def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,lex,sel,decls,p,OUTPUTS,SOURCES,AUDITS
 REPO=Path(repo);OUTPUT=Path(output);STANDALONE=standalone;OUTPUTS=[];SOURCES={};AUDITS={}
 lex=mod('stateLexer',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 sel=mod('stateSelect',REPO/'desktop/tools/extract-upstream-video-detail-full-units.py');sel.parser=mod('stateTokens',REPO/'desktop/tools/sync-upstream.py')
 decls=mod('stateDecls',REPO/'desktop/tools/extract-appearance-platform.py')
 p=types.SimpleNamespace(BASE=BASE,REPO=REPO,lex=lex,original=original,declaration=declaration,digest=digest,put=put)
 build_models();build_core();build_usecase();build_raw_protocol();build_supplement();generate_shared_playback_sources(repo,output)
 target=wide(OUTPUT/'original-video-state-core-selection.json');target.parent.mkdir(parents=True,exist_ok=True)
 target.write_text(json.dumps(dict(upstreamCommit=COMMIT,sourceOnly=True,fullVmHolderAccepted=False,sources=list(SOURCES.values()),outputs=OUTPUTS,audits=AUDITS),indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
 return OUTPUTS
if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--standalone',action='store_true');args=ap.parse_args();generate(args.repo,args.output,args.standalone)
