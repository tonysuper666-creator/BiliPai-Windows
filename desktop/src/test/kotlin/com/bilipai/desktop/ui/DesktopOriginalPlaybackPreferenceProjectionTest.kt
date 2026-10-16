package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalPortraitSettings
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerPreferencesStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class DesktopOriginalPlaybackPreferenceProjectionTest {
    private class Fixture(val root: Path = Files.createTempDirectory("bp-private-playback-projection-")) {
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        val projection = DesktopOriginalPlaybackPreferenceProjection(store)
        val file: Path get() = root.resolve("plugin-settings.json")
        fun bytes(): ByteArray = Files.readAllBytes(file)
        fun settings(key: String) = store.preferences("settings")[key]!!.jsonPrimitive
        fun mirror(name: String, key: String) = store.preferences(name)[key]!!.jsonPrimitive
    }

    /** The legacy player-settings.json values of a user who changed every duplicated option. */
    private fun legacyCopy() = PlayerPreferences(videoCodecPreference = "av01", videoSecondCodecPreference = "hev1",
        defaultAudioQuality = 30251, subtitleAutoPreference = SubtitleAutoPreference.ON, speed = 2.0, defaultSpeed = 1.5,
        rememberLastSpeed = false, speedOptions = listOf(1.0, 1.5, 2.0))

    /** Canonical values written by the original setters, as the Settings pane does. */
    private suspend fun arrangeCanonical(f: Fixture, remember: Boolean) {
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalVideoPlayerSettings.removePlaybackSpeedOption(f.context, 3f)
            DesktopOriginalPlaybackSettingsPreferences.setVideoCodec(f.context, "avc1")
            DesktopOriginalPlaybackSettingsPreferences.setVideoSecondCodec(f.context, "av01")
            DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(f.context, SubtitleAutoPreference.WITHOUT_AI)
            DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(f.context, 30250)
            DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(f.context, 1.5f)
            DesktopOriginalVideoPlayerSettings.setRememberLastPlaybackSpeed(f.context, remember)
            DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(f.context, 2f)
        }
    }

    /** A store seeded from [legacyCopy] by the real startup function, plus that legacy copy. */
    private suspend fun seeded(): Pair<Fixture, PlayerPreferences> {
        val f = Fixture()
        val legacy = legacyCopy()
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        return f to legacy
    }

    @Test fun `absent canonical keys fall back to the legacy copy and reading never writes`(): Unit = runBlocking {
        val f = Fixture()
        val legacy = legacyCopy()
        val current = f.projection.current(legacy)
        assertEquals(legacy.normalized(), current)
        assertEquals(1.5, current.preferredSpeed)
        assertFalse(Files.exists(f.file))
    }

    @Test fun `canonical values win over a different legacy copy and legacy stays unchanged`(): Unit = runBlocking {
        val f = Fixture()
        arrangeCanonical(f, remember = false)
        val legacy = legacyCopy()
        val current = f.projection.current(legacy)
        assertEquals("avc1", current.videoCodecPreference)
        assertEquals("av01", current.videoSecondCodecPreference)
        assertEquals(30250, current.defaultAudioQuality)
        assertEquals(SubtitleAutoPreference.WITHOUT_AI, current.subtitleAutoPreference)
        assertEquals(1.5, current.defaultSpeed)
        assertFalse(current.rememberLastSpeed)
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.3, 1.5, 1.75, 2.0, 2.5), current.speedOptions)
        assertEquals(legacyCopy(), legacy)
    }

    @Test fun `initial speed is the original default or remembered speed decision`(): Unit = runBlocking {
        for ((remember, expected) in listOf(false to 1.5f, true to 2.0f)) {
            val f = Fixture()
            arrangeCanonical(f, remember)
            val legacy = PlayerPreferences(defaultSpeed = 1.0, rememberLastSpeed = !remember, speed = 1.25)
            val current = f.projection.current(legacy)
            assertEquals(expected, DesktopOriginalVideoPlayerSettings.resolvePreferredPlaybackSpeed(1.5f, remember, 2f))
            assertEquals(expected, DesktopOriginalVideoPlayerSettings.getPreferredPlaybackSpeed(f.context).first())
            assertEquals(expected.toDouble(), current.preferredSpeed)
        }
    }

    @Test fun `last selected audio quality follows the original canonical setter and its mirror`(): Unit = runBlocking {
        val f = Fixture()
        val legacy = legacyCopy()
        assertEquals(-1, f.projection.current(legacy.copy(lastSelectedAudioQuality = -1)).lastSelectedAudioQuality)
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalPortraitSettings.setAudioQuality(f.context, 30250)
        }
        assertEquals(30250, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
        assertEquals(30250, f.projection.current(legacy).lastSelectedAudioQuality)
    }

    @Test fun `saved legacy remembered audio reaches canonical cached PGC getter and disk together`(): Unit = runBlocking {
        val f = Fixture()
        val legacy = legacyCopy().copy(defaultAudioQuality = 30250, lastSelectedAudioQuality = 30251)
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertEquals(30250, f.settings("default_audio_quality").int)
        assertEquals(30251, f.settings("audio_quality_preference").int)
        assertEquals(30250, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
        assertEquals(30251, f.projection.current(legacy).lastSelectedAudioQuality)
        val disk = Json.parseToJsonElement(Files.readString(f.file)).jsonObject
        assertEquals(JsonPrimitive(30251), disk["settings"]!!.jsonObject["audio_quality_preference"])
        assertEquals(JsonPrimitive(30251), disk["quality_settings"]!!.jsonObject["audio_quality"])
    }

    @Test fun `legacy automatic remembered audio sentinel is seeded without forcing a selected track`(): Unit = runBlocking {
        val f = Fixture()
        ensureDesktopWindowsPlaybackDefaults(f.context, legacyCopy().copy(lastSelectedAudioQuality = -1))
        assertEquals(-1, f.settings("audio_quality_preference").int)
        assertEquals(-1, f.mirror("quality_settings", "audio_quality").int)
        assertEquals(-1, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
    }

    @Test fun `existing audio mirrors seed missing canonical choices before different legacy values`(): Unit = runBlocking {
        val f = Fixture()
        f.store.update("quality_settings", mapOf(
            "default_audio_quality" to JsonPrimitive(30251), "audio_quality" to JsonPrimitive(30250)))
        val legacy = legacyCopy().copy(defaultAudioQuality = 30250, lastSelectedAudioQuality = 30251)
        // Runtime projection and startup migration must select the same original choices.
        assertEquals(30251, f.projection.current(legacy).defaultAudioQuality)
        assertEquals(30250, f.projection.current(legacy).lastSelectedAudioQuality)
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertEquals(30251, f.settings("default_audio_quality").int)
        assertEquals(30250, f.settings("audio_quality_preference").int)
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
        assertEquals(30250, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
    }

    @Test fun `canonical only audio choices populate missing original mirrors without changing canonical`(): Unit = runBlocking {
        val f = Fixture()
        f.store.update("settings", mapOf("default_audio_quality" to JsonPrimitive(30251),
            "audio_quality_preference" to JsonPrimitive(30250), "unrelated" to JsonPrimitive(37)))
        ensureDesktopWindowsPlaybackDefaults(f.context,
            legacyCopy().copy(defaultAudioQuality = 30250, lastSelectedAudioQuality = -1))
        assertEquals(30251, f.settings("default_audio_quality").int)
        assertEquals(30250, f.settings("audio_quality_preference").int)
        assertEquals(37, f.settings("unrelated").int)
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
        assertEquals(30250, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
    }

    @Test fun `canonical automatic audio choices win conflicts and cached PGC mirrors follow`(): Unit = runBlocking {
        val f = Fixture()
        f.store.update("settings", mapOf("default_audio_quality" to JsonPrimitive(-2),
            "audio_quality_preference" to JsonPrimitive(-1)))
        f.store.update("quality_settings", mapOf("default_audio_quality" to JsonPrimitive(30251),
            "audio_quality" to JsonPrimitive(30251), "unrelated" to JsonPrimitive(37)))
        val legacy = legacyCopy().copy(defaultAudioQuality = 30250, lastSelectedAudioQuality = 30250)
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertEquals(-2, f.settings("default_audio_quality").int)
        assertEquals(-1, f.settings("audio_quality_preference").int)
        assertEquals(-2, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
        assertEquals(-1, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
        assertEquals(-2, f.projection.current(legacy).defaultAudioQuality)
        assertEquals(-1, f.projection.current(legacy).lastSelectedAudioQuality)
        assertEquals(37, f.mirror("quality_settings", "unrelated").int)
    }

    @Test fun `audio seed is once only and later original choices survive a different legacy copy`(): Unit = runBlocking {
        val f = Fixture()
        ensureDesktopWindowsPlaybackDefaults(f.context,
            legacyCopy().copy(defaultAudioQuality = 30250, lastSelectedAudioQuality = 30251))
        val seeded = f.bytes()
        ensureDesktopWindowsPlaybackDefaults(f.context,
            legacyCopy().copy(defaultAudioQuality = 30251, lastSelectedAudioQuality = -1))
        assertContentEquals(seeded, f.bytes())
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(f.context, 30251)
            DesktopOriginalPortraitSettings.setAudioQuality(f.context, 30250)
        }
        val manuallySelected = f.bytes()
        ensureDesktopWindowsPlaybackDefaults(f.context,
            legacyCopy().copy(defaultAudioQuality = -2, lastSelectedAudioQuality = -1))
        assertContentEquals(manuallySelected, f.bytes())
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
        assertEquals(30250, DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(f.context))
    }

    @Test fun `state overlay keeps the runtime speed unless the remembered speed is requested`(): Unit = runBlocking {
        val f = Fixture()
        arrangeCanonical(f, remember = true)
        val snapshot = f.projection.snapshot(legacyCopy())
        val state = PlayerPreferences(speed = 1.75)
        assertEquals(1.75, snapshot.applyTo(state).speed)
        assertEquals(2.0, snapshot.applyTo(state, includeLastSpeed = true).speed)
        assertEquals("avc1", snapshot.applyTo(state).videoCodecPreference)
    }

    @Test fun `a collector applies only the fields canonical changed so an unpublished dialog edit survives`(): Unit = runBlocking {
        val f = Fixture()
        arrangeCanonical(f, remember = true)
        val legacy = legacyCopy()
        val earlier = f.projection.snapshot(legacy)
        val optimistic = f.projection.current(legacy).copy(videoCodecPreference = "hev1")
        // The Shell writes the last speed on every speed change; that emission must not revert the codec edit.
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(f.context, 1.25f)
        }
        val later = f.projection.snapshot(legacy)
        assertEquals("hev1", later.applyTo(optimistic, since = earlier).videoCodecPreference)
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(f.context, SubtitleAutoPreference.ON)
        }
        val published = f.projection.snapshot(legacy)
        assertEquals(SubtitleAutoPreference.ON, published.applyTo(optimistic, since = later).subtitleAutoPreference)
        assertEquals("avc1", published.applyTo(optimistic).videoCodecPreference)
    }

    @Test fun `legacy copy migrates into canonical and original mirrors once`(): Unit = runBlocking {
        val f = Fixture()
        val legacy = legacyCopy()
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertEquals("av01", DesktopOriginalPlaybackSettingsPreferences.getVideoCodec(f.context).first())
        assertEquals("hev1", DesktopOriginalPlaybackSettingsPreferences.getVideoSecondCodec(f.context).first())
        assertEquals(SubtitleAutoPreference.ON, DesktopOriginalPlaybackSettingsPreferences.getSubtitleAutoPreference(f.context).first())
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getDefaultAudioQuality(f.context).first())
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
        assertEquals(listOf(1f, 1.5f, 2f), DesktopOriginalVideoPlayerSettings.getPlaybackSpeedOptions(f.context).first())
        assertEquals(1.5f, DesktopOriginalVideoPlayerSettings.getDefaultPlaybackSpeed(f.context).first())
        assertFalse(DesktopOriginalVideoPlayerSettings.getRememberLastPlaybackSpeed(f.context).first())
        assertEquals(2f, DesktopOriginalVideoPlayerSettings.getLastPlaybackSpeed(f.context).first())
        assertEquals(1.5f, DesktopOriginalVideoPlayerSettings.getPreferredPlaybackSpeedSync(f.context))
        assertEquals("av01", f.mirror("quality_settings", "video_codec").content)
        assertEquals(legacy.normalized(), f.projection.current(legacy))
        val published = f.bytes()
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertContentEquals(published, f.bytes())
        // A later manual choice survives a startup that is handed a different legacy copy.
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setVideoCodec(f.context, "avc1")
        }
        ensureDesktopWindowsPlaybackDefaults(f.context, PlayerPreferences(videoCodecPreference = "hev1"))
        assertEquals("avc1", DesktopOriginalPlaybackSettingsPreferences.getVideoCodec(f.context).first())
    }

    @Test fun `seeding publishes exactly what the original setters publish`(): Unit = runBlocking {
        val (seededFixture, _) = seeded()
        val original = Fixture()
        DesktopOriginalPlaybackPreferenceOperation.run(original.context, { true }) {
            listOf(0.25f, 0.5f, 0.75f, 1.25f, 1.3f, 1.75f, 2.5f, 3f).forEach {
                DesktopOriginalVideoPlayerSettings.removePlaybackSpeedOption(original.context, it)
            }
            DesktopOriginalPlaybackSettingsPreferences.setVideoCodec(original.context, "av01")
            DesktopOriginalPlaybackSettingsPreferences.setVideoSecondCodec(original.context, "hev1")
            DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(original.context, SubtitleAutoPreference.ON)
            DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(original.context, 30251)
            DesktopOriginalPortraitSettings.setAudioQuality(original.context, legacyCopy().lastSelectedAudioQuality)
            DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(original.context, 1.5f)
            DesktopOriginalVideoPlayerSettings.setRememberLastPlaybackSpeed(original.context, false)
            DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(original.context, 2f)
        }
        listOf("video_codec_preference", "video_second_codec_preference", "default_audio_quality", "audio_quality_preference",
            "subtitle_auto_preference", "playback_speed_options", "default_playback_speed",
            "remember_last_playback_speed", "last_playback_speed").forEach { key ->
            assertEquals(original.store.preferences("settings")[key], seededFixture.store.preferences("settings")[key], key)
        }
        listOf("video_codec", "video_second_codec", "default_audio_quality", "audio_quality").forEach { key ->
            assertEquals(original.store.preferences("quality_settings")[key], seededFixture.store.preferences("quality_settings")[key], key)
        }
        listOf("default_speed", "remember_last_speed", "last_speed").forEach { key ->
            assertEquals(original.store.preferences("playback_speed_cache")[key], seededFixture.store.preferences("playback_speed_cache")[key], key)
        }
    }

    @Test fun `ZIP restore with marker and canonical keeps canonical over the legacy file from the same archive`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-playback-restore-marked-")
        Files.writeString(root.resolve("plugin-settings.json"), """{"settings":{
            "video_codec_preference":"avc1","video_second_codec_preference":"av01","default_audio_quality":30250,
            "subtitle_auto_preference":3,"playback_speed_options":"1.0,1.25,2.0","default_playback_speed":1.25,
            "remember_last_playback_speed":true,"last_playback_speed":2.0},
            "windows_desktop_defaults":{"highest_quality_v1":true,"legacy_playback_v1":true}}""")
        val legacyFile = PlayerPreferencesStore(root.resolve("player-settings.json"))
        legacyFile.save(legacyCopy())
        val legacy = legacyFile.read()
        val f = Fixture(root)
        val before = f.store.preferences("settings")
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertEquals(before, f.store.preferences("settings"))
        val current = f.projection.current(legacy)
        assertEquals("avc1", current.videoCodecPreference)
        assertEquals(SubtitleAutoPreference.AUTO, current.subtitleAutoPreference)
        assertEquals(listOf(1.0, 1.25, 2.0), current.speedOptions)
        assertEquals(2.0, current.preferredSpeed)
    }

    @Test fun `ZIP restore from a build without the marker seeds only the absent keys from the archived legacy file`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-playback-restore-unmarked-")
        Files.writeString(root.resolve("plugin-settings.json"), """{"settings":{
            "auto_highest_quality":true,"unrelated":37,"video_codec_preference":"hev1","remember_last_playback_speed":true},
            "windows_desktop_defaults":{"highest_quality_v1":true}}""")
        val legacyFile = PlayerPreferencesStore(root.resolve("player-settings.json"))
        legacyFile.save(legacyCopy())
        val legacy = legacyFile.read()
        val f = Fixture(root)
        ensureDesktopWindowsPlaybackDefaults(f.context, legacy)
        assertEquals("hev1", f.settings("video_codec_preference").content)
        assertTrue(f.settings("remember_last_playback_speed").boolean)
        assertEquals("hev1", f.settings("video_second_codec_preference").content)
        assertEquals(30251, f.settings("default_audio_quality").int)
        assertEquals(37, f.settings("unrelated").int)
        assertTrue(f.settings("auto_highest_quality").boolean)
        assertTrue(f.mirror("windows_desktop_defaults", DesktopOriginalPlaybackPreferenceProjection.LEGACY_SEED_MARKER).boolean)
        assertEquals(1.5f, DesktopOriginalVideoPlayerSettings.getDefaultPlaybackSpeed(f.context).first())
        assertEquals(2f, DesktopOriginalVideoPlayerSettings.getPreferredPlaybackSpeedSync(f.context))
    }

    @Test fun `changes emits for canonical edits and not for unrelated keys`(): Unit = runBlocking {
        val f = Fixture()
        val seen = CopyOnWriteArrayList<DesktopOriginalPlaybackPreferenceSnapshot>()
        val collector = launch(Dispatchers.Unconfined) { f.projection.changes(PlayerPreferences()).collect { seen += it } }
        withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
        assertEquals(1, seen.size)
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(f.context, SubtitleAutoPreference.AUTO)
        }
        withTimeout(5_000) { while (seen.size < 2) delay(10) }
        assertEquals(SubtitleAutoPreference.AUTO, seen.last().subtitleAutoPreference)
        f.store.update("settings", mapOf("hw_decode" to JsonPrimitive(false), "unrelated" to JsonPrimitive(1)))
        f.store.update("privacy_mode", mapOf("enabled" to JsonPrimitive(true)))
        delay(100)
        assertEquals(2, seen.size)
        collector.cancel()
    }

    @Test fun `the flow builds its value from the delivered snapshots and equals the locked read`(): Unit = runBlocking {
        val f = Fixture()
        arrangeCanonical(f, remember = true)
        val legacy = legacyCopy()
        // Canonical last audio quality is absent: only the quality_settings mirror, the flow's second snapshot, holds it.
        f.store.update("quality_settings", mapOf("audio_quality" to JsonPrimitive(30250)))
        assertEquals(30250, f.projection.snapshot(legacy).lastSelectedAudioQuality)
        assertEquals(f.projection.snapshot(legacy), f.projection.changes(legacy).first())
        val seen = CopyOnWriteArrayList<DesktopOriginalPlaybackPreferenceSnapshot>()
        val collector = launch(Dispatchers.Unconfined) { f.projection.changes(legacy).collect { seen += it } }
        withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(f.context, SubtitleAutoPreference.AUTO)
        }
        withTimeout(5_000) { while (seen.last().subtitleAutoPreference != SubtitleAutoPreference.AUTO) delay(10) }
        assertEquals(f.projection.snapshot(legacy), seen.last())
        f.store.update("quality_settings", mapOf("audio_quality" to JsonPrimitive(30251)))
        withTimeout(5_000) { while (seen.last().lastSelectedAudioQuality != 30251) delay(10) }
        assertEquals(f.projection.snapshot(legacy), seen.last())
        collector.cancel()
    }

    @Test fun `a dialog save writes only the changed fields the facade does not already persist`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val previous = f.projection.current(legacy)
        val next = previous.copy(videoCodecPreference = "hev1", videoSecondCodecPreference = "avc1",
            subtitleAutoPreference = SubtitleAutoPreference.OFF, defaultAudioQuality = 30250, rememberLastSpeed = true).normalized()
        assertTrue(f.projection.needsWrite(previous, next))
        val before = f.store.preferences("settings")
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) { f.projection.writeChanged(f.context, previous, next) }
        val after = f.store.preferences("settings")
        assertEquals(setOf("default_audio_quality", "remember_last_playback_speed"), after.filterKeys { after[it] != before[it] }.keys)
        assertEquals(30250, f.mirror("quality_settings", "default_audio_quality").int)
        assertTrue(f.mirror("playback_speed_cache", "remember_last_speed").boolean)
        assertEquals("av01", f.settings("video_codec_preference").content)
    }

    @Test fun `without an ordinary assembly the dialog also writes codec x2 and subtitle`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val previous = f.projection.current(legacy)
        val next = previous.copy(videoCodecPreference = "hev1", videoSecondCodecPreference = "avc1",
            subtitleAutoPreference = SubtitleAutoPreference.OFF).normalized()
        assertFalse(f.projection.needsWrite(previous, next))
        assertTrue(f.projection.needsWrite(previous, next, includeFacadeBridged = true))
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            f.projection.writeChanged(f.context, previous, next, includeFacadeBridged = true)
        }
        assertEquals("hev1", f.settings("video_codec_preference").content)
        assertEquals("hev1", f.mirror("quality_settings", "video_codec").content)
        assertEquals("avc1", f.settings("video_second_codec_preference").content)
        assertEquals(SubtitleAutoPreference.OFF.ordinal, f.settings("subtitle_auto_preference").int)
    }

    @Test fun `speed menu edit goes through add and remove and the original reconciliation`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val previous = f.projection.current(legacy)
        val next = previous.copy(speedOptions = listOf(1.0, 2.0, 3.0)).normalized()
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) { f.projection.writeChanged(f.context, previous, next) }
        assertEquals(listOf(1f, 2f, 3f), DesktopOriginalVideoPlayerSettings.getPlaybackSpeedOptions(f.context).first())
        assertEquals(1f, DesktopOriginalVideoPlayerSettings.getDefaultPlaybackSpeed(f.context).first())
        assertEquals(2f, DesktopOriginalVideoPlayerSettings.getLastPlaybackSpeed(f.context).first())
        assertEquals(1f, f.mirror("playback_speed_cache", "default_speed").float)
        assertEquals(2f, f.mirror("playback_speed_cache", "last_speed").float)
        assertEquals(next.speedOptions, f.projection.current(legacy).speedOptions)
    }

    @Test fun `an unchanged save and runtime-only fields write nothing`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val previous = f.projection.current(legacy)
        val runtimeOnly = previous.copy(speed = 1.25, volume = 10.0, muted = true, hardwareDecodeEnabled = false, audioOnly = true)
        assertFalse(f.projection.needsWrite(previous, previous))
        assertFalse(f.projection.needsWrite(previous, runtimeOnly))
        val bytes = f.bytes()
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) { f.projection.writeChanged(f.context, previous, runtimeOnly) }
        assertContentEquals(bytes, f.bytes())
    }

    @Test fun `restore playback defaults writes the duplicated fields once and the next projection equals them`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val previous = f.projection.current(legacy)
        val defaults = PlayerPreferences(danmaku = previous.danmaku).normalized()
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            f.projection.writeChanged(f.context, previous, defaults, includeFacadeBridged = true)
        }
        val snapshot = f.projection.snapshot(legacy)
        assertEquals("hev1", snapshot.videoCodecPreference)
        assertEquals("avc1", snapshot.videoSecondCodecPreference)
        assertEquals(-2, snapshot.defaultAudioQuality)
        assertEquals(SubtitleAutoPreference.OFF, snapshot.subtitleAutoPreference)
        assertEquals(1.0, snapshot.defaultSpeed)
        assertTrue(snapshot.rememberLastSpeed)
        assertEquals(defaults.speedOptions, snapshot.speedOptions)
        assertFalse(f.projection.needsWrite(snapshot.applyTo(previous), defaults, includeFacadeBridged = true))
    }

    @Test fun `an open dialog unrelated edit cannot rewrite newer canonical settings`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val opened = f.projection.current(legacy)
        val draft = opened.copy(audioOnly = !opened.audioOnly)
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setVideoCodec(f.context, "avc1")
            DesktopOriginalPlaybackSettingsPreferences.setSubtitleAutoPreference(f.context, SubtitleAutoPreference.AUTO)
            DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(f.context, 30250)
        }
        val latest = f.projection.current(legacy).copy(speed = 1.75)
        val before = f.bytes()
        val saved = resolvePlaybackSettingsDraftSave(draft, latest, opened)
        assertEquals(latest.copy(audioOnly = draft.audioOnly), saved)
        assertFalse(f.projection.needsWrite(latest, saved, includeFacadeBridged = true))
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            f.projection.writeChanged(f.context, latest, saved, includeFacadeBridged = true)
        }
        assertContentEquals(before, f.bytes())
        assertEquals("avc1", f.settings("video_codec_preference").content)
        assertEquals(30250, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(f.context))
    }

    @Test fun `a retired final Root permit publishes nothing from a dialog save`(): Unit = runBlocking {
        val (f, legacy) = seeded()
        val previous = f.projection.current(legacy)
        val next = previous.copy(defaultAudioQuality = 30250, defaultSpeed = 2.0).normalized()
        var current = true
        val retired = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(f.store), { current }, { current = false; false })
        val bytes = f.bytes()
        assertFailsWith<CancellationException> {
            DesktopOriginalPlaybackPreferenceOperation.run(retired, { true }) { f.projection.writeChanged(retired, previous, next) }
        }
        assertContentEquals(bytes, f.bytes())
    }
}
