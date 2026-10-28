package com.android.purebilibili.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BilibiliNavigationTargetParserTest {
    @Test
    fun sourceVideoParametersDoNotOverrideLinkDestination() {
        listOf(
            "https://www.bilibili.com/blackboard/activity?bvid=BV1xx411c7mD",
            "https://b23.tv/shop?from=av170001",
            "bilibili://mall/home?source=BV1xx411c7mD"
        ).forEach { url ->
            assertNull(BilibiliNavigationTargetParser.parse(url), url)
            assertNull(BilibiliUrlParser.parse(url).getVideoId(), url)
            assertNull(BilibiliUrlParser.parseDeepLink(url).getVideoId(), url)
        }
        assertEquals(
            BilibiliNavigationTarget.Article(123),
            BilibiliNavigationTargetParser.parse("https://www.bilibili.com/read/cv123?from=BV1xx411c7mD")
        )
        assertEquals(
            BilibiliNavigationTarget.Video("BV1xx411c7mD"),
            BilibiliNavigationTargetParser.parse("https://www.bilibili.com/video/BV1xx411c7mD?from=av170001")
        )
    }

    @Test
    fun externalLookalikesAndWrappedLinksCannotClaimNativeTargets() {
        listOf(
            "https://bilibili.com.example.org/video/BV1xx411c7mD",
            "https://fakeb23.tv/read/cv123",
            "https://bilibili.com@evil.example/read/cv123",
            "https://example.org/?url=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1xx411c7mD",
            "https://example.org/posts/BV1xx411c7mD",
        ).forEach { assertNull(BilibiliNavigationTargetParser.parse(it), it) }
    }

    @Test
    fun genuineNativeTargetsKeepPriorityAcrossContentTypes() {
        assertIs<BilibiliNavigationTarget.Video>(BilibiliNavigationTargetParser.parse("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertIs<BilibiliNavigationTarget.Article>(BilibiliNavigationTargetParser.parse("https://www.bilibili.com/read/cv123"))
        assertIs<BilibiliNavigationTarget.Dynamic>(BilibiliNavigationTargetParser.parse("https://t.bilibili.com/1199344045210468386"))
        assertIs<BilibiliNavigationTarget.Space>(BilibiliNavigationTargetParser.parse("https://space.bilibili.com/123"))
        assertIs<BilibiliNavigationTarget.Live>(BilibiliNavigationTargetParser.parse("https://live.bilibili.com/123"))
        assertIs<BilibiliNavigationTarget.BangumiEpisode>(BilibiliNavigationTargetParser.parse("https://www.bilibili.com/bangumi/play/ep123"))
    }

    @Test
    fun weeklyLinksPreservePeriodAndIgnoreInvalidNumbers() {
        for (url in listOf(
            "bilibili://popular/weekly?number=133",
            "https://www.bilibili.com/v/popular/weekly?num=133",
            "https://m.bilibili.com/v/popular/weekly?number=133"
        )) {
            assertEquals(BilibiliNavigationTarget.PopularFeed("weekly", 133), BilibiliNavigationTargetParser.parse(url))
        }
        for (number in listOf("0", "-1", "invalid", "2147483648")) {
            assertEquals(BilibiliNavigationTarget.PopularFeed("weekly"),
                BilibiliNavigationTargetParser.parse("bilibili://popular/weekly?number=$number"))
        }
        assertNull(BilibiliNavigationTargetParser.parse("https://example.com/v/popular/weekly?num=133"))
    }


    @Test
    fun parse_commentSpaceSchema_resolvesSpaceTarget() {
        val target = BilibiliNavigationTargetParser.parse("bilibili://space/495695169")

        assertIs<BilibiliNavigationTarget.Space>(target)
        assertEquals(495695169L, target.mid)
    }

    @Test
    fun parse_wrappedSpaceUrl_resolvesSpaceTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://browser?url=https%3A%2F%2Fspace.bilibili.com%2F123456"
        )

        assertIs<BilibiliNavigationTarget.Space>(target)
        assertEquals(123456L, target.mid)
    }

    @Test
    fun parse_wrappedLiveUrl_resolvesLiveTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://browser?url=https%3A%2F%2Flive.bilibili.com%2F456789"
        )

        assertIs<BilibiliNavigationTarget.Live>(target)
        assertEquals(456789L, target.roomId)
    }

    @Test
    fun parse_bangumiSeasonUrl_resolvesSeasonTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/bangumi/play/ss39708"
        )

        assertIs<BilibiliNavigationTarget.BangumiSeason>(target)
        assertEquals(39708L, target.seasonId)
    }

    @Test
    fun parse_cheeseSeasonUrl_resolvesSeasonTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/cheese/play/ss150"
        )

        assertIs<BilibiliNavigationTarget.BangumiSeason>(target)
        assertEquals(150L, target.seasonId)
    }

    @Test
    fun parse_cheeseEpisodeUrl_resolvesEpisodeTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/cheese/play/ep2425"
        )

        assertIs<BilibiliNavigationTarget.BangumiEpisode>(target)
        assertEquals(2425L, target.epId)
    }

    @Test
    fun parse_cheeseCustomSchemeSeason_resolvesSeasonTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://cheese/season/150"
        )

        assertIs<BilibiliNavigationTarget.BangumiSeason>(target)
        assertEquals(150L, target.seasonId)
    }

    @Test
    fun parse_cheeseCustomSchemePlay_resolvesSeasonTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://cheese/play/ss150"
        )

        assertIs<BilibiliNavigationTarget.BangumiSeason>(target)
        assertEquals(150L, target.seasonId)
    }

    @Test
    fun parse_bangumiMediaUrl_resolvesMediaTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/bangumi/media/md28237119"
        )

        assertIs<BilibiliNavigationTarget.BangumiSeason>(target)
        assertEquals(0L, target.seasonId)
        assertEquals(28237119L, target.mediaId)
        assertEquals(
            target,
            BilibiliNavigationTargetParser.parse("md28237119")
        )
    }

    @Test
    fun parse_articleReadUrl_resolvesArticleTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/read/cv12345"
        )

        assertIs<BilibiliNavigationTarget.Article>(target)
        assertEquals(12345L, target.articleId)
    }

    @Test
    fun parse_articleMobileReadUrl_resolvesArticleTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/read/mobile?id=67890"
        )

        assertIs<BilibiliNavigationTarget.Article>(target)
        assertEquals(67890L, target.articleId)
    }

    @Test
    fun parse_nonReadBilibiliUrlWithId_doesNotResolveArticleTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://www.bilibili.com/blackboard/activity-test?id=12345"
        )

        assertNull(target)
    }

    @Test
    fun parse_followingDetailDeepLink_resolvesDynamicTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://following/detail/1015637114125025318"
        )

        assertIs<BilibiliNavigationTarget.Dynamic>(target)
        assertEquals("1015637114125025318", target.dynamicId)
    }

    @Test
    fun parse_videoDeepLinkWithLikelyDynamicId_resolvesDynamicTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://video/1199344045210468386"
        )

        assertIs<BilibiliNavigationTarget.Dynamic>(target)
        assertEquals("1199344045210468386", target.dynamicId)
    }

    @Test
    fun parse_searchDeepLink_resolvesSearchTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "bilibili://search?keyword=%E9%BB%91%E7%A5%9E%E8%AF%9D"
        )

        assertIs<BilibiliNavigationTarget.Search>(target)
        assertEquals("黑神话", target.keyword)
    }

    @Test
    fun parse_searchHttpUrl_resolvesSearchTarget() {
        val target = BilibiliNavigationTargetParser.parse(
            "https://search.bilibili.com/all?keyword=oppo%205g"
        )

        assertIs<BilibiliNavigationTarget.Search>(target)
        assertEquals("oppo 5g", target.keyword)
    }

    @Test
    fun parse_nonBilibiliUrl_returnsNull() {
        val target = BilibiliNavigationTargetParser.parse("https://example.com/video/1")

        assertNull(target)
    }
}
