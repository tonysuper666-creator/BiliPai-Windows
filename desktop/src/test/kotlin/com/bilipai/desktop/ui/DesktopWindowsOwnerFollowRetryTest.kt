package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Actual original VM and both Windows bindings; no HTTP, account, DLL or UI. */
class DesktopWindowsOwnerFollowRetryTest {
    @TempDir lateinit var directory: Path

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() {
            var count = 0
            while (tasks.isNotEmpty()) {
                check(++count < 1_000) { "Unexpected unbounded fixture dispatch" }
                tasks.removeFirst().run()
            }
        }
    }

    private class Harness(folder: Path, known: Boolean, following: Boolean, val dispatcher: CoroutineDispatcher) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        @Volatile var entry = true
        @Volatile var account = 1
        var subject = VideoSubjectSnapshot("BVfixture", 70, 17, 33, "title", "", 60_000, 1)
        private var sourceVersion = 1L
        var accepted = source(subject)
        private val gate = Any()
        private val depth = ThreadLocal.withInitial { 0 }
        val queried = CopyOnWriteArrayList<Long>()
        val mutations = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        var query: suspend (Long) -> Result<Boolean> = { Result.success(false) }
        var mutation: suspend (Long, Boolean) -> Result<Boolean> = { _, was -> Result.success(!was) }
        var afterSourceAdmission: (() -> Unit)? = null
        private val bindings = mutableListOf<AutoCloseable>()

        fun dataOwned() = entry && account == 1 && scope.isActive
        private fun dataAdmission(action: () -> Unit): Boolean = synchronized(gate) {
            if (!dataOwned()) false else {
                depth.set(depth.get() + 1)
                try { if (!dataOwned()) false else { action(); true } }
                finally { depth.set(depth.get() - 1) }
            }
        }
        private fun source(value: VideoSubjectSnapshot) = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(value.bvid, aid = value.aid, cid = value.cid),
            OwnedPlaybackSourceSnapshot(sourceVersion, PlaybackSource("https://fixture.invalid/video")))
        private fun sourceAdmission(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            var applied = false
            dataAdmission { if (accepted === expected) { action(); applied = true } }
            val hook = afterSourceAdmission
            afterSourceAdmission = null
            hook?.invoke()
            return applied
        }
        private val actions = object : VideoEngagementActions {
            override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean): Result<Boolean> {
                assertEquals(0, depth.get(), "Mutation I/O inside caller admission")
                mutations += mid to currentlyFollowing
                return mutation(mid, currentlyFollowing)
            }
            override suspend fun doTripleAction(aid: Long, coinCount: Int): Result<TripleActionResult> = error("No triple")
            override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String) = error("No like")
            override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) = error("No dislike")
            override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) = error("No favorite")
            override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) = error("No watch later")
            override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String) = error("No coin")
        }
        val vm = VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(Files.createDirectories(folder))), scope,
            actions, VideoCoinBalanceLoader { error("No coin balance") }, ::dataOwned, ::dataAdmission,
            interactionSession = { account }, cachedFollowStatusReader = { error("Owner query does not use foreign cache") },
            followStatusQuery = { mid ->
                assertEquals(0, depth.get(), "Status I/O inside caller admission")
                queried += mid
                query(mid)
            },
        ))
        init { vm.bindSubject(subject, seed(known, following)) }
        fun seed(known: Boolean, following: Boolean) = VideoEngagementSeed(
            isLoggedIn = true, likeStatusKnown = true, followStatusKnown = known,
            isFollowing = following, followingMids = if (following) setOf(subject.ownerMid) else emptySet(),
        )
        fun ordinary(): DesktopWindowsVideoEngagementBinding {
            val expected = accepted
            return DesktopWindowsVideoEngagementBinding(expected, vm, subject,
                { dataOwned() && accepted === expected }, { action -> sourceAdmission(expected, action) })
                .also { bindings += it }
        }
        fun attention(): DesktopWindowsCommandAttentionBinding {
            val expected = accepted
            return DesktopWindowsCommandAttentionBinding(expected, vm, subject,
                { dataOwned() && accepted === expected }, { action -> sourceAdmission(expected, action) })
                .also { bindings += it }
        }
        fun replaceSource() { ++sourceVersion; accepted = source(subject) }
        fun replaceSubject() {
            subject = VideoSubjectSnapshot("BVnextfixture", 71, 18, 44, "next", "", 60_000, 2)
            replaceSource()
            vm.bindSubject(subject, seed(false, false))
        }
        suspend fun drain() = withTimeout(6_000) {
            while (scope.coroutineContext.job.children.any()) {
                (dispatcher as? QueuedDispatcher)?.runAll()
                scope.coroutineContext.job.children.toList().joinAll()
            }
        }
        suspend fun close() {
            bindings.forEach(AutoCloseable::close)
            scope.cancel()
            (dispatcher as? QueuedDispatcher)?.runAll()
            scope.coroutineContext.job.join()
        }
    }

    private suspend fun withHarness(known: Boolean = false, following: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined, block: suspend (Harness) -> Unit) {
        val h = Harness(directory.resolve("case"), known, following, dispatcher)
        try { block(h) } finally { h.close() }
    }

    @Test fun unknownOwnerConfirmsBeforeFollowing() = runBlocking { withHarness { h ->
        assertTrue(h.ordinary().toggleFollow()); h.drain()
        assertEquals(listOf(33L), h.queried)
        assertEquals(listOf(33L to false), h.mutations)
        assertTrue(h.vm.uiState.value.followStatusKnown)
        assertTrue(h.vm.uiState.value.isFollowing)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
    } }

    @Test fun unknownOwnerUsesConfirmedFollowingBeforeUnfollowing() = runBlocking { withHarness { h ->
        h.query = { Result.success(true) }
        h.ordinary().toggleFollow(); h.drain()
        assertEquals(listOf(33L to true), h.mutations)
        assertTrue(h.vm.uiState.value.followStatusKnown)
        assertFalse(h.vm.uiState.value.isFollowing)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
    } }

    @Test fun repeatedUnknownClicksShareOneConfirmationAndMutation() = runBlocking { withHarness { h ->
        val response = CompletableDeferred<Result<Boolean>>()
        h.query = { response.await() }
        val button = h.ordinary()
        button.toggleFollow(); button.toggleFollow()
        assertEquals(listOf(33L), h.queried)
        assertTrue(h.vm.uiState.value.isFollowSubmitting)
        assertTrue(h.mutations.isEmpty())
        response.complete(Result.success(false)); h.drain()
        assertEquals(listOf(33L to false), h.mutations)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
    } }

    @Test fun failedConfirmationKeepsUnknownAndAllowsAnotherClick() = runBlocking { withHarness { h ->
        h.query = { Result.failure(IllegalStateException("Controlled status failure")) }
        val button = h.ordinary()
        button.toggleFollow(); h.drain()
        assertEquals(3, h.queried.size)
        assertTrue(h.mutations.isEmpty())
        assertFalse(h.vm.uiState.value.followStatusKnown)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
        h.query = { Result.success(false) }
        button.toggleFollow(); h.drain()
        assertEquals(4, h.queried.size)
        assertEquals(listOf(33L to false), h.mutations)
    } }

    @Test fun confirmedStatusSurvivesMutationFailureAndAnUnknownSeed() = runBlocking { withHarness { h ->
        h.query = { Result.success(true) }
        h.mutation = { _, _ -> Result.failure(IllegalStateException("Controlled write failure")) }
        h.ordinary().toggleFollow(); h.drain()
        h.vm.bindSubject(h.subject, h.seed(false, false))
        assertTrue(h.vm.uiState.value.followStatusKnown)
        assertTrue(h.vm.uiState.value.isFollowing)
        assertEquals(setOf(33L), h.vm.uiState.value.followingMids)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
    } }

    @Test fun sourceRetirementRejectsOldResultAndReleasesOnlyItsBusyFlag() = runBlocking { withHarness { h ->
        val response = CompletableDeferred<Result<Boolean>>()
        h.query = { response.await() }
        h.ordinary().toggleFollow()
        assertTrue(h.vm.uiState.value.isFollowSubmitting)
        h.replaceSource(); response.complete(Result.success(true)); h.drain()
        assertTrue(h.mutations.isEmpty())
        assertFalse(h.vm.uiState.value.followStatusKnown)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
        h.query = { Result.success(false) }
        h.ordinary().toggleFollow(); h.drain()
        assertEquals(listOf(33L to false), h.mutations)
    } }

    @Test fun subjectChangeCannotLetOldCleanupClearTheNewSubmission() = runBlocking { withHarness { h ->
        val old = CompletableDeferred<Result<Boolean>>()
        val fresh = CompletableDeferred<Result<Boolean>>()
        h.query = { mid -> if (mid == 33L) old.await() else fresh.await() }
        h.ordinary().toggleFollow()
        val oldJob = h.scope.coroutineContext.job.children.single()
        h.replaceSubject(); h.ordinary().toggleFollow()
        old.complete(Result.success(true)); oldJob.join()
        assertEquals(h.subject, h.vm.uiState.value.subject)
        assertTrue(h.vm.uiState.value.isFollowSubmitting)
        assertTrue(h.mutations.isEmpty())
        fresh.complete(Result.success(false)); h.drain()
        assertEquals(listOf(44L to false), h.mutations)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
    } }

    @Test fun knownRapidTogglesStaySerializedAndBusyUntilBothFinish() = runBlocking { withHarness(known = true) { h ->
        val first = CompletableDeferred<Result<Boolean>>()
        val second = CompletableDeferred<Result<Boolean>>()
        val secondStarted = CompletableDeferred<Unit>()
        h.mutation = { _, _ -> if (h.mutations.size == 1) first.await() else {
            secondStarted.complete(Unit); second.await()
        } }
        val button = h.ordinary()
        button.toggleFollow(); button.toggleFollow()
        assertEquals(listOf(33L to false), h.mutations)
        first.complete(Result.success(true)); withTimeout(2_000) { secondStarted.await() }
        assertEquals(listOf(33L to false, 33L to true), h.mutations)
        assertTrue(h.vm.uiState.value.isFollowSubmitting)
        second.complete(Result.success(false)); h.drain()
        assertTrue(h.queried.isEmpty())
        assertFalse(h.vm.uiState.value.isFollowing)
        assertFalse(h.vm.uiState.value.isFollowSubmitting)
    } }

    @Test fun attentionCardCannotUnfollowAfterItsCapturedStateChanges() = runBlocking { withHarness(known = true) { h ->
        h.afterSourceAdmission = { h.vm.bindSubject(h.subject, h.seed(true, true)) }
        assertTrue(h.attention().follow()); h.drain()
        assertTrue(h.vm.uiState.value.isFollowing)
        assertTrue(h.mutations.isEmpty())
        assertTrue(h.queried.isEmpty())
    } }

    @Test fun cancelledBeforeDispatchStillReleasesItsOwnSubmittingState() = runBlocking {
        val dispatcher = QueuedDispatcher()
        withHarness(dispatcher = dispatcher) { h ->
            h.ordinary().toggleFollow()
            assertTrue(h.vm.uiState.value.isFollowSubmitting)
            h.scope.coroutineContext.job.children.single().cancel()
            h.replaceSource(); dispatcher.runAll(); h.drain()
            assertTrue(h.queried.isEmpty())
            assertTrue(h.mutations.isEmpty())
            assertFalse(h.vm.uiState.value.isFollowSubmitting)
            h.ordinary().toggleFollow(); dispatcher.runAll(); h.drain()
            assertEquals(listOf(33L to false), h.mutations)
        }
    }

    @Test fun accountRetirementCannotPublishOrClearAnotherEntryState() = runBlocking { withHarness { h ->
        val response = CompletableDeferred<Result<Boolean>>()
        h.query = { response.await() }
        h.ordinary().toggleFollow()
        val oldState = h.vm.uiState.value
        h.account = 2; response.complete(Result.success(true)); h.drain()
        assertEquals(oldState, h.vm.uiState.value)
        assertTrue(h.mutations.isEmpty())
    } }
}
