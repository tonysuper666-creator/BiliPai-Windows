from pathlib import Path
import argparse,hashlib,json,re,importlib.util,sys
sys.dont_write_bytecode=True
args=argparse.ArgumentParser(description="Complete original independent Bangumi Catalog/Detail/Timeline/Review bodies; Root owned platform bindings only.")
args.add_argument('--repo',type=Path,required=True);args.add_argument('--output',type=Path,required=True)
args.add_argument('--manifest',type=Path);options=args.parse_args()
C=options.repo.resolve();OUT=options.output.resolve();OUT.mkdir(parents=True,exist_ok=True);ROWS=[]
BASE='app/src/main/java/com/android/purebilibili/'
INPUT={'upstreamCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'}
PINS={'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiScreen.kt': '7d6be537fed4dcb3bd251749402a1d79bfa9703507bebf479109f55646d73078', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiDetailScreen.kt': '7aaa20bcbfec0174453f28d5c27db530633b0fcf15ef0a2733267e03cf1547d3', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiTimelineScreen.kt': 'd34dea667e32da6b89e4a322192b9472e0cb86b8b16b10ff5cb165f03c9842f0', 'app/src/main/java/com/android/purebilibili/feature/bangumi/ui/detail/BangumiDetailComponents.kt': '5becc31ca3b88526541e010394c9a152670aa4998b08a0291b26f7ebe17b4153', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiReviewScreen.kt': 'e4a9d64425e5244a7b237e231148931a71d288b3d9e93f7c98a18db26d36da2a', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiViewModel.kt': 'd720f3e4966801b37a86972b44f90a9a4882280739c3bc71aa8a7049852cf77b', 'app/src/main/java/com/android/purebilibili/data/repository/BangumiReviewRepository.kt': '7dd7ac7da8498648fae7242f921bc6654814ccd47dc28d678730449f78070a12', 'app/src/main/java/com/android/purebilibili/data/repository/BangumiRepository.kt': '358d9c8f0b9a787de638c69db7b4bda67974b649b8db4fab4fb34f244e5ad612', 'app/src/main/java/com/android/purebilibili/feature/bangumi/policy/BangumiFollowStatusPolicy.kt': '4f4d3f8befea28b3e4715061fdc71f563ee26d453c5899925188a8d1d2275e20', 'app/src/main/java/com/android/purebilibili/feature/bangumi/MyFollowStats.kt': '4ec993ecd0f5493c8d7d4c12e3a925c176d212d9d360aa18c772762ab3a491b1', 'app/src/main/java/com/android/purebilibili/feature/bangumi/policy/BangumiUiPolicy.kt': '7483af137b9c00e8b48343a09705f9584b0b7c5528d85c528ed35a334682f285', 'app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt': '26bae6dfaa2f92b4418f37012821ca1a6c0bf5a7fcb82a47cd0369c86dc4b5f2'}
spec=importlib.util.spec_from_file_location('parser',C/'desktop/tools/sync-upstream.py')
parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
spec=importlib.util.spec_from_file_location('media_extract',C/'desktop/tools/extract-upstream-media.py')
media=importlib.util.module_from_spec(spec);spec.loader.exec_module(media)
def sha(s):return hashlib.sha256(s.encode('utf-8')).hexdigest()
def original(short):
 rel=BASE+short;s=(C/rel).read_text(encoding='utf-8').replace('\r\n','\n')
 assert sha(s)==PINS[rel];return rel,s
class Edit:
 def __init__(self,s):self.before=s;self.s=s;self.changes=[]
 def replace(self,before,after,count=None):
  actual=self.s.count(before);assert actual>0 and (count is None or actual==count),(before,actual,count)
  self.s=self.s.replace(before,after);self.changes.append(dict(before=before,after=after,count=actual))
 def optional(self,before,after):
  if before in self.s:self.replace(before,after)
 def write(self,rel,name):
  reverse=self.s
  for d in reversed(self.changes):
   assert reverse.count(d['after'])==d['count'],(name,d['after'],reverse.count(d['after']),d['count'])
   reverse=reverse.replace(d['after'],d['before'])
  assert reverse==self.before,name
  path=OUT/'com/android/purebilibili'/rel.removeprefix(BASE).rsplit('/',1)[0]/name;path.parent.mkdir(parents=True,exist_ok=True)
  path.write_text('// GENERATED from '+rel+'; pinned LF SHA-256 '+PINS[rel]+'\n'+self.s,encoding='utf-8')
  ROWS.append(dict(originalPath=rel,originalSha256LF=PINS[rel],output=str(path.relative_to(OUT)),
      completeOriginalFileBody=True,adaptedSha256LF=sha(self.s),inverseByteEqual=True,changes=self.changes))

