package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.settings.DesktopOriginalHomeSettingsViewModel
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/** Real global backing, original recipes, actual ImageLifetime, and the existing original
 * preference journal. These assertions do not claim an actual Root draw or native chooser.
 */
class DesktopOriginalHomeSettingsOwnershipTest {
    private class Fixture {
        val root = Files.createTempDirectory("bp-home-settings-")
        val plugin = DesktopPluginContext(DesktopPluginStore(root))
        val page = Any()
        val currentPage = AtomicReference(page)
        val image = DesktopImageSaveLifetime { false }
        val owns = { currentPage.get() === page && image.isActive() }
        val context = DesktopOriginalPlayerSettingsContext(plugin, owns, image::withCommit)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        suspend fun <T> operation(block: suspend () -> T) = DesktopOriginalPlaybackPreferenceOperation.run(context, owns, block)
        fun disk() = Files.readAllBytes(root.resolve("plugin-settings.json"))
        fun retirePage() { currentPage.set(Any()) }
        fun close() { scope.cancel(); image.close() }
        fun home() = decodeDesktopOriginalHomeSettings(plugin.store.snapshot("settings").value)
    }

    @Test fun originalHeroPersistenceReachesActualExistingHomePortAndColdBacking(): Unit = runBlocking {
        val f = Fixture()
        try {
            val home = DesktopOriginalHomePreferences.create(f.plugin.store, f.scope, false) { false }
            assertFalse(DesktopOriginalHomeSettingsManager.getHomeHeroCarouselEnabled(f.context).first())
            assertFalse(DesktopOriginalHomeSettingsManager.getHomeHeroCarouselAutoplayEnabled(f.context).first())
            f.operation {
                DesktopOriginalHomeSettingsManager.setHomeHeroCarouselEnabled(f.context, true)
                DesktopOriginalHomeSettingsManager.setHomeHeroCarouselAutoplayEnabled(f.context, true)
            }
            val actual = withTimeout(5000) { home.homeSettings.first { it.homeHeroCarouselEnabled && it.homeHeroCarouselAutoplayEnabled } }
            assertTrue(actual.homeHeroCarouselEnabled)
            assertTrue(actual.homeHeroCarouselAutoplayEnabled)
            val cold = DesktopPluginContext(DesktopPluginStore(f.root))
            val decoded = decodeDesktopOriginalHomeSettings(cold.store.snapshot("settings").value)
            assertEquals(actual.homeHeroCarouselEnabled, decoded.homeHeroCarouselEnabled)
            assertEquals(actual.homeHeroCarouselAutoplayEnabled, decoded.homeHeroCarouselAutoplayEnabled)
        } finally { f.close() }
    }

