package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.TopicRepository
import com.android.purebilibili.data.repository.TopicFeedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Shares the one authorized DesktopRepository. No separate cookies or player. */
interface DesktopStoryTopicDataSource {
    val sessionEpoch: StateFlow<Long>
    suspend fun homePage(index: Int): List<VideoItem>
    suspend fun isVerticalVideo(bvid: String, aid: Long): Boolean
    suspend fun topicDetails(topicId: Long): TopicTopDetails
    suspend fun topicFeed(topicId: Long, offset: String = "", sortBy: Int = 0): TopicFeedPage
}

class DesktopStoryTopicRepository(
    private val repository: DesktopRepository,
    private val discovery: DesktopDiscoveryRepository,
    private val windowOpen: () -> Boolean = { true },
) : DesktopStoryTopicDataSource {
    override val sessionEpoch: StateFlow<Long> get() = repository.sessionEpochFlow

    override suspend fun homePage(index: Int): List<VideoItem> = owned {
        require(index in 0 until Int.MAX_VALUE)
        discovery.page(DiscoverySection.RECOMMEND, index + 1).items
    }
    override suspend fun isVerticalVideo(bvid: String, aid: Long): Boolean = owned {
        try {
            repository.videoDetails(bvid.ifBlank { "av$aid" }).raw?.dimension?.isVertical == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false } // Original VideoRepository returns false on a failed dimension lookup.
    }
    override suspend fun topicDetails(topicId: Long): TopicTopDetails = owned { upstream { TopicRepository.getTopicDetail(topicId).getOrThrow() } }
    override suspend fun topicFeed(topicId: Long, offset: String, sortBy: Int): TopicFeedPage = owned {
        upstream { TopicRepository.getTopicFeed(topicId, offset, sortBy).getOrThrow() }
    }
    /** Upstream TopicRepository runs over NetworkModule: its requests belong to an owner captured when the operation
     * starts, for this window's account (TON-1082). */
    private suspend fun <T> upstream(block: suspend () -> T): T =
        DesktopUpstreamCalls.withOwnerScope(repository.upstreamOwner(DesktopUpstreamPurpose.Main, windowOpen), block)
    private suspend fun <T> owned(action: suspend () -> T): T {
        val epoch = sessionEpoch.value
        repository.ensureSession()
        ensureEpoch(epoch)
        val result = action()
        ensureEpoch(epoch)
        return result
    }
    private fun ensureEpoch(epoch: Long) {
        if (epoch != sessionEpoch.value) throw CancellationException("Story/Topic session changed")
    }
}