# Exact full original screens; platform globals become borrowed required locals.
rel,s=original('feature/bangumi/BangumiScreen.kt');e=Edit(s)
e.replace('import android.os.Build','import com.bilipai.desktop.ui.LocalDesktopOriginalBangumiPagesEnvironment')
e.replace('import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect','// Capability comes from the actual Desktop page environment.')
e.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext')
e.replace('import com.android.purebilibili.core.store.SettingsManager','import kotlinx.coroutines.flow.map')
e.replace('import com.android.purebilibili.feature.download.DownloadManager','// Root owns the existing cover download/save boundary.')
e.replace('viewModel: BangumiHubViewModel = viewModel()','viewModel: BangumiHubViewModel',1)
e.replace('fun BangumiScreen(', 'internal fun BangumiScreen(')
e.replace('SettingsManager.getShowPgcTimeline(context)','context.settings.homeSettings.map { it.showPgcTimeline }',1)
e.replace('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','LocalDesktopOriginalBangumiPagesEnvironment.current.hazeEffectSupported',1)
e.replace('DownloadManager.saveImageToGallery(context, url, title)','context.saveCover(url, title)',1)
e.replace('        initialType = initialType,\n    )','        initialType = initialType,\n        viewModel = LocalDesktopOriginalBangumiPagesEnvironment.current.hub,\n    )',1)
e.write(rel,'DesktopOriginalBangumiScreen.kt')

rel,s=original('feature/bangumi/BangumiDetailScreen.kt');e=Edit(s)
e.replace('import androidx.lifecycle.viewmodel.compose.viewModel','// Root supplies the actual retained full original ViewModel.')
e.replace('import androidx.compose.ui.platform.LocalConfiguration','// Window layout is provided by the existing responsive/window policy.')
e.replace('viewModel: BangumiViewModel = viewModel()','viewModel: BangumiViewModel',1)
e.replace('fun BangumiDetailScreen(', 'internal fun BangumiDetailScreen(',1)
e.write(rel,'DesktopOriginalBangumiDetailScreen.kt')

rel,s=original('feature/bangumi/BangumiTimelineScreen.kt');Edit(s).write(rel,'DesktopOriginalBangumiTimelineScreen.kt')

rel,s=original('feature/bangumi/ui/detail/BangumiDetailComponents.kt');e=Edit(s)
e.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalBangumiPagesEnvironment as LocalContext')
e.replace('import com.android.purebilibili.feature.video.controller.PlaybackProgressManager','// Borrow the sole actual global progress owner through this entry read port.')
e.replace('val manager = remember { PlaybackProgressManager.getInstance(context) }','val manager = remember(context) { context.progress }',1)
e.replace('manager.getCachedPosition(bvid)','manager.getCachedPosition(bvid, 0L)',1)
e.write(rel,'DesktopOriginalBangumiDetailComponents.kt')

rel,s=original('feature/bangumi/BangumiReviewScreen.kt');e=Edit(s)
e.replace('import android.widget.Toast','import com.bilipai.desktop.ui.DesktopOriginalBangumiPagesToast as Toast')
e.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalBangumiPagesEnvironment as LocalContext')
e.replace('import com.android.purebilibili.data.repository.BangumiReviewRepository','// Review requests borrow the actual Root owned API and csrf authority.')
e.replace('BangumiReviewRepository.','context.reviews.')
e.replace('val scope = rememberCoroutineScope()','val scope = context.scope',1)
e.write(rel,'DesktopOriginalBangumiReviewScreen.kt')

# Full combined VM: its original init/load/filter/detail/follow/search business remains.
rel,s=original('feature/bangumi/BangumiViewModel.kt');e=Edit(s)
e.replace('import androidx.lifecycle.viewModelScope','import com.bilipai.desktop.ui.DesktopOriginalBangumiPagesEnvironment\nimport com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow')
e.replace('import com.android.purebilibili.data.repository.BangumiRepository','// Existing Root services are required, never a new network singleton.')
e.replace('class BangumiViewModel : ViewModel() {','internal class BangumiViewModel(private val desktopEnvironment: DesktopOriginalBangumiPagesEnvironment) : ViewModel() {\n    private val viewModelScope get() = desktopEnvironment.scope',1)
e.replace('BangumiRepository.','desktopEnvironment.requests.')
e.replace('            followedSeasonIds = followedSeasonIds\n        )',
 '            followedSeasonIds = followedSeasonIds,\n            fetchPage = { type, page, pageSize -> desktopEnvironment.requests.getMyFollowBangumi(type = type, page = page, pageSize = pageSize) }\n        )',1)
