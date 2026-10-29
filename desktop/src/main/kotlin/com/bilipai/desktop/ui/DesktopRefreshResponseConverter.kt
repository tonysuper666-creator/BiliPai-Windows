package com.bilipai.desktop.ui

import java.lang.reflect.Type
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.Buffer
import okio.buffer
import retrofit2.Converter
import retrofit2.Retrofit

/** Preserve the initiating operation through Retrofit's asynchronous response decoding. */
internal class DesktopRefreshResponseBody(
    private val delegate: ResponseBody,
    val failed: () -> Unit,
) : ResponseBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    private val watched = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long = try { super.read(sink, byteCount) }
        catch (failure: java.io.IOException) {
            if (failure.message != "Canceled" && failure.message != "Request owner retired") failed()
            throw failure
        }
    }.buffer()
    override fun source(): BufferedSource = watched
}

internal class DesktopRefreshResponseConverter(private val delegate: Converter.Factory) : Converter.Factory() {
    override fun responseBodyConverter(type: Type, annotations: Array<Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? {
        val converter = delegate.responseBodyConverter(type, annotations, retrofit) ?: return null
        return Converter<ResponseBody, Any?> { body ->
            try { converter.convert(body) }
            catch (failure: Exception) {
                if (failure !is java.util.concurrent.CancellationException) DesktopRefreshFailureWatch.decodeFailed()
                throw failure
            }
        }
    }
    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<Annotation>, methodAnnotations: Array<Annotation>, retrofit: Retrofit) =
        delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
    override fun stringConverter(type: Type, annotations: Array<Annotation>, retrofit: Retrofit) =
        delegate.stringConverter(type, annotations, retrofit)
}
