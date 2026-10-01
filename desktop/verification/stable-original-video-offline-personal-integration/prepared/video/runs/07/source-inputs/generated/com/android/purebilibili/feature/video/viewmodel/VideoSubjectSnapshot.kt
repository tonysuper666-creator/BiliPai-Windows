package com.android.purebilibili.feature.video.viewmodel
import androidx.compose.runtime.Immutable
@Immutable
data class VideoSubjectSnapshot(
    val bvid: String,
    val cid: Long,
    val aid: Long,
    val ownerMid: Long,
    val title: String,
    val coverUrl: String,
    val durationMs: Long,
    val generation: Long
)

