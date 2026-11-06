package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.android.purebilibili.data.model.response.RichTextNode
import com.android.purebilibili.data.model.response.DynamicDesc
import com.android.purebilibili.data.model.response.ReplyContent
import com.android.purebilibili.data.model.response.ReplyContentUrl
import com.android.purebilibili.feature.dynamic.components.RichTextContent
import com.android.purebilibili.feature.video.ui.components.RichCommentText
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey
import com.bilipai.desktop.appearance.*
import java.lang.reflect.Proxy
import kotlin.test.*

class DesktopTopicRoutingTest {
    @Test fun `message search links use existing URI decoding and retain other navigation`() {
        val routes = mutableListOf<BiliPaiNavKey>()
        val commands = commands(routes::add)
        val cases = listOf(
            "bilibili://search?keyword=%E8%80%81%E7%95%AA%E8%8C%84" to "老番茄",
            "https://search.bilibili.com/all?keyword=100%25+%E6%B5%8B%E8%AF%95" to "100% 测试",
            "bilibili://search?keyword=%20%E4%B8%AD%E6%96%87%20" to "中文",
            "bilibili://search?keyword=first&keyword=second" to "second",
            "bilibili://search?keyword=%20&query=other" to "other",
            "bilibili://browser/?url=bilibili%3A%2F%2Fsearch%3Fkeyword%3Dhello" to "hello",
        )
        cases.forEach { (url, keyword) ->
            desktopOriginalOpenMessageLink(url, commands, "search-test")
            val route = assertIs<BiliPaiNavKey.Search>(routes.singleOrNull(), url)
            routes.clear()
            assertEquals(keyword, route.keyword, url)
            assertEquals(DesktopShellPage.SEARCH, desktopShellPageOf(route))
        }
        listOf("bilibili://search?keyword=", "bilibili://search?keyword=%20", "bilibili://search?keyword=%zz")
            .forEach { desktopOriginalOpenMessageLink(it, commands, "search-test") }
        assertTrue(routes.isEmpty())
        desktopOriginalOpenMessageLink("https://example.com/path", commands, "search-test")
        assertEquals("https://example.com/path", assertIs<BiliPaiNavKey.Web>(routes.single()).url)
    }

    @Test fun `comment rich search link responds to a pointer click`() = assertRichSearchClick(comment = true)

