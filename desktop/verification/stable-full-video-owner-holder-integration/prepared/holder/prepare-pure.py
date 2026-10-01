from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
BASE='app/src/main/java/com/android/purebilibili/feature/video/screen/'
spec=importlib.util.spec_from_file_location('tokens',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
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
 return t[:starts[0][1]],[(n,t[a:starts[i+1][1] if i+1<len(starts) else len(t)]) for i,(n,a) in enumerate(starts)]
FILES=['LargeScreenVideoLayoutPolicy','ContinuousPlayerTransitionPolicy','PlayerCollapseAutoPausePolicy','PortraitDetailPresentationPolicy','VideoDetailBackPolicy','VideoDetailEntryLoadPolicy','VideoDetailReturnLoadBudgetPolicy','VideoDetailTopBarActionPolicy','VideoInitialPagePolicy','VideoDetailTransitionPolicy','VideoDetailSessionPolicy','VideoNavigationCidPolicy','VideoDetailPlatformPolicy']
EXCLUDES={
 'PortraitDetailPresentationPolicy':{'isVideoDetailIntroScrollPastCollapseThreshold'},
 'VideoDetailSessionPolicy':{'CommentUrlNavigationTarget','resolveCommentUrlNavigationTarget'},
 'VideoNavigationCidPolicy':{'buildVideoNavigationOptions'},
 'VideoDetailPlatformPolicy':{'VideoDetailSystemBarsVisibilityPolicy','resolveVideoDetailSystemBarsVisibilityPolicy','shouldApplyStatusBarPaddingToVideoPlayerChrome','applyVideoDetailSystemBarsSpec','findActivity','isActivityInMultiWindowOrFloatingMode'},
}
rows=[]
for name in FILES:
 raw=wide(P/'original-stable'/BASE/(name+'.kt')).read_bytes().replace(b'\r\n',b'\n').decode();header,decls=parts(raw);excluded=EXCLUDES.get(name,set());assert excluded<={n for n,_ in decls}
 chosen=[(n,t) for n,t in decls if n not in excluded];t=header+''.join(body for _,body in chosen);selected=t;edits=[]
 def change(a,b,label):
  global t
  assert a in t,(name,label,a);count=t.count(a);t=t.replace(a,b);edits.append(dict(label=label,before=a,after=b,count=count))
 if 'android.graphics.Rect' in t:change('android.graphics.Rect','androidx.compose.ui.unit.IntRect','Immutable actual Compose pixel bounds; original comparison math unchanged')
 if 'import android.os.Bundle\n' in t:change('import android.os.Bundle\n','','Original Bundle producer omitted; existing three-parameter native navigation helper is sole')
 if name=='VideoDetailPlatformPolicy':
  change('import android.app.Activity','import com.bilipai.desktop.ui.DesktopOriginalVideoHolderPresentation as Activity','Required real Root Window presentation owner')
  change('import android.content.pm.ActivityInfo','import com.bilipai.desktop.ui.DesktopOriginalVideoOrientationRequest as ActivityInfo','Original numeric policy inputs only; no Android player/activity implementation')
  change('import android.content.res.Configuration','import com.bilipai.desktop.ui.DesktopOriginalVideoWindowOrientation as Configuration','Original orientation policy constants')
  change('import android.view.OrientationEventListener','import com.bilipai.desktop.ui.DesktopOriginalVideoPhysicalOrientation as OrientationEventListener','Original physical-angle unknown constant, explicit optional sensor capability')
  change('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player','Same actual native player state constants')
  for line in t.splitlines(True):
   if line.startswith('import ') and any(x in line for x in ['android.content.Context','android.os.Build','android.view.Window','androidx.core.view.','WindowMetricsCalculator','core.ui.setWindow','core.util.applyPlayerRequestedOrientation']):change(line,'','Android implementation imports consumed by required Window port')
  change('isActivityInMultiWindowOrFloatingMode(\n        activity = activity,\n        displayContext = displayContext,\n    )','activity.isInMultiWindowMode','Actual presentation reads real Root Window capability, not inferred phone bounds')
  change('activity.applyPlayerRequestedOrientation(', 'activity.requestOrientation(', 'Original requested orientation dispatched to required Root Window owner')
  change('activity.requestedOrientation','activity.currentRequestedOrientation','Actual retained Window requested-presentation readback')
 # Every retained original declaration is byte-identical before explicitly tracked edits.
 replay=selected
 for e in edits:replay=replay.replace(e['before'],e['after'],e['count'])
 assert replay==t
 output='com/android/purebilibili/feature/video/screen/'+name+'.kt';put(P/'prepared/generated'/output,t)
 rows.append(dict(source=BASE+name+'.kt',originalSHA256LF=sha(raw),output=output,outputSHA256LF=sha(t),retainedDeclarations=[n for n,_ in chosen],excludedExistingOrPlatformDeclarations=sorted(excluded),completeFile=not excluded,originalSelectedBeforeAdaptationSHA256LF=sha(selected),orderedTransformReplayExact=True,edits=edits))
put(P/'pure-source-audit.json',json.dumps(dict(passed=True,sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',rows=rows,productRuntimeAccepted=False),ensure_ascii=False,indent=2)+'\n')
print('Prepared',len(rows),'Holder policy inputs')
