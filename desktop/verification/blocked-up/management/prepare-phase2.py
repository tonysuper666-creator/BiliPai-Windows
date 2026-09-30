from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;STAGE1=HERE.parent;ROOT=STAGE1.parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def original(path):return (ROOT/path).read_text(encoding='utf-8').replace('\r\n','\n')
def own(path,text,base=None):
    target=HERE/'prepared'/path;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding='utf-8',newline='\n')
    rows.append(dict(path=path,prepared=target.relative_to(HERE).as_posix(),baseSha256Bytes=sha(base or ROOT/path) if (base or ROOT/path).exists() else None,sha256Bytes=sha(target)))
def change(text,old,new):
    assert text.count(old)==1,old[:90];return text.replace(old,new,1)
rows=[]
store='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStore.kt'
text=original(store)
text=change(text,'    fun import(items:', '''    /** A delayed profile response cannot resurrect an unblocked or newly replaced entry. */
    fun replaceProfileIfUnchanged(previous: BlockedUp, next: BlockedUp): Boolean = synchronized(mutationLock) {
        require(next.mid == previous.mid && next.blockedAt == previous.blockedAt)
        migrateLegacyDiscoveryMids().getOrThrow()
        val rows = readRecords()
        if (rows.firstOrNull { it.mid == previous.mid } != previous) return@synchronized false
        persist(rows.map { if (it.mid == previous.mid) next else it })
        true
    }
    fun import(items:''')
own(store,text)
community='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopCommunityRepository.kt'
text=original(community)
text=change(text,'class DesktopCommunityRepository(private val repository: DesktopRepository) {', '''class DesktopCommunityRepository(private val repository: DesktopRepository,
    val blockedUps: DesktopBlockedUpStore = DesktopBlockedUpStore(com.bilipai.desktop.plugins.DesktopPluginContext(
        com.bilipai.desktop.plugins.DesktopPluginStore(com.bilipai.desktop.DesktopLibrary.directoryForAccount(null))))) {
    val blockedUpRepository by lazy { DesktopBlockedUpRepository(repository, blockedUps) }
    internal val accountEpoch get() = repository.sessionEpochFlow''')
own(community,text)
dynamic='desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt'
text=original(dynamic)
text=change(text,'    var type by remember { mutableStateOf("all") }','''    val blocked by community.blockedUps.mids.collectAsState()
    val epoch by community.accountEpoch.collectAsState()
    val transform = remember(blocked) { { rows: List<DynamicItem> -> desktopVisibleDynamicItems(rows, blocked) } }
    var type by remember { mutableStateOf("all") }''')
text=change(text,'CommunityFeed(Triple(mid, type, revision),','CommunityFeed(listOf(mid, epoch, type, revision),')
text=change(text,'identity = { it.id_str }, onLogin = navigation.onLogin) { CommunityDynamicCard','identity = { it.id_str }, onLogin = navigation.onLogin, transform = transform) { CommunityDynamicCard')
text=change(text,'    val author = item.modules.module_author','''    val account by community.account.collectAsState()
    val author = item.modules.module_author''')
text=change(text,'                if (!details) TextButton(onClick = { navigation.onDynamic(item.id_str) }) { Text("详情") }', '''                if (author != null && author.mid > 0 && account?.mid != author.mid) DesktopBlockedUpAction(
                    community.blockedUpRepository, author.mid, author.name, author.face, navigation.onLogin)
                if (!details) TextButton(onClick = { navigation.onDynamic(item.id_str) }) { Text("详情") }''')
own(dynamic,text)
search='desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunitySearchScreens.kt'
text=original(search)
text=change(text,'val season: Long = 0, val url: String = "")','val season: Long = 0, val url: String = "", val blockedOwnerMid: Long = 0)')
text=change(text,'private data class CommunitySearchRow','internal data class CommunitySearchRow')
text=change(text,'private fun communitySearchRows','internal fun communitySearchRows')
text=change(text,'    val account by community.account.collectAsState()','''    val account by community.account.collectAsState()
    val epoch by community.accountEpoch.collectAsState()
    val blocked by community.blockedUps.mids.collectAsState()''')
