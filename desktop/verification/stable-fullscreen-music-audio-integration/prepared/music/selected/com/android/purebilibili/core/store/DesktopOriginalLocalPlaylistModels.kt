package com.android.purebilibili.core.store
import kotlinx.serialization.Serializable
@Serializable
data class LocalPlaylistItem(
    val bvid: String,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    val durationSec: Long = 0L
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val source: String = "local",
    val createdAtMs: Long = 0L,
    val items: List<LocalPlaylistItem> = emptyList()
)

