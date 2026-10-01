from pathlib import Path
import hashlib,json,subprocess,re
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+'feature/partition/PartitionScreen.kt',BASE+'core/util/FeedRefreshPaging.kt',BASE+'core/ui/skeleton/ContentLoadingSkeletons.kt',BASE+'feature/common/VideoLazyKeyPolicy.kt',BASE+'feature/home/components/BottomBarMatchedLiquidChrome.kt']
def sha(b):return hashlib.sha256(b).hexdigest()
sources={}
for p in PATHS:
    b=(REPO/p).read_bytes().replace(b'\r\n',b'\n')
    original=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).replace(b'\r\n',b'\n');assert b==original,p
    sources[p]=b.decode('utf-8')
pins={p:sha(s.encode())for p,s in sources.items()}
# Generate one sole page producer from exact stable pins, retaining every original Partition declaration.
generator=r'''from pathlib import Path
import argparse,hashlib,json,re
PINS=__PINS__
BASE='app/src/main/java/com/android/purebilibili/'
def sha(s):return hashlib.sha256(s.encode('utf-8')).hexdigest()
def balanced(s,start):
 depth=0;quote=None;escape=False;line=False;block=False;i=start
 while i<len(s):
  c=s[i];n=s[i:i+2]
  if line:
   if c=='\n':line=False
  elif block:
   if n=='*/':block=False;i+=1
  elif quote:
   if escape:escape=False
   elif c=='\\':escape=True
   elif c==quote:quote=None
  elif n=='//':line=True;i+=1
  elif n=='/*':block=True;i+=1
  elif c in ('"',"'"):quote=c
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return i+1
  i+=1
 raise ValueError('unbalanced original Kotlin declaration')
def fn(s,name):
 m=re.search(r'(?m)^(?:internal |private )?fun (?:BoxScope\.)?'+re.escape(name)+r'\(',s);assert m,name
 start=m.start();annotation=s.rfind('@Composable',0,start)
 if annotation>=0 and s[annotation:start].strip()=='@Composable':start=annotation
 opening=s.index('{',m.end());return s[start:balanced(s,opening)]
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--repo',required=True);ap.add_argument('--output',required=True);a=ap.parse_args()
 root=Path(a.repo);out=Path(a.output);raw={}
 for path,pin in PINS.items():
  s=(root/path).read_text(encoding='utf-8');assert sha(s)==pin,path;raw[path]=s
 rows=[]
 def emit(path,s,origin,adaptations):
  p=out/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
  rows.append(dict(path=path,origin=origin,sha256LF=sha(s),adaptations=adaptations))
 origin=BASE+'feature/partition/PartitionScreen.kt';s=raw[origin];changes=[]
 def adapt(old,new,count=1):
  nonlocal s
  assert s.count(old)==count,(old,s.count(old),count);s=s.replace(old,new);changes.append(dict(before=old,after=new,count=count))
 adapt('import android.os.Build','// Windows: actual current renderer capability via required Root platform')
 adapt('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext')
 adapt('import androidx.lifecycle.viewModelScope','// Windows: the retained captured-owner scope supplies VM lifetime')
 adapt('import com.android.purebilibili.core.store.SettingsManager','// Windows: the same original persisted Home/card-settings projection')
 adapt('import com.android.purebilibili.data.repository.VideoRepository','// Windows: original raw Video request ports on the existing shared owner')
 adapt('class PartitionFeedViewModel : ViewModel() {','internal class PartitionFeedViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopPartitionEnvironment) : ViewModel() {')
 adapt('MutableStateFlow(PartitionFeedUiState())','com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(PartitionFeedUiState(), desktopEnvironment.commitIfCurrent)')
 adapt('viewModelScope.launch {','desktopEnvironment.scope.launch {')
 adapt('VideoRepository.getPopularVideos','desktopEnvironment.video.getPopularVideos')
 adapt('VideoRepository.getRegionVideos','desktopEnvironment.video.getRegionVideos')
 adapt('fun PartitionScreen(','internal fun PartitionScreen(')
 adapt('fun PartitionContent(','internal fun PartitionContent(')
 adapt('viewModel: PartitionFeedViewModel = viewModel()','viewModel: PartitionFeedViewModel = com.bilipai.desktop.ui.LocalDesktopPartitionViewModel.current')
 adapt('com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsRenderEffectBackedHaze')
 adapt('Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsDirectHazeLiquidGlassFallback')
 adapt('SettingsManager.getHomeSettings(context)','context.settings.homeSettings')
 adapt('SettingsManager\n        .getHomeFeedCardStyle(context)','context.settings.homeFeedCardStyle')
 for getter,field in [('getHomeUpBadgesVisible','showHomeUpBadges'),('getHomeDurationStyle','homeDurationStyle'),('getHomePublishTimeVisible','showHomePublishTime')]:
  adapt('SettingsManager\n        .'+getter+'(context)','com.android.purebilibili.core.store.DesktopOriginalHomeCardVisualSettings\n        .getSettings(context.pluginContext).map { it.'+field+' }')
 s=s.replace('import kotlinx.coroutines.flow.update','import kotlinx.coroutines.flow.update\nimport kotlinx.coroutines.flow.map')
 reverse=s.replace('import kotlinx.coroutines.flow.update\nimport kotlinx.coroutines.flow.map','import kotlinx.coroutines.flow.update')
 for row in reversed(changes):
  assert reverse.count(row['after'])==row['count'];reverse=reverse.replace(row['after'],row['before'])
 assert reverse==raw[origin]
 emit('com/android/purebilibili/feature/partition/DesktopOriginalPartitionScreen.kt',s,origin,changes)
 origin=BASE+'core/util/FeedRefreshPaging.kt';emit('com/android/purebilibili/core/util/FeedRefreshPaging.kt',raw[origin],origin,[])
 origin=BASE+'core/ui/skeleton/ContentLoadingSkeletons.kt';original=raw[origin]
 header=original[:original.index('/**')]
 bodies=[fn(original,n)for n in ('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton')]
 emit('com/android/purebilibili/core/ui/skeleton/DesktopOriginalMediaListSkeleton.kt',header+'\n\n'.join(bodies)+'\n',origin,[])
 origin=BASE+'feature/common/VideoLazyKeyPolicy.kt';emit('com/android/purebilibili/feature/common/VideoLazyKeyPolicy.kt',raw[origin],origin,[])
 (out/'partition-producer-inventory.json').write_text(json.dumps(dict(upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',pins=PINS,emitted=rows,completePartitionInverseByteEqual=True,sharedSkeletonPulseHasOneExistingProducer=True),indent=2)+'\n',encoding='utf-8')
 print('Complete original Partition Screen/Content/VM and shared media skeleton produced')
if __name__=='__main__':main()
'''
# Avoid a producer reading generated source: exact original pins are embedded, all body transforms local and reversible.
generator=generator.replace('__PINS__',repr(pins))
path=HERE/'prepared/tools/extract-upstream-home-partition.py';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(generator,encoding='utf-8',newline='\n')
subprocess.run([__import__('sys').executable,str(path),'--repo',str(REPO),'--output',str(HERE/'generated')],check=True)
original=sources[BASE+'feature/home/components/BottomBarMatchedLiquidChrome.kt']
# The extra indicator belongs to the existing shared-liquid-tabs generator, never this page producer.
base=(REPO/'desktop/tools/extract-upstream-shared-liquid-tabs.py').read_text(encoding='utf-8')
old="   selected_names.append('BottomBarMatchedDockVisibility')"
assert base.count(old)==1
desired=base.replace(old,old+"\n   selected_names.append('BottomBarMatchedLiquidIndicator')")
delta=HERE/'shared-tabs-indicator.patch';import difflib
delta.write_text(''.join(difflib.unified_diff(base.splitlines(keepends=True),desired.splitlines(keepends=True),fromfile='a/desktop/tools/extract-upstream-shared-liquid-tabs.py',tofile='b/desktop/tools/extract-upstream-shared-liquid-tabs.py')),encoding='utf-8',newline='\n')
(HERE/'shared-tabs-indicator-base.json').write_text(json.dumps(dict(path='desktop/tools/extract-upstream-shared-liquid-tabs.py',baselineSha256LF=sha(base.encode()),desiredSha256LF=sha(desired.encode()),installGeneratedReference=False),indent=2)+'\n',encoding='utf-8')
spec=__import__('importlib.util',fromlist=['util']);modspec=spec.spec_from_file_location('partition_source',path);producer=spec.module_from_spec(modspec);modspec.loader.exec_module(producer)
header=original[:original.index('/**')]
body=producer.fn(original,'BottomBarMatchedLiquidIndicator')
ref=HERE/'reference-only/DesktopPartitionIndicatorReference.kt';ref.parent.mkdir(parents=True,exist_ok=True);ref.write_text(header+'\n'+body+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(pins=pins,producerSHA256=sha(path.read_bytes()))))
