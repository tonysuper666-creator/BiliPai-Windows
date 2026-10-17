package com.bilipai.desktop.ui

import com.android.purebilibili.feature.list.isFavoriteFolderPrivate
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

private typealias EditorFixture = DesktopFavoriteCoverPrefetchRegressionTest.Fixture

/** Actual retained Entry -> original VM -> original repository -> sole Retrofit
 * service. The existing sealed memory transport rejects every unexpected request.
 * No Window, real account, device or external HTTP request participates. */
class DesktopFavoriteEditorRootRegressionTest {
    @TempDir lateinit var directory: Path
    private fun metadata(id: Long = 101L, attr: Int = 22) =
        """{"code":0,"data":{"id":$id,"title":"Server title","intro":"Existing introduction","cover":"https://image.invalid/actual-folder-cover.jpg","attr":$attr}}"""
    private fun fields(request: Request): Map<String, String> {
        val form = assertIs<FormBody>(request.body)
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    @Test fun metadataReadAndSavePreserveRealCoverIntroPrivacyAndCapturedSubject(): Unit = runBlocking {
        EditorFixture(directory, folderCount = 2).useSuspending { f ->
            f.folderInfoReply = { metadata() }; f.folderEditReply = { """{"code":0}""" }
            f.awaitInitialCovers(2)
            val before = f.preferenceBytes()?.toList()
            val info = f.owned { f.vm.loadFavoriteFolderForEditing(101L).getOrThrow() }
            assertEquals("Existing introduction", info.intro)
            assertEquals("https://image.invalid/actual-folder-cover.jpg", info.cover)
            assertEquals(22, info.attr); assertFalse(isFavoriteFolderPrivate(info.attr))
            assertNotEquals(info.cover, f.vm.folders.value.first().cover)
            assertEquals(before, f.preferenceBytes()?.toList())
            val read = f.folderInfoRequests.single()
            assertEquals("/x/v3/fav/folder/info", read.url.encodedPath)
            assertEquals("101", read.url.queryParameter("media_id"))
            f.ui { f.vm.switchFolder(1) }
            assertEquals(1, f.vm.selectedFolderIndex.value)
            val edit = f.launchFromAction {
                f.vm.editFavoriteFolder(info.id, "Renamed", info.intro, isFavoriteFolderPrivate(info.attr), info.cover)
            }
            f.driveUntil { edit.isCompleted && f.folderEditRequests.size == 1 }
            val saved = fields(f.folderEditRequests.single())
            assertEquals("101", saved["media_id"]); assertEquals("Renamed", saved["title"])
            assertEquals(info.intro, saved["intro"]); assertEquals(info.cover, saved["cover"])
            assertEquals("0", saved["privacy"]); assertEquals("synthetic-csrf-41", saved["csrf"])
        }
    }

    @Test fun invalidServerMetadataRetainsTheCatalogAndAnExplicitRetryUsesTheSameSubject(): Unit = runBlocking {
        EditorFixture(directory, folderCount = 1).useSuspending { f ->
            f.awaitInitialCovers(1); val before = f.vm.folders.value
            for (reply in listOf("""{"code":-799,"message":"retry metadata"}""", metadata(102L), """{"code":0,"data":null}""")) {
                f.folderInfoReply = { reply }
                assertTrue(f.owned { f.vm.loadFavoriteFolderForEditing(101L) }.isFailure)
                assertEquals(before, f.vm.folders.value); assertTrue(f.folderEditRequests.isEmpty())
            }
            f.folderInfoReply = { metadata() }
            assertEquals(101L, f.owned { f.vm.loadFavoriteFolderForEditing(101L).getOrThrow() }.id)
            assertEquals(listOf("101", "101", "101", "101"),
                f.folderInfoRequests.map { it.url.queryParameter("media_id") })
            assertTrue(f.folderEditRequests.isEmpty())
        }
    }

    @Test fun targetsOutsideTheActualOwnedCatalogCannotBeReadOrEdited(): Unit = runBlocking {
        EditorFixture(directory, folderCount = 1).useSuspending { f ->
            f.awaitInitialCovers(1)
            for (id in listOf(0L, -1L, 102L, 99_999L)) {
                assertTrue(f.owned { f.vm.loadFavoriteFolderForEditing(id) }.isFailure)
                f.ui { f.vm.editFavoriteFolder(id, "Foreign", "", false, "") }
            }
            f.drainReady()
            assertTrue(f.folderInfoRequests.isEmpty()); assertTrue(f.folderEditRequests.isEmpty())
            assertFalse(f.vm.isFavoriteManagingState.value)
        }
    }

    @Test fun realCredentialEpochRetirementRejectsLateMetadataIncludingSameMidReauthentication(): Unit = runBlocking {
        for (sameMid in listOf(false, true)) {
            EditorFixture(directory.resolve("epoch-$sameMid"), folderCount = 1).useSuspending { f ->
                val gate = CountDownLatch(1)
                f.folderInfoReply = { check(gate.await(6, TimeUnit.SECONDS)); metadata() }
                try {
                    f.awaitInitialCovers(1); val before = f.vm.folders.value
                    val request = f.entry.scope.async { f.vm.loadFavoriteFolderForEditing(101L) }
                    f.driveUntil { f.folderInfoRequests.size == 1 }
                    f.ui { f.saveAccount(if (sameMid) 41L else 42L, credentialSuffix = "-replacement") }
                    gate.countDown(); f.driveUntil { request.isCompleted }
                    assertTrue(request.isCancelled)
                    assertFailsWith<CancellationException> { request.await() }
                    assertEquals(before, f.vm.folders.value); assertTrue(f.folderEditRequests.isEmpty())
                } finally { gate.countDown() }
            }
        }
    }

    @Test fun entryWindowAndActualCallerRetirementCannotPublishLateMetadata(): Unit = runBlocking {
        for (kind in listOf("entry", "window", "caller")) {
            EditorFixture(directory.resolve(kind), folderCount = 1).useSuspending { f ->
                val gate = CountDownLatch(1)
                f.folderInfoReply = { check(gate.await(6, TimeUnit.SECONDS)); metadata() }
                try {
                    f.awaitInitialCovers(1); val before = f.vm.folders.value
                    val request = f.entry.scope.async { f.vm.loadFavoriteFolderForEditing(101L) }
                    f.driveUntil { f.folderInfoRequests.size == 1 }
                    f.ui { when (kind) {
                        "entry" -> f.entry.close()
                        "window" -> f.windowAlive.set(false)
                        else -> request.cancel("retire actual metadata caller")
                    } }
                    gate.countDown(); f.driveUntil { request.isCompleted }
                    assertTrue(request.isCancelled)
                    assertFailsWith<CancellationException> { request.await() }
                    assertEquals(before, f.vm.folders.value); assertTrue(f.folderEditRequests.isEmpty())
                } finally { gate.countDown() }
            }
        }
    }

    @Test fun inactivePageRejectsReadAndAQueuedEditBeforeTheRealNetworkSend(): Unit = runBlocking {
        EditorFixture(directory, folderCount = 1).useSuspending { f ->
            f.awaitInitialCovers(1)
            f.ui { f.vm.setPageActive(false) }
            assertFailsWith<CancellationException> { f.owned { f.vm.loadFavoriteFolderForEditing(101L) } }
            assertTrue(f.folderInfoRequests.isEmpty())
            f.ui { f.vm.setPageActive(true) }
            val edit = f.launchFromAction { f.vm.editFavoriteFolder(101L, "Renamed", "", false, "") }
            f.ui { f.vm.setPageActive(false) }
            f.driveUntil { edit.isCompleted }
            assertTrue(f.folderEditRequests.isEmpty()); assertFalse(f.vm.isFavoriteManagingState.value)
        }
    }

    @Test fun duplicateSaveWhileTheActualOriginalMutationIsPendingSendsOnlyOnce(): Unit = runBlocking {
        EditorFixture(directory, folderCount = 1).useSuspending { f ->
            val gate = CountDownLatch(1)
            f.folderEditReply = { check(gate.await(6, TimeUnit.SECONDS)); """{"code":0}""" }
            try {
                f.awaitInitialCovers(1)
                val first = f.launchFromAction { f.vm.editFavoriteFolder(101L, "Renamed", "Existing", true, "https://image.invalid/real.jpg") }
                f.driveUntil { f.folderEditRequests.size == 1 }
                f.ui { f.vm.editFavoriteFolder(101L, "Duplicate", "", false, "") }
                assertEquals(1, f.folderEditRequests.size); assertTrue(f.vm.isFavoriteManagingState.value)
                gate.countDown(); f.driveUntil { first.isCompleted }
                assertEquals(1, f.folderEditRequests.size); assertFalse(f.vm.isFavoriteManagingState.value)
                assertEquals("1", fields(f.folderEditRequests.single())["privacy"])
            } finally { gate.countDown() }
        }
    }

    @Test fun blankTitleCannotQueueAMutationOrChangeTheExistingCatalog(): Unit = runBlocking {
        EditorFixture(directory, folderCount = 1).useSuspending { f ->
            f.awaitInitialCovers(1); val before = f.vm.folders.value
            f.ui { f.vm.editFavoriteFolder(101L, "   ", "Existing", false, "https://image.invalid/real.jpg") }
            f.drainReady()
            assertEquals(before, f.vm.folders.value); assertTrue(f.folderEditRequests.isEmpty())
            assertFalse(f.vm.isFavoriteManagingState.value)
        }
    }
}
