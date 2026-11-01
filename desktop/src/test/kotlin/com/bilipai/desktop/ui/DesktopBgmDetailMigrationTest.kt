package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.audio.bgm.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

/** Real migrated page and injected upstream VMs, with strict in-memory ports.
 * CPU Compose only: no native client, dialog, account, HTTP or audio session. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopBgmDetailMigrationTest {
    @Test fun refreshAndPageRemountKeepTheInjectedSubjectAndBothDrafts() = fixture { f ->
        assertEquals(1, f.details.loads)
        assertEquals(listOf(771L to 17), f.commentReads)
        f.comments.beginCommentDraft()
        var session = f.comments.commentState.value.composerDrafts.sessionId
        f.comments.updateCommentDraft(session, null, "root draft", listOf("file:///synthetic.png"), true)
        f.comments.replyTo(ReplyItem(rpid = 41))
        f.comments.beginCommentDraft()
        session = f.comments.commentState.value.composerDrafts.sessionId
        f.comments.updateCommentDraft(session, 41, "reply draft", emptyList(), false)
        f.frames()
        val drafts = f.comments.commentState.value.composerDrafts
        f.action("刷新").invoke()
        f.frames()
        assertEquals(2, f.details.loads)
        assertEquals(listOf(771L to 17, 771L to 17), f.commentReads)
        assertEquals(drafts, f.comments.commentState.value.composerDrafts)
        assertEquals(41L, f.comments.commentState.value.replyTarget?.rpid)

        // The Root keeps its VMs while replacing the BGM page with a nested comment route.
        f.mounted.value = false
        f.frames()
        f.mounted.value = true
        f.frames()
        assertEquals(2, f.details.loads, "Remount reuses the injected detail VM")
        assertEquals(2, f.commentReads.size, "Same subject initialization does not reset or fetch again")
        assertEquals(drafts, f.comments.commentState.value.composerDrafts)
    }

    @Test fun failedDetailRetryUsesTheInjectedVmAndPublishesTheRecoveredPage() = fixture(failFirst = true) { f ->
        assertEquals("synthetic detail failure", f.vm.state.value.error)
        f.action("重试").invoke()
        f.frames()
        assertEquals(2, f.details.loads)
        assertNull(f.vm.state.value.error)
        assertEquals("Synthetic song", f.vm.state.value.detail?.musicTitle)
        assertEquals(listOf(771L to 17), f.commentReads)
    }

    @Test fun callbacksRetainedAfterOwnerRetirementCannotRefreshComposeShareOrNavigate() = fixture { f ->
        val refresh = f.action("刷新")
        val compose = f.action("写评论")
        val share = f.action("分享")
        val back = f.action("返回", description = true)
        val before = f.comments.commentState.value
        f.details.owned = false
        f.scope.cancel()
        refresh(); compose(); share(); back()
        f.scheduler.runCurrent()
        assertEquals(1, f.details.loads)
        assertEquals(1, f.commentReads.size)
        assertEquals(before, f.comments.commentState.value)
        assertTrue(f.actions.isEmpty(), f.actions.toString())
    }

    private fun fixture(failFirst: Boolean = false, body: (Page) -> Unit) {
        Page(failFirst).use(body)
    }

    private class Details(private val failFirst: Boolean) : DesktopBgmDetailRequests {
        var owned = true
        var loads = 0
        override fun isOwned() = owned
        override fun hasLogin() = false
        override suspend fun getBgmDetail(musicId: String, aid: Long, cid: Long): Result<BgmDetailData?> {
            assertEquals(listOf("M1", "1", "2"), listOf(musicId, aid.toString(), cid.toString()))
            loads++
            return if (failFirst && loads == 1) Result.failure(IllegalStateException("synthetic detail failure"))
            else Result.success(BgmDetailData(musicTitle = "Synthetic song", musicComment = BgmCommentInfo(oid = 771, pageType = 17)))
        }
        override suspend fun getAllBgmRecommendVideos(musicId: String): Result<List<BgmRecommendVideo>> = error("No video-list request")
        override suspend fun updateBgmWish(musicId: String, state: Int): Result<SimpleApiResponse> = error("No wish request")
        override suspend fun getEmotePackages(): Result<List<EmotePackage>> = error("No composer opens")
    }

    private class Page(failFirst: Boolean) : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val details = Details(failFirst)
        val vm = BgmDetailViewModel(scope, details)
        val commentReads = mutableListOf<Pair<Long, Int>>()
        val actions = mutableListOf<String>()
        val mounted = mutableStateOf(true)
        private val directory = Files.createTempDirectory("bgm-migration-")
        private val context = DesktopPluginContext(DesktopPluginStore(directory))
        private val requests = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "isOwned" -> details.owned
                "currentMid" -> 111L
                "getMobileCommentsForSubject" -> {
                    assertTrue(details.owned)
                    val values = args!!.dropLast(1)
                    assertEquals(listOf(771L, 17, 1, VIDEO_COMMENT_PAGE_SIZE), values.take(4))
                    commentReads += (values[0] as Long) to (values[1] as Int)
                    ReplyData(replies = emptyList(), cursor = ReplyCursor(isEnd = true))
                }
                else -> error("Unexpected comment port ${method.name}")
            }
        } as DesktopVideoCommentRequests
        val comments = VideoCommentViewModel(scope, requests)
        private val platform = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "getContext" -> context
                "isOwned" -> details.owned
                "showFeedback", "shareText", "copyText" -> { actions += method.name; Unit }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Strict BGM platform"
                else -> error("Unexpected platform ${method.name}")
            }
        } as DesktopCommentPlatform
        private val scene = ImageComposeScene(800, 900, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {
                    if (mounted.value) BgmDetailScreen("M1", 1, 2, false,
                        onBack = { actions += "back" }, onVideosClick = { actions += "videos" },
                        onVideoClick = { _, _, _ -> actions += "video" }, onUserClick = { actions += "user" },
                        onCommentClick = { _, _, _, _ -> actions += "comment" }, onLinkClick = { actions += "link" },
                        onLogin = { actions += "login" }, viewModel = vm, commentViewModel = comments,
                        requests = details, nowPlayingBarOverlayVisible = false,
                        onMediaSearch = { _, _, _ -> actions += "media"; true })
                }
            }
        }
        init { frames() }

        fun frames() {
            repeat(20) {
                Snapshot.sendApplyNotifications()
                scheduler.runCurrent()
                scene.render(scheduler.currentTime * 1_000_000).close()
                scheduler.advanceTimeBy(20)
            }
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), errors.toString())
        }

        fun action(label: String, description: Boolean = false): () -> Boolean {
            fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
            val all = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            var node: SemanticsNode? = all.first {
                if (description) it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                else it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true
            }
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            return assertNotNull(node).config[SemanticsActions.OnClick].action!!
        }

        override fun close() {
            scene.close()
            scope.cancel()
            scheduler.runCurrent()
            directory.toFile().deleteRecursively()
        }
    }
}
