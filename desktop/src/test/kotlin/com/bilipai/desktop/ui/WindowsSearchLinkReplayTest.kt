package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicDetailResponse
import com.android.purebilibili.data.model.response.ReplyResponse
import com.android.purebilibili.feature.video.ui.components.resolveReplyContentUrlNavigationUrl
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.*

/** Offline inputs of the actual-host search-link UI proof: the guest wire bytes go through
 * the installed original parsers and the production link dispatcher the video host calls. */
class WindowsSearchLinkReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `video comment wire keeps empty url and its search schema reaches actual Search`() {
        val replies = assertNotNull(json.decodeFromString<ReplyResponse>(WindowsSearchLinkReplay.mainReplies()).data).replies.orEmpty()
        assertEquals(listOf(WindowsSearchLinkReplay.ROOT_SEARCH, WindowsSearchLinkReplay.ROOT_DYNAMIC), replies.map { it.rpid })
        val search = replies.first()
        assertEquals(WindowsSearchLinkReplay.COMMENT_KEYWORD, search.content.message)
        val (token, link) = search.content.urls.entries.single()
        assertEquals("", link.url)
        assertEquals(WindowsSearchLinkReplay.SEARCH_SCHEMA, link.appUrlSchema)
        val target = resolveReplyContentUrlNavigationUrl(token, link)
        assertEquals(WindowsSearchLinkReplay.SEARCH_SCHEMA, target)
        val routes = mutableListOf<BiliPaiNavKey>()
        desktopOriginalOpenMessageLink(target, commands(routes::add), "video")
        assertEquals(listOf<BiliPaiNavKey>(BiliPaiNavKey.Search(WindowsSearchLinkReplay.COMMENT_KEYWORD)), routes)
        assertEquals(DesktopShellPage.SEARCH, desktopShellPageOf(routes.single()))
    }

    @Test fun `video comment dynamic link still opens the dynamic detail host`() {
        val replies = assertNotNull(json.decodeFromString<ReplyResponse>(WindowsSearchLinkReplay.mainReplies()).data).replies.orEmpty()
        val (token, link) = replies.single { it.rpid == WindowsSearchLinkReplay.ROOT_DYNAMIC }.content.urls.entries.single()
        assertEquals(WindowsSearchLinkReplay.DYNAMIC_LINK_TITLE, link.title)
        val target = resolveReplyContentUrlNavigationUrl(token, link)
        assertEquals(WindowsSearchLinkReplay.DYNAMIC_URL, target)
        val routes = mutableListOf<BiliPaiNavKey>()
        desktopOriginalOpenMessageLink(target, commands(routes::add), "video")
        assertEquals(listOf<BiliPaiNavKey>(BiliPaiNavKey.DynamicDetail(WindowsSearchLinkReplay.DYNAMIC_ID)), routes)
    }

    @Test fun `dynamic detail wire offers only the Chinese topic keyword`() {
        val item = assertNotNull(json.decodeFromString<DynamicDetailResponse>(WindowsSearchLinkReplay.dynamicDetail()).data?.item)
        assertEquals(WindowsSearchLinkReplay.DYNAMIC_ID, item.id_str)
        val content = assertNotNull(item.modules.module_dynamic)
        assertEquals(WindowsSearchLinkReplay.DYNAMIC_TEXT, content.desc?.text)
        val topic = assertNotNull(content.topic)
        assertEquals(0L, topic.id)
        assertEquals("#${WindowsSearchLinkReplay.DYNAMIC_TOPIC_KEYWORD}#", topic.name)
    }

    @Test fun `transport script answers only guest GET reads of its own targets`() {
        WindowsSearchLinkReplay().use { replay ->
            var owned = true
            val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                replay.intercept(chain) { owned } ?: error("Unmapped ${chain.request().url.encodedPath}")
            }).build()
            fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
            assertEquals(WindowsSearchLinkReplay.mainReplies(),
                get("https://api.bilibili.com/x/v2/reply/wbi/main?oid=${WindowsSearchLinkReplay.AID}&type=1&mode=3"))
            assertEquals(WindowsSearchLinkReplay.EMPTY_REPLIES,
                get("https://api.bilibili.com/x/v2/reply/main?oid=${WindowsSearchLinkReplay.DYNAMIC_ID}&type=17"))
            assertEquals(WindowsSearchLinkReplay.dynamicDetail(),
                get("https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=${WindowsSearchLinkReplay.DYNAMIC_ID}"))
            assertFailsWith<IllegalArgumentException> {
                get("https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=1")
            }
            assertFailsWith<IllegalArgumentException> {
                client.newCall(Request.Builder().url("https://api.bilibili.com/x/v2/reply/main?oid=1&type=1")
                    .post("".toRequestBody()).build()).execute().close()
            }
            assertFailsWith<IllegalArgumentException> {
                client.newCall(Request.Builder().url("https://api.bilibili.com/x/v2/reply/main?oid=1&type=1")
                    .header("authorization", "x").build()).execute().close()
            }
            val receipt = replay.receipt()
            assertEquals("false", receipt["fixtureRoutePushed"].toString())
            assertEquals("false", receipt["accountMutationSubmitted"].toString())
            owned = false
            assertFailsWith<IllegalStateException> {
                get("https://api.bilibili.com/x/v2/reply/main?oid=${WindowsSearchLinkReplay.AID}&type=1")
            }
        }
    }

    private fun commands(record: (BiliPaiNavKey) -> Unit) = object : DesktopOriginalRootRouteCommands {
        override fun push(key: BiliPaiNavKey): Boolean { record(key); return true }
        override fun video(key: BiliPaiNavKey.VideoDetail) { record(key) }
        override fun videoRoute(route: String, sourceRoute: String) {
            record(assertIs<BiliPaiNavKey.VideoDetail>(legacyRouteToBiliPaiNavKey(route)).copy(sourceRoute = sourceRoute))
        }
        override fun back(): Boolean = error("Unexpected back")
        override fun articleBack(article: BiliPaiNavKey.ArticleDetail, useSharedReturn: Boolean): Boolean = error("Unexpected back")
        override fun containsEntry(key: BiliPaiNavKey): Boolean = error("Unexpected entry lookup")
        override fun home(): Boolean = error("Unexpected Home")
        override fun replaceVideoDetail(current: BiliPaiNavKey.VideoDetail, bvid: String, cid: Long,
            cover: String, resumePositionMs: Long): Boolean = error("Unexpected video replacement")
        override fun homeFromVideo(current: BiliPaiNavKey.VideoDetail): Boolean = error("Unexpected Home")
        override fun markVideoReturning(current: BiliPaiNavKey.VideoDetail): Boolean = error("Unexpected video return")
        override fun clearVideoReturning(): Boolean = error("Unexpected video return")
    }
}
