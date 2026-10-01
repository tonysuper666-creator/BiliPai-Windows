package com.bilipai.desktop.danmaku

import com.android.purebilibili.data.model.response.PlayerInfoData

/** Fixed online source identity. Both required callbacks belong to the existing playback owner. */
class DesktopOwnedWebMaskSource internal constructor(
    val bvid: String,
    val cid: Long,
    val sourceVersion: Long,
    val accountEpoch: Long,
    val stillOwned: () -> Boolean,
    val metadata: suspend (String, Long) -> PlayerInfoData,
) {
    init { require(bvid.isNotBlank() && cid > 0 && sourceVersion >= 0 && accountEpoch >= 0) }
}
