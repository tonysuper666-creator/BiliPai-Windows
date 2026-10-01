package com.bilipai.desktop.ui.main04proof

import com.bilipai.desktop.ui.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.*

private data class StageGate(val custom: Path, val prefix: String, val complete: (Path) -> Boolean) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val fired = AtomicBoolean(false)
    val observedStage = AtomicReference<Path?>(null)
    val enteredMillis = AtomicLong()
}

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]).toRealPath()
    val mainJar = Path.of(args[1]).toRealPath()
    val temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
    var checks = 0
    fun prove(value: Boolean, reason: String) { check(value) { reason }; checks++ }
    fun list(directory: Path): List<Path> = if (Files.exists(directory)) Files.list(directory).use { it.toList() } else emptyList()
    fun scratch(directory: Path) = list(directory).filter { it.fileName.toString().startsWith(".bilipai-") }
    fun output(directory: Path) = list(directory).filter { it.fileName.toString().startsWith("BiliPai_") }
    fun waitFor(reason: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) { check(System.nanoTime() < until) { reason }; Thread.sleep(5) }
    }
    val inputs = root.resolve("inputs")
    val alpha = Files.readAllBytes(inputs.resolve("alpha.png"))
    val rgb = Files.readAllBytes(inputs.resolve("rgb.png"))
    val gif = Files.readAllBytes(inputs.resolve("animated.gif"))
    val webp = Files.readAllBytes(inputs.resolve("animated.webp"))
    val malformed = Files.readAllBytes(inputs.resolve("decode-failure.png"))
    val video = Files.readAllBytes(inputs.resolve("motion.mp4"))
    val traces = CopyOnWriteArrayList<String>()
    val anonymous = AtomicBoolean(true); val referer = AtomicBoolean(true)
    val executor = Executors.newCachedThreadPool { task -> Thread(task, "main04-loopback-fixture").apply { isDaemon = true } }
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.executor = executor
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        traces.add(path)
        anonymous.compareAndSet(true, exchange.requestHeaders.getFirst("Cookie") == null && exchange.requestHeaders.getFirst(FORCE_COOKIE_HEADER) == null)
        referer.compareAndSet(true, exchange.requestHeaders.getFirst("Referer") == "https://www.bilibili.com/")
        val bytes = when {
            path.endsWith("bad.png") -> malformed
            path.endsWith(".gif") -> gif
            path.endsWith(".webp") -> webp
            path.endsWith(".jpg") -> rgb // Proves remaining input is decoded/reencoded, not byte-copied by suffix.
            path.endsWith(".mp4") -> video
            else -> alpha
        }
        try {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: java.io.IOException) { /* Expected if cancelled in transport. */ }
        finally { exchange.close() }
    }
    server.start()
    val sessions = DesktopSessionStore.temporary()
    sessions.saveAccount(mapOf("SESSDATA" to "declared-main04-fixture-cookie"), AccountSummary(120L, "Declared integration fixture account", ""))
    val repository = DesktopRepository(sessions)
    val store = DesktopPluginStore(root.resolve("global-settings"))
    val lifetime = DesktopImageSaveLifetime { false }
    val preferences = DesktopImageSaveLocationPreferences(store, lifetime::withCommit)
    val defaultBase = Files.createDirectory(root.resolve("default-base"))
    val defaultDirectory = defaultBase.resolve("BiliPai")
    val defaultQueries = AtomicInteger()
    val locations = DesktopImageSaveLocations(preferences, lifetime::isActive, lifetime::withCommit) {
        defaultQueries.incrementAndGet(); Result.success(defaultDirectory)
    }
    val gate = AtomicReference<StageGate?>(null)
    val client = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
        .dns { name -> check(name == "127.0.0.1") { "Outside DNS forbidden" }; listOf(InetAddress.getByName("127.0.0.1")) }
        .addInterceptor { chain ->
            val original = chain.request(); check(original.url.host == "fixture.invalid") { "Outside request forbidden" }
            chain.proceed(original.newBuilder().url("http://127.0.0.1:${server.address.port}${original.url.encodedPath}")
                .header("Cookie", "declared-fixture-header").header(FORCE_COOKIE_HEADER, "declared-forced-header").build())
        }.build()
    fun binding(): DesktopDynamicImageAssets {
        val operations = DesktopDynamicCardOperations(repository)
        val owner = requireNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner())
        return DesktopDynamicImageAssets(client, {
            val owned = operations.isOwned()
            val pending = gate.get()
            if (owned && pending != null && !pending.fired.get()) {
                val stage = list(pending.custom).firstOrNull { path -> path.fileName.toString().startsWith(pending.prefix) && runCatching { pending.complete(path) }.getOrDefault(false) }
                if (stage != null && pending.fired.compareAndSet(false, true)) {
                    pending.observedStage.set(stage); pending.enteredMillis.set(System.currentTimeMillis()); pending.entered.countDown()
                    check(pending.release.await(8, TimeUnit.SECONDS)) { "fixture encoded stage release timeout" }
                }
            }
            owned
        }, repository.dynamicCacheSessionGuard, owner,
            selectTarget = { _, _ -> error("remembered location must not invoke chooser") },
            selectDirectory = { error("remembered location must not invoke batch chooser") }, imageSaveLocations = locations)
    }
    suspend fun custom(name: String): Path {
        val directory = Files.createDirectory(root.resolve(name))
        preferences.setImageSaveTreeUri(directory.toUri().toString())
        prove(preferences.getImageSaveTreeUriSync() == directory.toUri().toString(), "$name actual persisted preference")
        return directory
    }
    fun completePng(path: Path): Boolean {
        val bytes = Files.readAllBytes(path)
        return bytes.size >= 20 && bytes.takeLast(8).toByteArray().contentEquals(byteArrayOf(73, 69, 78, 68, -82, 66, 96, -126))
    }
    fun completeMotion(path: Path): Boolean {
        val size = Files.size(path)
        if (size <= video.size + 4L) return false
        val bytes = Files.readAllBytes(path)
        return bytes.takeLast(video.size).toByteArray().contentEquals(video)
    }
    val lockField = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }
    val actualStoreLock = lockField.get(sessions)
    fun storeFinalBlocked() = Thread.getAllStackTraces().any { (thread, stack) ->
        thread.state == Thread.State.BLOCKED && stack.any { it.className == DesktopSessionStore::class.java.name && it.methodName == "withCurrentDynamicCacheOwner" }
    }
    val reports = mutableListOf<String>()
    val outputsForIndependent = mutableListOf<String>()
    try {
        val productClasses = listOf(DesktopDynamicImageAssets::class.java, DesktopImageSaveLocations::class.java, DesktopImageSaveLifetime::class.java,
            DesktopImageSaveLocationPreferences::class.java, DesktopPluginStore::class.java, DesktopSessionStore::class.java, DesktopRepository::class.java,
            DesktopDynamicCardOperations::class.java, DesktopDynamicSaveTarget::class.java, DesktopDynamicMotionPhotoFiles::class.java, DesktopDynamicMotionPhotoExif::class.java,
            Class.forName("com.bilipai.desktop.ui.DesktopDynamicStaticImageCodecKt"),
            Class.forName("com.android.purebilibili.feature.dynamic.components.DesktopOriginalStaticGalleryFormatKt"))
        for (clazz in productClasses) prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == mainJar, "actual Main04 codeSource: ${clazz.name}")
        prove(repository.dynamicCacheSessionGuard === sessions, "single actual SessionStore authority")
        reports += """{"name":"actual-main04-product-codesource-zerooverride","productClasses":${productClasses.size},"passed":true}"""

        val success = custom("custom-success")
        val beforeSuccess = traces.size; val beforeDefaults = defaultQueries.get()
        binding().use { assets ->
            for (item in listOf("alpha.png", "remaining.jpg", "animated.gif", "animated.webp")) prove(assets.saveImage("https://fixture.invalid/custom-success/$item"), "stored custom $item save")
        }
        val successFiles = output(success)
        prove(successFiles.size == 4 && traces.size == beforeSuccess + 4, "custom PNG/JPEG/GIF/WebP one HTTP source each")
        prove(defaultQueries.get() == beforeDefaults && !Files.exists(defaultDirectory), "successful stored custom never resolves default")
        prove(successFiles.single { it.toString().endsWith(".gif") }.let(Files::readAllBytes).contentEquals(gif), "custom GIF exact animation bytes")
        prove(successFiles.single { it.toString().endsWith(".webp") }.let(Files::readAllBytes).contentEquals(webp), "custom WebP exact animation bytes")
        prove(scratch(success).isEmpty() && scratch(temporary).isEmpty(), "custom successful stages and owned download sources drained")
        for (file in successFiles) outputsForIndependent += """{"kind":"${file.fileName.toString().substringAfterLast('.')}","path":"${root.relativize(file).toString().replace('\\', '/')}"}"""
        reports += """{"name":"stored-custom-real-png-jpeg-gif-webp","requests":4,"passed":true}"""

        suspend fun finalFailure(name: String, motion: Boolean) {
            val directory = custom(name)
            val prefix = if (motion) ".bilipai-motion-photo-" else ".bilipai-image-"
            val pending = StageGate(directory, prefix, if (motion) ::completeMotion else ::completePng)
            gate.set(pending)
            val start = System.currentTimeMillis(); val before = traces.size; val beforeDefaultFiles = output(defaultDirectory).toSet()
            binding().use { assets ->
                val saving = async(Dispatchers.IO) {
                    if (motion) assets.saveMotionPhoto("https://fixture.invalid/$name/alpha.png", "https://fixture.invalid/$name/video.mp4")
                    else assets.saveImage("https://fixture.invalid/$name/alpha.png")
                }
                prove(withContext(Dispatchers.IO) { pending.entered.await(5, TimeUnit.SECONDS) }, "$name complete custom encoded stage observed")
                val observed = requireNotNull(pending.observedStage.get())
                Files.copy(observed, root.resolve("$name-first-complete-stage.${if (motion) "bin" else "png"}"))
                synchronized(actualStoreLock) {
                    pending.release.countDown()
                    waitFor("$name actual final Store monitor must block") { storeFinalBlocked() }
                    val end = pending.enteredMillis.get() + 2L
                    require(end - start < 5000L) { "filename collision fixture window unexpectedly large" }
                    val stem = if (motion) "BiliPai_Live_" else "BiliPai_"
                    val extension = if (motion) ".jpg" else ".png"
                    for (timestamp in (start - 2L)..end) Files.writeString(directory.resolve("$stem$timestamp$extension"), "declared fixture collision sentinel")
                }
                prove(saving.await(), "$name actual ordinary custom final file failure falls back")
            }
            gate.set(null)
            val fallback = (output(defaultDirectory).toSet() - beforeDefaultFiles).single()
            prove(Files.readString(directory.resolve(fallback.fileName)).startsWith("declared fixture collision"), "$name exact selected custom target occupied after selection")
            prove(traces.size == before + if (motion) 2 else 1, "$name same owned sources reused without extra HTTP")
            prove(scratch(directory).isEmpty() && scratch(defaultDirectory).isEmpty() && scratch(temporary).isEmpty(), "$name custom/default stages and original sources drained")
            if (motion) {
                val bytes = Files.readAllBytes(fallback)
                prove(bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes.takeLast(video.size).toByteArray().contentEquals(video), "$name actual Files JPEG and exact MP4 tail")
            } else prove(completePng(fallback), "$name default reopened source produced complete PNG")
            outputsForIndependent += """{"kind":"${if (motion) "motion" else "fallback-png"}","path":"${root.relativize(fallback).toString().replace('\\', '/')}"}"""
            reports += """{"name":"$name","requests":${if (motion) 2 else 1},"firstCompleteCustomStage":true,"actualStoreFinalFileFailure":true,"freshSameOwnedSource":true,"scratchDrained":true,"passed":true}"""
        }
        finalFailure("custom-final-failure", false)

        val batch = custom("batch-middle-decode-failure")
        val beforeBatch = traces.size
        binding().use { assets ->
            prove(!assets.saveImages(listOf("one.png", "bad.png", "last.gif").map { "https://fixture.invalid/batch-middle-decode-failure/$it" }), "batch aggregates actual middle decode failure as false")
        }
        prove(traces.drop(beforeBatch) == listOf("/batch-middle-decode-failure/one.png", "/batch-middle-decode-failure/bad.png", "/batch-middle-decode-failure/last.gif"), "batch malformed middle input does not suppress final HTTP item")
        prove(output(batch).size == 2 && output(batch).single { it.toString().endsWith(".gif") }.let(Files::readAllBytes).contentEquals(gif), "batch first and final items remain committed")
        prove(scratch(batch).isEmpty() && scratch(defaultDirectory).isEmpty() && scratch(temporary).isEmpty(), "batch both decode failure destinations and successful scratch drain")
        reports += """{"name":"batch-middle-native-decode-failure-continuation","requests":3,"committedItems":2,"passed":true}"""

        val cancelled = custom("cancel-final")
        val pending = StageGate(cancelled, ".bilipai-image-", ::completePng); gate.set(pending)
        val beforeCancel = traces.size; val defaultQueriesBeforeCancel = defaultQueries.get(); val defaultsBeforeCancel = output(defaultDirectory).toSet()
        binding().use { assets ->
            val result = AtomicReference<Boolean?>(null)
            val saving = async(Dispatchers.IO) { assets.saveImages(listOf("alpha.png", "later.gif").map { "https://fixture.invalid/cancel-final/$it" }).also { result.set(it) } }
            prove(withContext(Dispatchers.IO) { pending.entered.await(5, TimeUnit.SECONDS) }, "cancel complete custom encoded stage entered")
            synchronized(actualStoreLock) {
                pending.release.countDown()
                waitFor("cancel actual final Store monitor must block") { storeFinalBlocked() }
                saving.cancel()
            }
            saving.join()
            prove(saving.isCancelled && result.get() == null, "actual cancellation during Store final admission propagates")
        }
        gate.set(null); delay(150)
        prove(traces.drop(beforeCancel) == listOf("/cancel-final/alpha.png"), "cancel final issues no later batch request")
        prove(defaultQueries.get() == defaultQueriesBeforeCancel && output(defaultDirectory).toSet() == defaultsBeforeCancel, "cancel final performs no default fallback")
        prove(output(cancelled).isEmpty() && scratch(cancelled).isEmpty() && scratch(temporary).isEmpty(), "cancel final commits nothing and all owned scratch drains")
        reports += """{"name":"cancel-real-store-final-no-fallback-no-later-items","requests":1,"scratchDrained":true,"passed":true}"""

        finalFailure("motion-custom-final-failure", true)

        val falseDirectory = custom("custom-normal-false")
        val owner = requireNotNull(sessions.dynamicCacheOwner()); val attempts = AtomicInteger()
        prove(locations.save("declared-boolean-contract.png", { currentCoroutineContext().ensureActive() }, { block -> sessions.withCurrentDynamicCacheOwner(owner) { check(lifetime.withCommit(block)) } }) { target ->
            if (attempts.incrementAndGet() == 1) false else { Files.writeString(target.path, "declared true writer result"); true }
        }, "actual public Locations Boolean false custom result falls back to true default result")
        prove(attempts.get() == 2 && output(falseDirectory).isEmpty() && Files.exists(defaultDirectory.resolve("declared-boolean-contract.png")), "actual Locations attempted both distinct destination paths")
        reports += """{"name":"locations-normal-false-custom-falls-back","writerAttempts":2,"passed":true}"""

        prove(anonymous.get() && referer.get(), "all actual loopback receivers saw anonymous original Referer transport")
        prove(scratch(temporary).isEmpty(), "all downloaded owned source inputs drained")
        val sourceReports = productClasses.joinToString(",") { """{"className":"${it.name}","source":"${mainJar.toUri()}"}""" }
        Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$checks,"cases":${reports.size},"caseResults":[${reports.joinToString(",")}],"independentOutputs":[${outputsForIndependent.joinToString(",")}],"actualProductCodeSources":[$sourceReports],"actualLoopbackHTTP":true,"HTTPRequests":${traces.size},"existingMainProductOverrides":[],"sharedGradle":false,"HWND":false,"outsideSocket":false,"realChooserOpened":false,"old135MatrixRerun":false}""")
        println("PASS $checks assertions / ${reports.size} new Main04 integration cases; actual product overrides0")
    } finally {
        gate.get()?.release?.countDown()
        lifetime.close(); server.stop(0); executor.shutdownNow()
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
