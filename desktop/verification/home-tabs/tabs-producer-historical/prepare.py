from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(text,encoding='utf-8',newline='\n')
tool=HERE/'prepared/desktop/tools/extract-upstream-dynamic-tabs.py'
sp=importlib.util.spec_from_file_location('tabs_generator',tool);g=importlib.util.module_from_spec(sp);sp.loader.exec_module(g)
g.generate(REPO,HERE/'generated');write(HERE/'source-inventory.json',json.dumps(g.inventory(REPO),indent=2)+'\n')
targets=['desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopCommunityRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopDynamicTimelineSettings.kt']
baselines=[];patches=[]
for path in targets:
 raw=safe(REPO/path).read_bytes();base=HERE/'base'/path;safe(base.parent).mkdir(parents=True,exist_ok=True);safe(base).write_bytes(raw)
 s=raw.decode('utf-8').replace('\r\n','\n')
 if path.endswith('CommunityDynamicScreens.kt'):
  s=s.replace('import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences','import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences\nimport com.bilipai.desktop.settings.DesktopDynamicTabsPreferences')
  begin=s.index('@Composable\ninternal fun CommunityDynamicFeed(');end=s.index('@Composable\ninternal fun CommunityDynamicDetail',begin)
  replacement='''@Composable
internal fun CommunityDynamicFeed(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    val preferences=checkNotNull(LocalDesktopDynamicTimelinePreferences.current){"Root shared dynamic preferences are not mounted"}
    val blocked by community.blockedUps.mids.collectAsState()
    val epoch by community.accountEpoch.collectAsState()
    val capturedEpoch=epoch
    val scope=rememberCoroutineScope()
    val tabsPreferences=remember(preferences){DesktopDynamicTabsPreferences(preferences.context)}
    val users=remember(mid,capturedEpoch,tabsPreferences) {
        DesktopDynamicUsersState(scope,tabsPreferences,mid,
            followingPage={community.followings(mid,it).data},
            liveRooms={community.dynamicFollowedLiveUsers()},unreadUsers={community.dynamicUnreadUsers()},
            requestPage={community.dynamicSelectedUserPage(it)},
            stillOwned={community.accountEpoch.value==capturedEpoch&&community.account.value?.mid==mid},selfFace=community.account.value?.avatar.orEmpty())
    }
    DisposableEffect(users){onDispose{users.close()}}
    val transform=remember(blocked){{rows:List<DynamicItem>->desktopVisibleDynamicItems(rows,blocked)}}
    var composing by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    var published by remember {mutableStateOf(false)}
    val memory=LocalDesktopBrowseMemory.current
    val timelines=remember(memory,mid,capturedEpoch,revision){mutableMapOf<String,DesktopDynamicTimelineState>()}
    fun timeline(type:String)=timelines.getOrPut(type) {
        val create={DesktopDynamicTimelineState(type,fetchPage={requestType,offset,baseline->
            try{DynamicFeedResponse(data=community.dynamicFeed(requestType,offset,baseline).data)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:BiliApiException){DynamicFeedResponse(code=failure.apiCode,message=failure.message.orEmpty())}
        },stillOwned={community.accountEpoch.value==capturedEpoch&&community.account.value?.mid==mid})}
        memory?.screen(listOf("dynamic-settings-timeline",mid,capturedEpoch,type,revision),create)?:create()
    }
    Column {
        if(published)com.android.purebilibili.core.ui.components.AppText("动态已提交",Modifier.padding(horizontal=20.dp))
        DesktopDynamicTabsHost(users,preferences,navigation.onUser,navigation.onLogin,transform,::timeline,
            trailing={Button(onClick={composing=true}){DesktopSkinDynamicPublishIcon(composing);Text("发布动态")}}) {
            CommunityDynamicCard(it,community,navigation)
        }
    }
    if(composing)CommunityDynamicComposer(community,navigation,onDismiss={composing=false},onPublished={id->
        composing=false;revision++;published=true;if(id!=null)navigation.onDynamic(id)
    })
}

'''
  # Keep the real project's navigation property, not a parallel callback model.
  ui=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityUiSupport.kt').read_text(encoding='utf-8')
  models=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityScreens.kt').read_text(encoding='utf-8')
  if 'val onSpace:' in ui+models:replacement=replacement.replace('navigation.onUser','navigation.onSpace')
  s=s[:begin]+replacement+s[end:]
 elif path.endswith('DesktopCommunityRepository.kt'):
  marker='    suspend fun followings(mid: Long, page: Int = 1)'
  index=s.index(marker)
  methods='''    /** Original UP space params are supplied by its original UID-pagination repository. */
    internal suspend fun dynamicSelectedUserPage(params:Map<String,String>):DynamicFeedResponse {
        val expectedEpoch=repository.sessionEpoch;val expectedMid=repository.account.value?.mid
        fun owns()=repository.sessionEpoch==expectedEpoch&&repository.account.value?.mid==expectedMid
        val result=read(accountOnly=true,validate={require(params["host_mid"]?.toLongOrNull()?.let{it>0}==true)}) {
            if(!owns())throw kotlinx.coroutines.CancellationException("Dynamic source retired")
            dynamic.getUserDynamicFeed(params)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(!owns())throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return result
    }
    internal suspend fun dynamicFollowedLiveUsers():List<LiveRoom> {
        val expectedEpoch=repository.sessionEpoch;val expectedMid=repository.account.value?.mid
        val result=read(accountOnly=true) {
            val response=api.getFollowedLive(page=1,pageSize=50)
            check(response.code,response.message)
            com.android.purebilibili.data.repository.desktopOriginalDynamicFollowedLiveUsers(response)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(repository.sessionEpoch!=expectedEpoch||repository.account.value?.mid!=expectedMid)throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return result
    }
    internal suspend fun dynamicUnreadUsers():UplistData? {
        val expectedEpoch=repository.sessionEpoch;val expectedMid=repository.account.value?.mid
        val result=read(accountOnly=true) {
            repository.requireCsrf()
            val response=dynamic.getDynamicUplist()
            check(response.code,response.message)
            response.data
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(repository.sessionEpoch!=expectedEpoch||repository.account.value?.mid!=expectedMid)throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return result
    }

'''
  if 'import kotlinx.coroutines.ensureActive' not in s:s=s.replace('package com.bilipai.desktop.data','package com.bilipai.desktop.data\n\nimport kotlinx.coroutines.ensureActive')
  s=s[:s.index(marker)]+methods+s[s.index(marker):]
 else:
  marker='''            layout,{value->write{preferences.setLayoutMode(value)}})'''
  assert s.count(marker)==1
  s=s.replace(marker,marker+'\n        val tabs=remember(preferences){DesktopDynamicTabsPreferences(preferences.context)}\n        DesktopDynamicTabsSettings(tabs,onFailure)')
 prepared=HERE/'prepared'/path;write(prepared,s)
 baselines.append(dict(path=path,baseSha256Bytes=hashlib.sha256(raw).hexdigest(),desiredSha256Bytes=sha(prepared)))
 r=subprocess.run(['git','diff','--no-index','--no-ext-diff',str(base),str(prepared)],capture_output=True,text=True,encoding='utf-8',errors='replace');assert r.returncode in (0,1),r.stderr
 patch=r.stdout;first=patch.index('@@ ')
 patches.append('diff --git a/'+path+' b/'+path+'\n--- a/'+path+'\n+++ b/'+path+'\n'+patch[first:])
write(HERE/'consumer.patch',''.join(patches));write(HERE/'target-baselines.json',json.dumps(baselines,indent=2)+'\n')
print('Prepared original tabs and UP slice with 3 current consumer seams')
