package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicVoteInfo
import com.android.purebilibili.data.model.response.GradeDanmakuSummary
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.resolveGradeDanmakuSummary
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import com.android.purebilibili.feature.video.ui.overlay.submitOriginalDesktopCommandVote
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlin.test.*

/** Actual fixed policy/state/submission body -> Windows port -> result publication.
 * HTTP callbacks are controlled; no account, GUI or native audio/video operation. */
class DesktopWindowsCommandVoteBindingTest {
    private class Harness : AutoCloseable {
        val source = PlaybackSource("https://example.invalid/video")
        val lease = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create("BVfixture", aid = 17, cid = 70), OwnedPlaybackSourceSnapshot(1, source))
        var accepted = lease
        var nativeSnapshot = OwnedPlaybackSourceSnapshot(1, source)
        var accountEpoch = 1L
        var foreground = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CommandDanmakuOverlayState()
        var playback: VideoPlaybackUiState = VideoPlaybackUiState.Success(
            ViewInfo(bvid = "BVfixture", aid = 17, cid = 70), source.videoUrl)
        val messages = mutableListOf<String>()
        val votes = mutableListOf<Pair<Long, List<Int>>>()
        val grades = mutableListOf<List<Any>>()
        var voteResult: suspend () -> Result<Unit> = { Result.success(Unit) }
        var voteInfoResult: suspend () -> Result<DynamicVoteInfo> = { Result.success(DynamicVoteInfo(vote_id = 99)) }
        var gradeResult: suspend () -> Result<Unit> = { Result.success(Unit) }
        var summaryResult: suspend () -> Result<GradeDanmakuSummary> = { Result.success(GradeDanmakuSummary(2, 10.0, 8)) }
        fun sourceOwned() = accepted === lease && accountEpoch == 1L &&
            nativeSnapshot.sourceVersion == lease.sourceVersion && nativeSnapshot.source == lease.nativeSource.source
        val port = DesktopWindowsCommandVoteBinding(lease, 17, 70,
            current = { foreground && sourceOwned() }, cleanupCurrent = ::sourceOwned,
            admission = { action -> if (sourceOwned()) { action(); true } else false },
            readVote = { _, _ -> voteInfoResult() },
            writeVote = { _, id, indexes -> votes += id to indexes; voteResult() },
            writeGrade = { _, aid, cid, progress, id, score ->
                grades += listOf(aid, cid, progress, id, score); gradeResult() },
            readGrade = { _, _, _, _ -> summaryResult() }, onFeedback = messages::add)
        fun submit(item: CommandDanmakuItem, option: VoteOption, index: Int = option.optionIndex ?: 1) {
            assertTrue(if (item.voteKind == VoteDanmakuKind.GRADE) state.beginGradeSubmission(item, option)
                else state.beginVoteSubmission(item))
            submitOriginalDesktopCommandVote(item, option, index, playback, state, scope, port)
        }
        suspend fun drain() = scope.coroutineContext.job.children.toList().joinAll()
        override fun close() { scope.cancel() }
    }

    private fun command(extra: String, grade: Boolean = false) = assertNotNull(buildCommandDanmakuItem(
        DanmakuProto.CommandDm(id = 1, command = if (grade) "#GRADE#" else "#VOTE#", progress = 5000,
            extra = extra)))
    private fun vote() = command("""{"vote_id":99,"title":"choose","options":[{"idx":3,"desc":"third"}]}""")
    private fun grade() = command("""{"grade_id":3651137,"msg":"rate","mid_score":0,"count":76,"avg_score":9.8}""", true)

    @Test fun sparseOneBasedMetadataReachesRealSubmissionAdapter() = runBlocking {
        Harness().use { h ->
            val item = vote(); val option = item.voteOptions.single()
            assertEquals(3, option.optionIndex)
            h.submit(item, option); h.drain()
            assertEquals(listOf(99L to listOf(3)), h.votes)
            assertEquals(option, h.state.selection(item.id))
            assertFalse(h.state.beginVoteSubmission(item)); assertFalse(h.state.isSubmitting(item.id))
        }
    }

    @Test fun failedVoteReleasesPendingAndCanRetryWithoutFakeSelection() = runBlocking {
        Harness().use { h ->
            h.voteResult = { Result.failure(IllegalStateException("server refused")) }
            val item = vote(); val option = item.voteOptions.single()
            h.submit(item, option); h.drain()
            assertNull(h.state.selection(item.id)); assertFalse(h.state.isSubmitting(item.id))
            assertEquals(listOf("server refused"), h.messages)
            h.voteResult = { Result.success(Unit) }; h.submit(item, option); h.drain()
            assertEquals(2, h.votes.size); assertEquals(option, h.state.selection(item.id))
        }
    }

    @Test fun zeroBasedOrMissingVoteIdentityNeverInvokesPost() = runBlocking {
        Harness().use { h ->
            assertTrue(h.port.submitVote(99, listOf(0)).isFailure)
            assertTrue(h.port.submitVote(0, listOf(1)).isFailure)
            assertTrue(h.port.submitVote(99, emptyList()).isFailure)
            assertTrue(h.votes.isEmpty())
        }
    }

    @Test fun invalidOriginalVoteCanRetryAndDoesNotInventAcceptance() = runBlocking {
        Harness().use { h ->
            val item = vote().copy(voteId = "0"); val option = item.voteOptions.single()
            h.submit(item, option); h.drain()
            assertNull(h.state.selection(item.id)); assertFalse(h.state.isSubmitting(item.id))
            assertTrue(h.votes.isEmpty()); assertTrue(h.state.beginVoteSubmission(item))
        }
    }

    @Test fun gradeAcceptanceRefreshesMatchingMetadataRatherThanInventingStatistics() = runBlocking {
        Harness().use { h ->
            val item = grade(); val option = assertNotNull(resolveGradeStarOptions(item.voteOptions)[3])
            h.summaryResult = { Result.success(assertNotNull(resolveGradeDanmakuSummary(listOf(
                DanmakuProto.CommandDm(command = "#GRADE#", extra = """{"grade_id":999,"count":999,"avg_score":1}"""),
                DanmakuProto.CommandDm(command = "#GRADE#", extra = """{"grade_id":3651137,"count":2,"avg_score":10,"mid_score":8}""")), item.voteId))) }
            h.submit(item, option); h.drain()
            assertEquals(listOf(listOf<Any>(17L, 70L, 5000L, "3651137", 8)), h.grades)
            assertEquals(GradeDanmakuSummary(2, 10.0, 8), h.state.gradeSummary(item))
            assertEquals(8, h.state.gradeScore(item)); assertFalse(h.state.beginGradeSubmission(item, option))
        }
    }

    @Test fun acceptedGradeWithUnavailableSummaryKeepsOnlyConfirmedPersonalScore() = runBlocking {
        Harness().use { h ->
            h.summaryResult = { Result.failure(IllegalStateException("metadata unavailable")) }
            val item = grade(); val option = assertNotNull(resolveGradeStarOptions(item.voteOptions)[3])
            h.submit(item, option); h.drain()
            assertEquals(GradeDanmakuSummary(userScore = 8), h.state.gradeSummary(item))
            assertEquals(listOf("打分成功，统计暂不可用"), h.messages)
            assertFalse(h.state.isSubmitting(item.id))
        }
    }

    @Test fun rawSuccessFromAnotherPartIsRejectedBeforeGradePost() = runBlocking {
        Harness().use { h ->
            h.playback = VideoPlaybackUiState.Success(ViewInfo(bvid = "BVfixture", aid = 17, cid = 71), h.source.videoUrl)
            val item = grade(); val option = assertNotNull(resolveGradeStarOptions(item.voteOptions)[3])
            h.submit(item, option); h.drain()
            assertTrue(h.grades.isEmpty()); assertNull(h.state.selection(item.id))
            assertTrue(h.state.beginGradeSubmission(item, option))
        }
    }

    @Test fun sameVersionFullSourceReplacementCannotPublishLateVoteIntoSuccessor() = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<Unit>>(); h.voteResult = { reply.await() }
            val item = vote(); val option = item.voteOptions.single(); h.submit(item, option)
            val successor = CommandDanmakuOverlayState(); assertTrue(successor.beginVoteSubmission(item))
            h.nativeSnapshot = OwnedPlaybackSourceSnapshot(1, h.source.copy(startPositionSeconds = 20.0))
            reply.complete(Result.success(Unit)); h.drain()
            assertNull(h.state.selection(item.id)); assertNull(successor.selection(item.id))
            assertTrue(successor.isSubmitting(item.id)); assertTrue(h.messages.isEmpty())
        }
    }

    @Test fun accountGenerationABARejectsLateMetadataDespiteSameVideoAndCid() = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<DynamicVoteInfo>>(); h.voteInfoResult = { reply.await() }
            val task = async(start = CoroutineStart.UNDISPATCHED) { h.port.getVoteInfo(99) }
            h.accountEpoch = 2; reply.complete(Result.success(DynamicVoteInfo(vote_id = 99)))
            assertFailsWith<CancellationException> { task.await() }
            assertTrue(h.messages.isEmpty())
        }
    }

    @Test fun cancelledCallerReleasesItsPendingWithoutConvertingCancellationToFailure() = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<Unit>>(); h.voteResult = { reply.await() }
            val item = vote(); h.submit(item, item.voteOptions.single())
            h.scope.coroutineContext.job.children.single().cancelAndJoin()
            assertFalse(h.state.isSubmitting(item.id)); assertNull(h.state.selection(item.id))
            assertTrue(h.messages.isEmpty()); assertTrue(h.state.beginVoteSubmission(item))
        }
    }

    @Test fun freshSnapshotWrapperWithSameFullSourceDoesNotRetireRequest() = runBlocking {
        Harness().use { h ->
            h.nativeSnapshot = OwnedPlaybackSourceSnapshot(1, h.source.copy())
            val item = vote(); h.submit(item, item.voteOptions.single()); h.drain()
            assertNotNull(h.state.selection(item.id)); assertTrue(h.messages.isEmpty())
        }
    }

    @Test fun foregroundLossAllowsOnlySameSourcePendingCleanup() = runBlocking {
        Harness().use { h ->
            val reply = CompletableDeferred<Result<Unit>>(); h.voteResult = { reply.await() }
            val item = vote(); h.submit(item, item.voteOptions.single()); h.foreground = false
            h.scope.coroutineContext.job.children.single().cancelAndJoin()
            assertFalse(h.state.isSubmitting(item.id)); assertNull(h.state.selection(item.id))
            var successPublished = false
            assertFalse(h.port.publish(null) { successPublished = true }); assertFalse(successPublished)
            h.foreground = true; assertTrue(h.state.beginVoteSubmission(item))
        }
    }

    @Test fun oldFinallyCannotReleaseSuccessorPendingTicketOrRetiredSource() {
        Harness().use { h ->
            val key = h.state to "same-command"
            val old = h.port.capturePending(key); val next = h.port.capturePending(key)
            var clears = 0
            assertFalse(h.port.releasePending(old) { clears++ })
            assertTrue(h.port.releasePending(next) { clears++ }); assertEquals(1, clears)
            val stale = h.port.capturePending(key); h.accountEpoch = 2
            assertFalse(h.port.releasePending(stale) { clears++ }); assertEquals(1, clears)
        }
    }
}
