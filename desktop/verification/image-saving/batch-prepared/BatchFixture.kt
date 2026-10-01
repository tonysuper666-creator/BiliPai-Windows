package com.bilipai.desktop.ui.batchproof

import com.bilipai.desktop.ui.*
import com.bilipai.desktop.data.*
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

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]).toRealPath()
    val actualMainJar = Path.of(args[1]).toRealPath()
    val candidateJar = Path.of(args[2]).toRealPath()
    var checks = 0
    fun prove(value: Boolean, reason: String) { check(value) { reason }; checks++ }
    val traces = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    val payload = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
    val slowEntered = ConcurrentHashMap<String, CountDownLatch>()
    val slowRelease = ConcurrentHashMap<String, CountDownLatch>()
    for (name in listOf("cancel-first", "cancel-middle")) {
        slowEntered[name] = CountDownLatch(1); slowRelease[name] = CountDownLatch(1)
    }
    val anonymous = AtomicBoolean(true)
    val referer = AtomicBoolean(true)
    val executor = Executors.newCachedThreadPool { task -> Thread(task, "batch-loopback-fixture").apply { isDaemon = true } }
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.executor = executor
    server.createContext("/") { exchange ->
        val parts = exchange.requestURI.path.trim('/').split('/')
        val scenario = parts.first(); val item = parts.last()
        traces.computeIfAbsent(scenario) { CopyOnWriteArrayList() }.add(item)
        anonymous.compareAndSet(true, exchange.requestHeaders.getFirst("Cookie") == null && exchange.requestHeaders.getFirst(FORCE_COOKIE_HEADER) == null)
        referer.compareAndSet(true, exchange.requestHeaders.getFirst("Referer") == "https://www.bilibili.com/")
        try {
            if (item == "fail.png") {
                exchange.sendResponseHeaders(503, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            } else {
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { output ->
                    if (item == "slow.png") {
                        output.write(payload, 0, 64 * 1024); output.flush()
                        slowEntered.getValue(scenario).countDown()
                        check(slowRelease.getValue(scenario).await(8, TimeUnit.SECONDS)) { "fixture slow-body release timeout" }
                        output.write(payload, 64 * 1024, payload.size - 64 * 1024)
                    } else output.write(payload)
                }
            }
        } catch (_: java.io.IOException) { /* Expected actual transport cancellation. */ }
        finally { exchange.close() }
    }
    server.start()
    val sessions = DesktopSessionStore.temporary()
    sessions.saveAccount(mapOf("SESSDATA" to "declared-task-batch-cookie"), AccountSummary(120L, "Declared batch fixture account", ""))
    val repository = DesktopRepository(sessions)
    val actualClasses = listOf(DesktopSessionStore::class.java, DesktopRepository::class.java, DesktopDynamicCardOperations::class.java,
        DesktopDynamicSaveTarget::class.java, DesktopDynamicMotionPhotoFiles::class.java, DesktopDynamicMotionPhotoExif::class.java)
    for (clazz in actualClasses) prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == actualMainJar, "actual Main03 codeSource: ${clazz.name}")
    prove(Path.of(DesktopDynamicImageAssets::class.java.protectionDomain.codeSource.location.toURI()).toRealPath() == candidateJar, "declared candidate Assets codeSource")
    prove(repository.dynamicCacheSessionGuard === sessions, "actual repository reuses actual SessionStore authority")
    val fixtureClient = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
        .dns { name -> check(name == "127.0.0.1") { "Outside DNS forbidden: $name" }; listOf(InetAddress.getByName("127.0.0.1")) }
        .addInterceptor { chain ->
            val original = chain.request()
            check(original.url.host == "fixture.invalid") { "Outside request forbidden" }
            val rewritten = original.newBuilder().url("http://127.0.0.1:${server.address.port}${original.url.encodedPath}")
                .header("Cookie", "declared-fixture-header").header(FORCE_COOKIE_HEADER, "declared-forced-header").build()
            chain.proceed(rewritten)
        }.build()
    val directoryPicks = AtomicInteger(); val targetPicks = AtomicInteger()
    fun binding(directory: Path): DesktopDynamicImageAssets {
        val operations = DesktopDynamicCardOperations(repository)
        val owner = requireNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner())
        check(owner.epoch == operations.expectedEpoch)
        return DesktopDynamicImageAssets(fixtureClient, operations::isOwned, repository.dynamicCacheSessionGuard, owner,
            selectTarget = { _, _ -> targetPicks.incrementAndGet(); error("single target picker must not run in batch") },
            selectDirectory = { directoryPicks.incrementAndGet(); directory })
    }
    fun files(directory: Path) = Files.list(directory).use { paths -> paths.toList() }
    fun saved(directory: Path) = files(directory).filter { it.fileName.toString().startsWith("BiliPai-") }
    fun noScratch(directory: Path) = files(directory).none { it.fileName.toString().startsWith(".bilipai-") }
    val reports = mutableListOf<String>()
    suspend fun mixed(name: String, items: List<String>, expectedSuffixes: List<Int>) {
        val directory = Files.createDirectory(root.resolve(name))
        val beforePicks = directoryPicks.get()
        binding(directory).use { assets ->
            prove(!assets.saveImages(items.map { "https://fixture.invalid/$name/$it" }), "$name aggregate false")
        }
        prove(traces[name]?.toList() == items, "$name attempts all three in original order despite ordinary HTTP failure")
        val outputs = saved(directory)
        prove(outputs.size == 2 && expectedSuffixes.all { number -> outputs.any { it.fileName.toString().endsWith("-$number.png") } }, "$name later successful items committed")
        prove(outputs.all { Files.readAllBytes(it).contentEquals(payload) }, "$name actual loopback output bytes exact")
        prove(directoryPicks.get() == beforePicks + 1 && targetPicks.get() == 0, "$name one existing directory selection")
        prove(noScratch(directory), "$name all failed/successful item scratch drained")
        reports += """{"name":"$name","result":false,"requests":${traces[name]?.size},"committedItems":${expectedSuffixes.joinToString(prefix="[",postfix="]")},"scratchDrained":true}"""
    }
    suspend fun cancelled(name: String, items: List<String>, expectedTrace: List<String>, expectedCommitted: Int) {
        val directory = Files.createDirectory(root.resolve(name))
        val beforePicks = directoryPicks.get()
        binding(directory).use { assets ->
            val returnedBoolean = AtomicReference<Boolean?>(null)
            val saving = async(Dispatchers.IO) {
                assets.saveImages(items.map { "https://fixture.invalid/$name/$it" }).also { returnedBoolean.set(it) }
            }
            prove(withContext(Dispatchers.IO) { slowEntered.getValue(name).await(5, TimeUnit.SECONDS) }, "$name real HTTP slow body entered")
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (files(directory).none { it.fileName.toString().startsWith(".bilipai-download-") && Files.size(it) >= 64 * 1024 }) {
                check(System.nanoTime() < until) { "$name prefix not streamed to task stage" }; delay(10)
            }
            saving.cancel()
            slowRelease.getValue(name).countDown()
            saving.join()
            prove(saving.isCancelled && returnedBoolean.get() == null, "$name cancellation propagates without a Boolean failure result")
            delay(150) // Bounded quiescence observation after operation/callback drain.
            prove(traces[name]?.toList() == expectedTrace, "$name cancellation issues no later HTTP items")
            prove(saved(directory).size == expectedCommitted, "$name only already committed earlier item remains")
            prove(saved(directory).all { Files.readAllBytes(it).contentEquals(payload) }, "$name earlier output retains exact downloaded bytes")
            prove(noScratch(directory), "$name real cancelled callback drains before all scratch deletion")
        }
        prove(directoryPicks.get() == beforePicks + 1 && targetPicks.get() == 0, "$name one existing directory selection")
        reports += """{"name":"$name","cancellationPropagated":true,"requests":${traces[name]?.size},"committedItems":$expectedCommitted,"noLaterRequests":true,"scratchDrained":true}"""
    }
    try {
        mixed("first-failure", listOf("fail.png", "two.png", "three.png"), listOf(2, 3))
        mixed("middle-failure", listOf("one.png", "fail.png", "three.png"), listOf(1, 3))
        cancelled("cancel-first", listOf("slow.png", "two.png", "three.png"), listOf("slow.png"), 0)
        cancelled("cancel-middle", listOf("one.png", "slow.png", "three.png"), listOf("one.png", "slow.png"), 1)
        prove(anonymous.get(), "actual loopback receiver saw no account/forced Cookie")
        prove(referer.get(), "actual loopback receiver saw original Bilibili Referer")
        val classReports = actualClasses.joinToString(",") { clazz -> """{"className":"${clazz.name}","source":"${actualMainJar.toUri()}"}""" }
        Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$checks,"cases":4,"caseResults":[${reports.joinToString(",")}],"actualClassSources":[$classReports],"actualMain03StoreRepoOpsFiles":true,"candidateAssetsOverrideDeclared":true,"SaveTargetOverride":false,"actualLoopbackHTTP":true,"HTTPRequests":${traces.values.sumOf { it.size }},"actualReceiverCookiesAbsent":true,"outsideSocket":false,"realChooserOpened":false,"sharedGradle":false,"HWND":false,"MainInstalledIntegration":false}""")
        println("PASS $checks assertions / 4 batch contracts: mixed HTTP failures continue, first/middle cancellation stops later items")
    } finally {
        slowRelease.values.forEach { it.countDown() }
        server.stop(0); executor.shutdownNow()
        fixtureClient.dispatcher.executorService.shutdown()
        fixtureClient.connectionPool.evictAll()
    }
}
