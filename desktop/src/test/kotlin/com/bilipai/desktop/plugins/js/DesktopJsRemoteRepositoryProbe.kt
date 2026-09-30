package com.bilipai.desktop.plugins.js

import com.android.purebilibili.core.plugin.PluginCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun runDesktopJsRemoteRepositoryFixture(args: Array<String>): Unit = runBlocking {
    val resources = Path.of(args[0])
    val root = Files.createTempDirectory("bilipai-js-remote-")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool()
    server.executor = executor
    val base = "http://127.0.0.1:${server.address.port}"
    val script = AtomicReference("window.BiliPaiPlugin={id:'remote.fixture',title:'Fixture v1',permissions:['NETWORK'],modules:[{id:'items',title:'Items',functionName:'load'}],load:()=>[{id:'first',title:'First',coverUrl:'$base/image'}]};")
    val downloads = AtomicInteger()
    val imageRequests = AtomicInteger()
    val imageEntered = CountDownLatch(1)
    val imageRelease = CountDownLatch(1)
    val epochImageEntered = CountDownLatch(1)
    val epochImageRelease = CountDownLatch(1)
    val accountEpoch = AtomicLong()
    server.createContext("/plugin.js") { exchange ->
        downloads.incrementAndGet()
        val bytes = script.get().toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/image") { exchange ->
        imageRequests.incrementAndGet(); imageEntered.countDown()
        imageRelease.await(5, TimeUnit.SECONDS)
        runCatching { exchange.sendResponseHeaders(200, 4); exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) } }
    }
    server.createContext("/epoch-image") { exchange ->
        imageRequests.incrementAndGet(); epochImageEntered.countDown()
        epochImageRelease.await(5, TimeUnit.SECONDS)
        runCatching { exchange.sendResponseHeaders(200, 4); exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) } }
    }
    server.start()
    val process = DesktopJsWorkerResources(resources).createProcess(root)
    val host = DesktopJsPluginHost(process, root, acceptHttpUrl = { false }, ownerEpoch = accountEpoch::get)
    val repository = DesktopJsPluginRepository(DesktopPluginContext(DesktopPluginStore(root)), host,
        DesktopJsPublicHttp { it.startsWith("$base/") })
    var passed = 0
    suspend fun case(name: String, block: suspend () -> Unit) { block(); passed++; println("PASS $name") }
    suspend fun rejects(block: suspend () -> Unit) { check(runCatching { block() }.isFailure) }
    try {
        val preview = repository.previewRemote("  $base/plugin.js  ")
        case("original remote source URL and exact preview bytes persist disabled without refetch") {
            check(preview.sourceUrl == "$base/plugin.js" && preview.manifest.title == "Fixture v1")
            script.set(script.get().replace("Fixture v1", "Changed after preview").replace("id:'first'", "id:'changed'"))
            val installed = repository.install(preview, emptySet())
            check(!installed.enabled && installed.sourceUrl == "$base/plugin.js")
            check(downloads.get() == 1)
            check(Files.readString(Path.of(installed.scriptPath)) == preview.script)
            repository.setEnabled("remote.fixture", true)
            check(host.loadModuleItems("remote.fixture", "items").single().id == "first")
        }
        case("host image requests require matching current granted NETWORK") {
            rejects { repository.mediaImage("remote.fixture", "$base/image") }
            check(imageRequests.get() == 0)
        }
        case("disabled or replaced image owner cannot deliver old request after bytes arrive") {
            repository.install(preview, setOf(PluginCapability.NETWORK))
            repository.setEnabled("remote.fixture", true)
            val result = async { runCatching { repository.mediaImage("remote.fixture", "$base/image") } }
            withContext(Dispatchers.IO) { check(imageEntered.await(3, TimeUnit.SECONDS)) }
            repository.setEnabled("remote.fixture", false)
            imageRelease.countDown()
            check(result.await().isFailure)
        }
        case("direct account owner rejects images before delayed authorization refresh and recovers afterward") {
            repository.setEnabled("remote.fixture", true)
            val revision = host.executionRevision.value
            val result = async { runCatching { repository.mediaImage("remote.fixture", "$base/epoch-image") } }
            withContext(Dispatchers.IO) { check(epochImageEntered.await(3, TimeUnit.SECONDS)) }
            accountEpoch.incrementAndGet()
            epochImageRelease.countDown()
            check(result.await().isFailure && host.executionRevision.value == revision)
            val count = imageRequests.get()
            rejects { repository.mediaImage("remote.fixture", "$base/image") }
            check(imageRequests.get() == count)
            repository.accountChanged(accountEpoch.get())
            check(repository.mediaImage("remote.fixture", "$base/image").contentEquals(byteArrayOf(1, 2, 3, 4)))
        }
        case("edited script invalidates host image authorization before requesting") {
            repository.setEnabled("remote.fixture", true)
            val installed = repository.state.value.plugins.single().installed
            Files.writeString(Path.of(installed.scriptPath), "// changed after approval")
            val count = imageRequests.get()
            rejects { repository.mediaImage("remote.fixture", "$base/image") }
            check(imageRequests.get() == count)
        }
        case("restore owns remote service and rejects any later remote preview") {
            repository.shutdownForRestore()
            rejects { repository.previewRemote("$base/plugin.js") }
        }
    } finally {
        imageRelease.countDown(); epochImageRelease.countDown(); repository.shutdownForRestore(); process.close()
        server.stop(0); executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS))
        root.toFile().deleteRecursively()
    }
    println("Actual repository/trimmed worker remote fixtures: $passed PASS")
}
