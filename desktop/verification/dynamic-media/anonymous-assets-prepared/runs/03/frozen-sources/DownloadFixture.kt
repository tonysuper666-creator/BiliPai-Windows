package com.bilipai.desktop.ui.downloadproof

import com.bilipai.desktop.ui.*
import com.bilipai.desktop.data.*
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.*
import okio.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.*

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]); Files.createDirectories(root)
    val image = Files.readAllBytes(Path.of(args[1])); val video = Files.readAllBytes(Path.of(args[2]))
    val actualMainJar = Path.of(args[3]).toRealPath()
    var checks = 0; val cases = mutableListOf<String>()
    fun prove(value: Boolean, reason: String) { check(value) { reason }; checks++ }
    fun waitFor(reason: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate()) { check(System.nanoTime() < until) { reason }; Thread.sleep(10) }
    }
    fun noTemps() = Files.list(root).use { files -> files.noneMatch { it.fileName.toString().startsWith(".bilipai-") } }
    val count = AtomicInteger(); val observedAnonymous = AtomicBoolean(true); val observedReferer = AtomicBoolean(true)
    val slowEntered = CountDownLatch(1); val releaseSlow = CountDownLatch(1)
    val executor = Executors.newCachedThreadPool { task -> Thread(task,"fixture-loopback").apply { isDaemon = true } }
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0)
    server.executor = executor
    server.createContext("/") { exchange ->
        count.incrementAndGet()
        observedAnonymous.compareAndSet(true, exchange.requestHeaders.getFirst("Cookie") == null && exchange.requestHeaders.getFirst(FORCE_COOKIE_HEADER) == null)
        observedReferer.compareAndSet(true, exchange.requestHeaders.getFirst("Referer") == "https://www.bilibili.com/")
        val path = exchange.requestURI.path
        val bytes = if (path.endsWith(".mp4")) video else image
        try {
            if (path == "/fail.png") {
                exchange.sendResponseHeaders(503,3); exchange.responseBody.use { it.write(byteArrayOf(1,2,3)) }
            } else {
                exchange.responseHeaders.add("Content-Type",if(path.endsWith(".mp4")) "video/mp4" else "image/png")
                exchange.sendResponseHeaders(200,bytes.size.toLong())
                exchange.responseBody.use { output ->
                    if (path == "/slow.mp4") {
                        output.write(bytes,0,64*1024); output.flush(); slowEntered.countDown()
                        releaseSlow.await(8,TimeUnit.SECONDS)
                        output.write(bytes,64*1024,bytes.size-64*1024)
                    } else output.write(bytes)
                }
            }
        } catch (_: java.io.IOException) { /* expected when the client cancels */ }
        finally { exchange.close() }
    }
    server.start()
    val eofGate = AtomicReference<Pair<CountDownLatch,CountDownLatch>?>()
    val sessions = DesktopSessionStore.temporary()
    val fixtureAccount = AccountSummary(120L,"Declared fixture account","")
    sessions.saveAccount(mapOf("SESSDATA" to "declared-task-cookie-01"),fixtureAccount)
    val repository = DesktopRepository(sessions)
    for (clazz in listOf(DesktopSessionStore::class.java,DesktopRepository::class.java,DesktopDynamicCardOperations::class.java,DesktopDynamicSaveTarget::class.java)) {
        prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == actualMainJar, "actual Main codeSource for ${clazz.name}")
    }
    prove(repository.dynamicCacheSessionGuard === sessions, "actual repository guard is the same actual SessionStore")
    val fixtureClient = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
        .dns { name -> check(name == "127.0.0.1") { "Outside DNS forbidden: $name" }; listOf(InetAddress.getByName("127.0.0.1")) }
        .addInterceptor { chain ->
            val original = chain.request()
            check(original.url.host == "fixture.invalid") { "Outside socket request forbidden" }
            // Fixture-only rewrite AFTER the actual repository's app interceptor.
            // Real OkHttp bridge/network/callback and local HTTP bytes still run.
            val rewritten = original.newBuilder().url("http://127.0.0.1:${server.address.port}${original.url.encodedPath}")
                .header("Cookie","declared-fixture-header").header(FORCE_COOKIE_HEADER,"declared-forced-header").build()
            val response = chain.proceed(rewritten)
            val body = response.body
            val gate = eofGate.get()?.takeIf { original.url.encodedPath == "/video.mp4" }
            if (body == null || gate == null) response else {
                val fired = AtomicBoolean(false)
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val result = super.read(sink,byteCount)
                        if (result == -1L && fired.compareAndSet(false,true)) {
                            gate.first.countDown(); check(gate.second.await(8,TimeUnit.SECONDS)) { "EOF release timeout" }
                        }
                        return result
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }
        }.build()
    var target: DesktopDynamicSaveTarget? = DesktopDynamicSaveTarget(root.resolve("motion-photo.jpg"),false)
    val pickerCount = AtomicInteger()
    fun binding(): DesktopDynamicImageAssets {
        val operations = DesktopDynamicCardOperations(repository)
        val owner = requireNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner())
        check(owner.epoch == operations.expectedEpoch)
        return DesktopDynamicImageAssets(fixtureClient,operations::isOwned,repository.dynamicCacheSessionGuard,owner,
            selectTarget = { _, _ -> pickerCount.incrementAndGet(); target },selectDirectory = { root })
    }
    try {
        binding().use { assets ->
            prove(assets.saveMotionPhoto("//fixture.invalid/image.png@640w", "http://fixture.invalid/video.mp4"), "full actual anonymous HTTP -> Files/EXIF/packing output")
            prove(Files.readAllBytes(root.resolve("motion-photo.jpg")).takeLast(video.size).toByteArray().contentEquals(video), "actual HTTP MP4 bytes preserved")
            prove(count.get() == 2 && pickerCount.get() == 1, "two GETs and exactly one existing selector per motion save")
            prove(noTemps(), "successful motion download and compose temps drained")
        }
        cases += "real loopback HTTP bytes through actual Repo/Ops owner to real EXIF/original packing"

        binding().use { assets ->
            target = DesktopDynamicSaveTarget(root.resolve("plain.png"),false)
            prove(assets.saveImage("https://fixture.invalid/image.png"), "ordinary image save uses the same streamed write")
            prove(Files.readAllBytes(root.resolve("plain.png")).contentEquals(image), "plain image bytes unchanged")
            target = DesktopDynamicSaveTarget(root.resolve("plain.mp4"),false)
            prove(assets.saveLivePhotoVideo("//fixture.invalid/video.mp4"), "original video normalization then same streamed write")
            prove(Files.readAllBytes(root.resolve("plain.mp4")).contentEquals(video), "standalone video bytes unchanged")
            prove(noTemps(), "ordinary saves drain temps")
        }
        cases += "single image and live-video same owner/stream/save pipeline"

        binding().use { assets ->
            val before = count.get(); target = null
            prove(!assets.saveMotionPhoto("https://fixture.invalid/image.png","https://fixture.invalid/video.mp4"), "cancelled target returns false")
            prove(count.get() == before && noTemps(), "cancelled chooser performs no HTTP/temp work")
            target = DesktopDynamicSaveTarget(root.resolve("plain.png"),false)
            prove(runCatching { assets.saveImage("https://fixture.invalid/image.png") }.isFailure, "no-overwrite target admission")
            prove(count.get() == before && Files.readAllBytes(root.resolve("plain.png")).contentEquals(image), "existing target rejected before download")
            target = DesktopDynamicSaveTarget(root.resolve("plain.png"),true)
            prove(assets.saveImage("https://fixture.invalid/image.png"), "explicit replace remains accepted")
        }
        cases += "single picker cancellation and no-overwrite/replace admission"

        binding().use { assets ->
            target = DesktopDynamicSaveTarget(root.resolve("failed.png"),false)
            val before = count.get()
            prove(runCatching { assets.saveImage("https://fixture.invalid/fail.png") }.isFailure, "real HTTP503 surfaces failure")
            prove(count.get() == before+1 && !Files.exists(target!!.path) && noTemps(), "no automatic network retry and failed HTTP scratch drained")
            prove(assets.saveImage("https://fixture.invalid/image.png"), "explicit retry reuses same live save owner")
            prove(Files.readAllBytes(target!!.path).contentEquals(image) && noTemps(), "explicit retry commits complete bytes")
        }
        cases += "real transport failure and explicit retry"

        target = DesktopDynamicSaveTarget(root.resolve("cancelled.mp4"),false)
        binding().use { assets ->
            val saving = async(Dispatchers.IO) { assets.saveLivePhotoVideo("https://fixture.invalid/slow.mp4") }
            prove(withContext(Dispatchers.IO) { slowEntered.await(5,TimeUnit.SECONDS) }, "actual streaming HTTP body entered")
            waitFor("download must stream a real prefix before cancellation") {
                Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".bilipai-download-") && runCatching { Files.size(it)>=64*1024 }.getOrDefault(false) } }
            }
            saving.cancel(); releaseSlow.countDown(); saving.join()
            prove(saving.isCancelled && !Files.exists(target!!.path), "cancelled parent commits no streamed output")
            prove(noTemps(), "cancelled callback drains before scratch deletion/return")
        }
        cases += "mid-stream real Call cancellation and callback/file drain"

        target = DesktopDynamicSaveTarget(root.resolve("old-epoch.jpg"),false)
        val eofEntered = CountDownLatch(1); val releaseEof = CountDownLatch(1)
        eofGate.set(eofEntered to releaseEof)
        binding().use { assets ->
            val saving = async(Dispatchers.IO) { runCatching { assets.saveMotionPhoto("https://fixture.invalid/image.png","https://fixture.invalid/video.mp4") } }
            prove(withContext(Dispatchers.IO) { eofEntered.await(5,TimeUnit.SECONDS) }, "real HTTP MP4 reaches fixture-only EOF scheduling gate")
            val field = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }
            val actualLock = field.get(sessions)
            synchronized(actualLock) {
                releaseEof.countDown()
                waitFor("save must reach actual SessionStore atomic gate with complete output before rotation") {
                    Thread.getAllStackTraces().any { (thread, stack) -> thread.state == Thread.State.BLOCKED && stack.any {
                        it.className == DesktopSessionStore::class.java.name && it.methodName == "withCurrentDynamicCacheOwner"
                    } }
                }
                // Actual Main mutation under the same real monitor; same MID,
                // declared task credentials changed, no Ops/session override.
                sessions.saveAccount(mapOf("SESSDATA" to "declared-task-cookie-02"),fixtureAccount)
            }
            val result = saving.await()
            prove(result.exceptionOrNull() is kotlinx.coroutines.CancellationException, "late same MID actual session epoch blocks admitted old commit")
            prove(!Files.exists(target!!.path) && noTemps(), "old full downloaded/rendered output and scratch are drained")
        }
        eofGate.set(null)
        cases += "actual Store monitor serializes epoch replacement against final complete-file commit"

        val closed = binding(); closed.close(); target = DesktopDynamicSaveTarget(root.resolve("closed.png"),false)
        val beforeClosed = count.get()
        prove(runCatching { closed.saveImage("https://fixture.invalid/image.png") }.exceptionOrNull() is kotlinx.coroutines.CancellationException, "closed save owner rejects before selector/HTTP")
        prove(count.get() == beforeClosed && noTemps(), "closed owner does not create request/temp")
        cases += "own close admission rejects new work"
        prove(observedAnonymous.get(), "actual loopback receiver saw no Cookie or forced-cookie header")
        prove(observedReferer.get(), "actual HTTP receiver saw original Referer")
        prove(noTemps(), "all operation download/compose temps drained")
        Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$checks,"cases":${cases.size},"actualLoopbackHTTP":true,"outsideSocket":false,"actualMainRepoOpsSessionStore":true,"OpsOrSessionOverride":false,"candidateAssetsOverrideDeclared":true,"actualAtomicAccountEpochGate":true,"externalPageBoolCloseStrongGuarantee":false,"actualReceiverCookiesAbsent":true,"HTTPRequests":${count.get()},"realChooserOpened":false,"MainInstalledIntegration":false,"PhotosRecognition":false,"HWND":false,"systemSHARE":false}""")
        println("PASS $checks assertions / ${cases.size} cases: actual Main owner + loopback HTTP -> actual EXIF/packing; declared Assets candidate override only")
    } finally {
        releaseSlow.countDown(); eofGate.get()?.second?.countDown()
        server.stop(0); executor.shutdownNow()
        fixtureClient.dispatcher.executorService.shutdown()
        fixtureClient.connectionPool.evictAll()
    }
}
