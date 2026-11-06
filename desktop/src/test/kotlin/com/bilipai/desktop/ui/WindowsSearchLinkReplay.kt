package com.bilipai.desktop.ui

import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Only a guest GET transport script for the existing private Main. The bytes
 * reach the installed original REST comment and dynamic-detail parsers; no
 * route, VM, account or comment state is constructed or written here. */
internal class WindowsSearchLinkReplay : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val reads = CopyOnWriteArrayList<JsonObject>()

    companion object {
        const val AID = 170001L
        const val ROOT_SEARCH = 92001L
        const val ROOT_DYNAMIC = 92002L
        const val DYNAMIC_ID = "990000000000000001"
        const val COMMENT_KEYWORD = "老番茄"
        const val DYNAMIC_LINK_TITLE = "验收动态"
        const val DYNAMIC_TEXT = "动态正文验收"
        const val DYNAMIC_TOPIC_KEYWORD = "中文话题验收"
        const val DYNAMIC_URL = "https://t.bilibili.com/$DYNAMIC_ID"
        // Observed comment wire shape: empty url/pc_url, keyword only in the app schema.
        const val SEARCH_SCHEMA = "bilibili://search?from=appcommentline_search" +
            "&search_query=%E8%80%81%E7%95%AA%E8%8C%84&keyword=%E8%80%81%E7%95%AA%E8%8C%84&seid=4815162342108"
        val REPLY_PATHS = setOf("/x/v2/reply/wbi/main", "/x/v2/reply/main")
        val DYNAMIC_PATHS = setOf("/x/polymer/web-dynamic/v1/detail", "/x/polymer/web-dynamic/desktop/v1/detail")

        private fun reply(rpid: Long, message: String, token: String, link: JsonObject) = buildJsonObject {
            put("rpid", rpid); put("root", 0); put("parent", 0); put("oid", AID); put("type", 1)
            put("mid", rpid); put("count", 0); put("rcount", 0); put("like", 1); put("ctime", 1_700_000_000L + rpid % 100)
            putJsonObject("member") { put("mid", rpid.toString()); put("uname", "链接验收$rpid"); put("avatar", "")
                putJsonObject("level_info") { put("current_level", 3) } }
            putJsonObject("content") { put("message", message); putJsonObject("jump_url") { put(token, link) } }
            putJsonArray("replies") {}
        }

        fun mainReplies(): String = buildJsonObject {
            put("code", 0); put("message", "0")
            putJsonObject("data") {
                putJsonObject("cursor") { put("is_begin", true); put("is_end", true); put("all_count", 2); put("next", 0); put("prev", 0) }
                putJsonObject("page") { put("count", 2); put("num", 1); put("size", 20) }
                putJsonArray("top_replies") {}
                putJsonArray("replies") {
                    add(reply(ROOT_SEARCH, COMMENT_KEYWORD, COMMENT_KEYWORD, buildJsonObject {
                        put("title", COMMENT_KEYWORD); put("url", ""); put("pc_url", "")
                        put("app_url_schema", SEARCH_SCHEMA); put("prefix_icon", "")
                        putJsonObject("extra") { put("is_word_search", true) }
                    }))
                    add(reply(ROOT_DYNAMIC, DYNAMIC_URL, DYNAMIC_URL, buildJsonObject {
                        put("title", DYNAMIC_LINK_TITLE); put("url", DYNAMIC_URL); put("pc_url", DYNAMIC_URL)
                        put("app_url_schema", ""); put("prefix_icon", "")
                    }))
                }
            }
        }.toString()

        const val EMPTY_REPLIES = """{"code":0,"data":{"replies":[],"top_replies":[],"cursor":{"is_begin":true,"is_end":true,"all_count":0,"next":0,"prev":0},"page":{"count":0,"num":1,"size":20}}}"""

        fun dynamicDetail(): String = buildJsonObject {
            put("code", 0); put("message", "0")
            putJsonObject("data") { putJsonObject("item") {
                put("id_str", DYNAMIC_ID); put("type", "DYNAMIC_TYPE_WORD"); put("visible", true)
                putJsonObject("basic") { put("comment_id_str", DYNAMIC_ID); put("comment_type", 17) }
                putJsonObject("modules") {
                    putJsonObject("module_author") { put("mid", 92003); put("name", "动态验收作者"); put("face", "")
                        put("pub_time", "本地验收"); put("pub_ts", 1_700_000_000L) }
                    putJsonObject("module_dynamic") {
                        putJsonObject("desc") { put("text", DYNAMIC_TEXT); putJsonArray("rich_text_nodes") {
                            add(buildJsonObject { put("type", "RICH_TEXT_NODE_TYPE_TEXT"); put("text", DYNAMIC_TEXT); put("orig_text", DYNAMIC_TEXT) })
                        } }
                        // Original topic without an id: the card offers only its keyword.
                        putJsonObject("topic") { put("id", 0); put("name", "#$DYNAMIC_TOPIC_KEYWORD#") }
                    }
                    putJsonObject("module_stat") {
                        putJsonObject("comment") { put("count", 0) }; putJsonObject("forward") { put("count", 0) }
                        putJsonObject("like") { put("count", 0); put("status", false) }
                    }
                }
            } }
        }.toString()
    }

    fun intercept(chain: Interceptor.Chain, stillOwned: () -> Boolean): Response? {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        val replyRead = url.host == "api.bilibili.com" && path in REPLY_PATHS
        val dynamicRead = url.host == "api.bilibili.com" && path in DYNAMIC_PATHS
        if (!replyRead && !dynamicRead) return null
        check(alive.get() && stillOwned()) { "Owned search-link replay retired" }
        require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty())
        require(request.method == "GET" && request.header("authorization") == null) { "This fixture is guest GET-only" }
        val body = if (replyRead) {
            val video = url.queryParameter("oid")?.toLongOrNull() == AID && url.queryParameter("type") == "1"
            reads += buildJsonObject { put("kind", "reply"); put("path", path); put("method", "GET")
                put("oid", url.queryParameter("oid").orEmpty()); put("type", url.queryParameter("type").orEmpty()); put("videoRoots", video) }
            if (video) mainReplies() else EMPTY_REPLIES
        } else {
            require(url.queryParameter("id") == DYNAMIC_ID || url.queryParameter("rid") == DYNAMIC_ID) { "Dynamic detail request is not the fixture comment target" }
            reads += buildJsonObject { put("kind", "dynamicDetail"); put("path", path); put("method", "GET"); put("id", DYNAMIC_ID) }
            dynamicDetail()
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Owned search-link replay")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    fun receipt(): JsonObject {
        check(reads.any { it["videoRoots"]?.jsonPrimitive?.booleanOrNull == true }) { "Original video comment read not observed" }
        check(reads.any { it["kind"]?.jsonPrimitive?.content == "dynamicDetail" }) { "Original dynamic detail read not observed" }
        return buildJsonObject {
            put("syntheticGuestTransportOnly", true); put("commentSearchSchema", SEARCH_SCHEMA)
            put("commentKeyword", COMMENT_KEYWORD); put("dynamicId", DYNAMIC_ID); put("dynamicTopicKeyword", DYNAMIC_TOPIC_KEYWORD)
            put("accountMutationSubmitted", false); put("fixtureRoutePushed", false)
            put("readRequests", JsonArray(reads.toList()))
        }
    }
    override fun close() { alive.set(false) }
}
