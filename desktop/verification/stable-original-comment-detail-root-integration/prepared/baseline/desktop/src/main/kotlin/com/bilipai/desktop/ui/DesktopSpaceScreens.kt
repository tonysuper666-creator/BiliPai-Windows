package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class DesktopSpaceTab(val title: String) {
    VIDEOS("投稿"), DYNAMIC("动态"), FOLLOWINGS("关注"), COLLECTIONS("合集 / 系列"),
    FAVORITES("收藏夹"), ARTICLES("专栏 / 图文"), CHARGE("充电排行"), GUARDS("舰队")
}

/** Stored with the same account/UP owner as its feed pages, rather than the navigation composition. */
internal class DesktopSpaceBrowseState {
    var tab by mutableStateOf(DesktopSpaceTab.VIDEOS)
    var folderSource by mutableStateOf(FavFolderSource.OWNED)
    var folder by mutableStateOf<FavFolder?>(null)
    var profile by mutableStateOf<UserProfile?>(null)
    var profileError by mutableStateOf<Throwable?>(null)
    var supporterSummary by mutableStateOf<DesktopSpaceSupporters?>(null)
    var supporterError by mutableStateOf<Throwable?>(null)
    var supporterAttempted by mutableStateOf(false)
    var selectedPrivilege by mutableStateOf<Int?>(null)
    var rankTabs by mutableStateOf(emptyList<SpaceUpowerLevelInfo>())
    var rankTotal by mutableLongStateOf(0L)
    val tabScroll = ScrollState(0)
}

internal fun desktopSpaceOwner(epoch: Long, accountMid: Long?, spaceMid: Long): List<Any?> =
    listOf("up-space", epoch, accountMid, spaceMid)

