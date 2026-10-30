package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.DynamicRegionItem
import com.android.purebilibili.data.model.response.DynamicRegionResponse
import com.android.purebilibili.data.model.response.MobileFeedItem
import com.android.purebilibili.data.model.response.MobileFeedResponse
import com.android.purebilibili.data.model.response.PopularItem
import com.android.purebilibili.data.model.response.PopularResponse
import com.android.purebilibili.data.model.response.RankingResponse
import com.android.purebilibili.data.model.response.RecommendItem
import com.android.purebilibili.data.model.response.RecommendResponse
import com.android.purebilibili.data.model.response.SearchTypeData
import com.android.purebilibili.data.model.response.SearchTypeResponse
import com.android.purebilibili.data.model.response.SearchVideoItem
import com.android.purebilibili.data.model.response.SpaceAggregateArchiveItem
import com.android.purebilibili.data.model.response.SpaceAggregateData
import com.android.purebilibili.data.model.response.SpaceVideoItem
import com.android.purebilibili.data.model.response.SpaceVideoResponse
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.model.response.WatchLaterResponse
import com.android.purebilibili.data.model.response.VideoRights
import com.android.purebilibili.data.model.response.resolveVideoStatusLabels
import com.android.purebilibili.data.repository.SearchRepository
import com.android.purebilibili.data.repository.mergeSearchDurationResultPages
import com.android.purebilibili.feature.home.components.cards.resolveVideoPremiumBadgeLabel
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.space.mergeSpaceVideoPages
import com.android.purebilibili.feature.space.resolveSpaceInitialSeedFromAggregate
import com.android.purebilibili.feature.space.resolveSpaceVideoChargeBadgeLabel
import com.android.purebilibili.feature.watchlater.DesktopWatchLaterEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.4.1 video status labels through the four Windows entries: popular/region, search, UP space and
 * watch later. Each vector decodes a field combination of the endpoint model with the Json settings the
 * Windows repositories use, then reads the labels the covers draw (VideoItem.statusLabels, or the
 * SpaceVideoItem and SpaceAggregateArchiveItem getters). The fixtures are synthetic: field names come
 * from the models and the upstream tests, not from a live capture. No network, mpv or GUI.
 *
 * Needs the v041-video-status-labels slice. The seed vectors also need the mapSpaceAggregateVideoItem
 * hook of the space overview producer, and the watch-later vectors need the repository hooks of the
 * watch-later producer.
 */
class DesktopVideoStatusLabelEntriesTest {
    // Same settings as DesktopRepository, DesktopSearchRepository and DesktopOriginalSpaceEnvironment.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val none = emptyList<String>()

    @Test
    fun fullWatchLaterSchemaDecodesPageSizeAndKeepsLegacyDefault() {
        val paged = json.decodeFromString<WatchLaterResponse>(
            """{"code":0,"data":{"count":1,"ps":20,"list":[{"aid":17,"bvid":"BVpage","rights":{"is_stein_gate":1}}]}}"""
        )
        val data = assertNotNull(paged.data)
        assertEquals(1, data.count)
        assertEquals(20, data.ps)
        val items = assertNotNull(data.list)
        assertEquals(1, items.size)
        assertEquals(17L, items.single().aid)
        assertEquals(1, items.single().rights?.isSteinGate)
        val legacy = json.decodeFromString<WatchLaterResponse>(
            """{"code":0,"data":{"count":0,"list":[]}}"""
        )
        assertEquals(0, assertNotNull(legacy.data).ps)
    }

    private enum class LabelEntry { POPULAR, REGION, RECOMMEND, MOBILE_FEED, VIDEO_ITEM, SEARCH, SPACE, AGGREGATE }

    private fun labels(entry: LabelEntry, payload: String): List<String> = when (entry) {
        LabelEntry.POPULAR -> json.decodeFromString<PopularItem>(payload).toVideoItem().statusLabels
        LabelEntry.REGION -> json.decodeFromString<DynamicRegionItem>(payload).toVideoItem().statusLabels
        LabelEntry.RECOMMEND -> json.decodeFromString<RecommendItem>(payload).toVideoItem().statusLabels
        LabelEntry.MOBILE_FEED -> json.decodeFromString<MobileFeedItem>(payload).toVideoItem().statusLabels
        LabelEntry.VIDEO_ITEM -> json.decodeFromString<VideoItem>(payload).statusLabels
        LabelEntry.SEARCH -> json.decodeFromString<SearchVideoItem>(payload).toVideoItem().statusLabels
        LabelEntry.SPACE -> json.decodeFromString<SpaceVideoItem>(payload).statusLabels
        LabelEntry.AGGREGATE -> json.decodeFromString<SpaceAggregateArchiveItem>(payload).statusLabels
    }

    private fun assertLabels(entry: LabelEntry, vararg vectors: Pair<String, List<String>>) {
        vectors.forEach { (payload, expected) -> assertEquals(expected, labels(entry, payload), "$entry $payload") }
    }

