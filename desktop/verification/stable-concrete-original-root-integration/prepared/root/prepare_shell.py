from pathlib import Path
import hashlib,json,difflib,os
H=Path(__file__).resolve().parent
R=H.parents[4]/'work/BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
path='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
base=safe(R/path).read_text(encoding='utf-8').replace('\r\n','\n');text=base
def replace(a,b):
 global text
 assert text.count(a)==1,(a[:100],text.count(a));text=text.replace(a,b)
def body(name,replacement):
 global text
 start=text.index('    fun '+name+'('); k=text.index('(',start)+1; parens=1
 while parens:
  if text[k]=='(':parens+=1
  elif text[k]==')':parens-=1
  k+=1
 opening=text.index('{',k);depth=1;i=opening+1
 # Original functions in this selection contain no brace-bearing Kotlin strings.
 while depth:
  if text[i]=='{':depth+=1
  elif text[i]=='}':depth-=1
  i+=1
 text=text[:start]+replacement+text[i:]
replace('    STORY("竖屏播放", "▯"), TOPIC("话题", "#"), SETTINGS("设置", "⚙")','    STORY("竖屏播放", "▯"), TOPIC("话题", "#"), SETTINGS("设置", "⚙"), UNSUPPORTED("待接入页面", "…")')
replace('import com.bilipai.desktop.appearance.WindowsTextClipboard','import com.bilipai.desktop.appearance.WindowsTextClipboard\nimport com.android.purebilibili.navigation3.BiliPaiNavKey\nimport com.android.purebilibili.navigation3.toLegacyRoute')
replace('import kotlinx.coroutines.flow.MutableStateFlow','import kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.channels.Channel')
assert text.count('    danmakuPresentation: DesktopDanmakuPresentationBinding) {')==2
text=text.replace('    danmakuPresentation: DesktopDanmakuPresentationBinding) {','    danmakuPresentation: DesktopDanmakuPresentationBinding,\n    isFullscreen: () -> Boolean, setFullscreen: (Boolean) -> Unit) {')
# Two declarations have the same tail. Preserve explicit signatures and forwarding separately.
# The private Ready signature is transformed by its unique diagnostic prefix below.
replace('            danmakuPresentation = danmakuPresentation)','            danmakuPresentation = danmakuPresentation, isFullscreen = isFullscreen, setFullscreen = setFullscreen)')
replace('AtomicReference<DesktopHomeRootRetainer?>()', 'AtomicReference<DesktopReadyOriginalRootHandle?>()')
replace('    var section by remember { mutableStateOf(DesktopSection.HOME) }', '    var section by remember { mutableStateOf(DesktopSection.HOME) }\n    var physicalDestination by remember { mutableStateOf<BiliPaiNavKey>(BiliPaiNavKey.Home) }\n    var rootNowPlayingDismissed by remember { mutableStateOf(false) }\n    var initialVideoConsumed by remember { mutableStateOf(initialVideo==null) }\n    val musicOverlay = remember { MutableStateFlow(false) }\n    fun rootRoutes() = homeRootRef.get()?.route?.get()?.takeIf { it.owns() }')
replace('    var showVideo by remember { mutableStateOf(initialVideo != null) }','    var showVideo by remember { mutableStateOf(false) }')
replace('    var activatingUpdate by remember { mutableStateOf(false) }','''    var activatingUpdate by remember { mutableStateOf(false) }
    // Store's nav invalidation callback only enqueues. Never start inline coroutine cleanup
    // or dispatcher cancellation while that original Store callback owns its monitor.
    val authenticationInvalidations = remember(repository) { Channel<Pair<Long,Long>>(Channel.UNLIMITED) }
    DisposableEffect(authenticationInvalidations) { onDispose { authenticationInvalidations.close() } }
    LaunchedEffect(repository, authenticationInvalidations) {
        for ((expectedEpoch, expectedMid) in authenticationInvalidations) {
            if (isClosing() || activatingUpdate || !scope.isActive) continue
            try {
                if(repository.logoutHomeAuthenticationInvalidated(expectedEpoch,expectedMid,
                    {!isClosing() && !activatingUpdate && scope.isActive}))
                    error="登录信息已失效，请重新登录"
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { error="登录失效状态无法保存，请重试" }
        }
    }''')
replace('            revealVideo = { showVideo = true; mediaActive = false; playerFocused = false },', '''            revealVideo = {
                val item = playback.state.value.queue.getOrNull(playback.state.value.queueIndex)
                if (item != null) rootRoutes()?.video(BiliPaiNavKey.VideoDetail(item.bvid, item.preferredCid, item.cover,
                    sourceRoute = "favorite"))
            },''')
replace('            revealAudio = { showVideo = false; playerFocused = false; mediaActive = false\n                favoritesAudioCover = true; section = DesktopSection.LISTEN })','''            revealAudio = {
                val item = listen?.state?.value?.current
                if (item != null) { favoritesAudioCover = true; rootRoutes()?.push(BiliPaiNavKey.AudioMode(item.bvid, item.cid,
                    ((listen.player.state.value.positionSeconds) * 1000L).toLong())) }
            })''')
