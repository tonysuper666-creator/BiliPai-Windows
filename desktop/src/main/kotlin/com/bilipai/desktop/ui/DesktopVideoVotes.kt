package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.components.VideoCommentVoteCard
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlay
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import com.android.purebilibili.feature.video.ui.overlay.rememberCommandDanmakuOverlayState
import com.android.purebilibili.feature.video.ui.overlay.submitOriginalDesktopCommandVote
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Reuses the sole repository, epoch session/emotes, global context and original
 * vote dialog platform schema. It owns no account, HTTP client, items or store.
 */
@Composable
private fun DesktopVideoVoteBindings(
    repository: DesktopRepository,
    contentKey: Any,
    stillOwned: () -> Boolean,
    onFeedback: (String) -> Unit,
    content: @Composable (DesktopDynamicCardOperations, () -> Boolean) -> Unit,
) {
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val session = checkNotNull(LocalDesktopDynamicCardSession.current)
    val preferences = checkNotNull(LocalDesktopDynamicTimelinePreferences.current)
    val latestOwned by rememberUpdatedState(stillOwned)
    val latestFeedback by rememberUpdatedState(onFeedback)
    val alive = remember(repository, capturedEpoch, session, contentKey) { AtomicBoolean(true) }
    fun owned() = alive.get() && session.matches(repository, capturedEpoch) && latestOwned()
    val operations = remember(alive) {
        DesktopDynamicCardOperations(repository, capturedEpoch, ::owned, session.emotes)
    }
    val platform = remember(alive) {
        DesktopVideoVotePlatform(preferences.context, session.emotes, operations, ::owned,
            feedback = { if (owned()) latestFeedback(it) })
    }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    // The original Dialog state/effect keys contain voteId, not epoch or native
    // source. A keyed owner disposes even same-id requests from the old source.
    if (owned()) key(alive) {
        CompositionLocalProvider(LocalDesktopDynamicCardBindings provides platform) { content(operations, ::owned) }
    }
}

/** Raw original vote-card consumer for the existing video comments owner.
 * Only the vote field is projected; it is not another comments/items cache.
 * Root's future full comment owner can instead pass its already-loaded card to
 * DesktopVideoCommentVoteCardHost and avoid the legacy REST+gRPC duplicate read.
 */
