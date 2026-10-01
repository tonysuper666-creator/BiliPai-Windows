package com.bilipai.desktop.plugins

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

/** Request-local transport/write view. Runtime, Store and generation remain their existing owners. */
internal object DesktopPlayerPluginWriteAdmission {
    internal class Operation(
        val context: DesktopPluginContext,
        val originJob: Job,
        val playbackCalls: Call.Factory?,
        private val checkCaptured: () -> Unit,
        private val admission: ((() -> Unit) -> Boolean),
        private val serializedDeferred: suspend (Operation, suspend () -> Unit) -> Unit,
    ) {
        fun check() {
            if (originJob.isCancelled) throw CancellationException("Captured playback plugin request canceled")
            checkCaptured()
        }

        fun permit(store: DesktopPluginStore, callerJob: Job): Permit {
            require(context.store === store) { "Captured playback plugin write has a different Store" }
            return requestPermit(callerJob)
        }

        fun requestPermit(callerJob: Job): Permit {
            callerJob.ensureActive(); check()
            var accepted = false
            if (!admission { callerJob.ensureActive(); check(); accepted = true } || !accepted)
                throw CancellationException("Playback plugin final write admission retired")
            return Permit()
        }

        fun <T> mutate(action: () -> T): T {
            check()
            if (synchronousAdmission.get() === this) return action()
            var result: Result<T>? = null
            if (!admission { check(); result = runCatching(action) })
                throw CancellationException("Playback plugin cache admission retired")
            return (result ?: throw CancellationException("Playback plugin cache admission did not execute")).getOrThrow()
        }

        suspend fun deferred(block: suspend () -> Unit) = serializedDeferred(this, block)
    }

    /** Minted outside backing monitor. Consumption cannot acquire Root gates or revoke an in-flight commit. */
    internal class Permit {
        private val consumed = AtomicBoolean()
        fun consume() = check(consumed.compareAndSet(false, true)) { "Playback plugin write permit already consumed" }
    }

    private val operation = ThreadLocal<Operation?>()
    private val synchronousAdmission = ThreadLocal<Operation?>()
    internal fun currentOrNull(): Operation? = operation.get()

    internal suspend fun <T> withCaptured(value: Operation, block: suspend () -> T): T =
        withContext(operation.asContextElement(value)) { value.check(); block() }

    internal fun <T> inSynchronousAdmission(value: Operation, action: () -> T): T {
        val previous = synchronousAdmission.get()
        synchronousAdmission.set(value)
        try { value.check(); return action() }
        finally { synchronousAdmission.set(previous) }
    }

    fun checkCurrentOrOriginal() { operation.get()?.check() }
    fun contextOrOriginal(original: () -> DesktopPluginContext): DesktopPluginContext =
        operation.get()?.let { it.check(); it.context } ?: original()

    fun <T> mutateOrOriginal(action: () -> T): T {
        val captured = operation.get()
        return if (captured == null) action() else captured.mutate(action)
    }

    fun playbackCallsOrOriginal(original: Call.Factory): Call.Factory = playbackCallsOrOriginal { original }
    fun playbackCallsOrOriginal(original: () -> Call.Factory): Call.Factory = operation.get()?.let {
        it.check()
        it.playbackCalls ?: throw CancellationException("Captured playback CDN probe requires its accepted Call.Factory")
    } ?: original()

    fun launchOrOriginal(scope: CoroutineScope, block: suspend () -> Unit): Job {
        val captured = operation.get()
        return scope.launch {
            if (captured == null) block()
            else captured.deferred { withCaptured(captured, block) }
        }
    }

    /** Original public transport is unchanged. Admission only mints an in-flight request permit;
     * execute and response-body IO run after the Root gate has been released. */
    suspend fun <T> executePublicOrOriginal(call: Call, block: (Response) -> T): T {
        val captured = operation.get() ?: return call.execute().use(block)
        val caller = currentCoroutineContext()[Job] ?: error("Sponsor request requires a caller Job")
        captured.requestPermit(caller).consume()
        return executeOrOriginal(call, block)
    }

    /** The cancellation registration covers execute AND all blocking response-body reads. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun <T> executeOrOriginal(call: Call, block: (Response) -> T): T {
        val caller = currentCoroutineContext()[Job] ?: error("CDN probe requires a caller Job")
        val captured = operation.get()
        caller.ensureActive(); captured?.check()
        val callerHandle = caller.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        val originHandle = captured?.originJob?.takeIf { it !== caller }
            ?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
        try {
            val result = call.execute().use { response ->
                caller.ensureActive(); captured?.check()
                block(response)
            }
            caller.ensureActive(); captured?.check()
            return result
        } catch (failure: Throwable) {
            caller.ensureActive(); captured?.check()
            throw failure
        } finally {
            callerHandle.dispose(); originHandle?.dispose()
        }
    }
}
