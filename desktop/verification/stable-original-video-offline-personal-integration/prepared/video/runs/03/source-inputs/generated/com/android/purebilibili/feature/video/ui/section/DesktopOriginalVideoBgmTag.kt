package com.android.purebilibili.feature.video.ui.section
import com.android.purebilibili.data.model.response.BgmInfo
internal fun resolveBgmTagInfo(tag: com.android.purebilibili.data.model.response.VideoTag): BgmInfo? =
    if (tag.tag_type == "bgm" && (tag.music_id.isNotBlank() || tag.jump_url.isNotBlank())) {
        BgmInfo(musicId = tag.music_id, musicTitle = tag.tag_name, jumpUrl = tag.jump_url, coverUrl = tag.cover)
    } else null

