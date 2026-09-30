from pathlib import Path
import hashlib,importlib.util,json,sys,subprocess
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
tool=HERE/'prepared/desktop/tools/extract-upstream-dynamic-settings.py'
spec=importlib.util.spec_from_file_location('prepared_dynamic_extractor',tool);gen=importlib.util.module_from_spec(spec);spec.loader.exec_module(gen)
files=gen.generate(REPO,HERE/'generated',standalone=True)
write(HERE/'source-inventory.json',json.dumps(gen.inventory(REPO),indent=2)+'\n')
owned=['desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopHomeRecommendationSettings.kt']
baselines=[]
for path in owned:
 raw=safe(REPO/path).read_bytes();target=HERE/'base'/path;safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(raw)
 baselines.append(dict(path=path,baseSha256Bytes=hashlib.sha256(raw).hexdigest()))
 source=raw.decode('utf-8').replace('\r\n','\n')
 if path.endswith('CommunityDynamicScreens.kt'):
  source=source.replace('import com.bilipai.desktop.data.*','import com.bilipai.desktop.data.*\nimport com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences')
  begin=source.index('@Composable\ninternal fun CommunityDynamicFeed(');end=source.index('@Composable\ninternal fun CommunityDynamicDetail',begin)
  replacement='''@Composable
internal fun CommunityDynamicFeed(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    val preferences=checkNotNull(LocalDesktopDynamicTimelinePreferences.current){"Root shared dynamic preferences are not mounted"}
    val blocked by community.blockedUps.mids.collectAsState()
    val epoch by community.accountEpoch.collectAsState()
    val capturedEpoch=epoch
    val transform = remember(blocked) { { rows: List<DynamicItem> -> desktopVisibleDynamicItems(rows, blocked) } }
    var type by remember { mutableStateOf("all") }
    var composing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var published by remember { mutableStateOf(false) }
    val memory=LocalDesktopBrowseMemory.current
    val timeline=remember(memory,mid,capturedEpoch,type,revision) {
        val key=listOf("dynamic-settings-timeline",mid,capturedEpoch,type,revision)
        val create={DesktopDynamicTimelineState(type,fetchPage={requestType,offset,baseline->
            try {DynamicFeedResponse(data=community.dynamicFeed(requestType,offset,baseline).data)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:BiliApiException){DynamicFeedResponse(code=failure.apiCode,message=failure.message.orEmpty())}
        },stillOwned={community.accountEpoch.value==capturedEpoch&&community.account.value?.mid==mid})}
        memory?.screen(key,create)?:create()
    }
    Column {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Original remaining tabs/visibility/order/UP selection are a separate unimplemented slice.
            listOf("all" to "全部", "video" to "视频", "pgc" to "番剧").forEach { (value, label) ->
                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
            }
            Button(onClick = { composing = true }) { DesktopSkinDynamicPublishIcon(composing); Text("发布动态") }
        }
        if (published) Text("动态已提交", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary)
        val originalTitle=com.android.purebilibili.feature.dynamic.allDynamicTabSpecs.firstOrNull{it.id==type}?.title?:"内容"
        DesktopDynamicTimelineFeed(timeline,preferences,navigation.onLogin,transform,
            oldContentDividerLabel=if(type=="all")"以下是之前的动态"else"以下是之前的${originalTitle}") {
            CommunityDynamicCard(it,community,navigation)
        }
    }
    if (composing) CommunityDynamicComposer(community, navigation, onDismiss = { composing = false }, onPublished = { id ->
        composing = false; revision++; published = true
        if (id != null) navigation.onDynamic(id)
    })
}

'''
  source=source[:begin]+replacement+source[end:]
 else:
  before='''            onHomeRefreshCountChange={value->update{discovery.setRefreshCount(value)}},
        )'''
  assert source.count(before)==1
  source=source.replace(before,before+'''
        LocalDesktopDynamicTimelinePreferences.current?.let {preferences->
            DesktopDynamicTimelineSettings(preferences,onFailure)
        }''')
 write(HERE/'prepared'/path,source)
 baselines[-1]['desiredSha256Bytes']=sha(HERE/'prepared'/path)
write(HERE/'target-baselines.json',json.dumps(baselines,indent=2)+'\n')
# Git performs context-only patch generation here; main files are never modified.
patches=[]
for row in baselines:
 r=subprocess.run(['git','diff','--no-index','--no-ext-diff','--src-prefix=a/','--dst-prefix=b/',str(HERE/'base'/row['path']),str(HERE/'prepared'/row['path'])],
  capture_output=True,text=True,encoding='utf-8',errors='replace')
 assert r.returncode in (0,1),r.stderr
 patch=r.stdout
 firstHunk=patch.index('@@ ')
 patch='diff --git a/'+row['path']+' b/'+row['path']+'\n--- a/'+row['path']+'\n+++ b/'+row['path']+'\n'+patch[firstHunk:]
 patches.append(patch)
write(HERE/'consumer.patch',''.join(patches))
print('PREPARED',len(files),'original generated outputs,',len(baselines),'isolated current consumer overrides')
