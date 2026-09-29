package com.bilipai.desktop.data

import kotlinx.serialization.Serializable
import java.io.IOException

data class VideoCard(
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String,
    val playCount: Long,
    val duration: Int,
)

data class VideoPart(val cid: Long, val title: String, val duration: Long)

data class VideoDetails(
    val bvid: String,
    val aid: Long,
    val title: String,
    val description: String,
    val cover: String,
    val author: String,
    val playCount: Long,
    val likeCount: Long,
    val pages: List<VideoPart>,
)

data class PlaybackSource(
    val videoUrl: String,
    val audioUrl: String?,
    val title: String,
    val referer: String,
    val cookieHeader: String = "",
    val quality: Int = 0,
)

data class Comment(
    val id: Long,
    val author: String,
    val avatar: String,
    val text: String,
    val likeCount: Int,
    val timestamp: Long,
)

@Serializable
data class AccountSummary(val mid: Long, val name: String, val avatar: String, val isVip: Boolean = false)

data class QrLogin(val key: String, val url: String)

sealed interface QrLoginState {
    data object Waiting : QrLoginState
    data object Scanned : QrLoginState
    data object Expired : QrLoginState
    data class Complete(val account: AccountSummary) : QrLoginState
}

// OkHttp dispatches interceptor failures through its callback only for IOExceptions.
class BiliApiException(val apiCode: Int, message: String) : IOException(message.ifBlank { "Bilibili 请求失败 ($apiCode)" })
