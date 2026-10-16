package com.bilipai.desktop.player

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real bounded temporary files and the production Store, no user profile or copied loader. */
class PlayerPreferencesReadTest {
    @Test fun absentFileCanSeedDefaultsWithoutCreatingAFile() = withFile { _, file ->
        val store = PlayerPreferencesStore(file)
        val result = store.readForMigration()
        assertSame(PlayerPreferencesReadResult.Missing, result)
        assertEquals(PlayerPreferences(), result.preferences)
        assertSame(result.preferences, result.migrationSeed)
        assertEquals(result.preferences, store.read())
        assertFalse(Files.exists(file))
    }

    @Test fun validLegacyAudioAndCodecChoicesAreTheActualMigrationSeed() = withFile { _, file ->
        val expected = PlayerPreferences(volume = 34.0, defaultAudioQuality = 30251,
            lastSelectedAudioQuality = 30280, videoCodecPreference = "HEVC",
            speed = 1.75, rememberLastSpeed = true).normalized()
        val store = PlayerPreferencesStore(file)
        store.save(expected)
        val bytes = Files.readAllBytes(file)
        val result = assertIs<PlayerPreferencesReadResult.Loaded>(store.readForMigration())
        assertEquals(expected, result.preferences)
        assertSame(result.preferences, result.migrationSeed)
        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test fun damagedLegacyFileHasRuntimeFallbackButCannotSeedCanonicalDefaults() = withFile { _, file ->
        val bytes = "{ damaged settings with a remembered choice".toByteArray()
        Files.write(file, bytes)
        val store = PlayerPreferencesStore(file)
        val result = store.readForMigration()
        assertSame(PlayerPreferencesReadResult.Unavailable, result)
        assertEquals(PlayerPreferences(), result.preferences)
        assertNull(result.migrationSeed)
        assertEquals(PlayerPreferences(), store.read())
        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test fun directoryAtTheSettingsPathIsUnavailableAndIsNeverReplaced() = withFile { _, file ->
        Files.createDirectory(file)
        val kept = file.resolve("kept.txt")
        Files.writeString(kept, "owned fixture")
        try {
            val result = PlayerPreferencesStore(file).readForMigration()
            assertSame(PlayerPreferencesReadResult.Unavailable, result)
            assertNull(result.migrationSeed)
            assertEquals("owned fixture", Files.readString(kept))
            assertTrue(Files.isDirectory(file))
        } finally { Files.delete(kept) }
    }

    @Test fun oversizedSettingsAreUnavailableAndKeepTheOriginalBytes() = withFile { _, file ->
        val bytes = ByteArray(1_048_577) { ' '.code.toByte() }
        Files.write(file, bytes)
        val result = PlayerPreferencesStore(file).readForMigration()
        assertSame(PlayerPreferencesReadResult.Unavailable, result)
        assertNull(result.migrationSeed)
        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test fun unavailableReadIsNotCachedAndRepairedLegacyDataCanStillMigrate() = withFile { _, file ->
        Files.writeString(file, "not JSON")
        val store = PlayerPreferencesStore(file)
        assertNull(store.readForMigration().migrationSeed)
        val expected = PlayerPreferences(defaultAudioQuality = 30280,
            lastSelectedAudioQuality = 30251, defaultSpeed = 1.5).normalized()
        store.save(expected)
        val repaired = assertIs<PlayerPreferencesReadResult.Loaded>(store.readForMigration())
        assertEquals(expected, repaired.migrationSeed)
    }

    private fun withFile(block: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("bilipai-migration-read-")
        val real = directory.toRealPath()
        val file = directory.resolve("player-settings.json")
        try { block(directory, file) }
        finally {
            // No recursive deletion: remove only this fixture's two known paths.
            check(directory.toRealPath() == real && !Files.isSymbolicLink(directory))
            check(!Files.isSymbolicLink(file))
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }
}
