package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.video.share.VideoSharePayload
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import java.util.Comparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Shared real desktop settings, plugin store and lifecycle; no account or transport. */
internal class DesktopPageTestFixture : AutoCloseable {
    val root = Files.createTempDirectory("bp-page-test-")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pluginContext = DesktopPluginContext(DesktopPluginStore(root.resolve("global")))
    val lifecycleOwner = PageTestLifecycleOwner()
    val home = DesktopHomeEnvironment(
        DesktopOriginalHomePreferences.create(pluginContext.store, scope, false, { false }),
        pluginContext, lifecycleOwner, MutableStateFlow(false), UnusedAnalytics,
        { DesktopOriginalHomeGlobalNamespace(pluginContext.store, it) }, MutableStateFlow(null),
        { _, _ -> unsupported("loadWallpaperPalette") }, { unsupported("ensureEdgeToEdge") },
        { _, _ -> unsupported("applyHomeSystemBars") }, UnusedPages,
        { _, _ -> unsupported("saveCover") }, { unsupported("feedback") }, UnusedOverlays,
    )

    suspend fun shutdown() {
        lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
        scope.coroutineContext[Job]!!.cancelAndJoin()
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
    override fun close() = runBlocking { shutdown() }
}

internal class PageTestLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}

private fun unsupported(name: String): Nothing = error("DesktopPageTestFixture unsupported port: $name")

private object UnusedAnalytics : DesktopHomeAnalyticsPort {
    override fun logScreenView(name: String): Unit = unsupported("logScreenView")
    override fun logCategoryView(categoryName: String, categoryId: Int): Unit = unsupported("logCategoryView")
    override fun logHomeReturnAnimationPerformance(actualDurationMs: Long, plannedSuppressionMs: Long, sharedTransitionEnabled: Boolean, sharedTransitionReady: Boolean, isQuickReturn: Boolean, isTabletLayout: Boolean, cardAnimationEnabled: Boolean, builtinPluginEnabledCount: Int, playerPluginEnabledCount: Int, feedPluginEnabledCount: Int, danmakuPluginEnabledCount: Int, jsonPluginEnabledCount: Int, jsonFeedPluginEnabledCount: Int, jsonDanmakuPluginEnabledCount: Int): Unit = unsupported("logHomeReturnAnimationPerformance")
}

private object UnusedPages : DesktopHomeEmbeddedPages {
    @Composable override fun PartitionContent(contentPadding: PaddingValues, onVideoClick: (VideoItem) -> Unit, onBangumiClick: (Int) -> Unit, scrollToTopRequestId: Int): Unit = unsupported("PartitionContent")
    @Composable override fun SubscriptionFeedPage(contentPadding: PaddingValues, articleContentPadding: PaddingValues, scrollToTopRequestId: Int, listState: LazyStaggeredGridState, gridColumns: Int, pinchEnabled: Boolean, pinchBounds: IntRange, onColumnsChange: (Int) -> Unit, onPinchEnd: (Int) -> Unit, onArticleOpenChanged: (Boolean) -> Unit, onOpenPluginSettings: () -> Unit): Unit = unsupported("SubscriptionFeedPage")
    @Composable override fun LiveListScreen(onBack: () -> Unit, onLiveClick: (Long, String, String) -> Unit, onSearchClick: () -> Unit, onAreaListClick: () -> Unit, onFollowingClick: () -> Unit, onAreaDetailClick: (Int, Int, String) -> Unit, showNavigationBack: Boolean, embeddedInHome: Boolean, contentTopPadding: Dp, scrollToTopRequestId: Int): Unit = unsupported("LiveListScreen")
    @Composable override fun HomeBangumiTabPage(contentPadding: PaddingValues, onBangumiClick: (Long) -> Unit, onBangumiEpisodeClick: (Long, Long) -> Unit, scrollToTopRequestId: Int): Unit = unsupported("HomeBangumiTabPage")
}

private object UnusedOverlays : DesktopHomeOverlayPorts {
    @Composable override fun VideoShareSheetHost(payload: VideoSharePayload?, onDismiss: () -> Unit): Unit = unsupported("VideoShareSheetHost")
    @Composable override fun CrashTrackingConsentDialog(onDismiss: () -> Unit): Unit = unsupported("CrashTrackingConsentDialog")
}
