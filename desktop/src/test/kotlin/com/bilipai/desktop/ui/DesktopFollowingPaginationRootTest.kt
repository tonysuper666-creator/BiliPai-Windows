package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.FollowingCacheStore
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.feature.following.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

private typealias FollowingRootFixture = DesktopFavoriteCoverPrefetchRegressionTest.Fixture

/** Real current original VM/cache over the existing Root credential-aware API.
 * The bounded transport serves protocol bodies in memory, without remote HTTP. */
class DesktopFollowingPaginationRootTest {
    @TempDir lateinit var directory: Path
    private fun environment(f: FollowingRootFixture): DesktopFollowingEnvironment {
        val owner = f.entry
        val cache = DesktopFollowingCacheContext(f.store, owner::isOwned) { action ->
            f.repository.withPrimaryPlaybackAdmission(owner.epoch, owner::isOwned) {
                if (!owner.isOwned()) false else { action(); true }
            }
        }
        return DesktopFollowingEnvironment(owner.environment, cache)
    }
    private fun page(mids: List<Long>, total: Int = mids.size) =
        """{"code":0,"data":{"total":$total,"list":[${mids.joinToString(",") { """{"mid":$it,"uname":"UP $it","face":"https://image.invalid/$it.jpg"}""" }}]}}"""
    private fun users(vm: FollowingListViewModel) = assertIs<FollowingListUiState.Success>(vm.uiState.value).users.map { it.mid }

