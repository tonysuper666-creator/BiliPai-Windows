package com.bilipai.desktop.data

import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.TopicFeedPage
import com.android.purebilibili.data.repository.TopicRepository
import com.android.purebilibili.feature.story.*
import com.android.purebilibili.feature.video.ui.pager.*
import com.android.purebilibili.feature.dynamic.components.resolveDynamicRichTextTopicId
import com.bilipai.desktop.ui.desktopTopicDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.*

internal class StoryTopicFixtureData : DesktopStoryTopicDataSource {
    override val sessionEpoch = MutableStateFlow(7L)
    var homes: suspend (Int) -> List<VideoItem> = { emptyList() }
    var details: suspend (Long) -> TopicTopDetails = { TopicTopDetails(topicItem = TopicItem(it, "离线话题", "真实响应模型"), topicCreator = TopicCreator(51, "创作者")) }
    var topics: suspend (Long, String, Int) -> TopicFeedPage = { _, _, sort -> TopicFeedPage(emptyList(), "", false, selectedSortBy = sort) }
    override suspend fun homePage(index: Int) = homes(index)
    override suspend fun isVerticalVideo(bvid: String, aid: Long) = bvid != "BV-landscape"
    override suspend fun topicDetails(topicId: Long) = details(topicId)
    override suspend fun topicFeed(topicId: Long, offset: String, sortBy: Int) = topics(topicId, offset, sortBy)
}
internal fun storyFixtureVideo(index: Int, cid: Long = index.toLong() + 100) = VideoItem(aid = index.toLong(), bvid = "BV-fixture-$index", cid = cid,
    title = listOf("海洋纪录片","机器人对弈","经典交响乐","雪山攀登","算法课程","手工陶艺","宇宙星云","田园料理","古城旅行")[index-1],
    pic = "", duration = 30 + index, owner = Owner(index.toLong(), "作者 $index"))
internal suspend fun waitStoryFixture(condition: () -> Boolean) { withTimeout(3000) { while (!condition()) delay(2) } }

