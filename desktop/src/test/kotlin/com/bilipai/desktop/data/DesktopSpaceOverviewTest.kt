package com.bilipai.desktop.data

import com.android.purebilibili.core.util.IdUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import kotlin.test.*

class DesktopSpaceOverviewTest {
    private fun data(card: SpaceAggregateCard? = SpaceAggregateCard(mid = "22", name = "UP", face = "avatar")) = SpaceAggregateData(card = card)
    private fun metadata(data: SpaceAggregateData) = DesktopSpaceMetadata(data, resolveSpaceMainTabs(data.tab2), resolveSpaceContributionTabs(data.tab2))

    @Test fun incompleteAggregateRequiresLegacyFallbackInsteadOfFabricatedProfile() {
        listOf(data(null), data(SpaceAggregateCard(mid = "x", name = "UP", face = "avatar")),
            data(SpaceAggregateCard(mid = "0", name = "UP", face = "avatar")),
            data(SpaceAggregateCard(mid = "22", name = "", face = "avatar")),
            data(SpaceAggregateCard(mid = "22", name = "UP", face = ""))).forEach { assertNull(metadata(it).toOverview()) }
    }

    @Test fun aggregatePreservesEveryHomepagePreviewAndFavoriteMediaIdentity() {
        val original = data().copy(archive = SpaceAggregateArchive(count = 41, item = listOf(SpaceAggregateArchiveItem(aid = 3, bvid = "BV3", title = "视频", cover = "cover"))),
            favourite2 = SpaceAggregateFavoriteSection(4, listOf(SpaceAggregateFavoriteItem(id = 7, fid = 8, mid = 22, title = "收藏", mediaId = 9, count = 2, media_count = 3))),
            coinArchive = SpaceAggregateArchive(count = 5, item = listOf(SpaceAggregateArchiveItem(aid = 11))),
            likeArchive = SpaceAggregateArchive(count = 6, item = listOf(SpaceAggregateArchiveItem(aid = 12))),
            audios = SpaceAggregateAudioSection(7, listOf(SpaceAudioItem(id = 13))),
            article = SpaceAggregateArticleSection(8, listOf(SpaceArticleItem(id = 14))),
            season = SpaceAggregateArchive(count = 9, item = listOf(SpaceAggregateArchiveItem(param = "15", goto = "bangumi"))),
            comic = SpaceAggregateArchive(count = 10, item = listOf(SpaceAggregateArchiveItem(uri = "https://manga.bilibili.com/detail/mc16"))))
        val extra = DesktopSpaceHome(SpaceTopArcData(bvid = "BVtop"), "公告")
        val overview = assertNotNull(metadata(original).toOverview(extra))
        assertSame(extra, overview.supplemental); assertEquals(41, overview.seed.totalVideos)
        assertEquals("cover", overview.seed.videos.single().pic)
        assertEquals(9, overview.seed.homeFavoriteFolders.single().id); assertEquals(3, overview.seed.homeFavoriteFolders.single().media_count)
        assertEquals(11, overview.seed.homeCoinVideos.single().aid); assertEquals(12, overview.seed.homeLikeVideos.single().aid)
        assertEquals(13, overview.seed.audios.single().id); assertEquals(14, overview.seed.articles.single().id)
        assertEquals("15", overview.seed.homeBangumiItems.single().param); assertTrue(overview.seed.homeComicItems.single().uri.contains("mc16"))
    }

    @Test fun aggregateRetainsRelationSpecialBlacklistAndMetricsWithoutGuessing() {
        val followed = data(SpaceAggregateCard(mid = "22", name = "UP", face = "avatar", attention = 4, fans = 5,
            likes = SpaceAggregateLikes(6), relation = SpaceAggregateRelation(isFollow = 1))).copy(relSpecial = 1)
        val special = assertNotNull(metadata(followed).toOverview())
        assertTrue(special.user.isFollowed); assertEquals(-10, special.user.relationStatus)
        assertEquals("特别关注", resolveSpaceFollowActionLabel(false, special.user.relationStatus, special.user.isFollowed))
        assertEquals(listOf(5L, 4L, 6L), resolveSpaceHeaderMetricItems(special.relation, special.statistics).map { it.value })
        val blacklist = assertNotNull(metadata(followed.copy(relation = -1)).toOverview())
        assertFalse(blacklist.user.isFollowed); assertEquals(128, blacklist.user.relationStatus)
        assertEquals("移除黑名单", resolveSpaceFollowActionLabel(false, blacklist.user.relationStatus, false))
    }

    @Test fun aggregateUsesOriginalServerDefaultAndDoesNotDiscardCollectionTabIds() {
        val original = data().copy(defaultTab = "dynamic", tab2 = listOf(SpaceAggregateTab("TA 的投稿", "contribute",
            listOf(SpaceAggregateTabItem("系列甲", "series", seriesId = 91), SpaceAggregateTabItem("系列乙", "series", seriesId = 92))), SpaceAggregateTab("课程", "cheese")))
        val overview = assertNotNull(metadata(original).toOverview())
        assertEquals(SpaceMainTab.DYNAMIC, overview.defaultMainTab); assertTrue(overview.hasCheeseTab)
        assertEquals(listOf(91L, 92L), overview.contributionTabs.map { it.seriesId })
        assertEquals(2, overview.contributionTabs.map { it.id }.distinct().size)
    }

