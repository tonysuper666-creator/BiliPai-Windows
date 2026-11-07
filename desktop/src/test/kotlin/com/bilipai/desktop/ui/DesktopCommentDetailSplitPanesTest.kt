@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.comment.CommentDetailScreen
import com.android.purebilibili.feature.comment.CommentDetailViewModel
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real generated CommentDetailScreen -> SubReplyDetailContent on the standalone NavEntry
 * opt-in, at real widths around the 960dp breakpoint. No account, network or settings. */
internal abstract class DesktopCommentDetailSplitScenes {
    @TempDir lateinit var directory: Path
    private val emotes = object : DesktopDynamicEmotes {
        override fun snapshot() = emptyMap<String, String>()
        override fun currentSessionKey(): Any = "isolated-detail-test"
        override suspend fun ensureLoaded() = snapshot()
    }
    private fun platform(): DesktopCommentPlatform {
        val context = DesktopPluginContext(DesktopPluginStore(directory))
        return Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "IsolatedDetailPlatform"
                "isOwned" -> true
                "getContext" -> context
                "getEmotes" -> emotes
                "getSubReplyLoadedCountEnabled" -> flowOf(false)
                "getCollapsedReplyPreviewLimit" -> 3
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as DesktopCommentPlatform
    }

    private var rootLines = 30
    private val root get() = ReplyItem(rpid = ROOT, oid = OID, action = 2, rcount = 60,
        member = ReplyMember(mid = "7", uname = "楼主"),
        content = ReplyContent(message = (0 until rootLines).joinToString("\n") { "Root line $it" }))
    protected val replies = (1..60).map { ReplyItem(rpid = ROOT + it, oid = OID, root = ROOT, action = if (it == 1) 2 else 0,
        member = ReplyMember(mid = "${10 + it}", uname = "用户$it"), content = ReplyContent(message = "Reply $it")) }

