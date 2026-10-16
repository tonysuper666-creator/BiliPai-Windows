package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.nearestPlaybackSpeed
import com.android.purebilibili.core.store.normalizePlaybackSpeedOptions
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/** The canonical (original DataStore) values behind the Windows [PlayerPreferences] fields that
 * duplicate original settings. Doubles use the same hundredths rounding as [PlayerPreferences].
 */
internal data class DesktopOriginalPlaybackPreferenceSnapshot(
    val videoCodecPreference: String,
    val videoSecondCodecPreference: String,
    val defaultAudioQuality: Int,
    val lastSelectedAudioQuality: Int,
    val subtitleAutoPreference: SubtitleAutoPreference,
    val defaultSpeed: Double,
    val rememberLastSpeed: Boolean,
    val lastSpeed: Double,
    val speedOptions: List<Double>,
) {
    /** Replaces only the duplicated settings. The runtime [PlayerPreferences.speed] is kept unless
     * [includeLastSpeed] asks for the remembered speed (initial state), so a later settings edit
     * never changes the speed of a video that is already playing. With [since], only fields whose
     * canonical value differs from that earlier snapshot are replaced: an optimistic Window edit
     * that canonical has not published yet is not reverted by an unrelated emission.
     */
    fun applyTo(base: PlayerPreferences, includeLastSpeed: Boolean = false,
                since: DesktopOriginalPlaybackPreferenceSnapshot? = null): PlayerPreferences {
        fun <T> pick(now: T, before: T?, kept: T): T = if (since == null || now != before) now else kept
        return base.copy(
            videoCodecPreference = pick(videoCodecPreference, since?.videoCodecPreference, base.videoCodecPreference),
            videoSecondCodecPreference = pick(videoSecondCodecPreference, since?.videoSecondCodecPreference, base.videoSecondCodecPreference),
            defaultAudioQuality = pick(defaultAudioQuality, since?.defaultAudioQuality, base.defaultAudioQuality),
            lastSelectedAudioQuality = pick(lastSelectedAudioQuality, since?.lastSelectedAudioQuality, base.lastSelectedAudioQuality),
            subtitleAutoPreference = pick(subtitleAutoPreference, since?.subtitleAutoPreference, base.subtitleAutoPreference),
            defaultSpeed = pick(defaultSpeed, since?.defaultSpeed, base.defaultSpeed),
            rememberLastSpeed = pick(rememberLastSpeed, since?.rememberLastSpeed, base.rememberLastSpeed),
            speedOptions = pick(speedOptions, since?.speedOptions, base.speedOptions),
            speed = if (includeLastSpeed) lastSpeed else base.speed,
        ).normalized()
    }
}

/** Makes the original canonical keys the single authority for the codec, subtitle, speed and audio
 * choices that the legacy [PlayerPreferences] copy used to shadow (pattern of
 * [DesktopOriginalHardwareDecodePreferences]). Reads are projections; absent canonical keys fall
 * back to the legacy copy field by field. The one-time absence-only migration lives in
 * [legacySeedEdits] and is published by [ensureDesktopWindowsPlaybackDefaults]. Edits made in the
 * legacy playback dialog reach canonical through [writeChanged] and the original setters.
 * Inert until Root's Shell constructs it and passes the legacy snapshot.
 */
internal class DesktopOriginalPlaybackPreferenceProjection(private val store: DesktopPluginStore) {
    /** Canonical values, or the legacy copy for every key the Store does not hold yet. One-off read:
     * [DesktopPluginStore.snapshot] takes the Store monitor, which a writer holds across its file
     * replacement, so [changes] builds its values from the snapshots its flow delivers instead.
     */
    fun snapshot(legacy: PlayerPreferences): DesktopOriginalPlaybackPreferenceSnapshot =
        snapshotFrom(store.snapshot("settings").value, store.snapshot(QUALITY_NAMESPACE).value, legacy)

