package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.feature.watchlater.*
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** Retained under the existing PersonalLists/Home gate, not a second list/account store. */
internal class DesktopWatchLaterEntry(
    private val root: DesktopPersonalListsRoot,
    val key: BiliPaiNavKey,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(root.scope.coroutineContext[Job])
    val scope = CoroutineScope(root.scope.coroutineContext + job)
    lateinit var environment: DesktopWatchLaterEnvironment; private set
    lateinit var viewModel: WatchLaterViewModel; private set
    private var queue: DesktopFavoriteQueueBridge? = null
    fun owns() = !closed.get() && job.isActive && root.owns()
    fun assertOwned() { if (!owns()) throw CancellationException("WatchLater entry retired") }
    fun commit(block: () -> Unit): Boolean = root.gate.commit { if (owns()) block() } && owns()
    fun install(environment: DesktopWatchLaterEnvironment, viewModel: WatchLaterViewModel) {
        check(!this::viewModel.isInitialized); assertOwned()
        this.environment = environment; this.viewModel = viewModel
    }
    fun queueBridge(factory: () -> DesktopFavoriteQueueBridge): DesktopFavoriteQueueBridge {
        assertOwned(); return queue ?: factory().also { queue = it }
    }
    override fun close() { if (closed.compareAndSet(false, true)) { queue?.close(); job.cancel() } }
}

/** Whole original WatchLaterScreen; original active AppNavigation load effect and current
 * typed callback parameters are bound to the same retained VM/queue/physical stack. */
@Composable internal fun DesktopOriginalWatchLaterHost(
    entry: DesktopWatchLaterEntry,
    bindings: DesktopWatchLaterBindings,
    navigation: DesktopPersonalListNavigation,
    onBack: () -> Unit,
    onOpenSearch: (String) -> Unit,
    revealQueue: (String, Long, Boolean) -> Boolean,
    onPlayAllAudio: (String, Long, Long) -> Unit,
    searchChannel: Channel<String>,
    scrollToTopChannel: Channel<Unit>,
    globalHazeState: dev.chrisbanes.haze.HazeState?,
    isCurrentPage: Boolean,
) {
    if (!entry.owns()) return
    val model = entry.viewModel
    val search = entry.key as? BiliPaiNavKey.WatchLaterSearch
    LaunchedEffect(model,isCurrentPage) {
        if(isCurrentPage) model.loadData(showLoading=model.uiState.value.items.isEmpty())
    }
    val window = LocalWindowSizeClass.current
    CompositionLocalProvider(LocalDesktopWatchLaterBindings provides bindings,
        LocalDesktopFavoriteViewport provides DesktopFavoriteViewport(window.widthDp.value.toInt(),window.heightDp.value.toInt())) {
        WatchLaterScreen(onBack = {if(entry.owns()) onBack()},
            onVideoClick = {bvid,cid,position ->
                if(entry.owns() && !revealQueue(bvid,cid,false)) navigation.video(bvid,cid,
                    model.uiState.value.items.firstOrNull {it.bvid==bvid}?.pic.orEmpty(),
                    position, sourceRoute=entry.key.toLegacyRoute())
            },
            onPlayAllAudioClick = {bvid,cid,position ->
                if(entry.owns() && !revealQueue(bvid,cid,true)) onPlayAllAudio(bvid,cid,position)
            }, initialSearchQuery=search?.query.orEmpty(),
            onOpenSearchDestination=if(search==null) ({query -> if(entry.owns()) onOpenSearch(query)}) else null,
            isSearchDestination=search!=null, listScopedSearchChannel=if(search==null) searchChannel else null,
            viewModel=model, globalHazeState=globalHazeState,
            scrollToTopChannel=scrollToTopChannel, isCurrentPage=isCurrentPage)
    }
}
