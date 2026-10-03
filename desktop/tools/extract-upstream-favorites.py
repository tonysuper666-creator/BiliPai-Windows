from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib, importlib.util, json, re, subprocess
HERE=Path(__file__).resolve().parent
REPO=None
BASE='app/src/main/java/com/android/purebilibili/'
OUT=None
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def emit(p,s):
 relative=str(p).replace(chr(92),'/')
 row=records[-1]
 mode='direct' if hashlib.sha256(s.encode()).hexdigest()==row['sha256LfUtf8'] else 'selected'
 row.setdefault('outputs',[]).append(dict(path=relative,sha256LfUtf8=hashlib.sha256(s.encode()).hexdigest(),mode=mode))
 if mode=='direct' and not STANDALONE:return
 p=OUT/p;safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
parser=None
records=[]
def source(rel):
 path=BASE+rel+'.kt';s=read(_desktop_canonical_source(REPO, path))
 path=_desktop_canonical_source(REPO,path).relative_to(REPO).as_posix()
 blob=subprocess.check_output(['git','show','79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40:'+path],cwd=REPO)
 assert hashlib.sha256(blob.replace(b'\r\n',b'\n')).hexdigest()==hashlib.sha256(s.encode()).hexdigest(),path
 records.append(dict(path=path,upstreamCommit='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40',sha256LfUtf8=hashlib.sha256(s.encode()).hexdigest()))
 return s
def output(rel,s,mode):
 s=re.sub(r'(\banimate\(\s*)initial\s*=',r'\1initialValue =',s)
 emit('com/android/purebilibili/'+rel+'.kt',s)
 records[-1].update(output='com/android/purebilibili/'+rel+'.kt',selection=mode,outputSha256=hashlib.sha256(s.encode()).hexdigest())
def rmfun(s,name):
 mask=parser.masked(s);m=re.search(r'(?m)^.*?\bfun '+re.escape(name)+r'\s*\(',mask);assert m,name
 p=mask.index('(',m.start());p=parser.balanced(mask,p);b=mask.index('{',p);end=parser.balanced(mask,b,'{','}');return s[:m.start()]+s[end:]
def decl(s,name):
 mask=parser.masked(s);m=re.search(r'(?m)^(?:(?:private|internal|public|suspend|inline|enum|data)\s+)*(?:fun|class|object|val|const val)\s+(?:\w+\.)?'+re.escape(name)+r'\b',mask);assert m,name
 start=m.start();nexts=list(re.finditer(r'(?m)^(?:@Composable|@OptIn|(?:(?:private|internal|public|suspend|inline|enum|data)\s+)*(?:fun|class|object|val|const val)\b)',mask[m.end():]))
 # Top-level declarations are column 0; annotations directly before a selected function travel with it.
 end=m.end()+nexts[0].start() if nexts else len(s)
 before=s[:start].rstrip();annotation=before[before.rfind('\n')+1:]
 if annotation.startswith('@Composable') or annotation.startswith('@OptIn'):start=before.rfind('\n')+1
 return s[start:end].rstrip()+'\n'
def toast(s):
 while True:
  mask=parser.masked(s);m=re.search(r'android\.widget\.Toast\.makeText\s*\(',mask)
  if not m:return s
  p=mask.index('(',m.start());end=parser.balanced(mask,p)
  args=s[p+1:end-1];am=parser.masked(args);depth=0;commas=[]
  for i,c in enumerate(am):
   depth += (c in '([{')-(c in ')]}')
   if c==',' and depth==0:commas.append(i)
  assert len(commas)>=2,args
  message=args[commas[0]+1:commas[1]].strip()
  tail=re.match(r'\s*\.show\(\)',s[end:]);assert tail,s[end:end+40]
  s=s[:m.start()]+'environment.showFeedback('+message+')'+s[end+tail.end():]
def drop_logs(s):
 while True:
  mask=parser.masked(s);m=re.search(r'(?:(?:com\.android\.purebilibili\.core\.util\.)?Logger|android\.util\.Log)\.[dwei]\s*\(',mask)
  if not m:return s
  start=s.rfind('\n',0,m.start())+1;assert not s[start:m.start()].strip()
  end=parser.balanced(mask,mask.index('(',m.start()));s=s[:start]+s[end:]
