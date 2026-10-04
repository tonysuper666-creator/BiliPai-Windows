package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.FollowStateChange
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopWindowsVideoMetadataSectionTest {
    private val request = PlaybackRequest.create("BV1", aid = 1L, cid = 11L)
    private val info = ViewInfo(bvid = "BV1", aid = 1L, cid = 11L)

    @Test fun originalCommittedLoadAndPartAreRequired() {
        val store = PlaybackSessionStore()
        val load = store.beginLoadRequest(request)
        assertFalse(desktopWindowsVideoMetadataMatchesSource(info, request, store.state.value, load.requestToken))
        store.updateCurrentMedia(cid = 11L)
        assertTrue(desktopWindowsVideoMetadataMatchesSource(info, request, store.state.value, load.requestToken))
        assertFalse(desktopWindowsVideoMetadataMatchesSource(info.copy(cid = 12L), request, store.state.value, load.requestToken))
        assertFalse(desktopWindowsVideoMetadataMatchesSource(info.copy(bvid = "BV2"), request, store.state.value, load.requestToken))
    }

    @Test fun sameBvCidNewLoadCannotReusePreviousMetadataToken() {
        val store = PlaybackSessionStore()
        val first = store.beginLoadRequest(request)
        store.updateCurrentMedia(cid = 11L)
        val second = store.beginLoadRequest(request.copy(force = true))
        assertTrue(second.requestToken > first.requestToken)
        assertFalse(desktopWindowsVideoMetadataMatchesSource(info, request, store.state.value, first.requestToken))
        assertTrue(desktopWindowsVideoMetadataMatchesSource(info, request, store.state.value, second.requestToken))
    }

    @Test fun sameVideoPartSwitchRejectsOldPartAndAcceptsItsCommittedReplacement() {
        val store = PlaybackSessionStore()
        val load = store.beginLoadRequest(request)
        store.updateCurrentMedia(cid = 11L)
        store.updateCurrentMedia(cid = 12L)
        assertFalse(desktopWindowsVideoMetadataMatchesSource(info, request, store.state.value, load.requestToken))
        assertTrue(desktopWindowsVideoMetadataMatchesSource(info.copy(cid = 12L), request.copy(cid = 12L),
            store.state.value, load.requestToken))
    }

    private open class ExistingTeamFacet : DesktopCreatorTeamBindings {
        val queries = mutableListOf<Long>()
        val mutations = mutableListOf<Pair<Long, Boolean>>()
        override val followStateChanges: Flow<FollowStateChange> = emptyFlow()
        override suspend fun checkFollowStatus(mid: Long): Boolean { queries += mid; return true }
        override suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
            mutations += mid to follow
            return Result.success(follow)
        }
    }

    @Test fun onlyExistingOriginalFacetReceivesTheExactMemberAndFollowIntent(): Unit = runBlocking {
        val facet = ExistingTeamFacet()
        val lease = DesktopWindowsVideoMetadataLease { true }
        val bindings = lease.creatorTeam(facet)
        assertTrue(bindings.checkFollowStatus(42L))
        assertFalse(bindings.followUser(42L, false).getOrThrow())
        assertEquals(listOf(42L), facet.queries)
        assertEquals(listOf(42L to false), facet.mutations)
        lease.close()
    }

    @Test fun closedPanelCannotBeRevivedByReturningToEqualMetadata(): Unit = runBlocking {
        var current = true
        val facet = ExistingTeamFacet()
        val old = DesktopWindowsVideoMetadataLease { current }
        val bindings = old.creatorTeam(facet)
        old.close()
        current = false
        current = true
        assertFailsWith<CancellationException> { bindings.checkFollowStatus(42L) }
        assertFailsWith<CancellationException> { bindings.followUser(42L, true) }
        assertTrue(facet.queries.isEmpty())
        assertTrue(facet.mutations.isEmpty())
        val reopened = DesktopWindowsVideoMetadataLease { current }
        assertTrue(reopened.creatorTeam(facet).checkFollowStatus(42L))
        assertEquals(listOf(42L), facet.queries)
        reopened.close()
    }

    @Test fun lateQueryAfterSourceRetirementCannotPopulateTheOriginalTeam(): Unit = runBlocking {
        withTimeout(3_000L) {
            var current = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val facet = object : ExistingTeamFacet() {
                override suspend fun checkFollowStatus(mid: Long): Boolean {
                    queries += mid
                    entered.complete(Unit)
                    release.await()
                    return true
                }
            }
            val lease = DesktopWindowsVideoMetadataLease { current }
            val pending = async { lease.creatorTeam(facet).checkFollowStatus(42L) }
            entered.await()
            current = false
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(listOf(42L), facet.queries)
            lease.close()
        }
    }

    @Test fun lateSuccessfulFollowAfterPanelCloseCannotBecomeUiSuccess(): Unit = runBlocking {
        withTimeout(3_000L) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val facet = object : ExistingTeamFacet() {
                override suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
                    mutations += mid to follow
                    entered.complete(Unit)
                    release.await()
                    return Result.success(follow)
                }
            }
            val lease = DesktopWindowsVideoMetadataLease { true }
            val pending = async { lease.creatorTeam(facet).followUser(42L, true) }
            entered.await()
            lease.close()
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(listOf(42L to true), facet.mutations)
        }
    }

    @Test fun lateConfirmedEventFromExistingFacetCannotReachRetiredPanel(): Unit = runBlocking {
        var current = true
        val first = FollowStateChange(42L, true)
        val late = FollowStateChange(42L, false)
        val facet = object : ExistingTeamFacet() {
            override val followStateChanges = flow {
                emit(first)
                current = false
                emit(late)
            }
        }
        val received = mutableListOf<FollowStateChange>()
        val lease = DesktopWindowsVideoMetadataLease { current }
        assertFailsWith<CancellationException> {
            lease.creatorTeam(facet).followStateChanges.collect { received += it }
        }
        assertEquals(listOf(first), received)
        lease.close()
    }
}