    @Test fun wallpaperSelectionEffectAndChatScopeUseSameCanonicalKeys(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
            val uri = f.root.resolve("fixture-image.png").toUri().toString()
            f.operation {
                DesktopOriginalHomeSettingsManager.setHomeWallpaperUri(f.context, uri)
                DesktopOriginalHomeSettingsManager.setHomeWallpaperEffectMode(f.context, HomeWallpaperEffectMode.STRONG_BLUR)
                DesktopOriginalHomeSettingsManager.setHomeWallpaperEffectScope(f.context, HomeWallpaperEffectScope.GLOBAL)
            }
            assertEquals(uri, DesktopOriginalHomeSettingsManager.getHomeWallpaperUri(f.context).first())
            assertEquals(HomeWallpaperEffectMode.STRONG_BLUR, f.home().homeWallpaperEffectMode)
            assertEquals(HomeWallpaperEffectScope.GLOBAL, f.home().homeWallpaperEffectScope)
            assertEquals("keep", f.plugin.store.preferences("settings")["foreign"]!!.jsonPrimitive.content)
        } finally { f.close() }
    }

    @Test fun originalDisplayModeActionCommitsCanonicalAndAppPrefsMirrorTogether(): Unit = runBlocking {
        val f = Fixture()
        try {
            val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val actions = DesktopOriginalHomeSettingsActions(f.scope, f.context, f.owns, {}, { failures.add(it) })
            val vm = DesktopOriginalHomeSettingsViewModel(f.context, actions)
            vm.setDisplayMode(1)
            withTimeout(5000) { f.plugin.store.snapshot("settings").first { it[playerIntPreferencesKey("display_mode")] == 1 } }
            assertEquals(1, f.home().displayMode)
            assertEquals(1, f.plugin.store.preferences("app_prefs")["display_mode"]!!.jsonPrimitive.int)
            assertTrue(failures.isEmpty())
        } finally { f.close() }
    }

    @Test fun originalOnlineCountSetterKeepsVideoOverlayMirrorAndUnrelatedKeys(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("video_overlay_cache", mapOf("foreign" to JsonPrimitive(7)))
            f.operation { DesktopOriginalHomeSettingsManager.setShowOnlineCount(f.context, true) }
            assertTrue(DesktopOriginalHomeSettingsManager.getShowOnlineCount(f.context).first())
            val mirror = f.plugin.store.preferences("video_overlay_cache")
            assertTrue(mirror["show_online_count"]!!.jsonPrimitive.boolean)
            assertEquals(7, mirror["foreign"]!!.jsonPrimitive.int)
        } finally { f.close() }
    }

    @Test fun readDependentGlassMigrationReplaysAgainstFreshCanonicalSnapshot(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("settings", mapOf("home_card_dynamic_tint_enabled" to JsonPrimitive(false)))
            f.operation {
                DesktopOriginalHomeSettingsManager.setHomeCardDynamicTintEnabled(f.context, false)
                // A different existing actor commits while the original recipe is pending.
                f.plugin.store.update("settings", mapOf("home_card_dynamic_tint_enabled" to JsonPrimitive(true), "foreign" to JsonPrimitive("fresh")))
            }
            val values = f.plugin.store.preferences("settings")
            assertTrue(values["home_card_frosted_glass_enabled"]!!.jsonPrimitive.boolean)
            assertFalse(values["home_card_dynamic_tint_enabled"]!!.jsonPrimitive.boolean)
            assertEquals("fresh", values["foreign"]!!.jsonPrimitive.content)
        } finally { f.close() }
    }

    @Test fun originalDurationSettingKeepsLegacyBadgeConsumerInSameCommit(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.operation { DesktopOriginalHomeSettingsManager.setHomeDurationStyle(f.context, HomeDurationStyle.HIDDEN) }
            assertEquals(HomeDurationStyle.HIDDEN, DesktopOriginalHomeSettingsManager.getHomeDurationStyle(f.context).first())
            assertFalse(f.plugin.store.preferences("settings")["home_video_duration_badges_visible"]!!.jsonPrimitive.boolean)
        } finally { f.close() }
    }

    @Test fun sameSettingsTargetSuccessorObjectRejectsAllOldPageNamespaces(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
            val bytes = f.disk()
            assertThrows(CancellationException::class.java) { runBlocking {
                f.operation {
                    f.context.getSharedPreferences("app_prefs", 0).edit().putInt("display_mode", 1).apply()
                    DesktopOriginalHomeSettingsManager.setDisplayMode(f.context, 1)
                    DesktopOriginalHomeSettingsManager.setShowOnlineCount(f.context, true)
                    f.retirePage()
                }
            } }
            assertArrayEquals(bytes, f.disk())
            assertTrue(f.plugin.store.preferences("app_prefs").isEmpty())
            assertTrue(f.plugin.store.preferences("video_overlay_cache").isEmpty())
        } finally { f.close() }
    }

    @Test fun actualImageRetirementRejectsPendingHeroWallpaperAndMirrorWrites(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("settings", mapOf("foreign" to JsonPrimitive(12)))
            val bytes = f.disk()
            assertThrows(CancellationException::class.java) { runBlocking {
                f.operation {
                    DesktopOriginalHomeSettingsManager.setHomeHeroCarouselEnabled(f.context, true)
                    DesktopOriginalHomeSettingsManager.setHomeWallpaperUri(f.context, "file:///retired.png")
                    DesktopOriginalHomeSettingsManager.setShowOnlineCount(f.context, true)
                    f.image.close()
                }
            } }
            assertArrayEquals(bytes, f.disk())
            assertFalse(f.home().homeHeroCarouselEnabled)
        } finally { f.close() }
    }

    @Test fun originalBackToTopCacheAndNoticePublishOnlyAfterCanonicalReset(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("settings", mapOf("back_to_top_button_offset_x_dp" to JsonPrimitive(31f), "back_to_top_button_offset_y_dp" to JsonPrimitive(42f)))
            val back = DesktopFavoritePreferences(f.plugin.store)
            val notices = mutableListOf<String>()
            val actions = DesktopOriginalHomeSettingsActions(f.scope, f.context, f.owns, { notices += it }, { throw it })
            actions.launch {
                DesktopOriginalHomeBackToTopSettings.resetCustomOffset(f.context)
                afterCommitResetBackToTopOffset(back)
                afterCommitNotice("已恢复回顶按钮默认位置")
            }.join()
            assertEquals(0f to 0f, back.initialBackToTopOffset())
            assertFalse(f.plugin.store.preferences("settings").containsKey("back_to_top_button_offset_x_dp"))
            assertFalse(f.plugin.store.preferences("settings").containsKey("back_to_top_button_offset_y_dp"))
            assertEquals(listOf("已恢复回顶按钮默认位置"), notices)
        } finally { f.close() }
    }

    @Test fun retiredPageCannotResetActualOffsetCacheOrReportSuccess(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.plugin.store.update("settings", mapOf("back_to_top_button_offset_x_dp" to JsonPrimitive(31f), "back_to_top_button_offset_y_dp" to JsonPrimitive(42f)))
            val bytes = f.disk()
            val back = DesktopFavoritePreferences(f.plugin.store)
            val notices = mutableListOf<String>()
            val actions = DesktopOriginalHomeSettingsActions(f.scope, f.context, f.owns, { notices += it }, { throw it })
            val job = actions.launch {
                DesktopOriginalHomeBackToTopSettings.resetCustomOffset(f.context)
                afterCommitResetBackToTopOffset(back)
                afterCommitNotice("已恢复回顶按钮默认位置")
                f.retirePage()
            }
            job.join()
            assertTrue(job.isCancelled)
            assertArrayEquals(bytes, f.disk())
            assertEquals(31f to 42f, back.initialBackToTopOffset())
            assertTrue(notices.isEmpty())
        } finally { f.close() }
    }

    @Test fun originalHeaderHideDelegationKeepsModernAndLegacyActualHomeFields(): Unit = runBlocking {
        val f = Fixture()
        try {
            val home = DesktopOriginalHomePreferences.create(f.plugin.store, f.scope, false) { false }
            f.operation { DesktopOriginalHomeSettingsManager.setHeaderCollapseEnabled(f.context, false) }
            val off = withTimeout(5000) { home.homeSettings.first { it.homeHeaderCollapseMode == HomeHeaderCollapseMode.OFF } }
            assertFalse(off.isHeaderCollapseEnabled)
            assertEquals(HomeHeaderCollapseMode.OFF.value,
                f.plugin.store.preferences("settings")["home_header_collapse_mode"]!!.jsonPrimitive.int)
            assertFalse(f.plugin.store.preferences("settings")["header_collapse_enabled"]!!.jsonPrimitive.boolean)
            f.operation { DesktopOriginalHomeSettingsManager.setHeaderCollapseEnabled(f.context, true) }
            val on = withTimeout(5000) { home.homeSettings.first { it.homeHeaderCollapseMode == HomeHeaderCollapseMode.BOTH } }
            assertTrue(on.isHeaderCollapseEnabled)
            assertTrue(f.plugin.store.preferences("settings")["header_collapse_enabled"]!!.jsonPrimitive.boolean)
            val before = f.disk()
            assertThrows(CancellationException::class.java) { runBlocking {
                f.operation {
                    DesktopOriginalHomeSettingsManager.setHeaderCollapseEnabled(f.context, false)
                    f.retirePage()
                }
            } }
            assertArrayEquals(before, f.disk())
            assertEquals(HomeHeaderCollapseMode.BOTH, f.home().homeHeaderCollapseMode)
        } finally { f.close() }
    }
}