@Composable
internal fun DesktopVideoCommentVoteSlot(
    repository: DesktopRepository,
    aid: Long,
    mode: Int,
    refreshKey: Any,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (aid <= 0) return
    DesktopVideoVoteBindings(repository, listOf("video-comment-vote", aid), { true }, onFeedback) { operations, owned ->
        var card by remember { mutableStateOf<ReplyVoteCard?>(null) }
        LaunchedEffect(aid, mode, refreshKey) {
            try {
                val data = operations.getCommentsForSubject(aid, type = 1, page = 1, mode = mode).getOrThrow()
                ensureActive()
                if (owned()) card = data.voteCard
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { if (owned()) onFeedback(failure.message ?: "投票信息加载失败") }
        }
        card?.let { VideoCommentVoteCard(it, modifier) }
    }
}

/** Used by an existing raw comment owner; original card state/actions intact. */
@Composable
internal fun DesktopVideoCommentVoteCardHost(
    repository: DesktopRepository,
    aid: Long,
    card: ReplyVoteCard,
    stillOwned: () -> Boolean,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DesktopVideoVoteBindings(repository, listOf("raw-video-comment-vote", aid), stillOwned, onFeedback) { _, _ ->
        VideoCommentVoteCard(card, modifier)
    }
}

/** Complete original command cards; ATTENTION retains its real owned callbacks. */
@Composable
internal fun DesktopVideoCommandVoteContent(
    repository: DesktopRepository,
    player: MpvPlayer,
    sourceLease: Any,
    aid: Long,
    cid: Long,
    danmaku: DanmakuOverlay,
    fontScale: Float,
    hideInteractiveCommands: Boolean,
    stillOwned: () -> Boolean,
    withAdmission: (() -> Unit) -> Boolean,
    capturePlaybackState: () -> VideoPlaybackUiState?,
    attention: DesktopWindowsCommandAttentionBinding?,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val session = checkNotNull(LocalDesktopDynamicCardSession.current)
    // A replacement accepted object creates a new scope. Old jobs retain their
    // original predicate rather than receiving a successor's closure on recomposition.
    val alive = remember(repository, capturedEpoch, session, sourceLease) { AtomicBoolean(true) }
    val capturedOwns = remember(alive) { stillOwned }
    val capturedAdmission = remember(alive) { withAdmission }
    val capturedState = remember(alive) { capturePlaybackState }
    val feedback by rememberUpdatedState(onFeedback)
    val current = remember(alive) { { alive.get() && session.matches(repository, capturedEpoch) && capturedOwns() } }
    val operations = remember(alive) { DesktopDynamicCardOperations(repository, capturedEpoch, current, session.emotes) }
    val platform = remember(alive) {
        DesktopWindowsCommandVoteBinding(sourceLease, aid, cid, current, current, capturedAdmission,
            readVote = { _, id -> operations.getVoteInfo(id) },
            writeVote = { _, id, indexes -> operations.submitVote(id, indexes, "").map { Unit } },
            writeGrade = { _, aid, cid, progress, id, score -> operations.submitGradeDanmaku(aid, cid, progress, id, score) },
            readGrade = { _, cid, aid, id -> operations.getGradeDanmakuSummary(cid, aid, id) },
            onFeedback = { feedback(it) })
    }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    key(alive) {
        val scope = rememberCoroutineScope()
        val commandState = rememberCommandDanmakuOverlayState(sourceLease)
        val native by player.state.collectAsState()
        val commandItems by danmaku.commandItems.collectAsState()
        val cidOwnedCommands = commandItems.let { danmaku.commandItemsFor(cid) }
        var measured by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val viewport = resolveDanmakuViewport(measured.width, measured.height, density.density)
        val attentionState = attention?.state?.collectAsState()?.value
        val attentionOwned = attention != null && attention.sourceLease === sourceLease && attention.isOwned()
        val items = desktopWindowsVisibleCommandCards(
            cidOwnedCommands, hideInteractiveCommands, attentionOwned)
        CompositionLocalProvider(LocalDesktopWindowsCommandVotePlatform provides platform) {
            Box(modifier.fillMaxSize().onSizeChanged { measured = it }) {
                if (viewport != null && native.ready && current()) CommandDanmakuOverlay(
                    items = items, player = player, viewport = viewport, bottomInsetPx = 0,
                    state = commandState, fontScale = fontScale, isFollowing = attentionState?.isFollowing ?: false,
                    onFollowClick = { if (attentionOwned && current()) attention?.follow() },
                    onTripleClick = { if (attentionOwned && current()) attention?.triple() },
                    onVoteSubmit = { item, option, index ->
                        submitOriginalDesktopCommandVote(item, option, index, capturedState(), commandState, scope, platform)
                    })
            }
        }
    }
}

/** Popup is a real desktop window seam above mpv's native child; a plain Box
 * behind SwingPanel is insufficient. Exact sizing follows the actual surface.
 * Native hit testing, focus/fullscreen/PiP behavior is not yet accepted here.
 */
@Composable
internal fun DesktopVideoCommandPopup(surfaceSize: IntSize, content: @Composable () -> Unit, anchorComponent: java.awt.Component) {
    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, content)
}

/** Original DynamicVoteDialog only invokes the two vote operations. Unrelated
 * card actions are deliberately unavailable, without UI pretending to provide
 * shares, media saving or messaging from a vote-only host.
 */
private class DesktopVideoVotePlatform(
    override val context: DesktopPluginContext,
    override val emotes: DesktopDynamicEmotes,
    private val operations: DesktopDynamicCardOperations,
    private val stillOwned: () -> Boolean,
    private val feedback: (String) -> Unit,
) : DesktopDynamicCardPlatform {
    override fun isOwned() = stillOwned() && operations.isOwned()
    override suspend fun getVoteInfo(voteId: Long) = operations.getVoteInfo(voteId)
    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>, dynamicId: String) =
        operations.submitVote(voteId, optionIndexes, dynamicId)
    override fun showFeedback(message: String) { if (isOwned()) feedback(message) }
    private fun unavailable(): Nothing = error("此投票宿主不提供其它动态操作")
    override fun copyText(text: String) = unavailable()
    override fun shareText(text: String) = unavailable()
    override fun openLink(url: String) = unavailable()
    override suspend fun searchUp(name: String) = unavailable()
    override suspend fun saveImage(url: String) = unavailable()
    override suspend fun saveImages(urls: List<String>) = unavailable()
    override suspend fun saveMotionPhoto(imageUrl: String, videoUrl: String) = unavailable()
    override suspend fun saveLivePhotoVideo(videoUrl: String) = unavailable()
    override suspend fun shareImage(url: String) = unavailable()
    override suspend fun getShareTargets(size: Int) = unavailable()
    override suspend fun getMessageSessions(size: Int) = unavailable()
    override suspend fun fetchMessageUserInfo(mid: Long) = unavailable()
    override suspend fun sendDynamicShare(receiverId: Long, content: String) = unavailable()
}
