package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.CoroutineScope

/** One immutable navigation entry. The existing retained Home requests remain the transport. */
internal class DesktopCategoryEnvironment(
    val tid: Int,
    val settings: DesktopHomeSettingsPort,
    val parentScope: CoroutineScope,
    val stillOwned: () -> Boolean,
    val commitIfCurrent: ((() -> Unit) -> Boolean),
    val getRegionVideos: suspend (Int, Int) -> Result<List<VideoItem>>,
)