    private val requests = object : DesktopVideoCommentRequests {
        override fun isOwned() = true
        override fun currentMid() = 0L
        override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int, mode: Int,
            paginationOffset: String?, fallbackOnMissingLocation: Boolean): Result<ReplyData> = error("Detail never loads the main list")
        override suspend fun getSortedSubCommentsForSubject(oid: Long, type: Int, rootId: Long, mode: Int,
            paginationOffset: String?, targetReplyId: Long): Result<ReplyData> = Result.success(ReplyData(
            cursor = ReplyCursor(allCount = replies.size, isEnd = true), replies = replies, root = root, grpcNextOffset = ""))
        override suspend fun getDialogCommentsForSubject(oid: Long, type: Int, rootId: Long, dialogId: Long, page: Int,
            paginationOffset: String?): Result<ReplyData> = error("No conversation expected")
        override suspend fun uploadCommentPicture(source: String, index: Int): Result<ReplyPicture> = error("No mutation expected")
        override suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long, parent: Long,
            pictures: List<ReplyPicture>, syncToDynamic: Boolean): Result<ReplyItem?> = error("No mutation expected")
        override suspend fun likeCommentForSubject(oid: Long, type: Int, rpid: Long, like: Boolean): Result<Unit> = error("No mutation expected")
        override suspend fun hateCommentForSubject(oid: Long, type: Int, rpid: Long, hate: Boolean): Result<Unit> = error("No mutation expected")
        override suspend fun deleteCommentForSubject(oid: Long, type: Int, rpid: Long): Result<Unit> = error("No mutation expected")
        override suspend fun setCommentTopForSubject(oid: Long, type: Int, rpid: Long, isCurrentlyTop: Boolean): Result<Unit> = error("No mutation expected")
        override suspend fun reportCommentForSubject(oid: Long, type: Int, rpid: Long, reason: Int, content: String): Result<Unit> = error("No mutation expected")
        override suspend fun checkCommentStatus(aid: Long, rpid: Long, rootId: Long, hasPictures: Boolean, sentAtSeconds: Long,
            waitMs: Long): Result<CommentFraudStatus> = error("No mutation expected")
        override suspend fun saveFraudRecord(rpid: Long, oid: Long, type: Int, root: Long, message: String,
            status: CommentFraudStatus, initialStatus: CommentFraudStatus?) = error("No mutation expected")
    }

    private val homePlatform = DesktopHomePlatform(false, false, false, false, false, false, object : DesktopHomeWindowBackgroundPort {
        override val isInBackground = false
        override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
        override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
    }, 0f, false)

    protected inner class Scene(width: Int, private val targetId: Long = 0L, rootLines: Int = 30) : AutoCloseable {
        init { this@DesktopCommentDetailSplitScenes.rootLines = rootLines }
        private var time = 0L
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val handler = CoroutineExceptionHandler { _, error -> errors += error }
        private val vmScope = CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob() + handler)
        val vm = CommentDetailViewModel(vmScope, requests)
        var width by mutableStateOf(width)
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val transport = repository.httpClient.newBuilder().addInterceptor { chain ->
            check(chain.request().url.encodedPath == "/x/emote/user/panel/web") { "Unexpected request: ${chain.request().url}" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":{"packages":[]}}""".toResponseBody()).build()
        }.build()
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        init { DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport) }
        private val scene = DesktopUpstreamCalls.withOwner(owner) { ImageComposeScene(SCENE_WIDTH, SCENE_HEIGHT, Density(1f),
            coroutineContext = StandardTestDispatcher(scheduler) + DesktopUpstreamScope(owner) + handler) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                val bindings = remember { platform() }
                CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopKitReducedMotion provides true,
                    LocalDesktopHomePlatform provides homePlatform, LocalDesktopCommentBindings provides bindings,
                    LocalDesktopCommentDetailSplitPanes provides true) {
                    Box(Modifier.requiredWidth(this@Scene.width.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                        CommentDetailScreen(oid = OID, rootId = ROOT, targetId = targetId, onBack = {}, onOpenLink = {},
                            onUserClick = {}, viewModel = vm, requests = requests)
                    }
                }
            }
        } }
        init { check(GraphicsEnvironment.isHeadless()) }
        fun advance(frames: Int) { repeat(frames) {
            scheduler.runCurrent()
            time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Comment detail composition failed", it) }
        } }
        fun settle() = advance(12)
        fun resize(to: Int) { width = to; settle() }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun ownerCount() = scene.semanticsOwners.size
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun all(tag: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun has(tag: String) = all(tag).isNotEmpty()
        fun tagged(tag: String) = all(tag).single()
        fun top(tag: String) = tagged(tag).positionInWindow.y
        fun bounds(tag: String) = tagged(tag).boundsInWindow
        fun scrollValue(tag: String) = flatten(tagged(tag)).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun texts(text: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true }
        fun collapsedIn(area: Rect) = texts(HATED_PROMPT).count { area.contains(it.boundsInWindow.center) }
        fun click(node: SemanticsNode) {
            val p = node.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); settle()
        }
        fun scroll(point: Offset, delta: Float = 1f) {
            scene.sendPointerEvent(PointerEventType.Move, point)
            scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, delta)); settle()
        }
        /** The topmost reply whose header is inside the list viewport, with its y relative to the list. */
        fun firstReply(): Pair<Long, Float> {
            val list = bounds(SUB_REPLY_DETAIL_LIST_TAG)
            return replies.mapNotNull { reply -> all("$ACTION_PREFIX${reply.rpid}").singleOrNull()?.let { reply.rpid to it.positionInWindow.y - list.top } }
                .filter { it.second >= 0f }.minBy { it.second }
        }
        override fun close() {
            scene.close()
            vmScope.cancel()
            repository.logout()
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }

    protected companion object {
        const val OID = 170001L
        const val ROOT = 100L
        const val SCENE_WIDTH = 1800
        const val SCENE_HEIGHT = 900
        const val ACTION_PREFIX = "comment_action_button_"
        const val SUB_REPLY_DETAIL_LIST_TAG = "subreply_detail_reply_list"
        const val SUB_REPLY_DETAIL_ROOT_TAG = "subreply_detail_root"
        const val HATED_PROMPT = "已点踩的评论 · 点击展开"
        const val ACTION_SHEET_MARKER = "评论时间样式"
    }
}

