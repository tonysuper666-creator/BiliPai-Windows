package com.android.purebilibili.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Thin lifetime seam for the original raw response: the injected call factory
 * is Ops' existing guestWeb callFactory, never another HTTP client or account. */
internal suspend fun awaitDesktopCommentFraudRaw(call: Call, checkOwned: () -> Unit): String? =
    suspendCancellableCoroutine { continuation ->
        try { checkOwned() }
        catch (retired: CancellationException) { call.cancel(); continuation.cancel(retired); return@suspendCancellableCoroutine }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (!continuation.isActive) return
                try { checkOwned(); continuation.resume(null) }
                catch (retired: CancellationException) { continuation.cancel(retired) }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        checkOwned()
                        val raw = if (response.isSuccessful) response.body?.string() else null
                        checkOwned()
                        if (continuation.isActive) continuation.resume(raw)
                    } catch (retired: CancellationException) { continuation.cancel(retired) }
                    catch (failure: Exception) {
                        if (!continuation.isActive) return
                        try { checkOwned(); continuation.resume(null) }
                        catch (retired: CancellationException) { continuation.cancel(retired) }
                    }
                }
            }
        })
    }
