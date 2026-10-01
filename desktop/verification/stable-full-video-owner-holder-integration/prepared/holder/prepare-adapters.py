from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/feature/video/screen/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('mask',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');mask=importlib.util.module_from_spec(spec);spec.loader.exec_module(mask)
FILES=['VideoDetailDomainEffects','VideoDetailPlaybackSessionEffects','VideoDetailEntryTransitionObserver','VideoDetailInlineCollapseState','VideoDetailParentUiSnapshot','VideoDetailScreenContent','VideoDetailTransitionHost','VideoDetailReturnMediaLayout','VideoDetailReturnSourceCardChrome','VideoDetailPlayerTransitionHost','VideoDetailPhoneContent','VideoDetailCommonOverlayAdapter','VideoDetailFavoriteFolderOverlayAdapter','VideoDetailFeedbackOverlayAdapter','VideoDetailPlayerSettingsOverlayAdapter','VideoDetailPortraitOverlayAdapter','VideoDetailOverlayHost','VideoDetailInputOverlayAdapter','VideoDetailDownloadOverlayAdapter']
rows=[]
for name in FILES:
 raw=wide(P/'original-stable'/BASE/(name+'.kt')).read_bytes().replace(b'\r\n',b'\n').decode();t=raw;edits=[]
 def change(a,b,label,required=True):
  global t
  if a not in t:
   assert not required,(name,label,a)
   return
  at=[];pos=0
  while True:
   i=t.find(a,pos)
   if i<0:break
   at.append(i);pos=i+len(a)
  edits.append(dict(label=label,before=a,after=b,count=len(at),positions=at));t=t.replace(a,b)
 aliases={
  'import android.content.Context':'import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context',
  'import androidx.compose.ui.platform.LocalContext':'import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext',
  'import androidx.compose.ui.platform.LocalConfiguration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration',
  'import androidx.media3.common.Player':'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player',
  'import androidx.media3.exoplayer.ExoPlayer':'import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer',
  'import com.android.purebilibili.feature.video.state.VideoPlayerState':'import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState',
  'import com.android.purebilibili.feature.video.player.MiniPlayerManager':'import com.bilipai.desktop.ui.DesktopOriginalVideoHolderMini as MiniPlayerManager',
  'import com.android.purebilibili.feature.video.danmaku.DanmakuManager':'import com.bilipai.desktop.ui.DesktopOriginalPortraitDanmakuPort as DanmakuManager',
 }
 for a,b in aliases.items():change(a,b,'Existing same-player/global-store platform type alias',False)
 for line in t.splitlines(True):
  if line.startswith('@file:androidx.annotation.OptIn') or line.startswith('@androidx.annotation.OptIn'):change(line,'','Android Media3 compiler annotation only')
 if name=='VideoDetailDomainEffects':change('engagementViewModel.initWithContext(context)','engagementViewModel.initWithContext(context.pluginContext)','Actual existing Engagement context over same global Store')
 if name=='VideoDetailPlaybackSessionEffects':
  change('import com.android.purebilibili.feature.video.danmaku.rememberDanmakuManager','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform','Same document owner from retained Root')
  change('val danmakuManager = rememberDanmakuManager(playbackIdentity)','val danmakuManager = LocalDesktopOriginalVideoHolderPlatform.current.danmaku','No independent document/parser/session authority')
  change('context.applicationContext','context','Actual Windows context identity')
  change('com.android.purebilibili.core.util.AnalyticsHelper.logScreenView("VideoDetailScreen")','platform.logScreenView("VideoDetailScreen")','Required actual Root analytics binding')
  change(') {\n    LaunchedEffect(viewModel, state)',') {\n    val platform = LocalDesktopOriginalVideoHolderPlatform.current\n    LaunchedEffect(viewModel, state)','Capture required platform in composition before callback')
 if 'com.android.purebilibili.core.util.Logger.' in t:change('com.android.purebilibili.core.util.Logger.','android.util.Log.','Existing actual local logging adapter')
 if 'import com.android.purebilibili.core.util.Logger\n' in t:change('import com.android.purebilibili.core.util.Logger\n','import android.util.Log as Logger\n','Existing actual local logging adapter')
 if name=='VideoDetailPortraitOverlayAdapter':
  change('import com.android.purebilibili.data.repository.StoryRepository','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform','Required owned original Story request seam')
  change('    val context = LocalContext.current','    val context = LocalContext.current\n    val platform = LocalDesktopOriginalVideoHolderPlatform.current','Capture same page platform before asynchronous callbacks')
  change('StoryRepository.getStoryFeed(','platform.getStoryFeed(','Same owned raw request; original recommendations algorithm intact')
  change('viewModel = playbackViewModel,','viewModel = platform.portraitPlaybackOwner,','Required read/action view of the SAME original Playback VM')
 # Android Uri is only the platform image identity boundary; selected bodies remain intact.
 if 'import android.net.Uri\n' in t:change('import android.net.Uri\n','','Same owned Windows picker/file-URI String contract')
 if re.search(r'\bUri\b',t):
  change('List<Uri>','List<String>','Same original nine-image callback collection, Windows file URI identity',False)
 if name=='VideoDetailInputOverlayAdapter':
  # These are original PRIVATE local UI snapshots. Another original file already
  # owns the same JVM class name; retain every field/body under a private unique name.
  change('CommentInputSnapshot','DesktopOriginalHolderCommentInputSnapshot','Original private snapshot identifier only, avoid actual common-comment JVM class collision')
  change('CommentInputActions','DesktopOriginalHolderCommentInputActions','Original private action identifier only, avoid actual common-comment JVM class collision')
 if name=='VideoDetailPlayerSettingsOverlayAdapter':
  change('SettingsManager\n        .getPlayerDiagnosticLoggingEnabled','com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings\n        .getPlayerDiagnosticLoggingEnabled','Reuse existing exact player getter',False)
 # Compiler followups complete every remaining Android effect, no fake implementations emitted.
 replay=raw
 for e in edits:replay=replay.replace(e['before'],e['after'],e['count'])
 assert replay==t
 output='com/android/purebilibili/feature/video/screen/'+name+'.kt';put(P/'prepared/generated'/output,t)
 rows.append(dict(source=BASE+name+'.kt',originalSHA256LF=sha(raw),output=output,outputSHA256LF=sha(t),wholeOriginalFile=True,orderedTransformReplayExact=True,edits=edits))
put(P/'adapter-source-audit.json',json.dumps(dict(passed=True,sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',rows=rows,status='Prepared full body closure; platform followups and compilation pending',productRuntimeAccepted=False),ensure_ascii=False,indent=2)+'\n')
print('Prepared',len(rows),'whole original Holder adapters')
