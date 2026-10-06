package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicCreateFeedRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.security.MessageDigest

/** Only the explicit video_share Main case. Exact original requests terminate
 * in memory; all other mutations remain subject to the original rejecting replay. */
internal class WindowsVideoDynamicShareReplay(private val aid: Long) {
    companion object {
        const val DRAFT = "LOCAL video share draft"
        const val RETRY_ERROR = "LOCAL dynamic share retry"
        const val PATH = "/x/dynamic/feed/create/dyn"
    }
    private val json = Json { ignoreUnknownKeys = false }
    private val payloads = mutableListOf<JsonObject>()
    @Synchronized fun count(): Int = payloads.size
    @Synchronized fun respond(request: Request, stillOwned: () -> Boolean): Response? {
        if (request.url.encodedPath != PATH) return null
        check(stillOwned())
        val url = request.url
        require(request.method == "POST" && url.scheme == "https" && url.host == "api.bilibili.com" &&
            url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.encodedFragment == null &&
            url.queryParameterNames == setOf("csrf", "platform", "x-bili-device-req-json", "x-bili-web-req-json") &&
            url.queryParameterValues("csrf") == listOf("LOCAL-COMPOSER-NOT-A-REAL-CSRF") &&
            url.queryParameterValues("platform") == listOf("web") &&
            url.queryParameterValues("x-bili-device-req-json") == listOf("{\"platform\":\"web\",\"device\":\"pc\"}") &&
            url.queryParameterValues("x-bili-web-req-json") == listOf("{\"spm_id\":\"333.999\"}"))
        val body = requireNotNull(request.body)
        require(body.contentLength() in 1..16_384 && body.contentType()?.type == "application" &&
            body.contentType()?.subtype == "json")
        val buffer = Buffer(); body.writeTo(buffer); val bytes = buffer.readByteArray()
        require(bytes.size <= 16_384)
        val decoded = json.decodeFromString<DynamicCreateFeedRequest>(bytes.toString(Charsets.UTF_8))
        val dyn = decoded.dyn_req
        val resource = requireNotNull(decoded.web_repost_src).revs_id
        require(dyn.scene == 5 && resource.dyn_type == 8 && resource.rid == aid && aid > 0L)
        require(dyn.pics == null && dyn.attach_card == null && dyn.option == null && dyn.topic == null &&
            dyn.content.title == null && dyn.content.contents.size == 1)
        val text = dyn.content.contents.single()
        require(text.raw_text == DRAFT && text.type == 1 && text.biz_id.isEmpty())
        require(dyn.upload_id.isNotBlank() && dyn.upload_id.length <= 128 &&
            dyn.meta.app_meta.from == "create.dynamic.web" && dyn.meta.app_meta.mobi_app == "web")
        check(stillOwned())
        require(payloads.size < 2) { "Only one failed attempt and one explicit retry are permitted" }
        val responseCode = if (payloads.isEmpty()) -1 else 0
        payloads += buildJsonObject {
            put("method", "POST"); put("host", url.host); put("path", PATH)
            put("scene", dyn.scene); put("dynType", resource.dyn_type); put("rid", resource.rid)
            put("text", text.raw_text); put("csrfIsSynthetic", true)
            put("payloadSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            put("responseCode", responseCode); put("terminatedInMemory", true); put("remoteMutationSent", false)
        }
        val response = if (responseCode == 0) """{"code":0,"data":{"dyn_id_str":"990000027"}}"""
            else """{"code":-1,"message":"$RETRY_ERROR"}"""
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
            .message("LOCAL original video dynamic share only")
            .body(response.toResponseBody("application/json".toMediaType())).build()
    }
    @Synchronized fun receipt(requireComplete: Boolean = true): JsonObject {
        if (requireComplete) check(payloads.size == 2 && payloads[0]["responseCode"]?.jsonPrimitive?.int == -1 &&
            payloads[1]["responseCode"]?.jsonPrimitive?.int == 0)
        return buildJsonObject {
            put("schema", 1); put("actualOriginalVideoDynamicProtocolConsumed", payloads.isNotEmpty())
            put("syntheticResponsesOnly", true); put("remoteMutationSent", false)
            put("otherMutationPermitted", false); put("realCredentialsUsed", false)
            put("expectedAid", aid); put("payloads", JsonArray(payloads.toList()))
        }
    }
}
