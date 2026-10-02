exec((__import__('pathlib').Path(__file__).resolve().parent/'prepare.py').read_text(encoding='utf8'))

# Full original feed VM and screen. All existing schemas/policies/Pager/renderers
# remain borrowed; these two original declarations were not previously emitted.
def original(rel):
 t=read(C/rel);write(P/'original'/rel,t);return t
uipath='app/src/main/java/com/android/purebilibili/feature/story/StoryScreen.kt'
vmpath='app/src/main/java/com/android/purebilibili/feature/story/StoryViewModel.kt'
storyvm=original(vmpath)
start=storyvm.index('class StoryViewModel(')
body=storyvm[start:]
body=body.replace('class StoryViewModel(application: Application) : AndroidViewModel(application) {','internal class StoryViewModel(private val environment: com.bilipai.desktop.ui.DesktopOriginalStoryEnvironment) {\n    private val viewModelScope get() = environment.scope\n    private val VideoRepository get() = environment.requests\n    private fun <T> MutableStateFlow(initial:T): MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)')
body=body.replace('val result = VideoRepository.getHomeVideos(', 'environment.assertCurrent()\n            val result = VideoRepository.getHomeVideos(')
body=body.replace('            result.onSuccess', '            environment.assertCurrent()\n            result.onSuccess')
storyvm='''package com.android.purebilibili.feature.story
import android.util.Log as Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
'''+body
write(P/'prepared/generated/com/android/purebilibili/feature/story/StoryViewModel.kt',storyvm)

story=original(uipath)
edits=[('import androidx.compose.ui.platform.LocalContext\n',''),('import androidx.lifecycle.viewmodel.compose.viewModel\n',''),('import androidx.media3.common.util.UnstableApi\n',''),('@UnstableApi\n',''),('fun StoryScreen(','internal fun StoryScreen('),('viewModel: StoryViewModel = viewModel(),','viewModel: StoryViewModel,'),('playerViewModel: VideoPlaybackViewModel = viewModel(),','playerViewModel: VideoPlaybackViewModel,'),('engagementViewModel: VideoEngagementViewModel = viewModel(),','engagementViewModel: VideoEngagementViewModel,'),('onVideoClick: (String, Long, String) -> Unit = { _, _, _ -> },','onVideoClick: (String, Long, String) -> Unit,'),('onUserClick: (Long) -> Unit = {},','onUserClick: (Long) -> Unit,'),('onSearchClick: () -> Unit = {},','onSearchClick: () -> Unit,'),('onRotateToLandscape: () -> Unit = {}','onRotateToLandscape: () -> Unit'),('    val context = LocalContext.current','    val platform = com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform.current\n    val context = platform.section.settingsContext'),('com.android.purebilibili.core.store.SettingsManager','com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings'),('engagementViewModel.initWithContext(context)','engagementViewModel.initWithContext(context.pluginContext)'),('viewModel = playerViewModel,','viewModel = com.bilipai.desktop.ui.LocalDesktopOriginalStoryPlaybackView.current,')]
for before,after in edits:assert story.count(before)==1,(before,story.count(before));story=story.replace(before,after)
write(P/'prepared/generated/com/android/purebilibili/feature/story/StoryScreen.kt',story)

manual='''package com.bilipai.desktop.ui

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
                    CoinDialog(visible=engagement.coinDialogVisible, currentCoinCount=engagement.coinCount,
                        userBalance=engagement.userCoinBalance,
                        onDismiss={ assembly.domains.engagement.setCoinDialogVisible(false) },
                        onConfirm=assembly.domains.engagement::doCoin)
                    val favoriteVisible by assembly.playback.favoriteFolderDialogVisible.collectAsState()
                    VideoDetailFavoriteFolderOverlayAdapter(favoriteVisible, assembly.playback)
                    VideoDetailFollowGroupDialog(assembly.playback)
                }
            }
        }
    }
}
'''
write(P/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalStoryRoot.kt',manual)
write(P/'story-adaptations.json',json.dumps(dict(screen=dict(originalPath=uipath,sha256LF=sha(original(uipath)),edits=[dict(before=b,after=a)for b,a in edits]),vm=dict(originalPath=vmpath,sha256LF=sha(original(vmpath)),selected='complete class StoryViewModel; existing sole StoryUiState reused')),ensure_ascii=False,indent=2)+'\n')
print('Full original Story UI/feed VM prepared; borrowed compiled Pager/domains, not legacy Story controller.')

change('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoHolderRoute.kt',[
 ('required-retained-original-feed','    val portrait: DesktopOriginalPortraitPlatform\n','    val portrait: DesktopOriginalPortraitPlatform\n    val storyFeeds: DesktopOriginalStoryFeedOwners\n'),
])
change('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootWindowPlatforms.kt',[
 ('retain-feed-with-real-assembly','    private val closed = AtomicBoolean()\n','    private val closed = AtomicBoolean()\n    override val storyFeeds = DesktopOriginalStoryFeedOwners(assembly, portrait)\n'),
 ('cancel-only-after-assembly-drain','        subtitleMode.close()\n','        storyFeeds.close()\n        subtitleMode.close()\n'),
])
change('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalRootStack.kt',[
 ('retained-original-story-stack-keys','    val root = routes.root\n','    val root = routes.root\n    val storyFeeds = LocalDesktopOriginalVideoRootPlatforms.current?.storyFeeds\n    val retainedStoryKeys = routes.stack.filterIsInstance<BiliPaiNavKey.Story>().toSet()\n    SideEffect { storyFeeds?.reconcile(retainedStoryKeys) }\n'),
])
shell=read(C/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
begin=shell.index('                            section == DesktopSection.STORY -> DesktopStoryScreen(')
end=shell.index('                            section == DesktopSection.TOPIC ->',begin)
anchor=shell[begin:end]
after='''                            entryKey is BiliPaiNavKey.Story ->
                                DesktopOriginalStoryPhysicalLeaf(entryKey, ordinaryVideo, commands, active && !activatingUpdate,
                                    pendingOwner = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            ordinaryVideoResourceError?.let { Text(it) } ?: CircularProgressIndicator()
                                        }
                                    })
'''
assert shell.count(anchor)==1
# Shell is an exact Root hunk only, not a huge redundant compile override.
H.append(dict(path='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',name='actual-original-story-consumer',
    before=anchor,after=after,beforeSha256LF=sha(anchor),afterSha256LF=sha(after)))
write(P/'exact-hunks.json',json.dumps(H,ensure_ascii=False,indent=2)+'\n')