    // --- shared policy ---------------------------------------------------------------------------

    @Test fun policyOrdersChargingOrPaidThenCourseCooperationInteractiveThenServerLabels() {
        assertEquals(listOf("充电专属"), resolveVideoStatusLabels(charging = true, paid = true))
        assertEquals(listOf("付费"), resolveVideoStatusLabels(paid = true))
        assertEquals(listOf("付费", "课堂", "合作", "互动", "限免"), resolveVideoStatusLabels(
            cooperation = true, interactive = true, course = true, paid = true, serverLabels = listOf("付费", "限免")))
        val every = resolveVideoStatusLabels(
            cooperation = true, interactive = true, course = true, charging = true, paid = true)
        assertEquals(listOf("充电专属", "课堂", "合作", "互动"), every)
        // v0.4.1 has no subscription label, and the pinned chip is not part of the shared policy.
        assertFalse("订阅" in every || "置顶" in every)
    }

    @Test fun policyTrimsAliasesServerLabelsAndDeduplicatesOnlyExactStrings() {
        assertEquals(listOf("课堂", "充电专属"), resolveVideoStatusLabels(serverLabels = listOf(" 课程 ", "充电", "课堂")))
        assertEquals(listOf("课堂"), resolveVideoStatusLabels(course = true, serverLabels = listOf("课程")))
        assertEquals(listOf("充电专属"), resolveVideoStatusLabels(charging = true, serverLabels = listOf("充电", "充电专属")))
        assertEquals(listOf("合作"), resolveVideoStatusLabels(cooperation = true, serverLabels = listOf(" 合作 ", "合作")))
        // Upstream parity: a flag and a different server wording are both kept.
        assertEquals(listOf("充电专属", "付费"), resolveVideoStatusLabels(charging = true, serverLabels = listOf("付费")))
    }

    @Test fun policyFallsBackToNothingAndDoesNotCapTheLabelCount() {
        assertTrue(resolveVideoStatusLabels().isEmpty())
        assertTrue(resolveVideoStatusLabels(serverLabels = listOf("", "  ")).isEmpty())
        val server = (1..8).map { "标签$it" }
        assertEquals(listOf("充电专属", "课堂", "合作", "互动") + server, resolveVideoStatusLabels(
            cooperation = true, interactive = true, course = true, charging = true, serverLabels = server))
    }

    // --- popular, ranking, precious and region ----------------------------------------------------

    @Test fun popularFieldCombinationsFollowThePriorityAndKeepEveryServerLabel() {
        assertLabels(LabelEntry.POPULAR,
            // upstream VideoStatusLabelsTest: charging level 0 counts, repeats of a flag or pgc_label collapse
            """{"charging_pay":{"level":0},"rights":{"is_cooperation":1},"pgc_label":"独家","badges":[{"text":"独家"},{"text":"合作"}]}""" to listOf("充电专属", "合作", "独家"),
            """{"rights":{"pay":1,"is_cooperation":1}}""" to listOf("付费", "合作"),
            """{"rights":{"ugc_pay":1,"pay":1}}""" to listOf("充电专属"),
            """{"rights":{"ugc_pay_preview":1}}""" to listOf("充电专属"),
            """{"rights":{"arc_pay":1}}""" to listOf("付费"),
            """{"rights":{"pay_free_watch":1}}""" to none,
            """{"rights":{"is_stein_gate":1}}""" to listOf("互动"),
            """{"rights":{"pay":1,"ugc_pay":1,"is_cooperation":1,"is_stein_gate":1},"charging_pay":{"level":2},"pgc_label":"课程","badges":[{"text":"充电"},{"text":"限免"}]}""" to listOf("充电专属", "合作", "互动", "课堂", "限免"),
            """{"badges":[{"text":"独家","bg_color":"#FB7299","bg_style":1}]}""" to listOf("独家"),
            """{"pgc_label":"  限免 "}""" to listOf("限免"),
            """{"pgc_label":""}""" to none)
    }

    @Test fun missingNullAndEmptyPopularFieldsFallBackToNoLabels() {
        assertLabels(LabelEntry.POPULAR,
            """{}""" to none,
            """{"badges":null,"pgc_label":null,"charging_pay":null,"rights":null}""" to none,
            """{"badges":[],"rights":{}}""" to none,
            """{"charging_pay":{}}""" to none,
            """{"charging_pay":{"level":null}}""" to none)
        // Upstream parity, unverified against live payloads: any non-null level counts as charging.
        assertLabels(LabelEntry.POPULAR,
            """{"charging_pay":{"level":0}}""" to listOf("充电专属"),
            """{"charging_pay":{"level":1}}""" to listOf("充电专属"))
    }

