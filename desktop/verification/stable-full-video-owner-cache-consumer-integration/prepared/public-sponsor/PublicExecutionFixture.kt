package fixture.sponsorpublic

import com.bilipai.desktop.plugins.*
import com.android.purebilibili.data.repository.SponsorBlockRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private var assertions = 0
private fun prove(c: Boolean, label: String) { check(c) { label }; assertions++; println("ASSERT $assertions $label") }
private fun rejected(r: Result<*>) = r.exceptionOrNull() is CancellationException
/** All native/entry/Store ownership is an explicit memory predicate/admission test double. */
private class Gate {
    val monitor = Any(); val owned = AtomicBoolean(true); val permits = AtomicInteger()
    var deny = false
    fun check() { if (!owned.get()) throw CancellationException("Memory source retired") }
    fun admit(action: () -> Unit): Boolean = synchronized(monitor) {
        if (!owned.get() || deny) false else { action(); permits.incrementAndGet(); true }
    }
}
/** A Call supplied directly to the platform execution guard. No client, socket or Repository request injection. */
private class MemoryCall(private val gate: Gate, private val blockRead: Boolean = false,
    private val retireDuringExecute: Boolean = false) : Call {
    val executions = AtomicInteger(); val cancellations = AtomicInteger(); val reads = AtomicInteger(); val closed = AtomicBoolean()
    val enteredRead = CompletableDeferred<Unit>(); val release = CountDownLatch(1); private val dead = AtomicBoolean()
    private val request = Request.Builder().url("https://community.invalid/memory").post("{}".toRequestBody()).build()
    private val tags = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    @Suppress("UNCHECKED_CAST") override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = tags[type.java] as? T
    @Suppress("UNCHECKED_CAST") override fun <T> tag(type: Class<out T>): T? = tags[type] as? T
    @Suppress("UNCHECKED_CAST") override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = tags.computeIfAbsent(type.java) { computeIfAbsent() } as T
    @Suppress("UNCHECKED_CAST") override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = tags.computeIfAbsent(type) { computeIfAbsent() } as T
    override fun request() = request
    override fun execute(): Response {
        prove(!Thread.holdsLock(gate.monitor), "public execute outside short admission monitor")
        executions.incrementAndGet()
        if (retireDuringExecute) gate.owned.set(false)
        val source = object : Source {
            var emitted = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                prove(!Thread.holdsLock(gate.monitor), "public response-body IO outside short admission monitor")
                reads.incrementAndGet(); enteredRead.complete(Unit)
                if (blockRead) release.await()
                if (dead.get()) throw IOException("Canceled memory public body")
                if (emitted) return -1
                emitted = true; return sink.writeUtf8("ok").let { 2L }
            }
            override fun timeout() = Timeout()
            override fun close() { closed.set(true); release.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory").body(body).build()
    }
    override fun enqueue(callback: Callback) = error("Original Sponsor uses execute, not enqueue")
    override fun cancel() { if (dead.compareAndSet(false, true)) cancellations.incrementAndGet(); release.countDown() }
    override fun isExecuted() = executions.get() != 0
    override fun isCanceled() = dead.get()
    override fun timeout() = Timeout()
    override fun clone(): Call = MemoryCall(gate, blockRead, retireDuringExecute)
}
private fun origin(c: Class<*>): JsonObject {
    val entry = c.name.replace('.', '/') + ".class"
    val data = c.getResourceAsStream("/" + entry)!!.use { it.readBytes() }
    return buildJsonObject { put("class", c.name); put("entry", entry); put("codeSource", Path.of(c.protectionDomain.codeSource.location.toURI()).toString()); put("classSHA256Bytes", MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }) }
}
fun main() {
    var exit = 1
    runBlocking {
        val out = Path.of(System.getProperty("fixture.output")); Files.createDirectories(out)
        val store = DesktopPluginStore(out.resolve("private-store")); val context = DesktopPluginContext(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val jobs = mutableListOf<Job>()
        fun operation(gate: Gate, job: Job = Job().also { jobs += it }) =
            DesktopPlayerPluginWriteAdmission.Operation(context, job, null, gate::check, gate::admit, { _, _ -> error("Unused deferred path") })
        var failure: Throwable? = null
        try {
            val legacyGate = Gate(); val legacy = MemoryCall(legacyGate)
            prove(DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(legacy) { it.body.string() } == "ok", "no-context legacy original execute/use returns unchanged body")
            prove(legacyGate.permits.get() == 0 && legacy.executions.get() == 1 && legacy.closed.get(), "legacy has no introduced admission or resource leak")

            val gate = Gate(); val accepted = MemoryCall(gate)
            val value = DesktopPlayerPluginWriteAdmission.withCaptured(operation(gate)) {
                DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(accepted) { it.body.string() }
            }
            prove(value == "ok" && gate.permits.get() == 1 && accepted.executions.get() == 1 && accepted.closed.get(), "captured public request admitted once, original Call executes once and body closes")

            val retired = Gate().also { it.owned.set(false) }; val staleCall = MemoryCall(retired)
            prove(rejected(runCatching { DesktopPlayerPluginWriteAdmission.withCaptured(operation(retired)) { DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(staleCall) { it.body.string() } } }), "retired source rejected before public request starts")
            prove(staleCall.executions.get() == 0 && retired.permits.get() == 0, "retired source performed no execute or publication")

            val denied = Gate().also { it.deny = true }; val deniedCall = MemoryCall(denied)
            prove(rejected(runCatching { DesktopPlayerPluginWriteAdmission.withCaptured(operation(denied)) { DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(deniedCall) { it.body.string() } } }), "short final admission rejects still-owned source")
            prove(deniedCall.executions.get() == 0, "admission rejection starts no public IO")

            val canceledOrigin = Job().also { jobs += it; it.cancel() }; val canceledCall = MemoryCall(Gate())
            prove(rejected(runCatching { DesktopPlayerPluginWriteAdmission.withCaptured(operation(Gate(), canceledOrigin)) { DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(canceledCall) { it.body.string() } } }), "canceled fixed origin rejected without late-current retag")
            prove(canceledCall.executions.get() == 0, "canceled origin starts no public request")

            val completeOrigin = Job().also { jobs += it; it.complete() }; val completeGate = Gate(); val completeCall = MemoryCall(completeGate)
            DesktopPlayerPluginWriteAdmission.withCaptured(operation(completeGate, completeOrigin)) { DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(completeCall) { it.body.string() } }
            prove(completeOrigin.isCompleted && !completeOrigin.isCancelled && completeCall.executions.get() == 1, "normally completed fixed origin can still use same accepted source")

            val active = Gate(); val blocking = MemoryCall(active, blockRead = true); val fixed = operation(active)
            val pending = scope.async { DesktopPlayerPluginWriteAdmission.withCaptured(fixed) { DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(blocking) { it.body.string() } } }
            blocking.enteredRead.await(); pending.cancelAndJoin()
            prove(blocking.cancellations.get() == 1 && blocking.executions.get() == 1 && blocking.closed.get(), "actual caller cancellation cancels in-flight public body and closes response exactly once")
            prove(active.owned.get() && !fixed.originJob.isCancelled, "only caller Job canceled; accepted owner/fixed origin remain alive")

            val inflight = Gate(); val inflightCall = MemoryCall(inflight, retireDuringExecute = true)
            prove(rejected(runCatching { DesktopPlayerPluginWriteAdmission.withCaptured(operation(inflight)) { DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(inflightCall) { it.body.string() } } }), "source retired after permit rejects public result")
            prove(inflight.permits.get() == 1 && inflightCall.executions.get() == 1 && inflightCall.reads.get() == 0 && inflightCall.closed.get(), "accepted request is in-flight, not falsely revoked; stale response closes before body/result")
        } catch (t: Throwable) { failure = t; t.printStackTrace() }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin(); for (job in jobs) job.cancelAndJoin() }
        val result = buildJsonObject {
            put("passed", failure == null); put("assertions", assertions); put("actualBase", "snapshot71 101CP")
            put("prospectiveOverrides", "SponsorRepository and frozen120 helper family")
            put("memoryCallDirectGuardFixture", true); put("originalRepositoryPublicHttpExecuted", false); put("networkNativeAccountsGuiRun", false)
            put("origins", JsonArray(listOf(DesktopPlayerPluginWriteAdmission::class.java, SponsorBlockRepository::class.java, DesktopPluginStore::class.java).map(::origin)))
            put("failure", failure?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }
        Files.writeString(out.resolve("result.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result))
        if (failure == null) exit = 0
    }
    kotlin.system.exitProcess(exit)
}
