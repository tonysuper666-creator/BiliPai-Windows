@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.android.purebilibili.feature.download.DownloadTaskClickTarget
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.download.*
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

private var assertions = 0
private fun prove(value: Boolean, message: String) { check(value) { message }; assertions++; println("PASS $message") }
private suspend fun eventually(test: () -> Boolean) = withTimeout(8000) { while (!test()) delay(10) }
private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

fun main(args: Array<String>): Unit = runBlocking {
    val root = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(root)
    val clip = Path.of(args[1]); val candidateJar = Path.of(args[2])
    var loadedClasses = 0
    ZipFile(candidateJar.toFile()).use { z -> z.entries().asSequence().filter { it.name.endsWith(".class") }.forEach {
        Class.forName(it.name.removeSuffix(".class").replace('/', '.'), false, ClassLoader.getSystemClassLoader()); loadedClasses++
    } }
    prove(loadedClasses > 0, "Every candidate class loads without initialization")
    val managed = root.resolve("downloads"); Files.createDirectories(managed)
    fun item(cid: Long, quality: Int = 80, path: String? = null, status: DownloadStatus = DownloadStatus.COMPLETED) = OriginalTask(
        bvid = "BVsynthetic", cid = cid, title = "Synthetic episode $cid quality $quality", cover = "", ownerName = "fixture",
        ownerFace = "", duration = 4, quality = quality, qualityDesc = "$quality", videoUrl = "https://fixture.invalid/disabled",
        audioUrl = "", status = status, createdAt = cid, filePath = path, groupKey = "fixture-group", episodeSortIndex = cid.toInt(),
        lastPlaybackPositionMs = 500,
    )
    val originals = listOf(item(1), item(2), item(1, 64), item(3), item(4, status = DownloadStatus.PAUSED))
    val wrapped = originals.map { original ->
        val before = DownloadTask(original, managed.toString()); val dir = Path.of(before.directory); Files.createDirectories(dir)
        Files.writeString(dir.resolve(".bilipai-download"), before.id)
        val path = dir.resolve("video.mp4")
        if (original.cid <= 2) Files.copy(clip, path, StandardCopyOption.REPLACE_EXISTING)
        before.copy(item = original.copy(filePath = path.toString()))
    }
    val stateFile = root.resolve("queue.json"); val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    Files.writeString(stateFile, json.encodeToString(ListSerializer(DownloadTask.serializer()), wrapped))
    var httpCalls = 0
    val client = OkHttpClient.Builder().addInterceptor { httpCalls++; error("Fixture forbids HTTP") }.build()
    val manager = DesktopDownloadManager(client, stateFile, DownloadMuxer { _, _, _ -> error("Fixture forbids downloads") })
    val sessions = DesktopSessionStore(root.resolve("unused-session.json"), persistent = false)
    val stamp = requireNotNull(sessions.dynamicCacheOwner())
    val pageAlive = AtomicBoolean(true); var epoch = 0L
    val entryJob = SupervisorJob(); val entryScope = CoroutineScope(entryJob + Dispatchers.Default)
    val admitted = AtomicInteger()
    val owned = { pageAlive.get() && entryJob.isActive && epoch == 0L && sessions.dynamicCacheOwner() == stamp }
    val admission: ((() -> Unit) -> Boolean) = { effect ->
        var ran = false
        sessions.withCurrentDynamicCacheOwner(stamp) { if (owned()) { effect(); admitted.incrementAndGet(); ran = true } }
        ran
    }
    var network = true
    val noNativeMemory = DesktopRetainedMedia(entryScope, null) { error("An unavailable player cannot acquire native playback") }
    val noNative = DesktopOfflineTaskPlayerBinding(manager, noNativeMemory, null, entryScope, 0, { epoch }, owned, admission, { network }, { "Fixture actual initialization failure" })
    var online: DownloadTask? = null
    var scene: ImageComposeScene? = null
    var native: MpvPlayer? = null
    var nativeMemory: DesktopRetainedMedia? = null
    var binding: DesktopOfflineTaskPlayerBinding? = null
    try {
        val completed = wrapped.first(); val missing = wrapped.first { it.item.cid == 3L }; val paused = wrapped.first { it.item.cid == 4L }
        prove(noNative.selectedTask(completed.id)?.item?.quality == 80, "TaskId selects exact quality, not the first bvid match")
        prove(noNative.selectedTarget(completed) == DownloadTaskClickTarget.OfflinePlayer, "The existing original policy selects a real completed local file")
        prove(noNative.selectedTarget(missing) == DownloadTaskClickTarget.OnlinePlayer, "Missing completed file with actual network state keeps original online routing")
        network = false
        prove(noNative.selectedTarget(missing) == null, "Missing file without network does not fabricate an online success")
        network = true
        prove(noNative.selectedTarget(paused) == null, "Incomplete tasks cannot route to online or offline players")
        noNative.open(missing.id) { online = it }!!.join()
        prove(online?.id == missing.id && online?.item?.cid == 3L && online?.item?.quality == 80, "Online callback retains exact authoritative task fields")
        noNative.open(completed.id) { error("Real local file must not use HTTP fallback") }!!.join()
        prove(noNative.error == "Fixture actual initialization failure", "Actual native initialization failure is visible and does not open a task chooser")
        val ownScene = ImageComposeScene(width = 800, height = 600, coroutineContext = coroutineContext); scene = ownScene
        var backs = 0
        ownScene.setContent { DesktopAppearanceTheme(DesktopThemeSettings()) {
            DesktopOfflineTaskPlayerHost(completed.id, noNative, { backs++ }, { online = it }, { error("No fullscreen fixture action") }, { error("An unavailable player cannot render native content") })
        } }
        var time = 0L
        fun allNodes() = ownScene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun text(t: String) = allNodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == t } }
        withTimeout(5000) { while (!text("Fixture actual initialization failure")) { time += 32_000_000; ownScene.render(time).close(); delay(10) } }
        prove(text("离线播放") && text("返回"), "Task-specific failure surface renders a real return action")
        prove(!text("下载") && !text("暂停全部") && !text("Synthetic episode 2 quality 80"), "The taskId route cannot return the unrelated task-selection list")
        repeat(6) { time += 32_000_000; ownScene.render(time).close(); delay(5) }
        val backNode = allNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null && nodes(it).any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "返回" } } }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        val point = backNode.boundsInRoot.center
        ownScene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
        repeat(3) { time += 32_000_000; ownScene.render(time).close(); delay(5) }
        ownScene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time / 1_000_000 + 30, buttons = PointerButtons())
        repeat(5) { time += 32_000_000; ownScene.render(time).close(); delay(5) }
        prove(backs == 1, "The actual task-specific return button invokes the required navigation action once")
        ownScene.close(); scene = null
        prove(!noNative.isOwned(), "Task surface disposal retires its navigation binding")

        // The existing native player and existing retained memory, with actual local media. No production actor is created by the new binding.
        val target = MpvSoftwareTarget().apply { resize(160, 90) }
        val player = MpvPlayer(softwareTarget = target, useNullAudioOutput = true); native = player
        player.setMuted(true)
        var acquired = 0
        val retained = DesktopRetainedMedia(entryScope, player) { acquired++ }; nativeMemory = retained
        val ownBinding = DesktopOfflineTaskPlayerBinding(manager, retained, null, entryScope, 0, { epoch }, owned, admission, { network }, { error("The real native player initialized") }); binding = ownBinding
        ownBinding.open(completed.id) { error("Valid local file cannot route online") }!!.join()
        val accepted = requireNotNull(retained.offline.sourceVersion)
        prove(retained.offline.current == completed.id && ownBinding.ownsAcceptedSource(), "InitialTaskId publishes into the same retained offline memory and actual native source version")
        prove(player.state.value.sourceTitle == completed.title && player.state.value.positionSeconds == 0.5, "The existing manager restores the selected task's actual title and playback position")
        player.startSoftwareTransport()
        val frame = withTimeout(12000) { target.frames.first { it != null && it.sourceVersion == accepted } }!!
        prove(frame.sequence > 0 && frame.width == 160 && frame.height == 90, "Actual MPV decodes a real first frame belonging to the selected task version")
        prove(acquired == 1 && retained.current === retained.offline, "The sole retained media actor acquires only the actual offline memory")
        val expectedNext = manager.offlineEpisodeQueue(completed.id).let { queue -> queue[queue.indexOfFirst { it.id == completed.id } + 1] }
        retained.offline.next?.invoke()
        eventually { retained.offline.current == expectedNext.id && ownBinding.ownsAcceptedSource() }
        val secondVersion = requireNotNull(retained.offline.sourceVersion)
        prove(secondVersion != accepted && !player.ownsSourceVersion(accepted), "Original episode queue switching replaces the actual native token")
        ownBinding.close()
        eventually { !player.ownsSourceVersion(secondVersion) && retained.offline.current == null }
        prove(!player.ownsSourceVersion(secondVersion) && retained.offline.current == null, "Disposing the owning task entry clears its exact native source and memory")
        val reading = CountDownLatch(1); val releaseRead = CountDownLatch(1); val reads = AtomicInteger()
        val supersede = DesktopOfflineTaskPlayerBinding(manager, retained, null, entryScope, 0, { epoch }, owned, admission, {
            if (reads.incrementAndGet() == 1) { reading.countDown(); check(releaseRead.await(5, TimeUnit.SECONDS)) }
            network
        }, { error("Native initialized") }); binding = supersede
        val acquiredBefore = acquired
        val oldRequest = supersede.open(completed.id) { error("Local supersession cannot navigate online") }!!
        check(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        val latestRequest = supersede.open(expectedNext.id) { error("Local supersession cannot navigate online") }!!
        latestRequest.join(); releaseRead.countDown(); oldRequest.join()
        prove(retained.offline.current == expectedNext.id && supersede.ownsAcceptedSource() && acquired == acquiredBefore + 1, "A delayed superseded file-validation request cannot publish or acquire native playback")
        prove(oldRequest.isCancelled && !supersede.opening, "Supersession preserves cancellation and clears the actual entry loading state")
        epoch = 1L
        val beforeActions = player.state.value.paused
        supersede.runPlayerAction { it.setPaused(!beforeActions) }
        prove(player.state.value.paused == beforeActions && supersede.open(completed.id) { error("Retired route must not navigate") } == null, "A changed account epoch rejects player commands and new task requests")
        epoch = 0L
        val foreignVersion = player.loadVersioned(PlaybackSource(clip.toString(), referer = "", title = "Independent actual native source", startPaused = true))
        supersede.close()
        prove(player.ownsSourceVersion(foreignVersion), "Old task binding disposal cannot stop an independent native source")
        val cancelledEntry = SupervisorJob(entryJob); val cancelledScope = CoroutineScope(cancelledEntry + Dispatchers.Default)
        val cancelReading = CountDownLatch(1); val cancelRelease = CountDownLatch(1)
        val cancelBinding = DesktopOfflineTaskPlayerBinding(manager, retained, null, cancelledScope, 0, { epoch }, owned, admission, {
            cancelReading.countDown(); check(cancelRelease.await(5, TimeUnit.SECONDS)); network
        }, { error("Native initialized") })
        val cancelledRequest = cancelBinding.open(completed.id) { error("Cancelled entry cannot navigate") }!!
        check(withContext(Dispatchers.IO) { cancelReading.await(5, TimeUnit.SECONDS) })
        cancelledEntry.cancel(); cancelRelease.countDown(); cancelledRequest.join(); cancelledEntry.join()
        prove(cancelledRequest.isCancelled && !cancelBinding.isOwned() && !cancelBinding.opening && player.ownsSourceVersion(foreignVersion), "Entry-job cancellation rejects late file validation and leaves a foreign native owner intact")
        prove(manager.tasks.value.size == wrapped.size && Files.isRegularFile(Path.of(completed.directory).resolve("video.mp4")), "Playback does not replace task storage or remove local files")
        prove(httpCalls == 0, "All task routing and native validation use local files without HTTP")
        println("OFFLINE_TASK_PROOF " + buildJsonObject { put("status", "PASS"); put("groups", 4); put("assertions", assertions); put("pointerPairs", 1); put("loadedCandidateClasses", loadedClasses); put("HTTPCalls", httpCalls); put("nativeFirstFrame", true); put("sameRetainedOfflineMemory", true); put("fullOriginalOfflineUi", false); put("rootMounted", false); put("actualRootRuntimeAccepted", false); put("chooserOpened", false); put("nativeOverlayPaintAccepted", false) })
    } finally {
        scene?.close(); binding?.close(); nativeMemory?.close(); native?.close(); noNative.close(); noNativeMemory.close()
        pageAlive.set(false); entryScope.cancel(); manager.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }
}