# Preserve every original state expression while adding the existing short commit admission.
tokens=parser.kotlin_tokens(e.s)
calls=[]
for i,(token,start,end) in enumerate(tokens):
 if token!='MutableStateFlow':continue
 j=i+1
 if tokens[j][0]=='<':
  depth=1
  while depth:
   j+=1;depth+=(tokens[j][0]=='<')-(tokens[j][0]=='>')
  j+=1
 if tokens[j][0]!='(':continue
 depth=1;k=j
 while depth:
  k+=1;depth+=(tokens[k][0]=='(')-(tokens[k][0]==')')
 before=e.s[start:tokens[k][2]]
 after=before.replace('MutableStateFlow','DesktopHomeOwnedMutableStateFlow',1)
 after=after[:-1]+', desktopEnvironment.commitIfCurrent)'
 calls.append((before,after))
for b,a in calls:e.replace(b,a,1)
e.write(rel,'DesktopOriginalBangumiViewModel.kt')

# Whole original review operation bodies: only global api/csrf binding is replaced.
rel,s=original('data/repository/BangumiReviewRepository.kt');e=Edit(s)
e.replace('import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.network.BangumiApi')
e.replace('import com.android.purebilibili.core.store.TokenManager','// Root supplies its existing csrf reader.')
e.replace('object BangumiReviewRepository {','internal class DesktopOriginalBangumiReviewRequests(private val api: BangumiApi, private val csrf: () -> String?, private val owned: () -> Boolean, private val actionOwned: () -> Boolean) {',1)
e.replace('import kotlinx.coroutines.withContext','import com.bilipai.desktop.ui.ownedBangumiRequest')
e.replace('withContext(Dispatchers.IO)','ownedBangumiRequest(Dispatchers.IO, owned)')
e.replace('suspend fun likeReview(mediaId: Long, reviewId: Long): Result<Unit> = ownedBangumiRequest(Dispatchers.IO, owned)', 'suspend fun likeReview(mediaId: Long, reviewId: Long): Result<Unit> = ownedBangumiRequest(Dispatchers.IO, actionOwned)',1)
e.replace('    ): Result<Unit> = ownedBangumiRequest(Dispatchers.IO, owned)', '    ): Result<Unit> = ownedBangumiRequest(Dispatchers.IO, actionOwned)',1)
e.replace('NetworkModule.bangumiApi.','api.')
e.replace('TokenManager.csrfCache','csrf()')
e.write(rel,'DesktopOriginalBangumiReviewRequests.kt')

# Complete original non-Player season/catalog methods. Borrow the existing Hub
# object for already-produced methods; no independent HTTP/client/cache/store owner.
rel,s=original('data/repository/BangumiRepository.kt')
methods=['getBangumiIndex','getBangumiIndexWithFilter','getSeasonDetail','getPugvSeasonDetail','getBangumiMediaInfo','getSeasonSections','getSeasonDetailByMediaId','followBangumi']
pieces=[];method_rows=[]
for name in methods:
 body=media.function(s,name,parser);e=Edit(body)
 e.optional('TokenManager.csrfCache','csrf()')
 e.optional('com.android.purebilibili.core.store.TokenManager.sessDataCache','sessionCookie()')
 e.optional('withContext(Dispatchers.IO)','ownedBangumiRequest(Dispatchers.IO, actionOwned)' if name=='followBangumi' else 'ownedBangumiRequest(Dispatchers.IO, owned)')
 e.optional('return@withContext','return@ownedBangumiRequest')
 e.optional('${csrf.take(10)}','[Root credential bound]')
 pieces.append(e.s)
 reverse=e.s
 for d in reversed(e.changes):reverse=reverse.replace(d['after'],d['before'])
 assert reverse==body
 method_rows.append(dict(method=name,originalSha256LF=sha(body),adaptedSha256LF=sha(e.s),inverseByteEqual=True,changes=e.changes))
header='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.Dispatchers
import com.bilipai.desktop.ui.ownedBangumiRequest
import kotlinx.serialization.json.Json

