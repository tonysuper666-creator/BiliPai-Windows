@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.android.purebilibili.data.model.response

import com.android.purebilibili.core.util.ClosestTargetFallback
import com.android.purebilibili.core.util.findClosestTarget
import com.android.purebilibili.data.model.VideoDecodeFormat
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- 播放地址 Response (参考 PiliPala url.dart) ---
@Serializable
data class PlayUrlResponse(
    val code: Int = 0,
    val message: String = "",
    val data: PlayUrlData? = null
)

@Serializable
data class PlayUrlData(
    val quality: Int = 0,
    val format: String = "",
    val timelength: Long = 0,
    @SerialName("accept_format")
    val acceptFormat: String = "",
    @SerialName("accept_description")
    val acceptDescription: List<String> = emptyList(),
    @SerialName("accept_quality") 
    val acceptQuality: List<Int> = emptyList(),
    @SerialName("video_codecid")
    val videoCodecid: Int = 0,
    val durl: List<Durl>? = null,
    val dash: Dash? = null,
    @SerialName("support_formats")
    val supportFormats: List<FormatItem>? = null,
    @SerialName("last_play_time")
    val lastPlayTime: Int? = null,
    @SerialName("last_play_cid")
    val lastPlayCid: Long? = null,
    
    // [New] AI Original Sound Translation
    @SerialName("cur_language")
    val curLanguage: String? = null,
    @SerialName("dolby_type")
    val dolbyType: Int? = null,
    @SerialName("ai_audio")
    val aiAudio: AiAudioInfo? = null,
    // 校验响应可能只有此字段，不包含播放地址；不得按普通空流逐档重试。
    @SerialName("v_voucher")
    val vVoucher: String? = null
) {
    //  PiliPala 风格：提供便捷的访问方法
    val accept_quality: List<Int> get() = acceptQuality
    val accept_description: List<String> get() = acceptDescription
}

@Serializable
data class AiAudioInfo(
    val title: String = "",
    val items: List<AiAudioItem> = emptyList()
)

@Serializable
data class AiAudioItem(
    @SerialName("lang_code")
    val langCode: String = "",
    @SerialName("lang_doc")
    val langDoc: String = "",
    @SerialName("stream_url")
    val streamUrl: String = "" // Usually empty in playurl response, just indicates availability
)

@Serializable
data class Durl(
    val order: Int = 0,
    val length: Long = 0,
    val size: Long = 0,
    val url: String = "",
    @SerialName("backup_url")
    val backupUrl: List<String>? = null
) {
    val backup_url: List<String>? get() = backupUrl
}

@Serializable
data class Dash(
    val duration: Int = 0,
    @JsonNames("minBufferTime", "min_buffer_time")
    val minBufferTime: Float = 0f,
    val video: List<DashVideo> = emptyList(),
    val audio: List<DashAudio>? = emptyList(),
    val dolby: Dolby? = null,
    val flac: Flac? = null
)

//  DASH 视频流 (重命名避免与 ListModels.VideoItem 冲突)
@Serializable
data class DashVideo(
    val id: Int = 0,
    @JsonNames("baseUrl", "base_url")
    val baseUrl: String = "",
    @JsonNames("backupUrl", "backup_url")
    val backupUrl: List<String>? = null,
    @SerialName("bandwidth")
    val bandwidth: Int = 0,
    @SerialName("mime_type")
    val mimeType: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @JsonNames("frameRate", "frame_rate")
    val frameRate: String = "",
    val sar: String = "",
    @JsonNames("startWithSap", "start_with_sap")
    val startWithSap: Int? = null,
    @JsonNames("segmentBase", "segment_base")
    val segmentBase: SegmentBase? = null,
    val codecid: Int? = null
) {
    fun getValidUrl(): String = baseUrl.takeIf { it.isNotEmpty() }
        ?: backupUrl?.firstOrNull { it.isNotEmpty() } ?: ""
    
    val decodeFormat: VideoDecodeFormat?
        get() = VideoDecodeFormat.fromCodecs(codecs)
}

//  DASH 音频流
@Serializable
data class DashAudio(
    val id: Int = 0,
    @JsonNames("baseUrl", "base_url")
    val baseUrl: String = "",
    @JsonNames("backupUrl", "backup_url")
    val backupUrl: List<String>? = null,
    @SerialName("bandwidth")
    val bandwidth: Int = 0,
    @SerialName("mime_type")
    val mimeType: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @JsonNames("frameRate", "frame_rate")
    val frameRate: String = "",
    val sar: String = "",
    @JsonNames("startWithSap", "start_with_sap")
    val startWithSap: Int? = null,
    @JsonNames("segmentBase", "segment_base")
    val segmentBase: SegmentBase? = null,
    val codecid: Int? = null
) {
    fun getValidUrl(): String = baseUrl.takeIf { it.isNotEmpty() }
        ?: backupUrl?.firstOrNull { it.isNotEmpty() } ?: ""
}

@Serializable
data class SegmentBase(
    val initialization: String? = null,
    @JsonNames("indexRange", "index_range")
    val indexRange: String? = null
)

@Serializable
data class FormatItem(
    val quality: Int = 0,
    val format: String = "",
    @SerialName("new_description")
    val newDescription: String = "",
    val description: String = "",
    @SerialName("display_desc")
    val displayDesc: String = "",
    val superscript: String = "",
    val codecs: List<String>? = null,
    @SerialName("need_login")
    val needLogin: Boolean? = null,
    @SerialName("need_vip")
    val needVip: Boolean? = null
)