def produce():
 # Original models already installed are references; neither parallel DTO nor duplicate declaration.
 for rel in ['data/model/response/PersonalFavoriteModels','data/model/response/FavoriteModels']:
  s=source(rel);records[-1].update(selection='reference: installed original model')
 # Whole original repositories. API and cached identity are supplied by one owned Root port.
 for name in ['FavoriteRepository','PersonalFavoriteRepository','HistoryRepository','LikedVideosRepository']:
  rel='data/repository/'+name;s=source(rel)
  s=s.replace('import com.android.purebilibili.core.network.NetworkModule\n','').replace('import com.android.purebilibili.core.store.TokenManager\n','')
  s=s.replace('object '+name+' {','class DesktopOriginal'+name+'(private val environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment) {')
  s=s.replace('private val api = NetworkModule.api','private val api = environment.api').replace('NetworkModule.api.','environment.api.').replace('NetworkModule.spaceApi.','environment.spaceApi.')
  if name=='LikedVideosRepository':
   s=s.replace('environment.spaceApi.getSpaceCoinArchive','environment.getSpaceCoinArchive').replace('environment.spaceApi.getSpaceLikedArchive','environment.getSpaceLikedArchive').replace('import com.android.purebilibili.core.network.getSpaceCoinArchive\n','').replace('import com.android.purebilibili.core.network.getSpaceLikedArchive\n','')
  s=s.replace('com.android.purebilibili.core.store.TokenManager.csrfCache','environment.csrf()').replace('TokenManager.csrfCache','environment.csrf()').replace('com.android.purebilibili.core.store.TokenManager.midCache','environment.currentMid()').replace('TokenManager.midCache','environment.currentMid()')
  s=drop_logs(s)
  if name=='HistoryRepository':
   # The new original heartbeat guard remains a privacy no-op in its dataRequest scope.
   playbackPrivacy='        val context = com.android.purebilibili.core.network.NetworkModule.appContext\n        if (context != null && com.android.purebilibili.core.network.CoreNetworkRuntime.config.isPrivacyModeEnabled(context)) return@dataRequest'
   assert s.count(playbackPrivacy)==1, 'latest original heartbeat privacy guard'
   s=s.replace(playbackPrivacy,'        if (environment.privacyModeEnabled()) return@dataRequest')
   # HistoryCursorQuery already has the installed history producer.
   s=s.replace(decl(s,'HistoryCursorQuery'),'')
   s=s.replace(decl(s,'resolveHistoryCursorQuery'),'')
   a=s.index('                val context = com.android.purebilibili.core.network.NetworkModule.appContext');mask=parser.masked(s);end=mask.index('{',a);b=parser.balanced(mask,end,'{','}')
   # Original privacy no-op admission is bound to the existing search preferences consumer.
   s=s[:a]+'                if (environment.privacyModeEnabled()) return@withContext Result.success(Unit)'+s[b:]
  if name=='FavoriteRepository':
   # FavoriteRequestException and request resolver already have a sole installed producer.
   a=s.index('class FavoriteRequestException');b=s.index('class DesktopOriginalFavoriteRepository')
   s=s[:a]+s[b:]
   s=s.replace('favoriteApiFailure(','desktopFavoriteApiFailure(').replace('favoriteHttpFailure(','desktopFavoriteHttpFailure(').replace('NetworkModule.dynamicApi.','environment.dynamicApi.')
   originals=source(rel)
   a=originals.index('private fun favoriteApiFailure');b=originals.index('data class FavoriteResourceRequestParams')
   funcs=originals[a:b].replace('favoriteApiFailure','desktopFavoriteApiFailure').replace('favoriteHttpFailure','desktopFavoriteHttpFailure')+decl(originals,'FavoriteResourceRequestParams')+decl(originals,'resolveFavoriteResourceRequestParams')
   s=s[:s.index('class DesktopOriginalFavoriteRepository')]+funcs+s[s.index('class DesktopOriginalFavoriteRepository'):]
  output('data/repository/DesktopOriginal'+name,s,'entire original repository; shared API/identity constructor only, original protocol/parser/management bodies retained')
 # Original selected action + PGC repository methods, exact request body/fields.
 s=source('data/repository/ActionRepository');members=[]
 for name in ['createFavFolder','favoriteVideo','getDefaultFolderId','toggleWatchLater',
     'normalizeRelationTagIds','normalizeRelationTags','chunkFollowGroupTargetMids',
     'isFollowGroupRetryableError','addUsersToRelationTagsWithRetry','followUser',
     'getFollowGroupTags','getUserFollowGroupIds','getFollowGroupMemberMids',
     'getAllFollowGroupUsers','getFollowGroupUsers','overwriteFollowGroupIds']:
  a,b=parser.fun_span(s,name);members.append(s[a:b])
 members.insert(0, 'private companion object {\n'+'\n'.join(l for l in s.splitlines() if l.strip().startswith('private const val ') and any(k in l for k in ['FOLLOW_GROUP_', 'SPECIAL_FOLLOW_TAG_ID', 'ALL_FOLLOW_TAG_ID']))+'\n}')
 imports='\n'.join(l for l in s.splitlines() if l.startswith('import ') and 'NetworkModule'not in l and 'TokenManager'not in l)
 body='package com.android.purebilibili.data.repository\n'+imports+'\nclass DesktopOriginalFavoriteActions(private val environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment) {\n'+ '\n'.join(members)+'\n}\n'
 body=body.replace('NetworkModule.api.','environment.api.').replace('TokenManager.csrfCache','environment.csrf()').replace('WatchLaterRefreshBus.notifyChanged()','environment.watchLaterChanged()').replace('import com.android.purebilibili.core.refresh.WatchLaterRefreshBus\n','').replace(' {\n    suspend fun createFavFolder',' {\n    private val api=environment.api\n    suspend fun createFavFolder')
 body=body.replace('val response = api.','val response = environment.api.').replace('                    api.','                    environment.api.')
 body=body.replace('TokenManager.midCache','environment.currentMid()').replace('val response = api.','val response = environment.api.')
 body=body.replace('_followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))','environment.confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))')
 body=drop_logs(body);output('data/repository/DesktopOriginalFavoriteActions',body,'selected complete original create-folder/quick-save/default-folder/watchlater members; latter is compile-only auxiliary history dependency')
 s=source('data/repository/BangumiRepository');members=[]
 for name in ['getMyFollowBangumi','unfollowBangumi','updateBangumiFollowStatus']:
  a,b=parser.fun_span(s,name);members.append(s[a:b])
 body='package com.android.purebilibili.data.repository\n'+ '\n'.join(l for l in s.splitlines() if l.startswith('import ') and 'NetworkModule'not in l and 'TokenManager'not in l and 'WbiKeyManager'not in l)+'\nclass DesktopOriginalFavoritePgc(private val environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment) {\nprivate val api=environment.bangumiApi\n'+ '\n'.join(members)+'\n}\n'
 body=body.replace('TokenManager.csrfCache','environment.csrf()').replace('TokenManager.midCache','environment.currentMid()').replace('NetworkModule.api.','environment.api.');output('data/repository/DesktopOriginalFavoritePgc',body,'complete original followed-PGC request bodies only')
 # Full original list state/VM classes, including unmounted history/liked compile dependencies.
 s=source('feature/list/ListViewModel')
 s='\n'.join(l for l in s.splitlines() if not l.startswith('import android.') and not l.startswith('import androidx.lifecycle.') and not any(x in l for x in ['import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.coroutines.AppScope','import com.android.purebilibili.core.refresh.HistoryRefreshBus']))+'\n'
 a=s.index('class LikedVideosViewModelFactory');b=s.index('// --- 历史记录',a);s=s[:a]+s[b:]
 s=s.replace('application: Application','environment: DesktopFavoriteEnvironment').replace('AndroidViewModel(application)','DesktopFavoriteScopedOwner(environment)').replace('BaseListViewModel(application,','BaseListViewModel(environment,').replace('    application,','    environment,')
 s=s.replace('NetworkModule.api.','environment.api.').replace('com.android.purebilibili.data.repository.FavoriteRepository.','environment.favorite.').replace('com.android.purebilibili.data.repository.ActionRepository.','environment.actions.').replace('com.android.purebilibili.data.repository.HistoryRepository.','environment.history.').replace('com.android.purebilibili.data.repository.LikedVideosRepository.Page','com.android.purebilibili.data.repository.DesktopOriginalLikedVideosRepository.Page').replace('com.android.purebilibili.data.repository.LikedVideosRepository.','environment.liked.')
 s=s.replace('com.android.purebilibili.core.store.TokenManager.csrfCache','environment.csrf()').replace('AppScope.ioScope','viewModelScope').replace('HistoryRefreshBus.changes','environment.historyChanges')
 s=s.replace('com.android.purebilibili.data.repository.LikedVideosRepository','environment.liked').replace('com.android.purebilibili.data.repository.FavoriteRepository','environment.favorite')
 s=s.replace('Build.VERSION.SDK_INT','0').replace('Build.MANUFACTURER.orEmpty()','"Windows"')
 # Original history delete session chooses its DIRECT_DELETE branch when Windows GL is unavailable.
 before='isThanosEffectSupported(getApplication<Application>())';assert s.count(before)==1;s=s.replace(before,'false',1)
 before='import com.android.purebilibili.core.ui.animation.gl.isThanosEffectSupported\n';assert s.count(before)==1;s=s.replace(before,'',1)
 a=s.index('    private val progressManager by lazy');b=s.index('    // 游标分页',a);s=s[:a]+'    private val progressManager get() = environment\n\n'+s[b:]
 s=toast(s);s=drop_logs(s);output('feature/list/DesktopOriginalListViewModels',s,'full original Base/Favorite/History/Liked algorithms; lifecycle/environment aliases, Android factory omitted; history/liked not mounted by favorites host')
 s=source('feature/space/SeasonSeriesDetailViewModel');s=s.replace('import android.app.Application\n','').replace('import androidx.lifecycle.viewModelScope\n','').replace('import com.android.purebilibili.core.network.NetworkModule\n','').replace('import com.android.purebilibili.data.repository.FavoriteRepository\n','')
 s=s.replace('application: Application','environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment').replace('BaseListViewModel(application,','BaseListViewModel(environment,').replace('NetworkModule.spaceApi','environment.spaceApi').replace('FavoriteRepository.','environment.favorite.')
 output('feature/space/SeasonSeriesDetailViewModel',s,'entire original collection/favorite/favorite-season detail VM; same environment/scope')
 s=source('feature/list/FavoriteCategoryScreen');s=s.replace('import android.app.Application\n','').replace('import androidx.lifecycle.AndroidViewModel\n','').replace('import androidx.lifecycle.viewModelScope\n','').replace('import androidx.lifecycle.viewmodel.compose.viewModel\n','').replace('import com.android.purebilibili.data.repository.BangumiRepository\n','').replace('import com.android.purebilibili.data.repository.PersonalFavoriteRepository\n','')
 s=s.replace('application: Application','environment: DesktopFavoriteEnvironment').replace('AndroidViewModel(application)','DesktopFavoriteScopedOwner(environment)').replace('BangumiRepository.','environment.pgc.').replace('PersonalFavoriteRepository.','environment.personal.')
 s=s.replace('viewModel: FavoriteCategoryViewModel = viewModel(),','viewModel: FavoriteCategoryViewModel,')
 s=s.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle').replace('initialValue =','initial =')
 output('feature/list/FavoriteCategoryScreen',s,'entire original state/VM/renderer, only required constructor/scope/API bindings; no renderer/category removed')
 # Whole original generic list renderer, every branch and dialog retained. Android settings/queue/share aliases only.
 s=source('feature/list/CommonListScreen')
 s=s.replace('import androidx.compose.ui.platform.LocalContext // [New]','import coil3.compose.LocalPlatformContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopFavoriteBindings').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.LocalDesktopFavoriteViewport as LocalConfiguration')
 s=s.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle').replace('import com.android.purebilibili.core.store.SettingsManager // [New]','')
 s=s.replace('    val context = LocalContext.current','    val context = LocalContext.current\n    val platform = LocalDesktopFavoriteBindings.current')
 s=s.replace('SettingsManager.getShowOnlineCount(context)','platform.showOnlineCount').replace('SettingsManager.getHomeSettings(context)','platform.homeSettings').replace('SettingsManager.getAppNavigationSettings(context)','platform.navigationSettings').replace('com.android.purebilibili.core.store.HomeSettings()','platform.initialHomeSettings')
 s=s.replace('com.android.purebilibili.core.store.AppNavigationSettings()','platform.initialNavigationSettings')
 s=s.replace('initialValue =','initial =').replace('androidx.activity.compose.BackHandler','com.android.purebilibili.core.ui.LocalNavigationBackHandler')
 s=s.replace('import com.android.purebilibili.core.ui.blur.hazeSourceCompat','import dev.chrisbanes.haze.hazeSource').replace('import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n','')
 s=s.replace('shouldAllowRenderEffectBackedHazeEffect(0)','platform.hazeEffectSupported').replace('com.android.purebilibili.core.ui.blur.rememberRecoverableHazeState()','remember { HazeState() }').replace('.hazeSourceCompat(','.hazeSource(')
 s=s.replace('        SettingsManager\n            .getHomeFeedCardStyle(context)','        platform.homeFeedCardStyle')
 s=s.replace('FavoriteCategoryRoute(\n','FavoriteCategoryRoute(\n                        viewModel = platform.categoryViewModel,\n')
 s=s.replace('com.android.purebilibili.core.util.ShareUtils.shareText(', 'platform.shareText(').replace('platform.shareText(context,','platform.shareText(')
 s=s.replace('android.os.Build.VERSION.SDK_INT','0')
 s=s.replace('shouldAllowRenderEffectBackedHazeEffect(0)','platform.hazeEffectSupported')
 s=s.replace('import com.android.purebilibili.feature.video.player.PlaylistManager\n','').replace('import com.android.purebilibili.feature.video.player.PlaylistSession','import com.bilipai.desktop.ui.DesktopFavoriteQueueToken as PlaylistSession')
 a=s.index('                    PlaylistManager.setExternalPlaylist(');b=s.index('\n                }',a)
 s=s[:a]+'                    platform.openQueue(playlist.playlistItems, playlist.startIndex, playAllAudio)'+s[b:]
 s=s.replace('PlaylistManager.addAllToPlaylistIfCurrent(','platform.appendQueueIfCurrent(').replace('com.android.purebilibili.core.util.ShareUtils.shareText(context,','platform.shareText(')
 s=s.replace('import com.android.purebilibili.core.ui.animation.DissolveAnimationPreset','import com.bilipai.desktop.ui.DissolveAnimationPreset').replace('import com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard','import com.bilipai.desktop.ui.DesktopReplyDissolvableContainer as MaybeDissolvableVideoCard').replace('import com.android.purebilibili.core.ui.animation.jiggleOnDissolve','import com.bilipai.desktop.ui.jiggleOnDissolve')
 output('feature/list/CommonListScreen',s,'entire generic original UI, including all favorite catalog/detail/category branches/dialogs. Root shared queue/settings/share bindings replace platform singletons only')
 # New original full files with no producer in actual Main15.
 direct=['feature/list/CommonListAppearancePolicy','feature/list/CommonListBackToTopPolicy','feature/list/CommonListHeaderLayoutPolicy','feature/list/CommonListMotionSpec','feature/list/CommonListPaginationPolicy','feature/list/CommonListSearchPolicy','feature/list/CommonListVideoNavigationPolicy','feature/list/FavoriteCategoryPolicy','feature/list/FavoriteFolderCardList','feature/list/FavoritePersonalCard','feature/list/FavoritePersonalCardSkeleton','feature/list/HistoryFilterPolicy','feature/list/HistoryFilterTabChromePolicy','feature/list/HistoryPaginationPolicy','feature/list/HistoryPersonalCard','feature/list/ListScopedSearchActiveBar','feature/list/ListScopedSearchPolicy','feature/personal/PersonalMediaCard','feature/personal/PersonalListLayoutPolicy','feature/personal/PersonalCardVideoTransition','feature/article/ArticleSharedTransitionPolicy','core/ui/components/VideoListLayoutControl','core/ui/components/AppLiquidAwareSearchField','core/ui/components/AppLiquidGlassBackToTopButton','core/ui/adaptive/DeviceUiProfileAdapter','core/ui/BottomBarScrollHidePolicy','core/util/ScrollToTopPolicy','feature/home/HomeFeedPinchZoomPolicy','navigation/PagerSelectionMotion']
 for rel in direct:
  if not (REPO/(BASE+rel+'.kt')).exists():continue
  s=source(rel)
  if rel=='core/ui/adaptive/DeviceUiProfileAdapter':
   parts=[decl(s,n) for n in ['DeviceUiProfile','resolveDeviceUiProfile','toAdaptiveFoldPosture']]
   emit('com/android/purebilibili/core/ui/adaptive/DesktopFavoriteDeviceProfile.kt','package com.android.purebilibili.core.ui.adaptive\nimport com.android.purebilibili.core.util.*\n'+ '\n'.join(parts))
   continue
  s=s.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.LocalDesktopFavoriteViewport as LocalConfiguration')
  s=s.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle')
  s=s.replace('initialValue =','initial =')
  s=s.replace('import com.android.purebilibili.core.store.HomeSettings','import com.bilipai.desktop.ui.DesktopFavoriteHomeAppearance as HomeSettings')
  s=s.replace('import com.android.purebilibili.core.store.SettingsManager','import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes').replace('SettingsManager.BottomBarVisibilityMode','DesktopFavoriteNavigationTypes.BottomBarVisibilityMode')
  if rel=='core/ui/components/AppLiquidGlassBackToTopButton':
   s=s.replace('import com.android.purebilibili.core.store.BackToTopSettingsStore','import com.bilipai.desktop.ui.desktopBackToTopBindings')
   s=s.replace('    val context = LocalContext.current','    val context = LocalContext.current\n    val platform = desktopBackToTopBindings()').replace('val configuration = LocalConfiguration.current','val configuration = platform.viewport')
   s=s.replace('BackToTopSettingsStore.getCustomOffsetDp(context)','platform.offset').replace('BackToTopSettingsStore.getCachedOffsetDp()','platform.initialOffset').replace('BackToTopSettingsStore.setCustomOffsetDp(context,','platform.setOffset(').replace('BackToTopSettingsStore.updateCachedOffset(','platform.updateOffset(')
  output(rel,s,'full original leaf/pure file; platform context/viewport import only')
 # FavoriteCollectionPolicy installs only the not-yet-emitted declarations; risk policy is Main's sole original.
 s=source('feature/list/FavoriteCollectionPolicy');s=rmfun(s,'isFavoriteRiskControlError');output('feature/list/FavoriteCollectionPolicy',s,'full original policy minus already installed sole risk-control function')
 s=source('core/store/SettingsManager');parts=[decl(s,n) for n in ['CommonListHeaderCollapseMode','HomeHeaderBlurMode','resolveHomeHeaderBlurEnabled','resolveHomeHeaderBlurModePreference']]
 emit('com/android/purebilibili/core/store/DesktopFavoriteChromePolicies.kt','package com.android.purebilibili.core.store\n'+ '\n'.join(parts))
 a=s.index('    enum class BottomBarVisibilityMode');b=parser.balanced(parser.masked(s),s.index('{',a),'{','}')
 emit('com/bilipai/desktop/ui/DesktopFavoriteNavigationTypes.kt','package com.bilipai.desktop.ui\nobject DesktopFavoriteNavigationTypes {\n'+s[a:b]+'\n}\n')
 # Copy original selected constructor arguments + legacy header-blur decision, not rewritten defaults.
 a=s.index('        return HomeSettings(');p=s.index('(',a);end=parser.balanced(parser.masked(s),p)
 args=s[p+1:end-1];mask=parser.masked(args);depth=0;cuts=[0]
 for i,c in enumerate(mask):
  depth+=(c in '([{')-(c in ')]}')
  if c==',' and depth==0:cuts.append(i+1)
 cuts.append(len(args));selected=[]
 names={'androidNativeLiquidGlassEnabled','cardAnimationEnabled','cardTransitionEnabled','pinchToChangeGridColumnsEnabled','commonListHeaderCollapseMode','isBottomBarSearchEnabled','listScopedSearchEnabled','homeDurationStyle','headerBlurMode','isBottomBarBlurEnabled'}
 for left,right in zip(cuts,cuts[1:]):
  arg=args[left:right].strip().rstrip(',');name=arg.split('=',1)[0].strip()
  if name in names:selected.append(arg)
 assert len(selected)==len(names)
 keys={name:(kind,key) for name,kind,key in re.findall(r'(?:private )?val (KEY_\w+)\s*=\s*(boolean|int|float)PreferencesKey\("([^"]+)"\)',s)}
 a=s.index('        val headerBlurMode = resolveHomeHeaderBlurModePreference(');b=parser.balanced(parser.masked(s),s.index('(',a))
 body=s[a:b]+'\n        return DesktopFavoriteHomeAppearance(\n'+',\n'.join(selected)+'\n        )\n'
 for key in set(re.findall(r'KEY_\w+',body)):
  kind,name=keys[key];body=body.replace(key,'favorite'+kind.title()+'Key("'+name+'")')
 emit('com/bilipai/desktop/ui/DesktopOriginalFavoriteAppearance.kt','package com.bilipai.desktop.ui\nimport com.android.purebilibili.core.store.*\nimport com.bilipai.desktop.plugins.DesktopPreferenceSnapshot\ninternal fun decodeDesktopFavoriteHomeAppearance(preferences:DesktopPreferenceSnapshot):DesktopFavoriteHomeAppearance {\n'+body+'}\n')
 s=source('core/store/FavoriteInteractionSettingsStore')
 body=s[s.index('const val DEFAULT_FAVORITE_QUICK_SAVE_DEFAULT_FOLDER'):]
 body=body.replace('object FavoriteInteractionSettingsStore {','class DesktopFavoriteInteractionPreferences(private val store:com.bilipai.desktop.plugins.DesktopPluginStore) {\n    init { store.requireObjectNamespace("settings") }')
 body=body.replace('booleanPreferencesKey(', 'favoriteBooleanKey(').replace('context: Context','').replace('context.settingsDataStore.data','store.snapshot("settings")')
 body=body.replace('fun getQuickSaveDefaultFolder():','fun getQuickSaveDefaultFolder():')
 body=body.replace('suspend fun setQuickSaveDefaultFolder(, enabled: Boolean)', 'suspend fun setQuickSaveDefaultFolder(enabled: Boolean)')
 body=body.replace('context.settingsDataStore.edit { preferences ->\n            preferences[quickSaveDefaultFolderKey] = enabled\n        }','kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {\n            store.update("settings", mapOf("favorite_quick_save_default_folder" to kotlinx.serialization.json.JsonPrimitive(enabled)))\n        }')
 emit('com/bilipai/desktop/ui/DesktopOriginalFavoriteInteractionPreferences.kt','package com.bilipai.desktop.ui\nimport kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.map\n'+body)
 s=source('core/ui/CompositionLocals');parts=[decl(s,n) for n in ['LocalBottomBarContentPadding','LocalBottomBarVisible','LocalSetBottomBarVisible']]
 emit('com/android/purebilibili/core/ui/DesktopFavoriteBottomBarLocals.kt','package com.android.purebilibili.core.ui\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.unit.*\n'+ '\n'.join(parts))
 s=source('core/util/WindowSizeUtils');parts=[decl(s,n) for n in ['ResponsiveSpacing','rememberResponsiveSpacing','resolveSingleColumnFeedMaxWidth']]
 emit('com/android/purebilibili/core/util/DesktopFavoriteWindowHelpers.kt','package com.android.purebilibili.core.util\n'+ '\n'.join(l for l in s.splitlines() if l.startswith('import androidx.compose.') and 'LocalContext'not in l and 'LocalConfiguration'not in l)+'\n'+ '\n'.join(parts))
 s=source('core/ui/BackToTopPreference');s=s.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.desktopBackToTopBindings').replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle').replace('import com.android.purebilibili.core.store.BackToTopSettingsStore\n','').replace('import com.android.purebilibili.core.store.DEFAULT_BACK_TO_TOP_BUTTON_ENABLED\n','')
 s=s.replace('LocalContext.current','desktopBackToTopBindings()').replace('BackToTopSettingsStore.isEnabled(context)','context.enabled').replace('DEFAULT_BACK_TO_TOP_BUTTON_ENABLED','context.initialEnabled').replace('initialValue =','initial =');output('core/ui/BackToTopPreference',s,'whole original preference consumer; same global backing read port')
 # Sole installed original manager/preset/container remain the authority. Add only the
 # original modifier currently not emitted by that producer, in its existing namespace.
 s=source('core/ui/animation/ParticleDissolveEffect');part=decl(s,'jiggleOnDissolve')
 emit('com/bilipai/desktop/ui/DesktopOriginalFavoriteJiggle.kt','package com.bilipai.desktop.ui\nimport androidx.compose.animation.core.*\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.graphicsLayer\n'+part)
 source('core/network/ApiClient') # Reference original interaction builder in installed API producer; no duplicate output.
 s=source('feature/space/SpaceLoadPolicy');parts=[decl(s,n) for n in ['mapSeasonArchiveToVideoItem','mapSeriesArchiveToVideoItem']]
 emit('com/android/purebilibili/feature/space/DesktopFavoriteArchiveMappings.kt','package com.android.purebilibili.feature.space\nimport com.android.purebilibili.data.model.response.*\n'+ '\n'.join(parts))
 s=source('feature/home/components/HomeHeader');parts=[decl(s,n).replace('com.android.purebilibili.core.store.SettingsManager.TopTabLabelMode.TEXT_ONLY','2') for n in ['resolveHomeTopSearchPillHeight','resolveHomeTopSearchRowHorizontalPadding','resolveHomeTopTabRowHeight']]
 emit('com/android/purebilibili/feature/home/components/DesktopFavoriteHeaderMetrics.kt','package com.android.purebilibili.feature.home.components\nimport androidx.compose.ui.unit.*\nimport com.android.purebilibili.core.ui.*\n'+ '\n'.join(parts))
 s=source('feature/home/HomeScreen');parts=[decl(s,n) for n in ['LocalHomeScrollOffset','LocalHomeFeedScrollInProgress']]
 parts=[p[:p.find('@SuppressLint')] if '@SuppressLint' in p else p for p in parts]
 emit('com/android/purebilibili/feature/home/DesktopFavoriteScrollLocals.kt','package com.android.purebilibili.feature.home\nimport androidx.compose.runtime.*\n'+ '\n'.join(parts))
 s=source('core/util/ModifierExt');parts=[decl(s,'VideoGridItemSkeleton')]
 emit('com/android/purebilibili/core/util/DesktopFavoriteGridSkeleton.kt','package com.android.purebilibili.core.util\n'+ '\n'.join(l for l in s.splitlines() if l.startswith('import ') and not l.startswith('import android.') and 'LocalView'not in l)+'\n'+ '\n'.join(parts))
 s=source('core/ui/skeleton/ContentLoadingSkeletons');parts=[decl(s,'ContentVideoGridItemSkeleton')]
 emit('com/android/purebilibili/core/ui/skeleton/DesktopFavoriteContentGridSkeleton.kt','package com.android.purebilibili.core.ui.skeleton\n'+ '\n'.join(l for l in s.splitlines() if l.startswith('import ') and not l.startswith('import android.'))+'\n'+ '\n'.join(parts))
 for rel in ['feature/home/HomeScrollOffsetPolicy','feature/home/components/TopTabStylePolicy','core/ui/blur/FloatingChromeBackdrop']:
  if not (REPO/(BASE+rel+'.kt')).exists():continue
  s=source(rel);output(rel,s,'whole original leaf/pure file')
 s=source('feature/home/components/HomeInteractionMotionBudgetPolicy');output('feature/home/components/HomeInteractionMotionBudgetPolicy',s,'whole original pure file')
 s=source('feature/home/components/TopBar');names=['normalizeTopTabLabelMode','resolveHomeTopDockShellHeight','resolveIosTopTabActionButtonSize','resolveIosTopTabActionButtonCorner','resolveIosTopTabActionIconSize','resolveMd3TopTabActionButtonCorner','resolveMd3TopTabActionButtonSize','resolveMd3TopTabActionIconSize']
 parts=[decl(s,n) for n in names]
 emit('com/android/purebilibili/feature/home/components/DesktopFavoriteTopTabMetrics.kt','package com.android.purebilibili.feature.home.components\nimport androidx.compose.ui.unit.*\nimport com.android.purebilibili.core.ui.*\n'+ '\n'.join(parts))
 s=source('navigation/AppTopLevelNavigationPolicy');part=decl(s,'resolveBottomPagerNavigationDurationMillis')
 emit('com/android/purebilibili/navigation/DesktopFavoritePagerNavigation.kt','package com.android.purebilibili.navigation\n'+part)

def generate(repo:Path,output:Path,standalone=False):
 global REPO,OUT,parser,records,STANDALONE
 REPO=repo.resolve();OUT=output.resolve();STANDALONE=standalone;records=[]
 spec=importlib.util.spec_from_file_location('source_parser',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 produce()
 canonical={}
 for row in records:
  entry=canonical.setdefault(row['path'],dict(path=row['path'],upstreamCommit=row['upstreamCommit'],sha256LfUtf8=row['sha256LfUtf8'],outputs=[],selections=[]))
  if row.get('selection') and row['selection'] not in entry['selections']:entry['selections'].append(row['selection'])
  for emitted in row.get('outputs',[]):
   if emitted not in entry['outputs']:entry['outputs'].append(emitted)
 for row in canonical.values():
  row['mode']='selected' if any(x['mode']=='selected' for x in row['outputs']) else 'direct' if row['outputs'] else 'reference'
 safe(OUT.parent/'source-inventory.json').write_text(json.dumps(list(canonical.values()),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 return list(canonical.values())
if __name__=='__main__':
 import argparse
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--standalone',action='store_true')
 args=ap.parse_args();generate(args.repo,args.output,args.standalone)
