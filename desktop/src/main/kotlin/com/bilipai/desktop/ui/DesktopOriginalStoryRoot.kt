package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.story.*
import com.android.purebilibili.feature.video.ui.components.CoinDialog
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.*

internal class DesktopOriginalStoryEnvironment(
    val scope: CoroutineScope,
    val requests: DesktopOriginalPortraitRequests,
    private val isCurrent: () -> Boolean,
    private val admission: ((() -> Unit) -> Boolean),
) {
    fun assertCurrent() { scope.coroutineContext[Job]!!.ensureActive(); if (!isCurrent()) throw CancellationException("Story entry retired") }
    fun commit(action: () -> Unit): Boolean = isCurrent() && admission { assertCurrent(); action() }
}

internal val LocalDesktopOriginalStoryPlaybackView = staticCompositionLocalOf<DesktopOriginalPortraitPlaybackOwner> {
    error("Story requires the same original video owner view")
}

/** Retain ONLY original Story feed VMs in the existing Assembly scope. There is
 * no list DTO/cache/client/player or additional video VM here. The Root publishes
 * its real stack keys via reconcile(), including covered retained entries.
 */
internal class DesktopOriginalStoryFeedOwners(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val platform: DesktopOriginalPortraitPlatform,
) : AutoCloseable {
    private class Entry(val scope: CoroutineScope, val vm: StoryViewModel)
    private val entries = linkedMapOf<BiliPaiNavKey.Story, Entry>()
    private var closed = false
    @Synchronized fun get(key: BiliPaiNavKey.Story): StoryViewModel {
        check(!closed && assembly.owns())
        return entries.getOrPut(key) {
            val job = SupervisorJob(assembly.environment.scope.coroutineContext[Job])
            val scope = CoroutineScope(assembly.environment.scope.coroutineContext + job)
            val environment = DesktopOriginalStoryEnvironment(scope, platform.requests,
                { !closed && assembly.owns() && job.isActive }, assembly.environment::commit)
            Entry(scope, StoryViewModel(environment))
        }.vm
    }
    @Synchronized fun reconcile(retained: Set<BiliPaiNavKey.Story>) {
        val gone = entries.keys.filter { it !in retained }
        gone.forEach { entries.remove(it)?.scope?.cancel() }
    }
    @Synchronized fun find(key: BiliPaiNavKey.Story): StoryViewModel? = entries[key]?.vm
    @Synchronized override fun close() { closed = true; entries.values.forEach { it.scope.cancel() }; entries.clear() }
}

/** Same actual Section/carrier and all original interaction domains. The outer
 * portal includes dialogs, so no main-tree content hides behind the native HWND.
 */
@Composable internal fun DesktopOriginalStoryPhysicalLeaf(
    key: BiliPaiNavKey.Story,
    shell: DesktopOriginalVideoShellOwner,
    commands: DesktopOriginalRootRouteCommands,
    active: Boolean,
    pendingOwner: @Composable () -> Unit,
) {
    val ready by shell.slot.factoryReady.collectAsState()
    LaunchedEffect(shell, ready, key) { if (ready) shell.slot.requireAssembly() }
    val owner by shell.slot.assemblies.collectAsState()
    val platforms = LocalDesktopOriginalVideoRootPlatforms.current
    val assembly = owner?.takeIf { it.owns() }
    if (assembly == null || platforms == null) { pendingOwner(); return }
    val routes = commands as DesktopOriginalRootRouteAssembly
    fun currentRoute() = active && routes.owns() && routes.currentKey == key &&
        shell.slot.currentAssembly() === assembly && assembly.owns()
    val feeds = platforms.storyFeeds
    val feed = remember(feeds, key, active) { if (active) feeds.get(key) else feeds.find(key) } ?: return
    var initialized by remember(assembly, platforms) { mutableStateOf(false) }
    LaunchedEffect(assembly, platforms) {
        platforms.awaitNativeInitialization(); if (!assembly.owns()) throw CancellationException("Story initial surface retired")
        initialized = true
    }
    CompositionLocalProvider(LocalDesktopOriginalVideoNativeCarrierActive provides active) {
        if (!initialized) { platforms.InitialNativeSurface(Modifier.fillMaxSize()); return@CompositionLocalProvider }
        CompositionLocalProvider(
            LocalDesktopOriginalPortraitPlatform provides platforms.portrait,
            LocalDesktopOriginalVideoHolderPlatform provides platforms.holder,
            LocalDesktopOriginalPlayerSettingsContext provides platforms.holder.settingsContext,
            LocalDesktopCommentBindings provides platforms.holder.commentsPlatform,
            LocalDesktopOriginalStoryPlaybackView provides remember(assembly) { DesktopOriginalVideoConsumedViews(assembly) },
        ) {
            platforms.portrait.section.RenderPlayerForeground {
                Box(Modifier.fillMaxSize()) {
                    StoryScreen(seedBvid=key.seedBvid, seedCid=key.seedCid, seedCover=key.seedCover,
                        seedTitle=key.seedTitle, sourceRoute=key.sourceRoute,
                        viewModel=feed, playerViewModel=assembly.playback, engagementViewModel=assembly.domains.engagement,
                        isActive=active, onBack={ if(active) commands.back() },
                        onVideoClick={ bvid,cid,cover -> if(active) commands.video(BiliPaiNavKey.VideoDetail(bvid,cid,cover,sourceRoute=key.sourceRoute ?: "story")) },
                        onUserClick={ mid -> if(active) commands.push(BiliPaiNavKey.Space(mid)) },
                        onSearchClick={ if(active) commands.push(BiliPaiNavKey.Search()) },
                        onRotateToLandscape={ if(active) platforms.holder.window.presentation.requestOrientation(
                            DesktopOriginalVideoOrientationRequest.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, null) })
                    val engagement by assembly.domains.engagement.uiState.collectAsState()
                    val favoriteVisible by assembly.playback.favoriteFolderDialogVisible.collectAsState()
                    val dialogSource = assembly.native.current()
                    fun admitDialog(action: () -> Unit): Boolean {
                        val expected = dialogSource ?: return false
                        var applied = false
                        return assembly.environment.commit {
                            if (currentRoute() && assembly.native.isCurrent(expected)) {
                                action(); applied = true
                            }
                        } && applied
                    }
                    if (currentRoute() && dialogSource != null) {
                        CoinDialog(visible=engagement.coinDialogVisible, currentCoinCount=engagement.coinCount,
                            userBalance=engagement.userCoinBalance, maxCoins=engagement.coinLimit,
                            onDismiss={ admitDialog { assembly.domains.engagement.setCoinDialogVisible(false) } },
                            onConfirm={ count,alsoLike -> admitDialog { assembly.domains.engagement.doCoin(count,alsoLike) }; Unit })
                        VideoDetailFavoriteFolderOverlayAdapter(favoriteVisible, assembly.playback, ::admitDialog)
                        VideoDetailFollowGroupDialog(assembly.playback, ::admitDialog)
                    }
                }
            }
        }
    }
}