internal class DesktopCommentDetailSplitPanesTest : DesktopCommentDetailSplitScenes() {
    @Test fun breakpointSplitsAtExactly960AndCapsEachPaneAt760() {
        Scene(959).use { s ->
            s.settle()
            assertFalse(s.has(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG), "959dp keeps one column")
            val list = s.bounds(SUB_REPLY_DETAIL_LIST_TAG)
            assertTrue(list.contains(s.bounds(SUB_REPLY_DETAIL_ROOT_TAG).center), "Narrow: the original comment is the list's first item")
            assertTrue(s.bounds(DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG).width <= 760f)

            s.resize(960)
            val context = s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)
            val pane = s.bounds(DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG)
            assertTrue(context.width > 400f && pane.width > 400f, "960dp splits: $context | $pane")
            assertTrue(context.right <= pane.left, "Context pane sits left of the replies pane")
            assertTrue(context.contains(s.bounds(SUB_REPLY_DETAIL_ROOT_TAG).center), "Wide: the original comment lives in the context pane")
            assertFalse(s.bounds(SUB_REPLY_DETAIL_LIST_TAG).contains(s.bounds(SUB_REPLY_DETAIL_ROOT_TAG).center))
            assertEquals(1, s.all(SUB_REPLY_DETAIL_ROOT_TAG).size, "The original comment is rendered once")

            s.resize(1800)
            val wideContext = s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)
            val widePane = s.bounds(DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG)
            assertEquals(760f, wideContext.width); assertEquals(760f, widePane.width)
            assertEquals(1800f - widePane.right, wideContext.left, 1f, "Capped panes stay centred")
        }
    }

    @Test fun wideReplyAndContextPanesScrollIndependently() {
        Scene(1200, rootLines = 120).use { s ->
            s.settle()
            val context = s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)
            val list = s.bounds(SUB_REPLY_DETAIL_LIST_TAG)
            assertEquals(0f, s.scrollValue(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG))
            s.click(s.texts(HATED_PROMPT).first { context.contains(it.boundsInWindow.center) })
            val replyBefore = s.firstReply()
            s.scroll(context.center, delta = 5f)
            val contextScrolled = s.scrollValue(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)
            assertTrue(contextScrolled > 0f, "The expanded original comment scrolls in its own pane")
            assertEquals(replyBefore, s.firstReply(), "Scrolling the context pane never moves the replies")
            repeat(3) { s.scroll(list.center, delta = 5f) }
            assertNotEquals(replyBefore.first, s.firstReply().first, "The replies pane scrolls")
            assertEquals(contextScrolled, s.scrollValue(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG), "Scrolling replies never moves the context")
        }
    }

    @Test fun topOfThreadPositionAndExpandedStateSurviveNarrowWideNarrow() {
        Scene(959, rootLines = 120).use { s ->
            s.settle()
            val rootArea = { s.bounds(SUB_REPLY_DETAIL_ROOT_TAG) }
            s.click(s.texts(HATED_PROMPT).single { rootArea().contains(it.boundsInWindow.center) })
            assertEquals(0, s.collapsedIn(rootArea()), "The original comment's hated body is expanded")
            s.scroll(s.bounds(SUB_REPLY_DETAIL_LIST_TAG).center, delta = 20f)
            val narrowOffset = s.bounds(SUB_REPLY_DETAIL_LIST_TAG).top - s.top(SUB_REPLY_DETAIL_ROOT_TAG)
            assertTrue(narrowOffset > 0f && narrowOffset < rootArea().height, "Reading inside the original comment: $narrowOffset")

            s.resize(960)
            val context = s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)
            assertEquals(narrowOffset, s.scrollValue(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG), 1f, "The context pane continues the same line")
            assertEquals(narrowOffset, context.top - s.top(SUB_REPLY_DETAIL_ROOT_TAG), 1f)
            assertEquals(0, s.collapsedIn(context), "The expanded original comment stays expanded in the context pane")
            assertEquals(ROOT + 1, s.firstReply().first, "Replies start from the first reply under the header")

            s.resize(959)
            assertFalse(s.has(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG))
            assertEquals(narrowOffset, s.bounds(SUB_REPLY_DETAIL_LIST_TAG).top - s.top(SUB_REPLY_DETAIL_ROOT_TAG), 1f,
                "Back to one column at the same line of the original comment")
            assertEquals(0, s.collapsedIn(rootArea()), "The original comment is still expanded after the round trip")
        }
    }

    @Test fun expandedReplyStaysExpandedAcrossWidths() {
        Scene(959).use { s ->
            s.settle()
            val inReplies = { s.texts(HATED_PROMPT).count { !s.bounds(SUB_REPLY_DETAIL_ROOT_TAG).contains(it.boundsInWindow.center) } }
            assertEquals(1, inReplies(), "Reply #1 starts collapsed")
            s.click(s.texts(HATED_PROMPT).single { !s.bounds(SUB_REPLY_DETAIL_ROOT_TAG).contains(it.boundsInWindow.center) })
            assertEquals(0, inReplies())
            s.resize(960)
            assertEquals(0, s.collapsedIn(s.bounds(DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG)), "Same reply item, still expanded, in the replies pane")
            assertEquals(1, s.collapsedIn(s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)), "The untouched original comment stays collapsed")
            s.resize(959)
            assertEquals(0, inReplies(), "Still expanded back in one column")
        }
    }

    @Test fun originalCommentActionSheetStaysOpenAcrossWidths() {
        Scene(959).use { s ->
            s.settle()
            s.click(s.tagged("$ACTION_PREFIX$ROOT"))
            assertEquals(1, s.texts(ACTION_SHEET_MARKER).size, "The original comment's action sheet is open")
            val owners = s.ownerCount()
            for (width in listOf(960, 959, 1400)) {
                s.resize(width)
                assertEquals(1, s.texts(ACTION_SHEET_MARKER).size, "The same sheet stays open at $width")
                assertEquals(owners, s.ownerCount(), "No second sheet at $width")
            }
        }
    }

    @Test fun replyPositionSurvivesNarrowWideNarrow() {
        Scene(959).use { s ->
            s.settle()
            repeat(4) { s.scroll(s.bounds(SUB_REPLY_DETAIL_LIST_TAG).center, delta = 5f) }
            assertFalse(s.bounds(SUB_REPLY_DETAIL_LIST_TAG).overlaps(s.bounds(SUB_REPLY_DETAIL_ROOT_TAG)), "Reading replies below the original")
            val narrow = s.firstReply()
            s.resize(960)
            // The reply's own layout reflows in the narrower pane, so compare which reply leads here
            // and the exact pixel line again once the width is back.
            assertEquals(narrow.first, s.firstReply().first, "Same reply at the top of the replies pane")
            assertEquals(0f, s.scrollValue(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG), "The context pane opens at the original comment's start")
            s.resize(959)
            assertEquals(narrow.first, s.firstReply().first)
            assertEquals(narrow.second, s.firstReply().second, 1f)
        }
    }

    @Test fun targetedReplyJumpLandsInTheRepliesPane() {
        Scene(1200, targetId = ROOT + 40).use { s ->
            s.advance(40)
            val pane = s.bounds(DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG)
            val target = s.bounds("$ACTION_PREFIX${ROOT + 40}")
            assertTrue(pane.contains(target.center), "The target reply is shown in the replies pane")
            assertEquals(ROOT + 40, s.firstReply().first, "The jump puts the target reply at the top")
            assertTrue(s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG).contains(s.bounds(SUB_REPLY_DETAIL_ROOT_TAG).center),
                "The original comment stays beside it")
            assertEquals(0f, s.scrollValue(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG))
        }
    }

    @Test fun rootTargetKeepsTheOriginalCommentAtTheTopOfTheContextPane() {
        Scene(1200, targetId = ROOT).use { s ->
            s.advance(40)
            val context = s.bounds(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG)
            assertEquals(context.top, s.top(SUB_REPLY_DETAIL_ROOT_TAG), 1f)
            assertEquals(ROOT + 1, s.firstReply().first)
        }
    }

    @Test fun readingPositionMapsTheSharedFirstItemOnly() {
        val inRoot = DesktopCommentDetailReadingPosition(listIndex = 0, listOffset = 120, contextScroll = 0)
        val wide = mapDesktopCommentDetailReadingPosition(inRoot, toSplit = true, rootHeight = 500)
        assertEquals(DesktopCommentDetailReadingPosition(0, 0, 120), wide)
        assertEquals(inRoot.copy(contextScroll = 120), mapDesktopCommentDetailReadingPosition(wide, toSplit = false, rootHeight = 500))
        val header = DesktopCommentDetailReadingPosition(listIndex = 0, listOffset = 530, contextScroll = 0)
        val wideHeader = mapDesktopCommentDetailReadingPosition(header, toSplit = true, rootHeight = 500)
        assertEquals(DesktopCommentDetailReadingPosition(0, 30, 0), wideHeader)
        assertEquals(header, mapDesktopCommentDetailReadingPosition(wideHeader, toSplit = false, rootHeight = 500))
        val reply = DesktopCommentDetailReadingPosition(listIndex = 7, listOffset = 33, contextScroll = 50)
        assertEquals(reply, mapDesktopCommentDetailReadingPosition(reply, toSplit = true, rootHeight = 500))
        assertEquals(reply, mapDesktopCommentDetailReadingPosition(reply, toSplit = false, rootHeight = 500))
        assertFalse(isDesktopCommentDetailSplit(959.99f)); assertTrue(isDesktopCommentDetailSplit(960f))
    }
}
