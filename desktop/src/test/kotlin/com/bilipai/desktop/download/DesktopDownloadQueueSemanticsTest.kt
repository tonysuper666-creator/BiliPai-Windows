package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadStatus
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four semantics the upstream WorkManager download queue has, kept by the desktop persistent queue
 * (DesktopDownloadManager + DesktopDownloadQueueAuthority): de-duplication, retry with exponential backoff,
 * cancellation and recovery after a restart. Each test drives the real manager over a fake CDN.
 */
class DesktopDownloadQueueSemanticsTest {
    private val bytes = ByteArray(64) { (it * 3).toByte() }
    private val muxer = DownloadMuxer { video, _, output -> Files.write(output, Files.readAllBytes(requireNotNull(video))) }

    /** An in-memory CDN: records every request, can hold a path open and can fail the next requests of a path. */
    private class FakeCdn(private val bytes: ByteArray) {
        class Start(val method: String, val path: String, val atNanos: Long)
        val starts = CopyOnWriteArrayList<Start>()
        val failuresLeft = ConcurrentHashMap<String, AtomicInteger>()
        val failureOf = ConcurrentHashMap<String, () -> IOException>()
        val holds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        fun failNext(path: String, count: Int, error: () -> IOException) {
            failureOf[path] = error; failuresLeft[path] = AtomicInteger(count)
        }
        fun hold(path: String) = CompletableDeferred<Unit>().also { holds[path] = it }
        fun gets(path: String) = starts.count { it.method == "GET" && it.path == path }
        fun requests(path: String) = starts.count { it.path == path }
        fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            starts += Start(request.method, path, System.nanoTime())
            if (failuresLeft[path]?.let { left -> left.get() > 0 && left.decrementAndGet() >= 0 } == true) throw failureOf.getValue(path)()
            holds[path]?.let { gate ->
                while (!gate.isCompleted) {
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                    Thread.sleep(5)
                }
            }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .header("Accept-Ranges", "bytes").header("Content-Length", bytes.size.toString())
            if (request.method == "HEAD") builder.code(200).body(ByteArray(0).toResponseBody()).build()
            else builder.code(200).body(bytes.toResponseBody()).build()
        }.build()
    }

    private fun manager(root: Path, cdn: FakeCdn, policy: DesktopDownloadRetryPolicy = DesktopDownloadRetryPolicy(), state: Path = root.resolve("state.json"),
        publication: com.bilipai.desktop.player.DesktopPlaybackPublication) =
        DesktopDownloadManager(cdn.client(), state, muxer, publication = publication, retryPolicy = policy)

    private fun publication(job: kotlinx.coroutines.Job): com.bilipai.desktop.player.DesktopPlaybackPublication {
        val gate = Any()
        return com.bilipai.desktop.player.DesktopLocalPlaybackPublication({ job.isActive }, { admitted ->
            synchronized(gate) { if (!job.isActive) false else { admitted(); true } }
        })
    }

    private fun DesktopDownloadManager.add(root: Path, cid: Long): String =
        enqueue(PlaybackSource("https://cdn.example/video-$cid", title = "queue-$cid"), root,
            DownloadMetadata(bvid = "BV1xx411c7mD", cid = cid, includeCover = false, includeDanmaku = false))

    private suspend fun DesktopDownloadManager.status(id: String, wanted: DownloadStatus, millis: Long = 10_000) =
        withTimeout(millis) { while (tasks.value.firstOrNull { it.id == id }?.status != wanted) delay(10) }

    private suspend fun DesktopDownloadManager.task(id: String) = tasks.value.single { it.id == id }

    // ---- 去重 -------------------------------------------------------------------------------------------------------

    @Test fun `the same video added again while it downloads is one task and one transfer`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-dedupe-")
        val cdn = FakeCdn(bytes); val gate = cdn.hold("/video-1")
        val manager = manager(root, cdn, publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            val first = manager.add(root, 1)
            manager.status(first, DownloadStatus.DOWNLOADING)
            val again = manager.add(root, 1)
            assertEquals(first, again)
            assertEquals(1, manager.tasks.value.size)
            gate.complete(Unit)
            manager.status(first, DownloadStatus.COMPLETED)
            // A finished task is not downloaded a second time either.
            assertEquals(first, manager.add(root, 1))
            delay(200)
            assertEquals(1, manager.tasks.value.size)
            assertEquals(DownloadStatus.COMPLETED, manager.task(first).status)
            assertEquals(1, cdn.gets("/video-1"), "one transfer for three additions")
        } finally { gate.complete(Unit); manager.shutdownForApplicationExit() }
    }

    @Test fun `adding a failed or cancelled video again revives that same task`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-dedupe-revive-")
        val cdn = FakeCdn(bytes)
        val manager = manager(root, cdn, publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            cdn.failNext("/video-2", 1) { AccessDeniedException("video.m4s.part") } // not a cut-off: FAILED at once
            val id = manager.add(root, 2)
            manager.status(id, DownloadStatus.FAILED)
            assertEquals(id, manager.add(root, 2))
            manager.status(id, DownloadStatus.COMPLETED)
            assertEquals(1, manager.tasks.value.size)
            assertEquals(DownloadStatus.COMPLETED, manager.task(id).status)
        } finally { manager.shutdownForApplicationExit() }
    }

    // ---- 取消 -------------------------------------------------------------------------------------------------------

    @Test fun `cancel stops a running transfer, frees its slot for the next queued one and stays cancelled`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-cancel-")
        val cdn = FakeCdn(bytes); val gateA = cdn.hold("/video-1"); val gateB = cdn.hold("/video-2")
        val manager = manager(root, cdn, publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            val a = manager.add(root, 1); val b = manager.add(root, 2); val c = manager.add(root, 3)
            manager.status(a, DownloadStatus.DOWNLOADING); manager.status(b, DownloadStatus.DOWNLOADING)
            assertEquals(DownloadStatus.QUEUED, manager.task(c).status, "two slots are taken")
            assertEquals(0, cdn.requests("/video-3"))

            manager.cancel(a)
            manager.status(a, DownloadStatus.PAUSED)
            manager.status(c, DownloadStatus.COMPLETED) // c took the slot a gave up
            gateA.complete(Unit)
            delay(200)
            assertEquals(DownloadStatus.PAUSED, manager.task(a).status, "a cancelled task is not restarted by anything")
            assertFalse(Files.exists(Path.of(manager.task(a).directory).resolve("video.m4s")))
            assertEquals(DownloadStatus.DOWNLOADING, manager.task(b).status, "other transfers are untouched")

            // Cancelling something that never started means it never starts.
            val d = manager.add(root, 4); val e = manager.add(root, 5)
            manager.status(d, DownloadStatus.DOWNLOADING)
            manager.cancel(e)
            gateB.complete(Unit)
            manager.status(b, DownloadStatus.COMPLETED); manager.status(d, DownloadStatus.COMPLETED)
            delay(200)
            assertEquals(DownloadStatus.PAUSED, manager.task(e).status)
            assertEquals(0, cdn.requests("/video-5"))

            // And cancelling is not final: the user resumes it.
            manager.resume(a)
            manager.status(a, DownloadStatus.COMPLETED)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(manager.offlinePlayback(a).videoUrl)))
        } finally { gateA.complete(Unit); gateB.complete(Unit); manager.shutdownForApplicationExit() }
    }

    // ---- 进程重启后恢复 ----------------------------------------------------------------------------------------------

    @Test fun `after a restart finished tasks stay finished, cancelled ones stay cancelled and interrupted ones continue`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-restart-")
        val state = root.resolve("state.json")
        val cdn = FakeCdn(bytes); val gate = cdn.hold("/video-2"); val gateCancelled = cdn.hold("/video-3")
        val publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!)
        val first = manager(root, cdn, state = state, publication = publication)
        val done = first.add(root, 1)
        first.status(done, DownloadStatus.COMPLETED)
        val interrupted = first.add(root, 2)
        first.status(interrupted, DownloadStatus.DOWNLOADING)
        val cancelled = first.add(root, 3)
        first.status(cancelled, DownloadStatus.DOWNLOADING)
        first.cancel(cancelled); first.status(cancelled, DownloadStatus.PAUSED)
        val waiting = first.add(root, 4)
        val before = first.tasks.value.associateBy { it.id }
        first.shutdownForApplicationExit() // the process ends here with video-2 half way
        gate.complete(Unit); gateCancelled.complete(Unit)

        val cdn2 = FakeCdn(bytes)
        val second = manager(root, cdn2, state = state, publication = publication)
        try {
            assertEquals(setOf(done, interrupted, cancelled, waiting), second.tasks.value.map { it.id }.toSet())
            assertEquals(before.getValue(done).item.filePath, second.task(done).item.filePath)
            assertEquals(before.getValue(interrupted).item.createdAt, second.task(interrupted).item.createdAt, "same identity, not a new task")
            second.status(interrupted, DownloadStatus.COMPLETED); second.status(waiting, DownloadStatus.COMPLETED)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(second.offlinePlayback(interrupted).videoUrl)))
            assertEquals(DownloadStatus.PAUSED, second.task(cancelled).status)
            assertEquals(0, cdn2.requests("/video-1"), "a finished task is not fetched again")
            assertEquals(0, cdn2.requests("/video-3"), "a cancelled task is not resumed by a restart")
            assertEquals(1, cdn2.gets("/video-2"))
        } finally { second.shutdownForApplicationExit() }
    }

    // ---- 重试退避 ---------------------------------------------------------------------------------------------------

    @Test fun `a cut off transfer is queued again with a doubling wait and finishes without user action`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-backoff-")
        val cdn = FakeCdn(bytes)
        val policy = DesktopDownloadRetryPolicy(baseDelayMs = 250, maxDelayMs = 5_000, maxAttempts = 3)
        val manager = manager(root, cdn, policy, publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            cdn.failNext("/video-1", 2) { SocketTimeoutException("timeout") }
            val id = manager.add(root, 1)
            withTimeout(10_000) { while (manager.task(id).retryAttempt < 1) delay(5) }
            val waiting = manager.task(id)
            assertEquals(DownloadStatus.QUEUED, waiting.status)
            assertTrue(waiting.retryNotBeforeMillis > System.currentTimeMillis() - 5_000)
            assertTrue(waiting.error.orEmpty().contains("自动重试") && waiting.error.orEmpty().contains("timeout"), waiting.error)
            manager.status(id, DownloadStatus.COMPLETED)
            assertEquals(2, manager.task(id).retryAttempt)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(manager.offlinePlayback(id).videoUrl)))
            val tries = cdn.starts.filter { it.path == "/video-1" }.take(3)
            assertEquals(3, tries.size, "two cut-offs, then the transfer that works")
            val firstWait = (tries[1].atNanos - tries[0].atNanos) / 1_000_000
            val secondWait = (tries[2].atNanos - tries[1].atNanos) / 1_000_000
            assertTrue(firstWait >= 240, "first wait was $firstWait ms")
            assertTrue(secondWait >= 490, "second wait must be about double the first, was $secondWait ms")
        } finally { manager.shutdownForApplicationExit() }
    }

    @Test fun `automatic retries are bounded and the last real error is reported`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-backoff-bound-")
        val cdn = FakeCdn(bytes)
        val policy = DesktopDownloadRetryPolicy(baseDelayMs = 20, maxDelayMs = 100, maxAttempts = 2)
        val manager = manager(root, cdn, policy, publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            cdn.failNext("/video-1", 100) { ConnectException("offline") }
            val id = manager.add(root, 1)
            manager.status(id, DownloadStatus.FAILED)
            delay(300)
            val failed = manager.task(id)
            assertEquals(2, failed.retryAttempt)
            assertEquals("offline", failed.error)
            assertEquals(3, cdn.requests("/video-1"), "the first try and two automatic retries, nothing more")
        } finally { manager.shutdownForApplicationExit() }
    }

    @Test fun `a failure that is not a cut off is reported at once without retrying`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-backoff-hard-")
        val cdn = FakeCdn(bytes)
        val manager = manager(root, cdn, DesktopDownloadRetryPolicy(baseDelayMs = 20, maxDelayMs = 100, maxAttempts = 5),
            publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            cdn.failNext("/video-1", 100) { AccessDeniedException("C:/Users/a/Downloads/video.m4s.part") }
            val id = manager.add(root, 1)
            manager.status(id, DownloadStatus.FAILED)
            delay(200)
            assertEquals(0, manager.task(id).retryAttempt)
            assertEquals(1, cdn.requests("/video-1"))
        } finally { manager.shutdownForApplicationExit() }
    }

    @Test fun `cancelling during the wait stops the retry and a manual resume starts counting again`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-backoff-cancel-")
        val cdn = FakeCdn(bytes)
        val manager = manager(root, cdn, DesktopDownloadRetryPolicy(baseDelayMs = 300, maxDelayMs = 2_000, maxAttempts = 3),
            publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!))
        try {
            cdn.failNext("/video-1", 1) { EOFException("unexpected end of stream") }
            val id = manager.add(root, 1)
            withTimeout(10_000) { while (manager.task(id).retryAttempt < 1) delay(5) }
            manager.cancel(id)
            manager.status(id, DownloadStatus.PAUSED)
            delay(700) // longer than the wait
            assertEquals(DownloadStatus.PAUSED, manager.task(id).status)
            assertEquals(1, cdn.requests("/video-1"), "the pending retry died with the cancel")
            manager.resume(id)
            manager.status(id, DownloadStatus.COMPLETED)
            assertEquals(0, manager.task(id).retryAttempt)
        } finally { manager.shutdownForApplicationExit() }
    }

    @Test fun `a restart during the wait keeps the attempt count and the end of the wait`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-queue-backoff-restart-")
        val state = root.resolve("state.json")
        val policy = DesktopDownloadRetryPolicy(baseDelayMs = 900, maxDelayMs = 5_000, maxAttempts = 3)
        val publication = publication(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!)
        val cdn1 = FakeCdn(bytes)
        val first = manager(root, cdn1, policy, state, publication)
        cdn1.failNext("/video-1", 1) { UnknownHostException("cdn.example") }
        val id = first.add(root, 1)
        withTimeout(10_000) { while (first.task(id).retryAttempt < 1) delay(5) }
        val notBefore = first.task(id).retryNotBeforeMillis
        first.shutdownForApplicationExit()
        val saved = Json.decodeFromString<List<DownloadTask>>(Files.readString(state)).single()
        assertEquals(1, saved.retryAttempt); assertEquals(notBefore, saved.retryNotBeforeMillis)

        val cdn2 = FakeCdn(bytes)
        val second = manager(root, cdn2, policy, state, publication)
        try {
            assertEquals(DownloadStatus.QUEUED, second.task(id).status)
            assertEquals(1, second.task(id).retryAttempt)
            assertEquals(0, cdn2.requests("/video-1"), "the restart does not skip the wait")
            second.status(id, DownloadStatus.COMPLETED)
            val startedAtMillis = System.currentTimeMillis() - (System.nanoTime() - cdn2.starts.first().atNanos) / 1_000_000
            assertTrue(startedAtMillis >= notBefore - 30, "started ${notBefore - startedAtMillis} ms before the saved end of the wait")
        } finally { second.shutdownForApplicationExit() }
    }

    // ---- 退避策略本身 ------------------------------------------------------------------------------------------------

    @Test fun `the wait doubles from thirty seconds like the upstream worker and is capped`() {
        val policy = DesktopDownloadRetryPolicy()
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), (0..6).map { policy.delayAfter(it) })
        assertEquals(30_000L, policy.nextDelayMs(0, SocketTimeoutException()))
        assertEquals(480_000L, policy.nextDelayMs(4, SocketTimeoutException()))
        assertNull(policy.nextDelayMs(5, SocketTimeoutException()), "five automatic retries are the limit")
        assertNull(policy.nextDelayMs(0, AccessDeniedException("x")))
    }

    @Test fun `only network cut offs are retried`() {
        listOf<Throwable>(SocketTimeoutException(), UnknownHostException("h"), ConnectException(), EOFException(),
            java.net.SocketException("Connection reset"), IOException("wrapped", SocketTimeoutException()),
            IOException("下载长度不匹配: 10/20"), IOException("分片下载未完成: a.part 1/2"), IOException("HTTP 503"),
            IOException("HTTP 429 Too Many Requests"), javax.net.ssl.SSLException("fail", ConnectException()))
            .forEach { assertTrue(DesktopDownloadRetryPolicy.isTransient(it), it.toString()) }
        listOf<Throwable>(AccessDeniedException("x"), IllegalStateException("下载队列已关闭"), IllegalArgumentException(),
            IOException("弹幕标准分段未全部下载（4/5）"), IOException("HTTP 403 Forbidden"), IOException("HTTP 404"),
            IOException("无法合并音视频"), java.nio.file.FileSystemException("disk full"))
            .forEach { assertFalse(DesktopDownloadRetryPolicy.isTransient(it), it.toString()) }
        assertNotEquals(DesktopDownloadRetryPolicy().baseDelayMs, 0L)
    }
}
