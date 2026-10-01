from pathlib import Path
import difflib,hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('full_video_body_parser',LANE/'prepare.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def read(p):return m.wide(p).read_bytes().replace(b'\r\n',b'\n').decode()
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def save(p,v):m.write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
out=LANE/'install-audit-02';rows=[]
inventory=json.loads(read(LANE/'source-inventory.json'))
for r in inventory['sourceIdentities']:
 text=read(LANE/'original-stable'/r['path']);assert sha(text)==r['sha256LF']
for r in inventory['outputs']:
 text=read(LANE/'generated'/r['path']);assert sha(text)==r['sha256LF']
 if r['mode']=='direct':assert text==read(LANE/'original-stable'/r['origin'])
def body(origin,output,name,reverse=None):
 original=read(LANE/'original-stable'/(m.BASE+origin));generated=read(LANE/'generated/com/android/purebilibili'/output)
 a,b=m.function_range(original,name);x,y=m.function_range(generated,name)
 old=original[a:b];new=generated[x:y];normalized=reverse(new)if reverse else new
 same=normalized==old
 rows.append(dict(name=name,source=m.BASE+origin,sourceStartLine=original[:a].count('\n')+1,sourceEndLine=original[:b].count('\n')+1,originalBodySha256LF=sha(old),generatedFile=output,generatedBodySha256LF=sha(new),reverseNormalizedBodyEqual=same,declaredPlatformAdaptation=reverse is not None))
 if not same:
  m.write(out/'body-diffs'/f'{name}.patch',''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile=origin+':'+name,tofile=output+':'+name)))
 return same
def reverse_info(t):
 t=t.replace('    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current\n','')
 t=t.replace('(String, Long) -> Unit','(String, android.os.Bundle?) -> Unit')
 t=t.replace('val context = LocalDesktopOriginalVideoInfoBindings.current.context','val context = LocalContext.current')
 t=t.replace('getPlayerControlVisibilitySettings(LocalDesktopOriginalVideoInfoBindings.current.context)','getPlayerControlVisibilitySettings(LocalContext.current)')
 t=t.replace('ImageRequest.Builder(LocalPlatformContext.current)','ImageRequest.Builder(LocalContext.current)')
 t=t.replace('DesktopOriginalInlineBgmSection(','InlineBgmSection(')
 t=t.replace('com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings','SettingsManager')
 t=re.sub(r'(?<![\w.])SettingsManager', 'com.android.purebilibili.core.store.SettingsManager',t)
 return t
for name in ['VideoTitleSection','VideoDetailSponsorLabelChip','VideoTitleWithDesc','UpInfoSection','DescriptionSection']:
 assert body('feature/video/ui/section/VideoInfoSection.kt','feature/video/ui/section/DesktopOriginalVideoInfoSection.kt',name,reverse_info),name
for rel in ['feature/video/ui/gesture/GestureLevelOverlay.kt','feature/video/ui/components/CircularGesturePercentText.kt']:
 old=read(LANE/'original-stable'/(m.BASE+rel));new=read(LANE/'generated/com/android/purebilibili'/rel)
 restored=new.replace('import com.bilipai.desktop.ui.DesktopOriginalVideoGestureClock as SystemClock\n','import android.os.SystemClock\n')
 restored=restored.replace('import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n','import android.os.Build\n').replace('desktopDetailRenderEffectsSupported()','Build.VERSION.SDK_INT >= Build.VERSION_CODES.S')
 assert restored==old,rel
 rows.append(dict(source=m.BASE+rel,generatedFile=rel,wholeFileReverseNormalizedEqual=True,originalSha256LF=sha(old),generatedSha256LF=sha(new)))
old=read(LANE/'original-stable'/(m.BASE+'feature/video/ui/section/VideoActionSection.kt'))
new=read(LANE/'generated/com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoActionSection.kt')
assert m.remove_function(old,'TripleProgressIcon')==new
rows.append(dict(source=m.BASE+'feature/video/ui/section/VideoActionSection.kt',wholeRemainingFileByteEqual=True,excludedReference='actual47 public TripleProgressIcon, sole Profile producer'))
# Full original state/usecase and raw methods stay reviewable as exact original→generated diffs.
origin=read(LANE/'original-stable'/(m.BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt'))
candidate=read(LANE/'generated/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt')
o=origin[origin.index('class VideoEngagementViewModel('):origin.index('internal fun VideoPlaybackUiState.Success.toEngagementSeed')]
g=candidate[candidate.index('internal class VideoEngagementViewModel('):]
m.write(out/'body-diffs/FullVideoEngagementViewModel.patch',''.join(difflib.unified_diff(o.splitlines(True),g.splitlines(True),fromfile='original-full-Engagement-class',tofile='owned-full-Engagement-class')))
rows.append(dict(source=m.BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt',completeClassPreservedWithExplicitOwnedAdaptation=True,originalClassSha256LF=sha(o),adaptedClassSha256LF=sha(g),fullDiff='body-diffs/FullVideoEngagementViewModel.patch',pendingOriginalSuccessExtensions=['toEngagementSeed','withEngagement']))
o=read(LANE/'original-stable'/(m.BASE+'data/repository/ActionRepository.kt'))
g=read(LANE/'generated/com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt')
for name in ['followUser','favoriteVideo','getDefaultFolderId','likeVideo','dislikeVideo','coinVideo','tripleAction','toggleWatchLater','checkLikeStatus','checkFavoriteStatus','checkFollowStatus','checkCoinStatus']:
 a,b=m.function_range(o,name);x,y=m.function_range(g,name);old=o[a:b];new=g[x:y]
 m.write(out/'body-diffs'/f'raw-{name}.patch',''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='original:'+name,tofile='owned:'+name)))
 rows.append(dict(name=name,source=m.BASE+'data/repository/ActionRepository.kt',sourceStartLine=o[:a].count('\n')+1,sourceEndLine=o[:b].count('\n')+1,originalBodySha256LF=sha(old),generatedBodySha256LF=sha(new),declaredChanges='Required same API/credentials; cancellation/current-owner guards and existing follow bus; logger platform alias only',fullDiff=f'body-diffs/raw-{name}.patch'))
save(out/'source-body-audit.json',dict(passed=True,identities=len(inventory['sourceIdentities']),outputs=len(inventory['outputs']),directFullFileByteEqual=sum(r['mode']=='direct'for r in inventory['outputs']),completeOrdinaryInfoBodiesReverseNormalized=5,wholeActionRemainingBodyByteEqual=True,rows=rows,strictInverseClaimScope='Info5, Gesture2, Action remaining file, Direct8; owned VM/protocol full diffs separately explicit, not claimed byte-identical'))
print(json.dumps(dict(passed=True,rows=len(rows),identities=len(inventory['sourceIdentities']))))
