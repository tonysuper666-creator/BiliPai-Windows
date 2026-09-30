package com.bilipai.desktop.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Transport-only binding: original ProtoWire/framing/status logic is untouched.
 * The continuation remains cancellable throughout bounded body consumption. */
internal object DesktopDynamicGrpcCalls {
    suspend fun <T> execute(client:OkHttpClient,request:Request,stillOwned:()->Boolean,decode:(Response)->T):T =
        suspendCancellableCoroutine { continuation ->
            if(!stillOwned()){continuation.cancel(kotlinx.coroutines.CancellationException("Dynamic share owner retired"));return@suspendCancellableCoroutine}
            val call=client.newCall(request)
            continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,error:IOException){if(continuation.isActive)continuation.resumeWithException(error)}
                override fun onResponse(call:Call,response:Response){
                    try{
                        response.use {
                            if(!stillOwned()||!continuation.isActive)throw kotlinx.coroutines.CancellationException("Dynamic share owner retired")
                            val result=decode(it)
                            if(!stillOwned()||!continuation.isActive)throw kotlinx.coroutines.CancellationException("Dynamic share owner retired")
                            continuation.resume(result)
                        }
                    }catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
                }
            })
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