internal suspend fun runStoryTopicDataFixture(): Int = coroutineScope {
    var count = 0
    fun verify(ok: Boolean, message: String) { assertTrue(ok, message); count++ }
    val json = Json { ignoreUnknownKeys = true }
    val detail = json.decodeFromString<TopicDetailResponse>("""{"code":0,"data":{"top_details":{"topic_item":{"id":91,"name":"原协议","view":9000},"topic_creator":{"uid":51,"name":"作者"}}}}""")
    var response = json.decodeFromString<TopicFeedResponse>("""{"code":0,"data":{"topic_card_list":{"has_more":true,"offset":"cursor-2","items":[{"dynamic_card_item":{"id_str":"100","visible":true}},{"dynamic_card_item":{"id_str":"hidden","visible":false}},{"topic_type":"empty"}],"topic_sort_by_conf":{"show_sort_by":2,"all_sort_by":[{"sort_by":2,"sort_name":"热门"},{"sort_by":3,"sort_name":"最新"}]}}}}""")
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val api = Proxy.newProxyInstance(DynamicApi::class.java.classLoader, arrayOf(DynamicApi::class.java)) { _, method, args ->
        calls += method.name to args.orEmpty().dropLast(1)
        when (method.name) { "getTopicDetail" -> detail; "getTopicFeed" -> response; else -> error(method.name) }
    } as DynamicApi
    val original = TopicRepository(api)
    verify(original.getTopicDetail(91).getOrThrow().topicItem?.name == "原协议", "Original topic detail response mapping")
    val page = original.getTopicFeed(91, "cursor-1", 3).getOrThrow()
    verify(page.items.map { it.id_str } == listOf("100"), "Original topic mapper removes null/invisible cards")
    verify(page.selectedSortBy == 2 && page.offset == "cursor-2" && page.hasMore, "Original server sort/cursor/hasMore remain intact")
    verify(calls.last().second.take(4) == listOf(91L, 3, "cursor-1", 20), "Exact original Retrofit topic arguments")
    response = TopicFeedResponse(data = null)
    verify(!original.getTopicFeed(91, "old", 3).getOrThrow().hasMore, "Missing card list is original empty success")
    response = TopicFeedResponse(code = -101, message = "未登录")
    verify(original.getTopicFeed(91).isFailure && original.getTopicDetail(0).isFailure, "Original response error/invalid-id guards")
    val cancellationApi = Proxy.newProxyInstance(DynamicApi::class.java.classLoader, arrayOf(DynamicApi::class.java)) { _, _, _ -> throw CancellationException("cancel") } as DynamicApi
    var cancelled = false
    try { TopicRepository(cancellationApi).getTopicFeed(91) } catch (_: CancellationException) { cancelled = true }
    verify(cancelled, "Windows binding must propagate coroutine cancellation")

    val data = StoryTopicFixtureData(); val fetched = mutableListOf<Int>()
    data.homes = { index -> fetched += index; if (index == 0) (1..6).map(::storyFixtureVideo) else listOf(storyFixtureVideo(6), storyFixtureVideo(7)) }
    val story = DesktopStoryController(data, this, DesktopStorySeed("BV-seed", 88, title = "种子"))
    try {
        story.refresh(); waitStoryFixture { !story.state.value.feed.isLoading }
        verify(story.queue().first().bvid == "BV-seed" && story.queue().first().preferredCid == 88L, "Seed retains requested CID through queue conversion")
        verify(story.state.value.pages.size == 7 && story.queue().drop(1).all { it.preferredCid > 100 }, "Original mapping preserves all feed play identities")
        val previous = story.queue().map { it.bvid }; val selected = story.queue().indexOfFirst { it.bvid == "BV-fixture-6" }
        story.commitPage(selected); waitStoryFixture { fetched.size == 2 && !story.state.value.feed.isLoading }
        verify(fetched == listOf(0,1) && story.queue().map { it.bvid }.take(previous.size) == previous, "Original near-end prefetch appends without reshuffle")
        verify(story.queue().count { it.bvid == "BV-fixture-6" } == 1 && story.queue().last().bvid == "BV-fixture-7", "Original Story AID/BVID/ID dedup")
        verify(story.state.value.feed.currentIndex == selected, "Append retains the committed portrait page")
        verify(resolveCommittedPage(true, 1, 0) == null && resolveCommittedPage(false, 1, 0) == 1, "Only settled pager commits trigger playback")
    } finally { story.close() }
    val av = videoItemToStoryItem(VideoItem(aid=44,title="AV-only"))!!
    verify(buildStoryPortraitFeed(listOf(av))?.initialInfo?.bvid == "av44", "Original AV fallback uses unified videoDetails lookup")
    var lookups=0
    val filtered=filterPortraitOnlyVerticalRecommendations(listOf(RelatedVideo(bvid="known",isVertical=true), RelatedVideo(bvid="wide",isVertical=false),RelatedVideo(bvid="unknown")),true) { _, _ -> lookups++; true }
    verify(filtered.map { it.bvid } == listOf("known","unknown") && lookups == 1, "Original portrait filter trusts dimensions and resolves only unknown")

    val stale = CompletableDeferred<List<VideoItem>>(); val entered=CompletableDeferred<Unit>()
    data.homes = { entered.complete(Unit); withContext(NonCancellable) { stale.await() } }
    val old = DesktopStoryController(data,this)
    old.refresh(); entered.await(); data.sessionEpoch.value++; stale.complete(listOf(storyFixtureVideo(9))); delay(20)
    verify(old.state.value.pages.isEmpty(), "Old-account Story response cannot publish")
    old.close()

    val topicData=StoryTopicFixtureData(); var failSort=false; val sortCalls=mutableListOf<Triple<Long,String,Int>>()
    topicData.topics = { id, offset, sort -> sortCalls += Triple(id,offset,sort)
        if(failSort) throw IllegalStateException("排序失败")
        TopicFeedPage(if(offset.isEmpty()) listOf(DynamicItem(id_str="a")) else listOf(DynamicItem(id_str="a"),DynamicItem(id_str="b")),
            if(offset.isEmpty()) "next" else "end", offset.isEmpty(), listOf(TopicSortOption(0,"热门"),TopicSortOption(1,"最新")),sort) }
    val topic=DesktopTopicController(91,topicData,this)
    try {
        topic.load(); waitStoryFixture { topic.state.value.details != null }
        topic.loadMore(); waitStoryFixture { !topic.state.value.isLoadingMore }
        verify(topic.state.value.items.map { it.id_str } == listOf("a","b") && !topic.state.value.hasMore, "Original topic pagination stable dedup/terminal cursor")
        topic.selectSort(1); waitStoryFixture { !topic.state.value.isSwitchingSort }
        verify(topic.state.value.selectedSortBy == 1 && topic.state.value.items.size == 1 && sortCalls.last() == Triple(91L,"",1), "Changing sort resets cursor and replaces feed")
        failSort=true; topic.selectSort(0); waitStoryFixture { !topic.state.value.isSwitchingSort }
        verify(topic.state.value.selectedSortBy == 1 && topic.state.value.offset == "next" && topic.state.value.hasMore, "Failed sort restores original selected sort/cursor/hasMore")
        verify(topic.state.value.items.single().id_str == "a" && topic.state.value.error == "排序失败", "Failed sort retains visible feed and reports retryable error")
    } finally { topic.close() }
    val late=CompletableDeferred<TopicFeedPage>(); val waiting=CompletableDeferred<Unit>()
    topicData.topics={ _,_,_ -> waiting.complete(Unit); withContext(NonCancellable) { late.await() } }
    val disposed=DesktopTopicController(92,topicData,this); disposed.load(); waiting.await(); disposed.close()
    late.complete(TopicFeedPage(listOf(DynamicItem(id_str="late")),"",false)); delay(20)
    verify(disposed.state.value.items.isEmpty(), "Disposed topic does not accept a late non-cooperative transport result")
    val paginationLate=CompletableDeferred<TopicFeedPage>();val paginationEntered=CompletableDeferred<Unit>()
    topicData.topics={ _,offset,sort ->
        if(offset.isNotEmpty()) { paginationEntered.complete(Unit); withContext(NonCancellable) { paginationLate.await() } }
        else TopicFeedPage(listOf(DynamicItem(id_str="sort-$sort")),"next",true,selectedSortBy=sort)
    }
    val changing=DesktopTopicController(93,topicData,this)
    changing.load();waitStoryFixture { changing.state.value.details != null }
    changing.loadMore();paginationEntered.await();changing.selectSort(1)
    waitStoryFixture { !changing.state.value.isSwitchingSort }
    paginationLate.complete(TopicFeedPage(listOf(DynamicItem(id_str="old-sort-tail")),"stale",false));delay(20)
    verify(changing.state.value.items.map { it.id_str } == listOf("sort-1") && changing.state.value.offset=="next", "Late previous-sort pagination cannot overwrite replacement feed")
    changing.close()
    val epochLate=CompletableDeferred<TopicFeedPage>();val epochEntered=CompletableDeferred<Unit>()
    topicData.topics={ _,_,_ -> epochEntered.complete(Unit); withContext(NonCancellable) { epochLate.await() } }
    val switched=DesktopTopicController(94,topicData,this);switched.load();epochEntered.await();topicData.sessionEpoch.value++
    epochLate.complete(TopicFeedPage(listOf(DynamicItem(id_str="foreign-account")),"",false));delay(20)
    verify(switched.state.value.items.isEmpty() && switched.state.value.details==null,"Old-account topic details/feed transaction cannot publish")
    switched.close()
    val draft=desktopTopicDraft(TopicItem(91,"真实话题"),"文字","标题",false)
    verify(draft.topic == DynamicPublishTopic(91,"真实话题") && draft.text == "文字", "Participation retains real topic ID in upstream publish draft")
    verify(resolveDynamicRichTextTopicId(RichTextNode(rid="91",jump_url="https://www.bilibili.com/v/topic/detail/?topic_id=99"))==91L,
        "Original topic link policy prefers positive real RID")
    verify(resolveDynamicRichTextTopicId(RichTextNode(jump_url="https://www.bilibili.com/v/topic/detail/?topic_id=99"))==99L &&
        resolveDynamicRichTextTopicId(RichTextNode(jump_url="https://www.bilibili.com/topic-detail/77"))==77L,
        "Original topic URL query/path IDs support in-product routing")
    count
}

class DesktopStoryTopicTest {
    @org.junit.jupiter.api.Test fun originalMappingPaginationAndOwnership(): Unit = runBlocking { assertEquals(26,runStoryTopicDataFixture()) }
}
