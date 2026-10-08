from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,difflib
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
import argparse
cli=argparse.ArgumentParser(description='Complete fixed-tag AudioModeScreen and AudioModeMusicPlayer original source consumers')
cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true')
args=cli.parse_args();REPO=args.repo.resolve();LANE=args.output.resolve()
FEATURE='stable-video-audio-original'
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/video/screen/AudioModeScreen.kt': {'sha256LF': '8be4fda06237bf87601aa55f844bc501991a426fbde778f3abe6b264fd87ce2d', 'gitBlob': '88daf54da269640a18a1ab07bb030f0bff5fa462'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/AudioModeMusicPlayer.kt': {'sha256LF': '4aa801e622630315ff95d9ba5bb4c277ecee524055ef4f5ccc5c71e648f136ea', 'gitBlob': '86588a2c4f6404a0464b8d3ef856bfce43734d14'}}
manifest=json.loads((REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
assert manifest['upstreamCommit']=='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'

COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
OUT=LANE;SOURCES={};CHANGES={};OUTPUTS=[]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def write(p,t):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode()if isinstance(t,str)else t)
def module(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=module('audio_tokens',REPO/'desktop/tools/sync-upstream.py');selector=module('audio_select',REPO/'desktop/tools/extract-appearance-platform.py')
def read(rel):
 p=BASE+rel;raw=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO)
 assert raw==safe(_desktop_canonical_source(REPO, p)).read_bytes().replace(b'\r\n',b'\n'),p
 SOURCES[p]={'text':raw.decode(),'sha256LF':sha(raw),'gitBlob':subprocess.check_output(['git','rev-parse',COMMIT+':'+p],cwd=REPO,text=True).strip()}
 assert SOURCES[p]['sha256LF']==SOURCE_PINS[p]['sha256LF'] and SOURCES[p]['gitBlob']==SOURCE_PINS[p]['gitBlob'],p
 if not args.standalone:
  rows=[r for r in manifest['sources']if r['path']==p]
  assert len(rows)==1 and rows[0]['sha256']==SOURCE_PINS[p]['sha256LF'] and FEATURE in rows[0]['features'],p
 write(LANE/'original-retained'/(p+'.txt'),raw);return raw.decode()
def replace(t,b,a,label,all=False):
 n=t.count(b);assert n>0 and(all or n==1),(CURRENT,label,n)
 indices=[m.start()for m in re.finditer(re.escape(b),t)]
 for i in indices[::-1]if all else indices:
  CHANGES.setdefault(CURRENT,[]).append({'label':label,'before':b,'after':a,'index':i});t=t[:i]+a+t[i+len(b):]
 return t
def between(t,b,e,a,label):
 i=t.index(b);j=t.index(e,i);return replace(t,t[i:j],a,label)
def emit(rel,t):
 p=OUT/'com/android/purebilibili'/rel;write(p,t);OUTPUTS.append({'path':str(p),'sha256LF':sha(t),'original':BASE+rel})
CURRENT='feature/video/screen/AudioModeScreen.kt';t=read(CURRENT)
for imp in ['android.app.Activity','android.app.PictureInPictureParams','android.content.Context','android.content.ContextWrapper','android.content.pm.ActivityInfo','com.android.purebilibili.core.util.applyPlayerRequestedOrientation','android.content.res.Configuration','android.os.Build','android.util.Rational','androidx.compose.ui.platform.LocalContext','androidx.compose.ui.platform.LocalConfiguration','com.android.purebilibili.feature.audio.player.AudioNowPlayingSession','com.android.purebilibili.feature.video.state.rememberVideoPlayerState']:
 t=replace(t,'import '+imp+'\n','','Actual same Windows/native owner replaces Android entry '+imp)
t=replace(t,'import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as Player\nimport com.bilipai.desktop.ui.*','Same actual MPV command/readback facade')
t=replace(t,'import com.android.purebilibili.core.store.SettingsManager\n','','Required current global preferences')
t=replace(t,'import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel','import com.bilipai.desktop.ui.DesktopOriginalAudioVideoOwner as VideoPlaybackViewModel','Required view of parent sole original full VM')
t=replace(t,'Player.STATE_','DesktopOriginalPlaybackStates.STATE_','Original native state policies use actual same-source facts',True)
t=replace(t,'internal fun resolveAudioModeRequestedOrientation(isLandscape: Boolean): Int =\n    if (isLandscape) {\n        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT\n    } else {\n        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE\n    }','internal fun resolveAudioModeRequestedOrientation(isLandscape: Boolean): DesktopOriginalAudioOrientation =\n    if (isLandscape) {\n        DesktopOriginalAudioOrientation.PORTRAIT\n    } else {\n        DesktopOriginalAudioOrientation.LANDSCAPE\n    }','Explicit actual Windows presentation intent; not fabricated Activity orientation integer')
t=replace(t,selector.declarations(parser,t,['shouldShowAudioModePipButton']).rstrip(),'','Android SDK clause replaced by actual Root PiP capability')
t=between(t,'private tailrec fun Context.findHostActivity()','internal fun Player.handleAudioModePlayPause()','', 'No fake Android Context/Activity lookup')
t=between(t,'private fun enterAudioModePip(','@Composable\nfun AudioModeScreen','', 'Actual same Root PiP effect is required')
t=replace(t,'fun AudioModeScreen(','internal fun AudioModeScreen(','Internal actual typed platform API; entire original UI body preserved')
t=replace(t,'engagementViewModel: com.android.purebilibili.feature.video.viewmodel.VideoEngagementViewModel =\n        androidx.lifecycle.viewmodel.compose.viewModel(),','engagementViewModel: com.android.purebilibili.feature.video.viewmodel.VideoEngagementViewModel =\n        LocalDesktopOriginalAudioModePlatform.current.engagement,','Same original entry engagement VM')
t=replace(t,'composerViewModel: com.android.purebilibili.feature.video.viewmodel.VideoComposerViewModel =\n        androidx.lifecycle.viewmodel.compose.viewModel(),','composerViewModel: DesktopOriginalAudioComposerPort =\n        LocalDesktopOriginalAudioModePlatform.current.composer,','Same original composer subject owner') if False else t
# The original three lifecycle factories are distinct owners, never implicitly recreated.
t=replace(t,'composerViewModel: com.android.purebilibili.feature.video.viewmodel.VideoComposerViewModel =\n        androidx.lifecycle.viewmodel.compose.viewModel(),','composerViewModel: DesktopOriginalAudioComposerPort =\n        LocalDesktopOriginalAudioModePlatform.current.composer,','Same original composer subject owner')
t=replace(t,'supplementViewModel: com.android.purebilibili.feature.video.viewmodel.VideoSupplementViewModel =\n        androidx.lifecycle.viewmodel.compose.viewModel(),','supplementViewModel: com.android.purebilibili.feature.video.viewmodel.VideoSupplementViewModel =\n        LocalDesktopOriginalAudioModePlatform.current.supplement,','Same original supplement subject owner')
t=between(t,'    val context = LocalContext.current','    val playerChromeProfile', '''    val platform = LocalDesktopOriginalAudioModePlatform.current
    val musicPlatform = LocalDesktopOriginalMusicUiPlatform.current
    val context = musicPlatform.context
    val isLandscape by platform.window.isLandscape.collectAsStateWithLifecycle()
    val orientationActionLabel = resolveAudioModeOrientationActionLabel(isLandscape)
    DisposableEffect(platform.window) {
        val lease = platform.window.orientationLease()
        onDispose { lease.close() }
    }
    val homeSettings by musicPlatform.homeSettings.collectAsStateWithLifecycle()
''','Actual Root presentation lease and same global settings current state')
t=replace(t,'rememberVideoPlayerState(\n            context = context,','platform.standalonePlayerState(','Original standalone admission uses SAME native player/VM factory, no new actor')
t=replace(t,'AudioNowPlayingSession.markListening()','platform.session.markListening()','Same current Listen/session publication')
t=replace(t,'val enterPip = remember(context) { { enterAudioModePip(context.findHostActivity()) } }','val enterPip = remember(platform.window) { { platform.window.enterPip() } }','Same actual Root PiP effect')
t=replace(t,'showPipButton = shouldShowAudioModePipButton(Build.VERSION.SDK_INT)','showPipButton = platform.window.pipAvailable','Actual capability, no fabricated Android SDK')
t=replace(t,'activity?.applyPlayerRequestedOrientation(resolveAudioModeRequestedOrientation(isLandscape))','platform.window.setRequestedOrientation(resolveAudioModeRequestedOrientation(isLandscape))','Same actual Windows window/presentation actor')
emit(CURRENT,t)
CURRENT='feature/video/screen/AudioModeMusicPlayer.kt';t=read(CURRENT)
for imp in ['androidx.media3.exoplayer.ExoPlayer','com.android.purebilibili.core.player.PlayerVolumeController','com.android.purebilibili.feature.audio.player.AudioNowPlayingSession','com.android.purebilibili.feature.video.player.MiniPlayerManager','com.android.purebilibili.core.store.PlayHistoryStore','com.android.purebilibili.feature.video.player.PlaylistManager','androidx.lifecycle.viewmodel.compose.viewModel']:
 t=replace(t,'import '+imp+'\n','','Same existing owned actor replaces '+imp)
t=replace(t,'@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n\n','','Media3-only annotation has no Windows decoder')
t=replace(t,'import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as Player\nimport com.bilipai.desktop.ui.*','Same actual native player facade')
t=replace(t,'import androidx.compose.ui.platform.LocalContext\n','','Required same original platform context')
t=replace(t,'import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalMusicUiSettings as SettingsManager','Same canonical original keys through existing preferences actor')
t=replace(t,'import com.android.purebilibili.feature.audio.viewmodel.MusicViewModel','import com.bilipai.desktop.ui.DesktopOriginalAudioLyricsPort as MusicViewModel','Required same lyrics owner; no new VM/client')
t=replace(t,'import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel','import com.bilipai.desktop.ui.DesktopOriginalAudioVideoOwner as VideoPlaybackViewModel','Parent sole original full VM view')
t=replace(t,'engagementViewModel: VideoEngagementViewModel = viewModel()','engagementViewModel: VideoEngagementViewModel = LocalDesktopOriginalAudioModePlatform.current.engagement','Same original engagement owner')
t=replace(t,'    val context = LocalContext.current','    val platform = LocalDesktopOriginalAudioModePlatform.current\n    val musicPlatform = LocalDesktopOriginalMusicUiPlatform.current\n    val context = musicPlatform.context','Required same owned context/session/library')
t=replace(t,'PlaylistManager','platform.playlist','Same actual native queue view',True)
t=replace(t,'val lyricsViewModel = androidx.lifecycle.viewmodel.compose.viewModel<MusicViewModel>(\n        key = "audio_mode_lyrics"\n    )','val lyricsViewModel = platform.lyrics','Same lyrics owner view, no second VM')
t=replace(t,'PlayHistoryStore.record(\n            context = context,','musicPlatform.history.record(','Same actual library publication')
t=replace(t,'PlayHistoryStore.saveLastSession(\n                context,','musicPlatform.history.saveLastSession(','Same actual library checkpoint')
t=replace(t,'PlayHistoryStore.lastSession(context)','musicPlatform.history.lastSession()','Same actual library checkpoint read')
t=replace(t,'val commentViewModel: VideoCommentViewModel = viewModel()','val commentViewModel: VideoCommentViewModel = platform.comments','Same original generic comment owner')
t=replace(t,'val exoPlayer = player as? ExoPlayer ?: return@LaunchedEffect','val ownedPlayer = player ?: return@LaunchedEffect','Use actual same native source control for Mini metadata')
t=replace(t,'MiniPlayerManager.getInstance(context).setVideoInfo(','platform.mini.setVideoInfo(','Same Root native publication actor')
t=replace(t,'externalPlayer = exoPlayer','externalPlayer = ownedPlayer','Same Root actual native source')
t=replace(t,'com.android.purebilibili.core.store.FavoriteInteractionSettingsStore\n        .getQuickSaveDefaultFolder(LocalContext.current)','platform.favoriteInteraction\n        .getQuickSaveDefaultFolder()','Same existing original favorite interaction preferences')
t=replace(t,'player?.let(PlayerVolumeController::applyPreferredVolume)','player?.let(platform::applyPreferredVolume)','Same actual preferred/native volume authority')
t=replace(t,'AudioNowPlayingSession.dismiss()','platform.session.dismiss()','Same current native Listen session')
t=replace(t,'Player.STATE_','DesktopOriginalPlaybackStates.STATE_','Actual same source decoder facts',True)
t=replace(t,'object : Player.Listener {\n            override fun onEvents(player: Player, events: Player.Events) {\n                snapshot = player.readAudioPlaybackSnapshot()\n            }\n        }', '''object : DesktopOriginalMpvOverlayControl.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { snapshot = player.readAudioPlaybackSnapshot() }
            override fun onPlaybackStateChanged(playbackState: Int) { snapshot = player.readAudioPlaybackSnapshot() }
            override fun onPlaybackParametersChanged(parameters: DesktopOriginalPlaybackRate) { snapshot = player.readAudioPlaybackSnapshot() }
            override fun onSourceTransition(sourceVersion: Long) { snapshot = player.readAudioPlaybackSnapshot() }
        }''','Actual owned StateFlow listener events replace Media3 combined-events callback')
t=replace(t,'onPrevious = { viewModel.playPreviousAudioModeTrack() }',
 'onPrevious = { viewModel.playPreviousAudioModeTrack(historyScope) }',
 'Actual original AudioMode UI scope launches a source-bound playlist operation')
t=replace(t,'onNext = { viewModel.playNextAudioModeTrack() }',
 'onNext = { viewModel.playNextAudioModeTrack(historyScope) }',
 'Actual original AudioMode UI scope launches a source-bound playlist operation')
emit(CURRENT,t)
for o in OUTPUTS:
 original=o['original'];reverse=safe(o['path']).read_text(encoding='utf-8')
 for c in CHANGES[original.removeprefix(BASE)][::-1]:
  i=c['index'];a=c['after'];assert reverse[i:i+len(a)]==a,c['label'];reverse=reverse[:i]+c['before']+reverse[i+len(a):]
 assert reverse==SOURCES[original]['text']
 write(LANE/'diffs'/(Path(o['path']).stem+'.diff'),''.join(difflib.unified_diff(SOURCES[original]['text'].splitlines(True),safe(o['path']).read_text(encoding='utf-8').splitlines(True),fromfile=original,tofile=o['path'])))
write(LANE/'audio-source-pins.json',json.dumps({p:{k:v for k,v in d.items()if k!='text'}for p,d in SOURCES.items()},indent=2)+'\n')
write(LANE/'audio-adaptations.json',json.dumps(CHANGES,ensure_ascii=False,indent=2)+'\n')
write(LANE/'audio-outputs.json',json.dumps(OUTPUTS,indent=2)+'\n');print(json.dumps({'outputs':len(OUTPUTS),'exactWholeBodyInverse':True}))
