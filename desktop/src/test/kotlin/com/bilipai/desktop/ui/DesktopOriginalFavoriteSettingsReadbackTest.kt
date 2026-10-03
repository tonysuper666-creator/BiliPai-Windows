package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.FavoriteInteractionSettingsStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** Complete original favorite recipe and the existing actual favorite consumer share one Store. */
class DesktopOriginalFavoriteSettingsReadbackTest {
    private class Fixture {
        val directory = Files.createTempDirectory("bp-original-favorite-settings-")
        val plugin = DesktopPluginContext(DesktopPluginStore(directory))
        val current = AtomicBoolean(true)
        val context = DesktopOriginalPlayerSettingsContext(plugin, current::get, commitIfCurrent = { action ->
            if (current.get()) { action(); true } else false
        })
    }
    @Test fun completeOriginalSetterPublishesToExistingFavoriteConsumerAndColdStore(): Unit = runBlocking {
        val f = Fixture()
        val existing = DesktopFavoriteInteractionPreferences(f.plugin.store)
        assertFalse(existing.getQuickSaveDefaultFolder().first())
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, f.current::get) {
            FavoriteInteractionSettingsStore.setQuickSaveDefaultFolder(f.context, true)
            assertTrue(FavoriteInteractionSettingsStore.getQuickSaveDefaultFolder(f.context).first())
            assertFalse(existing.getQuickSaveDefaultFolder().first())
        }
        assertTrue(existing.getQuickSaveDefaultFolder().first())
        val cold = DesktopFavoriteInteractionPreferences(DesktopPluginStore(f.directory))
        assertTrue(cold.getQuickSaveDefaultFolder().first())
    }
    @Test fun retireBeforePublicationKeepsActualFavoriteConsumerAndColdStoreUnchanged(): Unit = runBlocking {
        val f = Fixture()
        assertThrows(CancellationException::class.java) { runBlocking {
            DesktopOriginalPlaybackPreferenceOperation.run(f.context, f.current::get) {
                FavoriteInteractionSettingsStore.setQuickSaveDefaultFolder(f.context, true)
                f.current.set(false)
            }
        } }
        assertFalse(DesktopFavoriteInteractionPreferences(f.plugin.store).getQuickSaveDefaultFolder().first())
        assertFalse(DesktopFavoriteInteractionPreferences(DesktopPluginStore(f.directory)).getQuickSaveDefaultFolder().first())
    }
    @Test fun failedOriginalRequestRollsBackStagedKey(): Unit = runBlocking {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { runBlocking {
            DesktopOriginalPlaybackPreferenceOperation.run(f.context, f.current::get) {
                FavoriteInteractionSettingsStore.setQuickSaveDefaultFolder(f.context, true)
                error("synthetic failure after complete original setter")
            }
        } }
        assertFalse(DesktopFavoriteInteractionPreferences(DesktopPluginStore(f.directory)).getQuickSaveDefaultFolder().first())
    }
}
