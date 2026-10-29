package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.asAndroidContext

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.core.store.home.DynamicSettingsStore
import com.android.purebilibili.feature.settings.share.SettingsShareSection
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.DesktopOriginalVideoOwnerNetwork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Share import/restore must reach the keys the Windows consumers actually read: the real service,
 * original setters, original sync getters and the real Root Store file; no UI/native fixture. */
class DesktopSettingsShareMirrorTest {
    private fun newStore() = DesktopPluginStore(Files.createTempDirectory("bp-settings-share-mirror-"))

    private fun newContext(store: DesktopPluginStore, commit: ((() -> Unit) -> Boolean) = { action -> action(); true }) =
        DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })

    private fun raw(sections: String) = """
        {"schemaVersion":1,"app":"BiliPai","appVersion":"0.3.3",
         "exportedAtIso":"2026-10-10T00:00:00Z","profileName":"fixture","sections":{$sections}}
    """.trimIndent()

    private fun bytes(store: DesktopPluginStore) = Files.readAllBytes(store.root.resolve("plugin-settings.json"))

    /** Real owner-network default: IEEE 802.11 reads wifi_quality, everything else reads mobile_quality. */
    private fun network(wifi: Boolean): DesktopOriginalVideoOwnerNetwork = object : DesktopOriginalVideoOwnerNetwork {
        override fun cdnNetwork() = error("No CDN observation")
        override fun isMobileData() = false
        override fun isWifi() = wifi
        override fun relatedNetworkObservation() = error("No related-network observation")
    }

    /** The pane's own default state: both network keys and both original mirrors, 1080P and 720P. */
    private fun seedQuality(store: DesktopPluginStore, wifi: Int = 80, mobile: Int = 64) {
        store.update("settings", mapOf("wifi_default_quality" to JsonPrimitive(wifi),
            "mobile_default_quality" to JsonPrimitive(mobile)))
        store.update("quality_settings", mapOf("wifi_quality" to JsonPrimitive(wifi),
            "mobile_quality" to JsonPrimitive(mobile)))
    }

    private fun assertQuality(store: DesktopPluginStore, context: DesktopOriginalPlayerSettingsContext, wifi: Int, mobile: Int) {
        assertEquals(JsonPrimitive(wifi), store.preferences("settings")["wifi_default_quality"])
        assertEquals(JsonPrimitive(mobile), store.preferences("settings")["mobile_default_quality"])
        assertEquals(JsonPrimitive(wifi), store.preferences("quality_settings")["wifi_quality"])
        assertEquals(JsonPrimitive(mobile), store.preferences("quality_settings")["mobile_quality"])
        assertEquals(wifi, network(wifi = true).getDefaultQualityId(context))
        assertEquals(mobile, network(wifi = false).getDefaultQualityId(context))
    }

    @Test fun newKeysUseTheOriginalSectionIdentityAndMobileQualityTakesTheWifiValueSet() {
        val fields = DesktopSettingsShareWindowsCatalog.fields
        assertEquals(SettingsShareSection.PLAYBACK, fields.getValue("mobile_default_quality").definition.section)
        assertEquals(SettingsShareSection.NAVIGATION, fields.getValue("dynamic_comment_preview_visible").definition.section)
        val accepted = listOf("wifi_default_quality", "mobile_default_quality").map { key ->
            (0..200).filter { id -> DesktopSettingsShareWindowsCatalog.normalize(mapOf(key to JsonPrimitive(id))).isNotEmpty() }
        }
        assertEquals(accepted[0], accepted[1])
        assertTrue(accepted[0].containsAll(listOf(16, 32, 64, 80, 112, 120)))
        assertFalse(7 in accepted[0])
    }

    @Test fun importedNetworkQualitiesReachTheKeysThatEthernetAndWifiPlaybackRead(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        val service = DesktopSettingsShareService(context)
        // An export from this build: the pane keeps both keys equal.
        val result = service.applyImport(service.readImportSession(raw("""
            "playback":{"wifi_default_quality":112,"mobile_default_quality":112}
        """.trimIndent())))
        assertEquals(listOf("mobile_default_quality", "wifi_default_quality"), result.appliedKeys)
        assertQuality(store, context, wifi = 112, mobile = 112)
        assertEquals(112, DesktopOriginalPlaybackSettingsPreferences.getWifiQuality(context).first())
        assertEquals(112, DesktopOriginalPlaybackSettingsPreferences.getMobileQuality(context).first())
    }

    @Test fun profileThatPredatesTheMobileKeyDerivesItFromWifiLikeThePane(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        seedQuality(store)
        val service = DesktopSettingsShareService(context)
        // Before this fix Windows exported wifi_default_quality only, so Ethernet kept its old 720P.
        val session = service.readImportSession(raw(""""playback":{"wifi_default_quality":112,"auto_highest_quality":false}"""))
        assertEquals(listOf("auto_highest_quality", "wifi_default_quality"), service.applyImport(session).appliedKeys)
        assertQuality(store, context, wifi = 112, mobile = 112)
        // The same replay on a PC that never stored either key.
        val fresh = newStore(); val freshContext = newContext(fresh)
        DesktopSettingsShareService(freshContext).let { it.applyImport(it.readImportSession(session.rawJson)) }
        assertQuality(fresh, freshContext, wifi = 112, mobile = 112)
    }

    @Test fun savedProfileFromBeforeTheFixRestoresBothNetworkKeys(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        seedQuality(store)
        val service = DesktopSettingsShareService(context)
        // An archive record exactly as the old export wrote it: a Wi-Fi key and no mobile key.
        store.update(DesktopSettingsShareService.PROFILES_NAMESPACE, mapOf("old-profile-1760054400000.json" to
            JsonPrimitive(raw(""""playback":{"wifi_default_quality":112,"click_to_play":true}"""))))
        val saved = service.listSavedProfiles().single()
        assertFalse(service.readSavedProfile(saved).profile.sections.playback.containsKey("mobile_default_quality"))
        assertTrue(service.restoreSavedProfile(saved).appliedKeys.containsAll(listOf("wifi_default_quality", "click_to_play")))
        assertQuality(store, context, wifi = 112, mobile = 112)
    }

    @Test fun aWifiKeySetsBothNetworkKeysAndTheMobileKeyOnlyCountsInAProfileWithoutOne(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        seedQuality(store)
        val service = DesktopSettingsShareService(context)
        // Android profile: Wi-Fi 1080P+, cellular lowered to 480P. Ethernet reads mobile_quality, so it follows Wi-Fi.
        val both = service.readImportSession(raw(""""playback":{"wifi_default_quality":112,"mobile_default_quality":32}"""))
        assertEquals(listOf("mobile_default_quality", "wifi_default_quality"), service.applyImport(both).appliedKeys)
        assertQuality(store, context, wifi = 112, mobile = 112)
        // 7 is not a quality id: the invalid mobile key is rejected on its own and Wi-Fi still sets both keys.
        seedQuality(store)
        val invalid = service.readImportSession(raw(""""playback":{"wifi_default_quality":112,"mobile_default_quality":7}"""))
        assertEquals(mapOf("mobile_default_quality" to "invalid type or value"), service.inspectImport(invalid).skippedReasons)
        assertEquals(listOf("wifi_default_quality"), service.applyImport(invalid).appliedKeys)
        assertQuality(store, context, wifi = 112, mobile = 112)
        // A lone valid mobile key (no Wi-Fi key at all) is applied and leaves Wi-Fi untouched.
        val lone = service.readImportSession(raw(""""playback":{"mobile_default_quality":32}"""))
        assertEquals(listOf("mobile_default_quality"), service.applyImport(lone).appliedKeys)
        assertQuality(store, context, wifi = 112, mobile = 32)
        // A Wi-Fi key that is itself rejected still keeps the mobile key out: nothing is written.
        val before = bytes(store)
        val rejected = service.readImportSession(raw(""""playback":{"wifi_default_quality":7,"mobile_default_quality":16}"""))
        assertEquals(setOf("mobile_default_quality", "wifi_default_quality"), rejected.preview.skippedKeys.toSet())
        assertEquals(setOf("mobile_default_quality", "wifi_default_quality"), service.inspectImport(rejected).skippedReasons.keys)
        assertTrue(service.applyImport(rejected).appliedKeys.isEmpty())
        assertArrayEquals(before, bytes(store))
        assertQuality(store, context, wifi = 112, mobile = 32)
    }

    @Test fun mistypedMobileQualityValuesAreRejectedWithoutTouchingTheStore(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        seedQuality(store)
        val before = bytes(store)
        val service = DesktopSettingsShareService(context)
        for (value in listOf("7", "\"64\"", "1e1000", "64.5", "true")) {
            val session = service.readImportSession(raw(""""playback":{"mobile_default_quality":$value}"""))
            assertEquals(listOf("mobile_default_quality"), session.preview.skippedKeys)
            assertEquals(mapOf("mobile_default_quality" to "invalid type or value"), service.inspectImport(session).skippedReasons)
            assertTrue(service.applyImport(session).appliedKeys.isEmpty())
            assertArrayEquals(before, bytes(store))
        }
        assertQuality(store, context, wifi = 80, mobile = 64)
    }

    @Test fun savedProfileRoundTripAndPaneStyleExportKeepBothQualityKeys(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        val service = DesktopSettingsShareService(context)
        // Pane-style write: one original operation publishes both keys and both mirrors.
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setWifiQuality(context, 120)
            DesktopOriginalPlaybackSettingsPreferences.setMobileQuality(context, 120)
        }
        val exported = service.buildExportArtifact("pane", "0.3.3").profile.sections.playback
        assertEquals(JsonPrimitive(120), exported["wifi_default_quality"])
        assertEquals(JsonPrimitive(120), exported["mobile_default_quality"])
        // The merged option: an Android-style pair with a lower cellular value still lands as one value.
        service.applyImport(service.readImportSession(raw(""""playback":{"wifi_default_quality":112,"mobile_default_quality":64}""")))
        assertQuality(store, context, wifi = 112, mobile = 112)
        val saved = service.saveCurrentProfile("round trip", "0.3.3", Instant.parse("2026-10-10T00:00:00Z"))
        val archived = service.readSavedProfile(saved).profile.sections.playback
        assertEquals(JsonPrimitive(112), archived["wifi_default_quality"])
        assertEquals(JsonPrimitive(112), archived["mobile_default_quality"])
        service.applyImport(service.readImportSession(raw(""""playback":{"wifi_default_quality":80,"mobile_default_quality":80}""")))
        assertQuality(store, context, wifi = 80, mobile = 80)
        service.restoreSavedProfile(saved)
        assertQuality(store, context, wifi = 112, mobile = 112)
    }

    @Test fun clickToPlayImportReachesTheAutoplayMirrorTheLoadPathReads(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        // State an import left behind before the mirror was projected: canonical off, mirror still on.
        store.update("settings", mapOf("click_to_play" to JsonPrimitive(false)))
        store.update("auto_play_cache", mapOf("click_to_play_enabled" to JsonPrimitive(true),
            "auto_play_enabled" to JsonPrimitive(true)))
        assertTrue(DesktopOriginalPlayerSectionSettings.getClickToPlaySync(context))
        val service = DesktopSettingsShareService(context)
        assertEquals(listOf("click_to_play"),
            service.applyImport(service.readImportSession(raw(""""playback":{"click_to_play":false}"""))).appliedKeys)
        assertFalse(DesktopOriginalPlayerSectionSettings.getClickToPlaySync(context))
        assertFalse(DesktopOriginalPlaybackSettingsPreferences.getClickToPlay(context).first())
        assertEquals(JsonPrimitive(false), store.preferences("auto_play_cache")["click_to_play_enabled"])
        assertEquals(JsonPrimitive(true), store.preferences("auto_play_cache")["auto_play_enabled"])
        service.applyImport(service.readImportSession(raw(""""playback":{"click_to_play":true}""")))
        assertTrue(DesktopOriginalPlayerSectionSettings.getClickToPlaySync(context))
        assertEquals(JsonPrimitive(true), store.preferences("settings")["click_to_play"])
        // A JSON string is not a boolean: rejected, nothing written.
        val before = bytes(store)
        val invalid = service.readImportSession(raw(""""playback":{"click_to_play":"false"}"""))
        assertEquals(listOf("click_to_play"), invalid.preview.skippedKeys)
        assertTrue(service.applyImport(invalid).appliedKeys.isEmpty())
        assertArrayEquals(before, bytes(store))
        assertTrue(DesktopOriginalPlayerSectionSettings.getClickToPlaySync(context))
    }

    @Test fun longPressLockAndHintImportsProjectTheMirrorsThePlayerSectionReads(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        // Canonical already on (earlier import), the synchronous cache the player section reads still off.
        store.update("settings", mapOf("long_press_speed_lock_enabled" to JsonPrimitive(true),
            "long_press_speed_hint_hidden" to JsonPrimitive(true)))
        store.update("long_press_speed_lock_cache", mapOf("long_press_speed_lock_hint_shown" to JsonPrimitive(true)))
        assertFalse(DesktopOriginalPlayerSectionSettings.getLongPressSpeedLockEnabledSync(context))
        assertFalse(DesktopOriginalPlayerSectionSettings.getLongPressSpeedHintHiddenSync(context))
        val service = DesktopSettingsShareService(context)
        service.applyImport(service.readImportSession(raw("""
            "gesture":{"long_press_speed_lock_enabled":true,"long_press_speed_hint_hidden":true}
        """.trimIndent())))
        assertTrue(DesktopOriginalPlayerSectionSettings.getLongPressSpeedLockEnabledSync(context))
        assertTrue(DesktopOriginalPlayerSectionSettings.getLongPressSpeedHintHiddenSync(context))
        assertTrue(DesktopOriginalVideoControlSettings.getLongPressSpeedLockEnabled(context).first())
        assertTrue(DesktopOriginalPlaybackSettingsPreferences.getLongPressSpeedHintHidden(context).first())
        service.applyImport(service.readImportSession(raw("""
            "gesture":{"long_press_speed_lock_enabled":false,"long_press_speed_hint_hidden":false}
        """.trimIndent())))
        assertFalse(DesktopOriginalPlayerSectionSettings.getLongPressSpeedLockEnabledSync(context))
        assertFalse(DesktopOriginalPlayerSectionSettings.getLongPressSpeedHintHiddenSync(context))
        assertEquals(JsonPrimitive(false), store.preferences("long_press_speed_lock_cache")["long_press_speed_lock_enabled"])
        assertEquals(JsonPrimitive(false), store.preferences("long_press_speed_lock_cache")["long_press_speed_hint_hidden"])
        assertEquals(JsonPrimitive(false), store.preferences("settings")["long_press_speed_lock_enabled"])
        // Only the two imported keys are projected; the unrelated mirror entry survives.
        assertEquals(JsonPrimitive(true), store.preferences("long_press_speed_lock_cache")["long_press_speed_lock_hint_shown"])
        val before = bytes(store)
        val invalid = service.readImportSession(raw(""""gesture":{"long_press_speed_lock_enabled":"true","long_press_speed_hint_hidden":1}"""))
        assertEquals(setOf("long_press_speed_lock_enabled", "long_press_speed_hint_hidden"), invalid.preview.skippedKeys.toSet())
        assertTrue(service.applyImport(invalid).appliedKeys.isEmpty())
        assertArrayEquals(before, bytes(store))
    }

    @Test fun casConflictAtTheFirstPermitReplaysEveryNewMirrorOnTheFreshSnapshot(): Unit = runBlocking {
        val store = newStore()
        val permits = AtomicInteger()
        val context = newContext(store) { action ->
            // A real write to the same Backing while the prepared import holds an old revision
            // forces its whole canonical/mirror recipe to replay on the fresh snapshot.
            if (permits.incrementAndGet() == 1) {
                store.update("settings", mapOf("mobile_default_quality" to JsonPrimitive(16),
                    "click_to_play" to JsonPrimitive(true), "concurrent_fixture" to JsonPrimitive(true)))
                store.update("auto_play_cache", mapOf("click_to_play_enabled" to JsonPrimitive(true)))
            }
            action(); true
        }
        val service = DesktopSettingsShareService(context)
        service.applyImport(service.readImportSession(raw("""
            "playback":{"wifi_default_quality":112,"click_to_play":false},
            "gesture":{"long_press_speed_lock_enabled":true,"long_press_speed_hint_hidden":true},
            "navigation":{"dynamic_comment_preview_visible":true}
        """.trimIndent())))
        assertTrue(permits.get() >= 2)
        assertQuality(store, context, wifi = 112, mobile = 112)
        assertFalse(DesktopOriginalPlayerSectionSettings.getClickToPlaySync(context))
        assertEquals(JsonPrimitive(false), store.preferences("settings")["click_to_play"])
        assertTrue(DesktopOriginalPlayerSectionSettings.getLongPressSpeedLockEnabledSync(context))
        assertTrue(DesktopOriginalPlayerSectionSettings.getLongPressSpeedHintHiddenSync(context))
        assertTrue(DynamicSettingsStore.observeCommentPreviewVisible(context.pluginContext.asAndroidContext()).first())
        assertEquals(JsonPrimitive(true), store.preferences("settings")["concurrent_fixture"])
        val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(JsonPrimitive(112), disk["quality_settings"]!!.jsonObject["mobile_quality"])
        assertEquals(JsonPrimitive(false), disk["auto_play_cache"]!!.jsonObject["click_to_play_enabled"])
    }

    @Test fun dynamicCommentPreviewRoundTripsAndReachesTheFlowTheNavigationDisplayObserves(): Unit = runBlocking {
        val store = newStore(); val context = newContext(store)
        store.update("settings", mapOf("dynamic_comment_preview_visible" to JsonPrimitive(false)))
        val service = DesktopSettingsShareService(context)
        assertFalse(DynamicSettingsStore.observeCommentPreviewVisible(context.pluginContext.asAndroidContext()).first())
        val session = service.readImportSession(raw(""""navigation":{"dynamic_comment_preview_visible":true}"""))
        assertEquals(listOf("dynamic_comment_preview_visible"), service.applyImport(session).appliedKeys)
        assertTrue(DynamicSettingsStore.observeCommentPreviewVisible(context.pluginContext.asAndroidContext()).first())
        assertEquals(JsonPrimitive(true), store.preferences("settings")["dynamic_comment_preview_visible"])
        assertEquals(JsonPrimitive(true),
            service.buildExportArtifact("export", "0.3.3").profile.sections.navigation["dynamic_comment_preview_visible"])
        val saved = service.saveCurrentProfile("preview", "0.3.3", Instant.parse("2026-10-10T00:00:00Z"))
        service.applyImport(service.readImportSession(raw(""""navigation":{"dynamic_comment_preview_visible":false}""")))
        assertFalse(DynamicSettingsStore.observeCommentPreviewVisible(context.pluginContext.asAndroidContext()).first())
        service.restoreSavedProfile(saved)
        assertTrue(DynamicSettingsStore.observeCommentPreviewVisible(context.pluginContext.asAndroidContext()).first())
        // A JSON string is not a boolean: rejected, nothing written.
        val before = bytes(store)
        val invalid = service.readImportSession(raw(""""navigation":{"dynamic_comment_preview_visible":"true"}"""))
        assertEquals(mapOf("dynamic_comment_preview_visible" to "invalid type or value"),
            service.inspectImport(invalid).skippedReasons)
        assertTrue(service.applyImport(invalid).appliedKeys.isEmpty())
        assertArrayEquals(before, bytes(store))
    }
}