    @Test fun startupLoadsExactlyOnePageInsteadOfTheEntireDirectory(): Unit = runBlocking {
        FollowingRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized(); f.followingReply = { page((101L..150L).toList(), 1_000) }
            val vm = FollowingListViewModel(environment(f))
            f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.loadJobs.none() }
            assertEquals(50, users(vm).size)
            assertTrue(assertIs<FollowingListUiState.Success>(vm.uiState.value).hasMore)
            assertEquals(1, f.followingRequests.size)
            assertEquals("1", f.followingRequests.single().url.queryParameter("pn"))
            assertEquals("50", f.followingRequests.single().url.queryParameter("ps"))
        }
    }

    @Test fun ordinaryLoadMoreRequestsOnlyOneAdditionalPage(): Unit = runBlocking {
        FollowingRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized()
            f.followingReply = { request -> page(if (request.url.queryParameter("pn") == "1") listOf(101L) else listOf(102L), 3) }
            val vm = FollowingListViewModel(environment(f))
            f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.loadJobs.none() }
            f.ui { vm.loadMore() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf("1", "2"), f.followingRequests.map { it.url.queryParameter("pn") })
            assertEquals(listOf(101L, 102L), users(vm))
            assertTrue(assertIs<FollowingListUiState.Success>(vm.uiState.value).hasMore)
        }
    }

    @Test fun explicitLoadAllStopsWhenAServerPageAddsNoNewUser(): Unit = runBlocking {
        FollowingRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized()
            f.followingReply = { request -> when (request.url.queryParameter("pn")) {
                "1" -> page(listOf(101L, 102L), 100)
                "2", "3" -> page(listOf(102L, 103L), 100)
                else -> error("Duplicate-page following pagination did not terminate")
            } }
            val vm = FollowingListViewModel(environment(f))
            f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.loadJobs.none() }
            f.ui { vm.loadAllForSearch() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf("1", "2", "3"), f.followingRequests.map { it.url.queryParameter("pn") })
            assertEquals(listOf(101L, 102L, 103L), users(vm))
            assertFalse(assertIs<FollowingListUiState.Success>(vm.uiState.value).hasMore)
        }
    }

    @Test fun failedNextPageKeepsRowsAndRetriesTheSameOffset(): Unit = runBlocking {
        FollowingRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized(); f.followingReply = { page(listOf(101L), 2) }
            val vm = FollowingListViewModel(environment(f))
            f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.loadJobs.none() }
            f.followingReply = { """{"code":-799,"message":"try again"}""" }
            f.ui { vm.loadMore() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf(101L), users(vm))
            assertFalse(assertIs<FollowingListUiState.Success>(vm.uiState.value).isLoadingMore)
            f.followingReply = { page(listOf(102L), 2) }
            f.ui { vm.loadMore() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf("1", "2", "2"), f.followingRequests.map { it.url.queryParameter("pn") })
            assertEquals(listOf(101L, 102L), users(vm))
        }
    }

    @Test fun cachedFirstScreenRemainsUntilTheActualFirstPageArrives(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        try {
            FollowingRootFixture(directory, 0).useSuspending { f ->
                f.awaitInitialized(); val env = environment(f)
                f.owned { FollowingCacheStore.saveSnapshot(env.cacheContext.asAndroidContext(), 41L, 1, listOf(FollowingUser(mid=901L,uname="Cached"))) }
                f.followingReply = { check(gate.await(6, TimeUnit.SECONDS)); page(listOf(101L)) }
                val vm = FollowingListViewModel(env)
                f.ui { vm.loadFollowingList(41L) }
                assertEquals(listOf(901L), users(vm))
                f.driveUntil { f.followingRequests.size == 1 }
                assertEquals(listOf(901L), users(vm))
                gate.countDown(); f.driveUntil { f.loadJobs.none() }
                assertEquals(listOf(101L), users(vm))
            }
        } finally { gate.countDown() }
    }

    @Test fun busyPagingDeduplicatesFurtherScrollAndLoadAllCommands(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        try {
            FollowingRootFixture(directory, 0).useSuspending { f ->
                f.awaitInitialized()
                f.followingReply = { request ->
                    if (request.url.queryParameter("pn") == "1") page(listOf(101L), 3)
                    else { check(gate.await(6, TimeUnit.SECONDS)); page(listOf(102L), 3) }
                }
                val vm = FollowingListViewModel(environment(f))
                f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.loadJobs.none() }
                f.ui { vm.loadMore() }; f.driveUntil { f.followingRequests.size == 2 }
                f.ui { vm.loadMore(); vm.loadAllForSearch() }
                assertEquals(2, f.followingRequests.size)
                gate.countDown(); f.driveUntil { f.loadJobs.none() }
                assertEquals(listOf(101L, 102L), users(vm))
            }
        } finally { gate.countDown() }
    }

    @Test fun refreshRetiresAnOlderPageAndItsCachePublication(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        try {
            FollowingRootFixture(directory, 0).useSuspending { f ->
                f.awaitInitialized(); val firstPages = AtomicInteger(); val env = environment(f)
                f.followingReply = { request ->
                    if (request.url.queryParameter("pn") == "1") {
                        if (firstPages.incrementAndGet() == 1) page(listOf(101L), 3) else page(listOf(201L))
                    } else { check(gate.await(6, TimeUnit.SECONDS)); page(listOf(102L), 3) }
                }
                val vm = FollowingListViewModel(env)
                f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.loadJobs.none() }
                f.ui { vm.loadMore() }; f.driveUntil { f.followingRequests.size == 2 }
                f.ui { vm.loadFollowingList(41L, forceRefresh=true) }
                f.driveUntil { (vm.uiState.value as? FollowingListUiState.Success)?.users?.map { it.mid } == listOf(201L) }
                gate.countDown(); f.driveUntil { f.loadJobs.none() }
                assertEquals(listOf(201L), users(vm))
                f.owned { assertEquals(listOf(201L), FollowingCacheStore.getSnapshot(env.cacheContext.asAndroidContext(),41L)?.users?.map { it.mid }) }
            }
        } finally { gate.countDown() }
    }

    @Test fun retiredRootRejectsPendingResultsAndAnyNewPagingCommand(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        try {
            FollowingRootFixture(directory, 0).useSuspending { f ->
                f.awaitInitialized(); f.followingReply = { check(gate.await(6, TimeUnit.SECONDS)); page(listOf(101L)) }
                val vm = FollowingListViewModel(environment(f))
                f.ui { vm.loadFollowingList(41L) }; f.driveUntil { f.followingRequests.size == 1 }
                f.entry.close(); gate.countDown(); f.driveUntil { f.loadJobs.none() }
                assertIs<FollowingListUiState.Loading>(vm.uiState.value)
                assertNull(f.store.preferences("following_cache")["following_payload_v1"])
                f.ui {
                    assertFailsWith<CancellationException> { vm.loadMore() }
                    assertFailsWith<CancellationException> { vm.loadAllForSearch() }
                }
            }
        } finally { gate.countDown() }
    }
}
