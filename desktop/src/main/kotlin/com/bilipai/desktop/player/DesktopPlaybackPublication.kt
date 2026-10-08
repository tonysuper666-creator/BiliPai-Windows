package com.bilipai.desktop.player

import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Final, short publication only. Never perform native joins, close, or network waits in admit. */
internal interface DesktopPlaybackPublication {
    val requiresAccountReceipt: Boolean
    fun isCurrent(source: PlaybackSource): Boolean
    fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T
    fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job? = null): Call.Factory
}

/** Native command admission survives successful request completion, but not cancellation. */
public fun interface DesktopNativePlaybackPublication {
    public fun admit(command: () -> Unit): Boolean
    /** Native actor acknowledgement only. Implementations may set an atomic flag;
     * no business work, IO or waiting is allowed inside this callback. */
    public fun onLoadCommandAccepted() { }
}

internal fun DesktopPlaybackPublication.ownedSource(source: PlaybackSource, stillOwned: () -> Boolean): PlaybackSource =
    source.copy(nativePublication = DesktopNativePlaybackPublication { command ->
        try { admit(source, stillOwned, command); true }
        catch (_: CancellationException) { false }
    })

/** Long-lived native observers reject one retired action without cancelling the observer itself. */
internal fun DesktopPlaybackPublication.tryAdmit(source: PlaybackSource, stillOwned: () -> Boolean, command: () -> Boolean): Boolean =
    try { admit(source, stillOwned, command) } catch (_: CancellationException) { false }

/** Actual transport always uses the one Repository/Store; null receipts are NOT a local bypass. */
internal class DesktopRepositoryPlaybackPublication(private val repository: DesktopRepository,
    private val allowPrimaryAccountSource: Boolean = false) : DesktopPlaybackPublication {
    override val requiresAccountReceipt get() = !allowPrimaryAccountSource
    override fun isCurrent(source: PlaybackSource): Boolean = source.authorizationReceipt?.let(repository::isPlaybackReceiptCurrent)
        ?: (allowPrimaryAccountSource && source.primaryAccountEpoch != null && source.primaryAccountEpoch == repository.sessionEpoch)
    override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
        source.authorizationReceipt?.let { return repository.withPlaybackReceiptAdmission(it, stillOwned, block) }
        val epoch = source.primaryAccountEpoch?.takeIf { allowPrimaryAccountSource }
            ?: throw CancellationException("Missing playback authorization receipt")
        return repository.withPrimaryPlaybackAdmission(epoch, stillOwned, block)
    }
    override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?): Call.Factory {
        val raw = source.authorizationReceipt?.let { repository.playbackReceiptCalls(it, stillOwned, delegate) }
            ?: source.primaryAccountEpoch?.takeIf { allowPrimaryAccountSource }?.let { repository.primaryPlaybackCalls(it, stillOwned, delegate) }
            ?: throw CancellationException("Missing playback authorization receipt")
        // raw delegates to the existing Repository client, not a new client/dispatcher/cookie jar.
        return admittedPlaybackCalls(raw, this, source, stillOwned, callerJob)
    }
}

/** Synthetic/local transports must explicitly supply their own atomic owner admission. */
internal class DesktopLocalPlaybackPublication(private val isOwned: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean)) : DesktopPlaybackPublication {
    override val requiresAccountReceipt = false
    override fun isCurrent(source: PlaybackSource) = source.authorizationReceipt == null && source.primaryAccountEpoch == null && isOwned()
    override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
        if (source.authorizationReceipt != null || source.primaryAccountEpoch != null) throw CancellationException("Local source cannot adopt account authority")
        var outcome: Result<T>? = null
        val accepted = withOwnedAdmission {
            if (!isOwned() || !stillOwned()) throw CancellationException("Local playback owner retired")
            outcome = runCatching(block)
        }
        if (!accepted || outcome == null) throw CancellationException("Local playback owner retired")
        return outcome!!.getOrThrow()
    }
    override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?) =
        admittedPlaybackCalls(delegate, this, source, stillOwned, callerJob)
}

