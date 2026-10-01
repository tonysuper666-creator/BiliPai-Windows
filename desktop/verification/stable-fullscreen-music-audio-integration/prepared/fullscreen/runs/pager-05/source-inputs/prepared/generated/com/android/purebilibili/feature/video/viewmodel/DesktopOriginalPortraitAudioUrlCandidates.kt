package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.DashAudio
internal fun buildPlaybackAudioUrlCandidates(
    audioUrl: String?,
    cachedDashAudios: List<DashAudio>
): List<String> {
    val selectedAudio = audioUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { selectedUrl ->
            cachedDashAudios.firstOrNull { audio ->
                audio.getValidUrl() == selectedUrl ||
                    audio.backupUrl.orEmpty().any { backupUrl -> backupUrl == selectedUrl }
            }
        }

    return buildList {
        audioUrl?.takeIf { it.isNotBlank() }?.let(::add)
        selectedAudio
            ?.backupUrl
            .orEmpty()
            .filter { it.isNotBlank() }
            .let(::addAll)
    }.distinct()
}
