from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';C='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def parts(t):
 tokens=parser.kotlin_tokens(t);depth=pa=br=0;starts=[]
 for i,(word,a,b) in enumerate(tokens):
  if depth==pa==br==0 and word in ['fun','val','var','class','object','interface']:
   name=tokens[i+1][0]
   if word=='fun':name=re.search(r'(\w+)\s*\(',t[b:])[1]
   line=t.rfind('\n',0,a)+1
   while line>0:
    prior=t.rfind('\n',0,line-1)+1
    if t[prior:line].strip().startswith('@'):line=prior
    else:break
   starts.append((name,line))
  depth+=(word=='{')-(word=='}');pa+=(word=='(')-(word==')');br+=(word=='[')-(word==']')
 return t[:starts[0][1]],[(n,t[a:starts[i+1][1] if i+1<len(starts) else len(t)])for i,(n,a)in enumerate(starts)]
specs={
 'core/ui/components/VideoStatRow.kt':None,
 'feature/video/ui/components/InteractiveChoiceOverlay.kt':None,
 'feature/video/ui/components/CelebrationAnimations.kt':{'LikeBurstAnimation'},
 'feature/video/ui/components/VideoActionFeedbackHost.kt':None,
 'feature/download/BatchDownloadDialog.kt':None,
 'feature/download/BatchDownloadDialogLayoutPolicy.kt':None,
 'feature/download/BatchDownloadSelectionPolicy.kt':None,
 'feature/video/screen/LargeScreenVideoLayout.kt':None,
 'core/ui/blur/HazeEffectCompat.kt':None,
 'core/ui/transition/VideoCardTransitionClock.kt':{'VideoCardMorphProgressReporter','LocalVideoCardMorphProgressReporter'},
 'feature/video/policy/VideoDetailScrollCoordinator.kt':None,
 'core/util/AppAdaptiveStrategySnapshot.kt':None,
 'core/util/PlayerOrientationPolicy.kt':{'PlayerWindowOrientationPolicy','toPlayerWindowOrientationPolicy','resolvePlayerWindowOrientationPolicy','resolveEffectivePlayerRequestedOrientation','isPlayerAxisOrientationRequest'},
 'core/util/PlayerPresentationPolicy.kt':None,
 'feature/video/state/VideoPlayerState.kt':{'shouldReuseMiniPlayerAtEntry'},
}
actual=set(zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-65/main-kotlin.jar').namelist())
rows=[]
for path,include in specs.items():
 raw=subprocess.check_output(['git','-C',str(REPO),'show',C+':'+BASE+path]).replace(b'\r\n',b'\n').decode();put(P/'original-stable'/BASE/path,raw)
 header,decls=parts(raw);package=re.search(r'package ([\w.]+)',raw)[1];excluded=[];chosen=[]
 for n,body in decls:
  if include is not None and n not in include:excluded.append(n);continue
  if path=='core/util/PlayerOrientationPolicy.kt' and n=='resolvePlayerWindowOrientationPolicy' and 'displayContext: AppDisplayContext,'not in body[:body.index('{') if'{'in body else len(body)]:excluded.append(n);continue
  classpath=package.replace('.','/')+'/'+n+'.class'
  if re.search(r'\b(?:class|interface|object)\s+'+re.escape(n)+r'\b',body) and classpath in actual:excluded.append(n);continue
  chosen.append((n,body))
 t=header+''.join(b for n,b in chosen);selected=t;edits=[]
 def change(a,b,label):
  global t
  if a not in t:return
  count=t.count(a);t=t.replace(a,b);edits.append(dict(before=a,after=b,count=count,label=label))
 if include is not None:
  tokens={x[0]for x in parser.kotlin_tokens(''.join(b for _,b in chosen))}
  for line in t.splitlines(True):
   if line.startswith('import '):
    alias=line.strip().split(' as ')[-1]if' as 'in line else line.strip().split('.')[-1]
    if alias not in tokens and alias not in ['getValue','setValue','provideDelegate'] and not line.strip().endswith('*'):change(line,'','Unused imports from omitted existing declarations')
 change('import android.content.pm.ActivityInfo','import com.bilipai.desktop.ui.DesktopOriginalVideoOrientationRequest as ActivityInfo','Original numeric policy inputs to actual Window')
 change('import android.content.res.Configuration','import com.bilipai.desktop.ui.DesktopOriginalVideoWindowOrientation as Configuration','Original orientation inputs')
 change('@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)','', 'Android Media3 annotation only; selected original pure policy has no player implementation')
 change('import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset','import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset','Same existing installed original skin decoder and lifecycle')
 if path=='feature/video/screen/LargeScreenVideoLayout.kt':
  change('import com.bilipai.desktop.ui.DesktopOriginalVideoWindowOrientation as Configuration','import com.bilipai.desktop.ui.DesktopHomeCardWindowBounds as Configuration','Actual client bounds')
  change('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext','Same global settings context')
  change('import com.android.purebilibili.feature.video.state.VideoPlayerState','import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState','Same native state projection')
 if path=='feature/download/BatchDownloadDialog.kt':
  change('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration','Actual Compose client viewport')
  change('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext','Same Store context')
 if path=='core/ui/transition/VideoCardTransitionClock.kt':output='com/android/purebilibili/core/ui/transition/DesktopOriginalVideoCardMorphProgressReporter.kt'
 elif path=='feature/video/state/VideoPlayerState.kt':output='com/android/purebilibili/feature/video/state/DesktopOriginalMiniEntryPolicy.kt'
 elif path.endswith('CelebrationAnimations.kt'):output='com/android/purebilibili/feature/video/ui/components/DesktopOriginalLikeBurstAnimation.kt'
 else:output='com/android/purebilibili/'+path
 replay=selected
 for e in edits:replay=replay.replace(e['before'],e['after'],e['count'])
 assert replay==t
 put(P/'prepared/generated'/output,t);rows.append(dict(source=BASE+path,originalSHA256LF=sha(raw),output=output,outputSHA256LF=sha(t),retainedDeclarations=[n for n,_ in chosen],excludedDeclarations=excluded,orderedTransformReplayExact=True,edits=edits))
put(P/'missing-source-audit.json',json.dumps(dict(passed=True,sourceCommit=C,rows=rows,productRuntimeAccepted=False),ensure_ascii=False,indent=2)+'\n')
print('Prepared missing original closure',len(rows))
