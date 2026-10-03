from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,json,re
PINS={'app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt': '04ba7fe22888802cb1da9e4d91ef97df318c49b2a9aea37672a00f4b10504010', 'app/src/main/java/com/android/purebilibili/core/util/FeedRefreshPaging.kt': '7ed91d88e7198729fd2503db6317a0abe15c92efaed7c670bce122bd37c7ae98', 'app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt': '26bae6dfaa2f92b4418f37012821ca1a6c0bf5a7fcb82a47cd0369c86dc4b5f2', 'app/src/main/java/com/android/purebilibili/feature/common/VideoLazyKeyPolicy.kt': '628f76935eba485f1adf9b81774643fc3b33a791970e5144de032bdad4463312', 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt': 'a952a41fc91d694bdc0410f3bba89806acf0b2f33272c071aca97500bcbeb5ad'}
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
  s=(_desktop_canonical_source(root, path)).read_text(encoding='utf-8');assert sha(s)==pin,path;raw[path]=s
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
 adapt('import androidx.lifecycle.ViewModel','// Windows: entry lifetime owns this original transient VM')
 adapt('import androidx.lifecycle.viewmodel.compose.viewModel','// Windows: required retained local supplies the original VM')
 adapt('import com.android.purebilibili.core.store.SettingsManager','// Windows: the same original persisted Home/card-settings projection')
 adapt('import com.android.purebilibili.data.repository.VideoRepository','// Windows: original raw Video request ports on the existing shared owner')
 adapt('class PartitionFeedViewModel : ViewModel() {','internal class PartitionFeedViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopPartitionEnvironment) {')
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
 bodies=[fn(original,n)for n in ('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton','ContentVideoGridSkeleton','ContentVideoGridSkeletonFixedColumns','ContentCategoryGridSkeleton')]
 emit('com/android/purebilibili/core/ui/skeleton/DesktopOriginalMediaListSkeleton.kt',header+'\n\n'.join(bodies)+'\n',origin,[])
 origin=BASE+'feature/common/VideoLazyKeyPolicy.kt';emit('com/android/purebilibili/feature/common/VideoLazyKeyPolicy.kt',raw[origin],origin,[])
 (out/'partition-producer-inventory.json').write_text(json.dumps(dict(upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',pins=PINS,emitted=rows,completePartitionInverseByteEqual=True,sharedSkeletonPulseHasOneExistingProducer=True),indent=2)+'\n',encoding='utf-8')
 print('Complete original Partition Screen/Content/VM and shared media skeleton produced')
if __name__=='__main__':main()
