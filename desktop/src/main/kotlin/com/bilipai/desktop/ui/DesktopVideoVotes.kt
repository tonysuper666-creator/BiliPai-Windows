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

/** Actual player command data, actual viewport size and original timed UI.
 * This scope is votes/grades only; it does not advertise inactive attention or
 * triple actions. Root binds the required grade callback to the same Operations
 * member hunk, while ordinary vote continues through its existing submitVote.
 */
@Composable
internal fun rememberDesktopVideoCommandVoteState(
    epoch: Long, sourceVersion: Long, bvid: String, cid: Long,
): CommandDanmakuOverlayState = rememberCommandDanmakuOverlayState(listOf(epoch, sourceVersion, bvid, cid))

@Composable
internal fun DesktopVideoCommandVoteContent(
    repository: DesktopRepository,
    player: MpvPlayer,
    sourceVersion: Long,
    bvid: String,
    aid: Long,
    cid: Long,
    danmaku: DanmakuOverlay,
    commandState: CommandDanmakuOverlayState,
    fontScale: Float,
    hideInteractiveCommands: Boolean,
    stillOwned: () -> Boolean,
    submitGrade: suspend (DesktopDynamicCardOperations, Long, Long, Long, String, Int) -> Result<Unit>,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val native by player.state.collectAsState()
    val commandItems by danmaku.commandItems.collectAsState()
    // Read the actual flow for recomposition, then retrieve only the sole list
    // owned by this CID under the same admission/publication lock.
    val cidOwnedCommands = commandItems.let { danmaku.commandItemsFor(cid) }
    val key = listOf("video-command-vote", sourceVersion, bvid, aid, cid)
    val nativeOwned = { sourceVersion > 0 && player.currentSourceVersion == sourceVersion &&
        native.ready && aid > 0 && cid > 0 && stillOwned() }
    DesktopVideoVoteBindings(repository, key, nativeOwned, onFeedback) { operations, owned ->
        val scope = rememberCoroutineScope()
        var measured by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val viewport = resolveDanmakuViewport(measured.width, measured.height, density.density)
        val items = filterVisibleCommandDanmakuItems(cidOwnedCommands, hideInteractiveCommands)
            .filter { it.type == CommandDanmakuType.VOTE }
        Box(modifier.fillMaxSize().onSizeChanged { measured = it }) {
            if (viewport != null && owned()) CommandDanmakuOverlay(items, player, viewport, commandState, fontScale,
                onFollowClick = {}, onTripleClick = {}, onVoteSubmit = { item, option, optionIndex ->
                    if (owned() && item.voteId.isNotBlank()) scope.launch {
                        val result = if (option.score != null) {
                            submitGrade(operations, aid, cid, item.startTimeMs, item.voteId, option.score)
                        } else {
                            val id = item.voteId.toLongOrNull()
                            if (id == null) Result.success(Unit) else
                                operations.submitVote(id, listOf(optionIndex), "").map { Unit }
                        }
                        ensureActive()
                        if (owned() && result.isFailure) onFeedback(result.exceptionOrNull()?.message ?:
                            if (option.score != null) "打分失败" else "投票失败")
                    }
                })
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
