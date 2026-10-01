package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi

/** Construct inside the SAME original playback invocation, using that binding's
 * primary API with existing Store/entry/caller-job admission. No independent
 * Retrofit/client, account state, CSRF cache, or persistence is allocated. */
internal class DesktopOriginalVideoNoteEnvironment(
    val api: BilibiliApi,
    val hasSession: () -> Boolean,
    val csrf: () -> String?,
    val assertOwned: () -> Unit,
)
