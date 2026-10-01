package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Receives the existing Ops guestWeb Call.Factory. No new client/cookie owner. */
internal object DesktopVideoShareImageTransport {
    suspend fun download(factory:Call.Factory,url:String,owned:()->Boolean):ByteArray = withContext(Dispatchers.IO) {
        suspend fun checkOwner() {currentCoroutineContext().ensureActive();if(!owned())throw CancellationException("分享图片所有者已退役")}
        checkOwner()
        val parsed=url.toHttpUrl()
        require(parsed.scheme=="https" && (parsed.host=="hdslb.com" || parsed.host.endsWith(".hdslb.com") || parsed.host=="bilibili.com" || parsed.host.endsWith(".bilibili.com"))) {"分享封面地址无效"}
        val request=Request.Builder().url(parsed).header("Referer","https://www.bilibili.com/")
            .removeHeader("Cookie").removeHeader(FORCE_COOKIE_HEADER).build()
        val call=factory.newCall(request)
        val response=suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation {call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,failure:IOException) {
                    if(continuation.isActive)continuation.resumeWithException(failure)
                }
                override fun onResponse(call:Call,response:Response) {
                    continuation.resume(response) { _,value,_ ->value.close() }
                }
            })
        }
        try {
            response.use { reply ->
                checkOwner();check(reply.isSuccessful) {"Cover download failed: ${reply.code}"}
                val body=reply.body
                require(body.contentLength() <= 32L*1024*1024) {"封面文件过大"}
                // Child cancellation promptly interrupts a blocking body read; dispose after close.
                val monitor=CoroutineScope(currentCoroutineContext()).launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()}finally {call.cancel()}}
                try {
                    body.byteStream().use {input ->
                        val output=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                        while(true) {
                            checkOwner();val count=input.read(buffer);if(count<0)break
                            require(output.size().toLong()+count <= 32L*1024*1024) {"封面文件过大"}
                            output.write(buffer,0,count)
                        }
                        checkOwner();output.toByteArray()
                    }
                } finally {monitor.cancel()}
            }
        } finally {call.cancel()}
    }
}
