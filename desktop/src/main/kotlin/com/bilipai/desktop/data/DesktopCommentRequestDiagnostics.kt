package com.bilipai.desktop.data

import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.SocketTimeoutException
import retrofit2.HttpException

private fun commentApiName(path: String): String = when (path) {
    "/bilibili.main.community.reply.v1.Reply/MainList" -> "Reply/MainList"
    "/bilibili.main.community.reply.v1.Reply/DetailList" -> "Reply/DetailList"
    "/bilibili.main.community.reply.v1.Reply/DialogList" -> "Reply/DialogList"
    "/x/v2/reply/wbi/main", "/x/v2/reply/main", "/x/v2/reply",
    "/x/v2/reply/reply", "/x/v2/reply/count" -> path
    else -> "Reply/Other"
}

internal suspend fun <T> desktopCommentRestRequest(
    path: String,
    assertOwner: () -> Unit,
    code: (T) -> Int,
    log: (String) -> Unit = { Logger.w("CommentRequest", it) },
    request: suspend () -> T,
): T {
    val response = desktopCommentGrpcRequest(path, log, assertOwner, request)
    currentCoroutineContext().ensureActive()
    assertOwner()
    val responseCode = code(response)
    if (responseCode != 0) {
        log("api=${commentApiName(path)} code=$responseCode message=comment API rejected request")
    }
    // Keep the original response, including its fallback/paging behavior.
    return response
}

/** No request/body/header or raw exception text enters the diagnostic sink. */
internal suspend fun <T> desktopCommentGrpcRequest(
    path: String,
    log: (String) -> Unit = { Logger.w("CommentRequest", it) },
    assertOwner: () -> Unit = {},
    request: suspend () -> T,
): T = try {
    request()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    assertOwner()
    // Paths are constants in the retained original protocol. Never echo an
    // arbitrary path (which could contain query parameters).
    val api = commentApiName(path)
    val status = Regex("^请求失败：HTTP ([0-9]{3})，gRPC (-?[0-9]{1,10}|null)(?:，|$)")
        .find(failure.message.orEmpty())
    // Use stable messages rather than the server's untrusted free text, which
    // can include request parameters, account identifiers or credentials.
    val code: String
    val message: String
    when {
        status != null -> {
            code = "http=${status.groupValues[1]} grpc=${status.groupValues[2]}"
            message = "server rejected comment request"
        }
        failure is BiliApiException -> {
            code = failure.apiCode.toString()
            message = "comment API rejected request"
        }
        failure is HttpException -> {
            code = "http=${failure.code()}"
            message = "server rejected comment request"
        }
        failure is SocketTimeoutException -> {
            code = "unavailable"
            message = "comment request timed out"
        }
        failure is IOException -> {
            code = "unavailable"
            message = "comment transport failed"
        }
        else -> {
            code = "unavailable"
            message = "comment request failed"
        }
    }
    log("api=$api code=$code message=$message")
    throw failure
}