body('navigate','''    fun navigate(target: DesktopSection, commit: () -> Unit = {}): Boolean {
        val routes = rootRoutes() ?: return false
        if (activatingUpdate) return false
        commit()
        val key: BiliPaiNavKey = when (target) {
            DesktopSection.HOME -> BiliPaiNavKey.Home
            DesktopSection.POPULAR, DesktopSection.RANKING, DesktopSection.PRECIOUS -> {
                routes.root.entry.viewModel.switchPopularSubCategory(when(target) { DesktopSection.RANKING -> com.android.purebilibili.feature.home.PopularSubCategory.RANKING; DesktopSection.PRECIOUS -> com.android.purebilibili.feature.home.PopularSubCategory.PRECIOUS; else -> com.android.purebilibili.feature.home.PopularSubCategory.COMPREHENSIVE })
                routes.root.entry.viewModel.switchCategory(com.android.purebilibili.feature.home.HomeCategory.POPULAR); BiliPaiNavKey.Home
            }
            DesktopSection.REGION -> BiliPaiNavKey.Partition
            DesktopSection.WEEKLY -> BiliPaiNavKey.WeeklySeries(weeklyInitialNumber)
            DesktopSection.DYNAMIC -> dynamicRoute?.let { BiliPaiNavKey.DynamicDetail(it.dynamicId,it.rootReplyId,it.targetReplyId) } ?: BiliPaiNavKey.Dynamic
            DesktopSection.LIVE -> if(roomId>0) BiliPaiNavKey.Live(roomId=roomId.toString()) else BiliPaiNavKey.LiveList
            DesktopSection.BANGUMI -> if(seasonId>0||episodeId>0) BiliPaiNavKey.BangumiPlayer(seasonId,episodeId,(seasonProgress*1000).toLong(),isCourse) else BiliPaiNavKey.Bangumi(seasonType)
            DesktopSection.PLUGINS -> BiliPaiNavKey.PluginsSettings()
            DesktopSection.CLOUD_HISTORY, DesktopSection.HISTORY -> BiliPaiNavKey.History
            DesktopSection.CLOUD_FAVORITES, DesktopSection.FAVORITES -> BiliPaiNavKey.Favorite
            DesktopSection.WATCH_LATER -> BiliPaiNavKey.WatchLater
            DesktopSection.FOLLOWINGS -> BiliPaiNavKey.Following(account?.mid ?: 0)
            DesktopSection.LIKED -> BiliPaiNavKey.LikedVideos(account?.mid ?: 0)
            DesktopSection.LISTEN -> BiliPaiNavKey.ListenVideo
            DesktopSection.DOWNLOADS -> BiliPaiNavKey.DownloadList
            DesktopSection.MESSAGES -> BiliPaiNavKey.Inbox
            DesktopSection.SEARCH -> BiliPaiNavKey.Search(submitted)
            DesktopSection.USER -> BiliPaiNavKey.Space(userId)
            DesktopSection.ARTICLE -> BiliPaiNavKey.ArticleDetail(articleId)
            DesktopSection.UNSUPPORTED -> return false
            DesktopSection.NOTES -> { error="视频笔记的原版导航入口仍待接入"; return false }
            DesktopSection.COLLECTION -> BiliPaiNavKey.SeasonSeriesDetail(collectionType,collectionId,collectionMid,collectionTitle)
            DesktopSection.JS_CONTENT -> BiliPaiNavKey.JsPluginContent(jsPluginId)
            DesktopSection.EXTERNAL_MEDIA -> { error="外部媒体必须从原授权 launchId 进入"; return false }
            DesktopSection.APPEARANCE -> BiliPaiNavKey.AppearanceSettings
            DesktopSection.MUSIC -> when(val value=musicSource) {
                is MusicPlaybackSource.AudioSong -> BiliPaiNavKey.MusicDetail(value.sid)
                is MusicPlaybackSource.VideoAudio -> BiliPaiNavKey.NativeMusic(value.title,value.bvid,value.cid)
                null -> return false
            }
            DesktopSection.BGM -> bgmRequest?.let{BiliPaiNavKey.BgmDetail(it.musicId,it.aid,it.cid,it.showVideos)} ?: return false
            DesktopSection.STORY -> BiliPaiNavKey.Story(storySeed.bvid,storySeed.cid,storySeed.cover,storySeed.title)
            DesktopSection.TOPIC -> BiliPaiNavKey.TopicDetail(topicId)
            DesktopSection.SETTINGS -> BiliPaiNavKey.Settings
        }
        return routes.push(key)
    }''')
body('openVideo','''    fun openVideo(card: VideoCard) {
        rootRoutes()?.video(BiliPaiNavKey.VideoDetail(card.bvid,card.preferredCid,card.cover,
            resumePositionMs=(card.progressSeconds?.coerceAtLeast(0)?.toLong() ?: 0L)*1000L,
            sourceRoute=physicalDestination.toLegacyRoute()))
    }''')
