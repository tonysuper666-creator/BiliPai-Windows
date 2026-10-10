package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** The actual queue worker, downloader, mux publication and persisted task, using
 * synthetic memory transport. These definitions require no real profile or GUI. */
class DesktopDownloadRefreshQualityTest {
    private val bytes = ByteArray(45) { it.toByte() }
    private fun transport(requests: MutableList<Request> = CopyOnWriteArrayList(), content: (Request) -> ByteArray = { bytes }): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val bytes = content(request)
            val forbidden = request.method == "GET" && request.url.queryParameter("token") == "expired"
            val range = request.header("Range")?.removePrefix("bytes=")?.split('-')
            val start = range?.firstOrNull()?.toIntOrNull() ?: 0
            val end = range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .message(if (forbidden) "Forbidden" else "memory")
                .header("Accept-Ranges", "bytes").header("Content-Length", bytes.size.toString())
            when {
                forbidden -> builder.code(403).body(ByteArray(0).toResponseBody()).build()
                request.method == "HEAD" -> builder.code(200).body(ByteArray(0).toResponseBody()).build()
                range != null -> builder.code(206).header("Content-Range", "bytes $start-$end/${bytes.size}")
                    .body(bytes.copyOfRange(start, end + 1).toResponseBody()).build()
                else -> builder.code(200).body(bytes.toResponseBody()).build()
            }
        }.build()
    private suspend fun lease(): DesktopPlaybackPublication {
        val job = currentCoroutineContext()[Job] ?: error("real test Job required")
        val gate = Any()
        return DesktopLocalPlaybackPublication({ job.isActive }, { action ->
            synchronized(gate) { if (!job.isActive) false else { action(); true } }
        })
    }
    private fun source(token: String, receipt: DesktopPlaybackAuthorizationReceipt? = null) =
        PlaybackSource("https://cdn.example/video?token=$token", "https://cdn.example/audio?token=$token",
            title = "quality fixture", authorizationReceipt = receipt)
    private val metadata = DownloadMetadata(bvid = "BV1GJ411x7h7", cid = 42, quality = 120,
        qualityLabel = "requested 4K", includeCover = false, includeDanmaku = false)
    private val muxer = DownloadMuxer { video, audio, output ->
        assertNotNull(video); assertNotNull(audio)
        assertContentEquals(bytes, Files.readAllBytes(video)); assertContentEquals(bytes, Files.readAllBytes(audio))
        Files.write(output, "merged".toByteArray() + bytes)
    }
    private suspend fun completed(manager: DesktopDownloadManager): DownloadTask = withTimeout(5_000) {
        manager.tasks.first { it.singleOrNull()?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }
            .single().also { assertEquals(DownloadStatus.COMPLETED, it.status, it.error) }
    }

    @Test fun fallbackStreamUpdatesActualQualityWithoutMovingTaskOrOfflineFile() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-refresh-"); val state = root.resolve("state.json")
        val client = transport(); var resolutions = 0
        val manager = DesktopDownloadManager(client, state, muxer, sourceResolver = { task ->
            resolutions++; assertEquals(120, task.item.quality)
            DesktopDownloadResolvedSource(source("fresh"), actualVideoQuality = 80)
        }, publication = lease())
        try {
            val id = manager.enqueue(source("expired"), root, metadata)
            val expectedDirectory = root.resolve(DownloadTask.directoryName(id))
            val task = completed(manager)
            assertEquals(1, resolutions); assertEquals(id, task.id); assertEquals(id, task.item.id)
            assertEquals(120, task.item.quality); assertEquals(80, task.resolvedVideoQuality)
            assertEquals("1080P 高清", task.item.qualityDesc)
            assertEquals(expectedDirectory, Path.of(task.directory)); assertEquals(id, Files.readString(expectedDirectory.resolve(".bilipai-download")))
            assertTrue(Path.of(task.outputFile!!).startsWith(expectedDirectory))
            assertContentEquals("merged".toByteArray() + bytes, Files.readAllBytes(Path.of(manager.offlinePlayback(id).videoUrl)))
        } finally { manager.close() }
        val restored = DesktopDownloadManager(client, state, muxer, publication = lease())
        try {
            val task = restored.tasks.value.single()
            assertEquals("BV1GJ411x7h7_42_120", task.id); assertEquals(80, task.resolvedVideoQuality)
            assertEquals("1080P 高清", task.item.qualityDesc)
            assertTrue(Files.isRegularFile(Path.of(restored.offlinePlayback(task.id).videoUrl)))
            assertNull(task.authorizationReceipt)
        } finally { restored.close() }
    }

    @Test fun sameUnknownAndMissingActualQualityPreserveOriginalLabelRules() = runBlocking<Unit> {
        for (selected in listOf<Int?>(120, 999, null)) {
            val root = Files.createTempDirectory("bilipai-quality-label-")
            val manager = DesktopDownloadManager(transport(), root.resolve("state.json"), muxer,
                sourceResolver = { DesktopDownloadResolvedSource(source("fresh"), selected) }, publication = lease())
            try {
                val id = manager.enqueue(source("expired"), root, metadata)
                val task = completed(manager)
                assertEquals(id, task.id); assertEquals(120, task.item.quality)
                assertEquals(selected, task.resolvedVideoQuality); assertEquals("requested 4K", task.item.qualityDesc)
            } finally { manager.close() }
        }
    }

    @Test fun pausedWorkerCannotPublishLateActualQuality() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-paused-")
        val requested = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = DesktopDownloadManager(transport(), root.resolve("state.json"), muxer, sourceResolver = {
            requested.complete(Unit)
            withContext(NonCancellable) { release.await(); returned.complete(Unit); DesktopDownloadResolvedSource(source("fresh"), 80) }
        }, publication = lease())
        try {
            val id = manager.enqueue(source("expired"), root, metadata)
            withTimeout(5_000) { requested.await() }; manager.pause(id); release.complete(Unit)
            withTimeout(5_000) { returned.await() }
            val task = manager.tasks.value.single()
            assertEquals(DownloadStatus.PAUSED, task.status); assertEquals(id, task.id)
            assertNull(task.resolvedVideoQuality); assertEquals("requested 4K", task.item.qualityDesc)
            assertTrue(task.item.videoUrl.endsWith("token=expired")); assertNull(task.outputFile)
        } finally { release.complete(Unit); manager.close() }
    }

    @Test fun retiredAccountCannotCommitOldResolutionOrAdoptReplacementCookies() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-account-")
        val sessions = DesktopSessionStore.temporary(); val repository = DesktopRepository(sessions)
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-old"), AccountSummary(41, "old", ""))
        val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
        val requested = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Request>()
        val manager = DesktopDownloadManager(transport(requests), root.resolve("state.json"), muxer, sourceResolver = { task ->
            assertEquals(receipt, task.authorizationReceipt); requested.complete(Unit); release.await()
            DesktopDownloadResolvedSource(source("fresh", receipt), 80)
        }, publication = DesktopRepositoryPlaybackPublication(repository))
        try {
            val id = manager.enqueue(source("expired", receipt), root, metadata)
            withTimeout(5_000) { requested.await() }
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-new"), AccountSummary(42, "new", "")); release.complete(Unit)
            val task = withTimeout(5_000) { manager.tasks.first { it.singleOrNull()?.status == DownloadStatus.PAUSED }.single() }
            assertEquals(id, task.id); assertNull(task.resolvedVideoQuality)
            assertEquals("requested 4K", task.item.qualityDesc); assertEquals(receipt, task.authorizationReceipt)
            assertTrue(task.item.videoUrl.endsWith("token=expired")); assertNull(task.outputFile)
            assertFalse(requests.any { it.url.queryParameter("token") == "fresh" })
        } finally { release.complete(Unit); manager.close() }
    }

    @Test fun legacyTaskAcquiresMetadataOnlyWithItsAcceptedFreshAuthorization() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-legacy-"); val state = root.resolve("state.json")
        val original = OriginalTask(bvid = metadata.bvid, cid = metadata.cid, title = "legacy", cover = "",
            ownerName = "", ownerFace = "", duration = 1, quality = 120, qualityDesc = "requested 4K",
            videoUrl = source("expired").videoUrl, audioUrl = source("expired").audioUrl!!, status = DownloadStatus.PAUSED,
            options = com.android.purebilibili.feature.download.DownloadOptions(includeDanmaku = false))
        val legacy = DownloadTask(original, root.toString())
        // Old saved schema has no resolvedVideoQuality or transient receipt.
        val encoded = Json.encodeToJsonElement(DownloadTask.serializer(), legacy).jsonObject
        Files.writeString(state, JsonArray(listOf(JsonObject(encoded.filterKeys { it != "resolvedVideoQuality" }))).toString())
        val sessions = DesktopSessionStore.temporary(); val repository = DesktopRepository(sessions)
        val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
        var resolutions = 0
        val manager = DesktopDownloadManager(transport(), state, muxer, sourceResolver = { restored ->
            resolutions++; assertNull(restored.authorizationReceipt); assertEquals(legacy.id, restored.id)
            DesktopDownloadResolvedSource(source("fresh", receipt), 64)
        }, publication = DesktopRepositoryPlaybackPublication(repository))
        try {
            assertNull(manager.tasks.value.single().resolvedVideoQuality); manager.resume(legacy.id)
            val task = completed(manager)
            assertEquals(1, resolutions); assertEquals(legacy.id, task.id); assertEquals(legacy.directory, task.directory)
            assertEquals(120, task.item.quality); assertEquals(64, task.resolvedVideoQuality)
            assertEquals("720P 高清", task.item.qualityDesc); assertEquals(receipt, task.authorizationReceipt)
        } finally { manager.close() }
    }

    @Test fun missingQualityOnRestoredRefreshPreservesPreviouslyAcceptedActualMetadata() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-missing-"); val state = root.resolve("state.json")
        val original = OriginalTask(bvid = metadata.bvid, cid = metadata.cid, title = "existing fallback", cover = "",
            ownerName = "", ownerFace = "", duration = 1, quality = 120, qualityDesc = "720P 高清",
            videoUrl = source("expired").videoUrl, audioUrl = source("expired").audioUrl!!, status = DownloadStatus.PAUSED,
            options = com.android.purebilibili.feature.download.DownloadOptions(includeDanmaku = false))
        val saved = DownloadTask(original, root.toString(), resolvedVideoQuality = 64)
        Files.writeString(state, JsonArray(listOf(Json.encodeToJsonElement(DownloadTask.serializer(), saved))).toString())
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
        val manager = DesktopDownloadManager(transport(), state, muxer,
            sourceResolver = { DesktopDownloadResolvedSource(source("fresh", receipt), actualVideoQuality = null) },
            publication = DesktopRepositoryPlaybackPublication(repository))
        try {
            manager.resume(saved.id); val task = completed(manager)
            assertEquals(saved.id, task.id); assertEquals(saved.directory, task.directory)
            assertEquals(120, task.item.quality); assertEquals(64, task.resolvedVideoQuality)
            assertEquals("720P 高清", task.item.qualityDesc)
            assertTrue(Files.isRegularFile(Path.of(manager.offlinePlayback(saved.id).videoUrl)))
        } finally { manager.close() }
    }

    @Test fun audioRefreshAfterVideoCompletedCannotClaimTheUnfetchedVideoQuality() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-audio-refresh-"); val requests = CopyOnWriteArrayList<Request>()
        var resolutions = 0
        val manager = DesktopDownloadManager(transport(requests), root.resolve("state.json"), muxer, sourceResolver = {
            resolutions++; DesktopDownloadResolvedSource(PlaybackSource("https://cdn.example/video80?token=fresh", "https://cdn.example/audio?token=fresh"), 80)
        }, publication = lease())
        try {
            val initial = source("current").copy(audioUrl = source("expired").audioUrl)
            val id = manager.enqueue(initial, root, metadata); val task = completed(manager)
            assertEquals(1, resolutions); assertEquals(id, task.id); assertEquals(120, task.item.quality)
            assertNull(task.resolvedVideoQuality); assertEquals("requested 4K", task.item.qualityDesc)
            assertTrue(requests.any { it.method == "GET" && it.url.encodedPath == "/video" })
            assertTrue(requests.any { it.method == "GET" && it.url.encodedPath == "/audio" && it.url.queryParameter("token") == "fresh" })
            assertFalse(requests.any { it.url.encodedPath == "/video80" })
        } finally { manager.close() }
    }

    private fun savedVideo(root: Path, complete: Boolean): DownloadTask {
        val amount = if (complete) 45L else 10L
        val original = OriginalTask(bvid = metadata.bvid, cid = metadata.cid, title = "saved video", cover = "",
            ownerName = "", ownerFace = "", duration = 1, quality = 120, qualityDesc = "4K 超清",
            videoUrl = "https://cdn.example/video120?token=expired", audioUrl = "https://cdn.example/audio?token=expired",
            status = DownloadStatus.PAUSED, videoProgress = amount.toFloat() / 45f, downloadedSize = amount,
            options = com.android.purebilibili.feature.download.DownloadOptions(includeDanmaku = false),
            assets = listOf(com.android.purebilibili.feature.download.DownloadAssetState(
                com.android.purebilibili.feature.download.DownloadAssetKind.VIDEO,
                if (complete) com.android.purebilibili.feature.download.DownloadAssetStatus.COMPLETED else com.android.purebilibili.feature.download.DownloadAssetStatus.DOWNLOADING,
                totalBytes = 45L, downloadedBytes = amount), com.android.purebilibili.feature.download.DownloadAssetState(
                com.android.purebilibili.feature.download.DownloadAssetKind.AUDIO, com.android.purebilibili.feature.download.DownloadAssetStatus.PENDING)))
        val saved = DownloadTask(original, root.toString(), resolvedVideoQuality = 120)
        val directory = Path.of(saved.directory); Files.createDirectories(directory)
        Files.writeString(directory.resolve(".bilipai-download"), saved.id)
        if (complete) Files.write(directory.resolve("video.m4s"), ByteArray(45) { 120.toByte() })
        else {
            Files.write(directory.resolve("video.m4s.part"), ByteArray(10) { 120.toByte() })
            Files.write(directory.resolve("video.m4s.part.chunk0001"), ByteArray(5) { 120.toByte() })
        }
        Files.writeString(root.resolve("state.json"), JsonArray(listOf(Json.encodeToJsonElement(DownloadTask.serializer(), saved))).toString())
        return saved
    }

    @Test fun restoredChangedVideoIdentityClearsCompletedAndPartialBytesBeforeAcceptingQuality() = runBlocking<Unit> {
        for (complete in listOf(true, false)) {
            val root = Files.createTempDirectory("bilipai-quality-saved-change-"); val saved = savedVideo(root, complete)
            val requests = CopyOnWriteArrayList<Request>(); val freshVideo = ByteArray(45) { 80.toByte() }; val freshAudio = ByteArray(45) { 1 }
            val repository = DesktopRepository(DesktopSessionStore.temporary())
            val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
            val manager = DesktopDownloadManager(transport(requests) { if (it.url.encodedPath == "/video80") freshVideo else freshAudio },
                root.resolve("state.json"), DownloadMuxer { video, audio, output ->
                    assertContentEquals(freshVideo, Files.readAllBytes(assertNotNull(video)))
                    assertContentEquals(freshAudio, Files.readAllBytes(assertNotNull(audio))); Files.write(output, freshVideo + freshAudio)
                }, sourceResolver = { DesktopDownloadResolvedSource(PlaybackSource("https://cdn.example/video80?token=fresh",
                    "https://cdn.example/audio?token=fresh", authorizationReceipt = receipt), 80) },
                publication = DesktopRepositoryPlaybackPublication(repository))
            try {
                manager.resume(saved.id); val task = completed(manager)
                assertEquals(saved.id, task.id); assertEquals(saved.directory, task.directory); assertEquals(120, task.item.quality)
                assertEquals(80, task.resolvedVideoQuality); assertEquals("1080P 高清", task.item.qualityDesc); assertEquals(90L, task.downloadedBytes)
                assertTrue(requests.any { it.method == "GET" && it.url.encodedPath == "/video80" })
                assertFalse(requests.any { it.url.encodedPath == "/video80" && it.header("Range")?.startsWith("bytes=10-") == true })
                assertFalse(Files.exists(Path.of(saved.directory).resolve("video.m4s.part.chunk0001")))
                assertContentEquals(freshVideo + freshAudio, Files.readAllBytes(Path.of(manager.offlinePlayback(saved.id).videoUrl)))
            } finally { manager.close() }
        }
    }

    @Test fun pauseBeforeFinalRestoredSourceAdmissionRetainsCompletedAndPartialVideoBytes() = runBlocking<Unit> {
        for (complete in listOf(true, false)) {
            val root = Files.createTempDirectory("bilipai-quality-final-admission-"); val saved = savedVideo(root, complete)
            val directory = Path.of(saved.directory)
            if (complete) {
                Files.write(directory.resolve("video.m4s.part"), ByteArray(10) { 120.toByte() })
                Files.write(directory.resolve("video.m4s.part.chunk0001"), ByteArray(5) { 120.toByte() })
            }
            val fileNames = if (complete) listOf("video.m4s", "video.m4s.part", "video.m4s.part.chunk0001")
                else listOf("video.m4s.part", "video.m4s.part.chunk0001")
            val retained = fileNames.associateWith { Files.readAllBytes(directory.resolve(it)) }
            val requests = CopyOnWriteArrayList<Request>()
            val repository = DesktopRepository(DesktopSessionStore.temporary())
            val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
            val delegate = DesktopRepositoryPlaybackPublication(repository)
            val admissions = java.util.concurrent.atomic.AtomicInteger()
            val rejected = CompletableDeferred<Unit>(); val worker = CompletableDeferred<Job>()
            lateinit var manager: DesktopDownloadManager
            val publication = object : DesktopPlaybackPublication by delegate {
                override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
                    if (admissions.incrementAndGet() != 2) return delegate.admit(source, stillOwned, block)
                    // The first startup precheck has succeeded. Pause synchronously before the final action.
                    assertTrue(stillOwned()); assertTrue(repository.isPlaybackReceiptCurrent(receipt))
                    manager.pause(saved.id)
                    assertFalse(stillOwned()); assertTrue(repository.isPlaybackReceiptCurrent(receipt))
                    try {
                        return delegate.admit(source, stillOwned, block)
                    } catch (cancelled: CancellationException) {
                        rejected.complete(Unit)
                        throw cancelled
                    }
                }
            }
            manager = DesktopDownloadManager(transport(requests), root.resolve("state.json"), muxer,
                sourceResolver = {
                    worker.complete(currentCoroutineContext().job)
                    DesktopDownloadResolvedSource(PlaybackSource("https://cdn.example/video80?token=fresh",
                        "https://cdn.example/audio?token=fresh", authorizationReceipt = receipt), 80)
                }, publication = publication)
            try {
                val beforeResume = manager.tasks.value.single()
                manager.resume(saved.id)
                withTimeout(5_000) { rejected.await(); worker.await().join() }
                val task = manager.tasks.value.single()
                assertEquals(2, admissions.get()); assertEquals(DownloadStatus.PAUSED, task.status)
                assertEquals(saved.id, task.id); assertEquals(saved.directory, task.directory)
                assertEquals(saved.item.createdAt, task.item.createdAt); assertEquals(120, task.item.quality)
                assertEquals(120, task.resolvedVideoQuality); assertEquals(saved.item.qualityDesc, task.item.qualityDesc)
                assertEquals(beforeResume.item.assets, task.item.assets); assertEquals(beforeResume.downloadedBytes, task.downloadedBytes)
                assertEquals(saved.item.videoUrl, task.item.videoUrl); assertEquals(saved.item.audioUrl, task.item.audioUrl)
                assertNull(task.authorizationReceipt); assertNull(task.outputFile); assertTrue(requests.isEmpty())
                retained.forEach { (name, expected) ->
                    val file = directory.resolve(name)
                    assertTrue(Files.isRegularFile(file), name); assertContentEquals(expected, Files.readAllBytes(file), name)
                }
                assertEquals(saved.id, Files.readString(directory.resolve(".bilipai-download")))
                val persisted = Json.decodeFromString<List<DownloadTask>>(Files.readString(root.resolve("state.json"))).single()
                assertEquals(DownloadStatus.PAUSED, persisted.status); assertEquals(task.item, persisted.item)
                assertEquals(task.resolvedVideoQuality, persisted.resolvedVideoQuality)
            } finally { manager.close() }
        }
    }

    @Test fun restoredSameIdentityCompletedVideoAndRepeatedAudioRefreshKeepActualFileMetadata() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-saved-retain-"); val saved = savedVideo(root, true)
        val requests = CopyOnWriteArrayList<Request>(); val oldVideo = ByteArray(45) { 120.toByte() }; val freshAudio = ByteArray(45) { 1 }
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }.receipt
        var resolutions = 0
        val manager = DesktopDownloadManager(transport(requests) { if (it.url.encodedPath.startsWith("/video")) ByteArray(45) { 80.toByte() } else freshAudio },
            root.resolve("state.json"), DownloadMuxer { video, audio, output ->
                assertContentEquals(oldVideo, Files.readAllBytes(assertNotNull(video)))
                assertContentEquals(freshAudio, Files.readAllBytes(assertNotNull(audio))); Files.write(output, oldVideo + freshAudio)
            }, sourceResolver = {
                resolutions++
                val video = if (resolutions == 1) "https://cdn.example/video120?token=fresh" else "https://cdn.example/video80?token=fresh"
                val audio = if (resolutions == 1) "https://cdn.example/audio?token=expired" else "https://cdn.example/audio?token=fresh"
                DesktopDownloadResolvedSource(PlaybackSource(video, audio, authorizationReceipt = receipt), 80)
            }, publication = DesktopRepositoryPlaybackPublication(repository))
        try {
            manager.resume(saved.id); val task = completed(manager)
            assertEquals(2, resolutions); assertEquals(saved.id, task.id); assertEquals(saved.directory, task.directory)
            assertEquals(120, task.item.quality); assertEquals(120, task.resolvedVideoQuality); assertEquals("4K 超清", task.item.qualityDesc)
            assertEquals(90L, task.downloadedBytes)
            assertFalse(requests.any { it.method == "GET" && it.url.encodedPath.startsWith("/video") })
            assertFalse(requests.any { it.url.encodedPath == "/video80" })
            assertTrue(requests.any { it.method == "GET" && it.url.encodedPath == "/audio" && it.url.queryParameter("token") == "fresh" })
            assertContentEquals(oldVideo + freshAudio, Files.readAllBytes(Path.of(manager.offlinePlayback(saved.id).videoUrl)))
        } finally { manager.close() }
    }

    @Test fun audioOnlyRefreshCannotAcquireVideoQualityOrVideoRequest() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-audio-"); val requests = CopyOnWriteArrayList<Request>()
        val manager = DesktopDownloadManager(transport(requests), root.resolve("state.json"), DownloadMuxer { video, audio, output ->
            assertNull(video); assertNotNull(audio); assertTrue(output.toString().endsWith(".m4a")); Files.write(output, bytes)
        }, sourceResolver = { DesktopDownloadResolvedSource(PlaybackSource("https://cdn.example/audio?token=fresh"), 80) }, publication = lease())
        try {
            val id = manager.enqueue(source("expired"), root, metadata.copy(audioOnly = true, qualityLabel = "audio"))
            val task = completed(manager)
            assertEquals(id, task.id); assertNull(task.resolvedVideoQuality); assertEquals("audio", task.item.qualityDesc)
            assertFalse(requests.any { it.url.encodedPath == "/video" })
        } finally { manager.close() }
    }

    @Test fun progressiveRefreshKeepsSegmentTupleAndDoesNotInventDashQuality() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-quality-progressive-")
        fun progressive(token: String) = PlaybackSource("https://cdn.example/first.flv?token=$token", progressiveSegments = listOf(
            PlaybackSegment("https://cdn.example/first.flv?token=$token", 1.0), PlaybackSegment("https://cdn.example/second.flv?token=$token", 2.0)))
        val muxer = object : DownloadMuxer {
            override suspend fun mux(video: Path?, audio: Path?, output: Path) = error("progressive concatenation required")
            override suspend fun muxSegments(segments: List<Path>, output: Path, audioOnly: Boolean) {
                assertFalse(audioOnly); assertEquals(2, segments.size)
                segments.forEach { assertContentEquals(bytes, Files.readAllBytes(it)) }; Files.write(output, bytes + bytes)
            }
        }
        val manager = DesktopDownloadManager(transport(), root.resolve("state.json"), muxer,
            sourceResolver = { DesktopDownloadResolvedSource(progressive("fresh"), 80) }, publication = lease())
        try {
            val id = manager.enqueue(progressive("expired"), root, metadata)
            val task = completed(manager)
            assertEquals(id, task.id); assertNull(task.resolvedVideoQuality); assertEquals("requested 4K", task.item.qualityDesc)
            assertEquals(listOf(1.0, 2.0), task.progressiveSegments.map { it.durationSeconds })
            assertContentEquals(bytes + bytes, Files.readAllBytes(Path.of(manager.offlinePlayback(id).videoUrl)))
        } finally { manager.close() }
    }
}