    @Test fun regionArchivesUseTheSameInputsAsPopular() {
        assertLabels(LabelEntry.REGION,
            """{"charging_pay":{"level":2},"pgc_label":"限免","rights":{"is_stein_gate":1}}""" to listOf("充电专属", "互动", "限免"),
            """{"rights":{"pay":1,"is_cooperation":1},"badges":[{"text":"付费"}]}""" to listOf("付费", "合作"),
            """{}""" to none)
    }

    @Test fun homeFeedItemsKeepDurationOutOfTheBadgesAndStillCarryRights() {
        assertLabels(LabelEntry.MOBILE_FEED,
            // upstream VideoStatusLabelsTest: cover_right_text is the duration on AV cards
            """{"goto":"av","cover_right_text":"03:25"}""" to none,
            // Native cards also carry the original v043 kind badge, even without a server label.
            """{"goto":"bangumi","cover_right_text":"独家"}""" to listOf("独家", "番剧"),
            """{"goto":"bangumi","cover_right_text":""}""" to listOf("番剧"),
            """{"goto":"av","cover_right_text":"独家","rights":{"pay":1},"charging_pay":{"level":1},"badges":[{"text":"合作"}]}""" to listOf("充电专属", "合作"))
        assertLabels(LabelEntry.RECOMMEND,
            """{"id":5,"bvid":"BV1recommend5","charging_pay":{"level":3},"pgc_label":"独家","badges":[{"text":"合作"}],"rights":{"is_stein_gate":1}}""" to listOf("充电专属", "互动", "独家", "合作"))
    }

    @Test fun videoItemDerivesLabelsFromRightsFlagsAndNavigationTypeWithoutAnyRequest() {
        assertLabels(LabelEntry.VIDEO_ITEM,
            // upstream VideoStatusLabelsTest and VideoCooperationMetadataTest
            """{"rights":{"is_stein_gate":1,"pay":1}}""" to listOf("付费", "互动"),
            """{}""" to none,
            """{"contentType":"ketang"}""" to listOf("课堂"),
            """{"contentType":"live_room"}""" to listOf("直播"),
            """{"is_cooperation":true}""" to listOf("合作"),
            """{"is_cooperation":false,"is_union_video":0}""" to none)
    }

    @Test fun videoItemKeepsItsLabelsThroughJsonPersistence() {
        val item = json.decodeFromString<PopularItem>(
            """{"aid":1,"charging_pay":{"level":0},"rights":{"is_cooperation":1,"is_stein_gate":1},"pgc_label":"独家","badges":[{"text":"限免"}]}""").toVideoItem()
        val restored = json.decodeFromString(VideoItem.serializer(), json.encodeToString(VideoItem.serializer(), item))
        // Only the label inputs are compared: they must survive the round trip, whatever else is persisted.
        assertEquals(item.rights, restored.rights)
        assertEquals(item.chargingPay, restored.chargingPay)
        assertEquals(item.pgcLabel, restored.pgcLabel)
        assertEquals(item.badges, restored.badges)
        assertEquals(listOf("充电专属", "合作", "互动", "独家", "限免"), restored.statusLabels)
    }

    @Test fun popularRankingAndRegionResponsesLabelEachItemFromItsOwnFields() {
        val popular = json.decodeFromString<PopularResponse>("""
            {"code":0,"message":"0","ttl":1,"data":{"no_more":false,"list":[
              {"aid":101,"bvid":"BV1popular101","cid":1,"title":"充电合作","pic":"//i0.hdslb.com/a.jpg","duration":120,"pubdate":1700000000,
               "owner":{"mid":7,"name":"UP","face":"//i0.hdslb.com/f.jpg"},"stat":{"view":10,"like":2,"danmaku":1},
               "rights":{"bp":0,"pay":0,"arc_pay":0,"ugc_pay":0,"ugc_pay_preview":0,"pay_free_watch":0,"is_cooperation":1},
               "charging_pay":{"level":0},"pgc_label":"独家","badges":[{"text":"独家"},{"text":"合作"}]},
              {"aid":102,"bvid":"BV1popular102","cid":2,"title":"普通","pic":"//i0.hdslb.com/b.jpg","duration":60,"pubdate":1700000001,
               "owner":{"mid":8,"name":"UP2","face":""},"stat":{"view":3},"rights":{"pay":0,"arc_pay":0,"ugc_pay":0},"badges":[]},
              {"aid":103,"bvid":"BV1popular103","cid":3,"title":"付费","pic":"//i0.hdslb.com/c.jpg","duration":30,"pubdate":1700000002,
               "owner":{"mid":9,"name":"UP3","face":""},"stat":{},"rights":{"pay":1}}
            ]}}
        """.trimIndent())
        val videos = assertNotNull(popular.data?.list).map { it.toVideoItem() }
        assertEquals(listOf("BV1popular101", "BV1popular102", "BV1popular103"), videos.map { it.bvid })
        assertEquals(listOf(listOf("充电专属", "合作", "独家"), none, listOf("付费")), videos.map { it.statusLabels })

        val ranking = json.decodeFromString<RankingResponse>("""
            {"code":0,"message":"0","data":{"note":"全站榜","list":[
              {"aid":111,"bvid":"BV1ranking111","cid":4,"title":"互动","owner":{"mid":7,"name":"UP"},"rights":{"is_stein_gate":1,"arc_pay":1}}
            ]}}
        """.trimIndent())
        assertEquals(listOf(listOf("付费", "互动")), assertNotNull(ranking.data?.list).map { it.toVideoItem().statusLabels })

        val region = json.decodeFromString<DynamicRegionResponse>("""
            {"code":0,"message":"0","data":{"archives":[
              {"aid":121,"bvid":"BV1region121","cid":5,"title":"分区","owner":{"mid":7,"name":"UP"},"stat":{"view":1},
               "charging_pay":{"level":1},"pgc_label":"限免","rights":{"is_cooperation":1}},
              {"aid":122,"bvid":"BV1region122","cid":6,"title":"普通","owner":{"mid":7,"name":"UP"},"stat":{"view":1}}
            ]}}
        """.trimIndent())
        val archives = assertNotNull(region.data?.archives).map { it.toVideoItem() }
        assertEquals(listOf("BV1region121", "BV1region122"), archives.map { it.bvid })
        assertEquals(listOf(listOf("充电专属", "合作", "限免"), none), archives.map { it.statusLabels })
    }

