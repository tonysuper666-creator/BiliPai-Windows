package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BgmDetailData
import com.android.purebilibili.data.model.response.BgmRecommendVideo
import com.android.purebilibili.data.model.response.EmotePackage
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.feature.audio.bgm.BgmDetailViewModel
import com.android.purebilibili.feature.audio.bgm.DesktopBgmDetailRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The upstream BgmDetailViewModel (compiled from the merged root, patched onto the desktop request owner).
 * Upstream has no view-model test for it and the generated v0.3.x copy had none either, so these cases pin the behaviour
 * of the upstream reducers and the owner gate the desktop copy provided: a retired owner admits no state write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBgmDetailViewModelTest {
    private class FakeRequests(
        var owned: Boolean = true,
        var login: Boolean = true,
        var detail: Result<BgmDetailData?> = Result.success(BgmDetailData(musicTitle = "Song", wishCount = 5)),
        var videos: Result<List<BgmRecommendVideo>> = Result.success(listOf(BgmRecommendVideo(bvid = "BV1"))),
        var wish: Result<SimpleApiResponse> = Result.success(SimpleApiResponse(code = 0)),
    ) : DesktopBgmDetailRequests {
        val wishCalls = mutableListOf<Pair<String, Int>>()
        var detailCalls = 0
        override fun isOwned() = owned
        override fun hasLogin() = login
        override suspend fun getBgmDetail(musicId: String, aid: Long, cid: Long): Result<BgmDetailData?> { detailCalls++; return detail }
        override suspend fun getAllBgmRecommendVideos(musicId: String) = videos
        override suspend fun updateBgmWish(musicId: String, state: Int): Result<SimpleApiResponse> { wishCalls += musicId to state; return wish }
        override suspend fun getEmotePackages(): Result<List<EmotePackage>> = Result.success(emptyList())
    }

    private fun TestScope.model(requests: FakeRequests): Pair<BgmDetailViewModel, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        return BgmDetailViewModel(scope, requests) to scope
    }

    @Test
    fun `initialize loads the detail and the recommended videos once per request`() = runTest {
        val requests = FakeRequests()
        val (model, scope) = model(requests)
        model.initialize("M1", 1L, 2L, showVideos = true)
        advanceUntilIdle()
        val state = model.state.value
        assertFalse(state.loading)
        assertEquals("Song", state.detail?.musicTitle)
        assertEquals(listOf("BV1"), state.videos.map { it.bvid })
        assertEquals(1, state.revision)
        model.initialize("M1", 1L, 2L, showVideos = true) // same request: no second load
        advanceUntilIdle()
        assertEquals(1, requests.detailCalls)
        scope.cancel()
    }

    @Test
    fun `a failed detail load keeps the real error`() = runTest {
        val requests = FakeRequests(detail = Result.failure(IOException("网络不可用")))
        val (model, scope) = model(requests)
        model.initialize("M1", 1L, 2L, showVideos = false)
        advanceUntilIdle()
        assertEquals("网络不可用", model.state.value.error)
        assertFalse(model.state.value.loading)
        scope.cancel()
    }

    @Test
    fun `a retired owner admits no state write`() = runTest {
        val requests = FakeRequests(owned = false)
        val (model, scope) = model(requests)
        model.initialize("M1", 1L, 2L, showVideos = true)
        advanceUntilIdle()
        assertTrue(model.state.value.loading, "the state stays as it was before the retired owner's load")
        assertNull(model.state.value.detail)
        assertEquals(0, model.state.value.revision)
        scope.cancel()
    }

    @Test
    fun `wishing without a login sends a message and no request`() = runTest {
        val requests = FakeRequests(login = false)
        val (model, scope) = model(requests)
        model.initialize("M1", 1L, 2L, showVideos = false)
        advanceUntilIdle()
        model.toggleWish()
        advanceUntilIdle()
        assertEquals("请先登录", model.messages.first())
        assertTrue(requests.wishCalls.isEmpty())
        scope.cancel()
    }

    @Test
    fun `wishing toggles the flag and count, and a rejected wish restores the button`() = runTest {
        val requests = FakeRequests()
        val (model, scope) = model(requests)
        model.initialize("M1", 1L, 2L, showVideos = false)
        advanceUntilIdle()
        model.toggleWish()
        advanceUntilIdle()
        assertEquals(listOf("M1" to 1), requests.wishCalls)
        assertTrue(model.state.value.detail!!.wishListen)
        assertEquals(6, model.state.value.detail!!.wishCount)
        assertFalse(model.state.value.updatingWish)

        requests.wish = Result.success(SimpleApiResponse(code = -101, message = "账号未登录"))
        model.toggleWish()
        advanceUntilIdle()
        assertEquals("M1" to 2, requests.wishCalls.last())
        assertEquals("账号未登录", model.messages.first())
        assertTrue(model.state.value.detail!!.wishListen, "a rejected wish changes nothing")
        assertFalse(model.state.value.updatingWish)
        scope.cancel()
    }
}
