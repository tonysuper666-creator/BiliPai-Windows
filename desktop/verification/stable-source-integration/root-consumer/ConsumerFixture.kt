package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.*
import com.android.purebilibili.data.model.response.ViewPoint
import com.android.purebilibili.feature.video.ui.overlay.*
import com.android.purebilibili.feature.home.components.cards.VideoCardCoverColorStore
import com.bilipai.desktop.player.PlaybackSource
import coil3.PlatformContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private var assertions = 0
private fun expect(value: Boolean, message: String) { assertions++; check(value) { message } }

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args.single()); Files.createDirectories(root)
    val transport = AtomicInteger()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        transport.incrementAndGet()
        val data = ByteArray(32) { 3 }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("Content-Length", data.size.toString())
            .body((if (chain.request().method == "HEAD") byteArrayOf() else data).toResponseBody()).build()
    }.build()
    val gates = (0..2).associate { "task-$it" to CompletableDeferred<Unit>() }
    val starts = ConcurrentHashMap.newKeySet<String>()
    val active = AtomicInteger(); val peak = AtomicInteger()
    val muxer = DownloadMuxer { _, _, output ->
        val id = output.fileName.toString().substringBefore(".mp4")
        val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
        starts.add(id)
        try { gates.getValue(id).await(); Files.write(output, byteArrayOf(9, 8, 7)) }
        finally { active.decrementAndGet() }
    }
    val manager = DesktopDownloadManager(client, root.resolve("queue.json"), muxer)
    try {
        val ids = (0..2).map { index -> manager.enqueue(
            PlaybackSource("https://cdn.example/task-$index", title = "task-$index"), root.resolve("downloads"),
            DownloadMetadata(bvid = "BV-fixture-$index", cid = index + 1L, includeCover = false, includeDanmaku = false)) }
        withTimeout(10_000) { while (starts.size != 2) delay(10) }
        expect(active.get() == 2, "two tasks must run concurrently")
        expect(peak.get() == 2, "default concurrency must be two")
        expect(manager.tasks.value.count { it.status == DownloadStatus.MERGING } == 2, "both tasks reserve their active slot")
        expect(manager.tasks.value.count { it.status == DownloadStatus.QUEUED } == 1, "third task must remain queued")
        expect(starts == setOf("task-0", "task-1"), "original createdAt/id queue order must be retained")
        gates.getValue("task-0").complete(Unit)
        withTimeout(10_000) { while ("task-2" !in starts) delay(10) }
        expect(peak.get() == 2, "finishing one task must admit exactly one queued task")
        gates.values.forEach { it.complete(Unit) }
        withTimeout(10_000) { while (manager.tasks.value.any { it.status != DownloadStatus.COMPLETED }) delay(10) }
        expect(manager.tasks.value.size == 3, "all three task owners must remain distinct")
        ids.forEach { id -> expect(Files.readAllBytes(Path.of(manager.offlinePlayback(id).videoUrl)).contentEquals(byteArrayOf(9, 8, 7)), "each completed task must expose its own output") }
        expect(peak.get() == 2, "concurrency may never exceed the original default")
    } finally { gates.values.forEach { it.complete(Unit) }; manager.close() }

    val points = normalizeViewPointSegments(listOf(ViewPoint(from=40,to=120,content="B"),
        ViewPoint(from=0,to=50,content="A"),ViewPoint(from=100,to=200,content="C")),250_000)
    expect(points.map { it.fromMs to it.toMs } == listOf(0L to 50_000L,50_000L to 120_000L,120_000L to 200_000L), "use original overlap clipping")
    expect(findViewPointSegmentAt(points,50_000)?.content == "B", "chapter boundary selects the next chapter")
    expect(findViewPointSegmentAt(points,240_000)?.content == "C", "original final chapter extends to video end")
    expect(findViewPointSegmentAt(points,-1) == null, "position before the first chapter must not seek")
    expect(normalizeViewPointSegments(listOf(ViewPoint(from=0,to=20,content="")),20_000).isEmpty(), "blank chapter names are omitted")
    expect(normalizeViewPointSegments(listOf(ViewPoint(from=0,to=99,content="end")),10_000).single().toMs == 10_000L, "chapter is clamped to actual duration")

    val image = root.resolve("cover.png")
    val bitmap = BufferedImage(1600,900,BufferedImage.TYPE_INT_RGB)
    val graphics = bitmap.createGraphics()
    try { graphics.color=java.awt.Color(200,30,45);graphics.fillRect(0,0,1600,900) }
    finally { graphics.dispose() }
    ImageIO.write(bitmap,"png",image.toFile())
    VideoCardCoverColorStore.clear()
    val color = VideoCardCoverColorStore.extractColor(PlatformContext.INSTANCE,"fixture-cover",image.toUri().toString())
    expect(color != null,"new original sampled palette request must decode an actual local image")
    Files.delete(image)
    expect(VideoCardCoverColorStore.extractColor(PlatformContext.INSTANCE,"fixture-cover",image.toUri().toString()) == color,"color cache must be checked before decoding again")
    VideoCardCoverColorStore.trimToSize(0)
    expect(VideoCardCoverColorStore.getCachedColor("fixture-cover") == null,"original bounded cache eviction must remain effective")
    Files.writeString(root.resolve("result.json"),"""{"passed":true,"cases":3,"assertions":$assertions,"peakConcurrentTasks":${peak.get()},"declaredTransportCalls":${transport.get()},"externalHttp":0,"socketDns":0,"wholeStableRuntimeAccepted":false}""")
    println("PASS $assertions assertions; peak tasks=${peak.get()}; no external HTTP")
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
}
