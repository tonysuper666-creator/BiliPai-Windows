package com.bilipai.desktop.download

import com.android.purebilibili.core.events.*
import com.android.purebilibili.feature.download.DownloadStatus
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Existing manager, original downloader/mux completion and real temporary
 * files. In-memory OkHttp interceptor prevents all external requests. */
class DesktopDownloadBrandSuccessTest {
    private class Harness {
        val root = Files.createTempDirectory("download-brand-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var accountCurrent = true
        var decorateFails = false
        val events = java.util.concurrent.CopyOnWriteArrayList<BrandSuccessFeedback>()
        val bus = BrandSuccessEvents { if (decorateFails) error("synthetic decorator failure") else scope.isActive }
        val gate = Any()
        val publication = DesktopLocalPlaybackPublication({ accountCurrent }, { block ->
            synchronized(gate) { if (!accountCurrent) false else { block(); true } }
        })
        private val bytes = ByteArray(80) { it.toByte() }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); val range = r.header("Range")?.removePrefix("bytes=")?.split('-')
            val start = range?.firstOrNull()?.toIntOrNull() ?: 0
            val end = range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex
            val b = Response.Builder().request(r).protocol(Protocol.HTTP_1_1).message("OK")
                .header("Accept-Ranges", "bytes").header("Content-Length", bytes.size.toString())
            if (r.method == "HEAD") b.code(200).body(ByteArray(0).toResponseBody()).build()
            else if (range != null) b.code(206).header("Content-Range", "bytes $start-$end/${bytes.size}")
                .body(bytes.copyOfRange(start, end + 1).toResponseBody()).build()
            else b.code(200).body(bytes.toResponseBody()).build()
        }.build()
        private val managers = mutableListOf<DesktopDownloadManager>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { events += it } }
        var merge: suspend (Path?, Path?, Path) -> Unit = { video, audio, output ->
            assertNotNull(video); assertNotNull(audio)
            assertContentEquals(bytes, Files.readAllBytes(video)); assertContentEquals(bytes, Files.readAllBytes(audio))
            Files.write(output, "synthetic-mux-result".toByteArray()); Unit
        }
        fun manager() = DesktopDownloadManager(client, root.resolve("downloads.json"), DownloadMuxer { v, a, out ->
            merge(v, a, out)
        }, publication = publication, brandEvents = bus).also { managers += it }
        fun enqueue(manager: DesktopDownloadManager) = manager.enqueue(PlaybackSource(
            "https://fixture.invalid/video", "https://fixture.invalid/audio", title = "原完成标题"), root.resolve("destination"))
        suspend fun completed(manager: DesktopDownloadManager) = withTimeout(5_000) {
            manager.tasks.first { it.singleOrNull()?.status == DownloadStatus.COMPLETED }.single()
        }
        suspend fun closeManager(manager: DesktopDownloadManager) {
            // Exact owned manager scope only; deterministic cleanup without a new production API.
            val field = DesktopDownloadManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
            val job = (field.get(manager) as CoroutineScope).coroutineContext.job
            manager.close(); job.join()
        }
        suspend fun close() {
            managers.forEach { closeManager(it) }; collector.cancelAndJoin(); bus.close()
            scope.cancel(); scope.coroutineContext.job.join(); root.toFile().deleteRecursively()
        }
    }

    @Test fun realCompletionAfterMuxAndCleanupPublishesOneOriginalDownloadEvent(): Unit = runBlocking {
        val h = Harness()
        try {
            val manager = h.manager(); val id = h.enqueue(manager); val task = h.completed(manager)
            withTimeout(2_000) { while (h.events.isEmpty()) delay(5) }
            assertEquals(BrandSuccessKind.DOWNLOAD, h.events.single().kind)
            assertEquals("原完成标题", h.events.single().detail)
            assertTrue(h.bus.isCurrent(h.events.single()))
            assertTrue(Files.isRegularFile(Path.of(manager.offlinePlayback(id).videoUrl)))
            assertFalse(Files.exists(Path.of(task.directory).resolve("video.m4s")))
            assertFalse(Files.exists(Path.of(task.directory).resolve("audio.m4s")))
        } finally { h.close() }
    }

    @Test fun decorativeExceptionDoesNotRewriteCompletedTask(): Unit = runBlocking {
        val h = Harness()
        try {
            h.decorateFails = true
            val manager = h.manager(); h.enqueue(manager); h.completed(manager); h.closeManager(manager)
            assertEquals(DownloadStatus.COMPLETED, manager.tasks.value.single().status)
            assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }

    @Test fun accountRetiresAfterOutputButBusinessCompletionRemainsCompleted(): Unit = runBlocking {
        val h = Harness()
        try {
            val old = h.merge
            h.merge = { video, audio, output -> old(video, audio, output); h.accountCurrent = false }
            val manager = h.manager(); h.enqueue(manager); h.completed(manager); h.closeManager(manager)
            assertEquals(DownloadStatus.COMPLETED, manager.tasks.value.single().status)
            assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }

    @Test fun restoredCompletedTaskDoesNotEmitAgain(): Unit = runBlocking {
        val h = Harness()
        try {
            val manager = h.manager(); h.enqueue(manager); h.completed(manager)
            withTimeout(2_000) { while (h.events.isEmpty()) delay(5) }
            h.closeManager(manager); h.events.clear()
            val restored = h.manager(); assertEquals(DownloadStatus.COMPLETED, restored.tasks.value.single().status)
            delay(30); assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }

    @Test fun actualPausedWorkerDoesNotEmitCompletion(): Unit = runBlocking {
        val h = Harness()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.merge = { _, _, _ -> entered.complete(Unit); release.await() }
            val manager = h.manager(); val id = h.enqueue(manager)
            withTimeout(2_000) { entered.await() }; manager.pause(id); release.complete(Unit)
            h.closeManager(manager)
            assertEquals(DownloadStatus.PAUSED, manager.tasks.value.single().status)
            assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }
}
