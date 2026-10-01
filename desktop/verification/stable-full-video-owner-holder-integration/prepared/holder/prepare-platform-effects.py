from pathlib import Path
import hashlib,json,re
P=Path(__file__).resolve().parent;BASE='app/src/main/java/com/android/purebilibili/feature/video/screen/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
rows=[]
for name in ['VideoDetailPlatformEffectsHost','VideoDetailCommentFraudOverlayAdapter']:
 raw=wide(P/'original-stable'/BASE/(name+'.kt')).read_bytes().replace(b'\r\n',b'\n').decode();t=raw;edits=[]
 def change(a,b,label):
  global t
  assert a in t,(name,label,a);n=t.count(a);edits.append(dict(before=a,after=b,count=n,label=label));t=t.replace(a,b)
 def between(a,b,out,label):
  i=t.index(a);j=t.index(b,i);change(t[i:j],out,label)
 if name=='VideoDetailPlatformEffectsHost':
  change('import android.app.Activity\n','import com.bilipai.desktop.ui.DesktopOriginalVideoHolderWindowPort as Window\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform\n','Required actual Root Window port')
  change('import android.graphics.Rect','import androidx.compose.ui.unit.IntRect as Rect','Actual immutable pixel bounds')
  change('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player\nimport com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl','Sole native state/listener authority')
  change('    context: Context,\n    activity: Activity?,','    window: Window,','Actual same retained Window')
  change('    player: Player?,','    player: DesktopOriginalMpvSectionControl?,','Same actual PiP source control')
  change('    val latestPlayer by rememberUpdatedState(player)','    val latestPlayer by rememberUpdatedState(player)\n    val platform = LocalDesktopOriginalVideoHolderPlatform.current','Capture owned monotonic clock')
  change('LaunchedEffect(activity, playerBounds, pipModeEnabled)','LaunchedEffect(window, playerBounds, pipModeEnabled)','Same original effect dependency over actual Window')
  change('if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null)','if (!window.supportsPictureInPicture)','Explicit Windows capability, no forged SDK version')
  change('android.os.SystemClock.elapsedRealtime()','platform.elapsedRealtimeMillis()','Actual monotonic clock')
  change('playerBounds?.let(::Rect)','playerBounds','Immutable same-original bounds value')
  between('        val params = android.app.PictureInPictureParams.Builder()','    }\n}', '''        window.updatePictureInPicture(
            player = latestPlayer, sourceBounds = playerBounds,
            autoEnterEnabled = pipModeEnabled, seamlessResizeEnabled = pipModeEnabled,
        )
''','Original throttle and bounds selection retained; actual same-source PiP native consumer')
  between('        if (shouldKeepAwake) window?.addFlags','    }\n}', '''        val lease = window?.acquireKeepAwake(shouldKeepAwake)
        onDispose { lease?.close() }
''','Same owned wake lease, preserves existing player readback/listener algorithm')
  change('    view: View,\n','', 'No Android view authority')
  change('    insetsController: WindowInsetsControllerCompat?,\n','','No fake Android insets controller')
  change('if (view.isInEditMode || !isScreenActive || window == null || insetsController == null)','if (!isScreenActive || window == null)','Actual active Window admission')
  change('applyVideoDetailSystemBarsSpec(window, insetsController, effectiveSpec)','window.applySystemBars(effectiveSpec)','Required original spec -> actual Window client capability mapping')
 else:
  change('import android.content.Context','import com.bilipai.desktop.ui.DesktopCommentPlatform','Same existing owned comment feedback consumer')
  change('    context: Context,','    platform: DesktopCommentPlatform,','Original full PlaybackVM overload over same comment platform')
  change('Toast.makeText(context, lightMessage, Toast.LENGTH_SHORT).show()','platform.showFeedback(lightMessage)','Same actual Root feedback callback')
 for line in t.splitlines(True):
  if line.startswith('import android.') or any(line.startswith('import '+x) for x in ['androidx.core.view.','com.android.purebilibili.core.util.resolveSafeAndroidPipRational','com.android.purebilibili.feature.video.player.buildPipPlaybackRemoteActions']):change(line,'','Consumed Android implementation import')
 replay=raw
 for e in edits:replay=replay.replace(e['before'],e['after'],e['count'])
 assert replay==t
 outputName=name if name=='VideoDetailPlatformEffectsHost' else 'DesktopOriginalHolderCommentFraudOverlayAdapter'
 output='com/android/purebilibili/feature/video/screen/'+outputName+'.kt';put(P/'prepared/generated'/output,t)
 rows.append(dict(source=BASE+name+'.kt',originalSHA256LF=sha(raw),output=output,outputSHA256LF=sha(t),completeOriginalFile=True,orderedTransformReplayExact=True,edits=edits,overload='Full original PlaybackVM overload; existing generic composer overload remains sole and unchanged' if name.endswith('FraudOverlayAdapter') else None))
put(P/'platform-effects-source-audit.json',json.dumps(dict(passed=True,sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',rows=rows,productRuntimeAccepted=False),ensure_ascii=False,indent=2)+'\n')
print('Prepared actual Window effects and full PlaybackVM comment-fraud overload')