@Serializable
data class Dolby(
    val type: Int = 0,
    val audio: List<DashAudio>? = null
)

@Serializable
data class Flac(
    val display: Boolean = false,
    val audio: DashAudio? = null
)

// 兼容旧代码的类型别名
typealias DashMedia = DashVideo

//  扩展函数：获取最佳视频流
fun Dash.getBestVideo(
    targetQn: Int,
    preferCodec: String = "hev1",
    secondPreferCodec: String = "avc1",
    isHevcSupported: Boolean = true,
    isAv1Supported: Boolean = false
): DashVideo? {
    if (video.isEmpty()) {
        android.util.Log.w("VideoResponse", " getBestVideo: video list is empty!")
        return null
    }
    
    com.android.purebilibili.core.network.CoreDataLog.d("VideoResponse", "🔍 getBestVideo: targetQn=$targetQn, preferCodec=$preferCodec, hevc=$isHevcSupported, av1=$isAv1Supported")
    
    val validVideos = video.filter { it.getValidUrl().isNotEmpty() }
    if (validVideos.isEmpty()) {
        android.util.Log.w("VideoResponse", " getBestVideo: no video has valid URL")
        return video.firstOrNull()
    }
    
    val grouped = validVideos.groupBy { it.id }
    
    // 1. 找到匹配画质的视频列表
    val targetQualityId = grouped.keys.toList().findClosestTarget(
        target = targetQn,
        fallback = ClosestTargetFallback.NEAREST_HIGHER
    )
    val targetVideos = targetQualityId?.let { grouped[it] } ?: validVideos
    
    // 2. 根据编码格式偏好进行排序选择
    // 权重策略：
    // - 优先匹配用户偏好且设备如果支持
    // - 其次降级：AV1 -> HEVC -> AVC
    // - 不支持的格式降权
    // - 同等可用性/编码偏好时，选择更低 bandwidth 的轨，避免同为 1080P 时吃到高码率源
    
    val selected = targetVideos.sortedWith(
        compareByDescending<DashVideo> { video ->
            videoPlaybackSelectionScore(
                video = video,
                preferCodec = preferCodec,
                secondPreferCodec = secondPreferCodec,
                isHevcSupported = isHevcSupported,
                isAv1Supported = isAv1Supported
            )
        }.thenBy { video -> normalizedSelectionBandwidth(video.bandwidth) }
    ).firstOrNull()
    
    com.android.purebilibili.core.network.CoreDataLog.d(
        "VideoResponse",
        " getBestVideo: selected id=${selected?.id}, codec=${selected?.codecs}, bandwidth=${selected?.bandwidth ?: 0}"
    )
    return selected
}

private fun videoPlaybackSelectionScore(
    video: DashVideo,
    preferCodec: String,
    secondPreferCodec: String,
    isHevcSupported: Boolean,
    isAv1Supported: Boolean
): Int {
    var score = 0
    val codecFormat = VideoDecodeFormat.fromCodecs(video.codecs)
    val preferredFormat = VideoDecodeFormat.fromCodecs(preferCodec)
    val secondPreferredFormat = VideoDecodeFormat.fromCodecs(secondPreferCodec)

    val isAvc = codecFormat == VideoDecodeFormat.AVC
    val isHevc = codecFormat == VideoDecodeFormat.HEVC
    val isAv1 = codecFormat == VideoDecodeFormat.AV1

    val supported = when {
        isAvc -> true
        isHevc -> isHevcSupported
        isAv1 -> isAv1Supported
        else -> false
    }

    if (!supported) {
        return -100
    }

    if (preferredFormat != null && codecFormat == preferredFormat) {
        score += 10
    } else if (secondPreferredFormat != null && codecFormat == secondPreferredFormat) {
        score += 6
    }

    if (isAv1) score += 3
    else if (isHevc) score += 2
    else if (isAvc) score += 1

    return score
}

private fun normalizedSelectionBandwidth(bandwidth: Int): Int {
    return if (bandwidth > 0) bandwidth else Int.MAX_VALUE
}

//  扩展函数：获取最佳音频流
fun Dash.getBestAudio(preferQuality: Int = -1): DashAudio? {
    val validBaseAudio = audio.orEmpty().filter { it.id > 0 && it.getValidUrl().isNotBlank() }
    if (preferQuality > 0) {
        val returnedAudio = validBaseAudio +
            dolby?.takeIf { it.type > 0 }?.audio.orEmpty()
                .filter { it.id > 0 && it.getValidUrl().isNotBlank() } +
            listOfNotNull(flac?.audio?.takeIf { it.id > 0 && it.getValidUrl().isNotBlank() })
        returnedAudio.filter { it.id == preferQuality }
            .maxByOrNull { it.bandwidth }
            ?.let { return it }
    }
    // AUTO and missing requests retain the best ordinary audio; never reinterpret a raw ID
    // as an ordinal quality or silently enable a premium stream.
    return validBaseAudio.filterNot { track ->
        val codec = track.codecs.lowercase().replace("_", "-").replace(" ", "")
        (codec.isBlank() && (track.id == 30250 || track.id == 30255 || track.id == 30251)) ||
            codec == "flac" || codec.contains("ec-3") || codec.contains("e-ac-3") ||
            codec.contains("eac3") || codec == "ec3"
    }.maxByOrNull { it.bandwidth }
}
