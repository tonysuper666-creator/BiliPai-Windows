package com.bilipai.desktop.plugins.js

import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.js.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.writeText

/** Executes the actual Kotlin product adapter and original JS wrappers in independently launched JVMs. */
internal fun runDesktopJsHostFixture(args: Array<String>): Unit = runBlocking {
    val java = Path.of(args[0]); val engine = Path.of(args[1]); val repo = Path.of(args[2])
    val workerClasspath = args.drop(3).map(Path::of)
    val root = Files.createTempDirectory("bilipai-js-kotlin-host-")
    val requests = AtomicInteger(); val cookieReceived = AtomicBoolean()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        requests.incrementAndGet()
        cookieReceived.set(cookieReceived.get() || !exchange.requestHeaders.getFirst("Cookie").isNullOrBlank())
        val body = if (exchange.requestURI.path == "/oversize") "x".repeat(1_048_577).toByteArray()
            else "#EXTM3U\n#EXTINF:-1 tvg-id=\"fixture\" tvg-name=\"Fixture TV\" group-title=\"news\",Fixture TV\nhttps://media.invalid/primary.m3u8\n#EXTINF:-1 tvg-id=\"fixture\" tvg-name=\"Fixture TV\" group-title=\"news\",Fixture TV\nhttps://media.invalid/backup.m3u8\n".toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.responseHeaders.add("Set-Cookie", "untrusted_remote_cookie=must_not_return; Path=/")
        exchange.sendResponseHeaders(200, body.size.toLong())
        runCatching { exchange.responseBody.use { it.write(body) } }
        exchange.close()
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}/"
    val cases = mutableListOf<JsonObject>()
    val hosts = mutableListOf<DesktopJsPluginHost>()
    fun host(name: String, ownerEpoch: (() -> Long)? = null): DesktopJsPluginHost {
        val path = root.resolve(name); Files.createDirectories(path)
        return DesktopJsPluginHost(DesktopJsWorkerProcess(java, workerClasspath, engine, "com.bilipai.desktop.plugins.js.DesktopJsPluginWorker"), path, acceptHttpUrl = { it.startsWith(base) }, ownerEpoch = ownerEpoch)
            .also(hosts::add)
    }
    suspend fun install(host: DesktopJsPluginHost, name: String, script: String,
        grants: Set<PluginCapability>? = null, epoch: Long = 1): DesktopJsAuthorizedPlugin {
        val manifest = host.previewManifest(script)
        val path = root.resolve("$name/bilipai_js_plugins/packages/${manifest.id}/plugin.js")
        Files.createDirectories(path.parent); path.writeText(script)
        val record = InstalledBiliPaiJsPlugin(manifest, scriptPath = path.toAbsolutePath().toString(), installedAtMillis = 1,
            enabled = true, grantedCapabilities = grants ?: manifest.permissions)
        return DesktopJsAuthorizedPlugin(record, DesktopJsPluginHost.scriptSha256(script)).also { host.replaceAuthorizations(listOf(it), epoch) }
    }
    suspend fun case(name: String, block: suspend () -> Unit) {
        val start = System.nanoTime()
        try { block() }
        catch (failure: Throwable) {
            cases += buildJsonObject { put("name", name); put("passed", false); put("category", failure.javaClass.simpleName) }
            throw failure
        }
        cases += buildJsonObject { put("name", name); put("passed", true); put("elapsedMs", (System.nanoTime() - start) / 1_000_000) }
    }
    suspend fun rejects(block: suspend () -> Unit) {
        val result = runCatching { block() }
        check(result.isFailure) { "Expected real adapter failure" }
    }
    fun fixtureScript(id: String, body: String, capabilities: List<String> = listOf("PLUGIN_STORAGE")): String =
        "window.BiliPaiPlugin={id:'$id',title:'Fixture',permissions:${JsonArray(capabilities.map(::JsonPrimitive))}," +
            "modules:[{id:'items',title:'Items',functionName:'load'}],load:()=>{$body}};"
    try {
        case("credentialEpochImmediatelyRejectsOldGuestAndLaunchBeforeFlowDelivery") {
            val epoch = AtomicLong(1)
            val host = host("direct-epoch", epoch::get)
            host.replaceAuthorizations(emptyList(), 1)
            val script = fixtureScript("epoch.fixture", "return [{id:'media',title:'Media',videoUrl:'https://media.invalid/stream.mp4'}];", listOf("EXTERNAL_MEDIA_PLAYBACK"))
            val approved = install(host, "direct-epoch", script)
            val item = host.loadModuleItems("epoch.fixture", "items").single()
            val oldLaunch = host.createExternalLaunch("epoch.fixture", item)
            epoch.incrementAndGet()
            rejects { host.loadModuleItems("epoch.fixture", "items") }
            rejects { host.createExternalLaunch("epoch.fixture", item) }
            host.replaceAuthorizations(listOf(approved), 2)
            check(ExternalMediaLaunchStore.get(oldLaunch) == null)
            check(host.loadModuleItems("epoch.fixture", "items").single().id == "media")
        }
        val tvScript = repo.resolve("examples/plugins/tv-live.bilipai.js").toFile().readText()
        val huyaScript = repo.resolve("examples/plugins/huya-live.bilipai.js").toFile().readText()
        case("originalTVAndHuyaPreviewWithoutGrantsOrNetwork") {
            val host = host("preview")
            val before = requests.get()
            check(host.previewManifest(tvScript).id == "tv.live")
            check(host.previewManifest(huyaScript).id == "live.huya")
            check(requests.get() == before)
        }
        case("originalTVActualHTTPPrimaryBackupAndOriginalStorage") {
            val host = host("tv")
            install(host, "tv", tvScript)
            val params = buildJsonObject { put("dataSource", base + "live.m3u"); put("category", "all"); put("logoBaseUrl", ""); put("iconLibraryUrl", "") }.toString()
            val items = host.loadModuleItems("tv.live", "channels", params)
            check(items.size == 1 && items.single().videoUrl == "https://media.invalid/primary.m3u8")
            check(items.single().streams.single().url == "https://media.invalid/backup.m3u8")
            check(root.resolve("tv/bilipai_js_plugin_storage/tv.live/lastSourceUrl").toFile().readText() == base + "live.m3u")
            val launch = host.createExternalLaunch("tv.live", items.single(), 1)
            val request = ExternalMediaLaunchStore.get(launch)!!
            check(request.streams.size == 2 && request.selectedStreamIndex == 1)
            host.releaseExternalLaunch(launch); check(ExternalMediaLaunchStore.get(launch) == null)
            host.loadModuleItems("tv.live", "channels", params)
            check(!cookieReceived.get()) // Remote Set-Cookie never becomes a request cookie.
        }
        case("missingNetworkGrantRejectsBeforeActualHTTP") {
            val script = fixtureScript("no.network", "BiliPai.http.get('${base}denied'); return [];", emptyList())
            val host = host("network-denied"); install(host, "network-denied", script)
            val before = requests.get()
            rejects { host.loadModuleItems("no.network", "items") }
            check(requests.get() == before)
        }
        case("missingStorageGrantRejectsBeforeFileMutation") {
            val script = fixtureScript("no.storage", "BiliPai.storage.set('denied','secret'); return [];", emptyList())
            val host = host("storage-denied"); install(host, "storage-denied", script)
            rejects { host.loadModuleItems("no.storage", "items") }
            check(!Files.exists(root.resolve("storage-denied/bilipai_js_plugin_storage")))
        }
        case("approvedHashTamperRejectsBeforeVMRun") {
            val script = fixtureScript("hash.guard", "return [];", emptyList())
            val host = host("hash"); val record = install(host, "hash", script)
            Path.of(record.installed.scriptPath).writeText(script + "\nthrow Error('changed');")
            rejects { host.loadModuleItems("hash.guard", "items") }
        }
        case("originalStorageRecoversInNewKotlinHostAndNewVM") {
            val script = fixtureScript("store.recover", "const old=BiliPai.storage.get('counter')||'0'; BiliPai.storage.set('counter',String(Number(old)+1)); return [{id:old,title:'Item'}];")
            val first = host("recover"); val record = install(first, "recover", script)
            check(first.loadModuleItems("store.recover", "items").single().id == "0")
            first.shutdownForRestore()
            val second = host("recover"); second.replaceAuthorizations(listOf(record), 2)
            check(second.loadModuleItems("store.recover", "items").single().id == "1")
        }
        case("accountEpochChangeCancelsEnteredPromiseAndDropsOldResults") {
            val script = fixtureScript("account.promise", "BiliPai.storage.set('entered','yes'); return new Promise(()=>{});")
            val host = host("account"); val record = install(host, "account", script)
            val running = async { host.loadModuleItems("account.promise", "items") }
            withTimeout(10_000) { while (!Files.exists(root.resolve("account/bilipai_js_plugin_storage/account.promise/entered"))) delay(20) }
            host.replaceAuthorizations(listOf(record), 2)
            rejects { running.await() }
        }
        case("explicitCoroutineCancelKillsGuestBeforeReturning") {
            val script = fixtureScript("cancel.promise", "BiliPai.storage.set('entered','yes'); return new Promise(()=>{});")
            val host = host("cancel"); install(host, "cancel", script)
            val running = async { host.loadModuleItems("cancel.promise", "items") }
            withTimeout(10_000) { while (!Files.exists(root.resolve("cancel/bilipai_js_plugin_storage/cancel.promise/entered"))) delay(20) }
            withTimeout(5_000) { running.cancelAndJoin() }
        }
        case("windowsReservedStorageNameCannotCreateDeviceFile") {
            val script = fixtureScript("reserved.key", "BiliPai.storage.set('CON','no'); return [];")
            val host = host("reserved"); install(host, "reserved", script)
            rejects { host.loadModuleItems("reserved.key", "items") }
            check(!Files.exists(root.resolve("reserved/bilipai_js_plugin_storage")))
        }
        case("boundedActualHTTPRejectsOversizeResponse") {
            val script = fixtureScript("large.http", "BiliPai.http.get('${base}oversize'); return [];", listOf("NETWORK"))
            val host = host("http-limit"); install(host, "http-limit", script)
            val before = requests.get(); rejects { host.loadModuleItems("large.http", "items") }
            check(requests.get() == before + 1)
        }
        case("originalExternalMediaHeadersAndContentTypePreservedAndReleased") {
            val script = fixtureScript("external.source", "return [{id:'item',title:'Item',streams:[{id:'a',title:'A',url:'https://media.invalid/a',contentType:'video/mp2t',headers:{'X-Custom':'a,b'}}]}];", listOf("EXTERNAL_MEDIA_PLAYBACK"))
            val host = host("external"); install(host, "external", script)
            val item = host.loadModuleItems("external.source", "items").single()
            val launch = host.createExternalLaunch("external.source", item)
            val stream = ExternalMediaLaunchStore.get(launch)!!.streams.single()
            check(stream.headers == mapOf("X-Custom" to "a,b") && stream.contentType == "video/mp2t")
            host.shutdownForRestore(); check(ExternalMediaLaunchStore.get(launch) == null)
            rejects { host.loadModuleItems("external.source", "items") }
        }
        case("guestDeepPayloadRejectedBeforeRecursiveModelDecode") {
            val script = fixtureScript("deep.payload", "let x={id:'leaf',title:'Leaf'}; for(let i=0;i<40;i++) x={id:String(i),title:'Branch',childItems:[x]}; return [x];", emptyList())
            val host = host("deep"); install(host, "deep", script)
            rejects { host.loadModuleItems("deep.payload", "items") }
        }
        case("guestOversizeMediaCollectionRejectedAfterActualExecution") {
            val script = fixtureScript("many.items", "return Array.from({length:4097},(_,i)=>({id:String(i),title:'Item'}));", emptyList())
            val host = host("many"); install(host, "many", script)
            rejects { host.loadModuleItems("many.items", "items") }
        }
    } finally {
        hosts.forEach { runCatching { it.shutdownForRestore() } }
        server.stop(0)
        val report = buildJsonObject { put("passed", cases.size == 14 && cases.all { it["passed"]!!.jsonPrimitive.boolean }); put("caseCount", cases.size); put("cases", JsonArray(cases)) }
        engine.resolve("kotlin-host-report.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        check(root.toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    println("Actual Kotlin JS host PASS: ${cases.size}")
}
