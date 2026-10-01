package com.android.purebilibili.feature.video.viewmodel
import kotlinx.coroutines.*
internal fun originalVideoCoinBalanceLoader(api: com.android.purebilibili.core.network.BilibiliApi, hasSession: () -> Boolean, assertOwned: () -> Unit): VideoCoinBalanceLoader = VideoCoinBalanceLoader {
    currentCoroutineContext().ensureActive(); assertOwned()
    if (!hasSession()) {
        -4.0
    } else {
        try {
            val response = withContext(Dispatchers.IO) {
                withTimeout(5_000L) { api.getNavInfo().also { currentCoroutineContext().ensureActive(); assertOwned() } }
            }
            when {
                response.code == 0 && response.data?.isLogin == true -> response.data.money
                response.code == 0 -> -3.0
                else -> -1.0
            }
        } catch (_: TimeoutCancellationException) {
            -2.0
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            assertOwned()
            -2.0
        }
    }
}

