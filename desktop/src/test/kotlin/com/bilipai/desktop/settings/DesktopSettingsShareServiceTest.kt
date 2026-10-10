package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Real JSON service, original preference consumers and Root Store file; no UI/native fixture. */
class DesktopSettingsShareServiceTest {
    private fun store() = DesktopPluginStore(Files.createTempDirectory("bp-settings-share-"))

    private fun context(store: DesktopPluginStore, owns: () -> Boolean = { true },
        commit: ((() -> Unit) -> Boolean) = { action -> action(); true }) =
        DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), owns, commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })

    private fun raw(sections: String) = """
        {"schemaVersion":1,"app":"BiliPai","appVersion":"0.3.3",
         "exportedAtIso":"2026-10-10T00:00:00Z","profileName":"fixture","sections":{$sections}}
    """.trimIndent()

    private fun seed(store: DesktopPluginStore) {
        store.update("settings", mapOf("foreign" to JsonPrimitive("keep"), "hw_decode" to JsonPrimitive(true)))
        store.update("player_settings_cache", mapOf("hw_decode_enabled" to JsonPrimitive(true)))
        store.update("accounts", mapOf("fixture_sentinel" to JsonPrimitive("preserve")))
    }

    private fun bytes(store: DesktopPluginStore) = Files.readAllBytes(store.root.resolve("plugin-settings.json"))

    @Test fun invalidTypesNonfiniteValuesEnumsAndUnknownFieldsCannotPublish(): Unit = runBlocking {
        val store = store(); seed(store)
        val before = bytes(store)
        val service = DesktopSettingsShareService(context(store))
        // JSON strings must not become booleans/numbers. Overflow is syntactically valid
        // JSON but becomes nonfinite as a Float and must not reach the Store.
        val session = service.readImportSession(raw("""
            "appearance":{"theme_selection_v1":"UNKNOWN","theme_mode_v2":999},
            "playback":{"hw_decode":"false"},
            "danmaku":{"danmaku_opacity":"NaN","danmaku_speed":1e1000},
            "navigation":{"account_cookie":"never import","bottom_bar_order":["HOME"]}
        """.trimIndent()))
        val rejected = setOf("theme_selection_v1", "theme_mode_v2", "hw_decode", "danmaku_opacity",
            "danmaku_speed", "account_cookie", "bottom_bar_order")
        assertEquals(rejected, session.preview.skippedKeys.toSet())
        assertTrue(session.preview.importableSections.isEmpty())
        assertEquals(rejected, service.inspectImport(session).skippedReasons.keys)
        assertTrue(service.applyImport(session).appliedKeys.isEmpty())
        assertArrayEquals(before, bytes(store))
        assertEquals(JsonPrimitive(true), store.preferences("settings")["hw_decode"])
        assertEquals(JsonPrimitive(true), store.preferences("player_settings_cache")["hw_decode_enabled"])
        for (csv in listOf("1,NaN", "1,1e1000", "1,wrong")) {
            val invalidCsv = service.readImportSession(raw(""""playback":{"playback_speed_options":"$csv"}"""))
            assertEquals(listOf("playback_speed_options"), invalidCsv.preview.skippedKeys)
            assertTrue(service.applyImport(invalidCsv).appliedKeys.isEmpty())
            assertArrayEquals(before, bytes(store))
        }
    }

    @Test fun validSubsetUsesOriginalMirrorSetterWithoutResettingOtherNamespaces(): Unit = runBlocking {
        val store = store(); seed(store)
        val unmountedEffects = setOf("miuix_transition_blur_enabled", "video_shared_return_gesture_follow_enabled",
            "video_shared_return_gesture_translation_enabled")
        store.update("settings", unmountedEffects.associateWith { JsonPrimitive(true) })
        val accounts = store.preferences("accounts")
        val service = DesktopSettingsShareService(context(store))
        val session = service.readImportSession(raw("""
            "appearance":{"miuix_transition_blur_enabled":false,"video_shared_return_gesture_follow_enabled":false,
                          "video_shared_return_gesture_translation_enabled":false},
            "playback":{"hw_decode":false,"default_playback_speed":"2.0"},
            "gesture":{"haptic_feedback_enabled":false},
            "navigation":{"unknown_fixture_key":true}
        """.trimIndent()))
        val result = service.applyImport(session)
        assertEquals(listOf("hw_decode"), result.appliedKeys)
        assertEquals(setOf("default_playback_speed", "haptic_feedback_enabled", "unknown_fixture_key") + unmountedEffects,
            result.skippedKeys.toSet())
        for (key in unmountedEffects) assertEquals(JsonPrimitive(true), store.preferences("settings")[key])
        assertEquals(JsonPrimitive(false), store.preferences("settings")["hw_decode"])
        assertEquals(JsonPrimitive(false), store.preferences("player_settings_cache")["hw_decode_enabled"])
        assertEquals(JsonPrimitive("keep"), store.preferences("settings")["foreign"])
        assertEquals(accounts, store.preferences("accounts"))
        assertFalse(store.preferences("settings").containsKey("haptic_feedback_enabled"))
        val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(JsonPrimitive(false), disk["settings"]!!.jsonObject["hw_decode"])
        assertEquals(JsonPrimitive(false), disk["player_settings_cache"]!!.jsonObject["hw_decode_enabled"])
    }

    @Test fun originalLegacyThemeMigrationKeepsAnExplicitNewKeyAuthoritative(): Unit = runBlocking {
        val store = store(); val service = DesktopSettingsShareService(context(store))
        val legacy = service.readImportSession(raw("""
            "appearance":{"ui_preset":1,"android_native_variant_v1":1}
        """.trimIndent()))
        assertEquals(JsonPrimitive(AppUiStyle.fromLegacyValues(1, 1).name),
            legacy.profile.sections.appearance["theme_selection_v1"])
        assertFalse(legacy.profile.sections.appearance.containsKey("ui_preset"))
        assertFalse(legacy.profile.sections.appearance.containsKey("android_native_variant_v1"))
        service.applyImport(legacy)
        assertEquals(JsonPrimitive(AppUiStyle.fromLegacyValues(1, 1).name),
            store.preferences("settings")["theme_selection_v1"])

        store.update("settings", mapOf("ui_preset" to JsonPrimitive(0), "android_native_variant_v1" to JsonPrimitive(1)))
        val explicit = service.readImportSession(raw("""
            "appearance":{"theme_selection_v1":"MATERIAL3","ui_preset":1,"android_native_variant_v1":1}
        """.trimIndent()))
        assertEquals(JsonPrimitive(AppUiStyle.MATERIAL3.name), explicit.profile.sections.appearance["theme_selection_v1"])
        assertEquals(listOf("theme_selection_v1"), service.applyImport(explicit).appliedKeys)
        assertEquals(JsonPrimitive(AppUiStyle.MATERIAL3.name), store.preferences("settings")["theme_selection_v1"])
        assertFalse(store.preferences("settings").containsKey("ui_preset"))
        assertFalse(store.preferences("settings").containsKey("android_native_variant_v1"))
    }

    @Test fun explicitGlassOffSurvivesTheActualWindowsStartupMigration(): Unit = runBlocking {
        val store = store(); val service = DesktopSettingsShareService(context(store))
        val session = service.readImportSession(raw("""
            "appearance":{"android_native_liquid_glass_enabled":false}
        """.trimIndent()))
        service.applyImport(session)
        assertEquals(JsonPrimitive(false), store.preferences("settings")["android_native_liquid_glass_enabled"])
        assertEquals(JsonPrimitive(true), store.preferences("settings")["windows_liquid_glass_default_v1"])
        val preferences = DesktopThemePrefs(store)
        preferences.ensureMigrated()
        assertFalse(preferences.initialSettings().liquidGlassEnabled)
        assertEquals(JsonPrimitive(false), store.preferences("settings")["android_native_liquid_glass_enabled"])
    }

    @Test fun alteredPreviewProfileOrRawJsonCannotBeApplied(): Unit = runBlocking {
        val store = store(); seed(store)
        val before = bytes(store)
        val service = DesktopSettingsShareService(context(store))
        val session = service.readImportSession(raw("""
            "playback":{"hw_decode":false},"navigation":{"unknown_fixture_key":true}
        """.trimIndent()))
        val altered = listOf(
            session.copy(profile = session.profile.copy(profileName = "changed")),
            session.copy(preview = session.preview.copy(skippedKeys = emptyList())),
            session.copy(rawJson = raw(""""playback":{"hw_decode":true}""")),
        )
        for (candidate in altered) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.applyImport(candidate) } }
            assertArrayEquals(before, bytes(store))
        }
    }

    @Test fun ownerReplacementAtActualFinalPermitRejectsCanonicalAndMirrors(): Unit = runBlocking {
        val store = store(); seed(store)
        val before = bytes(store)
        val ownerGeneration = AtomicInteger(1)
        val permits = AtomicInteger()
        val service = DesktopSettingsShareService(context(store, { ownerGeneration.get() == 1 }) { action ->
            permits.incrementAndGet(); ownerGeneration.set(2); action(); true
        })
        val session = service.readImportSession(raw("""
            "appearance":{"theme_mode_v2":2},"playback":{"hw_decode":false,"default_playback_speed":2.0}
        """.trimIndent()))
        assertThrows(CancellationException::class.java) { runBlocking { service.applyImport(session) } }
        assertEquals(1, permits.get())
        assertArrayEquals(before, bytes(store))
        assertTrue(store.preferences("theme_cache").isEmpty())
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
        assertEquals(JsonPrimitive(true), store.preferences("player_settings_cache")["hw_decode_enabled"])
    }

    @Test fun callerCancellationAtActualFinalPermitCannotPublishTheBatch(): Unit = runBlocking {
        val store = store(); seed(store)
        val before = bytes(store)
        val permits = AtomicInteger()
        lateinit var request: Job
        val service = DesktopSettingsShareService(context(store, commit = { action ->
            permits.incrementAndGet(); request.cancel(CancellationException("Fixture caller retired")); action(); true
        }))
        val session = service.readImportSession(raw("""
            "appearance":{"theme_mode_v2":2},"playback":{"hw_decode":false,"default_playback_speed":2.0}
        """.trimIndent()))
        request = launch(start = CoroutineStart.LAZY) { service.applyImport(session) }
        request.start(); request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, permits.get())
        assertArrayEquals(before, bytes(store))
        assertTrue(store.preferences("theme_cache").isEmpty())
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
    }

    @Test fun importedSpeedOptionsReplayFreshSelectionsAndAllOriginalMirrorsAfterCasConflict(): Unit = runBlocking {
        val store = store(); seed(store)
        store.update("settings", mapOf("playback_speed_options" to JsonPrimitive("1,1.5,3"),
            "default_playback_speed" to JsonPrimitive(1f), "last_playback_speed" to JsonPrimitive(1f)))
        val permits = AtomicInteger()
        val context = context(store, commit = { action ->
            // A real write to the same Backing while the prepared import holds an old
            // document revision forces its whole canonical/mirror recipe to replay.
            if (permits.incrementAndGet() == 1) store.update("settings", mapOf(
                "last_playback_speed" to JsonPrimitive(2.6f), "concurrent_fixture" to JsonPrimitive(true)))
            action(); true
        })
        val service = DesktopSettingsShareService(context)
        val session = service.readImportSession(raw("""
            "appearance":{"liquid_glass_readability_mode":1},
            "playback":{"playback_speed_options":"2,1.5,1.5","default_playback_speed":1.6,
                        "default_audio_quality":30251},
            "gesture":{"long_press_speed":3.333}
        """.trimIndent()))
        service.applyImport(session)
        assertTrue(permits.get() >= 2)
        assertEquals(listOf(1f, 1.5f, 2f), DesktopOriginalVideoPlayerSettings.getPlaybackSpeedOptions(context).first())
        assertEquals(1.5f, DesktopOriginalVideoPlayerSettings.getDefaultPlaybackSpeed(context).first())
        assertEquals(2f, DesktopOriginalVideoPlayerSettings.getLastPlaybackSpeed(context).first())
        assertEquals(1.5f, DesktopOriginalVideoPlayerSettings.getPreferredPlaybackSpeedSync(context))
        assertEquals(JsonPrimitive(1.5f), store.preferences("playback_speed_cache")["default_speed"])
        assertEquals(JsonPrimitive(2f), store.preferences("playback_speed_cache")["last_speed"])
        assertEquals(30251, DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(context))
        assertEquals(JsonPrimitive(30251), store.preferences("quality_settings")["default_audio_quality"])
        assertEquals(3.33f, DesktopOriginalVideoPlayerSettings.getLongPressSpeed(context).first())
        assertEquals(JsonPrimitive(1), store.preferences("settings")["liquid_glass_readability_mode"])
        assertEquals(JsonPrimitive(true), store.preferences("settings")["concurrent_fixture"])
        assertEquals(JsonPrimitive("keep"), store.preferences("settings")["foreign"])
        assertEquals(JsonPrimitive("preserve"), store.preferences("accounts")["fixture_sentinel"])
        val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(JsonPrimitive(2f), disk["playback_speed_cache"]!!.jsonObject["last_speed"])
        assertEquals(JsonPrimitive(30251), disk["quality_settings"]!!.jsonObject["default_audio_quality"])
    }

    @Test fun importingBaselineDanmakuPreservesActualScopedOverrides(): Unit = runBlocking {
        val store = store()
        val admission: (() -> Unit) -> Boolean = { action -> action(); true }
        val blocks = DesktopDanmakuBlockPreferences(store, admission)
        val preferences = DesktopOriginalDanmakuPreferences(store, blocks, admission)
        // The retained original policy shares enabled with LANDSCAPE, but opacity
        // and block rules are genuine separate portrait/landscape overrides.
        preferences.setDanmakuEnabled(true, DanmakuSettingsScope.LANDSCAPE)
        preferences.setDanmakuOpacity(0.65f, DanmakuSettingsScope.PORTRAIT)
        preferences.setDanmakuOpacity(0.85f, DanmakuSettingsScope.LANDSCAPE)
        blocks.setDanmakuBlockRulesRaw("portrait-only", DanmakuSettingsScope.PORTRAIT)
        blocks.setDanmakuBlockRulesRaw("landscape-only", DanmakuSettingsScope.LANDSCAPE)
        val portrait = preferences.currentSettings(DanmakuSettingsScope.PORTRAIT)
        val landscape = preferences.currentSettings(DanmakuSettingsScope.LANDSCAPE)
        val service = DesktopSettingsShareService(context(store))
        val session = service.readImportSession(raw("""
            "danmaku":{"danmaku_enabled":false,"danmaku_opacity":0.5,"danmaku_block_rules":"baseline"}
        """.trimIndent()))
        assertEquals(setOf("danmaku_enabled", "danmaku_opacity", "danmaku_block_rules"),
            service.inspectImport(session).baselineDanmakuKeys)
        service.applyImport(session)
        assertEquals(JsonPrimitive(false), store.preferences("settings")["danmaku_enabled"])
        assertEquals(JsonPrimitive(0.5f), store.preferences("settings")["danmaku_opacity"])
        assertEquals(JsonPrimitive("baseline"), store.preferences("settings")["danmaku_block_rules"])
        assertEquals(portrait, preferences.currentSettings(DanmakuSettingsScope.PORTRAIT))
        assertEquals(landscape, preferences.currentSettings(DanmakuSettingsScope.LANDSCAPE))
        assertTrue(preferences.currentSettings(DanmakuSettingsScope.PORTRAIT).enabled)
        assertTrue(preferences.currentSettings(DanmakuSettingsScope.LANDSCAPE).enabled)
    }
}
