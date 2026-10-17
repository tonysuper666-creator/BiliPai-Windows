package com.bilipai.desktop.ui

import com.android.purebilibili.core.refresh.FavoriteSubscriptionBus
import com.android.purebilibili.core.store.DesktopOriginalCollectionSettings
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.FavFolderSource
import com.android.purebilibili.feature.list.resolveSubscribedFavoriteFolderKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import com.bilipai.desktop.plugins.DesktopPluginContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

private typealias SubscriptionFixture = DesktopFavoriteCoverPrefetchRegressionTest.Fixture

/** Actual Root entry, account epochs, owned Retrofit service, original VM/repository
 * and existing preference admission. Shared cover fixture seals every HTTP request
 * in memory and drives original continuations without a window or native playback. */
class DesktopFavoriteSubscriptionRegressionTest {
    @TempDir lateinit var directory: Path
    private val json = Json { encodeDefaults = true }
    private fun folder(id: Long = 1_650_276L, type: Int = 11) = FavFolder(
        id = id, fid = id, mid = 3_461_565_701_425_334L, title = "Subscribed $id", type = type,
        source = FavFolderSource.SUBSCRIBED)
    private fun collected(vararg rows: FavFolder, total: Int = rows.size) =
        """{"code":0,"data":{"count":$total,"list":[${rows.joinToString(",") { json.encodeToString(it) }}]}}"""
    private fun fields(request: Request): Map<String, String> {
        val form = assertIs<FormBody>(request.body)
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    @Test fun actualRootPageActivationStartsCatalogsOnceAcrossRepeatedVisibilityChanges(): Unit = runBlocking {
        SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
            f.awaitInitialized()
            f.ui { f.vm.setPageActive(true); f.vm.setPageActive(false); f.vm.setPageActive(true) }
            f.drainReady()
            assertEquals(1, f.catalogCalls.get())
            assertEquals(1, f.subscriptionRequests.size)
            assertTrue(f.loadJobs.none())
        }
    }

