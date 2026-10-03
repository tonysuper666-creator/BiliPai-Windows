package com.bilipai.desktop.player.cache

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** SIDX/probe reads use the SAME bound lease/Repository transport, not a client.
 * Keep both cancellation hooks through ResponseBody.close, not just response headers. */
internal class DesktopMediaByteProbeCalls(
    private val lease: DesktopMediaByteLease,
    private val tracks: List<DesktopMediaByteTrack>,
    private val callerJob: Job,
    private val stillCurrent: () -> Boolean,
) : Call.Factory {
    private fun assertCurrent() {
        callerJob.ensureActive(); lease.check()
        if (!stillCurrent()) throw CancellationException("Captured media probe retired")
    }
    override fun newCall(request: Request): Call {
        assertCurrent()
        val track = tracks.firstOrNull { candidate -> candidate.urls.any { it.toHttpUrl() == request.url } }
            ?: throw IOException("Probe URL is not a captured media track")
        val range = request.header("Range")
        require(range != null && Regex("bytes=\\d+-\\d+").matches(range)) { "Captured probe requires an exact range" }
        val prepared = request.newBuilder().apply {
            track.headers.forEach { (key, value) -> header(key, value) }
            tag(DesktopCdnRangeRequestPolicy::class.java, DesktopCdnRangeRequestPolicy)
            tag(DesktopMediaOriginHeaders::class.java, DesktopMediaOriginHeaders(track.headers,
                track.urls.map { it.toHttpUrl().origin() }.toSet()))
        }.build()
        val delegate = lease.probeAdmission().calls({ lease.current() && stillCurrent() }, callerJob).newCall(prepared)
        return object : Call by delegate {
            override fun clone(): Call = this@DesktopMediaByteProbeCalls.newCall(request)
            @OptIn(InternalCoroutinesApi::class)
            private fun hooks(): List<DisposableHandle> = listOf(
                callerJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) delegate.cancel() },
                lease.watchProbeCancellation { delegate.cancel() })
            private fun checked(response: Response, hooks: List<DisposableHandle>): Response {
                try { assertCurrent() } catch (failure: Throwable) { response.close(); hooks.forEach { it.dispose() }; throw failure }
                val original = response.body
                val done = AtomicBoolean(false)
                fun finish() { if (done.compareAndSet(false, true)) hooks.forEach { it.dispose() } }
                val guarded = object : ResponseBody() {
                    private val input = object : ForwardingSource(original.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            assertCurrent()
                            return try { super.read(sink, byteCount).also { assertCurrent() } }
                            catch (failure: Throwable) { assertCurrent(); throw failure }
                        }
                        override fun close() { try { super.close() } finally { finish() } }
                    }.buffer()
                    override fun contentType(): MediaType? = original.contentType()
                    override fun contentLength(): Long = original.contentLength()
                    override fun source(): BufferedSource = input
                }
                return response.newBuilder().body(guarded).build()
            }
            override fun execute(): Response {
                val hooks = hooks()
                try { assertCurrent(); return checked(delegate.execute(), hooks) }
                catch (failure: Throwable) { hooks.forEach { it.dispose() }; delegate.cancel(); assertCurrent(); throw failure }
            }
            override fun enqueue(callback: Callback) {
                val hooks = hooks()
                try {
                    assertCurrent()
                    delegate.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            hooks.forEach { it.dispose() }; callback.onFailure(call, e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            val wrapped = try { checked(response, hooks) }
                            catch (failure: Throwable) { callback.onFailure(call, IOException("Captured probe retired", failure)); return }
                            callback.onResponse(call, wrapped)
                        }
                    })
                } catch (failure: Throwable) { hooks.forEach { it.dispose() }; delegate.cancel(); throw failure }
            }
        }
    }
}
