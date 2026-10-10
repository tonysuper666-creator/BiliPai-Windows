package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.core.store.DesktopFeedSettings
import kotlinx.coroutines.CoroutineScope

/** Required views of Root's existing network/settings/session graph. Services must enforce this
 * owner's immutable epoch at transport/CookieJar admission, not only before/after coroutine awaits.
 * This adapter constructs no HTTP client, account store, DTO, recommendation planner or settings. */
internal class DesktopHomeProtocolEnvironment(
    val api: BilibiliApi,
    val guestApi: BilibiliApi,
    val messageApi: MessageApi,
    val parentScope: CoroutineScope,
    val isCurrent: () -> Boolean,
    val commitIfCurrent: ((() -> Unit) -> Boolean),
    val feedApiType: () -> DesktopFeedSettings.FeedApiType,
    val refreshCount: () -> Int,
    val wbiKeys: suspend () -> Result<Pair<String, String>>,
    val accessToken: () -> String?,
    val csrf: () -> String?,
    val buvid3: () -> String?,
    val awaitSessionRestored: suspend () -> Unit,
    val ensureBuvid3FromSpi: suspend () -> Unit,
) {
    /** Only the existing original Action protocol consumes this stateless view. Its API
     * and primary tokens belong to the actual caller's immutable source-bound request.
     * Unused Home planning/message ports remain the existing Root references. */
    fun forActionRequest(request: DesktopOriginalVideoRepositoryBinding): DesktopHomeProtocolEnvironment {
        request.assertCurrent()
        return DesktopHomeProtocolEnvironment(
            api = request.primaryApi,
            guestApi = request.environment.guestApi,
            messageApi = messageApi,
            parentScope = parentScope,
            isCurrent = { runCatching { request.assertCurrent() }.isSuccess },
            commitIfCurrent = request::admitCurrentMutation,
            feedApiType = feedApiType, refreshCount = refreshCount, wbiKeys = wbiKeys,
            accessToken = request::primaryAccessToken,
            csrf = request::primaryCsrf,
            buvid3 = buvid3, awaitSessionRestored = awaitSessionRestored,
            ensureBuvid3FromSpi = request.environment.ensureBuvid,
        )
    }
}
