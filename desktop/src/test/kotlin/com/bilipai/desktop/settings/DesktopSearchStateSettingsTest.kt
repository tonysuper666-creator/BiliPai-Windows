package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalFullNavigationSettings
import com.android.purebilibili.feature.settings.share.SettingsShareSection
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** The v0.4.6 Android backup keys that the search state slice lets Windows accept. No window, GPU or network. */
class DesktopSearchStateSettingsTest {
    private val keys = listOf("related_video_transition_enabled", "video_return_content_follow_progress_enabled")

    private class Fixture {
        val directory = Files.createTempDirectory("bp-search-state-settings-")
        val store = DesktopPluginStore(directory)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { action -> action(); true },
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
    }

    private fun profile(body: String) =
        """{"schemaVersion":1,"app":"BiliPai","appVersion":"0.4.6","exportedAtIso":"2026-10-17T00:00:00Z","profileName":"search","sections":{"appearance":{$body}}}"""

    @Test fun `both keys are catalog fields of the original appearance section and accept only booleans`() {
        for (key in keys) {
            assertEquals(SettingsShareSection.APPEARANCE, DesktopSettingsShareWindowsCatalog.fields.getValue(key).definition.section, key)
            assertEquals(mapOf(key to JsonPrimitive(false)), DesktopSettingsShareWindowsCatalog.normalize(mapOf(key to JsonPrimitive(false))), key)
            assertEquals(mapOf(key to JsonPrimitive(true)), DesktopSettingsShareWindowsCatalog.normalize(mapOf(key to JsonPrimitive(true))), key)
            assertTrue(DesktopSettingsShareWindowsCatalog.normalize(mapOf(key to JsonPrimitive("false"))).isEmpty(), key)
            assertTrue(DesktopSettingsShareWindowsCatalog.normalize(mapOf(key to JsonPrimitive(0))).isEmpty(), key)
        }
    }

    @Test fun `the search only silent switch is not an Android share key`() {
        assertFalse("search_history_recording_enabled" in DesktopSettingsShareWindowsCatalog.fields)
        assertTrue(DesktopSettingsShareWindowsCatalog.definitions.none { it.storageKey == "search_history_recording_enabled" })
    }

    @Test fun `an Android backup import stores both booleans where the settings page reads them`(): Unit = runBlocking {
        val f = Fixture()
        val service = DesktopSettingsShareService(f.context)
        val applied = service.applyImport(service.readImportSession(profile(
            """"related_video_transition_enabled":false,"video_return_content_follow_progress_enabled":false""")))
        assertEquals(keys.sorted(), applied.appliedKeys.sorted())
        for (key in keys) assertEquals(JsonPrimitive(false), f.store.preferences("settings")[key], key)
        assertFalse(DesktopOriginalFullNavigationSettings.getRelatedVideoTransitionEnabled(DesktopPluginContext(f.store)).first())
        val again = service.applyImport(service.readImportSession(profile(""""related_video_transition_enabled":true""")))
        assertEquals(listOf("related_video_transition_enabled"), again.appliedKeys)
        assertTrue(DesktopOriginalFullNavigationSettings.getRelatedVideoTransitionEnabled(DesktopPluginContext(f.store)).first())
    }

    @Test fun `the animation settings setter of the second key writes the key and namespace an import stores`(): Unit = runBlocking {
        val f = Fixture()
        val service = DesktopSettingsShareService(f.context)
        service.applyImport(service.readImportSession(profile(""""video_return_content_follow_progress_enabled":false""")))
        assertEquals(JsonPrimitive(false), f.store.preferences("settings")[keys[1]])
        // The generated setter has no getter beside it: it must overwrite the very value the import stored.
        DesktopOriginalFullNavigationSettings.setVideoReturnContentFollowProgressEnabled(DesktopPluginContext(f.store), true)
        assertEquals(JsonPrimitive(true), f.store.preferences("settings")[keys[1]])
        DesktopOriginalFullNavigationSettings.setVideoReturnContentFollowProgressEnabled(DesktopPluginContext(f.store), false)
        assertEquals(JsonPrimitive(false), f.store.preferences("settings")[keys[1]])
    }

    @Test fun `a string or a number is rejected without touching the stored file`(): Unit = runBlocking {
        val f = Fixture()
        val service = DesktopSettingsShareService(f.context)
        service.applyImport(service.readImportSession(profile(""""related_video_transition_enabled":false""")))
        val before = Files.readAllBytes(f.directory.resolve("plugin-settings.json"))
        val invalid = service.applyImport(service.readImportSession(profile(
            """"related_video_transition_enabled":"true","video_return_content_follow_progress_enabled":1""")))
        assertTrue(invalid.appliedKeys.isEmpty())
        assertEquals(keys.sorted(), invalid.skippedKeys.sorted())
        assertContentEquals(before, Files.readAllBytes(f.directory.resolve("plugin-settings.json")))
    }
}
