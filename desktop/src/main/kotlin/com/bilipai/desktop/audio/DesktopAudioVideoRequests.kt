package com.bilipai.desktop.audio

import com.android.purebilibili.data.model.response.PlayUrlData
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.ui.DesktopOriginalVideoRepositoryBinding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The request-scoped binding is captured by the video owner, never by a global provider. */
internal class DesktopAudioVideoRequest private constructor(
    private val binding: DesktopOriginalVideoRepositoryBinding,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopAudioVideoRequest> {
        fun captured(binding: DesktopOriginalVideoRepositoryBinding) = DesktopAudioVideoRequest(binding)
    }

    suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int): PlayUrlData? {
        currentCoroutineContext().ensureActive()
        binding.assertCurrent()
        val result = try {
            binding.rawRepository.getPlayUrlData(bvid, cid, qn, audioLang = null)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            binding.assertCurrent()
            throw failure
        }
        currentCoroutineContext().ensureActive()
        binding.assertCurrent()
        return result
    }
}

internal suspend fun <T> withDesktopAudioVideoRequest(
    binding: DesktopOriginalVideoRepositoryBinding,
    action: suspend () -> T,
): T {
    currentCoroutineContext().ensureActive()
    binding.assertCurrent()
    return withContext(DesktopAudioVideoRequest.captured(binding)) { action() }
}

internal suspend fun requireDesktopAudioVideoRequest(): DesktopAudioVideoRequest =
    currentCoroutineContext()[DesktopAudioVideoRequest] ?: throw UnsupportedDesktopCapability(
        "Audio video request without a captured video binding",
        "run inside withDesktopAudioVideoRequest using the real request binding",
    )
