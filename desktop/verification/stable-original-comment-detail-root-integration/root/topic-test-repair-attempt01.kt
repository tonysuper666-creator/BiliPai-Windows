package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.RichTextNode
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey
import com.bilipai.desktop.appearance.*
import kotlin.test.*

class DesktopTopicRoutingTest {
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
        assertEquals(BiliPaiNavKey.DynamicDetail("123",701,703), routes[0])
        assertEquals(BiliPaiNavKey.DynamicDetail("123",701,0), routes[1])
        assertEquals(BiliPaiNavKey.DynamicDetail("123",701,703), routes[3])
        for (index in listOf(2,4)) {
            val comment = assertIs<BiliPaiNavKey.CommentDetail>(routes[index])
            assertEquals(listOf(123L,701L,703L,17L),
                listOf(comment.oid,comment.rootId,comment.targetId,comment.type.toLong()))
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
