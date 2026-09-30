package com.bilipai.desktop.plugins.js

import com.android.purebilibili.core.plugin.PluginCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** Original install store + actual Windows repository, temporary filesystem only. */
internal fun runDesktopJsRepositoryFixture(args: Array<String>): Unit = runBlocking {
    val java = Path.of(args[0]); val engine = Path.of(args[1]); val classpath = args.drop(2).map(Path::of)
    val root = Files.createTempDirectory("bilipai-js-repository-")
    val repositories = mutableListOf<DesktopJsPluginRepository>()
    val cases = mutableListOf<JsonObject>()
    fun repository(name: String): DesktopJsPluginRepository {
        val directory = root.resolve(name); Files.createDirectories(directory)
        val host = DesktopJsPluginHost(DesktopJsWorkerProcess(java, classpath, engine, "com.bilipai.desktop.plugins.js.DesktopJsPluginWorker"), directory, acceptHttpUrl = { false })
        return DesktopJsPluginRepository(DesktopPluginContext(DesktopPluginStore(directory)), host).also(repositories::add)
    }
    fun script(id: String, body: String = "return [{id:'item',title:'Item'}];", permissions: String = "['PLUGIN_STORAGE']") =
        "window.BiliPaiPlugin={id:'$id',title:'Fixture',permissions:$permissions,modules:[{id:'items',title:'Items',functionName:'load'}],load:()=>{$body}};"
    suspend fun case(name: String, block: suspend () -> Unit) {
        val start = System.nanoTime()
        try { block() }
        catch (failure: Throwable) {
            cases += buildJsonObject { put("name", name); put("passed", false); put("category", failure.javaClass.simpleName) }
            throw failure
        }
        cases += buildJsonObject { put("name", name); put("passed", true); put("elapsedMs", (System.nanoTime() - start) / 1_000_000) }
    }
    suspend fun rejects(block: suspend () -> Unit) { check(runCatching { block() }.isFailure) { "Expected actual repository rejection" } }
    try {
        case("originalInstallDisabledThenExplicitEnableAndDisable") {
            val repo = repository("install")
            val preview = repo.previewScript(script("installed.plugin"))
            val installed = repo.install(preview, setOf(PluginCapability.PLUGIN_STORAGE))
            check(!installed.enabled && repo.state.value.plugins.single().authorizationMatches)
            rejects { repo.host.loadModuleItems("installed.plugin", "items") }
            repo.setEnabled("installed.plugin", true)
            check(repo.host.loadModuleItems("installed.plugin", "items").single().id == "item")
            repo.setEnabled("installed.plugin", false)
            rejects { repo.host.loadModuleItems("installed.plugin", "items") }
        }
        case("exactApprovalAndOriginalEnabledMetadataRecoverInNewRepository") {
            val first = repository("recover")
            val preview = first.previewScript(script("reopen.plugin"))
            first.install(preview, emptySet()); first.setEnabled("reopen.plugin", true); first.shutdownForRestore()
            val second = repository("recover"); second.load()
            check(second.state.value.plugins.single().installed.enabled && second.state.value.plugins.single().authorizationMatches)
            check(second.host.loadModuleItems("reopen.plugin", "items").single().id == "item")
        }
        case("scriptTamperInvalidatesApprovalAndCannotReenable") {
            val repo = repository("tamper")
            val installed = repo.install(repo.previewScript(script("tamper.plugin")), emptySet())
            Path.of(installed.scriptPath).writeText(script("tamper.plugin", "return [{id:'changed',title:'Changed'}];"))
            repo.load()
            check(!repo.state.value.plugins.single().authorizationMatches)
            rejects { repo.setEnabled("tamper.plugin", true) }
        }
        case("manifestMetadataTamperCannotReuseUnchangedScriptApproval") {
            val repo = repository("manifest")
            repo.install(repo.previewScript(script("manifest.plugin")), emptySet())
            val metadata = root.resolve("manifest/bilipai_js_plugins/installed/manifest.plugin.json")
            val original = Json.parseToJsonElement(metadata.toFile().readText()).jsonObject
            val changed = JsonObject(original.getValue("manifest").jsonObject + ("title" to JsonPrimitive("Changed after approval")))
            metadata.writeText(JsonObject(original + ("manifest" to changed)).toString())
            repo.load(); check(!repo.state.value.plugins.single().authorizationMatches)
            rejects { repo.setEnabled("manifest.plugin", true) }
        }
        case("grantsOutsideManifestAndAlteredPreviewAreRejected") {
            val repo = repository("grants")
            val preview = repo.previewScript(script("grant.plugin", permissions = "[]"))
            rejects { repo.install(preview, setOf(PluginCapability.NETWORK)) }
            rejects { repo.install(preview.copy(manifest = preview.manifest.copy(title = "Altered after preview")), emptySet()) }
            check(repo.state.value.plugins.isEmpty() && !Files.exists(root.resolve("grants/bilipai_js_plugins/packages")))
        }
        case("windowsReservedAndCaseFoldCollidingIdsCannotInstall") {
            val repo = repository("windows")
            rejects { repo.previewScript(script("CON")) }
            repo.install(repo.previewScript(script("mixed.ID")), emptySet())
            rejects { repo.install(repo.previewScript(script("MIXED.id")), emptySet()) }
            check(repo.state.value.plugins.size == 1)
        }
        case("foreignAbsoluteMetadataBlocksOriginalRecursiveRemoval") {
            val repo = repository("foreign")
            repo.install(repo.previewScript(script("foreign.plugin")), emptySet())
            val protected = root.resolve("outside-protected.js"); protected.writeText("untouched")
            val metadata = root.resolve("foreign/bilipai_js_plugins/installed/foreign.plugin.json")
            val original = Json.parseToJsonElement(metadata.toFile().readText()).jsonObject
            metadata.writeText(JsonObject(original + ("scriptPath" to JsonPrimitive(protected.toAbsolutePath().toString()))).toString())
            rejects { repo.load() }; rejects { repo.remove("foreign.plugin") }
            check(protected.toFile().readText() == "untouched" && repo.state.value.error != null)
            rejects { repo.host.loadModuleItems("foreign.plugin", "items") }
        }
        case("restoreShutdownCancelsEnteredGuestAndRejectsFutureWrites") {
            val repo = repository("restore")
            repo.install(repo.previewScript(script("restore.plugin", "BiliPai.storage.set('entered','yes'); return new Promise(()=>{});")), setOf(PluginCapability.PLUGIN_STORAGE))
            repo.setEnabled("restore.plugin", true)
            val running = async { repo.host.loadModuleItems("restore.plugin", "items") }
            withTimeout(10_000) { while (!Files.exists(root.resolve("restore/bilipai_js_plugin_storage/restore.plugin/entered"))) delay(20) }
            withTimeout(5_000) { repo.shutdownForRestore() }
            rejects { running.await() }; rejects { repo.setEnabled("restore.plugin", false) }
        }
        case("originalRemoveDeletesVerifiedPackageAndApprovalOnly") {
            val repo = repository("remove")
            repo.install(repo.previewScript(script("remove.plugin")), emptySet())
            repo.remove("remove.plugin")
            check(repo.state.value.plugins.isEmpty())
            check(!Files.exists(root.resolve("remove/bilipai_js_plugins/packages/remove.plugin")))
            check(!Files.exists(root.resolve("remove/bilipai_js_plugins/installed/remove.plugin.json")))
            check(!Files.exists(root.resolve("remove/bilipai_js_plugins/authorizations/remove.plugin.json")))
        }
        case("feedSourceNeedsCurrentApprovalEnableAndBothOriginalCapabilities") {
            val repo = repository("feed")
            val source = "window.BiliPaiPlugin={id:'approved.feed',title:'Feed',permissions:['NETWORK','FEED_SOURCE'],modules:[{id:'rss',title:'RSS',kind:'feed',functionName:'feed',params:[{name:'url',title:'URL',defaultValue:'https://fixture.invalid/rss'}]}]};"
            repo.install(repo.previewScript(source), setOf(PluginCapability.NETWORK, PluginCapability.FEED_SOURCE))
            check(repo.approvedFeedModuleIds().second.isEmpty())
            repo.setEnabled("approved.feed", true)
            check(repo.approvedFeedModuleIds().second == setOf("js:approved.feed:rss"))
            repo.install(repo.previewScript(source), setOf(PluginCapability.FEED_SOURCE))
            repo.setEnabled("approved.feed", true)
            check(repo.approvedFeedModuleIds().second.isEmpty())
        }
    } finally {
        repositories.forEach { runCatching { it.shutdownForRestore() } }
        val report = buildJsonObject { put("passed", cases.size == 10 && cases.all { it["passed"]!!.jsonPrimitive.boolean }); put("caseCount", cases.size); put("cases", JsonArray(cases)) }
        engine.resolve("repository-report.json").writeText(report.toString())
        check(root.toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    println("Actual JS repository PASS: ${cases.size}")
}
