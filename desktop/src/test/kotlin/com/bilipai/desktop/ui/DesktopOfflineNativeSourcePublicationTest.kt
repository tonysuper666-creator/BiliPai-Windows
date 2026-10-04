package com.bilipai.desktop.ui

import com.android.purebilibili.feature.download.DownloadOptions
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalDownloadTask
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.platform.DesktopOfflineMedia3ErrorCodes
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real headless MPV requested-source state and the actual offline binding/gate.
 * No native Window or decoded playback is claimed by these tests.
 */
class DesktopOfflineNativeSourcePublicationTest {
    private class Fixture(private val networkAvailable:Boolean=false) : AutoCloseable {
        val directory = Files.createTempDirectory("bp-offline-publication-")
        val sessions = DesktopSessionStore(directory.resolve("private-session.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val alive = AtomicBoolean(true)
        val player = MpvPlayer()
        val rootGate = DesktopHomeRetainedGate(repository.dynamicCacheSessionGuard,
            checkNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner()),
            { repository.sessionEpoch }, { repository.account.value?.mid }, alive::get, scope)
        private fun task(index: Int): DownloadTask {
            val initial = OriginalDownloadTask(bvid = "local-publication-$index", cid = index.toLong(),
                title = "Private local source $index", cover = "", ownerName = "", ownerFace = "",
                duration = 30, quality = 80, qualityDesc = "local", videoUrl = "", audioUrl = "",
                status = DownloadStatus.COMPLETED, progress = 1f, options = DownloadOptions(includeDanmaku = false))
            val wrapper = DownloadTask(initial, directory.resolve("cache").toString())
            val folder = java.nio.file.Path.of(wrapper.directory)
            Files.createDirectories(folder)
            Files.writeString(folder.resolve(".bilipai-download"), wrapper.id)
            val file = folder.resolve("local-fixture.media")
            Files.write(file, byteArrayOf(1, 2, 3))
            return wrapper.copy(item = initial.copy(filePath = file.toString(), fileSize = 3, downloadedSize = 3))
        }
        val tasks = listOf(task(1), task(2))
        val stateFile = directory.resolve("downloads.json").also {
            Files.writeString(it, Json.encodeToString(ListSerializer(DownloadTask.serializer()), tasks))
        }
        val manager = DesktopDownloadManager(repository.playbackHttpClient, stateFile,
            DownloadMuxer { _, _, _ -> error("Completed local fixtures must not download or mux") },
            publication = DesktopRepositoryPlaybackPublication(repository))
        val retained = DesktopRetainedMedia(scope, player) { }
        val entryScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
        val binding = DesktopOfflineTaskPlayerBinding(manager, retained, null, entryScope,
            rootGate.epoch, { repository.sessionEpoch }, rootGate::owns, rootGate::commit,
            { networkAvailable }, { "No decoder used by headless source identity test" })
        suspend fun open(index: Int) {
            checkNotNull(binding.open(tasks[index].id) { error("Private completed file must open offline") }).join()
            assertNull(binding.error)
            assertTrue(binding.ownsAcceptedSource())
        }
        fun source() = assertNotNull(player.currentSourceSnapshot())
        override fun close() {
            binding.close(); rootGate.close(); alive.set(false)
            entryScope.cancel(); scope.cancel(); manager.close(); retained.close(); player.close()
        }
    }

