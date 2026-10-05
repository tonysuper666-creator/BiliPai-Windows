package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.ui.overlay.*
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

/** Fixed metadata/click policy -> actual Windows binding -> complete generated
 * engagement VM/use case/protocol. Only HTTP results are controlled; no native
 * player, account credentials, user profile or real mutation request is used. */
class DesktopWindowsCommandAttentionBindingTest {
    private class Harness : AutoCloseable {
        val root = Files.createTempDirectory("attention-owned-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        val lease = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://example.invalid/video")))
        @Volatile var accepted = lease
        @Volatile var account = 1L
        @Volatile var entry = true
        var admissionDepth = 0
        var beforeAdmission: () -> Unit = {}
        val follows = mutableListOf<Pair<Long, Boolean>>()
        val triples = mutableListOf<Long>()
        var followResult: suspend () -> Result<Boolean> = { Result.success(true) }
        var tripleResult: suspend () -> Result<TripleActionResult> = { Result.success(TripleActionResult(true, true, null, true)) }
        private val actions = object : VideoEngagementActions {
            override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean): Result<Boolean> {
                assertEquals(0, admissionDepth); follows += mid to currentlyFollowing; return followResult()
            }
            override suspend fun doTripleAction(aid: Long): Result<TripleActionResult> {
                assertEquals(0, admissionDepth); triples += aid; return tripleResult()
            }
            override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) = error("not selected")
            override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) = error("not selected")
            override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String) = error("not selected")
        }
        fun owned() = account == 1L && entry && accepted === lease
        fun admit(action: () -> Unit): Boolean {
            beforeAdmission()
            if (!owned()) return false
            admissionDepth++
            try { if (!owned()) return false; action(); return true }
            finally { admissionDepth-- }
        }
        val engagement = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(root)), scope, actions, VideoCoinBalanceLoader { 0.0 },
            { account == 1L && entry }, { block -> if (account == 1L && entry) { block(); true } else false }))
        val binding: DesktopWindowsCommandAttentionBinding
        init {
            engagement.bindSubject(subject, VideoEngagementSeed(isLoggedIn = true))
            binding = DesktopWindowsCommandAttentionBinding(lease, engagement, subject, ::owned, ::admit)
        }
        fun replacement() = DesktopOriginalVideoAcceptedPublication(lease.request, lease.nativeSource)
        suspend fun drain() = withTimeout(2_000) {
            while (scope.coroutineContext.job.children.any()) scope.coroutineContext.job.children.toList().joinAll()
        }
        override fun close() {
            binding.close(); scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private fun command(type: Int) = assertNotNull(buildCommandDanmakuItem(DanmakuProto.CommandDm(
        id = 1, command = "#ATTENTION#", progress = 5_000,
        extra = """{"type":$type,"duration":4321,"posX_2":25,"posY_2":75}""")))

    private fun click(h: Harness, item: CommandDanmakuItem, target: AttentionCommandAction): Boolean {
        val action = resolveAttentionCommandClickAction(item.attentionType, target, h.binding.state.value.isFollowing)
        return when {
            action.shouldFollow -> h.binding.follow()
            action.shouldTriple -> h.binding.triple()
            else -> false
        }
    }

    @Test fun originalMetadataFollowCardCallsOnlySameOwnerFollowAndPublishesOriginalState() = runBlocking {
        Harness().use { h ->
            val item = command(0)
            assertEquals(4321L, item.durationMs); assertEquals(.25f, item.positionXRatio); assertEquals(.75f, item.positionYRatio)
            assertTrue(item.isActiveAt(5_000)); assertFalse(item.isActiveAt(9_321))
            assertFalse(click(h, item, AttentionCommandAction.TRIPLE))
            assertTrue(click(h, item, AttentionCommandAction.FOLLOW)); h.drain()
            assertEquals(listOf(33L to false), h.follows); assertTrue(h.triples.isEmpty())
            assertTrue(h.engagement.uiState.value.isFollowing)
            assertFalse(click(h, item, AttentionCommandAction.FOLLOW))
        }
    }

    @Test fun tripleOnlyAndCombinedCardsNeverImplicitlyFollow() = runBlocking {
        for (type in listOf(1, 2)) Harness().use { h ->
            assertTrue(click(h, command(type), AttentionCommandAction.TRIPLE)); h.drain()
            assertEquals(listOf(17L), h.triples); assertTrue(h.follows.isEmpty())
            val state = h.engagement.uiState.value
            assertTrue(state.isLiked); assertTrue(state.isFavorited); assertEquals(2, state.coinCount)
            assertFalse(state.isFollowing)
            if (type == 1) assertFalse(click(h, command(type), AttentionCommandAction.FOLLOW))
        }
    }

    @Test fun partialTripleKeepsOriginalSuccessfulFieldsAndDoesNotInventAllSuccess() = runBlocking {
        Harness().use { h ->
            h.tripleResult = { Result.success(TripleActionResult(true, false, "余额不足", true)) }
            assertTrue(h.binding.triple()); h.drain()
            val state = h.engagement.uiState.value
            assertTrue(state.isLiked); assertTrue(state.isFavorited); assertEquals(0, state.coinCount)
            assertFalse(state.tripleCelebrationVisible); assertFalse(state.isFollowing)
        }
    }

    @Test fun originalFailureCanRetryWithoutFakeFollowing() = runBlocking {
        Harness().use { h ->
            h.followResult = { Result.failure(IllegalStateException("server refused")) }
            assertTrue(h.binding.follow()); h.drain(); assertFalse(h.engagement.uiState.value.isFollowing)
            h.followResult = { Result.success(true) }
            assertTrue(h.binding.follow()); h.drain(); assertTrue(h.engagement.uiState.value.isFollowing)
            assertEquals(2, h.follows.size)
        }
    }

    @Test fun accountOrSameValueAcceptedSuccessorBeforeClickRejectsRequest() = runBlocking {
        for (retire in listOf<(Harness) -> Unit>({ it.account = 2 }, { it.accepted = it.replacement() }, { it.entry = false }))
            Harness().use { h ->
                h.beforeAdmission = { retire(h) }
                assertFalse(h.binding.follow()); assertFalse(h.binding.triple()); h.drain()
                assertTrue(h.follows.isEmpty()); assertTrue(h.triples.isEmpty())
            }
    }

    @Test fun sameGenerationSourceReplacingDuringAwaitRejectsFinalStateAndFeedback() = runBlocking {
        Harness().use { h ->
            val result = CompletableDeferred<Result<Boolean>>()
            h.followResult = { result.await() }
            assertTrue(h.binding.follow()); assertEquals(1, h.follows.size)
            h.accepted = h.replacement(); result.complete(Result.success(true)); h.drain()
            assertFalse(h.engagement.uiState.value.isFollowing)
            assertNull(withTimeoutOrNull(40) { h.engagement.events.take(1).toList() })
        }
    }

    @Test fun subjectRetirementAndCallerCancellationDoNotPublishOldSuccess() = runBlocking {
        Harness().use { h ->
            val result = CompletableDeferred<Result<TripleActionResult>>()
            h.tripleResult = { result.await() }; assertTrue(h.binding.triple())
            h.engagement.bindSubject(h.subject.copy(cid = 71, generation = 2), VideoEngagementSeed())
            result.complete(Result.success(TripleActionResult(true, true, null, true))); h.drain()
            assertFalse(h.engagement.uiState.value.isLiked); assertEquals(0, h.engagement.uiState.value.coinCount)
        }
        Harness().use { h ->
            val result = CompletableDeferred<Result<Boolean>>()
            h.followResult = { result.await() }; assertTrue(h.binding.follow())
            h.scope.coroutineContext.job.cancelChildren(); result.complete(Result.success(true)); h.drain()
            assertFalse(h.engagement.uiState.value.isFollowing)
        }
    }

    @Test fun queuedOriginalEventsAreDiscardedOnSameSubjectSourceReplacement() = runBlocking {
        Harness().use { h ->
            assertTrue(h.binding.follow()); h.drain(); assertTrue(h.engagement.uiState.value.isFollowing)
            h.accepted = h.replacement()
            assertNull(withTimeoutOrNull(40) { h.engagement.events.take(1).toList() })
        }
    }

    @Test fun naturallyCompletedRequestStillDeliversItsCurrentOriginalEvent() = runBlocking {
        Harness().use { h ->
            assertTrue(h.binding.follow()); h.drain()
            val events = withTimeout(2_000) { h.engagement.events.take(2).toList() }
            assertTrue(events.any { it == VideoEngagementEvent.Message("关注成功") })
            assertTrue(events.any { it == VideoEngagementEvent.OpenFollowGroups(33) })
        }
    }

    private val analytics = object : DesktopOriginalVideoInteractionAnalytics {
        override fun logLike(videoId: String, isLiked: Boolean) = Unit
        override fun logDislike(videoId: String, isDisliked: Boolean) = Unit
        override fun logFavorite(videoId: String, isFavorited: Boolean) = Unit
        override fun logFollow(userId: String, isFollowed: Boolean) = Unit
        override fun logCoin(videoId: String, coinCount: Int) = Unit
    }
    private fun protocol(checkpoint: () -> Unit, confirm: () -> Unit = {}, call: (String, List<Any?>) -> Any): DesktopOriginalVideoEngagementProtocol {
        val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
            call(method.name, args.orEmpty().toList())
        } as BilibiliApi
        return DesktopOriginalVideoEngagementProtocol(api, { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null },
            checkpoint, { confirm() }, DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, checkpoint))
    }

    @Test fun realOriginalTripleProtocolPreservesOrderedRequestsAndNeverCallsFollow() = runBlocking {
        val calls = mutableListOf<String>()
        val arguments = mutableMapOf<String, List<Any?>>()
        val port = protocol({}) { name, args ->
            calls += name; arguments[name] = args
            if (name == "getFavFolders") FavFolderResponse(data = FavFolderList(list = listOf(FavFolder(id = 9))))
            else SimpleApiResponse()
        }
        val result = VideoInteractionUseCase(port, analytics).doTripleAction(17).getOrThrow()
        assertTrue(result.allSuccess)
        assertEquals(listOf("likeVideo", "coinVideo", "getFavFolders", "dealFavorite"), calls)
        assertEquals(listOf<Any?>(17L, 2, 1), arguments.getValue("coinVideo").take(3))
        assertEquals("9", arguments.getValue("dealFavorite")[2])
    }

    @Test fun actualProtocolRetiresBetweenTripleStagesAndCannotConfirmLateFollow() = runBlocking {
        var owned = true
        val presentation = DesktopOriginalVideoEngagementPresentation({ owned }, { action -> if (owned) { action(); true } else false })
        val calls = mutableListOf<String>()
        val port = protocol({}) { name, _ -> calls += name; owned = false; SimpleApiResponse() }
        assertFailsWith<CancellationException> {
            withContext(presentation.context) { port.tripleAction(17) }
        }
        assertEquals(listOf("likeVideo"), calls)
        owned = true; var confirmed = 0
        val follow = protocol({}, { confirmed++ }) { _, _ -> owned = false; SimpleApiResponse() }
        assertFailsWith<CancellationException> { withContext(presentation.context) { follow.followUser(33, true) } }
        assertEquals(0, confirmed)
    }

    @Test fun originalProtocolChecksSourceAgainInsideFinalFollowAdmission() = runBlocking {
        var owned = true; var confirmed = 0
        val presentation = DesktopOriginalVideoEngagementPresentation({ owned }, { action ->
            owned = false; action(); true
        })
        val port = protocol({}, { confirmed++ }) { _, _ -> SimpleApiResponse() }
        assertFailsWith<CancellationException> { withContext(presentation.context) { port.followUser(33, true) } }
        assertEquals(0, confirmed)
    }
}