    /** The one place the projection is computed, from the `settings` and `quality_settings` snapshots. */
    private fun snapshotFrom(settings: DesktopPreferenceSnapshot, quality: DesktopPreferenceSnapshot,
                             legacy: PlayerPreferences): DesktopOriginalPlaybackPreferenceSnapshot {
        val values = DesktopOriginalPlayerPreferenceValues(settings)
        val old = legacy.normalized()
        // The original resolver, so a stored list, its legacy-key fallback and 1x stay original.
        val options = if (values[optionsKey] != null) DesktopOriginalVideoPlayerSettings.playbackSpeedOptions(values)
            else legacyOptions(old, values[defaultSpeedKey], values[lastSpeedKey])
        return DesktopOriginalPlaybackPreferenceSnapshot(
            videoCodecPreference = values[codecKey] ?: old.videoCodecPreference,
            videoSecondCodecPreference = values[secondCodecKey] ?: old.videoSecondCodecPreference,
            defaultAudioQuality = values[defaultAudioKey] ?: quality[defaultAudioKey] ?: old.defaultAudioQuality,
            lastSelectedAudioQuality = values[lastAudioKey] ?: quality[audioMirrorKey] ?: old.lastSelectedAudioQuality,
            subtitleAutoPreference = values[subtitleKey]?.let { raw ->
                SubtitleAutoPreference.entries.getOrElse(raw) { SubtitleAutoPreference.OFF }
            } ?: old.subtitleAutoPreference,
            defaultSpeed = nearestPlaybackSpeed(values[defaultSpeedKey] ?: old.defaultSpeed.optionFloat(), options).hundredths(),
            rememberLastSpeed = values[rememberKey] ?: old.rememberLastSpeed,
            lastSpeed = nearestPlaybackSpeed(values[lastSpeedKey] ?: old.speed.optionFloat(), options).hundredths(),
            speedOptions = options.map { it.hundredths() },
        )
    }

    /** Initial Window state: the legacy copy overlaid with canonical. [PlayerPreferences.speed] is the
     * remembered speed, so `preferredSpeed` applies the original default/remember policy.
     */
    fun current(legacy: PlayerPreferences): PlayerPreferences = snapshot(legacy).applyTo(legacy, includeLastSpeed = true)

    /** Emits when any projected value changes. Collectors must stay state-only: a codec or subtitle
     * edit is already persisted by the original setters, and the video owner reloads on a codec change.
     * Each value is built from the two snapshots the flow delivers, so an emission never takes the Store
     * monitor. One write that publishes both namespaces can therefore emit a value between them first;
     * the final state always follows as the next emission.
     */
    fun changes(legacy: PlayerPreferences): Flow<DesktopOriginalPlaybackPreferenceSnapshot> = combine(
        store.snapshot("settings"), store.snapshot(QUALITY_NAMESPACE)
    ) { settings, quality -> snapshotFrom(settings, quality, legacy) }.distinctUntilChanged()

    /** True when [writeChanged] would publish anything for this Save. */
    fun needsWrite(previous: PlayerPreferences, next: PlayerPreferences, includeFacadeBridged: Boolean = false): Boolean =
        !changeBetween(previous, next, includeFacadeBridged).isEmpty

