package com.bilipai.desktop.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Immutable per-request facet of the existing account/page/sheet admission.
 * Inherited by the original API's IO context; it owns no client, token or scope. */
internal class DesktopDynamicGrpcRequestSource(
    val stillOwned: () -> Boolean,
    val admission: (() -> Unit) -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopDynamicGrpcRequestSource>
}

/** Transport-only binding: original ProtoWire/framing/status logic is untouched.
 * The continuation remains cancellable throughout bounded body consumption. */
internal object DesktopDynamicGrpcCalls {
    suspend fun <T> execute(
        client: Call.Factory,
        request: Request,
        stillOwned: () -> Boolean,
        decode: (Response) -> T,
    ): T {
        val source = currentCoroutineContext()[DesktopDynamicGrpcRequestSource]
        return suspendCancellableCoroutine { continuation ->
            fun current() = continuation.isActive && stillOwned() && source?.stillOwned?.invoke() != false
            fun admit(action: () -> Unit): Boolean {
                var applied = false
                val publication = {
                    if (current()) { action(); applied = true }
                }
                val accepted = source?.admission?.invoke(publication) ?: run { publication(); applied }
                return accepted && applied
            }
            fun retired() = CancellationException("Dynamic share owner retired")
            if (!current()) { continuation.cancel(retired()); return@suspendCancellableCoroutine }
            // Call construction and cancellation handlers are outside source admission.
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            // Even an inline dispatcher failure may invoke a callback from enqueue.
            // Defer all response IO, close and continuation delivery until its gate returns.
            val callbackLock = Any()
            var enqueueReturned = false
            var callbackClaimed = false
            var pending: (() -> Unit)? = null
            fun dispatch(action: () -> Unit) {
                val runNow = synchronized(callbackLock) {
                    if (callbackClaimed) false else {
                        callbackClaimed = true
                        if (enqueueReturned) true else { pending = action; false }
                    }
                }
                if (runNow) action()
            }
            fun finishEnqueue() {
                val action = synchronized(callbackLock) {
                    enqueueReturned = true
                    pending.also { pending = null }
                }
                action?.invoke()
            }
            fun failure(error: Exception) {
                val accepted = try {
                    admit { /* Only accept this source's error here. */ }
                } catch (ownerFailure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(ownerFailure)
                    return
                }
                if (continuation.isActive) {
                    if (accepted) continuation.resumeWithException(error)
                    else continuation.cancel(retired())
                }
            }
            val callback = object : Callback {
                override fun onFailure(call: Call, error: IOException) = dispatch { failure(error) }
                override fun onResponse(call: Call, response: Response) = dispatch {
                    try {
                        response.use {
                            if (!current()) throw retired()
                            val result = decode(it)
                            if (!admit { /* Body consumption and delivery remain outside admission. */ })
                                throw retired()
                            continuation.resume(result)
                        }
                    } catch (error: Exception) {
                        failure(error)
                    }
                }
            }
            try {
                // The same account/source gate only publishes this already-created Call.
                if (!admit { call.enqueue(callback) }) {
                    call.cancel()
                    if (continuation.isActive) continuation.cancel(retired())
                }
            } catch (error: Exception) {
                call.cancel()
                failure(error)
            } finally {
                finishEnqueue()
            }
        }
    }

    fun readBody(body:ResponseBody):ByteArray {
        val maximum=8L*1024*1024
        require(body.contentLength()<=maximum){"Dynamic share response is too large"}
        return body.byteStream().use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192);var size=0L
            while(true){val read=input.read(buffer);if(read<0)break;size+=read;require(size<=maximum){"Dynamic share response is too large"};out.write(buffer,0,read)}
            out.toByteArray()
        }
    }
}