@Composable
internal fun DesktopSpaceScreen(requestedMid: Long, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, space: DesktopSpaceRepository,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onArticle: (Long) -> Unit,
    onDynamic: (String) -> Unit, onLive: (Long) -> Unit, onBangumi: (Long) -> Unit, onResource: (PersonalResource) -> Unit,
    onCollection: (mid: Long, collectionId: Long, collectionType: String) -> Unit,
    onLogin: () -> Unit, onExternalUrl: (String) -> Unit,
    bodyTab: DesktopSpaceTab? = null, headerVisible: Boolean = true,
    browseState: DesktopSpaceBrowseState? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {},
    onImagePreviewFeedback: (String) -> Unit = {}) {
    val account by repository.account.collectAsState()
    val blocked by community.blockedUps.mids.collectAsState()
    val dynamicTransform = remember(blocked) { { rows: List<DynamicItem> -> desktopVisibleDynamicItems(rows, blocked) } }
    val epoch by repository.sessionEpochFlow.collectAsState()
    val mid = requestedMid.takeIf { it > 0 } ?: account?.mid ?: 0
    if (mid <= 0) { CommunityLoginGate(repository, onLogin) {}; return }
    val memory = LocalDesktopBrowseMemory.current
    val owner = desktopSpaceOwner(epoch, account?.mid, mid)
    val state = browseState ?: remember(memory, owner) { memory?.screen(owner) { DesktopSpaceBrowseState() } ?: DesktopSpaceBrowseState() }
    val selectedTab = bodyTab ?: state.tab
    val scope = rememberCoroutineScope()
    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi, onDynamic, onTopic, onTopicKeyword)
    suspend fun loadProfile() {
        try { val result = social.userProfile(mid); state.profile = result; state.profileError = null }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; state.profileError = failure }
    }
    suspend fun loadSupporters() {
        try { state.supporterSummary = space.supporters(mid); state.supporterError = null }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; state.supporterError = failure }
        state.supporterAttempted = true
    }
    LaunchedEffect(owner) {
        if (state.profile == null && state.profileError == null) loadProfile()
        if (!state.supporterAttempted) loadSupporters()
    }
    CompositionLocalProvider(LocalCommunityFeedNamespace provides owner) {
    Column(Modifier.fillMaxSize()) {
        if (headerVisible) {
        state.profile?.let { user ->
            DesktopSpaceImagePreviews(repository, mid, user.avatar, emptyList(), "", onImagePreviewFeedback) { avatarClick, _ ->
            val avatarRect = rememberImagePreviewSourceRect()
            val avatarHidden = isImagePreviewSourceHidden(avatarRect.value, user.avatar)
            val context = LocalPlatformContext.current
            Box(Modifier.fillMaxWidth()) {
                DesktopSkinSpaceBackground(isOwner = account?.mid == user.mid, modifier = Modifier.matchParentSize())
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(80.dp).imagePreviewSourceBounds(avatarRect).alpha(if (avatarHidden) 0f else 1f)
                        .clickable(interactionSource = null, indication = null, enabled = user.avatar.isNotBlank() && !avatarHidden) {
                            avatarClick(avatarRect.value)
                        }) {
                        AsyncImage(model = ImageRequest.Builder(context)
                            .data(FormatUtils.buildSizedImageUrl(user.avatar, width = 320, height = 320))
                            .memoryCacheKey(resolveImagePreviewPlaceholderCacheKey(user.avatar) ?: user.avatar)
                            .crossfade(false).build(), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(user.name, style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onExternalUrl("https://space.bilibili.com/$mid/fans/fans") }) { Text("${user.followers} 粉丝") }
                            TextButton(onClick = { state.tab = DesktopSpaceTab.FOLLOWINGS }) { Text("${user.followingCount} 关注") }
                            Text("LV${user.level}${if (user.isVip) " · 大会员" else ""}", Modifier.align(Alignment.CenterVertically))
                        }
                        if (user.officialTitle.isNotBlank()) Text(user.officialTitle, color = MaterialTheme.colorScheme.primary)
                        if (user.biography.isNotBlank()) Text(user.biography)
                    }
                    if (account?.mid != user.mid) CommunityAction(if (user.isFollowed) "取消关注" else "关注", onLogin,
                        action = { social.setFollowing(user.mid, !user.isFollowed) },
                        onSuccess = { state.profile = user.copy(isFollowed = !user.isFollowed) })
                    TextButton(onClick = { scope.launch { loadProfile(); loadSupporters() } }) { Text("刷新资料") }
                }
            }
            }
        }
        state.profileError?.let { CommunityFailure(it, onLogin) { scope.launch { loadProfile() } } }
        state.supporterSummary?.let { summary ->
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.charge?.let { group -> TextButton(onClick = { state.tab = DesktopSpaceTab.CHARGE }) { Text("${group.count} 人为 TA 充电") } }
                summary.guard?.let { group -> TextButton(onClick = { state.tab = DesktopSpaceTab.GUARDS }) { Text("${group.count} 人加入了大航海") } }
            }
        }
        // Optional supporters can fail independently; the profile and chosen page remain available.
        if (state.tab in setOf(DesktopSpaceTab.CHARGE, DesktopSpaceTab.GUARDS)) {
            state.supporterError?.let { CommunityFailure(it, onLogin) { scope.launch { loadSupporters() } } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(state.tabScroll).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopSpaceTab.entries.forEach { tab -> FilterChip(selected = state.tab == tab,
                onClick = { state.tab = tab }, label = { Text(tab.title) }) }
        }
        }
        if (!headerVisible && selectedTab in setOf(DesktopSpaceTab.CHARGE, DesktopSpaceTab.GUARDS)) {
            state.supporterError?.let { CommunityFailure(it, onLogin) { scope.launch { loadSupporters() } } }
        }
        key(owner, selectedTab) {
            when (selectedTab) {
                DesktopSpaceTab.VIDEOS -> CommunityFeed(selectedTab, 1,
                    load = { page -> community.spaceVideos(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
                    identity = { it.aid }, onLogin = onLogin) { item ->
                    CommunityVideoRow(VideoCard(item.bvid, item.title, item.pic, item.author, item.play.toLong(), personalDurationText(item.length),
                        publishedAt = item.created, authorMid = mid), onVideo, onUser)
                }
                DesktopSpaceTab.DYNAMIC -> CommunityLoginGate(repository, onLogin) {
                    CommunityFeed(selectedTab, "", load = { offset -> community.spaceDynamics(mid, offset).let { CommunityBatch(it.items, it.nextOffset) } },
                        identity = { it.id_str }, onLogin = onLogin, transform = dynamicTransform) { CommunityDynamicCard(it, community, navigation) }
                }
                DesktopSpaceTab.FOLLOWINGS -> CommunityFeed(selectedTab, 1,
                    load = { page -> community.followings(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
                    identity = { it.mid }, onLogin = onLogin) { user -> CommunityLinkCard(user.uname, user.face, user.sign) { onUser(user.mid) } }
                DesktopSpaceTab.COLLECTIONS -> CommunityFeed<Pair<String, Any>, Unit>(selectedTab, Unit, load = {
                    val data = community.spaceCollections(mid).items_lists
                    CommunityBatch(data?.seasons_list.orEmpty().map { "season" to it } + data?.series_list.orEmpty().map { "series" to it }, null)
                }, identity = { entry -> when (val item = entry.second) {
                    is SeasonItem -> "season:${item.meta.season_id}"; is SeriesItem -> "series:${item.meta.series_id}"; else -> entry.toString()
                } }, onLogin = onLogin) { entry -> when (val item = entry.second) {
                    is SeasonItem -> CommunityLinkCard(item.meta.name, item.meta.cover, "${item.meta.total} 个视频 · ${item.meta.description}") { onCollection(mid, item.meta.season_id, "season") }
                    is SeriesItem -> CommunityLinkCard(item.meta.name, item.meta.cover, "${item.meta.total} 个视频 · ${item.meta.description}") { onCollection(mid, item.meta.series_id, "series") }
                } }
                DesktopSpaceTab.FAVORITES -> SpaceFavoritePage(mid, state, space, onVideo, onUser, onLogin, onResource, onCollection)
                DesktopSpaceTab.ARTICLES -> CommunityFeed(selectedTab, 1, load = { page -> space.articles(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
                    identity = { it.id }, onLogin = onLogin) { article ->
                    Card(Modifier.fillMaxWidth().clickable { navigateSpaceArticle(article, onArticle, onDynamic, onUser, onVideo) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(article.title, style = MaterialTheme.typography.titleMedium)
                            if (article.summary.isNotBlank()) Text(article.summary)
                            Text(buildSpaceArticleStatsText(article), style = MaterialTheme.typography.bodySmall)
                            val images = article.displayImageUrls()
                            if (images.isNotEmpty()) Row(Modifier.horizontalScroll(remember { ScrollState(0) }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                images.forEach { AsyncImage(model = imageUrl(it), contentDescription = article.title, modifier = Modifier.size(140.dp, 100.dp)) }
                            }
                        }
                    }
                }
                DesktopSpaceTab.CHARGE -> CommunityFeed<SpaceUpowerRankItem, Unit>(Pair(selectedTab, state.selectedPrivilege), Unit, load = {
                    val requested = state.selectedPrivilege
                    val result = space.chargeRank(mid, requested)
                    if (result.totalCount > 0) state.rankTotal = result.totalCount
                    if (requested == null && state.rankTabs.isEmpty() && result.data.levelInfo.size > 1) state.rankTabs = result.data.levelInfo
                    CommunityBatch(result.items, null)
                }, identity = { it.mid }, onLogin = onLogin, header = {
                    Text("${state.rankTotal.takeIf { it > 0 } ?: state.supporterSummary?.charge?.count ?: 0} 人为 TA 充电")
                    Row(Modifier.horizontalScroll(remember { ScrollState(0) }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.selectedPrivilege == null, onClick = { state.selectedPrivilege = null }, label = { Text("全部") })
                        state.rankTabs.forEach { level -> FilterChip(selected = state.selectedPrivilege == level.privilegeType,
                            onClick = { state.selectedPrivilege = level.privilegeType }, label = { Text("${level.name} (${level.memberTotal})") }) }
                    }
                }) { user -> CommunityLinkCard(user.nickname, user.avatar, if (user.day > 0) "${user.day} 天" else "") { onUser(user.mid) } }
                DesktopSpaceTab.GUARDS -> CommunityFeed<DesktopSpaceGuardRow, Int>(selectedTab, 1, load = { page ->
                    val result = space.guards(mid, page)
                    CommunityBatch(result.tops.mapIndexed { index, item -> DesktopSpaceGuardRow(item, index + 1) } + result.items.map { DesktopSpaceGuardRow(it) }, result.nextPage)
                }, identity = { it.user.uid }, onLogin = onLogin) { row ->
                    CommunityLinkCard(row.user.username, row.user.face,
                        listOfNotNull(row.podiumRank?.let { "第 $it 名" }, resolveSpaceGuardLevelLabel(row.user.guardLevel)).joinToString(" · ")) { onUser(row.user.uid) }
                }
            }
        }
    }
    }
}

private data class DesktopSpaceGuardRow(val user: SpaceGuardMemberItem, val podiumRank: Int? = null)

@Composable
private fun SpaceFavoritePage(mid: Long, state: DesktopSpaceBrowseState, space: DesktopSpaceRepository,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit, onResource: (PersonalResource) -> Unit,
    onCollection: (Long, Long, String) -> Unit) {
    val folder = state.folder
    if (folder != null) Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { state.folder = null }) { Text("‹ ${folder.title} · 返回收藏夹") }
        CommunityFeed<PersonalResource, Int>(listOf("space-folder", folder.id), 1,
            load = { page -> space.favoriteResources(folder.id, page).let { CommunityBatch(it.items, it.nextCursor) } },
            identity = { (it as PersonalResource.Favorite).item.let { data -> "${data.type}:${data.id}" } }, onLogin = onLogin) { resource ->
            val raw = (resource as PersonalResource.Favorite).item
            val item = raw.toVideoItem(ownerFallbackMid = mid, ownerFallbackName = state.profile?.name.orEmpty(),
                ownerFallbackFace = state.profile?.avatar.orEmpty())
            val card = VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name,
                item.stat.view.toLong(), item.duration, preferredCid = item.cid, authorMid = item.owner.mid)
            CommunityVideoRow(card, onVideo = {
                if (item.isCollectionResource && item.collectionId > 0) onCollection(item.collectionMid, item.collectionId, "favorite_season")
                else if (!item.isCollectionResource && item.bvid.isNotBlank()) onVideo(card)
                else onResource(resource)
            }, onUser = onUser)
        }
    } else Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FavFolderSource.entries.forEach { source -> FilterChip(selected = state.folderSource == source,
                onClick = { state.folderSource = source }, label = { Text(if (source == FavFolderSource.OWNED) "创建的收藏夹" else "收藏的合集") }) }
        }
        CommunityFeed<FavFolder, Int>(Pair("space-folders", state.folderSource), 1, load = { page ->
            if (state.folderSource == FavFolderSource.OWNED) CommunityBatch(space.createdFavorites(mid), null)
            else space.collectedFavorites(mid, page).let { CommunityBatch(it.items, it.nextPage) }
        }, identity = { it.id }, onLogin = onLogin) { entry ->
            CommunityLinkCard(entry.title, entry.cover, "${entry.media_count} 个内容 · ${entry.intro}") {
                // Original SpaceScreen passes type="favorite" for both owned and subscribed folders.
                state.folder = entry
            }
        }
    }
}

internal fun navigateSpaceArticle(article: SpaceArticleItem, onArticle: (Long) -> Unit, onDynamic: (String) -> Unit,
    onUser: (Long) -> Unit, onVideo: (VideoCard) -> Unit) {
    when (val action = resolveSpaceArticleClickAction(article)) {
        is SpaceDynamicClickAction.OpenArticle -> onArticle(action.articleId)
        is SpaceDynamicClickAction.OpenDynamicDetail -> onDynamic(action.dynamicId)
        is SpaceDynamicClickAction.OpenUser -> onUser(action.mid)
        is SpaceDynamicClickAction.OpenVideo -> onVideo(VideoCard(action.bvid, article.title, article.displayImageUrls().firstOrNull().orEmpty(), "", 0, 0))
        SpaceDynamicClickAction.None -> Unit
    }
}
