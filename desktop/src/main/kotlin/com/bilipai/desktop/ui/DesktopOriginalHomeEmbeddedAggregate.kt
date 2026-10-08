package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.DesktopOriginalBangumiHubRepository
import com.android.purebilibili.feature.bangumi.BangumiHubViewModel
import com.android.purebilibili.feature.partition.PartitionFeedViewModel
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.awt.Component

/** Mandatory genuine Root UI ports absent from the original HomeEmbeddedPages parameters.
 * Nullable channel/haze reflect the original supported absence, not an invented callback. */
internal class DesktopHomeEmbeddedRootUi(
    val onMatchClick: () -> Unit,
    val liveScrollToTopChannel: Channel<Unit>?,
    val globalHazeState: () -> HazeState?,
)

/** One four-page aggregate constructed BEFORE Home composition in the retained factory.
 * Every original VM/binding is stable while video/Favorites/audio temporarily covers Home.
 * No flat Desktop browser, replacement DTO, page algorithm, API client or store is created. */
internal class DesktopOriginalHomeEmbeddedAggregate private constructor(
    private val runtime: DesktopPluginRuntime,
    private val settings: DesktopHomeSettingsPort,
    internal val lifetime: DesktopHomeEmbeddedLifetime,
    val gallery: DesktopHomeGalleryBindings,
    val partitionEnvironment: DesktopPartitionEnvironment,
    val partition: PartitionFeedViewModel,
    val bangumiEnvironment: DesktopBangumiHubEnvironment,
    val bangumi: BangumiHubViewModel,
    val live: DesktopOriginalLiveListBinding,
    val subscription: DesktopSubscriptionPageBindings,
    private val ui: DesktopHomeEmbeddedRootUi,
) : DesktopHomeEmbeddedRetainedOwner {
    @Composable private fun home(): DesktopHomeEnvironment {
        val value = LocalDesktopHomeEnvironment.current
        check(value.pluginContext.store === runtime.store && value.settings === settings && value.pages === this) {
            "Embedded pages require the same actual Root Home environment/settings/global store"
        }
        return value
    }
    @Composable override fun PartitionContent(contentPadding: PaddingValues, onVideoClick: (VideoItem) -> Unit,
        onBangumiClick: (Int) -> Unit, scrollToTopRequestId: Int) {
        if (!lifetime.owns()) return
        val home = home()
        if (lifetime.owns()) DesktopOriginalPartitionContent(partitionEnvironment, home, partition,
            contentPadding, { item -> lifetime.commit { onVideoClick(item) } },
            { type -> lifetime.commit { onBangumiClick(type) } }, scrollToTopRequestId, ui.globalHazeState())
    }
    @Composable override fun SubscriptionFeedPage(contentPadding: PaddingValues, articleContentPadding: PaddingValues,
        scrollToTopRequestId: Int, listState: LazyStaggeredGridState, gridColumns: Int, pinchEnabled: Boolean,
        pinchBounds: IntRange, onColumnsChange: (Int) -> Unit, onPinchEnd: (Int) -> Unit,
        onArticleOpenChanged: (Boolean) -> Unit, onOpenPluginSettings: () -> Unit) {
        if (!lifetime.owns()) return
        val home = home()
        if (lifetime.owns()) CompositionLocalProvider(LocalDesktopHomeEnvironment provides home) {
            DesktopSubscriptionPageHost(subscription) {
                com.android.purebilibili.feature.home.subscription.SubscriptionFeedPage(
                    contentPadding, articleContentPadding, scrollToTopRequestId, listState, gridColumns,
                    pinchEnabled, pinchBounds, { value -> lifetime.commit { onColumnsChange(value) } },
                    { value -> lifetime.commit { onPinchEnd(value) } },
                    { value -> lifetime.commit { onArticleOpenChanged(value) } },
                    { lifetime.commit(onOpenPluginSettings) })
            }
        }
    }
    @Composable override fun LiveListScreen(onBack: () -> Unit, onLiveClick: (Long, String, String) -> Unit,
        onSearchClick: () -> Unit, onAreaListClick: () -> Unit, onFollowingClick: () -> Unit,
        onAreaDetailClick: (Int, Int, String) -> Unit, showNavigationBack: Boolean, embeddedInHome: Boolean,
        contentTopPadding: Dp, scrollToTopRequestId: Int) {
        if (!lifetime.owns()) return
        val home = home()
        if (lifetime.owns()) CompositionLocalProvider(LocalDesktopHomeEnvironment provides home) {
            DesktopOriginalLiveListHost(live.viewModel, live.platform,
                { lifetime.commit(onBack) }, { id, title, cover -> lifetime.commit { onLiveClick(id, title, cover) } },
                { lifetime.commit(onSearchClick) }, { lifetime.commit(onAreaListClick) },
                { lifetime.commit(onFollowingClick) }, { parent, area, title -> lifetime.commit { onAreaDetailClick(parent, area, title) } },
                { lifetime.commit(ui.onMatchClick) }, showNavigationBack, embeddedInHome, contentTopPadding,
                scrollToTopRequestId, ui.liveScrollToTopChannel, ui.globalHazeState())
        }
    }
    @Composable override fun HomeBangumiTabPage(contentPadding: PaddingValues, onBangumiClick: (Long) -> Unit,
        onBangumiEpisodeClick: (Long, Long) -> Unit, scrollToTopRequestId: Int) {
        if (!lifetime.owns()) return
        val home = home()
        if (lifetime.owns()) DesktopOriginalHomeBangumiContent(bangumiEnvironment, home, bangumi, contentPadding,
            { id -> lifetime.commit { onBangumiClick(id) } },
            { season, episode -> lifetime.commit { onBangumiEpisodeClick(season, episode) } }, scrollToTopRequestId)
    }
    override fun close() {
        lifetime.close()
        try { subscription.close() } finally { gallery.close() }
    }
    override suspend fun closeAndJoin() = withContext(NonCancellable) {
        try { close() } finally { lifetime.closeAndJoin() }
    }
    companion object {
        fun create(
            repository: DesktopRepository,
            gate: DesktopHomeRetainedGate,
            requests: DesktopHomeRootRequestBinding,
            runtime: DesktopPluginRuntime,
            sameSettings: DesktopHomeSettingsPort,
            sameCardSession: DesktopDynamicCardSession,
            actualImageLocations: DesktopImageSaveLocations,
            actualRootWindow: Component,
            shareFiles: DesktopVideoShareFiles,
            mediaShare: DesktopImagePreviewMediaShare,
            textShare: DesktopTextShareBindings,
            clipboard: (String) -> Unit,
            externalLink: (String) -> Unit,
            feedback: (String) -> Unit,
            ui: DesktopHomeEmbeddedRootUi,
        ): DesktopOriginalHomeEmbeddedAggregate {
            gate.assertOwned()
            require(requests.capturedEpoch == gate.epoch)
            val lifetime = DesktopHomeEmbeddedLifetime(gate.scope, gate::owns, gate::commit)
            var gallery: DesktopHomeGalleryBindings? = null
            var subscription: DesktopSubscriptionPageBindings? = null
            try {
                // This child environment changes only task admission/scope. All existing protocol
                // services, headers/WBI/buvid/CSRF getters and cookie ownership are identical.
                val source = requests.environment
                val local = DesktopHomeProtocolEnvironment(source.api, source.guestApi, source.messageApi,
                    lifetime.scope, lifetime::owns, lifetime::commit, source.feedApiType, source.refreshCount,
                    source.wbiKeys, source.accessToken, source.csrf, source.buvid3,
                    source.awaitSessionRestored, source.ensureBuvid3FromSpi)
                val images = DesktopHomeGalleryBindings.create(repository, runtime.context, gate, lifetime,
                    sameCardSession, actualImageLocations, actualRootWindow, shareFiles, mediaShare, textShare, clipboard, externalLink, feedback)
                gallery = images
                val partitionEnvironment = DesktopPartitionEnvironment(requests.ports.video,
                    lifetime.scope, lifetime::owns, lifetime::commit)
                val partition = PartitionFeedViewModel(partitionEnvironment)
                val bangumiApi = repository.ownedHomeService(BangumiApi::class.java,
                    "https://api.bilibili.com/", gate.epoch,
                    { lifetime.owns() && requests.isMountedSourceCurrent() })
                val searchApi = repository.ownedHomeService(SearchApi::class.java,
                    "https://api.bilibili.com/", gate.epoch,
                    { lifetime.owns() && requests.isMountedSourceCurrent() })
                val bangumiRepository = DesktopOriginalBangumiHubRepository(bangumiApi, local.api, searchApi,
                    local.csrf, {
                        var mid: Long? = null
                        if (!lifetime.commit { mid = repository.account.value?.mid?.takeIf { it > 0L } })
                            throw CancellationException("Bangumi account owner retired")
                        mid
                    })
                val bangumiEnvironment = DesktopBangumiHubEnvironment(bangumiRepository, lifetime.scope, {
                    var loggedIn = false
                    if (!lifetime.commit { loggedIn = repository.account.value?.mid?.let { it > 0L } == true })
                        throw CancellationException("Bangumi account owner retired")
                    loggedIn
                }, lifetime::owns, lifetime::commit)
                val bangumi = BangumiHubViewModel(bangumiEnvironment)
                val live = DesktopOriginalLiveListBinding(local, object : DesktopLiveListPlatform {
                    override fun isCurrent() = lifetime.owns()
                    override suspend fun saveCover(url: String, title: String) = images.saveImage(url)
                    override fun feedback(message: String) = images.showFeedback(message)
                })
                val subscribed = DesktopSubscriptionPageBindings(runtime, images, lifetime::owns,
                    lifetime::commit, clipboard, feedback, externalLink)
                subscription = subscribed
                lifetime.assertOwned()
                return DesktopOriginalHomeEmbeddedAggregate(runtime, sameSettings, lifetime, images,
                    partitionEnvironment, partition, bangumiEnvironment, bangumi, live, subscribed, ui)
            } catch (failure: Throwable) {
                lifetime.close()
                try { subscription?.close() } finally { gallery?.close() }
                throw failure
            }
        }
    }
}