/** Enqueue is the HTTP publication point. execute waits OUTSIDE the Store monitor. */
@OptIn(InternalCoroutinesApi::class)
internal fun admittedPlaybackCalls(delegate: Call.Factory, publication: DesktopPlaybackPublication,
    source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job? = null): Call.Factory = Call.Factory { request ->
    val call = publication.admit(source, stillOwned) { delegate.newCall(request) }
    object : Call by call {
        override fun request(): Request = call.request()
        override fun cancel() = call.cancel()
        override fun isExecuted() = call.isExecuted()
        override fun isCanceled() = call.isCanceled()
        override fun timeout(): Timeout = call.timeout()
        override fun clone(): Call = admittedPlaybackCalls(delegate, publication, source, stillOwned, callerJob).newCall(request)
        override fun enqueue(callback: Callback) {
            val cancellation = callerJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) call.cancel() }
            val finished = AtomicBoolean(false)
            fun finish() { if (finished.compareAndSet(false, true)) cancellation?.dispose() }
            fun retainCancellation(response: Response): Response {
                callerJob?.ensureActive()
                val original = response.body
                val guarded = object : ResponseBody() {
                    private val input = object : ForwardingSource(original.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            try {
                                callerJob?.ensureActive()
                                return super.read(sink, byteCount).also { callerJob?.ensureActive() }
                            } catch (failure: Throwable) {
                                finish(); call.cancel()
                                callerJob?.ensureActive()
                                throw failure
                            }
                        }
                        override fun close() { try { super.close() } finally { finish() } }
                    }.buffer()
                    override fun contentType(): MediaType? = original.contentType()
                    override fun contentLength(): Long = original.contentLength()
                    override fun source(): BufferedSource = input
                }
                return response.newBuilder().body(guarded).build()
            }
            val guardedCallback = object : Callback {
                override fun onFailure(call: Call, e: IOException) { finish(); callback.onFailure(call, e) }
                override fun onResponse(call: Call, response: Response) {
                    // Headers do not end a download: keep the hook while its body can block.
                    val wrapped = try { retainCancellation(response) }
                    catch (failure: Throwable) {
                        try { response.close() }
                        finally {
                            finish(); call.cancel()
                            callback.onFailure(call, IOException("Playback response retired before body delivery", failure))
                        }
                        return
                    }
                    try { callback.onResponse(call, wrapped) }
                    catch (failure: Throwable) {
                        try { wrapped.close() }
                        catch (closeFailure: Throwable) { if (closeFailure !== failure) failure.addSuppressed(closeFailure) }
                        throw failure
                    }
                }
            }
            try { publication.admit(source, stillOwned) { call.enqueue(guardedCallback) } }
            catch (failure: Throwable) { finish(); call.cancel(); throw failure }
        }
        override fun execute(): Response {
            val finished = CountDownLatch(1)
            val abandoned = AtomicBoolean(false)
            val responseGate = Any()
            var response: Response? = null
            var failure: IOException? = null
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure = e; finished.countDown() }
                override fun onResponse(call: Call, value: Response) {
                    val discarded = synchronized(responseGate) {
                        if (abandoned.get()) value else { response = value; null }
                    }
                    try { discarded?.close() } finally { finished.countDown() }
                }
            })
            try { finished.await() }
            catch (interrupted: InterruptedException) {
                val delivered = synchronized(responseGate) {
                    abandoned.set(true); response.also { response = null }
                }
                try { delivered?.close() }
                catch (closeFailure: Throwable) { if (closeFailure !== interrupted) interrupted.addSuppressed(closeFailure) }
                finally { cancel(); Thread.currentThread().interrupt() }
                throw IOException("Interrupted playback request", interrupted)
            }
            try { callerJob?.ensureActive() }
            catch (cancelled: CancellationException) {
                val delivered = synchronized(responseGate) {
                    abandoned.set(true); response.also { response = null }
                }
                try { delivered?.close() }
                catch (closeFailure: Throwable) { if (closeFailure !== cancelled) cancelled.addSuppressed(closeFailure) }
                finally { cancel() }
                throw cancelled
            }
            failure?.let { throw it }
            return response ?: throw IOException("Playback request returned no response")
        }
    }
}
