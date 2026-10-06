package com.bilipai.desktop.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink

/** Existing blocked-UP one-shot pattern, scoped to the live Send service only.
 * newBuilder retains the original CookieJar, dispatcher, pool and owner interceptors. */
internal fun desktopLiveSendTransport(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .apply {
        // First so every downstream interceptor and the actual HTTP follow-up layer
        // see the same one-shot request, including memory-only test transports.
        interceptors().add(0, Interceptor { chain ->
            val request = chain.request()
            val body = request.body
            val once = if (request.method == "POST" && body != null)
                request.newBuilder().method("POST", object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                }).build() else request
            chain.proceed(once)
        })
    }.build()
