package com.bilipai.desktop.ui

import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Only the new opt-in Main scope. All five original Like/unlike submissions
 * terminate in this memory response. No socket/client/profile is constructed.
 * The unchanged composer replay still rejects every mutation in its own mode. */
internal class WindowsBrandFeedbackReplay(private val aid: Long) {
    private val observed = mutableListOf<Int>()
    @Synchronized fun respond(request: Request, stillOwned: () -> Boolean): Response? {
        if (request.url.encodedPath != "/x/web-interface/archive/like") return null
        check(stillOwned())
        val url = request.url
        require(request.method == "POST" && url.scheme == "https" && url.host == "api.bilibili.com" &&
            url.port == 443 && url.encodedQuery == null && url.encodedFragment == null &&
            url.username.isEmpty() && url.password.isEmpty())
        val body = request.body as? FormBody ?: error("Original Like must be form encoded")
        require(body.size == 3 && (0 until body.size).map(body::name).toSet() == setOf("aid", "like", "csrf"))
        fun field(name: String) = (0 until body.size).single { body.name(it) == name }.let(body::value)
        require(field("aid").toLongOrNull() == aid && field("csrf") == "LOCAL-COMPOSER-NOT-A-REAL-CSRF")
        val expected = listOf(1, 2, 1, 2, 1)
        val action = field("like").toIntOrNull()
        require(observed.size < expected.size && action == expected[observed.size])
        check(stillOwned())
        observed += requireNotNull(action)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("LOCAL original Like only")
            .body("""{"code":0,"message":"0","ttl":1}""".toResponseBody("application/json".toMediaType())).build()
    }
    @Synchronized fun receipt(requireComplete: Boolean = true): JsonObject {
        if (requireComplete) check(observed == listOf(1, 2, 1, 2, 1))
        return buildJsonObject {
            put("actualOriginalLikeProtocolConsumed", observed.isNotEmpty())
            put("syntheticResponsesOnly", true); put("remoteMutationSent", false)
            put("otherMutationPermitted", false); put("realCredentialsUsed", false)
            put("actions", JsonArray(observed.map(::JsonPrimitive)))
        }
    }
}