    // --- search --------------------------------------------------------------------------------

    @Test fun searchFieldCombinationsReachTheSharedPolicy() {
        assertLabels(LabelEntry.SEARCH,
            // upstream VideoStatusLabelsTest: the course alias is canonicalised and the other badge kept
            """{"type":"ketang","badges":[{"text":"课程"},{"text":"限时免费"}]}""" to listOf("课堂", "限时免费"),
            """{"is_union_video":1,"is_steins":true,"is_lesson_video":1,"charging_pay":{"level":1},"pgc_label":"独家","badges":[{"text":" 合作 "},{"text":""},{"text":"独家"}]}""" to listOf("充电专属", "课堂", "合作", "互动", "独家"),
            """{"type":"live_room","pgc_label":"独家","badges":[{"text":"限免"}]}""" to listOf("独家", "限免", "直播"),
            """{"type":"ketang"}""" to listOf("课堂"),
            """{"type":"video"}""" to none,
            """{}""" to none,
            """{"badges":null,"pgc_label":null,"charging_pay":null}""" to none,
            """{"badges":[{}]}""" to none,
            """{"badges":[{"text":"独家","bg_color":"#FB7299","bg_style":1}]}""" to listOf("独家"),
            """{"is_steins":1}""" to listOf("互动"),
            """{"charging_pay":{}}""" to none,
            // Upstream parity, unverified against live payloads: any non-null level counts as charging.
            """{"charging_pay":{"level":0}}""" to listOf("充电专属"))
    }

    @Test fun searchKeepsNavigationTypeAndAddsLabelsPerResultFromTheWholeResponse() {
        val response = json.decodeFromString<SearchTypeResponse>("""
            {"code":0,"message":"0","data":{"page":1,"pagesize":20,"numResults":3,"numPages":1,"result":[
              {"type":"video","id":201,"bvid":"BV1search201","aid":201,"title":"合作视频","pic":"//i0.hdslb.com/s1.jpg","author":"UP","mid":7,
               "play":"1.2万","video_review":5,"duration":"3:25","pubdate":1700000000,"arcurl":"http://www.bilibili.com/video/av201",
               "typeid":"17","is_union_video":1,"badges":[{"text":"合作"}]},
              {"type":"ketang","id":202,"bvid":"BV1search202","title":"课程","pic":"//i0.hdslb.com/s2.jpg","author":"UP","mid":8,
               "arcurl":"https://www.bilibili.com/cheese/play/ss202","is_lesson_video":1,"badges":[{"text":"课程"},{"text":"限时免费"}]},
              {"type":"video","id":203,"bvid":"BV1search203","title":"普通","pic":"//i0.hdslb.com/s3.jpg","author":"UP","mid":9,
               "arcurl":"http://www.bilibili.com/video/av203","badges":[]}
            ]}}
        """.trimIndent())
        val videos = assertNotNull(response.data?.result).map { it.toVideoItem() }
        assertEquals(listOf("BV1search201", "BV1search202", "BV1search203"), videos.map { it.bvid })
        assertEquals(listOf(listOf("合作"), listOf("课堂", "限时免费"), none), videos.map { it.statusLabels })
        // The label never replaces the navigation type or the original link.
        assertEquals(listOf("video", "ketang", "video"), videos.map { it.contentType })
        assertEquals("https://www.bilibili.com/cheese/play/ss202", videos[1].navigationUrl)
    }

