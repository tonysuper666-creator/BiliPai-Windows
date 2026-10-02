package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Required projections of the already mounted Root owners. No HTTP client,
 * account cache, progress manager, settings Store or native player is created. */
internal class DesktopOriginalBangumiPagesEnvironment(
    val hub: BangumiHubViewModel,
    val requests: DesktopOriginalBangumiPagesRequests,
    val reviews: DesktopOriginalBangumiReviewRequests,
    val progress: DesktopOriginalVideoProgressPort,
    val scope: CoroutineScope,
    val commitIfCurrent: (() -> Unit) -> Boolean,
    val owns: () -> Boolean,
    val feedback: (String) -> Unit,
    val hazeEffectSupported: Boolean,
)

internal val LocalDesktopOriginalBangumiPagesEnvironment =
    staticCompositionLocalOf<DesktopOriginalBangumiPagesEnvironment> {
        error("Original independent Bangumi page requires its actual retained entry and Root owners")
    }

/** Preserve original result/error bodies while keeping caller cancellation and
 * route retirement outside IO. runCatching in the original body cannot turn a
 * retired caller into a successful UI update. */
internal suspend fun <T> ownedBangumiRequest(
    context: CoroutineContext,
    owned: () -> Boolean,
    block: suspend CoroutineScope.() -> T,
): T = withContext(context) {
    ensureActive()
    if (!owned()) throw CancellationException("Original Bangumi entry retired")
    val result = block()
    ensureActive()
    if (!owned()) throw CancellationException("Original Bangumi entry retired")
    result
}

/** Android's Toast presentation boundary uses the existing Root feedback port. */
internal object DesktopOriginalBangumiPagesToast {
    const val LENGTH_SHORT = 0
    fun makeText(environment: DesktopOriginalBangumiPagesEnvironment, message: String, duration: Int): Message {
        require(duration == LENGTH_SHORT)
        return Message(environment, message)
    }
    class Message(private val environment: DesktopOriginalBangumiPagesEnvironment, private val message: String) {
        fun show() { environment.commitIfCurrent { if (environment.owns()) environment.feedback(message) } }
    }
}

/** Each real original navigation entry owns its original page VMs. The Hub's
 * stateless protocol binding and all global owners come from the same Root. */