    /** Writes only the fields that differ between [previous] and [next] through the original setters.
     * Call inside the existing [DesktopOriginalPlaybackPreferenceOperation.run] block so the edits share
     * its single publication and Root permit. Codec x2 and subtitle edits already reach canonical
     * through the playback facade (`VideoPlaybackViewModel.setVideoCodec` and the Shell owner), and
     * the codec one also reloads the video, so they are skipped unless [includeFacadeBridged] says
     * no ordinary assembly exists to bridge them.
     */
    suspend fun writeChanged(context: DesktopOriginalPlayerSettingsContext, previous: PlayerPreferences,
                             next: PlayerPreferences, includeFacadeBridged: Boolean = false) {
        require(context.pluginContext.store === store)
        val change = changeBetween(previous, next, includeFacadeBridged)
        // Menu first: the original add/remove reconciles default and last speed, then explicit choices follow.
        change.addedOptions.forEach { DesktopOriginalVideoPlayerSettings.addPlaybackSpeedOption(context, it) }
        change.removedOptions.forEach { DesktopOriginalVideoPlayerSettings.removePlaybackSpeedOption(context, it) }
        change.defaultSpeed?.let { DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(context, it) }
        change.rememberLastSpeed?.let { DesktopOriginalVideoPlayerSettings.setRememberLastPlaybackSpeed(context, it) }
        change.defaultAudioQuality?.let { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, it) }
        change.videoCodec?.let { DesktopOriginalPlaybackSettingsPreferences.setVideoCodec(context, it) }
        change.videoSecondCodec?.let { DesktopOriginalPlaybackSettingsPreferences.setVideoSecondCodec(context, it) }
        change.subtitle?.let { DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(context, it) }
    }

    private class Change(
        val addedOptions: List<Float>, val removedOptions: List<Float>,
        val defaultSpeed: Float?, val rememberLastSpeed: Boolean?, val defaultAudioQuality: Int?,
        val videoCodec: String?, val videoSecondCodec: String?, val subtitle: SubtitleAutoPreference?,
    ) {
        val isEmpty: Boolean get() = addedOptions.isEmpty() && removedOptions.isEmpty() && defaultSpeed == null &&
            rememberLastSpeed == null && defaultAudioQuality == null && videoCodec == null &&
            videoSecondCodec == null && subtitle == null
    }

    private fun changeBetween(previous: PlayerPreferences, next: PlayerPreferences, includeFacadeBridged: Boolean): Change {
        val before = previous.normalized()
        val after = next.normalized()
        val oldOptions = before.speedOptions.map { it.optionFloat() }
        val newOptions = after.speedOptions.map { it.optionFloat() }
        return Change(
            addedOptions = newOptions - oldOptions,
            removedOptions = oldOptions - newOptions,
            defaultSpeed = after.defaultSpeed.takeIf { it != before.defaultSpeed }?.optionFloat(),
            rememberLastSpeed = after.rememberLastSpeed.takeIf { it != before.rememberLastSpeed },
            defaultAudioQuality = after.defaultAudioQuality.takeIf { it != before.defaultAudioQuality },
            videoCodec = after.videoCodecPreference.takeIf {
                includeFacadeBridged && it != before.videoCodecPreference },
            videoSecondCodec = after.videoSecondCodecPreference.takeIf {
                includeFacadeBridged && it != before.videoSecondCodecPreference },
            subtitle = after.subtitleAutoPreference.takeIf {
                includeFacadeBridged && it != before.subtitleAutoPreference },
        )
    }

    companion object {
        /** Namespace of the existing Windows one-time defaults ([ensureDesktopWindowsPlaybackDefaults]). */
        const val DEFAULTS_NAMESPACE = "windows_desktop_defaults"
        /** Set by the same publication that seeds canonical from the legacy file; never seeded twice. */
        const val LEGACY_SEED_MARKER = "legacy_playback_v1"
        private const val QUALITY_NAMESPACE = "quality_settings"

        private val codecKey = playerStringPreferencesKey("video_codec_preference")
        private val secondCodecKey = playerStringPreferencesKey("video_second_codec_preference")
        private val defaultAudioKey = playerIntPreferencesKey("default_audio_quality")
        private val lastAudioKey = playerIntPreferencesKey("audio_quality_preference")
        /** The original synchronous mirror of [lastAudioKey], in the [QUALITY_NAMESPACE] snapshot. */
        private val audioMirrorKey = playerIntPreferencesKey("audio_quality")
        private val subtitleKey = playerIntPreferencesKey("subtitle_auto_preference")
        private val defaultSpeedKey = playerFloatPreferencesKey("default_playback_speed")
        private val rememberKey = playerBooleanPreferencesKey("remember_last_playback_speed")
        private val lastSpeedKey = playerFloatPreferencesKey("last_playback_speed")
        private val optionsKey = playerStringPreferencesKey("playback_speed_options")

        /** The legacy menu plus any canonical default/last speed already stored, so those stay valid
         * choices (the original migration likewise keeps the stored default and last speed in the list).
         */
        private fun legacyOptions(old: PlayerPreferences, defaultSpeed: Float?, lastSpeed: Float?): List<Float> =
            normalizePlaybackSpeedOptions(old.speedOptions.map { it.optionFloat() } + listOfNotNull(defaultSpeed, lastSpeed))

        /** Absence-only migration edits for one atomic Store publication: every codec, audio, subtitle and
         * speed key that canonical does not hold is taken from [legacy], with the original synchronous
         * mirrors (`quality_settings`, `playback_speed_cache`) written exactly as the original setters do.
         * Canonical keys already present always win. For both audio choices, an existing original
         * mirror wins over legacy when canonical is absent; a missing or conflicting mirror follows
         * canonical in this same publication, so the original PGC cached getters agree. Empty once
         * the marker is set. [fresh] must be the
         * settings snapshot of the publication that commits these edits (the Store retries on conflict).
         *
         * A non-empty result always carries the [LEGACY_SEED_MARKER], so the legacy copy is never read for
         * seeding again. A caller whose legacy copy is unavailable must therefore pass `null` to
         * [ensureDesktopWindowsPlaybackDefaults] (it then calls nothing here: no seed, no marker) and must
         * not hand over the defaults that a failed read falls back to. Only a legacy file that is really
         * absent may seed defaults. Telling an unreadable file from a missing one needs a failure-aware read
         * in the shared player package and the Shell call site, which are outside this free file (Root decision D9).
         */
        fun legacySeedEdits(store: DesktopPluginStore, fresh: DesktopPreferenceSnapshot,
                            legacy: PlayerPreferences): Map<String, Map<String, JsonElement?>> {
            val seeded = (store.preferences(DEFAULTS_NAMESPACE)[LEGACY_SEED_MARKER] as? JsonPrimitive)?.booleanOrNull == true
            if (seeded) return emptyMap()
            val old = legacy.normalized()
            val settings = linkedMapOf<String, JsonElement?>()
            val quality = linkedMapOf<String, JsonElement?>()
            if (fresh[codecKey] == null) {
                settings[codecKey.name] = JsonPrimitive(old.videoCodecPreference)
                quality["video_codec"] = JsonPrimitive(old.videoCodecPreference)
            }
            if (fresh[secondCodecKey] == null) {
                settings[secondCodecKey.name] = JsonPrimitive(old.videoSecondCodecPreference)
                quality["video_second_codec"] = JsonPrimitive(old.videoSecondCodecPreference)
            }
            // The cached original PGC getters read this mirror. Resolve both fields using the
            // same precedence as snapshotFrom, then publish canonical + mirror in this one CAS.
            // A concurrent mirror write invalidates the backing snapshot and retries the callback.
            val audioMirror = store.snapshot(QUALITY_NAMESPACE).value
            val defaultAudio = fresh[defaultAudioKey] ?: audioMirror[defaultAudioKey] ?: old.defaultAudioQuality
            val rememberedAudio = fresh[lastAudioKey] ?: audioMirror[audioMirrorKey] ?: old.lastSelectedAudioQuality
            if (fresh[defaultAudioKey] == null) settings[defaultAudioKey.name] = JsonPrimitive(defaultAudio)
            if (audioMirror[defaultAudioKey] != defaultAudio) quality[defaultAudioKey.name] = JsonPrimitive(defaultAudio)
            if (fresh[lastAudioKey] == null) settings[lastAudioKey.name] = JsonPrimitive(rememberedAudio)
            if (audioMirror[audioMirrorKey] != rememberedAudio) quality[audioMirrorKey.name] = JsonPrimitive(rememberedAudio)
            if (fresh[subtitleKey] == null) settings[subtitleKey.name] = JsonPrimitive(old.subtitleAutoPreference.ordinal)
            val options = if (fresh[optionsKey] != null) {
                DesktopOriginalVideoPlayerSettings.playbackSpeedOptions(DesktopOriginalPlayerPreferenceValues(fresh))
            } else legacyOptions(old, fresh[defaultSpeedKey], fresh[lastSpeedKey]).also {
                settings[optionsKey.name] = JsonPrimitive(it.joinToString(","))
            }
            val defaultSpeed = fresh[defaultSpeedKey] ?: nearestPlaybackSpeed(old.defaultSpeed.optionFloat(), options).also {
                settings[defaultSpeedKey.name] = JsonPrimitive(it)
            }
            val remember = fresh[rememberKey] ?: old.rememberLastSpeed.also {
                settings[rememberKey.name] = JsonPrimitive(it)
            }
            val lastSpeed = fresh[lastSpeedKey] ?: nearestPlaybackSpeed(old.speed.optionFloat(), options).also {
                settings[lastSpeedKey.name] = JsonPrimitive(it)
            }
            val speedKeys = setOf(optionsKey.name, defaultSpeedKey.name, rememberKey.name, lastSpeedKey.name)
            val speedCache = if (settings.keys.none { it in speedKeys }) emptyMap<String, JsonElement?>() else mapOf<String, JsonElement?>(
                "default_speed" to JsonPrimitive(nearestPlaybackSpeed(defaultSpeed, options)),
                "remember_last_speed" to JsonPrimitive(remember),
                "last_speed" to JsonPrimitive(nearestPlaybackSpeed(lastSpeed, options)))
            return buildMap {
                if (settings.isNotEmpty()) put("settings", settings)
                if (quality.isNotEmpty()) put(QUALITY_NAMESPACE, quality)
                if (speedCache.isNotEmpty()) put("playback_speed_cache", speedCache)
                put(DEFAULTS_NAMESPACE, mapOf(LEGACY_SEED_MARKER to JsonPrimitive(true)))
            }
        }
    }
}

private fun Float.hundredths(): Double = (this * 100).roundToInt() / 100.0
private fun Double.optionFloat(): Float = (this * 100).roundToInt() / 100f
