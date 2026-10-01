package com.bilipai.desktop.ui.videoDefaultProof

import com.bilipai.desktop.ui.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import okhttp3.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.*

private data class StageGate(val directory: Path) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val fired = AtomicBoolean(false)
    val stage = AtomicReference<Path?>(null)
}

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]).toRealPath()
    val candidateJar = Path.of(args[1]).toRealPath()
    val mainJar = Path.of(args[2]).toRealPath()
    val source = Files.readAllBytes(root.resolve("input.mp4"))
    var assertions = 0
    fun prove(value: Boolean, reason: String) { check(value) { reason }; assertions++ }
    fun list(path: Path): List<Path> = if (Files.isDirectory(path)) Files.list(path).use { it.toList() } else emptyList()
    fun scratch(path: Path) = list(path).filter { it.fileName.toString().startsWith(".bilipai-") }
    fun outputs(path: Path) = list(path).filter { it.fileName.toString().startsWith("BiliPai_") }
    fun waitFor(reason: String, block: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!block()) { check(System.nanoTime() < until) { reason }; Thread.sleep(5) }
    }
    val traces = CopyOnWriteArrayList<String>()
    val anonymous = AtomicBoolean(true)
    val referer = AtomicBoolean(true)
    val executor = Executors.newCachedThreadPool { task -> Thread(task, "video-default-loopback-fixture").apply { isDaemon = true } }
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.executor = executor
    server.createContext("/") { exchange ->
        traces += exchange.requestURI.path
        anonymous.compareAndSet(true, exchange.requestHeaders.getFirst("Cookie") == null && exchange.requestHeaders.getFirst(FORCE_COOKIE_HEADER) == null)
        referer.compareAndSet(true, exchange.requestHeaders.getFirst("Referer") == "https://www.bilibili.com/")
        try { exchange.sendResponseHeaders(200, source.size.toLong()); exchange.responseBody.use { it.write(source) } }
        catch (_: java.io.IOException) { /* task may cancel */ }
        finally { exchange.close() }
    }
    server.start()
    val sessions = DesktopSessionStore.temporary()
    sessions.saveAccount(mapOf("SESSDATA" to "declared-video-default-fixture-cookie"), AccountSummary(120L, "Declared video default fixture", ""))
    val repository = DesktopRepository(sessions)
    val owner = requireNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner())
    val operations = DesktopDynamicCardOperations(repository)
    val store = DesktopPluginStore(root.resolve("global-settings"))
    val lifetime = DesktopImageSaveLifetime { false }
    val preferences = DesktopImageSaveLocationPreferences(store, lifetime::withCommit)
    val custom = Files.createDirectory(root.resolve("custom-images"))
    val videoBase = Files.createDirectory(root.resolve("videos-base"))
    val videoDirectory = videoBase.resolve("BiliPai")
    val pictureQueries = AtomicInteger()
    val videoQueries = AtomicInteger()
    val videoResolution = AtomicReference(Result.success(videoDirectory))
    val locations = DesktopImageSaveLocations(preferences, lifetime::isActive, lifetime::withCommit,
        resolveDefaultVideoDirectory = { videoQueries.incrementAndGet(); videoResolution.get() },
        resolveDefaultDirectory = { pictureQueries.incrementAndGet(); error("Pictures resolver forbidden for standalone MP4") })
    val gate = AtomicReference<StageGate?>(null)
    val client = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
        .dns { host -> check(host == "127.0.0.1"); listOf(InetAddress.getByName("127.0.0.1")) }
        .addInterceptor { chain ->
            val original = chain.request(); check(original.url.host == "fixture.invalid")
            chain.proceed(original.newBuilder().url("http://127.0.0.1:${server.address.port}${original.url.encodedPath}")
                .header("Cookie", "declared-fixture-cookie").header(FORCE_COOKIE_HEADER, "declared-forced-cookie").build())
        }.build()
    fun binding(): DesktopDynamicImageAssets = DesktopDynamicImageAssets(client, {
        val owned = operations.isOwned()
        val pending = gate.get()
        val rawWriterCheckpoint = Thread.currentThread().stackTrace.any {
            it.className.startsWith("com.bilipai.desktop.ui.DesktopDynamicImageAssets\$writeRaw")
        }
        if (owned && pending != null && rawWriterCheckpoint && !pending.fired.get()) {
            val staged = scratch(pending.directory).firstOrNull { path ->
                path.fileName.toString().startsWith(".bilipai-download-") && runCatching {
                    Files.readAllBytes(path).contentEquals(source)
                }.getOrDefault(false)
            }
            if (staged != null && pending.fired.compareAndSet(false, true)) {
                pending.stage.set(staged); pending.entered.countDown()
                check(pending.release.await(8, TimeUnit.SECONDS)) { "raw writer checkpoint release timeout" }
            }
        }
        owned
    }, repository.dynamicCacheSessionGuard, owner,
        selectTarget = { _, _ -> error("default MP4 must not show SaveAs") },
        selectDirectory = { error("default MP4 must not show directory chooser") }, imageSaveLocations = locations)
    val actualStoreLock = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(sessions)
    fun storeFinalBlocked() = Thread.getAllStackTraces().any { (thread, stack) ->
        thread.state == Thread.State.BLOCKED && stack.any { it.className == DesktopSessionStore::class.java.name && it.methodName == "withCurrentDynamicCacheOwner" }
    }
    val reports = mutableListOf<String>()
    val classSources = mutableListOf<String>()
    try {
        val candidateClasses = listOf(DesktopDynamicImageAssets::class.java, DesktopDynamicSaveTarget::class.java,
            DesktopImageSaveLocations::class.java, DesktopImageSaveLifetime::class.java)
        val actualClasses = listOf(DesktopSessionStore::class.java, DesktopRepository::class.java,
            DesktopDynamicCardOperations::class.java, DesktopImageSaveLocationPreferences::class.java, DesktopPluginStore::class.java)
        for ((clazz, expected) in candidateClasses.map { it to candidateJar } + actualClasses.map { it to mainJar }) {
            prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == expected, "declared class code source: ${clazz.name}")
            classSources += """{"className":"${clazz.name}","candidateOverride":${expected == candidateJar}}"""
        }
        prove(repository.dynamicCacheSessionGuard === sessions, "single actual SessionStore authority")
        preferences.setImageSaveTreeUri(custom.toUri().toString())
        val originalPreference = preferences.getImageSaveTreeUriSync()
        prove(originalPreference == custom.toUri().toString(), "same global original image preference persisted")
        binding().use { prove(it.saveLivePhotoVideo("https://fixture.invalid/default/live.mp4"), "actual candidate standalone save") }
        val saved = outputs(videoDirectory).single()
        prove(saved.fileName.toString().matches(Regex("BiliPai_Live_[0-9]+\\.mp4")), "original standalone MP4 display name")
        prove(Files.readAllBytes(saved).contentEquals(source), "actual streaming raw MP4 bytes exact")
        prove(traces.size == 1 && videoQueries.get() == 1 && pictureQueries.get() == 0, "one anonymous source GET and only Videos resolver")
        prove(list(custom).isEmpty() && preferences.getImageSaveTreeUriSync() == originalPreference, "custom image destination and setting untouched")
        prove(scratch(videoDirectory).isEmpty(), "successful download stage drained")
        reports += """{"name":"actual-candidate-streaming-standalone-default-video","requests":1,"passed":true}"""

        val collision = videoDirectory.resolve("BiliPai_Live_42.mp4")
        Files.writeString(collision, "declared old MP4 sentinel")
        var selected: Path? = null
        val scopeContext = currentCoroutineContext()
        fun fixtureCommit(block: () -> Unit): Boolean = sessions.withCurrentDynamicCacheOwner(owner) {
            locations.withCommit { scopeContext.ensureActive(); block() }
        }
        prove(locations.saveDefaultVideo("BiliPai_Live_42.mp4", { currentCoroutineContext().ensureActive() }, ::fixtureCommit) {
            selected = it.path
            prove(!it.replaceExisting, "default resolver target never opts into overwrite")
            true
        }, "shared location collision reservation")
        prove(selected == videoDirectory.resolve("BiliPai_Live_42 (1).mp4"), "original timestamp kept with collision-only suffix")
        prove(Files.readString(collision) == "declared old MP4 sentinel" && !Files.exists(selected), "location selection never writes or overwrites file")
        reports += """{"name":"location-only-default-collision-selection","requests":0,"passed":true}"""

        videoResolution.set(Result.failure(IllegalStateException("declared video resolver failure")))
        val beforeFailure = traces.size
        val failure = binding().use { runCatching { it.saveLivePhotoVideo("https://fixture.invalid/failure/live.mp4") }.exceptionOrNull() }
        prove(failure is IllegalStateException && failure.message == "declared video resolver failure", "Videos resolution failure remains failure")
        prove(traces.size == beforeFailure && pictureQueries.get() == 0 && list(custom).isEmpty(), "native failure has no GET, image fallback or chooser")
        prove(preferences.getImageSaveTreeUriSync() == originalPreference, "video failure preserves original image preference")
        videoResolution.set(Result.success(videoDirectory))
        reports += """{"name":"default-video-resolver-failure-no-image-fallback","requests":0,"passed":true}"""

        suspend fun finalBarrier(name: String, closeLifetime: Boolean) {
            val pending = StageGate(videoDirectory); gate.set(pending)
            val beforeFiles = outputs(videoDirectory).toSet(); val before = traces.size
            binding().use { assets ->
                val saving = async(Dispatchers.IO) { assets.saveLivePhotoVideo("https://fixture.invalid/$name/live.mp4") }
                prove(withContext(Dispatchers.IO) { pending.entered.await(5, TimeUnit.SECONDS) }, "$name complete actual raw stage observed")
                prove(Files.readAllBytes(requireNotNull(pending.stage.get())).contentEquals(source), "$name complete MP4 stage")
                synchronized(actualStoreLock) {
                    pending.release.countDown()
                    waitFor("actual SessionStore final monitor must block") { storeFinalBlocked() }
                    if (closeLifetime) lifetime.close() else saving.cancel(CancellationException("declared cancel-only-save-job"))
                    prove(operations.isOwned(), "$name current account and card owner remain active")
                    prove(if (closeLifetime) !lifetime.isActive() else lifetime.isActive(), "$name exact retirement authority")
                    prove(outputs(videoDirectory).toSet() == beforeFiles, "$name no output before Store gate release")
                }
                val result = runCatching { saving.await() }
                prove(result.exceptionOrNull() is CancellationException, "$name cancellation reaches caller")
                saving.join()
                prove(outputs(videoDirectory).toSet() == beforeFiles && scratch(videoDirectory).isEmpty(), "$name no late commit and raw stage drained")
                prove(traces.size == before + 1 && pictureQueries.get() == 0, "$name exactly one GET, no fallback")
                prove(Files.readString(collision) == "declared old MP4 sentinel", "$name previous file unchanged")
            }
            gate.set(null)
            reports += """{"name":"$name","requests":1,"passed":true}"""
        }
        finalBarrier("cancel-only-save-job-at-actual-store-final-gate", false)
        finalBarrier("same-global-app-lifetime-close-at-actual-store-final-gate", true)
        prove(anonymous.get() && referer.get() && traces.size == 3, "all three real loopback GETs strip both cookies and retain Bilibili Referer")
        prove(preferences.getImageSaveTreeUriSync() == originalPreference && list(custom).isEmpty(), "all video paths leave original image setting and folder unchanged")
        prove(scratch(Path.of(System.getProperty("java.io.tmpdir"))).isEmpty(), "task temporary download scratch drained")
        Files.writeString(root.resolve("result.json"), """{"passed":true,"cases":${reports.size},"assertions":$assertions,"requests":${traces.size},"classSources":[${classSources.joinToString(",")}],"reports":[${reports.joinToString(",")}],"outsideHttp":false,"actualWindowsKnownFolderWrites":false,"sharedGradle":false,"MainIntegrated":false,"HWND":false,"actualSaveAs":false,"allWritesInNewTaskLane":true}""")
        println("PASS ${reports.size} cases, $assertions assertions, ${traces.size} loopback GETs; candidate overrides explicitly declared")
    } finally {
        gate.get()?.release?.countDown(); server.stop(0); executor.shutdownNow()
        lifetime.close(); store.freezeWrites()
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