@Composable internal fun DesktopOriginalBangumiPagesRootHost(
    entryKey: BiliPaiNavKey,
    routes: DesktopOriginalRootRouteAssembly,
    repository: DesktopRepository,
    appResources: DesktopOriginalVideoAppResources?,
    active: Boolean,
    replaceSeason: (BiliPaiNavKey.BangumiDetail, Long) -> Unit,
) {
    require(entryKey is BiliPaiNavKey.Bangumi || entryKey is BiliPaiNavKey.BangumiDetail ||
        entryKey is BiliPaiNavKey.BangumiReview)
    val home = LocalDesktopHomeEnvironment.current
    val aggregate = home.pages as? DesktopOriginalHomeEmbeddedAggregate
        ?: error("Independent original Bangumi requires the actual Root Home aggregate")
    val gate = routes.root.entry.gate
    val activeState = rememberUpdatedState(active)
    // Detail progress reads must borrow the actual global original progress
    // owner. Waiting for its real initialization avoids constructing a second.
    if (appResources == null) {
        com.android.purebilibili.core.ui.components.AppText("正在准备番剧页面…")
        return
    }
    androidx.compose.runtime.key(entryKey, aggregate, gate, appResources) {
        val open = remember { AtomicBoolean(true) }
        val scope = remember {
            CoroutineScope(gate.scope.coroutineContext + SupervisorJob(gate.scope.coroutineContext[Job]))
        }
        fun owns() = open.get() && scope.isActive && gate.owns() &&
            repository.sessionEpoch == gate.epoch && routes.containsEntry(entryKey) && appResources.isActive()
        fun acts() = owns() && activeState.value && routes.currentKey == entryKey
        fun commit(action: () -> Unit): Boolean {
            var applied = false
            return gate.commit { if (owns()) { action(); applied = true } } && applied
        }
        fun navigate(action: () -> Unit) { if (acts()) routes.callbackFor(entryKey, action) }
        val environment = remember(scope, appResources) {
            val hubRepository = aggregate.bangumiEnvironment.repository
            val hubEnvironment = DesktopBangumiHubEnvironment(hubRepository, scope,
                { owns() && gate.mid != null }, ::owns, ::commit)
            val hub = BangumiHubViewModel(hubEnvironment)
            val api = repository.ownedHomeService(BangumiApi::class.java, "https://api.bilibili.com/",
                gate.epoch, ::owns)
            val csrf = { repository.ownedHomeCookie("bili_jct", gate.epoch, ::owns) }
            val sessionCookie = { repository.ownedHomeCookie("SESSDATA", gate.epoch, ::owns) }
            DesktopOriginalBangumiPagesEnvironment(hub,
                DesktopOriginalBangumiPagesRequests(api, hubRepository, csrf, sessionCookie, ::owns, ::acts),
                DesktopOriginalBangumiReviewRequests(api, csrf, ::owns, ::acts),
                // This page only reads cached progress; the zero duration
                // function is never invoked because it performs no saves.
                appResources.progress.forEntry({ _, _ -> 0L }, ::owns),
                scope, ::commit, ::owns,
                { message -> if (acts()) home.feedback(message) },
                // Desktop does not have Android's RenderEffect-backed haze.
                hazeEffectSupported = false)
        }
        val detail = remember(environment) {
            if (entryKey is BiliPaiNavKey.BangumiDetail) BangumiViewModel(environment) else null
        }
        LaunchedEffect(scope, routes, entryKey) {
            snapshotFlow { routes.containsEntry(entryKey) }.collect { retained ->
                if (!retained) scope.cancel("Original independent Bangumi NavEntry removed")
            }
        }
        DisposableEffect(scope) { onDispose { open.set(false); scope.cancel() } }
        if (owns()) CompositionLocalProvider(LocalDesktopOriginalBangumiPagesEnvironment provides environment) {
            when (entryKey) {
                is BiliPaiNavKey.Bangumi -> BangumiScreen(
                    onBack = { navigate { routes.back() } },
                    onBangumiClick = { season -> navigate { routes.push(BiliPaiNavKey.BangumiDetail(seasonId = season)) } },
                    onBangumiEpisodeClick = { season, episode -> navigate { routes.push(BiliPaiNavKey.BangumiDetail(seasonId = season, epId = episode)) } },
                    initialType = entryKey.initialType, viewModel = environment.hub)
                is BiliPaiNavKey.BangumiDetail -> BangumiDetailScreen(
                    seasonId = entryKey.seasonId, epId = entryKey.epId, mediaId = entryKey.mediaId,
                    onBack = { navigate { routes.back() } },
                    onEpisodeClick = { actionSeason, episode -> navigate {
                        routes.push(BiliPaiNavKey.BangumiPlayer(seasonId = actionSeason, epId = episode.id,
                            preferredAid = episode.aid,
                            isCourse = episode.from == "pugv" || episode.playable || episode.episodeCanView))
                    } },
                    onSeasonClick = { season -> navigate { replaceSeason(entryKey, season) } },
                    onReviewsClick = { media, title -> if (media > 0L) navigate { routes.push(BiliPaiNavKey.BangumiReview(mediaId = media, title = title)) } },
                    onUserClick = { mid -> navigate { routes.push(BiliPaiNavKey.Space(mid)) } },
                    viewModel = requireNotNull(detail))
                is BiliPaiNavKey.BangumiReview -> BangumiReviewScreen(
                    mediaId = entryKey.mediaId, title = entryKey.title,
                    onBack = { navigate { routes.back() } },
                    onOpenWeb = { url, title -> navigate { routes.push(BiliPaiNavKey.Web(url, title)) } })
                else -> error("Unexpected original Bangumi page key")
            }
        }
    }
}