replace('        showVideo = true; mediaActive = false; playback.openQueue(videos, index)','''        playback.openQueue(videos, index)
        rootRoutes()?.video(BiliPaiNavKey.VideoDetail(selected.bvid,selected.preferredCid,selected.cover,
            sourceRoute=physicalDestination.toLegacyRoute()))''')
body('closeWeeklySeries','    fun closeWeeklySeries() { rootRoutes()?.back() }')
body('openUser','    fun openUser(id: Long) { rootRoutes()?.push(BiliPaiNavKey.Space(id)) }')
body('openDynamicRoute','    fun openDynamicRoute(route: DesktopDynamicDetailRoute) { rootRoutes()?.push(BiliPaiNavKey.DynamicDetail(route.dynamicId,route.rootReplyId,route.targetReplyId)) }')
body('openTopic','    fun openTopic(id: Long) { if(id>0) rootRoutes()?.push(BiliPaiNavKey.TopicDetail(id)) }')
body('openTopicKeyword','    fun openTopicKeyword(keyword: String) { if(keyword.isNotBlank()) rootRoutes()?.push(BiliPaiNavKey.Search(keyword)) }')
body('openStory','    fun openStory(card: VideoCard? = null) { rootRoutes()?.push(BiliPaiNavKey.Story(card?.bvid.orEmpty(),card?.preferredCid ?: 0,card?.cover.orEmpty(),card?.title.orEmpty(),sourceRoute=physicalDestination.toLegacyRoute())) }')
body('openBgm','    fun openBgm(request: DesktopBgmMusicTarget.Detail) { rootRoutes()?.push(BiliPaiNavKey.BgmDetail(request.musicId,request.aid,request.cid,request.showVideos)) }')
body('closeBgm','    fun closeBgm() { rootRoutes()?.back() }')
body('closeMusic','    fun closeMusic() { rootRoutes()?.back() }')
body('openArticle','    fun openArticle(id: Long) { rootRoutes()?.push(BiliPaiNavKey.ArticleDetail(id)) }')
body('openJsPlugin','    fun openJsPlugin(id: String) { rootRoutes()?.push(BiliPaiNavKey.JsPluginContent(id)) }')
replace('            showVideo = false; section = DesktopSection.EXTERNAL_MEDIA; lastMediaSection = section','            rootRoutes()?.push(BiliPaiNavKey.ExternalMedia(launchId))')
body('openLive','    fun openLive(id: Long) { if(id>0) rootRoutes()?.push(BiliPaiNavKey.Live(roomId=id.toString())) }')
body('showSeason','''    fun showSeason(id: Long, epId: Long = 0, course: Boolean = false, progress: Double = 0.0) {
        rootRoutes()?.push(BiliPaiNavKey.BangumiPlayer(id,epId,(progress.coerceAtLeast(0.0)*1000L).toLong(),course))
    }''')
body('openCollection','    fun openCollection(mid: Long, id: Long, type: String) { rootRoutes()?.push(BiliPaiNavKey.SeasonSeriesDetail(type,id,mid)) }')
replace('    val capturedFavoritesUiEpoch = sessionEpoch', '    LaunchedEffect(listening.current?.bvid,listening.current?.cid,sessionEpoch) { rootNowPlayingDismissed=false }\n    val capturedFavoritesUiEpoch = sessionEpoch')
replace('    LaunchedEffect(Unit) { runCatching { repository.refreshAccount() }; initialVideo?.let { playback.open(it) } }','    LaunchedEffect(Unit) { runCatching { repository.refreshAccount() } }')
replace('            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) { onToggleFullscreen(); true }', '''            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) { onToggleFullscreen(); true }
            else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !searchFocused) {
                if (isFullscreen()) setFullscreen(false) else homeRootRef.get()?.navigation?.requestBack()
                true
            }''')
replace('        if (audioPlayer != null) SwingPanel(factory = { audioPlayer.surface }, background = Color.Transparent, modifier = Modifier.size(1.dp))', '''        if (audioPlayer != null) SwingPanel(factory = { audioPlayer.surface }, background = Color.Transparent, modifier = Modifier.size(1.dp))
        // Native AO keeps the same actual source while ordinary Video is covered by Home.
        if(player!=null && !showVideo && section!=DesktopSection.STORY && retainedMedia.current==null && !pipActive && playing.details!=null)
            SwingPanel(factory={player.surface},background=Color.Transparent,modifier=Modifier.size(1.dp))''')
