package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.util.buildDesktopCollectionShareText
import com.android.purebilibili.feature.video.ui.components.CollectionRow
import com.android.purebilibili.feature.video.ui.components.CollectionSheet
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopCollectionBindings(
    val context: DesktopPluginContext,
    val operations: DesktopDynamicCardOperations,
    private val stillOwned: () -> Boolean,
    private val feedback: (String) -> Unit,
    private val share: (String, String, () -> Boolean) -> Unit,
) {
    fun showFeedback(message: String) { if (stillOwned()) feedback(message) }
    fun shareCollection(context: DesktopPluginContext, title: String, mid: Long, seasonId: Long) {
        check(context === this.context)
        if (stillOwned()) share(title, buildDesktopCollectionShareText(title, mid, seasonId), stillOwned)
    }
}
internal val LocalDesktopCollectionBindings = staticCompositionLocalOf<DesktopCollectionBindings> {
    error("Original collection UI requires its existing page/account/settings owner")
}

/** Whole original row/drawer. Episode mapping only binds the existing playback queue. */
@Composable
internal fun DesktopCollectionSheetHost(
    details: VideoDetails,
    repository: DesktopRepository,
    currentCid: Long,
    isPlaying: Boolean,
    onPlayQueue: (List<VideoCard>, VideoCard) -> Unit,
    onFeedback: (String) -> Unit,
    onShare: (String, String, () -> Boolean) -> Unit,
    stillOwned: () -> Boolean,
) {
    val season = details.raw?.ugc_season ?: return
    val epoch by repository.sessionEpochFlow.collectAsState()
    val session = checkNotNull(LocalDesktopDynamicCardSession.current)
    val context = checkNotNull(LocalDesktopDynamicTimelinePreferences.current).context
    val alive = remember(details.bvid, season.id, epoch, session) { AtomicBoolean(true) }
    val latestOwned by rememberUpdatedState(stillOwned)
    val feedback by rememberUpdatedState(onFeedback)
    val share by rememberUpdatedState(onShare)
    fun owned() = alive.get() && session.matches(repository, epoch) && latestOwned()
    val bindings = remember(alive, context) {
        DesktopCollectionBindings(context, DesktopDynamicCardOperations(repository, epoch, ::owned, session.emotes),
            ::owned, { feedback(it) }, { title, text, owner -> share(title, text, owner) })
    }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    if (owned()) key(alive) {
        var showSheet by remember { mutableStateOf(false) }
        CompositionLocalProvider(LocalDesktopCollectionBindings provides bindings) {
            CollectionRow(season, details.bvid, currentCid, isPlaying, onClick = { if (owned()) showSheet = true })
            if (showSheet) CollectionSheet(season, details.bvid, currentCid, onDismiss = { showSheet = false },
                onEpisodeClick = { episode ->
                    if (owned()) {
                        val collection = desktopUgcCollection(details, currentCid = currentCid)
                        val queue = collection?.queue.orEmpty()
                        val bvid = discoveryEpisodeBvid(episode)
                        val card = queue.firstOrNull { it.bvid == bvid && (episode.cid <= 0 || it.preferredCid == episode.cid) }
                            ?: queue.firstOrNull { it.bvid == bvid }
                        if (card != null) {
                            val selected = card.copy(preferredCid = episode.cid.takeIf { it > 0 } ?: card.preferredCid,
                                pageIndex = episode.pages.indexOfFirst { it.cid == episode.cid }.coerceAtLeast(0))
                            showSheet = false
                            // Root selects queues by (bvid,cid). A clicked part may
                            // differ from the season's default CID; keep it in the
                            // same queue position so Root cannot fall back to #0.
                            val selectedQueue = queue.toMutableList().apply { set(indexOf(card), selected) }
                            onPlayQueue(selectedQueue, selected)
                        }
                    }
                })
        }
    }
}