    @Test fun searchMergeKeepsTheFirstRecordsLabelsForDuplicateVideos() {
        val first = json.decodeFromString<SearchTypeData>(
            """{"page":1,"numPages":1,"numResults":2,"result":[{"bvid":"BV1merge1","badges":[{"text":"独家"}]},{"bvid":"BV1merge2","is_union_video":1}]}""")
        val second = json.decodeFromString<SearchTypeData>(
            """{"page":1,"numPages":1,"numResults":2,"result":[{"bvid":"BV1merge1","type":"ketang","charging_pay":{"level":1},"badges":[{"text":"限免"}]},{"bvid":"BV1merge3","is_steins":true}]}""")
        val expected = listOf(listOf("独家"), listOf("合作"), listOf("互动"))
        val raw = assertNotNull(mergeDesktopSearchVideoPages(listOf(first, second), 1).first.result)
        assertEquals(listOf("BV1merge1", "BV1merge2", "BV1merge3"), raw.map { it.bvid })
        assertEquals(expected, raw.map { it.toVideoItem().statusLabels })

        val pageInfo = SearchRepository.SearchPageInfo(currentPage = 1, totalPages = 1, totalResults = 2, hasMore = false)
        val (videos, _) = mergeSearchDurationResultPages(listOf(
            assertNotNull(first.result).map { it.toVideoItem() } to pageInfo,
            assertNotNull(second.result).map { it.toVideoItem() } to pageInfo))
        assertEquals(listOf("BV1merge1", "BV1merge2", "BV1merge3"), videos.map { it.bvid })
        assertEquals(expected, videos.map { it.statusLabels })
    }

    // --- UP space ------------------------------------------------------------------------------

    @Test fun spaceVideoFieldCombinationsReachTheSharedPolicy() {
        assertLabels(LabelEntry.SPACE,
            // upstream VideoStatusLabelsTest
            """{"is_steins":true,"is_lesson_video":1,"is_charging_arc":true,"badges":[{"text":"互动"},{"text":"限免"}]}""" to listOf("充电专属", "课堂", "互动", "限免"),
            """{"elec_arc_type":1,"badges":[{"text":"充电"}]}""" to listOf("充电专属"),
            """{"is_cooperation":true,"is_union_video":1}""" to listOf("合作"),
            """{}""" to none,
            """{"badges":null}""" to none,
            """{"badges":[{"text":" "}]}""" to none)
    }

    @Test fun spaceVideoResponseLabelsEachVideoFromItsOwnFields() {
        val response = json.decodeFromString<SpaceVideoResponse>("""
            {"code":0,"message":"0","data":{"list":{"vlist":[
              {"aid":301,"bvid":"BV1space301","title":"互动充电","pic":"//i0.hdslb.com/p1.jpg","play":100,"comment":3,"length":"10:24",
               "created":1700000000,"author":"UP","typeid":17,"typename":"分区","is_steins":true,"is_charging_arc":true,"elec_arc_type":1,
               "is_union_video":0,"is_lesson_video":1,"badges":[{"text":"互动"},{"text":"限免"}]},
              {"aid":302,"bvid":"BV1space302","title":"普通","pic":"//i0.hdslb.com/p2.jpg","play":5,"comment":0,"length":"00:30",
               "created":1700000001,"author":"UP","is_steins":false,"is_charging_arc":false,"elec_arc_type":0,"badges":[]},
              {"aid":303,"bvid":"BV1space303","title":"合作","pic":"//i0.hdslb.com/p3.jpg","play":7,"comment":1,"length":"01:00",
               "created":1700000002,"author":"UP","is_cooperation":true,"is_union_video":1}
            ]},"page":{"pn":1,"ps":30,"count":3}}}
        """.trimIndent())
        val videos = assertNotNull(response.data?.list?.vlist)
        assertEquals(listOf("BV1space301", "BV1space302", "BV1space303"), videos.map { it.bvid })
        assertEquals(listOf(listOf("充电专属", "课堂", "互动", "限免"), none, listOf("合作")), videos.map { it.statusLabels })
    }

    @Test fun spaceMergeKeepsTheFirstRecordsLabelsForDuplicateVideos() {
        val existing = json.decodeFromString<List<SpaceVideoItem>>(
            """[{"aid":1,"bvid":"BV1spacemerge1","is_steins":true,"badges":[{"text":"独家"}]},{"aid":2,"bvid":"BV1spacemerge2","is_union_video":1}]""")
        val incoming = json.decodeFromString<List<SpaceVideoItem>>(
            """[{"aid":1,"bvid":"BV1spacemerge1","is_charging_arc":true,"badges":[{"text":"限免"}]},{"aid":3,"bvid":"BV1spacemerge3","is_lesson_video":1}]""")
        val merged = mergeSpaceVideoPages(existing, incoming)
        assertEquals(listOf("BV1spacemerge1", "BV1spacemerge2", "BV1spacemerge3"), merged.map { it.bvid })
        assertEquals(listOf(listOf("互动", "独家"), listOf("合作"), listOf("课堂")), merged.map { it.statusLabels })
    }

