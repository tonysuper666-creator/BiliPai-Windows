package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.story.*
import com.android.purebilibili.feature.search.TopicDetailUiState
import com.android.purebilibili.feature.search.mergeDynamicItems
import com.android.purebilibili.feature.video.ui.pager.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DesktopStorySeed(val bvid: String = "", val cid: Long = 0, val cover: String = "", val title: String = "")
data class DesktopStoryOwner(val routeId: String, val sessionEpoch: Long)
data class DesktopStoryPlaybackRequest(val owner: DesktopStoryOwner, val revision: Long,
    val queue: List<VideoCard>, val index: Int, val select: Boolean)
data class DesktopStoryPlaybackSnapshot(val owner: DesktopStoryOwner? = null, val bvid: String = "",
    val loading: Boolean = false, val error: String? = null, val queueIndex: Int = -1, val cid: Long = 0)
data class DesktopStoryState(val feed: StoryUiState = StoryUiState(), val pages: List<ViewInfo> = emptyList(),
    val nextPageIndex: Int = 0)

/** Android ViewModel scope replaced by an owned child job; original mapping/shuffle/append policies retained. */
class DesktopStoryController(private val data: DesktopStoryTopicDataSource, scope: CoroutineScope,
    private val seed: DesktopStorySeed = DesktopStorySeed(), private val onlyVertical: Boolean = false) : AutoCloseable {
    val owner = DesktopStoryOwner(UUID.randomUUID().toString(), data.sessionEpoch.value)
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private var request: Job? = null
    private val initial = seed.takeIf { it.bvid.isNotBlank() }?.let { StoryFeedSeed(it.bvid.trim(), it.cid.coerceAtLeast(0), it.cover, it.title) }
    private val mutable = MutableStateFlow(DesktopStoryState(pages = initial?.toViewInfo()?.let(::listOf).orEmpty()))
    val state = mutable.asStateFlow()
    private fun owned(token: Long) = !closed.get() && token == generation.get() && owner.sessionEpoch == data.sessionEpoch.value

    fun refresh() {
        if (closed.get()) return
        val token = generation.incrementAndGet(); request?.cancel()
        mutable.value = DesktopStoryState(feed = StoryUiState(isLoading = true), pages = initial?.toViewInfo()?.let(::listOf).orEmpty())
        request = scope.launch { fetch(token, 0, true) }
    }
    fun loadMore() {
        val state = state.value
        if (state.feed.isLoading || !owned(generation.get())) return
        mutable.update { it.copy(feed = it.feed.copy(isLoading = true, error = null)) }
        val token = generation.get()
        request = scope.launch { fetch(token, state.nextPageIndex, false) }
    }
    private suspend fun fetch(token: Long, page: Int, replace: Boolean) {
        try {
            val incoming = videoItemsToStoryItems(data.homePage(page))
            if (!owned(token)) return
            val items = if (replace) incoming else mergeStoryFeedItems(state.value.feed.items, incoming)
            val portrait = buildStoryPortraitFeed(items, initial)
            val previous = state.value.pages
            val pages = if (portrait == null) previous else {
                val filtered = filterPortraitOnlyVerticalRecommendations(portrait.recommendations, onlyVertical, data::isVerticalVideo)
                if (!owned(token)) return
                val shuffleSeed = resolvePortraitRecommendationShuffleSeed(portrait.initialInfo.bvid, portrait.initialInfo.aid)
                if (replace || previous.isEmpty()) listOf(portrait.initialInfo) + shufflePortraitRecommendations(
                    shuffleSeed, filtered, portrait.initialInfo.owner.mid).map(::desktopPortraitInfo)
                else {
                    val append = resolvePortraitExternalRecommendationAppendItems(previous.first().bvid, previous.map { it.bvid }.toSet(), filtered)
                    previous + shufflePortraitRecommendations(resolvePortraitRecommendationAppendSeed(shuffleSeed, previous.first().bvid),
                        append, previous.lastOrNull()?.owner?.mid ?: 0).map(::desktopPortraitInfo)
                }
            }
            if (!owned(token)) return
            mutable.update { it.copy(feed = it.feed.copy(items = items, isLoading = false,
                error = if (pages.isEmpty()) "暂时没有可播放的推荐视频" else null), pages = pages,
                nextPageIndex = if (replace) 1 else if (incoming.isNotEmpty()) page + 1 else page) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (owned(token)) mutable.update { it.copy(feed = it.feed.copy(isLoading = false, error = error.message ?: "加载失败")) }
        }
    }
    fun commitPage(index: Int) {
        if (!owned(generation.get()) || index !in state.value.pages.indices) return
        mutable.update { it.copy(feed = it.feed.copy(currentIndex = index)) }
        val bvid = state.value.pages[index].bvid
        val feedIndex = resolveStoryPortraitIndexForBvid(bvid, state.value.feed.items, initial?.bvid.orEmpty())
        // Original StoryViewModel updates a feed index, independent of portrait shuffle order.
        if (feedIndex >= state.value.feed.items.size - 3 && state.value.feed.items.isNotEmpty()) loadMore()
    }
    fun queue(): List<VideoCard> = state.value.pages.map { info -> VideoCard(info.bvid, info.title, info.pic,
        info.owner.name, info.stat.view.toLong(), info.pages.firstOrNull()?.duration?.toInt() ?: 0,
        progressSeconds = 0, preferredCid = info.cid, authorMid = info.owner.mid) }
    fun currentEpochIsOwned() = owned(generation.get())
    override fun close() { if (closed.compareAndSet(false, true)) { generation.incrementAndGet(); scope.cancel() } }
}

/** Original pager's lightweight ViewInfo intentionally omits CID; the unified Windows queue needs the known part. */
private fun desktopPortraitInfo(video: RelatedVideo): ViewInfo = toViewInfoForPortraitDetail(video).copy(
    cid = video.cid.coerceAtLeast(0),
    pages = listOf(Page(cid = video.cid.coerceAtLeast(0), page = 1, part = video.title, duration = video.duration.toLong().coerceAtLeast(0))))

/** Sort replacement and pagination preserve the original Topic ViewModel rollback/merge semantics. */
class DesktopTopicController(private val topicId: Long, private val data: DesktopStoryTopicDataSource,
    scope: CoroutineScope) : AutoCloseable {
    private val epoch = data.sessionEpoch.value
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private var request: Job? = null
    private val mutable = MutableStateFlow(TopicDetailUiState())
    val state = mutable.asStateFlow()
    private fun owned(token: Long) = !closed.get() && token == generation.get() && epoch == data.sessionEpoch.value
    fun load() {
        if (closed.get()) return
        if (topicId <= 0) { mutable.value = TopicDetailUiState(error = "话题不存在"); return }
        val token = generation.incrementAndGet(); request?.cancel(); mutable.value = TopicDetailUiState(isLoading = true)
        request = scope.launch {
            val detail = result { data.topicDetails(topicId) }; val feed = result { data.topicFeed(topicId) }
            if (!owned(token)) return@launch
            val page = feed.getOrNull()
            mutable.value = TopicDetailUiState(details = detail.getOrNull(), items = page?.items.orEmpty(), offset = page?.offset.orEmpty(),
                hasMore = page?.hasMore == true, sortOptions = page?.sortOptions.orEmpty(), selectedSortBy = page?.selectedSortBy ?: 0,
                error = detail.exceptionOrNull()?.message ?: feed.exceptionOrNull()?.message)
        }
    }
    fun loadMore() {
        val previous = state.value
        if (topicId <= 0 || !previous.hasMore || previous.isLoading || previous.isLoadingMore || previous.isSwitchingSort || !owned(generation.get())) return
        val token = generation.get(); mutable.update { it.copy(isLoadingMore = true) }
        request = scope.launch {
            result { data.topicFeed(topicId, previous.offset, previous.selectedSortBy) }.fold({ page ->
                if (owned(token)) mutable.update { it.copy(isLoadingMore = false, items = mergeDynamicItems(it.items, page.items),
                    offset = page.offset, hasMore = page.hasMore, error = null) }
            }, { error -> if (owned(token)) mutable.update { it.copy(isLoadingMore = false, error = error.message ?: "加载更多失败") } })
        }
    }
    fun selectSort(sortBy: Int) {
        val previous = state.value
        if (topicId <= 0 || sortBy == previous.selectedSortBy || previous.isLoading || previous.isSwitchingSort || !owned(generation.get())) return
        val token = generation.incrementAndGet(); request?.cancel()
        mutable.update { it.copy(offset = "", hasMore = false, selectedSortBy = sortBy, isSwitchingSort = true, isLoadingMore = false, error = null) }
        request = scope.launch {
            result { data.topicFeed(topicId, sortBy = sortBy) }.fold({ page ->
                if (owned(token)) mutable.update { it.copy(isSwitchingSort = false, items = page.items, offset = page.offset, hasMore = page.hasMore,
                    sortOptions = page.sortOptions.ifEmpty { it.sortOptions }, selectedSortBy = page.selectedSortBy) }
            }, { error -> if (owned(token)) mutable.update { it.copy(isSwitchingSort = false, selectedSortBy = previous.selectedSortBy,
                offset = previous.offset, hasMore = previous.hasMore, error = error.message ?: "话题动态加载失败") } })
        }
    }
    /** After successful topic publish, reload the user's selected sort without clearing details. */
    fun refreshSelectedFeed() {
        if (!owned(generation.get())) return
        val selected = state.value.selectedSortBy
        val token = generation.incrementAndGet(); request?.cancel()
        mutable.update { it.copy(isSwitchingSort = true, isLoadingMore = false) }
        request = scope.launch {
            result { data.topicFeed(topicId, sortBy = selected) }.fold({ page ->
                if (owned(token)) mutable.update { it.copy(isSwitchingSort = false, items = page.items, offset = page.offset, hasMore = page.hasMore,
                    sortOptions = page.sortOptions.ifEmpty { it.sortOptions }, selectedSortBy = page.selectedSortBy, error = null) }
            }, { error -> if (owned(token)) mutable.update { it.copy(isSwitchingSort = false, error = error.message) } })
        }
    }
    private suspend fun <T> result(action: suspend () -> T): Result<T> = try { Result.success(action()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
    override fun close() { if (closed.compareAndSet(false, true)) { generation.incrementAndGet(); scope.cancel() } }
}
