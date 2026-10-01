package com.android.purebilibili.core.store
import kotlinx.serialization.Serializable
@Serializable
data class PlayHistoryEntry(
    val bvid: String,
    val cid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    val durationSec: Long = 0L,
    val playCount: Int = 1,
    val lastPlayedAtMs: Long = 0L
)

@Serializable
data class PlayLastSession(
    val bvid: String,
    val cid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    val positionMs: Long = 0L,
    val savedAtMs: Long = 0L
)