text=change(text,'listOf("search-screen", account?.mid, initialQuery)','listOf("search-screen", epoch, account?.mid, initialQuery)')
start=text.index('    val transform = remember(');end=text.index('    fun saveHistory(',start)
text=text[:start]+'''    val transform = remember(runtime, nativePlugins, jsonPlugins, pluginConfig, blocked) { { rows: List<CommunitySearchRow> ->
        val visible = desktopVisibleSearchRows(rows, blocked)
        if (runtime == null || visible.none { it.rawVideo != null }) visible else {
            val originals = visible.associateBy { it.rawVideo?.bvid }
            runtime.filterFeedItems(visible.mapNotNull { it.rawVideo }, FeedKind.SEARCH).mapNotNull { video ->
                originals[video.bvid]?.copy(video = discoveryVideoCard(video), rawVideo = video)
            }
        }
    } }
'''+text[end:]
text=change(text,'listOf(submitted, type, filters.requestKey(type))','listOf(epoch, submitted, type, filters.requestKey(type))')
text=change(text,'publishedAt = item.pubdate, authorMid = item.owner.mid))','publishedAt = item.pubdate, authorMid = item.owner.mid), blockedOwnerMid = item.owner.mid)')
text=change(text,'item.upic, user = item.mid)','item.upic, user = item.mid, blockedOwnerMid = item.mid)')
text=change(text,'item.user_cover.ifBlank { item.cover }, live = item.roomid)','item.user_cover.ifBlank { item.cover }, live = item.roomid, blockedOwnerMid = item.uid)')
text=change(text,'live = item.roomid, user = if (item.roomid <= 0) item.uid else 0)','live = item.roomid, user = if (item.roomid <= 0) item.uid else 0, blockedOwnerMid = item.uid)')
text=change(text,'    var profile by remember(mid)', '''    val blocked by community.blockedUps.mids.collectAsState()
    val dynamicTransform = remember(blocked) { { rows: List<DynamicItem> -> desktopVisibleDynamicItems(rows, blocked) } }
    var profile by remember(mid)''')
text=change(text,'identity = { it.id_str }, onLogin = navigation.onLogin) { CommunityDynamicCard',
    'identity = { it.id_str }, onLogin = navigation.onLogin, transform = dynamicTransform) { CommunityDynamicCard')
text+='''
/** Match the original video/UP/live-room/live-user categories; other search types keep their source behavior. */
internal fun desktopVisibleSearchRows(rows: List<CommunitySearchRow>, blocked: Set<Long>): List<CommunitySearchRow> =
    rows.filter { it.blockedOwnerMid !in blocked }
'''
own(search,text)
editors='desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityEditors.kt'
text=original(editors)
text=change(text,'        if (account?.mid == comment.memberId && comment.memberId > 0) TextButton', '''        if (comment.memberId > 0 && account?.mid != comment.memberId) DesktopBlockedUpAction(
            community.blockedUpRepository, comment.memberId, comment.author, comment.avatar, onLogin,
            source = com.android.purebilibili.data.repository.BlockedUpRelationSource.COMMENT)
        if (account?.mid == comment.memberId && comment.memberId > 0) TextButton''')
own(editors,text)
space='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCompleteSpaceScreen.kt'
text=original(space)
text=change(text,'''                    else CommunityAction(resolveSpaceFollowActionLabel(false, user.relationStatus, user.isFollowed), onLogin,
                        action = { if (user.relationStatus == 128) social.removeBlacklist(mid) else social.setFollowing(mid, !user.isFollowed) },
                        onSuccess = { scope.launch { loadOverview() } })''', '''                    else Row {
                        if (user.relationStatus != 128) CommunityAction(resolveSpaceFollowActionLabel(false, user.relationStatus, user.isFollowed), onLogin,
                            action = { social.setFollowing(mid, !user.isFollowed) }, onSuccess = { scope.launch { loadOverview() } })
                        DesktopBlockedUpAction(community.blockedUpRepository, mid, user.name, user.face, onLogin,
                            remoteBlocked = user.relationStatus == 128, onChanged = { scope.launch { loadOverview() } })
                    }''')
own(space,text)
spaceLeaf='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceScreens.kt'
text=original(spaceLeaf)
text=change(text,'    val account by repository.account.collectAsState()', '''    val account by repository.account.collectAsState()
    val blocked by community.blockedUps.mids.collectAsState()
    val dynamicTransform = remember(blocked) { { rows: List<DynamicItem> -> desktopVisibleDynamicItems(rows, blocked) } }''')
text=change(text,'identity = { it.id_str }, onLogin = onLogin) { CommunityDynamicCard',
    'identity = { it.id_str }, onLogin = onLogin, transform = dynamicTransform) { CommunityDynamicCard')
own(spaceLeaf,text)
(HERE/'owned-base-files.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8',newline='\n')
