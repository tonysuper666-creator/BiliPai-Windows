package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistSession
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalFavoriteFolderSession
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.DesktopWindowConfiguration
import com.bilipai.desktop.data.DesktopDynamicCacheOwner
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.VideoCard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/** The existing Root actors the Home card actions use. Every field is passed by the ready Root mount from its
 * own services; nothing here owns a player, a queue, a window or an account. */
internal class DesktopHomeCardActionServices(
    val repository: DesktopRepository,
    /** The Root's existing owned original API (DesktopCommunityRepository.favoriteApi). */
    val favoriteApi: BilibiliApi,
    val navigationAdmission: ((() -> Unit) -> Boolean),
    val beforeNavigationCommit: (BiliPaiNavKey, BiliPaiNavKey) -> Unit,
    val login: () -> Unit,
    val feedback: (String) -> Unit,
    val isPipActiveOrPending: () -> Boolean,
)

/** One 收藏 action of this Home instance, fixed with the session it was taken in. */
private class DesktopHomeCardFavoriteRequest(val id: Long, val aid: Long, val epoch: Long,
    val owner: DesktopDynamicCacheOwner)

/** One submitted 小窗播放 action waiting for its accepted source. */
private class DesktopHomeCardMiniRequest(val id: Long, val card: Any, val epoch: Long,
    val intent: DesktopHomeCardMiniIntent<PlaylistSession>,
    val waiting: DesktopHomeCardMiniResult.Waiting<PlaylistSession>)

/** The per-Home-instance port. Requests live only in this host; leaving it drops them. */
private class DesktopHomeCardActionsPort : DesktopHomeCardActions {
    var nextId = 0L
    var favoriteRequest by mutableStateOf<DesktopHomeCardFavoriteRequest?>(null)
    var miniRequest by mutableStateOf<DesktopHomeCardMiniRequest?>(null)
    var onFavorite: (DesktopHomeCardTarget) -> Unit = {}
    var onMini: (DesktopHomeCardTarget, Any) -> Unit = { _, _ -> }
    override fun favorite(target: DesktopHomeCardTarget) = onFavorite(target)
    override fun mini(target: DesktopHomeCardTarget, card: Any) = onMini(target, card)
    override fun cardLeft(card: Any) { if (miniRequest?.card === card) miniRequest = null }
}

/** Wraps the real mounted Home page. The local is always provided (the content keeps one composition shape), and
 * is non-null only for the active Home of a Root with services while card interactions are on. */
@Composable
internal fun DesktopHomeCardActionsHost(services: DesktopHomeCardActionServices?, active: Boolean,
    content: @Composable () -> Unit) {
    val port = remember { DesktopHomeCardActionsPort() }
    val enabled = services != null && active && desktopCardInteractionsOn()
    CompositionLocalProvider(LocalDesktopHomeCardActions provides if (enabled) port else null) { content() }
    if (services != null) DesktopHomeCardActionsEffects(services, enabled, port)
}