# Pull the exact existing native player/control lambda and leaf body out of the flat legacy scaffold.
pc=text.index('                    val playerContent: @Composable (MpvPlayer) -> Unit =')
wh=text.index('                        when {',pc)
player_code=text[pc:text.index('                    Box(Modifier.weight(1f).fillMaxWidth()) {',pc)]
end=text.index('        if (eyePaint.dimAlpha',wh)
leaf=text[wh:end]
# Strip the exact old enclosing Row/Column/Box closure, retaining only when body.
tail='''                        }
                    }
                }
            }
        }
'''
assert leaf.endswith(tail);leaf=leaf[:-len(tail)]+'                        }\n'
# Every leaf reads its typed immutable entry key; globals are only native/SMTC projections.
leaf=leaf.replace('onBack = { if (checkpointForNavigation()) { showVideo = false; playerFocused = false } }','onBack = { commands.back() }')
leaf=leaf.replace('                                val entry = favoritesEntry','                                val entry = ensureFavoritesEntry()')
leaf=leaf.replace('                                    val detail = entry.currentDetail','''                                    val detail = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.let {
                                        com.android.purebilibili.feature.list.FavoriteCollectionRoute(it.type,it.id,it.mid,it.title,it.ownerName)
                                    }''')
leaf=leaf.replace('''onBack = { if (entry.isOwned()) {
                                                if (entry.currentDetail != null) entry.currentDetail = null else navigate(DesktopSection.HOME)
                                            } }''','onBack = { if (entry.isOwned()) commands.back() }')
leaf=leaf.replace('onCollectionClick = { if (entry.isOwned()) entry.currentDetail = it }','onCollectionClick = { if (entry.isOwned()) commands.push(BiliPaiNavKey.SeasonSeriesDetail(it.type,it.id,it.mid,it.title,it.ownerName)) }')
leaf=leaf.replace('if (entry.isOwned()) entry.currentDetail = com.android.purebilibili.feature.list.FavoriteCollectionRoute("favorite", mediaId, ownerMid, title, ownerName)','if (entry.isOwned()) commands.push(BiliPaiNavKey.SeasonSeriesDetail("favorite",mediaId,ownerMid,title,ownerName))')
leaf=leaf.replace('isCurrentPage = !showVideo && !activatingUpdate','isCurrentPage = active && !activatingUpdate')
leaf=leaf.replace('loadFavoriteViewModelOnEnter = false)','loadFavoriteViewModelOnEnter = false,\n                                            initialSearchQuery = (entryKey as? BiliPaiNavKey.FavoriteSearch)?.query.orEmpty(),\n                                            initialSearchScope = (entryKey as? BiliPaiNavKey.FavoriteSearch)?.scope ?: com.android.purebilibili.data.model.response.FavoriteSearchScope.CURRENT_FOLDER,\n                                            initialSubscribed = entryKey == BiliPaiNavKey.FavoriteSubscribed,\n                                            isSearchDestination = entryKey is BiliPaiNavKey.FavoriteSearch,\n                                            onOpenSearchDestination = { value -> commands.push(BiliPaiNavKey.FavoriteSearch(value,(entryKey as? BiliPaiNavKey.FavoriteSearch)?.scope ?: com.android.purebilibili.data.model.response.FavoriteSearchScope.CURRENT_FOLDER)) })')
leaf=leaf.replace('onBack = { navigate(DesktopSection.JS_CONTENT) }','onBack = { commands.back() }')
leaf=leaf.replace('TextButton(onClick = { navigate(if (jsSettingsOrigin) DesktopSection.SETTINGS else DesktopSection.PLUGINS) })','TextButton(onClick = { commands.back() })')
leaf=leaf.replace('onVideosClick = { bgmRequest = request.copy(showVideos = true) }','onVideosClick = { commands.push(BiliPaiNavKey.BgmDetail(request.musicId,request.aid,request.cid,true)) }')
leaf=leaf.replace('onMediaSearch = { _, _, _ -> false }','onMediaSearch = { _, title, _ -> commands.push(BiliPaiNavKey.Search(title)) }')
leaf=leaf.replace('isActive = !showVideo && !activatingUpdate','isActive = active && !activatingUpdate')
leaf=leaf.replace('section == DesktopSection.STORY && !showVideo && !activatingUpdate','active && !activatingUpdate')
leaf=leaf.replace('onBack = { navigate(storyReturnSection) }','onBack = { commands.back() }')
topic_start=leaf.index('                                onBack = {\n                                    if (topicStack.size > 1)')
topic_end=leaf.index('                                }, onTopic = ::openTopic)',topic_start)
leaf=leaf[:topic_start]+'                                onBack = { commands.back() }, onTopic = ::openTopic)'+leaf[topic_end+len('                                }, onTopic = ::openTopic)'):]
leaf=leaf.replace('locateBvid = playing.details?.takeIf { it.authorMid == userId }?.bvid ?: history.firstOrNull()?.bvid','locateBvid = (entryKey as? BiliPaiNavKey.Space)?.targetBvid?.takeIf{it.isNotBlank()} ?: playing.details?.takeIf { it.authorMid == userId }?.bvid ?: history.firstOrNull()?.bvid')
# Preserve original actual video comment admission tails from installed69.
leaf=leaf.replace('onLogin = { loginDialog = true }, modifier = Modifier.fillMaxWidth().height(440.dp),','''onLogin = { loginDialog = true }, modifier = Modifier.fillMaxWidth().height(440.dp),
                                            initialCommentRootRpid = (entryKey as? BiliPaiNavKey.VideoDetail)?.commentRootRpid ?: 0L,
                                            initialCommentTargetRpid = (entryKey as? BiliPaiNavKey.VideoDetail)?.commentTargetRpid ?: 0L,
                                            commentRouteOpenId = (entryKey as? BiliPaiNavKey.VideoDetail)?.openId ?: 0L,''')
