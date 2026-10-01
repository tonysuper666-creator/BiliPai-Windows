from pathlib import Path
import hashlib,importlib.util,json,re,sys,zipfile,subprocess
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=module('content_tokens',REPO/'desktop/tools/sync-upstream.py');selector=module('content_decls',REPO/'desktop/tools/extract-appearance-platform.py')
helpers=module('content_stage2_helpers',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py')
SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
def read(rel):
 path=BASE+rel
 if path not in SOURCES:
  raw=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n')
  SOURCES[path]=dict(text=raw.decode(),sha256LF=sha(raw),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip())
  write(LANE/'original-stable'/path,raw)
 return SOURCES[path]['text']
def emit(rel,t,origin,mode):
 write(LANE/'generated/com/android/purebilibili'/rel,t);OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,origin=BASE+origin,sha256LF=sha(t),mode=mode,lines=len(t.splitlines())))
def adapt(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));ADAPT.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def remove_function(t,name):
 a,b=helpers.full.function_range(t,name)
 if t[:a].rstrip().endswith('@Composable'):a=t.rfind('@Composable',0,a)
 return t[:a]+t[b:]
def main():
 for rel in ['feature/video/ui/section/AiSummarySection.kt','feature/video/viewmodel/AiSummaryUiPolicy.kt','data/repository/AiSummaryFetchPolicy.kt','feature/video/ui/section/VideoSupplementSection.kt','feature/video/ui/section/VideoNoteSection.kt','feature/video/ui/components/RelatedVideoActionSheet.kt','feature/video/ui/components/RelatedVideoActionPolicy.kt','feature/video/ui/components/RelatedVideoCardLayoutPolicy.kt','feature/video/ui/section/VideoDetailMotionBudgetPolicy.kt','feature/video/ui/components/VideoCommentBackToTopPolicy.kt','feature/video/ui/section/VideoDetailSecondaryButton.kt','feature/video/note/VideoNoteEditorStatePolicy.kt','feature/video/note/VideoNoteEmotionPolicy.kt','feature/video/note/VideoNoteVisibilityPolicy.kt']:
  emit(rel,read(rel),rel,'direct-complete-original')
 rel='feature/video/screen/PortraitDetailPresentationPolicy.kt';t=read(rel)
 a,b=helpers.full.function_range(t,'isVideoDetailIntroScrollPastCollapseThreshold')
 emit('feature/video/screen/DesktopOriginalVideoContentCollapseThreshold.kt','package com.android.purebilibili.feature.video.screen\n'+t[a:b]+'\n',rel,'complete-original-collapse-threshold-function')
 rel='feature/video/ui/components/CommentSortFilterBar.kt';t=read(rel)
 cut=t.index('/**\n * 评论排序分段控件，放置在详情页顶栏')
 header=t[:t.index('internal data class CommentSortSegmentedControlSpec')]
 emit('feature/video/ui/components/DesktopOriginalCommentSortFilterBar.kt',header+t[cut:],rel,'complete-original-remaining-filter-bar-reference-sole-generic-header-and-policies')
 rel='feature/video/screen/VideoNavigationCidPolicy.kt';t=read(rel)
 a,b=helpers.full.function_range(t,'buildVideoNavigationOptions');body=t[a:b]
 body=body.replace('buildVideoNavigationOptions(','buildDesktopOriginalVideoNavigationOptions(').replace('base: Bundle?','base: Pair<Long, String?>?').replace('): Bundle?','): Pair<Long, String?>?')
 body=adapt(body,'''    return Bundle().apply {
        if (base != null) {
            putAll(base)
        }
        if (targetCid > 0L) {
            putLong(VIDEO_NAV_TARGET_CID_KEY, targetCid)
        }
        if (normalizedCover.isNotEmpty()) {
            putString(VIDEO_NAV_COVER_URL_KEY, normalizedCover)
        }
    }''','''    return Pair(
        if (targetCid > 0L) targetCid else base?.first ?: 0L,
        if (normalizedCover.isNotEmpty()) normalizedCover else base?.second
    )''','Original two-key Bundle omission/base merge/cover trim maps primitive typed CID+cover fields, without a second route schema')
 emit('feature/video/screen/DesktopOriginalVideoNavigationOptions.kt','package com.android.purebilibili.feature.video.screen\n'+body+'\n',rel,'complete-original-navigation-builder-two-key-platform-adapt')
 rel='feature/video/ui/components/RelatedVideoItem.kt';t=read(rel)
 t=t.replace('import android.widget.Toast\n','').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t=t.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoContentBindings as LocalContext\nimport coil3.compose.LocalPlatformContext')
 for name in ['SettingsManager','TodayWatchFeedbackStore']:
  t=t.replace('import com.android.purebilibili.core.store.'+name+'\n','')
 for name in ['ActionRepository','BlockedUpRepository']:
  t=t.replace('import com.android.purebilibili.data.repository.'+name+'\n','')
 t=adapt(t,'internal const val RELATED_VIDEO_CARD_COVER_ASPECT_RATIO = 16f / 10f\n','','Existing BGM skeleton producer remains sole owner of the exact original cover constant')
 t=adapt(t,'''    val homeFeedCardStyle by SettingsManager
        .getHomeFeedCardStyle(context)
        .collectAsStateWithLifecycle(initialValue = HomeFeedCardStyle.BILIPAI)''','''    val homeFeedCardStyle by context.homeSettings.homeFeedCardStyle
        .collectAsStateWithLifecycle()''','Same actual global original Home preferences, with real initial value')
 t=t.replace('ImageRequest.Builder(context)','ImageRequest.Builder(LocalPlatformContext.current)')
 # Composition locals are read during composition, outside remember's noncomposable lambda.
 t=adapt(t,'    val context = LocalContext.current\n    // Keep the request stable','    val context = LocalPlatformContext.current\n    // Keep the request stable','Coil receives actual Windows platform context')
 t=t.replace('ImageRequest.Builder(LocalPlatformContext.current)','ImageRequest.Builder(context)')
 t=adapt(t,'val blockedUpRepository = remember { BlockedUpRepository.getInstance(context) }','val blockedUpRepository = context.blockedUps','Reuse the owned Root global block repository port, never a second instance')
 t=t.replace('ActionRepository.toggleWatchLater(pendingVideo.aid, true)','context.toggleWatchLater(pendingVideo.aid, true)')
 t=re.sub(r'Toast\.makeText\(\s*context,\s*([^,]+),\s*Toast\.LENGTH_SHORT\s*\)\.show\(\)',r'context.feedback(\1)',t)
 t=t.replace('context: android.content.Context','context: com.bilipai.desktop.ui.DesktopOriginalVideoContentBindings')
 t=t.replace('TodayWatchFeedbackStore.getSnapshot(context)','context.getFeedbackSnapshot()').replace('TodayWatchFeedbackStore.saveSnapshot(context, snapshot)','context.saveFeedbackSnapshot(snapshot)')
 t=adapt(t,'                                onVideoHidden?.invoke(blockRequest.video)','''                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                context.requireCurrent()
                                onVideoHidden?.invoke(blockRequest.video)''','Reject retired/canceled late block result before original UI callbacks')
 for variable in ['pendingVideo','blockRequest.video','request.video']:
  t=t.replace('onVideoHidden?.invoke('+variable+')','context.commitUi { onVideoHidden?.invoke('+variable+') }')
 start=t.index('                            scope.launch {\n                                val result = withContext')
 end=t.index('                                blockCreatorRequest = null\n',start)+len('                                blockCreatorRequest = null\n')
 before=t[start:end]
 after=before.replace('                            scope.launch {\n','                            scope.launch {\n                                try {\n',1)+'''                                } finally {
                                    context.cleanupUi {
                                        if (blockCreatorRequest === blockRequest) {
                                            isBlockingCreator = false
                                            blockCreatorRequest = null
                                        }
                                    }
                                }
'''
 t=adapt(t,before,after,'Canceled/failed block work releases only its own captured busy request while same detail owner remains alive')
 t=t.replace('import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n')
 emit('feature/video/ui/components/DesktopOriginalRelatedVideoItem.kt',t,rel,'complete-original-related-card-grid-feedback-actions-owner-platform-adapt')
 rel='feature/video/screen/VideoContentSection.kt';t=read(rel);t=remove_function(t,'VideoCommentTab')
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration').replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoContentBindings')
 t=t.replace('import com.android.purebilibili.core.util.ShareUtils\n','').replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoContentSettings as SettingsManager')
 t=t.replace('com.android.purebilibili.core.store.SettingsManager','SettingsManager')
 t=t.replace('(String, android.os.Bundle?) -> Unit','(String, Long, String?) -> Unit')
 t=t.replace('onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _ -> }','onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _, _ -> }')
 t=adapt(t,'    val onShareVideoNote:','    val platform = LocalDesktopOriginalVideoContentBindings.current\n    val onShareVideoNote:','Capture existing owner bindings during composition')
 t=adapt(t,'''ShareUtils.shareText(
            context = context,
            subject = document.title.ifBlank { info.title },''','''platform.shareText(
            subject = document.title.ifBlank { info.title },''','Same Root typed native actor receives original note subject and text')
 t=adapt(t,'''),
            chooserTitle = "分享视频笔记"
        )''',''')
        )''','Android chooser caption has no separate Windows native actor parameter')
 t=adapt(t,'''                        onRelatedVideoClick(
                            episode.bvid,
                            buildVideoNavigationOptions(targetCid = episode.cid)
                        )''','''                        val navigationOptions = buildDesktopOriginalVideoNavigationOptions(targetCid = episode.cid)
                        onRelatedVideoClick(episode.bvid, navigationOptions?.first ?: 0L, navigationOptions?.second)''','Original collection callback preserves original CID omission through same typed Root fields')
 t=adapt(t,'''                        val navOptions = buildVideoNavigationOptions(
                            targetCid = video.cid,
                            coverUrl = video.pic
                        )
                        onRelatedVideoClick(video.bvid, navOptions)''','''                        val navOptions = buildDesktopOriginalVideoNavigationOptions(
                            targetCid = video.cid,
                            coverUrl = video.pic
                        )
                        onRelatedVideoClick(video.bvid, navOptions?.first ?: 0L, navOptions?.second)''','Preserve original CID omission and cover trimming through the two typed Root fields')
 t=adapt(t,'''            onRelatedVideoClick = onRelatedVideoClick,
            animateLayout = animateVideoDetailLayout,''','''            onRelatedVideoClick = { bvid, cid -> onRelatedVideoClick(bvid, cid, null) },
            animateLayout = animateVideoDetailLayout,''','Existing complete original info renderer callback consumes CID; rich recommended callback retains cover separately')
 t=t.replace('com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(','com.bilipai.desktop.ui.DesktopReplyDissolvableContainer(')
 emit('feature/video/screen/DesktopOriginalVideoContentSection.kt',t,rel,'complete-original-content-groups-body-all-panels-reference-sole-commenttab-platform-adapt')
 rel='core/store/SettingsManager.kt';t=read(rel);manager=t[t.index('object SettingsManager {')+len('object SettingsManager {'):t.rfind('}')]
 body,names=helpers.member_closure(manager,['getShowVideoDetailCommentCount','getVideoDetailChromeScrollHideEnabled','getVideoAiSummaryEntryEnabled','getVideoNoteEnabled'])
 body=body.replace('private val','private val')
 header='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopOriginalVideoContentSettings {
'''
 emit('core/store/DesktopOriginalVideoContentSettings.kt',header+body+'\n}\n',rel,'selected-exact-original-read-getters-keys-same-global-settings')
 save(LANE/'content-source-selection.json',dict(commit=COMMIT,sources=[dict(path=p,**{k:v for k,v in r.items()if k!='text'})for p,r in SOURCES.items()],outputs=OUTPUTS,adaptations=ADAPT,scope='Entire VideoContentSection excluding sole existing original VideoCommentTab, plus complete AI/note/related UI units. Prepared only.'))
 print(json.dumps(dict(sources=len(SOURCES),outputs=len(OUTPUTS))))
if __name__=='__main__':main()