    @Test fun `dynamic rich search link responds to a pointer click`() = assertRichSearchClick(comment = false)

    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    private fun assertRichSearchClick(comment: Boolean) {
        val routes = mutableListOf<BiliPaiNavKey>()
        val commands = commands(routes::add)
        val url = "bilibili://search?keyword=%E8%80%81%E7%95%AA%E8%8C%84"
        val emotes = object : DesktopDynamicEmotes {
            override fun snapshot() = emptyMap<String, String>()
            override fun currentSessionKey(): Any = "search-fixture"
            override suspend fun ensureLoaded() = snapshot()
        }
        val platform = Proxy.newProxyInstance(DesktopDynamicCardPlatform::class.java.classLoader,
            arrayOf(DesktopDynamicCardPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "getEmotes" -> emotes
                "isOwned" -> true
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SearchFixturePlatform"
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as DesktopDynamicCardPlatform
        val clicked = mutableListOf<String>()
        run {
            val scene = ImageComposeScene(600, 200, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings()) {
                    CompositionLocalProvider(LocalDesktopDynamicCardBindings provides platform) {
                        if (comment) {
                            RichCommentText(text = "link", fontSize = 18.sp, emoteMap = emptyMap(),
                                content = ReplyContent(message = "link", urls = mapOf("link" to
                                    ReplyContentUrl(title = "老番茄", appUrlSchema = url))),
                                onUrlClick = { clicked += it; desktopOriginalOpenMessageLink(it, commands, "comment") })
                        } else {
                            RichTextContent(desc = DynamicDesc(rich_text_nodes = listOf(
                                RichTextNode(type = "RICH_TEXT_NODE_TYPE_WEB", text = "老番茄", jump_url = url))),
                                onUserClick = {}, onTopicKeywordClick = { keyword ->
                                    clicked += keyword
                                    if (keyword.isNotBlank()) commands.push(BiliPaiNavKey.Search(keyword))
                                })
                        }
                    }
                }
            }
            try {
                var nanos = 0L
                fun settle() { repeat(5) { nanos += 40_000_000; scene.render(nanos).close() } }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                settle()
                val textNode = scene.semanticsOwners.asSequence().flatMap { nodes(it.unmergedRootSemanticsNode).asSequence() }
                    .first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("老番茄") } == true }
                val point = textNode.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, point)
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                settle()
                assertEquals(listOf(if (comment) url else "老番茄"), clicked, "actual rich text callback")
                val route = assertIs<BiliPaiNavKey.Search>(routes.singleOrNull(), if (comment) "comment" else "dynamic")
                assertEquals("老番茄", route.keyword)
                assertEquals(DesktopShellPage.SEARCH, desktopShellPageOf(route))
                routes.clear()
            } finally { scene.close() }
        }
    }

    @Test fun `original topic URL variants route to numeric detail inside client`() {
        val topics = mutableListOf<Long>(); val other = mutableListOf<String>()
        val navigation = navigation(topics, other)
        navigateCommunityUrl("https://www.bilibili.com/v/topic/detail/?topic_id=91", navigation)
        navigateCommunityUrl("https://www.bilibili.com/topic-detail/77", navigation)
        navigateCommunityUrl("//www.bilibili.com/v/topic/detail/?topicId=99", navigation)
        assertEquals(listOf(91L, 77L, 99L), topics); assertTrue(other.isEmpty())
    }

    @Test fun `existing video user dynamic article and live routes keep their targets`() {
        val topics = mutableListOf<Long>(); val other = mutableListOf<String>(); val navigation = navigation(topics, other)
        navigateCommunityUrl("https://www.bilibili.com/video/BV1234567890", navigation)
        navigateCommunityUrl("https://space.bilibili.com/23", navigation)
        navigateCommunityUrl("https://t.bilibili.com/789", navigation)
        navigateCommunityUrl("https://www.bilibili.com/read/cv31", navigation)
        navigateCommunityUrl("https://live.bilibili.com/57", navigation)
        assertTrue(topics.isEmpty()); assertEquals(listOf("video:BV1234567890", "user:23", "dynamic:789", "article:31", "live:57"), other)
    }

    @Test fun `original message aliases preserve dynamic and standalone comment parameters`() {
        val routes=mutableListOf<BiliPaiNavKey>()
        val commands = commands(routes::add)
        val navigation=CommunityNavigation({},{},{},{},{},{},{},
            onMessageLink={ desktopOriginalOpenMessageLink(it, commands, "message-test") })
        navigateCommunityUrl("https://t.bilibili.com/123?comment_root_id=701&comment_secondary_id=703",navigation)
        navigateCommunityUrl("https://www.bilibili.com/opus/123#reply701",navigation)
        navigateCommunityUrl("bilibili://comment/detail/17/123/701?reply_id=703",navigation)
        navigateCommunityUrl("bilibili://browser/?url=https%3A%2F%2Ft.bilibili.com%2F123%3Froot_reply_id%3D701%26target_id%3D703",navigation)
        navigateCommunityUrl("https://www.bilibili.com/h5/comment/sub?oid=123&pageType=17&root=701&comment_id=703",navigation)
        assertEquals(5, routes.size)
        for (index in routes.indices) {
            val comment = assertIs<BiliPaiNavKey.CommentDetail>(routes[index])
            assertEquals(listOf(123L,701L,if(index == 1) 0L else 703L,17L),
                listOf(comment.oid,comment.rootId,comment.targetId,comment.type.toLong()))
            assertEquals("bilibili://following/detail/123",comment.enterUri)
        }
    }

    @Test fun `video message links retain both reply ids through the original typed route`() {
        val routes=mutableListOf<BiliPaiNavKey>()
        val commands=commands(routes::add)
        val navigation=CommunityNavigation({},{},{},{},{},{},{},
            onMessageLink={ desktopOriginalOpenMessageLink(it,commands,"message-test") })
        navigateCommunityUrl("https://www.bilibili.com/video/BV1234567890?comment_root_id=701&comment_secondary_id=703",navigation)
        val video=assertIs<BiliPaiNavKey.VideoDetail>(routes.single())
        assertEquals("BV1234567890",video.bvid)
        assertEquals(701L,video.commentRootRpid)
        assertEquals(703L,video.commentTargetRpid)
        assertEquals("message-test",video.sourceRoute)
    }

    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun `real rich topic pointer prefers original RID while mention still routes to UP`() {
        for (style in AppUiStyle.entries) {
            val topics = mutableListOf<Long>(); val other = mutableListOf<String>(); val keywords = mutableListOf<String>()
            val navigation = navigation(topics, other, keywords)
            val scene = ImageComposeScene(600, 400, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style)) {
                    Column {
                        CommunityDynamicText("", listOf(
                            RichTextNode(type = "RICH_TEXT_NODE_TYPE_TOPIC", text = "真实话题", rid = "91",
                                jump_url = "https://www.bilibili.com/v/topic/detail/?topic_id=99"),
                            RichTextNode(type = "RICH_TEXT_NODE_TYPE_AT", text = "真实 UP", rid = "23"),
                            RichTextNode(type = "RICH_TEXT_NODE_TYPE_TOPIC", text = "#无编号话题#")), navigation)
                    }
                }
            }
            try {
                var nanos = 0L
                fun settle() { repeat(5) { nanos += 40_000_000; scene.render(nanos).close() } }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                fun click(label: String) {
                    settle()
                    val button = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode).asSequence() }
                        .first { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                            nodes(node).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true } }
                    val point = button.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                    scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                    settle()
                }
                click("真实话题"); click("真实 UP"); click("#无编号话题#")
                assertEquals(listOf(91L), topics, style.name); assertEquals(listOf("user:23"), other, style.name)
                assertEquals(listOf("无编号话题"), keywords, style.name)
            } finally { scene.close() }
        }
    }

    private fun navigation(topics: MutableList<Long>, other: MutableList<String>, keywords: MutableList<String> = mutableListOf()): CommunityNavigation {
        val commands=commands { key -> other += when(key) {
            is BiliPaiNavKey.VideoDetail -> "video:${key.bvid}"
            is BiliPaiNavKey.Space -> "user:${key.mid}"
            is BiliPaiNavKey.ArticleDetail -> "article:${key.articleId}"
            is BiliPaiNavKey.Live -> "live:${key.roomId}"
            is BiliPaiNavKey.DynamicDetail -> "dynamic:${key.dynamicId}"
            else -> error("Unexpected message target: $key")
        } }
        return CommunityNavigation(
            { other += "video:${it.bvid}" }, { other += "user:$it" }, { other += "article:$it" }, {},
            { other += "live:$it" }, { other += "bangumi:$it" }, { other += "dynamic:$it" }, { topics += it }, { keywords += it },
            onMessageLink={ desktopOriginalOpenMessageLink(it,commands,"message-test") })
    }

    private fun commands(record: (BiliPaiNavKey) -> Unit) = object : DesktopOriginalRootRouteCommands {
        override fun push(key: BiliPaiNavKey): Boolean { record(key);return true }
        override fun video(key: BiliPaiNavKey.VideoDetail) { record(key) }
        override fun videoRoute(route: String, sourceRoute: String) {
            val key=assertIs<BiliPaiNavKey.VideoDetail>(legacyRouteToBiliPaiNavKey(route))
            record(key.copy(sourceRoute=sourceRoute))
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
