package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.MobileFeedItem
import com.android.purebilibili.feature.home.HomeVideoClickRequest
import com.android.purebilibili.feature.home.HomeVideoClickSource
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class DesktopHomeNativeCardNavigationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `mobile card_goto episode enters real Root bangumi key without AV metadata`() {
        val item = json.decodeFromString<MobileFeedItem>(
            """{"goto":"av","card_goto":"bangumi","param":"123","player_args":{"aid":999,"cid":888}}""").toVideoItem()
        val commands = Commands()
        desktopOriginalRootHomeNavigation(commands, null, null).onVideoClick(HomeVideoClickRequest(
            bvid = item.bvid, dynamicId = item.dynamicId, episodeId = item.episodeId,
            cid = item.cid, source = HomeVideoClickSource.GRID))
        val target = assertIs<BiliPaiNavKey.BangumiDetail>(commands.pushed.single())
        assertEquals(123L, target.epId)
        assertEquals(0L, target.seasonId)
        assertTrue(commands.videos.isEmpty())
    }

    @Test fun `picture conversion enters real Root dynamic key without AV playback`() {
        val item = json.decodeFromString<MobileFeedItem>(
            """{"card_goto":"picture","uri":"https://t.bilibili.com/456","duration":60}""").toVideoItem()
        val commands = Commands()
        desktopOriginalRootHomeNavigation(commands, null, null).onVideoClick(HomeVideoClickRequest(
            bvid = item.bvid, dynamicId = item.dynamicId, episodeId = item.episodeId, cid = item.cid))
        assertEquals(BiliPaiNavKey.DynamicDetail("456"), commands.pushed.single())
        assertTrue(commands.videos.isEmpty())
    }

    @Test fun `ordinary video retains Root CID cover portrait and return source`() {
        val commands = Commands()
        desktopOriginalRootHomeNavigation(commands, null, null).onVideoClick(HomeVideoClickRequest(
            bvid = " BV1234567890 ", cid = 87L, coverUrl = "cover", isVerticalVideo = true,
            source = HomeVideoClickSource.PREVIEW, sourceRoute = "home?category=FOLLOW"))
        val target = commands.videos.single()
        assertEquals("BV1234567890", target.bvid)
        assertEquals(87L, target.cid)
        assertEquals("cover", target.coverUrl)
        assertTrue(target.autoPortrait && target.initialVertical)
        assertEquals("home?category=FOLLOW", target.sourceRoute)
        assertTrue(commands.pushed.isEmpty())
    }

    @Test fun `episode identity takes precedence over an incidental BV field`() {
        val commands = Commands()
        desktopOriginalRootHomeNavigation(commands, null, null).onVideoClick(
            HomeVideoClickRequest(bvid = "BV1234567890", cid = 99, episodeId = 123L))
        assertEquals(123L, assertIs<BiliPaiNavKey.BangumiDetail>(commands.pushed.single()).epId)
        assertTrue(commands.videos.isEmpty())
    }

    @Test fun `malformed native identities never fall through to the AV player`() {
        val commands = Commands()
        val navigation = desktopOriginalRootHomeNavigation(commands, null, null)
        for (identity in listOf("bangumi:bad", "dynamic:bad", "")) {
            navigation.onVideoClick(HomeVideoClickRequest(identity))
        }
        assertTrue(commands.pushed.isEmpty() && commands.videos.isEmpty())
    }

    @Test fun `real BV card with incidental dynamic metadata keeps Root video precedence`() {
        val commands = Commands()
        desktopOriginalRootHomeNavigation(commands, null, null).onVideoClick(
            HomeVideoClickRequest("BV1234567890", dynamicId = "123", cid = 87L))
        assertEquals("BV1234567890", commands.videos.single().bvid)
        assertEquals(87L, commands.videos.single().cid)
        assertTrue(commands.pushed.isEmpty())
    }

    private class Commands : DesktopOriginalRootRouteCommands {
        val pushed = mutableListOf<BiliPaiNavKey>()
        val videos = mutableListOf<BiliPaiNavKey.VideoDetail>()
        override fun push(key: BiliPaiNavKey): Boolean { pushed += key; return true }
        override fun video(key: BiliPaiNavKey.VideoDetail) { videos += key }
        override fun videoRoute(route: String, sourceRoute: String) = error("Unexpected legacy route")
        override fun back(): Boolean = error("Unexpected back")
        override fun articleBack(article: BiliPaiNavKey.ArticleDetail, useSharedReturn: Boolean): Boolean = error("Unexpected article")
        override fun containsEntry(key: BiliPaiNavKey): Boolean = error("Unexpected lookup")
        override fun home(): Boolean = error("Unexpected home")
        override fun replaceVideoDetail(current: BiliPaiNavKey.VideoDetail, bvid: String, cid: Long,
            cover: String, resumePositionMs: Long): Boolean = error("Unexpected replacement")
        override fun homeFromVideo(current: BiliPaiNavKey.VideoDetail): Boolean = error("Unexpected home")
        override fun markVideoReturning(current: BiliPaiNavKey.VideoDetail): Boolean = error("Unexpected return")
        override fun clearVideoReturning(): Boolean = error("Unexpected return")
    }
}