# The old discovery branch is not an allowed Home/Partition renderer; those are actual RootStack.
disc=leaf.index('                            section in listOf(DesktopSection.HOME, DesktopSection.POPULAR')
discend=leaf.index('                            section == DesktopSection.LIVE',disc)
leaf=leaf[:disc]+leaf[discend:]
download=leaf.index('                            section == DesktopSection.DOWNLOADS -> DownloadBrowserScreen')
dlend=leaf.index('                            section == DesktopSection.LISTEN',download)
leaf=leaf[:download]+'''                            entryKey == BiliPaiNavKey.DownloadList -> {
                                val owner = requireNotNull(homeRootRef.get()?.retainer?.current()).entry.gate
                                val binding = remember(owner) { DesktopOriginalDownloadListBindings(globalPluginContext,downloads,owner.scope,
                                    owner::owns,owner::commit,::desktopDownloadNetworkAvailable,{error=it}) }
                                DesktopOriginalDownloadListHost(binding,{commands.back()},
                                    {bvid->commands.video(BiliPaiNavKey.VideoDetail(bvid,sourceRoute="download_list"))},
                                    {taskId->commands.push(BiliPaiNavKey.OfflineVideoPlayer(taskId))})
                            }
                            entryKey is BiliPaiNavKey.OfflineVideoPlayer -> {
                                val owner = requireNotNull(homeRootRef.get()?.retainer?.current()).entry.gate
                                val binding = remember(owner,entryKey.taskId) { DesktopOfflineTaskPlayerBinding(downloads,retainedMedia,danmaku,
                                    owner.scope,owner.epoch,{repository.sessionEpoch},owner::owns,owner::commit,
                                    ::desktopDownloadNetworkAvailable,{playerError}) }
                                DesktopOfflineTaskPlayerHost(entryKey.taskId,binding,{commands.back()},
                                    {task->if(task.episodeId>0) commands.push(BiliPaiNavKey.BangumiPlayer(task.seasonId,task.episodeId,
                                        task.item.lastPlaybackPositionMs.coerceAtLeast(0L),task.isCourse))
                                    else commands.video(BiliPaiNavKey.VideoDetail(task.item.bvid,task.item.cid,task.item.cover,
                                        resumePositionMs=task.item.lastPlaybackPositionMs.coerceAtLeast(0L),sourceRoute="offline_video"))},
                                    onToggleFullscreen,playerContent)
                            }
                            entryKey is BiliPaiNavKey.WeeklySeries -> DesktopWeeklySeriesScreen(discovery.weeklySeriesRequests(),repository,entryKey.number,{commands.back()},
                                {video,list -> val cards=list.map{VideoCard(it.bvid,it.title,it.pic,it.owner.name,it.stat.view.toLong(),it.duration,preferredCid=it.cid)};
                                    openQueue(cards,cards.firstOrNull{it.bvid==video.bvid} ?: VideoCard(video.bvid,video.title,video.pic,video.owner.name,video.stat.view.toLong(),video.duration,preferredCid=video.cid))},isClosing=isClosing)
                            entryKey == BiliPaiNavKey.Login -> Column(Modifier.fillMaxSize()) {
                                TextButton(onClick={commands.back()}) {Text("返回")}
                                AdvancedLoginDialog(repository,onDismiss={commands.back()},onComplete={commands.back()})
                            }
                            entryKey is BiliPaiNavKey.Web -> Column {
                                Text(entryKey.title);TextButton(onClick={openDynamicWeb(entryKey.url,entryKey.title)}){Text("在浏览器打开")}
                            }
'''+leaf[dlend:]
fallback=leaf.index('                            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {')
leaf=leaf[:fallback]+'''                            else -> Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                                Text("该原版页面的平台闭包仍在接入中：${entryKey.toLegacyRoute()}")
                                TextButton(onClick={commands.back()}) { Text("返回") }
                            }
                        }
'''
aliases='''
                val section = desktopReadySection(entryKey)
                val videoEntry = entryKey as? BiliPaiNavKey.VideoDetail
                LaunchedEffect(videoEntry,active,native.ready,player?.currentSourceVersion) {
                    if(active && videoEntry!=null && native.ready && playing.details?.bvid==videoEntry.bvid &&
                        (videoEntry.cid<=0 || playing.details?.pages?.getOrNull(playing.currentPart)?.cid==videoEntry.cid) &&
                        playback.currentCastSource(player?.currentSourceVersion ?: 0L)!=null)
                        player?.setAudioOnly(videoEntry.startAudio || preferences.audioOnly)
                }
                LaunchedEffect(entryKey,active) {
                    if(active) when(entryKey) {
                        BiliPaiNavKey.Settings -> settingsNavigator.openRoot()
                        is BiliPaiNavKey.SettingsCategory -> settingsNavigator.openCategory(entryKey.category)
                        BiliPaiNavKey.SettingsSearch -> settingsNavigator.openSearch()
                        BiliPaiNavKey.PlaybackSettings -> settingsNavigator.openDetail(SettingsSearchTarget.PLAYBACK,null)
                        BiliPaiNavKey.HomeSettings -> settingsNavigator.openCategory(SettingsRootCategory.HOME_RECOMMENDATION)
                        BiliPaiNavKey.PermissionSettings -> settingsNavigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
                        BiliPaiNavKey.WebDavBackup -> settingsNavigator.openDetail(SettingsSearchTarget.DATA_BACKUP,null)
                        is BiliPaiNavKey.AudioMode -> if(listen!=null && entryKey.sourceBvid.isNotBlank() && entryKey.sourceCid>0) {
                            val current=listen.state.value.current
                            if(current?.bvid!=entryKey.sourceBvid || current.cid!=entryKey.sourceCid) {
                                val info=repository.videoDetails(entryKey.sourceBvid)
                                ensureActive()
                                if(homeRootRef.get()?.route?.get()?.currentKey==entryKey) {
                                    listen.playStartingAt(listOf(com.android.purebilibili.feature.video.player.PlaylistItem(
                                        info.bvid,entryKey.sourceCid,info.title,info.cover,info.author)),0,
                                        entryKey.sourceResumePositionMs.coerceAtLeast(0L)/1000.0)
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                val showVideo = entryKey is BiliPaiNavKey.VideoDetail
                val userId = (entryKey as? BiliPaiNavKey.Space)?.mid ?: (entryKey as? BiliPaiNavKey.Following)?.mid ?: 0L
                val submitted = (entryKey as? BiliPaiNavKey.Search)?.keyword.orEmpty()
                val articleId = (entryKey as? BiliPaiNavKey.ArticleDetail)?.articleId ?: 0L
                val dynamicRoute = (entryKey as? BiliPaiNavKey.DynamicDetail)?.let { DesktopDynamicDetailRoute(it.dynamicId,it.commentRootRpid,it.commentTargetRpid) }
                val topicId = (entryKey as? BiliPaiNavKey.TopicDetail)?.topicId ?: 0L
                val roomId = (entryKey as? BiliPaiNavKey.Live)?.roomId?.toLongOrNull() ?: 0L
                val bgmRequest = (entryKey as? BiliPaiNavKey.BgmDetail)?.let {DesktopBgmMusicTarget.Detail(it.musicId,it.aid,it.cid,it.showVideos)}
                val musicSource = when(entryKey) {is BiliPaiNavKey.MusicDetail->MusicPlaybackSource.AudioSong(entryKey.sid);is BiliPaiNavKey.NativeMusic->MusicPlaybackSource.VideoAudio(entryKey.bvid,entryKey.cid,entryKey.title);else->null}
                val musicStartPosition = if(entryKey is BiliPaiNavKey.AudioMode) entryKey.sourceResumePositionMs/1000.0 else 0.0
                val seasonId = when(entryKey){is BiliPaiNavKey.BangumiPlayer->entryKey.seasonId;is BiliPaiNavKey.BangumiDetail->entryKey.seasonId;else->0L}
                val episodeId = when(entryKey){is BiliPaiNavKey.BangumiPlayer->entryKey.epId;is BiliPaiNavKey.BangumiDetail->entryKey.epId;else->0L}
                val isCourse = (entryKey as? BiliPaiNavKey.BangumiPlayer)?.isCourse ?: false
                val seasonProgress = (entryKey as? BiliPaiNavKey.BangumiPlayer)?.resumePositionMs?.div(1000.0) ?: 0.0
                val seasonType = (entryKey as? BiliPaiNavKey.Bangumi)?.initialType ?: 1
                val jsPluginId = (entryKey as? BiliPaiNavKey.JsPluginContent)?.pluginId.orEmpty()
                val storySeed = (entryKey as? BiliPaiNavKey.Story)?.let{DesktopStorySeed(it.seedBvid,it.seedCid,it.seedCover,it.seedTitle)} ?: DesktopStorySeed()
                val collectionMid = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.mid ?: 0L
                val collectionId = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.id ?: 0L
                val collectionType = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.type.orEmpty()
                val collectionTitle = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.title.orEmpty()
'''
start=text.index('            Row(Modifier.fillMaxSize().padding(18.dp),')
text=text[:start]+player_code+'''
            DesktopDetailWindow {
            val actualWindow = hostWindow
            if(actualWindow==null) Text("原版导航需要实际应用窗口，尚未创建") else {
                val ffprobe = com.bilipai.desktop.download.WindowsFfmpegMuxer.locateFfmpeg()?.resolveSibling("ffprobe.exe")
                    ?: java.nio.file.Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),"native","windows-x64","ffprobe.exe")
                SideEffect { musicOverlay.value = listening.current != null && !rootNowPlayingDismissed }
                val services = DesktopReadyOriginalRootServices(repository,discovery,community,pluginRuntime,dynamicCardSession,
                    appearance,actualWindow,imageSaveLocations,imageSaveLifetime,diagnostics,nativeTextShare,rootTextShareBindings,
                    musicOverlay,{downloads.tasks.value.map{it.item}},listen,playerError,
                    { !isClosing() && !activatingUpdate },{pip?.active?.value==true},
                    { !isClosing() && !activatingUpdate && appearanceReady },
                    { action -> if(!isClosing()&&!activatingUpdate&&checkpointForNavigation()){action();true}else false },
                    { old,new ->
                        if(old is BiliPaiNavKey.Story && new !is BiliPaiNavKey.Story) storyHost.retire()
                        if(new is BiliPaiNavKey.VideoDetail) {
                            storyHost.retire();retainedMedia.stop();listen?.pause();systemTargetAudio=false
                            playback.openVideoDetail(VideoCard(new.bvid,new.bvid,new.coverUrl,"",0,0,preferredCid=new.cid),
                                new.resumePositionMs,keepMatchingSource=!new.startAudio)
                            if(new.fullscreen && !isFullscreen()) setFullscreen(true)
                        } else if(old is BiliPaiNavKey.VideoDetail && old.fullscreen && isFullscreen()) setFullscreen(false)
                    },
                    { destination -> if(physicalDestination!=destination) playerFocused=false
                        physicalDestination=destination;section=desktopReadySection(destination)
                        showVideo=destination is BiliPaiNavKey.VideoDetail
                        if(!initialVideoConsumed && initialVideo!=null) {
                            initialVideoConsumed=true;rootRoutes()?.video(BiliPaiNavKey.VideoDetail(initialVideo,sourceRoute="home"))
                        } },
                    {error=it}, {raw->openDynamicWeb(raw,"链接")},
                    { expectedEpoch,expectedMid -> authenticationInvalidations.trySend(expectedEpoch to expectedMid); Unit },
                    { gate -> DesktopProfileAccountsBinding(repository,gate.epoch,gate.mid,
                        requireNotNull(gate.scope.coroutineContext[Job]),gate::owns,gate::commit) },
                    { onExit() },{loginDialog=true},if(account!=null)({repository.logout()})else null,
                    dynamicCardRegistry::currentAllUpdateBaseline,null,favoritesEntry?.searchChannel,null,
                    { rootNowPlayingDismissed=true;listen?.pause() },
                    { DesktopOriginalNowPlayingVisibility(listening.current!=null&&!rootNowPlayingDismissed,
                        physicalDestination is BiliPaiNavKey.AudioMode,pipActive,true,false,
                        physicalDestination is BiliPaiNavKey.VideoDetail,actualWindow.width>actualWindow.height,
                        physicalDestination is BiliPaiNavKey.VideoDetail || retainedMedia.current!=null) },ffprobe)
                DesktopReadyOriginalRootMount(services,homeRootRef,Modifier.fillMaxSize()) { entryKey,commands,active,pagerHosted ->
                    CompositionLocalProvider(LocalDesktopDetailForeground provides (active&&hostVisible&&hostDisplayable)) {
''' + aliases+'''
                    Column(Modifier.fillMaxSize()) {
                        if(!pagerHosted && (entryKey is BiliPaiNavKey.VideoDetail || entryKey is BiliPaiNavKey.Search || entryKey == BiliPaiNavKey.Settings))
                            com.android.purebilibili.core.ui.components.AppTextButton(onClick={commands.back()}) {
                                com.android.purebilibili.core.ui.components.AppText("返回")
                            }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
'''+leaf+'''
                        }
                    }
                    }
                }
            }
            }
        }
'''+text[end:]
# MainHost provides its own pager selection; this enum is only a projection for the existing
# native/SMTC controls and leaf reuse. It never chooses a default Home for unknown keys.
map_code='''
private fun desktopReadySection(key:BiliPaiNavKey):DesktopSection = when(key) {
    is BiliPaiNavKey.VideoDetail -> DesktopSection.HOME
    BiliPaiNavKey.Dynamic,is BiliPaiNavKey.DynamicDetail -> DesktopSection.DYNAMIC
    is BiliPaiNavKey.Search -> DesktopSection.SEARCH
    is BiliPaiNavKey.Space -> DesktopSection.USER
    is BiliPaiNavKey.ArticleDetail -> DesktopSection.ARTICLE
    is BiliPaiNavKey.TopicDetail -> DesktopSection.TOPIC
    is BiliPaiNavKey.Story -> DesktopSection.STORY
    is BiliPaiNavKey.BgmDetail -> DesktopSection.BGM
    is BiliPaiNavKey.MusicDetail,is BiliPaiNavKey.NativeMusic -> DesktopSection.MUSIC
    BiliPaiNavKey.ListenVideo,is BiliPaiNavKey.AudioMode -> DesktopSection.LISTEN
    is BiliPaiNavKey.Live -> DesktopSection.LIVE
    is BiliPaiNavKey.Bangumi,is BiliPaiNavKey.BangumiDetail,is BiliPaiNavKey.BangumiPlayer -> DesktopSection.BANGUMI
    BiliPaiNavKey.DownloadList,is BiliPaiNavKey.OfflineVideoPlayer -> DesktopSection.DOWNLOADS
    BiliPaiNavKey.Favorite,BiliPaiNavKey.FavoriteSubscribed,is BiliPaiNavKey.FavoriteSearch,is BiliPaiNavKey.SeasonSeriesDetail -> DesktopSection.CLOUD_FAVORITES
    BiliPaiNavKey.History,is BiliPaiNavKey.HistorySearch -> DesktopSection.CLOUD_HISTORY
    BiliPaiNavKey.WatchLater,is BiliPaiNavKey.WatchLaterSearch -> DesktopSection.WATCH_LATER
    is BiliPaiNavKey.Following -> DesktopSection.FOLLOWINGS
    is BiliPaiNavKey.LikedVideos -> DesktopSection.LIKED
    BiliPaiNavKey.Inbox,BiliPaiNavKey.ReplyMe,BiliPaiNavKey.AtMe,BiliPaiNavKey.LikeMe,BiliPaiNavKey.SystemNotice,is BiliPaiNavKey.Chat -> DesktopSection.MESSAGES
    is BiliPaiNavKey.PluginsSettings -> DesktopSection.PLUGINS
    is BiliPaiNavKey.JsPluginContent -> DesktopSection.JS_CONTENT
    is BiliPaiNavKey.ExternalMedia -> DesktopSection.EXTERNAL_MEDIA
    BiliPaiNavKey.AppearanceSettings -> DesktopSection.APPEARANCE
    BiliPaiNavKey.Settings,is BiliPaiNavKey.SettingsCategory,BiliPaiNavKey.SettingsSearch,
        BiliPaiNavKey.HomeSettings,BiliPaiNavKey.IconSettings,BiliPaiNavKey.AnimationSettings,
        BiliPaiNavKey.PlaybackSettings,BiliPaiNavKey.PermissionSettings,BiliPaiNavKey.MessageNotificationSettings,
        BiliPaiNavKey.BottomBarSettings,BiliPaiNavKey.SettingsShare,BiliPaiNavKey.WebDavBackup -> DesktopSection.SETTINGS
    else -> DesktopSection.UNSUPPORTED // Explicit unsupported leaf; never Home or Notes.
}
'''
text+=map_code
out=H/'prepared/existing'/path;safe(out.parent).mkdir(parents=True,exist_ok=True);safe(out).write_text(text,encoding='utf-8')
safe(H/'shell-hunks.patch').write_text(''.join(difflib.unified_diff(base.splitlines(True),text.splitlines(True),'a/'+path,'b/'+path)),encoding='utf-8')
safe(H/'shell-hunks-pins.json').write_text(json.dumps({'path':path,'baseSha256LF':sha(base),'desiredSha256LF':sha(text),'preparedOnly':True,'mountedAcceptance':False},indent=2)+'\n',encoding='utf-8')
print('Prepared concrete Shell',sha(base),sha(text))
main_path='desktop/src/main/kotlin/com/bilipai/desktop/Main.kt'
main_base=safe(R/main_path).read_text(encoding='utf-8').replace('\r\n','\n')
anchor='                danmakuPresentation = danmakuPresentation)'
assert main_base.count(anchor)==1
main_desired=main_base.replace(anchor,'''                danmakuPresentation = danmakuPresentation,
                isFullscreen = { windowState.placement == WindowPlacement.Fullscreen },
                setFullscreen = { enabled ->
                    val destination = if(enabled) WindowPlacement.Fullscreen else WindowPlacement.Floating
                    if(windowState.placement != destination) windowState.placement = destination
                })''')
out=H/'prepared/existing'/main_path;safe(out.parent).mkdir(parents=True,exist_ok=True)
safe(out).write_text(main_desired,encoding='utf-8')
safe(H/'main-fullscreen-hunk.patch').write_text(''.join(difflib.unified_diff(main_base.splitlines(True),main_desired.splitlines(True),'a/'+main_path,'b/'+main_path)),encoding='utf-8')
safe(H/'main-fullscreen-hunk-pins.json').write_text(json.dumps({'path':main_path,'baseSha256LF':sha(main_base),'desiredSha256LF':sha(main_desired)},indent=2)+'\n',encoding='utf-8')