    @Test fun aggregateArchiveItemsKeepLabelBeforeBadgesAndHaveNoChargingInputs() {
        assertLabels(LabelEntry.AGGREGATE,
            """{"is_cooperation":true,"is_steins":1,"is_lesson_video":false,"label":"独家","badges":[{"text":"独家"},{"text":"合作"}]}""" to listOf("合作", "互动", "独家"),
            """{"label":"限免","badges":[{"text":"独家"}]}""" to listOf("限免", "独家"),
            """{"is_lesson_video":1,"label":"  ","badges":[{"text":""}]}""" to listOf("课堂"),
            """{"label":"独家","badges":[{"text":"独家"},{"text":"独家"}]}""" to listOf("独家"),
            """{}""" to none,
            // Upstream parity: the aggregate card has no charging fields, so these keys are ignored.
            """{"is_charging_arc":true,"elec_arc_type":1,"ugc_pay":1}""" to none)
    }

    @Test fun homepageSeedVideosCarryTheFlagsAndOrderBadgesBeforeTheLabel() {
        val aggregate = """{"is_cooperation":true,"is_steins":1,"is_lesson_video":false,"label":"独家","badges":[{"text":"独家"},{"text":"合作"}]}"""
        val reordered = """{"label":"限免","badges":[{"text":"独家"}]}"""
        val lesson = """{"is_lesson_video":1}"""
        val plain = """{}"""
        val items = listOf(aggregate, reordered, lesson, plain).mapIndexed { index, fields ->
            val body = fields.removePrefix("{").removeSuffix("}")
            val lead = if (body.isEmpty()) "" else "$body,"
            """{$lead"aid":${400 + index},"bvid":"BV1seed${400 + index}","title":"首页","cover":"//i0.hdslb.com/h.jpg","author":"UP","play":1,"ctime":1700000000,"length":"01:20"}"""
        }
        val data = json.decodeFromString<SpaceAggregateData>(
            """{"card":{"mid":"22","name":"UP","face":"avatar"},"archive":{"count":4,"item":[${items.joinToString(",")}]}}""")
        val cards = assertNotNull(data.archive).item
        val seeds = assertNotNull(resolveSpaceInitialSeedFromAggregate(data)).videos
        assertEquals(listOf("BV1seed400", "BV1seed401", "BV1seed402", "BV1seed403"), seeds.map { it.bvid })
        assertEquals(listOf(listOf("合作", "互动", "独家"), listOf("限免", "独家"), listOf("课堂"), none), cards.map { it.statusLabels })
        // Upstream parity: the seed mapping appends the label after the badges, so a seed card can differ in order.
        assertEquals(listOf(listOf("合作", "互动", "独家"), listOf("独家", "限免"), listOf("课堂"), none), seeds.map { it.statusLabels })
    }

    // --- watch later ---------------------------------------------------------------------------

    private val watchLaterVectors = listOf(
        """{"charging_pay":{"level":1},"rights":{"is_cooperation":1,"is_stein_gate":1},"is_pugv":true,"pgc_label":"独家"}""" to listOf("充电专属", "课堂", "合作", "互动", "独家"),
        """{"pgc_label":"课程","is_pugv":true}""" to listOf("课堂"),
        """{"pgc_label":"充电","charging_pay":{"level":0}}""" to listOf("充电专属"),
        """{"charging_pay":{"level":null}}""" to none,
        """{"rights":{"is_stein_gate":1}}""" to listOf("互动"),
        """{"rights":{"is_cooperation":1}}""" to listOf("合作"),
        """{"pgc_label":"  "}""" to none,
        """{"is_pgc":true,"is_pugv":false,"pgc_label":"独家"}""" to listOf("独家"),
        """{"pgc_label":null,"charging_pay":null,"rights":null,"is_pugv":null}""" to none,
        // Paid is never derived from watch later: the rights model only reads the two flags.
        """{"rights":{"pay":1,"arc_pay":1,"is_cooperation":0}}""" to none,
        """{}""" to none,
    )