@Composable
private fun DesktopHomeCardActionsEffects(services: DesktopHomeCardActionServices, enabled: Boolean,
    port: DesktopHomeCardActionsPort) {
    val repository = services.repository
    val epoch by repository.sessionEpochFlow.collectAsState()
    val shell = LocalDesktopOriginalVideoShellOwner.current
    val platforms = rememberUpdatedState(LocalDesktopOriginalVideoRootPlatforms.current)
    // The Root mount builds its services value on each recomposition: the actors are read through the latest one,
    // so a recomposition never rebuilds the playback adapter under a waiting request.
    val latestServices by rememberUpdatedState(services)
    val playback = remember(shell) { DesktopHomeCardRootMiniPlayback(shell) { latestServices } }
    val latestEnabled by rememberUpdatedState(enabled)
    SideEffect {
        port.onFavorite = favorite@{ target ->
            if (!latestEnabled || target.aid <= 0L) return@favorite
            val owner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
            // Not logged in: only the Root login. The action is not replayed after it.
            if (repository.account.value == null || owner == null) { services.login(); return@favorite }
            port.favoriteRequest = DesktopHomeCardFavoriteRequest(++port.nextId, target.aid, repository.sessionEpoch, owner)
        }
        port.onMini = mini@{ target, card ->
            if (!latestEnabled) return@mini
            port.miniRequest = null // A new action replaces (withdraws) the earlier wait.
            val intent = DesktopHomeCardMiniIntent(target, playback)
            when (val result = intent.start(services.navigationAdmission)) {
                is DesktopHomeCardMiniResult.Rejected -> services.feedback(result.message)
                is DesktopHomeCardMiniResult.Waiting<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val waiting = result as DesktopHomeCardMiniResult.Waiting<PlaylistSession>
                    port.miniRequest = DesktopHomeCardMiniRequest(++port.nextId, card, repository.sessionEpoch, intent, waiting)
                }
                else -> Unit
            }
        }
    }
    // Favourite drawer: off, leaving Home or another session closes it before any write.
    val favorite = port.favoriteRequest
    if (favorite != null && (!enabled || favorite.epoch != epoch)) SideEffect {
        if (port.favoriteRequest === favorite) port.favoriteRequest = null
    }
    if (favorite != null && enabled && favorite.epoch == epoch) key(favorite.id, favorite.aid, favorite.epoch, favorite.owner) {
        DesktopHomeCardFavoriteDrawer(favorite, services) {
            if (port.favoriteRequest === favorite) port.favoriteRequest = null
        }
    }
    // Mini wait: re-checked on each Root state change, no polling. Off, leaving Home, another session, another
    // action or the card leaving only withdraw the wait.
    val mini = port.miniRequest
    val changes = remember(shell) { desktopHomeCardMiniChanges(shell, snapshotFlow { platforms.value }) }
    LaunchedEffect(mini, enabled, epoch) {
        if (mini == null) return@LaunchedEffect
        if (!enabled || mini.epoch != epoch) { if (port.miniRequest === mini) port.miniRequest = null; return@LaunchedEffect }
        val outcome = mini.intent.await(mini.waiting, changes)
        if (port.miniRequest !== mini) return@LaunchedEffect
        port.miniRequest = null
        if (outcome is DesktopHomeCardMiniResult.Rejected) latestServices.feedback(outcome.message)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun desktopHomeCardMiniChanges(shell: DesktopOriginalVideoShellOwner,
    platforms: Flow<DesktopOriginalVideoRootWindowPlatforms?>): Flow<Unit> = merge(
    shell.slot.assemblies.flatMapLatest { assembly ->
        val owned = assembly?.takeIf { it.owns() }
        val parts = owned?.let { runCatching { listOf(it.playback.uiState, it.section.nativePlayer.state) }.getOrNull() }
        if (parts == null) flowOf(Unit) else merge(*parts.map { flow -> flow.map { } }.toTypedArray())
    },
    shell.playlist.uiState.map { }, shell.playlist.externalPlaylistSource.map { }, platforms.map { },
)

/** The original cloud favourite folder drawer for one Home request, in the pattern of DesktopVideoFavoriteRoot:
 * membership comes only from the drawer's own getFavoriteFolders; it never calls a one-tap favourite. */
@Composable
private fun DesktopHomeCardFavoriteDrawer(request: DesktopHomeCardFavoriteRequest,
    services: DesktopHomeCardActionServices, closed: () -> Unit) {
    val repository = services.repository
    val brandEvents = LocalDesktopBrandSuccessEvents.current
    val viewport = DesktopWindowConfiguration.current
    val latestFeedback by rememberUpdatedState(services.feedback)
    val latestClosed by rememberUpdatedState(closed)
    val alive = remember { AtomicBoolean(true) }
    val parent = rememberCoroutineScope()
    val scope = remember { CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])) }
    val guard = repository.dynamicCacheSessionGuard
    fun owned() = alive.get() && scope.isActive && repository.sessionEpoch == request.epoch &&
        guard.dynamicCacheOwner() == request.owner
    fun commit(action: () -> Unit) {
        if (!owned() || !guard.withCurrentDynamicCacheOwner(request.owner) {
                if (!owned()) throw CancellationException("Home favorite owner retired")
                action()
            }) throw CancellationException("Home favorite owner retired")
    }
    fun notify(message: String) { commit { latestFeedback(message) } }
    val environment = remember {
        DesktopFavoriteEnvironment.forFolderDrawer(scope, services.favoriteApi, ::owned,
            repository::requireCsrf, { repository.account.value?.mid }, ::notify).also { env ->
                env.mountBrandFeedback(brandEvents) { action ->
                    try { commit(action); true } catch (_: CancellationException) { false }
                }
            }
    }
    val protocol = remember {
        DesktopOriginalFavoriteFolderProtocol(environment.api, environment::currentMid,
            environment::csrf, environment::assertOwned)
    }
    val folderEnvironment = remember {
        DesktopFavoriteFolderEnvironment(scope, ::owned, { request.aid }, { 0 },
            protocol::getFavoriteFolders, protocol::updateFavoriteFolders,
            environment.actions::createFavFolder, { }, { _, _ -> }, ::notify).also {
                it.mountBrandFeedback(brandEvents, environment::captureBrandFeedback)
            }
    }
    val session = remember { DesktopOriginalFavoriteFolderSession(folderEnvironment) }
    // The drawer closed (saved, dismissed or never admitted): the request ends with it.
    LaunchedEffect(session) {
        try {
            if (owned()) {
                session.showFavoriteFolderDialog(request.aid)
                session.favoriteFolderDialogVisible.first { !it }
            }
        } finally { if (alive.get()) latestClosed() }
    }
    DisposableEffect(session) {
        onDispose { alive.set(false); session.close(); scope.cancel() }
    }
    CompositionLocalProvider(LocalDesktopFavoriteFolderViewport provides
        DesktopFavoriteFolderViewport(viewport.screenHeightDp)) {
        DesktopOriginalFavoriteFolderDrawer(session)
    }
}

