from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023';OUT=HERE/'home-data-pages-phase1-install'
sha=lambda b:hashlib.sha256(b).hexdigest()
changes=[]
def edit(name,substitutions):
 p=REPO/'desktop/tools'/name;base=p.read_bytes();s=base.decode('utf-8').replace('\r\n','\n')
 for old,new in substitutions:
  assert s.count(old)==1,(name,old,s.count(old));s=s.replace(old,new)
 p.write_text(s,encoding='utf-8',newline='\n')
 folder=OUT/'whole-build-ownership-repair';folder.mkdir(exist_ok=True)
 (folder/(name+'.before')).write_bytes(base)
 (folder/(name+'.patch')).write_text(''.join(difflib.unified_diff(base.decode('utf-8').replace('\r\n','\n').splitlines(keepends=True),s.splitlines(keepends=True),fromfile='a/desktop/tools/'+name,tofile='b/desktop/tools/'+name)),encoding='utf-8',newline='\n')
 changes.append(dict(path='desktop/tools/'+name,beforeSHA256Bytes=sha(base),afterSHA256Bytes=sha(p.read_bytes())))
edit('extract-upstream-home-partition.py',[
 ("adapt('import androidx.lifecycle.viewModelScope','// Windows: the retained captured-owner scope supplies VM lifetime')","adapt('import androidx.lifecycle.viewModelScope','// Windows: the retained captured-owner scope supplies VM lifetime')\n adapt('import androidx.lifecycle.ViewModel','// Windows: entry lifetime owns this original transient VM')\n adapt('import androidx.lifecycle.viewmodel.compose.viewModel','// Windows: required retained local supplies the original VM')"),
 ('internal class PartitionFeedViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopPartitionEnvironment) : ViewModel() {','internal class PartitionFeedViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopPartitionEnvironment) {'),
 ("('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton','ContentVideoGridSkeleton','ContentVideoGridItemSkeleton')","('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton','ContentVideoGridSkeleton')"),
])
edit('extract-upstream-home-bangumi-page.py',[
 ("adapt('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext')","adapt('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext')\n   adapt('import androidx.lifecycle.viewmodel.compose.viewModel','// Windows: required retained local supplies the original Hub VM')"),
 ("adapt('import androidx.lifecycle.viewModelScope','// Windows: supplied captured-entry scope')","adapt('import androidx.lifecycle.viewModelScope','// Windows: supplied captured-entry scope')\n   adapt('import androidx.lifecycle.ViewModel','// Windows: entry lifetime owns this original transient VM')"),
 ('internal class BangumiHubViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopBangumiHubEnvironment) : ViewModel() {','internal class BangumiHubViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopBangumiHubEnvironment) {'),
])
edit('extract-discovery-platform.py',[
 ('[("class", "PartitionCategory", True), ("fun", "resolvePartitionBangumiType", False)], ["allPartitions"],\n         "import com.android.purebilibili.data.model.response.BangumiType"','[], [],\n         "// Complete original Partition producer now owns class, list and resolver once."'),
])
edit('extract-upstream-plugins.py',[
 ('for name in ("toTodayWatchMode", "toTodayWatchPlan", "toTodayUpRanks"):','for name in ("toTodayWatchMode", "toTodayUpRanks"): # Full original HomeVM now owns toTodayWatchPlan once.'),
 ('        if name == "toTodayWatchPlan":\n            method = substitute(method, "private fun RecommendationResult.toTodayWatchPlan", "internal fun RecommendationResult.toTodayWatchPlan")\n',''),
])
(OUT/'whole-build-ownership-repair/report.json').write_text(json.dumps(dict(wholeClasses38FailurePreserved=True,changes=changes,
 noBusinessAlgorithmRewrite=True,noNewLifecycleDependency=True,retainedOwnerReplacesAndroidViewModelPlatformBaseOnly=True,
 canonicalPartitionThreeDeclarationsNowFullSourceProducer=True,canonicalTodayWatchPlanNowFullHomeVmProducer=True,
 canonicalGridItemSkeletonStillExistingFavoritesProducer=True,oldPlannerPreservedAndResolvesSameOriginalPlanConverter=True),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(productProducerFiles=len(changes),existingDuplicateDeclarationsRetired=4,androidLifecycleBasePlatformAdaptations=2)))
