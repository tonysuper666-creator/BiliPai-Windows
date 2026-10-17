package com.bilipai.desktop.ui

import com.android.purebilibili.feature.following.DesktopFollowingCacheContext
import com.android.purebilibili.feature.following.DesktopFollowingEnvironment
import com.android.purebilibili.feature.following.FansListViewModel
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

private typealias FansRootFixture = DesktopFavoriteCoverPrefetchRegressionTest.Fixture

/** Actual original fans VM and production environment over the existing retained
 * Root's credential-aware Retrofit transport. No window, device or remote HTTP. */
class DesktopFansListRootRegressionTest {
    @TempDir lateinit var directory: Path

    private fun model(f: FansRootFixture, mid: Long): FansListViewModel {
        val owner = f.entry
        val cache = DesktopFollowingCacheContext(f.store, owner::isOwned) {
            error("The original fans page must not write the following-list cache")
        }
        return FansListViewModel(mid, DesktopFollowingEnvironment(owner.environment, cache))
    }
    private fun page(vararg mids: Long, total: Int = mids.size) =
        """{"code":0,"data":{"total":$total,"list":[${mids.joinToString(",") { """{"mid":$it,"uname":"Fan $it","face":"https://image.invalid/$it.jpg"}""" }}]}}"""

    @Test fun invalidSubjectStopsWithoutAnyFansRequest(): Unit = runBlocking {
        FansRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized()
            val vm = model(f, 0)
            f.ui { vm.load() }
            assertTrue(f.fansRequests.isEmpty())
            assertEquals("无效的用户 UID", vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.hasMore)
        }
    }

    @Test fun firstReadUsesTheOriginalFansEndpointAndDefaultsForTheRequestedSubject(): Unit = runBlocking {
        FansRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized()
            f.fansReply = { page(101L) }
            val vm = model(f, 600L)
            assertTrue(f.fansRequests.isEmpty())
            f.ui { vm.load() }
            f.driveUntil { f.loadJobs.none() }
            val request = f.fansRequests.single()
            assertEquals("/x/relation/fans", request.url.encodedPath)
            assertEquals("600", request.url.queryParameter("vmid"))
            assertEquals("1", request.url.queryParameter("pn"))
            assertEquals("20", request.url.queryParameter("ps"))
            assertEquals("desc", request.url.queryParameter("order"))
            assertEquals("attention", request.url.queryParameter("order_type"))
            assertEquals(listOf(101L), vm.state.value.users.map { it.mid })
            assertFalse(vm.state.value.hasMore)
            assertTrue(f.store.preferences("following_cache").isEmpty())
        }
    }

    @Test fun duplicateOnlyPageEndsPaginationWithoutDiscardingExistingUsers(): Unit = runBlocking {
        FansRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized()
            f.fansReply = { request -> when (request.url.queryParameter("pn")) {
                "1" -> page(101L, 102L, total = 5)
                "2" -> page(102L, 103L, total = 5)
                "3" -> page(101L, 102L, total = 5)
                else -> error("Fans pagination did not terminate")
            } }
            val vm = model(f, 600L)
            repeat(3) { f.ui { vm.load() }; f.driveUntil { f.loadJobs.none() } }
            assertEquals(listOf(101L, 102L, 103L), vm.state.value.users.map { it.mid })
            assertEquals(5, vm.state.value.total)
            assertFalse(vm.state.value.hasMore)
            f.ui { vm.load() }; f.drainReady()
            assertEquals(listOf("1", "2", "3"), f.fansRequests.map { it.url.queryParameter("pn") })
        }
    }

    @Test fun failedNextPageKeepsRowsAndRetriesTheSamePage(): Unit = runBlocking {
        FansRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized()
            f.fansReply = { page(101L, total = 2) }
            val vm = model(f, 600L)
            f.ui { vm.load() }; f.driveUntil { f.loadJobs.none() }
            f.fansReply = { """{"code":-799,"message":"try again"}""" }
            f.ui { vm.load() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf(101L), vm.state.value.users.map { it.mid })
            assertNotNull(vm.state.value.error)
            f.fansReply = { page(102L, total = 2) }
            f.ui { vm.load() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf("1", "2", "2"), f.fansRequests.map { it.url.queryParameter("pn") })
            assertEquals(listOf(101L, 102L), vm.state.value.users.map { it.mid })
            assertNull(vm.state.value.error)
        }
    }

    @Test fun failedRefreshKeepsRowsAndCanRetryEvenAfterPreviouslyReachingTheEnd(): Unit = runBlocking {
        FansRootFixture(directory, 0).useSuspending { f ->
            f.awaitInitialized(); f.fansReply = { page(101L) }
            val vm = model(f, 600L)
            f.ui { vm.load() }; f.driveUntil { f.loadJobs.none() }
            f.fansReply = { """{"code":-799,"message":"retry refresh"}""" }
            f.ui { vm.load(true) }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf(101L), vm.state.value.users.map { it.mid })
            assertNotNull(vm.state.value.error)
            f.fansReply = { page(201L) }
            f.ui { vm.load() }; f.driveUntil { f.loadJobs.none() }
            assertEquals(listOf("1", "1", "1"), f.fansRequests.map { it.url.queryParameter("pn") })
            assertEquals(listOf(201L), vm.state.value.users.map { it.mid })
            assertNull(vm.state.value.error)
        }
    }

    @Test fun busyLoadIgnoresFurtherLoadAndRefreshCommands(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        try {
            FansRootFixture(directory, 0).useSuspending { f ->
                f.awaitInitialized()
                f.fansReply = { check(gate.await(6, TimeUnit.SECONDS)); page(101L) }
                val vm = model(f, 600L)
                f.ui { vm.load() }; f.driveUntil { f.fansRequests.size == 1 }
                f.ui { vm.load(); vm.load(true) }
                assertEquals(1, f.fansRequests.size)
                gate.countDown(); f.driveUntil { f.loadJobs.none() }
                assertEquals(listOf(101L), vm.state.value.users.map { it.mid })
            }
        } finally { gate.countDown() }
    }

    @Test fun retiredActualRootRejectsLateFansResultsAndFurtherCommands(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        try {
            FansRootFixture(directory, 0).useSuspending { f ->
                f.awaitInitialized()
                f.fansReply = { check(gate.await(6, TimeUnit.SECONDS)); page(101L) }
                val vm = model(f, 600L)
                f.ui { vm.load() }; f.driveUntil { f.fansRequests.size == 1 }
                f.entry.close(); gate.countDown()
                f.driveUntil { f.loadJobs.none() }
                assertTrue(vm.state.value.users.isEmpty())
                assertNull(vm.state.value.error)
                f.ui { assertFailsWith<CancellationException> { vm.load() } }
            }
        } finally { gate.countDown() }
    }

    @Test fun nativeFansIdentityRestoresWithoutChangingOrdinaryFollowingOrOldSavedKeys() {
        val fans = BiliPaiNavKey.Following(600L, fans = true)
        val following = BiliPaiNavKey.Following(600L)
        assertEquals("fans/600", fans.toLegacyRoute())
        assertEquals(fans, legacyRouteToBiliPaiNavKey(fans.toLegacyRoute()))
        assertEquals(following, legacyRouteToBiliPaiNavKey(following.toLegacyRoute()))
        assertNotEquals(resolveNavigation3SaveableStateKey(fans), resolveNavigation3SaveableStateKey(following))
        assertEquals(following, Json.decodeFromString<BiliPaiNavKey.Following>("""{"mid":600}"""))
        assertEquals(fans, Json.decodeFromString<BiliPaiNavKey.Following>(Json.encodeToString(fans)))
    }
}