/** The single Shell owner's ordinary player, read for one Home mini action. */
internal class DesktopHomeCardRootMiniPlayback(
    private val shell: DesktopOriginalVideoShellOwner,
    private val services: () -> DesktopHomeCardActionServices,
) : DesktopHomeCardMiniPlayback<PlaylistSession> {
    private fun assembly() = shell.slot.currentAssembly()?.takeIf { it.owns() }
    override fun pipActiveOrPending() = services().isPipActiveOrPending()
    override fun captureSession(): PlaylistSession? {
        if (!shell.navigationReady() || assembly() == null) return null
        return runCatching { shell.playlist.captureSession() }.getOrNull()
    }
    override fun isSessionCurrent(session: PlaylistSession) =
        runCatching { shell.playlist.isSessionCurrent(session) }.getOrDefault(false)
    override fun currentItem() = runCatching { shell.playlist.getCurrentItem() }.getOrNull()?.let { it.bvid to it.cid }
    override fun sourceIsNone() = shell.playlist.externalPlaylistSource.value == ExternalPlaylistSource.NONE
    override fun accepted() = assembly()?.native?.current()?.let {
        DesktopHomeCardMiniAccepted(it.request.bvid, it.request.cid, it.sourceVersion)
    }
    override fun sourceVersion() = assembly()?.let { runCatching { it.section.nativePlayer.currentSourceVersion }.getOrNull() }
    override fun excludeOtherMedia(target: DesktopHomeCardTarget) = services().beforeNavigationCommit(BiliPaiNavKey.Home,
        BiliPaiNavKey.VideoDetail(target.bvid, target.cid, target.card.cover, fullscreen = false, sourceRoute = "home"))
    override fun open(card: VideoCard) = shell.playback.openVideoDetail(card, 0L, true)
    override fun loadFailed(bvid: String): Boolean {
        val a = assembly() ?: return false
        if (a.playback.uiState.value !is VideoPlaybackUiState.Error) return false
        return runCatching { a.captureLoadState().currentRequest?.bvid }.getOrNull() == bvid
    }
    override fun enterMini(accepted: DesktopHomeCardMiniAccepted): Boolean {
        val a = assembly() ?: return false
        val expected = a.native.current() ?: return false
        if (expected.request.bvid != accepted.bvid || expected.request.cid != accepted.cid ||
            expected.sourceVersion != accepted.sourceVersion) return false
        val success = (a.playback.uiState.value as? VideoPlaybackUiState.Success)?.takeIf {
            it.info.bvid == expected.request.bvid && it.info.cid == expected.request.cid
        } ?: return false
        val mini = shell.windowPlatformsFor(a)?.holder?.mini ?: return false
        val factory = runCatching { shell.factoryFor(a) }.getOrNull() ?: return false
        return factory.withPresentationAdmission(a, expected) {
            val info = success.info
            mini.setVideoInfo(info.bvid, info.title, info.pic, info.owner.name, info.cid, info.aid, a.section)
            mini.cacheUiState(success)
            mini.enterMiniMode(false)
        }
    }
}