    @Test fun tagsKeepOriginalTitlesFilterServerTypesAndUseCardFallbackOnlyWhenAbsent() {
        val location = SpaceTagItem(title = "IP属地：上海", type = "location", uri = "https://example.test/location")
        val original = data(SpaceAggregateCard(mid = "22", name = "UP", face = "avatar", ipLocation = "北京",
            spaceTag = listOf(location, SpaceTagItem("已实名", type = "real_name"), SpaceTagItem("促销", type = "ad"))))
        val overview = assertNotNull(metadata(original).toOverview(cardIpLocation = "广东"))
        assertEquals("IP属地：上海", overview.user.ipLocation)
        assertEquals(listOf(location, SpaceTagItem("已实名", type = "real_name")), resolveSpaceDisplayTags(overview.user.spaceTags, overview.user.ipLocation))
        val fallback = assertNotNull(metadata(data()).toOverview(cardIpLocation = "广东"))
        assertEquals("IP属地：广东", fallback.user.spaceTags.single().title)
    }

    @Test fun topImageCollectionKeepsPreviewCropTitlesAndThemeFallback() {
        val photo = SpaceCollectionTopImage(defaultImage = "//image/header", location = "position-50-150", height = 100.0)
        val title = SpaceCollectionTopTitle("装扮", "完整头图")
        val images = SpaceAggregateImages(imgUrl = "day", nightImgUrl = "night", collectionTopSimple = SpaceCollectionTopSimple(SpaceCollectionTop(listOf(
            SpaceCollectionTopItem(SpaceCollectionTopItemDetail(image = photo), cover = "full", title = title)))))
        val overview = assertNotNull(metadata(data().copy(images = images)).toOverview())
        assertEquals("https://image/header", overview.user.topPhoto)
        val item = overview.user.topImages.single(); assertEquals("full", item.fullCover); assertEquals(title, item.title); assertEquals(1f, item.dy)
        assertEquals("night", resolveSpaceAggregateTopPhoto(SpaceAggregateImages(imgUrl = "day", nightImgUrl = "night"), true))
        assertEquals(0f, parseTopImageDy("invalid", 0.0))
    }

    @Test fun aggregateVideoDispatchPreservesNativeTargetCidAndAvFallback() {
        var selected: VideoCard? = null
        val item = SpaceAggregateArchiveItem(aid = 170001, goto = "av", param = "170001", firstCid = 42, length = "1:20", title = "视频")
        dispatchDesktopSpaceAggregate(item, 22, { selected = it }, { fail("audio") }, { fail("bangumi") }, { _, _ -> fail("web") })
        val card = assertNotNull(selected); assertEquals(IdUtils.av2bv(170001), card.bvid); assertEquals(42, card.preferredCid); assertEquals(80, card.duration)
    }

    @Test fun aggregateDispatchDoesNotTreatPgcAudioAndComicAsOrdinaryVideos() {
        val events = mutableListOf<String>()
        fun dispatch(item: SpaceAggregateArchiveItem) = dispatchDesktopSpaceAggregate(item, 22, { events += "video:${it.bvid}" },
            { events += "audio:$it" }, { events += "bangumi:$it" }, { uri, title -> events += "web:$uri:$title" })
        dispatch(SpaceAggregateArchiveItem(goto = "bangumi", param = "31"))
        dispatch(SpaceAggregateArchiveItem(goto = "audio", param = "32"))
        dispatch(SpaceAggregateArchiveItem(goto = "comic", uri = "https://manga.bilibili.com/detail/mc33", title = "漫画"))
        dispatch(SpaceAggregateArchiveItem(goto = "audio", param = "0"))
        assertEquals(listOf("bangumi:31", "audio:32", "web:https://manga.bilibili.com/detail/mc33:漫画"), events)
    }

    @Test fun videoCategoriesUseServerTypeIdsRatherThanSeedListOrdinalNumbers() {
        val categories = desktopOriginalSpaceVideoCategories(listOf(SpaceVideoItem(typeid = 160, typename = "生活"), SpaceVideoItem(typeid = 160),
            SpaceVideoItem(typeid = 1, typename = "动画"), SpaceVideoItem(typeid = 0, typename = "忽略")))
        assertEquals(listOf(160, 1), categories.map { it.tid }); assertEquals("生活", categories.first().name)
        assertEquals(2, categories.first().count); assertEquals("分区27", desktopOriginalSpaceVideoCategories(listOf(SpaceVideoItem(typeid = 27))).single().name)
    }

    @Test fun spacePlaylistAndResumeKeepOriginalLoadedOrderAndPageCid() {
        val videos = listOf(SpaceVideoItem(bvid = "BV2", title = "第二", length = "1:20"), SpaceVideoItem(bvid = "BV1", title = "第一", length = "0:40"))
        val playlist = assertNotNull(buildExternalPlaylistFromSpaceVideos(videos, "BV1"))
        assertEquals(listOf("BV2", "BV1"), playlist.playlistItems.map { it.bvid }); assertEquals(1, playlist.startIndex)
        val resumed = desktopSpaceVideoCard(videos[1], 22, SpaceWatchProgress("BV1", 7, "", 15, 40, 100), 30_000)
        assertEquals(7, resumed.preferredCid); assertEquals(15, resumed.progressSeconds)
        val completed = desktopSpaceVideoCard(videos[1], 22, SpaceWatchProgress("BV1", 7, "", -1, 40, 100), 30_000)
        assertEquals(0, completed.preferredCid); assertNull(completed.progressSeconds)
    }
}