/** State-free original protocol bodies borrowing the same Root API/Hub/credentials. */
internal class DesktopOriginalBangumiPagesRequests(
    private val api: BangumiApi,
    private val hub: DesktopOriginalBangumiHubRepository,
    private val csrf: () -> String?,
    private val sessionCookie: () -> String?,
    private val owned: () -> Boolean,
    private val actionOwned: () -> Boolean,
) {
    suspend fun getTimeline(type: Int = 1, before: Int = 3, after: Int = 7) = ownedBangumiRequest(Dispatchers.IO, owned) { hub.getTimeline(type,before,after) }
    suspend fun getMyFollowBangumi(type: Int = 1, followStatus: Int? = null, page: Int = 1, pageSize: Int = 30, vmid: Long? = null) = ownedBangumiRequest(Dispatchers.IO, owned) { hub.getMyFollowBangumi(type,followStatus,page,pageSize,vmid) }
    suspend fun searchBangumi(keyword: String, seasonType: Int = BangumiType.ANIME.value, page: Int = 1, pageSize: Int = 20) = ownedBangumiRequest(Dispatchers.IO, owned) { hub.searchBangumi(keyword,seasonType,page,pageSize) }
    suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean = false) = ownedBangumiRequest(Dispatchers.IO, actionOwned) { hub.unfollowBangumi(seasonId,isCourse) }
    suspend fun updateBangumiFollowStatus(seasonId: Long,status: Int) = ownedBangumiRequest(Dispatchers.IO, actionOwned) { hub.updateBangumiFollowStatus(seasonId,status) }
'''
path=OUT/'com/android/purebilibili/data/repository/DesktopOriginalBangumiPagesRequests.kt';path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(header+'\n\n'.join(pieces)+'\n}\n',encoding='utf-8')
ROWS.append(dict(originalPath=rel,originalSha256LF=PINS[rel],output=str(path.relative_to(OUT)),
 completeOriginalFileBody=False,completeSelectedMethodBodies=True,methods=method_rows,
 existingHubDelegation=['getTimeline','getMyFollowBangumi','searchBangumi','unfollowBangumi','updateBangumiFollowStatus'],
 noNewNetworkOwner=True))
# Existing media producer already emits the follow-status choices. Only the
# missing complete preload constants/DTO/functions are prospective outputs.
rel,s=original('feature/bangumi/policy/BangumiFollowStatusPolicy.kt')
head=s[:s.index('internal const val BANGUMI_FOLLOW_STATUS_UNFOLLOW')]
selected=head+media.function(s,'resolveFollowPreloadPageCount',parser)+'\n\n'+media.function(s,'preloadFollowedSeasonsForType',parser)+'\n'
e=Edit(selected)
e.replace('import com.android.purebilibili.data.repository.BangumiRepository','// Root requires the exact existing follow-request delegate.')
e.replace('fetchPage: suspend (type: Int, page: Int, pageSize: Int) -> Result<MyFollowBangumiData> =\n        { requestType, page, ps ->\n            BangumiRepository.getMyFollowBangumi(type = requestType, page = page, pageSize = ps)\n        }','fetchPage: suspend (type: Int, page: Int, pageSize: Int) -> Result<MyFollowBangumiData>',1)
e.write(rel,'DesktopOriginalBangumiFollowPreloadPolicy.kt')
ROWS[-1]['completeOriginalFileBody']=False
ROWS[-1]['completeSelectedMethodBodies']=True
ROWS[-1]['selectedMethods']=['resolveFollowPreloadPageCount','preloadFollowedSeasonsForType']
rel,s=original('feature/bangumi/MyFollowStats.kt');Edit(s).write(rel,'DesktopOriginalMyFollowStats.kt')
rel,s=original('feature/bangumi/policy/BangumiUiPolicy.kt')
selected='package com.android.purebilibili.feature.bangumi\n\n'+s[s.index('internal data class BangumiEpisodePreviewWindow'):]
Edit(selected).write(rel,'DesktopOriginalBangumiEpisodePagePolicy.kt')
ROWS[-1]['completeOriginalFileBody']=False;ROWS[-1]['completeSelectedMethodBodies']=True
rel,s=original('core/ui/skeleton/ContentLoadingSkeletons.kt')
selected=s[:s.index('/**')]+ '@Composable\n'+media.function(s,'PosterDetailSkeleton',parser)+'\n'
Edit(selected).write(rel,'DesktopOriginalBangumiPosterDetailSkeleton.kt')
ROWS[-1]['completeOriginalFileBody']=False;ROWS[-1]['completeSelectedMethodBodies']=True
(options.manifest or OUT/'bangumi-pages-producer-inventory.json').write_text(json.dumps(dict(upstreamCommit=INPUT['upstreamCommit'],emitted=ROWS,
 originalInverseTransformsVerified=True,playerBodyNotYetAdapted=True,wholeProductBuildAccepted=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(generatedFiles=len(ROWS),fullOriginalFiles=sum(r.get('completeOriginalFileBody',False) for r in ROWS),playerAdaptationStillInProgress=True),ensure_ascii=False))
