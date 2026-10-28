package com.android.purebilibili.feature.video.screen

/** 新入口指定内容时，旧播放器的成功状态不能充当该入口的首屏。 */
internal fun matchesAudioModeInitialSource(
    requestedBvid: String,
    requestedCid: Long,
    loadedBvid: String,
    loadedCid: Long,
): Boolean = requestedBvid.isBlank() ||
    (requestedBvid == loadedBvid && (requestedCid <= 0L || requestedCid == loadedCid))