    @Test fun `real completed offline open carries its actual entry source publication`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0)
            val source = f.source()
            val publication = assertNotNull(source.source.nativePublication)
            assertNull(source.source.authorizationReceipt); assertNull(source.source.primaryAccountEpoch)
            var calls = 0
            assertTrue(publication.admit { calls++ })
            assertEquals(1, calls)
            assertTrue(f.player.ownsSourceSnapshot(source))
        }
    }

    @Test fun `retired real Home entry rejects an already captured local publication`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0)
            val publication = assertNotNull(f.source().source.nativePublication)
            f.rootGate.close()
            assertFalse(publication.admit { error("Retired Root must not dispatch a native command") })
        }
    }

    @Test fun `offline binding disposal rejects old publication before queued source cleanup`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0)
            val publication = assertNotNull(f.source().source.nativePublication)
            f.binding.close()
            assertFalse(publication.admit { error("Disposed offline entry must not dispatch") })
        }
    }

    @Test fun `replacing an offline request cannot pause or resume the replacement through its old receipt`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0); val original = f.source()
            val old = assertNotNull(original.source.nativePublication)
            f.open(1); val replacement = f.source()
            assertTrue(replacement.sourceVersion > original.sourceVersion)
            assertFalse(old.admit { error("Old local publication must not touch replacement") })
            var calls = 0
            assertTrue(assertNotNull(replacement.source.nativePublication).admit { calls++ })
            assertEquals(1, calls)
        }
    }

    @Test fun `same version different file is rejected even when it copied the old publication`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0); val original = f.source()
            val old = assertNotNull(original.source.nativePublication)
            assertTrue(f.player.recoverSource(original.sourceVersion,
                replacement = original.source.copy(videoUrl = f.tasks[1].item.filePath!!)))
            assertFalse(old.admit { error("Same native version does not authorize a different local file") })
        }
    }

    @Test fun `existing same file recovery and replay keep source admission without acknowledging another source`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0); val original = f.source()
            val publication = assertNotNull(original.source.nativePublication)
            assertTrue(f.player.recoverSource(original.sourceVersion, positionSeconds = 4.0, paused = true))
            assertTrue(publication.admit { })
            f.player.replay()
            assertEquals(original.sourceVersion, f.source().sourceVersion)
            assertTrue(publication.admit { })
            f.player.loadVersioned(original.source)
            assertFalse(publication.admit { error("A distinct native load needs its own offline receipt") })
        }
    }

    @Test fun `cancelled actual offline entry scope retires its source publication`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open(0)
            val publication = assertNotNull(f.source().source.nativePublication)
            f.entryScope.cancel()
            assertFalse(publication.admit { error("Cancelled caller page must not dispatch") })
        }
    }

    @Test fun `repeated original mount reuses only its actual accepted full source`(): Unit = runBlocking {
        Fixture().use { f ->
            val first=assertNotNull(f.binding.openOriginal(f.tasks[0].id) { });first.join()
            val before=f.source()
            val repeated=assertNotNull(f.binding.openOriginal(f.tasks[0].id) { });repeated.join()
            assertSame(first,repeated)
            assertEquals(before.sourceVersion,f.source().sourceVersion)
            assertEquals(before.source,f.source().source)
            assertTrue(assertNotNull(before.source.nativePublication).admit { })
        }
    }

    @Test fun `explicit original retry creates a new admitted load at the last good cursor`(): Unit = runBlocking {
        Fixture().use { f ->
            assertNotNull(f.binding.openOriginal(f.tasks[0].id) { }).join()
            val before=f.source()
            assertNotNull(f.binding.openOriginal(f.tasks[0].id,forceReload=true,resumePositionMs=7_250L) { }).join()
            val retry=f.source()
            assertTrue(retry.sourceVersion>before.sourceVersion)
            assertEquals(7.25,retry.source.startPositionSeconds)
            assertFalse(assertNotNull(before.source.nativePublication).admit { error("Retry retires old source") })
            assertTrue(assertNotNull(retry.source.nativePublication).admit { })
        }
    }

    @Test fun `original episode switch creates its own source without applying a foreign retry cursor`(): Unit = runBlocking {
        Fixture().use { f ->
            assertNotNull(f.binding.openOriginal(f.tasks[0].id) { }).join()
            val before=f.source()
            assertNotNull(f.binding.openOriginal(f.tasks[1].id,resumePositionMs=9_000L) { }).join()
            val replacement=f.source()
            assertTrue(replacement.sourceVersion>before.sourceVersion)
            assertEquals(f.tasks[1].item.filePath,replacement.source.videoUrl)
            assertEquals(f.tasks[1].item.lastPlaybackPositionMs/1000.0,replacement.source.startPositionSeconds)
            assertFalse(assertNotNull(before.source.nativePublication).admit { error("Previous episode cannot dispatch") })
            assertTrue(assertNotNull(replacement.source.nativePublication).admit { })
        }
    }

    @Test fun `same task with a foreign same version file cannot deduplicate a mount`(): Unit = runBlocking {
        Fixture().use { f ->
            assertNotNull(f.binding.openOriginal(f.tasks[0].id) { }).join()
            val before=f.source()
            assertTrue(f.player.recoverSource(before.sourceVersion,replacement=before.source.copy(videoUrl=f.tasks[1].item.filePath!!)))
            assertNotNull(f.binding.openOriginal(f.tasks[0].id) { }).join()
            val corrected=f.source()
            assertTrue(corrected.sourceVersion>before.sourceVersion)
            assertEquals(before.source.videoUrl,corrected.source.videoUrl)
            assertTrue(assertNotNull(corrected.source.nativePublication).admit { })
        }
    }

    @Test fun `actual deleted managed file publishes original missing-file failure before native load`(): Unit = runBlocking {
        Fixture().use { f ->
            Files.delete(java.nio.file.Path.of(f.tasks[0].item.filePath!!))
            assertNotNull(f.binding.openOriginal(f.tasks[0].id) { error("Deleted original source must not select another episode") }).join()
            assertEquals(DesktopOfflineMedia3ErrorCodes.ERROR_CODE_IO_FILE_NOT_FOUND,f.binding.loadErrorCode)
            assertNotNull(f.binding.error)
            assertNull(f.player.currentSourceSnapshot())
            assertFalse(f.binding.opening)
            assertFalse(com.android.purebilibili.feature.download.resolveOfflinePlaybackFailure(f.binding.loadErrorCode!!).canRetry)
        }
    }

    @Test fun `task list still uses original online fallback for a deleted completed file`(): Unit = runBlocking {
        Fixture(networkAvailable=true).use { f ->
            Files.delete(java.nio.file.Path.of(f.tasks[0].item.filePath!!))
            var onlineCalls=0
            assertNotNull(f.binding.open(f.tasks[0].id) { task ->
                assertEquals(f.tasks[0].id,task.id);onlineCalls++
            }).join()
            assertEquals(1,onlineCalls)
            assertNull(f.binding.error)
            assertNull(f.binding.loadErrorCode)
            assertNull(f.player.currentSourceSnapshot())
        }
    }
}
