package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadAssetKind
import com.android.purebilibili.feature.download.DownloadAssetStatus
import com.android.purebilibili.feature.download.HttpDownloadAssetRequest
import com.android.purebilibili.feature.download.ResumableAssetDownloader
import com.android.purebilibili.feature.download.LocalDanmakuManifest
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlaybackSegment
import kotlinx.coroutines.delay
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
