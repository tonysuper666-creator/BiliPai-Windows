package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerPreferencesReadResult
import com.bilipai.desktop.player.PlayerPreferencesStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The real startup boundary: one failure-aware legacy read, the actual atomic defaults writer,
 * original canonical projection and the PGC port. No replacement migration model or native player. */
class DesktopWindowsSettingsStartupMigrationTest {
    @TempDir lateinit var directory: Path

    private class Fixture(root: Path) {
        val legacyFile = root.resolve("player-settings.json")
        val legacy = PlayerPreferencesStore(legacyFile)
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        val projection = DesktopOriginalPlaybackPreferenceProjection(store)
        val pgc = DesktopBangumiPgcAudioPreferences(context)
    }

    @Test fun `unavailable legacy does not consume migration and a repaired file can still seed audio`(): Unit = runBlocking {
        val f = Fixture(directory)
        val damaged = "{invalid legacy".toByteArray()
        Files.write(f.legacyFile, damaged)
        val initialRead = f.legacy.readForMigration()
        assertSame(PlayerPreferencesReadResult.Unavailable, initialRead)
        assertNull(initialRead.migrationSeed)
        ensureDesktopWindowsPlaybackDefaults(f.context, initialRead.migrationSeed)
        assertFalse(f.store.preferences("windows_desktop_defaults")
            .containsKey(DesktopOriginalPlaybackPreferenceProjection.LEGACY_SEED_MARKER))
        assertFalse(f.store.preferences("settings").containsKey("audio_quality_preference"))
        assertContentEquals(damaged, Files.readAllBytes(f.legacyFile))

        f.legacy.save(PlayerPreferences(defaultAudioQuality = 30251, lastSelectedAudioQuality = 30250))
        val repairedRead = assertIs<PlayerPreferencesReadResult.Loaded>(f.legacy.readForMigration())
        val repairedBytes = Files.readAllBytes(f.legacyFile)
        ensureDesktopWindowsPlaybackDefaults(f.context, repairedRead.migrationSeed)
        assertEquals(30251, f.pgc.defaultQuality())
        assertEquals(30250, f.pgc.rememberedQuality())
        assertEquals(30251, f.projection.current(repairedRead.preferences).defaultAudioQuality)
        assertEquals(30250, f.projection.current(repairedRead.preferences).lastSelectedAudioQuality)
        assertContentEquals(repairedBytes, Files.readAllBytes(f.legacyFile))
    }

    @Test fun `genuinely absent legacy seeds defaults and original PGC reads the same choices`(): Unit = runBlocking {
        val f = Fixture(directory)
        val initialRead = f.legacy.readForMigration()
        assertSame(PlayerPreferencesReadResult.Missing, initialRead)
        ensureDesktopWindowsPlaybackDefaults(f.context, initialRead.migrationSeed)
        val projected = f.projection.current(initialRead.preferences)
        assertEquals(projected.defaultAudioQuality, f.pgc.defaultQuality())
        assertEquals(projected.lastSelectedAudioQuality, f.pgc.rememberedQuality())
        val marks = f.store.preferences("windows_desktop_defaults")
        assertEquals(JsonPrimitive(true), marks[DesktopOriginalPlaybackPreferenceProjection.LEGACY_SEED_MARKER])
        assertFalse(Files.exists(f.legacyFile))
    }

    @Test fun `loaded legacy fills only absent audio choices and both current consumers honor canonical`(): Unit = runBlocking {
        val f = Fixture(directory)
        f.legacy.save(PlayerPreferences(defaultAudioQuality = 30251, lastSelectedAudioQuality = 30250))
        val initialRead = assertIs<PlayerPreferencesReadResult.Loaded>(f.legacy.readForMigration())
        val legacyBytes = Files.readAllBytes(f.legacyFile)
        f.store.update("settings", mapOf("default_audio_quality" to JsonPrimitive(-2)))
        ensureDesktopWindowsPlaybackDefaults(f.context, initialRead.migrationSeed)
        val projected = f.projection.current(initialRead.preferences)
        assertEquals(-2, f.pgc.defaultQuality())
        assertEquals(30250, f.pgc.rememberedQuality())
        assertEquals(f.pgc.defaultQuality(), projected.defaultAudioQuality)
        assertEquals(f.pgc.rememberedQuality(), projected.lastSelectedAudioQuality)
        assertEquals(-2, f.store.preferences("settings")["default_audio_quality"]!!.jsonPrimitive.int)
        assertContentEquals(legacyBytes, Files.readAllBytes(f.legacyFile))
    }
}