    @Test fun failedNextPageRetainsRowsAndOffsetUntilExplicitSamePageRetry(): Unit = runBlocking {
        SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
            val first = folder(); val second = folder(1_650_277L)
            val fail = AtomicBoolean(true)
            f.subscriptionReply = { request -> when (request.url.queryParameter("pn")) {
                "1" -> collected(first, total = 2)
                "2" -> if (fail.get()) """{"code":-799,"message":"page failed"}""" else collected(second, total = 2)
                else -> error("Unexpected subscription page")
            } }
            f.awaitInitialized()
            val visible = f.vm.subscribedFolders.value
            f.ui { f.vm.loadMoreSubscribedFolders() }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderProgressState.value.error != null }
            assertEquals(visible, f.vm.subscribedFolders.value)
            assertEquals(1, f.vm.subscribedFolderProgressState.value.currentPage)
            f.ui { f.vm.loadMoreSubscribedFolders(); f.vm.ensureSubscribedFoldersLoaded() }
            f.drainReady(); assertEquals(2, f.subscriptionRequests.size)
            fail.set(false)
            f.ui { f.vm.retrySubscribedFolders() }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolders.value.size == 2 }
            assertNull(f.vm.subscribedFolderProgressState.value.error)
            assertEquals(listOf("1", "2", "2"), f.subscriptionRequests.map { it.url.queryParameter("pn") })
        }
    }

    @Test fun folderUnsubscribeKeepsUndoRowAndUsesExpandedMediaIdForBothEndpoints(): Unit = runBlocking {
        SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
            val row = folder(); val key = resolveSubscribedFavoriteFolderKey(row)
            f.subscriptionReply = { collected(row) }
            f.awaitInitialized()
            f.ui { f.vm.toggleSubscribedFolderSubscription(row) }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderActions.value[key]?.isSubscribed == false }
            assertEquals(listOf(row.id), f.vm.subscribedFolders.value.map { it.id })
            f.ui { f.vm.toggleSubscribedFolderSubscription(row) }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderActions.value[key]?.isSubscribed == true }
            assertEquals(listOf("/x/v3/fav/folder/unfav", "/x/v3/fav/folder/fav"), f.mutationRequests.map { it.url.encodedPath })
            for (request in f.mutationRequests) {
                assertEquals("165027634", fields(request)["media_id"])
                assertEquals("synthetic-csrf-41", fields(request)["csrf"])
                assertFalse("season_id" in fields(request))
            }
        }
    }

    @Test fun failedMutationKeepsSubscriptionAndAllowsAnExplicitRetry(): Unit = runBlocking {
        SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
            val row = folder(); val key = resolveSubscribedFavoriteFolderKey(row)
            f.subscriptionReply = { collected(row) }
            f.mutationReply = { """{"code":-799,"message":"try again"}""" }
            f.awaitInitialized()
            f.ui { f.vm.toggleSubscribedFolderSubscription(row) }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderActions.value[key]?.error != null }
            assertTrue(f.vm.subscribedFolderActions.value.getValue(key).isSubscribed)
            assertEquals(listOf(row.id), f.vm.subscribedFolders.value.map { it.id })
            f.mutationReply = { """{"code":0}""" }
            f.ui { f.vm.toggleSubscribedFolderSubscription(row) }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderActions.value[key]?.isSubscribed == false }
            assertNull(f.vm.subscribedFolderActions.value.getValue(key).error)
            assertEquals(2, f.mutationRequests.size)
        }
    }

    @Test fun sameMidCredentialReplacementRejectsOldBusIdentityButAcceptsCurrentIdentity(): Unit = runBlocking {
        SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
            val row = folder(); val key = resolveSubscribedFavoriteFolderKey(row)
            f.subscriptionReply = { collected(row) }; f.awaitInitialized()
            val oldIdentity = f.entry.environment.favoriteAccountIdentity()
            // AccountStore deliberately deduplicates an identical cookie set. Replace
            // SESSDATA while preserving the MID to exercise an actual new epoch.
            f.switchAccount(41L, coldPreferenceBacking = false, credentialSuffix = "-replacement"); f.awaitInitialized()
            val currentIdentity = f.entry.environment.favoriteAccountIdentity()
            assertEquals(oldIdentity.mid, currentIdentity.mid)
            assertNotEquals(oldIdentity.generation, currentIdentity.generation)
            val before = f.vm.subscribedFolderActions.value
            f.owned { FavoriteSubscriptionBus.notifyChanged(oldIdentity, row, false) }
            f.drainReady(); assertEquals(before, f.vm.subscribedFolderActions.value)
            f.owned { FavoriteSubscriptionBus.notifyChanged(currentIdentity, row, false) }
            f.driveUntil { f.vm.subscribedFolderActions.value[key]?.isSubscribed == false }
            assertTrue(f.mutationRequests.isEmpty())
            assertEquals(listOf(row.id), f.vm.subscribedFolders.value.map { it.id })
        }
    }

    @Test fun confirmedWriteInvalidatesAnAlreadyRunningOldOffsetPage(): Unit = runBlocking {
        val pageGate = CountDownLatch(1)
        try {
            SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
                val first = folder(); val stale = folder(1_650_277L); val key = resolveSubscribedFavoriteFolderKey(first)
                f.subscriptionReply = { request ->
                    if (request.url.queryParameter("pn") == "1") collected(first, total = 2)
                    else { check(pageGate.await(6, TimeUnit.SECONDS)); collected(stale, total = 2) }
                }
                f.awaitInitialized()
                f.ui { f.vm.loadMoreSubscribedFolders() }
                f.driveUntil { f.subscriptionRequests.size == 2 }
                f.ui { f.vm.toggleSubscribedFolderSubscription(first) }
                f.driveUntil { f.vm.subscribedFolderActions.value[key]?.let { !it.isLoading && !it.isSubscribed } == true }
                pageGate.countDown()
                f.driveUntil { f.loadJobs.none() }
                assertEquals(listOf(first.id), f.vm.subscribedFolders.value.map { it.id })
                assertEquals(1, f.vm.subscribedFolderProgressState.value.currentPage)
                assertNull(f.vm.subscribedFolderProgressState.value.error)
            }
        } finally { pageGate.countDown() }
    }

    @Test fun busyActionIgnoresRepeatedClicksUntilTheOriginalRequestCompletes(): Unit = runBlocking {
        val mutationGate = CountDownLatch(1)
        try {
            SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
                val row = folder(); val key = resolveSubscribedFavoriteFolderKey(row)
                f.subscriptionReply = { collected(row) }
                f.mutationReply = { check(mutationGate.await(6, TimeUnit.SECONDS)); """{"code":0}""" }
                f.awaitInitialized()
                f.ui { f.vm.toggleSubscribedFolderSubscription(row) }
                f.driveUntil { f.mutationRequests.size == 1 }
                f.ui { f.vm.toggleSubscribedFolderSubscription(row); f.vm.toggleSubscribedFolderSubscription(row) }
                assertEquals(1, f.mutationRequests.size)
                mutationGate.countDown()
                f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderActions.value[key]?.isLoading == false }
                assertFalse(f.vm.subscribedFolderActions.value.getValue(key).isSubscribed)
            }
        } finally { mutationGate.countDown() }
    }

    @Test fun seasonSubscriptionUsesExistingCollectionEndpointAndKeepsUndoRow(): Unit = runBlocking {
        SubscriptionFixture(directory, folderCount = 0).useSuspending { f ->
            val row = folder(900L, type = 21); val key = resolveSubscribedFavoriteFolderKey(row)
            f.subscriptionReply = { collected(row) }; f.awaitInitialized()
            val context = DesktopPluginContext(f.store)
            f.owned { f.entry.environment.persistCollectionSubscription(900L, true) }
            assertTrue(DesktopOriginalCollectionSettings.isCollectionSubscribed(context, 900L).first())
            f.ui { f.vm.toggleSubscribedFolderSubscription(row) }
            f.driveUntil { f.loadJobs.none() && f.vm.subscribedFolderActions.value[key]?.isLoading == false }
            assertFalse(f.vm.subscribedFolderActions.value.getValue(key).isSubscribed)
            val request = f.mutationRequests.single()
            assertEquals("/x/v3/fav/season/unfav", request.url.encodedPath)
            assertEquals("900", fields(request)["season_id"])
            assertEquals("web", fields(request)["platform"])
            assertEquals("synthetic-csrf-41", fields(request)["csrf"])
            assertFalse("media_id" in fields(request))
            assertEquals(listOf(900L), f.vm.subscribedFolders.value.map { it.id })
            assertFalse(DesktopOriginalCollectionSettings.isCollectionSubscribed(context, 900L).first())
        }
    }
}
