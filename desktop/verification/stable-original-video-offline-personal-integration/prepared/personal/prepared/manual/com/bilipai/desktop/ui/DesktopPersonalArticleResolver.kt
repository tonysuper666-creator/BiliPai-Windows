package com.bilipai.desktop.ui

import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The source HEAD request uses the same owner-tagged Root Call.Factory. Original pure
 * article/opus redirect policy remains selected source; this adapter owns only the Call.
 * Follow-redirect transport is inherited from Root, rather than constructing another client.
 * Pinned navigation policy deliberately always returns NativeArticle for a positive CV id. */
internal class DesktopPersonalArticleResolver(
    private val calls: Call.Factory,
    private val stillOwned: () -> Boolean,
) {
    suspend fun resolve(articleId: Long): ArticleNavigationTarget? {
        val fallback = buildArticleWebUrl(articleId) ?: return null
        fun owned() { if (!stillOwned()) throw CancellationException("History article owner retired") }
        currentCoroutineContext().ensureActive(); owned()
        val result = try {
            val request = Request.Builder().url(fallback).method("HEAD", null).build()
            val response = suspendCancellableCoroutine<Response> { continuation ->
                val call = calls.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, rejected, _ -> rejected.close() }
                    }
                })
            }
            response.use { resolveArticleNavigationTargetFromRedirect(articleId, it.header("Location"))
                ?: ArticleNavigationTarget.NativeArticle(articleId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ArticleNavigationTarget.NativeArticle(articleId) }
        currentCoroutineContext().ensureActive(); owned()
        return result
    }
}
