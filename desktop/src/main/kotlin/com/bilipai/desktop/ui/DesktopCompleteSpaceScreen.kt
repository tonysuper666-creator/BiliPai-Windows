package com.bilipai.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Retained per account epoch and UP. Server defaults apply once; refresh preserves user navigation. */
internal class DesktopCompleteSpaceState {
    var overview by mutableStateOf<DesktopSpaceOverview?>(null)
    var failure by mutableStateOf<Throwable?>(null)
    var selectedMain by mutableStateOf(SpaceMainTab.HOME)
    var selectedContributionId by mutableStateOf("")
    var selectedSubTab by mutableStateOf(SpaceSubTab.VIDEO)
    var auxiliaryTab by mutableStateOf<DesktopSpaceTab?>(null)
    var interaction by mutableStateOf<DesktopSpaceContributionSection?>(null)
    var headerExpanded by mutableStateOf(true)
    var seasons by mutableStateOf(emptyList<SeasonItem>())
    var series by mutableStateOf(emptyList<SeriesItem>())
    var collectionsFailure by mutableStateOf<Throwable?>(null)
    var collectionsAttempted = false
    var collectionsLoaded = false
    private var defaultsApplied = false
    val leaf = DesktopSpaceBrowseState()
    val mainScroll = ScrollState(0)
    val secondaryScroll = ScrollState(0)
    val headerScroll = ScrollState(0)
    val homeScroll = ScrollState(0)

    fun contributionTabs(): List<SpaceContributionTab> {
        val seed = overview?.seed ?: return buildDefaultSpaceContributionTabs()
        val base = ensureSpaceContributionTabsForAvailableContent(seed.contributionTabs, seed.totalArticles > 0 || seed.articles.isNotEmpty())
        val tabs = if (collectionsLoaded) mergeSpaceContributionTabsWithCollections(base, seasons, series) else base
        return resolveDisplayedSpaceContributionTabs(tabs, seed.totalAudios)
    }

    fun acceptOverview(value: DesktopSpaceOverview) {
        overview = value
        if (!defaultsApplied) {
            selectedMain = value.defaultMainTab
            selectedContributionId = value.defaultContributionTabId
            defaultsApplied = true
        }
        reconcileContribution()
        failure = null
    }

    fun reconcileContribution() {
        val tab = resolveSelectedContributionTab(contributionTabs(), selectedContributionId, selectedSubTab)
        selectedContributionId = tab.id
        selectedSubTab = tab.subTab
    }

    fun selectMain(tab: SpaceMainTab) {
        selectedMain = tab
        auxiliaryTab = null
        interaction = null
    }

    fun selectSecondary(item: SpaceSecondarySwitchItem) {
        selectMain(item.targetTab)
        item.contributionTabId?.let { selectedContributionId = it; reconcileContribution() }
    }

    fun showHomeSection(section: DesktopSpaceHomeSection) {
        when (section) {
            DesktopSpaceHomeSection.FAVORITES -> selectMain(SpaceMainTab.FAVORITE)
            DesktopSpaceHomeSection.FOLLOW_BANGUMI -> selectMain(SpaceMainTab.BANGUMI)
            DesktopSpaceHomeSection.COINS, DesktopSpaceHomeSection.LIKED -> {
                selectMain(SpaceMainTab.HOME)
                interaction = if (section == DesktopSpaceHomeSection.COINS) DesktopSpaceContributionSection.COINS else DesktopSpaceContributionSection.LIKED
            }
            else -> {
                selectMain(SpaceMainTab.CONTRIBUTION)
                val desired = when (section) {
                    DesktopSpaceHomeSection.AUDIO -> SpaceSubTab.AUDIO
                    DesktopSpaceHomeSection.ARTICLES -> SpaceSubTab.ARTICLE
                    else -> SpaceSubTab.VIDEO
                }
                val selected = resolveSelectedContributionTab(contributionTabs(), "", desired)
                selectedContributionId = selected.id
                selectedSubTab = selected.subTab
            }
        }
    }
}

