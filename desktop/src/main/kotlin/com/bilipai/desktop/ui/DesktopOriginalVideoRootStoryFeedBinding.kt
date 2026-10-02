package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.StoryItem
import com.android.purebilibili.data.repository.DesktopOriginalVideoStoryFeedProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No HTTP, state or feed cache authority: capture the existing request binding
 * inside each actual Holder caller coroutine and use its fixed account receipt.
 */
internal class DesktopOriginalVideoRootStoryFeedBinding(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val captureRequest: suspend (DesktopOriginalVideoOwnerAssembly) -> DesktopOriginalVideoRepositoryBinding,
) {
    suspend fun getStoryFeed(aid: Long, bvid: String): Result<List<StoryItem>> {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (!assembly.owns()) throw CancellationException("Original Story Holder owner retired")
        val binding = captureRequest(assembly)
        fun assertOwned() {
            caller.ensureActive()
            if (!assembly.owns()) throw CancellationException("Original Story Holder owner retired")
            binding.assertCurrent()
        }
        assertOwned()
        return DesktopOriginalVideoStoryFeedProtocol(binding.primaryStoryApi, ::assertOwned)
            .getStoryFeed(aid, bvid).also { assertOwned() }
    }
}
