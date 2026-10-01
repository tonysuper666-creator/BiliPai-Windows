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
)