@Composable
internal fun DesktopCompleteSpaceScreen(requestedMid: Long, repository: DesktopRepository,
    social: DesktopSocialRepository, community: DesktopCommunityRepository, space: DesktopSpaceRepository,
    backend: DesktopSpaceContributionsRepository, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit,
    onArticle: (Long) -> Unit, onDynamic: (String) -> Unit, onLive: (Long) -> Unit, onBangumi: (Long) -> Unit,
    onAudio: (Long) -> Unit, onCourse: (Long) -> Unit, onResource: (PersonalResource) -> Unit,
    onCollection: (Long, Long, String) -> Unit, onPlaylist: (SpaceExternalPlaylist) -> Unit,
    onLogin: () -> Unit, onExternalUrl: (String) -> Unit,
    progressByBvid: Map<String, SpaceWatchProgress> = emptyMap(), localPositionMs: (String) -> Long = { 0L },
    locateBvid: String? = null) {
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val mid = requestedMid.takeIf { it > 0 } ?: account?.mid ?: 0
    if (mid <= 0) { CommunityLoginGate(repository, onLogin) {}; return }
    val memory = LocalDesktopBrowseMemory.current
    val owner = listOf("complete-up-space", epoch, account?.mid, mid)
    val state = remember(memory, owner) { memory?.screen(owner) { DesktopCompleteSpaceState() } ?: DesktopCompleteSpaceState() }
    val scope = rememberCoroutineScope()
    var loading by remember(state) { mutableStateOf(false) }
    suspend fun loadOverview() {
        loading = true
        try {
            val value = coroutineScope {
                val supplemental = async { backend.home(mid) }
                backend.metadata(mid).toOverview(supplemental.await())
                    ?: throw BiliApiException(-1, "空间聚合资料不完整")
            }
            if (repository.sessionEpoch == epoch) state.acceptOverview(value)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (repository.sessionEpoch == epoch) state.failure = failure
        } finally { loading = false }
    }
    suspend fun loadCollections() {
        try {
            val collections = community.spaceCollections(mid).items_lists
            if (repository.sessionEpoch == epoch) {
                state.seasons = collections?.seasons_list.orEmpty()
                state.series = collections?.series_list.orEmpty()
                state.collectionsLoaded = true
                state.reconcileContribution()
                state.collectionsFailure = null
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (repository.sessionEpoch == epoch) state.collectionsFailure = failure
        }
        state.collectionsAttempted = true
    }
    LaunchedEffect(owner) {
        coroutineScope {
            if (state.overview == null && state.failure == null) launch { loadOverview() }
            if (!state.collectionsAttempted) launch { loadCollections() }
        }
    }
    val overview = state.overview
    if (overview == null) {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failure?.let { CommunityFailure(it, onLogin) { scope.launch { loadOverview() } } }
            // The original aggregate policy requires the legacy profile path when the card is incomplete.
            if (state.failure != null) DesktopSpaceScreen(mid, repository, social, community, space,
                onVideo, onUser, onArticle, onDynamic, onLive, onBangumi, onResource, onCollection, onLogin, onExternalUrl)
        }
        return
    }
    val contributionTabs = state.contributionTabs()
    val contribution = resolveSelectedContributionTab(contributionTabs, state.selectedContributionId, state.selectedSubTab)
    @Composable fun leaf(tab: DesktopSpaceTab) {
        DesktopSpaceScreen(mid, repository, social, community, space, onVideo, onUser, onArticle, onDynamic,
            onLive, onBangumi, onResource, onCollection, onLogin, onExternalUrl, tab, false, state.leaf)
    }
    @Composable fun contributions(section: DesktopSpaceContributionSection) {
        DesktopSpaceContributionScreen(mid, section, repository, backend, onVideo, onUser,
            { onBangumi(it.seasonId) }, { onAudio(it.id) }, { onCourse(it.seasonId) }, onLogin)
    }
    CompositionLocalProvider(LocalCommunityFeedNamespace provides owner) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { state.headerExpanded = !state.headerExpanded }) { Text(if (state.headerExpanded) "收起资料" else overview.user.name) }
                TextButton(onClick = { scope.launch { loadOverview(); loadCollections() } }, enabled = !loading) { Text("刷新资料") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.headerExpanded) DesktopSpaceHeader(overview,
                onFollowers = { onExternalUrl("https://space.bilibili.com/$it/fans/fans") },
                onFollowings = { state.auxiliaryTab = DesktopSpaceTab.FOLLOWINGS }, onUser, onLive,
                onExternalUrl = { url, _ -> onExternalUrl(url) },
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(state.headerScroll), action = {
                    val user = overview.user
                    if (account?.mid == mid) TextButton(onClick = { onExternalUrl("https://account.bilibili.com/account/setting") }) { Text("编辑资料") }
                    else CommunityAction(resolveSpaceFollowActionLabel(false, user.relationStatus, user.isFollowed), onLogin,
                        action = { if (user.relationStatus == 128) social.removeBlacklist(mid) else social.setFollowing(mid, !user.isFollowed) },
                        onSuccess = { scope.launch { loadOverview() } })
                })
            state.failure?.let { CommunityFailure(it, onLogin) { scope.launch { loadOverview() } } }
            Row(Modifier.horizontalScroll(state.mainScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                resolveSpaceDisplayedMainTabs(overview.mainTabs, state.selectedMain, overview.hasCheeseTab).forEach { item ->
                    FilterChip(resolveSpacePrimaryTab(state.selectedMain) == item.tab && state.auxiliaryTab == null && state.interaction == null,
                        { state.selectMain(item.tab) }, label = { Text(item.title) })
                }
                TextButton(onClick = { state.auxiliaryTab = DesktopSpaceTab.CHARGE }) { Text("充电排行") }
                TextButton(onClick = { state.auxiliaryTab = DesktopSpaceTab.GUARDS }) { Text("舰队") }
            }
            if (shouldShowSpaceSecondarySwitch(state.selectedMain) && state.auxiliaryTab == null && state.interaction == null) {
                Row(Modifier.horizontalScroll(state.secondaryScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resolveSpaceSecondarySwitchItems(contributionTabs, overview.hasCheeseTab).forEach { item ->
                        FilterChip(resolveSelectedSpaceSecondarySwitchId(state.selectedMain, state.selectedContributionId) == item.id,
                            { state.selectSecondary(item) }, label = { Text(item.title) })
                    }
                }
                state.collectionsFailure?.let { CommunityFailure(it, onLogin) { scope.launch { loadCollections() } } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.auxiliaryTab != null -> Column {
                        TextButton(onClick = { state.auxiliaryTab = null }) { Text("‹ 返回空间") }
                        leaf(state.auxiliaryTab!!)
                    }
                    state.interaction != null -> Column {
                        TextButton(onClick = { state.interaction = null }) { Text("‹ 返回主页") }
                        contributions(state.interaction!!)
                    }
                    else -> when (state.selectedMain) {
                        SpaceMainTab.HOME -> Column(Modifier.fillMaxSize().verticalScroll(state.homeScroll).padding(16.dp)) {
                            DesktopSpaceHomeContent(overview, onVideo, onUser, onArticle, onDynamic, onAudio, onBangumi,
                                onFolder = { state.leaf.folder = it; state.leaf.folderSource = it.source; state.selectMain(SpaceMainTab.FAVORITE) },
                                onSection = state::showHomeSection, onExternalUrl = { url, _ -> onExternalUrl(url) })
                        }
                        SpaceMainTab.DYNAMIC -> leaf(DesktopSpaceTab.DYNAMIC)
                        SpaceMainTab.FAVORITE -> leaf(DesktopSpaceTab.FAVORITES)
                        SpaceMainTab.COLLECTIONS -> leaf(DesktopSpaceTab.COLLECTIONS)
                        SpaceMainTab.BANGUMI -> contributions(DesktopSpaceContributionSection.FOLLOW_BANGUMI)
                        SpaceMainTab.CHEESE -> contributions(DesktopSpaceContributionSection.COURSES)
                        SpaceMainTab.CONTRIBUTION -> when (contribution.subTab) {
                            SpaceSubTab.VIDEO, SpaceSubTab.CHARGING_VIDEO -> DesktopSpaceVideoBrowser(mid, repository, backend,
                                onVideo, onUser, onPlaylist, onLogin, progressByBvid, localPositionMs, locateBvid)
                            SpaceSubTab.AUDIO -> contributions(DesktopSpaceContributionSection.AUDIO)
                            SpaceSubTab.ARTICLE, SpaceSubTab.OPUS -> leaf(DesktopSpaceTab.ARTICLES)
                            SpaceSubTab.SEASON_VIDEO -> CommunityCollectionScreen(mid, contribution.seasonId, "season", community, onVideo, onUser, onLogin)
                            SpaceSubTab.SERIES -> CommunityCollectionScreen(mid, contribution.seriesId, "series", community, onVideo, onUser, onLogin)
                            SpaceSubTab.UGC_SEASON, SpaceSubTab.COMIC -> Text("该投稿栏目尚未开放", Modifier.padding(20.dp))
                        }
                    }
                }
            }
        }
    }
}
