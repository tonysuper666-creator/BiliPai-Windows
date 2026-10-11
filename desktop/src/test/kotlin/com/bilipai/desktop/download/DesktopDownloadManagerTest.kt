package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadAssetKind
import com.android.purebilibili.feature.download.DownloadAssetStatus
import com.android.purebilibili.feature.download.DownloadAssetState
import com.android.purebilibili.feature.download.HttpDownloadAssetRequest
import com.android.purebilibili.feature.download.ResumableAssetDownloader
import com.android.purebilibili.feature.download.LocalDanmakuManifest
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlaybackSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class DesktopDownloadManagerTest {
    @Test fun `multi durl downloads every segment in order and persists aggregate bytes`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-multi-download-")
        val one = ByteArray(31) { 1 }; val two = ByteArray(45) { 2 }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            memoryClient(if (chain.request().url.encodedPath.contains("second")) two else one).interceptors.single().intercept(chain)
        }.build()
        val muxer = object : DownloadMuxer {
            override suspend fun mux(video: Path?, audio: Path?, output: Path) = error("Multi-segment source must use concatenation")
            override suspend fun muxSegments(segments: List<Path>, output: Path, audioOnly: Boolean) {
                assertFalse(audioOnly); assertEquals(2, segments.size)
                assertContentEquals(one, Files.readAllBytes(segments[0])); assertContentEquals(two, Files.readAllBytes(segments[1]))
                Files.write(output, one + two)
            }
        }
        val state = root.resolve("state.json")
        val manager = DesktopDownloadManager(client, state, muxer, publication = localDownloadPublication())
        val source = PlaybackSource("https://cdn.example/first.flv", title = "两段课程", progressiveSegments = listOf(
            PlaybackSegment("https://cdn.example/first.flv", 1.0), PlaybackSegment("https://cdn.example/second.flv", 2.0)))
        val id = manager.enqueue(source, root, DownloadMetadata(bvid = "BV1xx411c7mD", cid = 100, isCourse = true, includeDanmaku = false))
        withTimeout(5000) { while (manager.tasks.value.single().status !in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)) delay(10) }
        val task = manager.tasks.value.single()
        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertEquals(76L, task.downloadedBytes); assertEquals(2, task.item.assets.first { it.kind == DownloadAssetKind.VIDEO }.segmentCount)
        assertContentEquals(one + two, Files.readAllBytes(Path.of(manager.offlinePlayback(id).videoUrl)))
        assertFalse(Files.exists(Path.of(task.directory).resolve("segment-0001.media")))
        manager.close()
        val restored = DesktopDownloadManager(client, state, muxer, publication = localDownloadPublication())
        assertEquals(listOf(source.videoUrl, "https://cdn.example/second.flv"), restored.tasks.value.single().progressiveSegments.map { it.url })
        assertTrue(restored.tasks.value.single().isCourse); restored.close()
    }

    @Test fun `Windows multi segment command uses safe local concat and explicit copy tracks`() {
        val command = WindowsFfmpegMuxer.concatCommand(Path.of("C:/media tools/ffmpeg.exe"), Path.of("C:/media/parts.ffconcat"), Path.of("C:/media/merged.mp4"), false)
        assertEquals("concat", command[command.indexOf("-f") + 1]); assertEquals("1", command[command.indexOf("-safe") + 1])
        assertTrue(command.contains("0:v:0")); assertTrue(command.contains("0:a:0?"))
        assertEquals("copy", command[command.indexOf("-c") + 1]); assertEquals(1, command.count { it == "-i" })
    }

    private fun memoryClient(bytes: ByteArray, requests: MutableList<String> = CopyOnWriteArrayList()): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.header("Range").orEmpty()}"
            val range = request.header("Range")?.removePrefix("bytes=")?.split('-')
            val start = range?.firstOrNull()?.toIntOrNull() ?: 0
            val end = range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .header("Accept-Ranges", "bytes").header("Content-Length", bytes.size.toString())
            if (request.method == "HEAD") builder.code(200).body(ByteArray(0).toResponseBody()).build()
            else if (range != null) builder.code(206).header("Content-Range", "bytes $start-$end/${bytes.size}")
                .body(bytes.copyOfRange(start, end + 1).toResponseBody()).build()
            else builder.code(200).body(bytes.toResponseBody()).build()
        }.build()

    @Test fun `upstream downloader resumes exact byte range from existing part`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-range-test-")
        val output = root.resolve("video.m4s")
        val bytes = ByteArray(100) { it.toByte() }
        val requests = CopyOnWriteArrayList<String>()
        Files.write(root.resolve("video.m4s.part"), bytes.copyOfRange(0, 40))
        val result = ResumableAssetDownloader(memoryClient(bytes, requests)).download(
            HttpDownloadAssetRequest("https://cdn.example/video", output.toFile(), emptyMap()), {}, { _, _ -> })
        assertEquals(100L, result.downloadedBytes)
        assertTrue(requests.contains("GET bytes=40-"))
        assertContentEquals(bytes, Files.readAllBytes(output))
        assertFalse(Files.exists(root.resolve("video.m4s.part")))
    }

    @Test fun `upstream parallel ranges are assembled in original order`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-chunk-test-")
        val output = root.resolve("video.m4s")
        val bytes = ByteArray(200) { it.toByte() }
        val requests = CopyOnWriteArrayList<String>()
        val result = ResumableAssetDownloader(memoryClient(bytes, requests)).download(
            HttpDownloadAssetRequest("https://cdn.example/video", output.toFile(), emptyMap(), parallelThresholdBytes = 10, maxParallelChunks = 4), {}, { _, _ -> })
        assertEquals(4, result.segmentCount)
        assertContentEquals(bytes, Files.readAllBytes(output))
        assertTrue(requests.contains("GET bytes=0-49"))
        assertTrue(requests.contains("GET bytes=150-199"))
    }

    @Test fun `completed task points to merged file and survives manager restart`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-download-test-")
        val bytes = ByteArray(80) { it.toByte() }
        val state = root.resolve("state/downloads.json")
        val muxer = DownloadMuxer { video, audio, output ->
            assertNotNull(video)
            assertNotNull(audio)
            assertContentEquals(bytes, Files.readAllBytes(video))
            assertContentEquals(bytes, Files.readAllBytes(audio))
            Files.write(output, byteArrayOf(0, 0, 0, 20) + "ftyp".toByteArray() + bytes)
        }
        val manager = DesktopDownloadManager(memoryClient(bytes), state, muxer, publication = localDownloadPublication())
        val id = manager.enqueue(PlaybackSource("https://cdn.example/video", "https://cdn.example/audio", title = "合法标题"),
            root.resolve("destination"), DownloadMetadata(bvid = "BV1xx411c7mD", cid = 99, quality = 80))
        withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.COMPLETED) delay(10) }
        val source = manager.offlinePlayback(id)
        assertTrue(Files.isRegularFile(Path.of(source.videoUrl)))
        assertEquals("", source.referer)
        val directory = Path.of(manager.tasks.value.single().directory)
        assertFalse(Files.exists(directory.resolve("video.m4s")))
        assertFalse(Files.exists(directory.resolve("audio.m4s")))
        manager.close()
        val restored = DesktopDownloadManager(memoryClient(bytes), state, muxer, publication = localDownloadPublication())
        assertEquals(DownloadStatus.COMPLETED, restored.tasks.value.single().status)
        assertEquals(source.videoUrl, restored.offlinePlayback(id).videoUrl)
        restored.close()
    }

    @Test fun `mux failure retains both tracks for a successful retry`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-mux-retry-")
        var attempts = 0
        val muxer = DownloadMuxer { _, _, output ->
            if (++attempts == 1) throw java.io.IOException("Missing muxer")
            Files.write(output, "merged".toByteArray())
        }
        val manager = DesktopDownloadManager(memoryClient(ByteArray(30)), root.resolve("downloads.json"), muxer, publication = localDownloadPublication())
        val id = manager.enqueue(PlaybackSource("https://cdn.example/video", "https://cdn.example/audio"), root)
        withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.FAILED) delay(10) }
        val task = manager.tasks.value.single()
        assertTrue(Files.isRegularFile(Path.of(task.directory).resolve("video.m4s")))
        assertTrue(Files.isRegularFile(Path.of(task.directory).resolve("audio.m4s")))
        manager.retry(id)
        withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.COMPLETED) delay(10) }
        assertEquals(2, attempts)
        manager.close()
    }

    @Test fun `Windows merge uses argument array and copy with both explicit tracks`() {
        val command = WindowsFfmpegMuxer.command(Path.of("C:/media tools/ffmpeg.exe"), Path.of("C:/media/video.m4s"),
            Path.of("C:/media/audio.m4s"), Path.of("C:/media/merged.mp4"))
        assertEquals(2, command.count { it == "-i" })
        assertTrue(command.contains("0:v:0"))
        assertTrue(command.contains("1:a:0"))
        assertEquals("copy", command[command.indexOf("-c") + 1])
        assertTrue(command.contains("-nostdin"))
        assertFalse(command.any { it == "cmd" || it == "powershell" })
    }

    @Test fun `expired media URLs refresh through shared source resolver and preserve final tracks`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-expired-url-")
        val bytes = ByteArray(45) { it.toByte() }
        val expired = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.queryParameter("token") == "expired" && chain.request().method == "GET")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                    .body(ByteArray(0).toResponseBody()).build()
            else chain.proceed(chain.request())
        }.addInterceptor(memoryClient(bytes).interceptors.single()).build()
        var refreshes = 0
        val manager = DesktopDownloadManager(expired, root.resolve("state.json"), DownloadMuxer { video, audio, output ->
            assertNotNull(video)
            assertNotNull(audio)
            assertContentEquals(bytes, Files.readAllBytes(video))
            assertContentEquals(bytes, Files.readAllBytes(audio))
            Files.write(output, "merged".toByteArray())
        }, sourceResolver = {
            refreshes++
            DesktopDownloadResolvedSource(PlaybackSource("https://cdn.example/video?token=current", "https://cdn.example/audio?token=current"))
        }, publication = localDownloadPublication())
        manager.enqueue(PlaybackSource("https://cdn.example/video?token=expired", "https://cdn.example/audio?token=expired"), root)
        withTimeout(5000) { while (manager.tasks.value.single().status !in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)) delay(10) }
        assertEquals(DownloadStatus.COMPLETED, manager.tasks.value.single().status)
        assertEquals(1, refreshes)
        assertTrue(manager.tasks.value.single().item.audioUrl.endsWith("token=current"))
        manager.close()
    }

    @Test fun `audio only task produces M4A from audio track without downloading video`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-audio-only-")
        val requests = CopyOnWriteArrayList<String>()
        val recording = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.encodedPath
            chain.proceed(chain.request())
        }.addInterceptor(memoryClient(ByteArray(20)).interceptors.single()).build()
        val manager = DesktopDownloadManager(recording, root.resolve("state.json"), DownloadMuxer { video, audio, output ->
            assertNull(video)
            assertNotNull(audio)
            assertEquals("m4a", output.fileName.toString().substringAfterLast('.'))
            Files.write(output, "audio".toByteArray())
        }, publication = localDownloadPublication())
        manager.enqueue(PlaybackSource("https://cdn.example/video", "https://cdn.example/audio"), root,
            DownloadMetadata(audioOnly = true, includeDanmaku = false))
        withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.COMPLETED) delay(10) }
        assertFalse(requests.contains("/video"))
        assertTrue(requests.contains("/audio"))
        manager.close()
    }

    @Test fun `failed optional cover does not lose successfully downloaded media`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-cover-failure-")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/cover")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Missing")
                    .body(ByteArray(0).toResponseBody()).build()
            else chain.proceed(chain.request())
        }.addInterceptor(memoryClient(ByteArray(20)).interceptors.single()).build()
        val manager = DesktopDownloadManager(client, root.resolve("state.json"), DownloadMuxer { _, _, output -> Files.write(output, "merged".toByteArray()) }, publication = localDownloadPublication())
        val id = manager.enqueue(PlaybackSource("https://cdn.example/video"), root,
            DownloadMetadata(cover = "https://cdn.example/cover", includeDanmaku = false))
        withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.COMPLETED) delay(10) }
        assertEquals(DownloadAssetStatus.FAILED, manager.tasks.value.single().item.assets.single { it.kind == DownloadAssetKind.COVER }.status)
        assertTrue(Files.isRegularFile(Path.of(manager.offlinePlayback(id).videoUrl)))
        manager.close()
    }

    @Test fun `output filename excludes Windows reserved names and path components`() {
        assertEquals("_CON", DesktopDownloadManager.safeOutputName("CON"))
        assertEquals("title__next", DesktopDownloadManager.safeOutputName("title/\\next"))
        assertEquals("video", DesktopDownloadManager.safeOutputName("... "))
        assertEquals("Latin title 123", DesktopDownloadManager.safeOutputName("Latin title 123"))
        assertEquals("title_next", DesktopDownloadManager.safeOutputName("title\nnext"))
    }

    @Test fun `missing middle offline danmaku file retains its original six minute slot`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-offline-danmaku-")
        val manager = DesktopDownloadManager(memoryClient(ByteArray(20)), root.resolve("state.json"),
            DownloadMuxer { _, _, output -> Files.write(output, "merged".toByteArray()) },
            danmakuDownloader = { task, directory, _ ->
                val paths = (1..3).map { index -> directory.resolve("${task.id}_seg_$index.pb").also {
                    Files.write(it, byteArrayOf(index.toByte()))
                }.toString() }
                val special = directory.resolve("${task.id}_special_1.pb").also { Files.write(it, byteArrayOf(9)) }.toString()
                val all = paths + special
                val metadata = directory.resolve("manifest.json")
                Files.writeString(metadata, Json.encodeToString(LocalDanmakuManifest.serializer(), LocalDanmakuManifest(
                    bvid = task.item.bvid, cid = task.item.cid, aid = task.item.aid, durationMs = 1_080_000L,
                    segmentPaths = all, standardSegmentCount = 3, savedAt = 0L)))
                all to metadata.toString()
            }, publication = localDownloadPublication())
        val id = manager.enqueue(PlaybackSource("https://cdn.example/video"), root,
            DownloadMetadata(bvid = "BV1xx411c7mD", cid = 99, durationSeconds = 1080, includeCover = false))
        try {
            withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.COMPLETED) delay(10) }
            val original = assertNotNull(manager.offlineDanmaku(id))
            Files.delete(original.standardSegments[1])
            val remaining = assertNotNull(manager.offlineDanmaku(id))
            assertEquals(3, remaining.standardSegments.size)
            assertEquals(original.standardSegments, remaining.standardSegments)
            assertFalse(Files.exists(remaining.standardSegments[1]))
            assertTrue(Files.exists(remaining.standardSegments[2]))
            assertEquals(original.specialSegments, remaining.specialSegments)
        } finally { manager.close() }
    }

    @Test fun `offline resume position persists and resets when the original policy reaches the end`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-offline-position-")
        val state = root.resolve("state.json")
        val muxer = DownloadMuxer { _, _, output -> Files.write(output, "merged".toByteArray()) }
        val manager = DesktopDownloadManager(memoryClient(ByteArray(20)), state, muxer, publication = localDownloadPublication())
        val id = manager.enqueue(PlaybackSource("https://cdn.example/video"), root, DownloadMetadata(includeDanmaku = false))
        withTimeout(5000) { while (manager.tasks.value.single().status != DownloadStatus.COMPLETED) delay(10) }
        manager.savePlaybackPosition(id, 61_250L, 600_000L)
        manager.close()
        val restored = DesktopDownloadManager(memoryClient(ByteArray(20)), state, muxer, publication = localDownloadPublication())
        try {
            assertEquals(61.25, restored.offlinePlayback(id).startPositionSeconds)
            restored.savePlaybackPosition(id, 599_000L, 600_000L)
            assertEquals(0.0, restored.offlinePlayback(id).startPositionSeconds)
        } finally { restored.close() }
    }

    @Test fun `completed optional danmaku publishes aggregate bytes before any media request`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-optional-complete-")
        val state = root.resolve("state.json")
        val requests = CopyOnWriteArrayList<String>()
        val activeRequests = AtomicInteger()
        val workers = CopyOnWriteArrayList<Job>()
        val published = CompletableDeferred<DownloadTask>()
        val release = CompletableDeferred<Unit>()
        val client = optionalAssetMemoryClient(requests, activeRequests)
        val muxer = DownloadMuxer { video, audio, output ->
            Files.write(output, Files.readAllBytes(requireNotNull(video)) + Files.readAllBytes(requireNotNull(audio)))
        }
        lateinit var manager: DesktopDownloadManager
        manager = DesktopDownloadManager(client, state, muxer,
            danmakuDownloader = { _, directory, callback ->
                workers += DownloadDanmakuTransport.taskOwner().callerJob
                val segment = directory.resolve("optional.pb")
                Files.write(segment, ByteArray(13))
                callback(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.COMPLETED,
                    totalBytes = 13L, downloadedBytes = 13L, filePath = segment.toString(), segmentCount = 1))
                published.complete(manager.tasks.value.single())
                release.await()
                listOf(segment.toString()) to null
            }, publication = localDownloadPublication())
        try {
            manager.enqueue(PlaybackSource("https://cdn.example/video", "https://cdn.example/audio"), root,
                DownloadMetadata(bvid = "BV1xx411c7mD", cid = 99, cover = "https://cdn.example/cover"))
            val beforeMedia = withTimeout(5_000) { published.await() }
            assertEquals(DownloadStatus.DOWNLOADING, beforeMedia.status)
            assertEquals(20L, beforeMedia.downloadedBytes)
            assertEquals(20L, beforeMedia.totalBytes)
            assertEquals(0.9f, beforeMedia.progress)
            assertEquals(7L, beforeMedia.item.assets.single { it.kind == DownloadAssetKind.COVER }.downloadedBytes)
            val danmaku = beforeMedia.item.assets.single { it.kind == DownloadAssetKind.DANMAKU }
            assertEquals(DownloadAssetStatus.COMPLETED, danmaku.status)
            assertEquals(13L, danmaku.downloadedBytes)
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.substringAfter(' ') == "/cover" }, requests.toString())

            release.complete(Unit)
            val completed = awaitOptionalTaskCompletion(manager)
            withTimeout(5_000) { workers.single().join() }
            assertEquals(60L, completed.downloadedBytes)
            assertEquals(60L, completed.totalBytes)
            assertEquals(40L, completed.item.fileSize)
            assertEquals(40L, Files.size(Path.of(requireNotNull(completed.outputFile))))
            manager.close()
            val restoredClient = optionalAssetMemoryClient(CopyOnWriteArrayList(), AtomicInteger())
            val restored = DesktopDownloadManager(restoredClient, state, muxer, publication = localDownloadPublication())
            try {
                val cold = restored.tasks.value.single()
                assertEquals(DownloadStatus.COMPLETED, cold.status)
                assertEquals(60L, cold.downloadedBytes)
                assertEquals(60L, cold.totalBytes)
                assertEquals(DownloadAssetStatus.COMPLETED,
                    cold.item.assets.single { it.kind == DownloadAssetKind.DANMAKU }.status)
            } finally {
                restored.close()
                restoredClient.dispatcher.executorService.shutdownNow()
                restoredClient.connectionPool.evictAll()
            }
        } finally {
            closeOptionalAssetFixture(manager, client, listOf(release), workers, activeRequests)
        }
    }

    @Test fun `failed and skipped optional danmaku replace their partial aggregate before media starts`(): Unit = runBlocking {
        for (terminal in listOf(DownloadAssetStatus.FAILED, DownloadAssetStatus.SKIPPED)) {
            val root = Files.createTempDirectory("bilipai-optional-${terminal.name.lowercase()}-")
            val requests = CopyOnWriteArrayList<String>()
            val activeRequests = AtomicInteger()
            val workers = CopyOnWriteArrayList<Job>()
            val partial = CompletableDeferred<DownloadTask>()
            val published = CompletableDeferred<DownloadTask>()
            val replacePartial = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client = optionalAssetMemoryClient(requests, activeRequests)
            lateinit var manager: DesktopDownloadManager
            manager = DesktopDownloadManager(client, root.resolve("state.json"), DownloadMuxer { video, audio, output ->
                Files.write(output, Files.readAllBytes(requireNotNull(video)) + Files.readAllBytes(requireNotNull(audio)))
            }, danmakuDownloader = { _, directory, callback ->
                workers += DownloadDanmakuTransport.taskOwner().callerJob
                val segment = directory.resolve("optional.pb.part")
                Files.write(segment, ByteArray(5))
                callback(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.DOWNLOADING,
                    totalBytes = 13L, downloadedBytes = 5L, tempPath = segment.toString()))
                partial.complete(manager.tasks.value.single())
                replacePartial.await()
                Files.delete(segment)
                callback(DownloadAssetState(DownloadAssetKind.DANMAKU, terminal,
                    errorMessage = if (terminal == DownloadAssetStatus.FAILED) "fixture optional failure" else null))
                published.complete(manager.tasks.value.single())
                release.await()
                emptyList<String>() to null
            }, publication = localDownloadPublication())
            try {
                manager.enqueue(PlaybackSource("https://cdn.example/video", "https://cdn.example/audio"), root,
                    DownloadMetadata(bvid = "BV1xx411c7mD", cid = 99, cover = "https://cdn.example/cover"))
                val beforeReplacement = withTimeout(5_000) { partial.await() }
                assertEquals(12L, beforeReplacement.downloadedBytes, terminal.name)
                assertEquals(20L, beforeReplacement.totalBytes, terminal.name)
                assertEquals(0.54f, beforeReplacement.progress, terminal.name)
                assertEquals(DownloadAssetStatus.DOWNLOADING,
                    beforeReplacement.item.assets.single { it.kind == DownloadAssetKind.DANMAKU }.status)
                assertTrue(requests.isNotEmpty())
                assertTrue(requests.all { it.substringAfter(' ') == "/cover" }, requests.toString())

                replacePartial.complete(Unit)
                val beforeMedia = withTimeout(5_000) { published.await() }
                assertEquals(DownloadStatus.DOWNLOADING, beforeMedia.status)
                assertEquals(7L, beforeMedia.downloadedBytes, terminal.name)
                assertEquals(7L, beforeMedia.totalBytes, terminal.name)
                assertEquals(0.9f, beforeMedia.progress, terminal.name)
                val danmaku = beforeMedia.item.assets.single { it.kind == DownloadAssetKind.DANMAKU }
                assertEquals(terminal, danmaku.status)
                assertEquals(0L, danmaku.downloadedBytes)
                assertEquals(0L, danmaku.totalBytes)
                assertTrue(requests.all { it.substringAfter(' ') == "/cover" }, requests.toString())

                release.complete(Unit)
                val completed = awaitOptionalTaskCompletion(manager)
                withTimeout(5_000) { workers.single().join() }
                assertEquals(47L, completed.downloadedBytes, terminal.name)
                assertEquals(47L, completed.totalBytes, terminal.name)
                assertEquals(terminal, completed.item.assets.single { it.kind == DownloadAssetKind.DANMAKU }.status)
                assertTrue(requests.any { it.substringAfter(' ') == "/video" })
                assertTrue(requests.any { it.substringAfter(' ') == "/audio" })
            } finally {
                closeOptionalAssetFixture(manager, client, listOf(replacePartial, release), workers, activeRequests)
            }
        }
    }

    @Test fun `paused optional callback cannot publish into the resumed same id worker or persisted task`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-optional-retirement-")
        val state = root.resolve("state.json")
        val requests = CopyOnWriteArrayList<String>()
        val activeRequests = AtomicInteger()
        val workers = CopyOnWriteArrayList<Job>()
        val attempts = AtomicInteger()
        val first = CompletableDeferred<Pair<Job, (DownloadAssetState) -> Unit>>()
        val second = CompletableDeferred<Pair<Job, (DownloadAssetState) -> Unit>>()
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val client = optionalAssetMemoryClient(requests, activeRequests)
        val manager = DesktopDownloadManager(client, state, DownloadMuxer { video, _, output ->
            Files.write(output, Files.readAllBytes(requireNotNull(video)))
        }, danmakuDownloader = { _, directory, callback ->
            val worker = DownloadDanmakuTransport.taskOwner().callerJob
            workers += worker
            when (attempts.incrementAndGet()) {
                1 -> {
                    callback(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.DOWNLOADING,
                        totalBytes = 13L, downloadedBytes = 5L))
                    first.complete(worker to callback)
                    // Hold this actual cancelled worker until the test permits its queue slot to retire.
                    withContext(NonCancellable) { oldRelease.await() }
                    emptyList<String>() to null
                }
                2 -> {
                    callback(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.DOWNLOADING,
                        totalBytes = 19L, downloadedBytes = 11L))
                    second.complete(worker to callback)
                    newRelease.await()
                    val segment = directory.resolve("optional.pb")
                    Files.write(segment, ByteArray(19))
                    callback(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.COMPLETED,
                        totalBytes = 19L, downloadedBytes = 19L, filePath = segment.toString(), segmentCount = 1))
                    listOf(segment.toString()) to null
                }
                else -> error("Unexpected third fixture worker")
            }
        }, publication = localDownloadPublication())
        try {
            val id = manager.enqueue(PlaybackSource("https://cdn.example/video"), root,
                DownloadMetadata(bvid = "BV1xx411c7mD", cid = 99, includeCover = false))
            val (oldWorker, oldCallback) = withTimeout(5_000) { first.await() }
            assertEquals(5L, manager.tasks.value.single().downloadedBytes)
            assertTrue(requests.isEmpty())
            manager.pause(id)
            val paused = manager.tasks.value.single()
            val pausedDisk = Files.readAllBytes(state)
            assertEquals(DownloadStatus.PAUSED, paused.status)
            val obsolete = DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.COMPLETED,
                totalBytes = 13L, downloadedBytes = 13L)
            assertFailsWith<CancellationException> { oldCallback(obsolete) }
            assertEquals(paused, manager.tasks.value.single())
            assertContentEquals(pausedDisk, Files.readAllBytes(state))
            assertTrue(requests.isEmpty())

            oldRelease.complete(Unit)
            withTimeout(5_000) { oldWorker.join() }
            manager.resume(id)
            val (newWorker, _) = withTimeout(5_000) { second.await() }
            assertFalse(oldWorker === newWorker)
            val resumed = manager.tasks.value.single()
            val resumedDisk = Files.readAllBytes(state)
            assertEquals(id, resumed.id)
            assertEquals(DownloadStatus.DOWNLOADING, resumed.status)
            assertEquals(11L, resumed.downloadedBytes)
            assertEquals(19L, resumed.totalBytes)
            assertFailsWith<CancellationException> { oldCallback(obsolete) }
            assertEquals(resumed, manager.tasks.value.single())
            assertContentEquals(resumedDisk, Files.readAllBytes(state))
            assertTrue(requests.isEmpty())

            newRelease.complete(Unit)
            val completed = awaitOptionalTaskCompletion(manager)
            withTimeout(5_000) { newWorker.join() }
            assertEquals(id, completed.id)
            assertEquals(39L, completed.downloadedBytes)
            assertEquals(39L, completed.totalBytes)
            assertEquals(DownloadAssetStatus.COMPLETED,
                completed.item.assets.single { it.kind == DownloadAssetKind.DANMAKU }.status)
            assertEquals(2, attempts.get())
            assertTrue(requests.any { it.substringAfter(' ') == "/video" })
        } finally {
            closeOptionalAssetFixture(manager, client, listOf(oldRelease, newRelease), workers, activeRequests)
        }
    }

    /** All HTTP is intercepted before transport; use the existing original downloader memory fixture. */
    private fun optionalAssetMemoryClient(requests: MutableList<String>, active: AtomicInteger): OkHttpClient {
        val cover = memoryClient(ByteArray(7)).interceptors.single()
        val media = memoryClient(ByteArray(20)).interceptors.single()
        return OkHttpClient.Builder().addInterceptor { chain ->
            requests += "${chain.request().method} ${chain.request().url.encodedPath}"
            active.incrementAndGet()
            try {
                (if (chain.request().url.encodedPath == "/cover") cover else media).intercept(chain)
            } finally { active.decrementAndGet() }
        }.build()
    }

    private suspend fun awaitOptionalTaskCompletion(manager: DesktopDownloadManager): DownloadTask {
        val result = withTimeout(5_000) { manager.tasks.first { tasks ->
            tasks.single().status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        }.single() }
        assertEquals(DownloadStatus.COMPLETED, result.status, result.error)
        return result
    }

    private suspend fun closeOptionalAssetFixture(manager: DesktopDownloadManager, client: OkHttpClient,
        releases: List<CompletableDeferred<Unit>>, workers: List<Job>, activeRequests: AtomicInteger) {
        try {
            manager.close()
        } finally {
            releases.forEach { it.complete(Unit) }
            try {
                withContext(NonCancellable) {
                    withTimeout(5_000) {
                        workers.toList().forEach { it.join() }
                        while (activeRequests.get() != 0) delay(10)
                    }
                }
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}

/** Explicit memory-only fixture lease; never adopts an account receipt. Original assertions stay unchanged. */
private suspend fun localDownloadPublication(): com.bilipai.desktop.player.DesktopPlaybackPublication {
    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        ?: error("Download fixture requires an actual test Job")
    val gate = Any()
    return com.bilipai.desktop.player.DesktopLocalPlaybackPublication({ job.isActive }, { admitted ->
        synchronized(gate) { if (!job.isActive) false else { admitted(); true } }
    })
}
