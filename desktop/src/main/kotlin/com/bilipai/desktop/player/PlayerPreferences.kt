package com.bilipai.desktop.player

import com.bilipai.desktop.danmaku.DanmakuSettings
import com.android.purebilibili.core.store.DEFAULT_PLAYBACK_SPEED_OPTIONS
import com.android.purebilibili.core.store.normalizePlaybackSpeedOptions
import com.android.purebilibili.core.store.nearestPlaybackSpeed
import com.android.purebilibili.core.store.resolvePreferredPlaybackSpeed
import com.android.purebilibili.core.store.player.DEFAULT_AUDIO_QUALITY_FOLLOW_LAST
import com.android.purebilibili.feature.settings.normalizeDefaultAudioQualityOption
import com.android.purebilibili.feature.video.playback.audio.normalizeAudioQualityPreference
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.viewmodel.normalizeCodecFamilyKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random
import kotlin.math.roundToInt

@Serializable
enum class PlaybackMode { STOP_AFTER_CURRENT, SEQUENTIAL, SHUFFLE, REPEAT_ONE, REPEAT_ALL }

/** Returns a queue/part index to play, or null when the selected mode stops. */
fun resolveNextPlaybackIndex(mode: PlaybackMode, currentIndex: Int, itemCount: Int, random: Random = Random.Default): Int? {
    if (itemCount <= 0 || currentIndex !in 0 until itemCount) return null
    return when (mode) {
        PlaybackMode.STOP_AFTER_CURRENT -> null
        PlaybackMode.REPEAT_ONE -> currentIndex
        PlaybackMode.SHUFFLE -> if (itemCount == 1) 0 else random.nextInt(itemCount - 1).let { if (it >= currentIndex) it + 1 else it }
        PlaybackMode.SEQUENTIAL -> (currentIndex + 1).takeIf { it < itemCount }
        PlaybackMode.REPEAT_ALL -> (currentIndex + 1) % itemCount
    }
}

@Serializable
data class PlayerPreferences(
    val volume: Double = 75.0,
    val speed: Double = 1.0,
    val muted: Boolean = false,
    val audioOnly: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val danmaku: DanmakuSettings = DanmakuSettings(),
    val speedOptions: List<Double> = DEFAULT_PLAYBACK_SPEED_OPTIONS.map { (it * 100).roundToInt() / 100.0 },
    val defaultSpeed: Double = 1.0,
    val rememberLastSpeed: Boolean = true,
    val hardwareDecodeEnabled: Boolean = true,
    val videoCodecPreference: String = "hev1",
    val videoSecondCodecPreference: String = "avc1",
    val defaultAudioQuality: Int = DEFAULT_AUDIO_QUALITY_FOLLOW_LAST,
    val lastSelectedAudioQuality: Int = -1,
    val subtitleAutoPreference: SubtitleAutoPreference = SubtitleAutoPreference.OFF,
) {
    val preferredSpeed: Double get() = resolvePreferredPlaybackSpeed(defaultSpeed.toFloat(), rememberLastSpeed, speed.toFloat()).let { (it * 100).roundToInt() / 100.0 }
    fun normalized(): PlayerPreferences {
        val options = normalizePlaybackSpeedOptions(speedOptions.map(Double::toFloat))
        return copy(
        volume = if (volume.isFinite()) volume.coerceIn(0.0, 100.0) else 75.0,
        speed = if (speed.isFinite()) speed.coerceIn(0.1, 8.0) else 1.0,
        defaultSpeed = nearestPlaybackSpeed(if (defaultSpeed.isFinite()) defaultSpeed.toFloat().coerceIn(0.1f, 8f) else 1f, options).let { (it * 100).roundToInt() / 100.0 },
        speedOptions = options.map { (it * 100).roundToInt() / 100.0 },
        danmaku = danmaku.normalized(),
        videoCodecPreference = normalizeCodecFamilyKey(videoCodecPreference)?.takeIf { it in setOf("avc1", "hev1", "av01") } ?: "hev1",
        videoSecondCodecPreference = normalizeCodecFamilyKey(videoSecondCodecPreference)?.takeIf { it in setOf("avc1", "hev1", "av01") } ?: "avc1",
        defaultAudioQuality = normalizeDefaultAudioQualityOption(defaultAudioQuality),
        lastSelectedAudioQuality = normalizeAudioQualityPreference(lastSelectedAudioQuality),
    )
    }
}

/** Separate from the library file so player preferences survive library migrations. */
class PlayerPreferencesStore(private val file: Path = defaultFile()) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun read(): PlayerPreferences = runCatching {
        require(Files.isRegularFile(file) && Files.size(file) <= 1_048_576)
        json.decodeFromString<PlayerPreferences>(Files.readString(file)).normalized()
    }.getOrDefault(PlayerPreferences())

    @Synchronized
    fun save(preferences: PlayerPreferences) {
        val target = file.toAbsolutePath().normalize()
        val directory = requireNotNull(target.parent)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "player-settings-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(preferences.normalized()))
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        private fun defaultFile() = Path.of(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
            "BiliPaiWindows", "player-settings.json",
        )
    }
}