    /** Offline Retrofit over the real BilibiliApi and the generated watch-later repository. */
    private class WatchLaterFixture {
        private val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + Dispatchers.Default)
        private val wbiKey = "0123456789abcdef".repeat(2)
        @Volatile private var payload = ""
        val requestedPaths = CopyOnWriteArrayList<String>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedPaths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
            .client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }
                .asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
        private val repository = DesktopWatchLaterEnvironment(
            DesktopFavoriteEnvironment.forFolderDrawer(scope, api, { owner.isActive }, { "fixture-csrf" }, { 17L },
                { error("A label read must not produce visible feedback") }),
            { Result.success(wbiKey to wbiKey) },
            { error("A label read must not report a failure: $it") },
        ).watchLater

        suspend fun page(items: List<String>): List<VideoItem> = withTimeout(5_000) {
            payload = """{"code":0,"message":"0","data":{"count":${items.size},"list":[${items.joinToString(",")}]}}"""
            repository.getPage(1, 0, "", false).getOrThrow().items
        }

        suspend fun close() {
            owner.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    /** One watch-later list item: the case fields lead, then the identity fields every item carries. */
    private fun watchLaterItem(aid: Long, fields: String = "{}"): String {
        val body = fields.trim().removePrefix("{").removeSuffix("}").trim()
        val lead = if (body.isEmpty()) "" else "$body,"
        return """{$lead"aid":$aid,"bvid":"BV1watch$aid","cid":${aid + 1000},"title":"稍后$aid","pic":"//i0.hdslb.com/w.jpg","duration":90,"progress":-1,"pubdate":1700000000,"owner":{"mid":7,"name":"UP","face":"//i0.hdslb.com/f.jpg"},"stat":{"view":1,"danmaku":2},"redirect_url":"https://www.bilibili.com/video/BV1watch$aid"}"""
    }

    @Test fun watchLaterFieldCombinationsBecomeCanonicalCoverLabelsInsteadOfOverlineText(): Unit = runBlocking {
        val fixture = WatchLaterFixture()
        try {
            val items = fixture.page(watchLaterVectors.mapIndexed { index, (fields, _) -> watchLaterItem(1001L + index, fields) })
            assertEquals(listOf("/x/v2/history/toview/web"), fixture.requestedPaths.toList())
            assertEquals(watchLaterVectors.indices.map { 1001L + it }, items.map { it.aid })
            assertEquals(watchLaterVectors.map { it.second }, items.map { it.statusLabels })
            // The old overline text (充电 · 合作 · pgc_label · 课程) is gone; navigation keeps the redirect link.
            assertTrue(items.all { it.contentType.isEmpty() })
            assertEquals("https://www.bilibili.com/video/BV1watch1001", items.first().navigationUrl)
        } finally {
            fixture.close()
        }
    }

    @Test fun watchLaterRightsStayNonNullWithZeroFlagsWhenTheServerSendsNone(): Unit = runBlocking {
        val fixture = WatchLaterFixture()
        try {
            val items = fixture.page(listOf(watchLaterItem(1101L), watchLaterItem(1102L, """{"rights":null}""")))
            assertEquals(listOf(VideoRights(), VideoRights()), items.map { it.rights })
            assertTrue(items.all { it.statusLabels.isEmpty() })
        } finally {
            fixture.close()
        }
    }

    @Test fun watchLaterLabelsAreDerivedPerItemSoANewPageNeverInheritsOldOnes(): Unit = runBlocking {
        val fixture = WatchLaterFixture()
        try {
            val first = fixture.page(listOf(
                watchLaterItem(2001L, """{"charging_pay":{"level":1},"rights":{"is_cooperation":1},"pgc_label":"独家"}"""),
                watchLaterItem(2002L)))
            val second = fixture.page(listOf(watchLaterItem(2002L), watchLaterItem(2003L, """{"is_pugv":true}""")))
            assertEquals(listOf(listOf("充电专属", "合作", "独家"), none), first.map { it.statusLabels })
            assertEquals(listOf(none, listOf("课堂")), second.map { it.statusLabels })
            // The earlier page is unchanged by the later read.
            assertEquals(listOf(listOf("充电专属", "合作", "独家"), none), first.map { it.statusLabels })
        } finally {
            fixture.close()
        }
    }

    // --- legacy chips and parity quirks -----------------------------------------------------------

    @Test fun popularPremiumPillStaysTheFirstLabelForEveryRightsCombination() {
        listOf(
            """{"ugc_pay":1}""" to "充电专属",
            """{"ugc_pay_preview":1}""" to "充电专属",
            """{"ugc_pay":1,"pay":1}""" to "充电专属",
            """{"pay":1}""" to "付费",
            """{"arc_pay":1}""" to "付费",
            """{"pay_free_watch":1}""" to null,
            """{"pay":0,"arc_pay":0,"ugc_pay":0,"ugc_pay_preview":0}""" to null,
        ).forEach { (rights, pill) ->
            val video = json.decodeFromString<PopularItem>("""{"rights":$rights}""").toVideoItem()
            assertEquals(pill, resolveVideoPremiumBadgeLabel(video.rights), rights)
            assertEquals(listOfNotNull(pill), video.statusLabels, rights)
        }
        assertNull(resolveVideoPremiumBadgeLabel(json.decodeFromString<PopularItem>("""{"rights":null}""").toVideoItem().rights))
        val paidCooperation = json.decodeFromString<PopularItem>("""{"rights":{"pay":1,"is_cooperation":1}}""").toVideoItem()
        assertEquals("付费", resolveVideoPremiumBadgeLabel(paidCooperation.rights))
        assertEquals(listOf("付费", "合作"), paidCooperation.statusLabels)
    }

    @Test fun spaceChargeChipAgreesWithTheChargingLabelForEveryLegacyCondition() {
        listOf(
            """{"is_charging_arc":true}""" to true,
            """{"elec_arc_type":1}""" to true,
            """{"is_ugcpay":true}""" to true,
            """{"ugc_pay":1}""" to true,
            """{"ugc_pay_preview":2}""" to true,
            """{"elec_arc_type":2}""" to false,
            """{"is_charging_arc":false,"is_ugcpay":false,"ugc_pay":0,"ugc_pay_preview":0}""" to false,
            """{}""" to false,
        ).forEach { (payload, charged) ->
            val video = json.decodeFromString<SpaceVideoItem>(payload)
            assertEquals(if (charged) "充电专属" else null, resolveSpaceVideoChargeBadgeLabel(video), payload)
            assertEquals(if (charged) listOf("充电专属") else none, video.statusLabels, payload)
        }
    }

    @Test fun paidIsOnlyDerivedFromPopularFamilyRights() {
        assertLabels(LabelEntry.POPULAR, """{"rights":{"arc_pay":1}}""" to listOf("付费"))
        // Search, space and aggregate items carry no rights model, so these keys are ignored.
        assertLabels(LabelEntry.SEARCH, """{"rights":{"pay":1,"arc_pay":1}}""" to none)
        assertLabels(LabelEntry.SPACE, """{"pay":1,"arc_pay":1,"rights":{"pay":1}}""" to none)
        assertLabels(LabelEntry.AGGREGATE, """{"pay":1,"rights":{"pay":1}}""" to none)
    }

    @Test fun integerTypedFlagsReadJsonBooleansAsZeroWhileBooleanTypedFlagsAcceptThemLikeUpstream() {
        // FlexibleIntSerializer reads true as 0 and "1" as 1. Parity with upstream, unverified against live payloads.
        assertLabels(LabelEntry.POPULAR,
            """{"rights":{"is_cooperation":true}}""" to none,
            """{"rights":{"is_cooperation":"1"}}""" to listOf("合作"),
            """{"rights":{"is_stein_gate":true}}""" to none,
            """{"rights":{"is_stein_gate":"1"}}""" to listOf("互动"))
        assertLabels(LabelEntry.SEARCH,
            """{"is_union_video":true}""" to none,
            """{"is_union_video":"1"}""" to listOf("合作"),
            """{"is_union_video":1}""" to listOf("合作"),
            """{"is_union_video":0}""" to none)
        assertLabels(LabelEntry.SPACE,
            """{"is_union_video":true}""" to none,
            """{"is_union_video":"1"}""" to listOf("合作"),
            """{"is_union_video":1}""" to listOf("合作"))
        // FlexibleBooleanSerializer accepts true, 1 and "1".
        assertLabels(LabelEntry.SEARCH,
            """{"is_steins":true}""" to listOf("互动"),
            """{"is_steins":"1"}""" to listOf("互动"),
            """{"is_lesson_video":true}""" to listOf("课堂"),
            """{"is_lesson_video":0}""" to none)
        assertLabels(LabelEntry.SPACE,
            """{"is_cooperation":true}""" to listOf("合作"),
            """{"is_cooperation":1}""" to listOf("合作"),
            """{"is_cooperation":false,"is_union_video":0}""" to none)
        assertLabels(LabelEntry.AGGREGATE,
            """{"is_cooperation":true}""" to listOf("合作"),
            """{"is_steins":1}""" to listOf("互动"),
            """{"is_lesson_video":false}""" to none)
    }

    @Test fun badgeElementsThatAreNotObjectsFailTheWholeResponseExactlyLikeUpstreamModels() {
        // Upstream parity: List<VideoContentBadge> is strict even with ignoreUnknownKeys, so one malformed
        // badge rejects the page. Unverified against live payloads; a Root decision if the API ever sends strings.
        assertFailsWith<SerializationException> {
            json.decodeFromString<PopularResponse>("""{"code":0,"data":{"list":[{"badges":["独家"]}]}}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<SearchTypeResponse>("""{"code":0,"data":{"result":[{"badges":[null]}]}}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<SpaceVideoResponse>("""{"code":0,"data":{"list":{"vlist":[{"badges":["限免"]}]}}}""")
        }
        // The replaced list models also type the home feeds, so the same strictness reaches 推荐 pages.
        assertFailsWith<SerializationException> {
            json.decodeFromString<MobileFeedResponse>("""{"code":0,"data":{"items":[{"badges":["独家"]}]}}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<RecommendResponse>("""{"code":0,"data":{"item":[{"badges":[1]}]}}""")
        }
    }
}
