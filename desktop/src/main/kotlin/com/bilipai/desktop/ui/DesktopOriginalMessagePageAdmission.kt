package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.DesktopOriginalMessageRepository
import com.android.purebilibili.feature.message.UserBasicInfo
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A child lifetime of the EXISTING Root entry; no credentials, client, store or media owner. */
internal class DesktopMessagePageAdmission(
    private val repository: DesktopRepository,
    val epoch: Long,
    val mid: Long,
    parent: CoroutineScope,
    private val retained: () -> Boolean,
    private val visible: () -> Boolean,
    private val commitEntry: ((() -> Unit) -> Boolean),
    private val userInfo: suspend (Long) -> UserBasicInfo?,
    private val videoInfo: suspend (String) -> Result<ViewInfo>,
) {
    private val lock = Any()
    private val live = AtomicBoolean(true)
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val revision = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val operations = mutableMapOf<String, Job>()
    private val permit = ThreadLocal<(() -> Boolean)?>()
    lateinit var requests: DesktopOriginalMessageRepository
        internal set

    fun isOwned(): Boolean = live.get() && lifetime.isActive && retained() && repository.sessionEpoch == epoch && repository.account.value?.mid == mid
    private fun <T> admit(block: () -> T): T = repository.withProfileAccountAdmission(epoch, mid, ::isOwned, commitEntry) {
        synchronized(lock) {
            if (!isOwned() || permit.get()?.invoke() == false) throw CancellationException("Message caller retired")
            block()
        }
    }
    suspend fun assertCurrent() { currentCoroutineContext().ensureActive(); admit { Unit } }
    fun csrf(): String? = admit { repository.ownedHomeCookie("bili_jct", epoch, ::isOwned) }
    fun callFactory(transport: okhttp3.OkHttpClient): okhttp3.Call.Factory = okhttp3.Call.Factory { request ->
        val caller = permit.get() ?: throw java.io.IOException("Message request has no caller")
        val retainedCaller = { isOwned() && caller() }
        admit { Unit }
        val body = request.body
        val singleSubmission = if (request.method == "POST" && body != null) request.newBuilder().method(request.method,
            object : okhttp3.RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = true // Prevent implicit HTTP503/redirect replay; original bytes stay unchanged.
                override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
            }).build() else request
        repository.ownedHomeCallFactory(epoch, retainedCaller, transport = transport).newCall(singleSubmission)
    }
    suspend fun sign(params: Map<String, String>, api: com.android.purebilibili.core.network.BilibiliApi): Map<String, String> {
        assertCurrent()
        val caller = permit.get() ?: throw CancellationException("Message sign has no caller")
        val (image, sub) = repository.homeWbiKeys(epoch, { isOwned() && caller() }, api).getOrThrow()
        assertCurrent()
        return com.android.purebilibili.core.network.WbiUtils.sign(params, image, sub)
    }
    fun <T> success(value: T): Result<T> = admit { Result.success(value) }
    suspend fun fetchUserInfo(uid: Long): UserBasicInfo? { assertCurrent(); val result = userInfo(uid); assertCurrent(); return result }
    suspend fun getVideoDetails(bvid: String): Result<ViewInfo> { assertCurrent(); val result = videoInfo(bvid); assertCurrent(); return result }
    suspend fun <T> runCatching(block: suspend () -> T): Result<T> = try {
        assertCurrent(); val value = block(); assertCurrent(); Result.success(value)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { assertCurrent(); Result.failure(failure) }

    /** Original VM launch body, with caller publication token propagated across IO/children. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = launchOwned(null, null, false, block)
    fun launchRead(channel: String, dependsOn: String? = null, block: suspend CoroutineScope.() -> Unit): Job =
        launchOwned(channel, dependsOn, false, block)
    fun launchMutation(channel: String, block: suspend CoroutineScope.() -> Unit): Job =
        launchOwned(channel, null, true, block)

    /** A visible screen's refresh uses this child's transport but keeps caller cancellation. */
    suspend fun awaitRead(channel: String, block: suspend CoroutineScope.() -> Unit) {
        val result = CompletableDeferred<Unit>()
        val caller = currentCoroutineContext()[Job]
        val job = launchOwned(channel, null, false, {
            try { block(); result.complete(Unit) }
            catch (failure: Exception) { result.completeExceptionally(failure) }
        }, caller)
        job.invokeOnCompletion { cause ->
            if (!result.isCompleted) result.completeExceptionally(cause ?: CancellationException("Message refresh retired"))
        }
        try { result.await() }
        finally { job.cancel(); withContext(NonCancellable) { job.join() } }
    }

    private fun launchOwned(channel: String?, dependsOn: String?, mutation: Boolean,
        block: suspend CoroutineScope.() -> Unit, caller: Job? = null): Job {
        val job: Job
        val previous: Job?
        var dependent: Job? = null
        synchronized(lock) {
            if (!isOwned() || mutation && !visible()) return Job().also { it.cancel() }
            if (mutation && channel != null) operations[channel]?.takeIf { it.isActive }?.let { return it }
            val ticket = if (channel != null) (revision[channel] ?: 0L) + 1 else 0L
            if (channel != null) revision[channel] = ticket
            val dependency = dependsOn?.let { revision[it] ?: 0L }
            previous = channel?.let { operations[it] }
            if (dependsOn == null && channel?.endsWith("-list") == true) {
                val dependentChannel=channel.removeSuffix("-list")+"-more"
                revision[dependentChannel]=(revision[dependentChannel] ?: 0L)+1L
                dependent = operations[dependentChannel]
            }
            lateinit var actual: Job
            val allowed = { actual.isActive && caller?.isActive != false && isOwned() && (channel == null || revision[channel] == ticket) &&
                (dependsOn == null || (revision[dependsOn] ?: 0L) == dependency) }
            actual = scope.launch(permit.asContextElement(allowed), start = CoroutineStart.LAZY) {
                assertCurrent(); block(); assertCurrent()
            }
            job = actual
            if (channel != null) operations[channel] = job
        }
        previous?.cancel()
        dependent?.cancel()
        job.start()
        return job
    }

    /** Synchronous direct VM actions also use Store -> entry gate; no IO in this transaction. */
    fun <T> stateFlow(initial: T): MutableStateFlow<T> {
        val state = MutableStateFlow(initial)
        return object : MutableStateFlow<T> by state {
            override var value: T
                get() = state.value
                set(next) { admit { state.value = next } }
            override fun compareAndSet(expect: T, update: T): Boolean = admit { state.compareAndSet(expect, update) }
            override fun tryEmit(value: T): Boolean = admit { state.tryEmit(value) }
            override suspend fun emit(value: T) { assertCurrent(); admit { state.value = value } }
        }
    }
    fun retire() { if (live.compareAndSet(true, false)) lifetime.cancel() }
    fun isDrained(): Boolean = lifetime.isCompleted
    suspend fun retireAndJoin(timeoutMillis: Long = 5_000): Boolean {
        require(currentCoroutineContext()[Job] !== lifetime)
        retire()
        return withContext(NonCancellable) { withTimeoutOrNull(timeoutMillis) { lifetime.join(); true } ?: false }
    }
}

/** Selected Windows file only. No folder scans or persisted gallery grants. Original 15MiB cap. */
internal data class DesktopMessageLocalImage(val path: Path, val mimeType: String, val fileName: String = path.fileName.toString()) {
    fun readBytes(): ByteArray {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "无法读取图片文件" }
        return Files.newInputStream(path).use { it.readNBytes(15 * 1024 * 1024 + 1) }
    }
}
