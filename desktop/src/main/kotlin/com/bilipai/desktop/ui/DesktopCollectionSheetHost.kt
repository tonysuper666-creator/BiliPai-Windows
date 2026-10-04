package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.util.buildDesktopCollectionShareText
import com.android.purebilibili.feature.video.ui.components.CollectionRow
import com.android.purebilibili.feature.video.ui.components.CollectionSheet
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.DesktopCollectionPreferenceWriteOperation
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopCollectionBindings(
    val context: DesktopPluginContext,
    val operations: DesktopDynamicCardOperations,
    private val stillOwned: () -> Boolean,
    private val feedback: (String) -> Unit,
    private val share: (String, String, () -> Boolean) -> Unit,
    private val commitIfCurrent: ((() -> Unit) -> Boolean) = operations::withOwnedEditorImageAdmission,
) {
    fun isOwned(): Boolean = operations.isOwned() && stillOwned()
    fun showFeedback(message: String) { if (isOwned()) feedback(message) }
    fun shareCollection(context: DesktopPluginContext, title: String, mid: Long, seasonId: Long) {
        check(context === this.context)
        if (isOwned()) share(title, buildDesktopCollectionShareText(title, mid, seasonId), ::isOwned)
    }
    suspend fun <T> withOwnedCollectionPreferences(block: suspend () -> T): T =
        DesktopCollectionPreferenceWriteOperation.withOwned(context, ::isOwned, commitIfCurrent, block)

    /** A popup borrows this exact Root authority, with an additional disposal lease. */
    fun forWindow(windowOwned: () -> Boolean): DesktopCollectionBindings {
        fun owned() = isOwned() && windowOwned()
        return DesktopCollectionBindings(context, operations.forEditor(::owned), ::owned,
            feedback, share, { action ->
                var entered = false
                commitIfCurrent { if (owned()) { action(); entered = true } } && entered
            })
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
            if (showSheet) DesktopWindowsVideoCollectionSheetHost(details, currentCid, bindings, alive,
                ::owned, onPlayQueue, onDismiss = { showSheet = false })
        }
    }
}
