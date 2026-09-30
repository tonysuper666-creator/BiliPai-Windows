package com.bilipai.desktop.data

import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.TopicRepository
import com.android.purebilibili.data.repository.TopicFeedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

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
) : DesktopStoryTopicDataSource {
    override val sessionEpoch: StateFlow<Long> get() = repository.sessionEpochFlow
    private val topic = TopicRepository(Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(repository.httpClient)
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .asConverterFactory("application/json".toMediaType()))
        .build().create(DynamicApi::class.java))

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
    override suspend fun topicDetails(topicId: Long): TopicTopDetails = owned { topic.getTopicDetail(topicId).getOrThrow() }
    override suspend fun topicFeed(topicId: Long, offset: String, sortBy: Int): TopicFeedPage = owned {
        topic.getTopicFeed(topicId, offset, sortBy).getOrThrow()
    }
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
