package fixture.finalwrite

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.feature.plugin.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private var assertions = 0
private fun prove(c: Boolean, label: String) { check(c) { label }; assertions++; println("ASSERT $assertions $label") }
private fun rejected(r: Result<*>): Boolean = r.exceptionOrNull() is CancellationException
private fun tmpCount(path: Path): Long = Files.list(path).use { it.filter { p -> p.fileName.toString().endsWith(".tmp") }.count() }
private fun origin(c: Class<*>): JsonObject {
    val entry = c.name.replace('.', '/') + ".class"
    val bytes = c.getResourceAsStream("/" + entry)!!.use { it.readBytes() }
    return buildJsonObject { put("class", c.name); put("entry", entry); put("codeSource", Path.of(c.protectionDomain.codeSource.location.toURI()).toString()); put("classSHA256Bytes", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
}

/** Explicit memory native ticket and Root gate; no Account/MPV implementation or real native completion. */
private class Lease {
    val monitor = Any()
    val active = AtomicBoolean(true)
    val completedTicket = AtomicBoolean(true)
    fun owns() = active.get() && completedTicket.get()
    fun admit(block: () -> Unit): Boolean = synchronized(monitor) { if (!owns()) false else { block(); true } }
}
private class FixturePlugin : PlayerPlugin {
    override val id = "fixture_final_write"
    override val name = "Memory final-write provider"
    override val description = "No HTTP or native callbacks"
    override val version = "1"
    override suspend fun onVideoLoad(bvid: String, cid: Long) = Unit
    override suspend fun onPositionUpdate(positionMs: Long): SkipAction? = null
    override fun onVideoEnd() = Unit
}
/** Call is a memory transport double. This does not construct or contact any OkHttp client/socket. */
private class MemoryCalls(private val blocked: Boolean = false) : Call.Factory {
    val count = AtomicInteger(); val canceled = AtomicInteger(); val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    override fun newCall(request: Request): Call = object : Call {
        val started = AtomicBoolean(); val dead = AtomicBoolean()
        val tags = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
        @Suppress("UNCHECKED_CAST") override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = tags[type.java] as? T
        @Suppress("UNCHECKED_CAST") override fun <T> tag(type: Class<out T>): T? = tags[type] as? T
        @Suppress("UNCHECKED_CAST") override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = tags.computeIfAbsent(type.java) { computeIfAbsent() } as T
        @Suppress("UNCHECKED_CAST") override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = tags.computeIfAbsent(type) { computeIfAbsent() } as T
        override fun request() = request
        override fun execute(): Response {
            started.set(true); count.incrementAndGet(); entered.complete(Unit)
            if (blocked) release.await()
            if (dead.get()) throw IOException("Canceled memory call")
            prove(request.header("Range")?.startsWith("bytes=0-") == true, "original probe Range request retained")
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("memory").body(ByteArray(4096).toResponseBody()).build()
        }
        override fun enqueue(callback: Callback) = error("probe must use original synchronous execute in IO")
        override fun cancel() { if (dead.compareAndSet(false, true)) canceled.incrementAndGet(); release.countDown() }
        override fun isExecuted() = started.get()
        override fun isCanceled() = dead.get()
        override fun timeout() = Timeout()
        override fun clone(): Call = newCall(request)
    }
}
private fun record(id: String) = SponsorBlockSkipRecord(id, "memory", "BVfixed", 1, "", "fixture", "", 7,
    "sponsor", 1000, 3000, trigger = SponsorBlockSkipTrigger.AUTO, timestampMs = 100)

fun main() {
    var exit = 1
    runBlocking {
        val out = Path.of(System.getProperty("fixture.output")); Files.createDirectories(out)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = DesktopPluginStore(out.resolve("private-store"))
        val ids = listOf("sponsor_block", "danmaku_enhance", "eye_protection", "anime4k", "bilipai_feed_filter", "home_feed_anonymizer", "adfilter", "subscription_feed", "today_watch", "cdn_region", "dlna_cast", "google_cast")
        store.update("plugin_prefs", ids.associate { "plugin_enabled_$it" to JsonPrimitive(false) } + mapOf("plugin_enabled_fixture_final_write" to JsonPrimitive(true)))
        val runtime = DesktopPluginRuntime(store); val plugin = FixturePlugin(); val lease = Lease()
        var failure: Throwable? = null
        suspend fun capture(cid: Long): DesktopPlayerPluginDispatch {
            val gen = runtime.onVideoLoad("BVfixed", cid)
            return checkNotNull(runtime.capturePlaybackPluginDispatch("BVfixed", cid, gen, lease::owns, lease::admit))
        }
        try {
            for (id in ids) PluginManager.awaitPluginReady(id)
            for (info in PluginManager.pluginsFlow.value) if (info.enabled) PluginManager.setEnabled(info.plugin.id, false)
            PluginManager.register(plugin); PluginManager.awaitPluginReady(plugin.id)
            prove(PluginManager.getEnabledPlayerPlugins() == listOf(plugin), "only explicit fixture provider enabled; Sponsor/CDN initialization disabled")
            var dispatch = capture(1)
            runtime.runPlaybackPluginCallback(dispatch, plugin) {
                prove(!Thread.holdsLock(lease.monitor), "original suspending history callback outside Root gate")
                SponsorBlockInsightStore.appendRecord(runtime.context, record("accepted"))
            }
            prove(SponsorBlockInsightStore.readRecords(runtime.context).single().segmentId == "accepted", "original Sponsor append/read schema persisted through captured final permit")
            prove(tmpCount(store.root) == 0L, "accepted history commit leaves no stage")
            lease.completedTicket.set(false)
            prove(rejected(runCatching { runtime.runPlaybackPluginCallback(dispatch, plugin) { SponsorBlockInsightStore.appendRecord(runtime.context, record("retired")) } }), "retired memory native-completion ticket rejects append")
            prove(SponsorBlockInsightStore.readRecords(runtime.context).size == 1, "retired ticket did not add history")
            lease.completedTicket.set(true)

            // Standalone fixed operation exercises stage/permit scheduling on the actual SAME Store/backing.
            val cancellationJob = Job(); var cancellationStage = false
            val canceledOp = DesktopPlayerPluginWriteAdmission.Operation(runtime.context, cancellationJob, null,
                checkCaptured = { if (tmpCount(store.root) != 0L) { cancellationStage = true; cancellationJob.cancel() } },
                admission = { block -> block(); true }, serializedDeferred = { _, _ -> error("unused") })
            val beforeCancel = Files.readAllBytes(store.root.resolve("plugin-settings.json"))
            prove(rejected(runCatching { DesktopPlayerPluginWriteAdmission.withCaptured(canceledOp) { PluginStore.setDataJson(runtime.context, plugin.id, "cancel", "cancelled") } }), "cancellation after staging rejects final permit")
            prove(cancellationStage && Files.readAllBytes(store.root.resolve("plugin-settings.json")).contentEquals(beforeCancel), "stage existed but canceled operation preserved target bytes")
            prove(tmpCount(store.root) == 0L, "canceled stage cleaned up")
            cancellationJob.cancelAndJoin()

            val conflictJob = Job(); val conflicts = AtomicInteger(); val permits = AtomicInteger()
            val conflictOp = DesktopPlayerPluginWriteAdmission.Operation(runtime.context, conflictJob, null,
                checkCaptured = {
                    if (tmpCount(store.root) != 0L && conflicts.compareAndSet(0, 1)) {
                        prove(!Thread.holdsLock(lease.monitor), "conflict injection before permit runs outside Root gate")
                        store.update("plugin_prefs", mapOf("fixture_competing" to JsonPrimitive("other")))
                    }
                }, admission = { block -> permits.incrementAndGet(); lease.admit(block) }, serializedDeferred = { _, _ -> error("unused") })
            DesktopPlayerPluginWriteAdmission.withCaptured(conflictOp) { PluginStore.setDataJson(runtime.context, plugin.id, "conflict", "ours") }
            prove(permits.get() == 2 && conflicts.get() == 1, "backing snapshot conflict discarded permit/temp and retried fixed operation")
            prove(store.preferences("plugin_prefs")["fixture_competing"]?.jsonPrimitive?.content == "other" && PluginStore.getDataJson(runtime.context, plugin.id, "conflict") == "ours", "retry preserved competing field and original plugin data key")
            prove(tmpCount(store.root) == 0L, "conflict retry leaves no temp")
            conflictJob.complete(); conflictJob.join()

            val delayedEntered = CompletableDeferred<Unit>(); val delayedRelease = CompletableDeferred<Unit>(); var delayed: Job? = null
            val original = scope.launch {
                runtime.mutatePlaybackPlugin(dispatch, plugin, false) {
                    prove(Thread.holdsLock(lease.monitor), "original sync mutation remains inside short Root gate")
                    delayed = DesktopPlayerPluginWriteAdmission.launchOrOriginal(scope) {
                        delayedEntered.complete(Unit); delayedRelease.await()
                        prove(DesktopPlayerPluginWriteAdmission.contextOrOriginal { error("late global Context lookup") } === runtime.context, "delayed task retains captured Runtime Context")
                        PluginStore.setDataJson(runtime.context, plugin.id, "delayed", "done")
                    }
                }
            }
            original.join(); delayedEntered.await(); prove(original.isCompleted && !original.isCancelled, "origin Job completed normally before delayed write")
            delayedRelease.complete(Unit); delayed!!.join()
            prove(!delayed!!.isCancelled && PluginStore.getDataJson(runtime.context, plugin.id, "delayed") == "done", "normal completed origin remains valid for same accepted source")

            val staleEntered = CompletableDeferred<Unit>(); val staleRelease = CompletableDeferred<Unit>(); var stale: Deferred<Result<Unit>>? = null
            runtime.mutatePlaybackPlugin(dispatch, plugin, false) {
                // Keep the original helper's global launch but collect its result inside the fixture body.
                val finished = CompletableDeferred<Result<Unit>>()
                val job = DesktopPlayerPluginWriteAdmission.launchOrOriginal(scope) {
                    staleEntered.complete(Unit); staleRelease.await()
                    val result = runCatching { PluginStore.setDataJson(runtime.context, plugin.id, "stale", "bad") }
                    finished.complete(result)
                    result.getOrThrow()
                }
                stale = scope.async { job.join(); if (finished.isCompleted) finished.await() else Result.failure(CancellationException("retired before body")) }
            }
            staleEntered.await(); lease.active.set(false); staleRelease.complete(Unit)
            prove(rejected(stale!!.await()), "entry retired after deferred await rejects write")
            prove(PluginStore.getDataJson(runtime.context, plugin.id, "stale") == null && tmpCount(store.root) == 0L, "retired deferred source writes no key/stage")
            lease.active.set(true); dispatch = capture(2)

            val cdn = CdnRegionPlugin(); val calls = MemoryCalls()
            runtime.runPlaybackPluginCallback(dispatch, plugin, calls) { cdn.probePlaybackCdnCandidates(listOf("https://memory.invalid/video"), emptyList()) }
            prove(calls.count.get() == 1, "original CDN probe uses required captured memory Call.Factory once; no client/socket")
            prove(PluginStore.getConfigJson(runtime.context, CDN_REGION_PLUGIN_ID)?.contains("memory.invalid") == true, "original CDN health config persisted on same Store")
            prove(rejected(runCatching { runtime.runPlaybackPluginCallback(dispatch, plugin) { cdn.probePlaybackCdnCandidates(listOf("https://missing.invalid/video"), emptyList()) } }), "three-argument ABI cannot silently fall back for captured probe")

            val blocked = MemoryCalls(true); val beforeProbeCancel = Files.readAllBytes(store.root.resolve("plugin-settings.json"))
            val pending = scope.async { runtime.runPlaybackPluginCallback(dispatch, plugin, blocked) { cdn.probePlaybackCdnCandidates(listOf("https://cancel.invalid/video"), emptyList()) } }
            blocked.entered.await(); pending.cancelAndJoin()
            prove(blocked.canceled.get() == 1, "caller cancellation immediately canceled original blocking Call")
            prove(Files.readAllBytes(store.root.resolve("plugin-settings.json")).contentEquals(beforeProbeCancel) && tmpCount(store.root) == 0L, "canceled probe did not persist health/fallback or leak stage")
        } catch (t: Throwable) { failure = t; t.printStackTrace() }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin(); withTimeout(12000) { runtime.shutdownForRestore() } }
        val result = buildJsonObject {
            put("passed", failure == null); put("assertions", assertions); put("prospectiveOverrides", 4)
            put("base", "actual69 101CP"); put("sameActualStoreAuthority", true); put("runtimeShutdownJoined", true)
            put("rootNativeTicket", "explicit memory predicate, not native completed seek"); put("httpAccountsNativeWindowRun", false)
            put("origins", JsonArray(listOf(DesktopPluginRuntime::class.java, DesktopPluginStore::class.java, DesktopPlayerPluginWriteAdmission::class.java, CdnRegionPlugin::class.java, SponsorBlockInsightStore::class.java, PluginManager::class.java).map(::origin)))
            put("failure", failure?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }
        Files.writeString(out.resolve("result.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result))
        if (failure == null) exit = 0
    }
    kotlin.system.exitProcess(exit)
}
