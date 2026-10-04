package com.bilipai.desktop.ui

import com.android.purebilibili.feature.onboarding.*
import com.android.purebilibili.feature.settings.RELEASE_DISCLAIMER_ACK_KEY
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class DesktopOriginalOnboardingPreferencesTest {
    @Test fun freshWindowsStartupGoesHomeWithoutAcceptingOrWritingPreferences() {
        val root = Files.createTempDirectory("bp-windows-startup-fresh-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val preferences = DesktopOriginalOnboardingPreferences(context)
        assertFalse(preferences.isRequired())
        assertFalse(preferences.openPortraitFeedOnStartup)
        assertEquals(listOf(BiliPaiNavKey.MainHost), preferences.initialStack())
        assertTrue(context.store.preferences(APP_WELCOME_PREFS_NAME).isEmpty())
        assertTrue(context.store.preferences("settings").isEmpty())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun oldWindowsStartupPreferencesArePreservedByteForByte() {
        val root = Files.createTempDirectory("bp-windows-startup-old-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        context.getSharedPreferences(APP_WELCOME_PREFS_NAME, 0).edit()
            .putBoolean("first_launch_shown", true)
            .putBoolean(USER_AGREEMENT_ACK_KEY, false)
            .putBoolean(RELEASE_DISCLAIMER_ACK_KEY, false).apply()
        context.getSharedPreferences("settings", 0).edit()
            .putBoolean("launch_to_portrait_feed_on_startup", true)
            .putBoolean("crash_tracking_enabled", false)
            .putBoolean("crash_tracking_consent_shown", false)
            .putBoolean("enhanced_diagnostic_logging_enabled", false).apply()
        val file = root.resolve("plugin-settings.json")
        val before = Files.readAllBytes(file)
        val preferences = DesktopOriginalOnboardingPreferences(context)
        assertFalse(preferences.isRequired())
        assertFalse(preferences.openPortraitFeedOnStartup)
        assertEquals(listOf(BiliPaiNavKey.MainHost), preferences.initialStack())
        assertEquals(listOf(BiliPaiNavKey.MainHost), preferences.initialStack(false))
        assertArrayEquals(before, Files.readAllBytes(file))
        assertFalse(context.store.preferences(APP_WELCOME_PREFS_NAME)[USER_AGREEMENT_ACK_KEY]!!.jsonPrimitive.boolean)
        assertFalse(context.store.preferences(APP_WELCOME_PREFS_NAME)[RELEASE_DISCLAIMER_ACK_KEY]!!.jsonPrimitive.boolean)
        assertFalse(context.store.preferences("settings")["crash_tracking_consent_shown"]!!.jsonPrimitive.boolean)
        assertFalse(context.store.preferences("settings")["enhanced_diagnostic_logging_enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun explicitManualAcknowledgementKeepsOriginalDurabilityAndCannotChangeWindowsStartup(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-onboarding-persist-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        context.getSharedPreferences(APP_WELCOME_PREFS_NAME, 0).edit().putString("keep", "original").apply()
        context.getSharedPreferences("settings", 0).edit().putBoolean("launch_to_portrait_feed_on_startup", true).apply()
        val preferences = DesktopOriginalOnboardingPreferences(context)
        preferences.acknowledge({ true }, { it(); true })
        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        val welcome = disk[APP_WELCOME_PREFS_NAME]!!.jsonObject
        for (key in listOf(USER_AGREEMENT_ACK_KEY, "first_launch_shown", RELEASE_DISCLAIMER_ACK_KEY))
            assertTrue(welcome[key]!!.jsonPrimitive.boolean)
        assertEquals("original", welcome["keep"]!!.jsonPrimitive.content)
        val restarted = DesktopOriginalOnboardingPreferences(DesktopPluginContext(DesktopPluginStore(root)))
        assertFalse(restarted.isRequired())
        assertFalse(restarted.openPortraitFeedOnStartup)
        assertEquals(listOf(BiliPaiNavKey.MainHost), restarted.initialStack())
        assertEquals(listOf(BiliPaiNavKey.MainHost), restarted.initialStack(includeStartupPortraitFeed = false))
    }

    @Test fun ownerRetiredAtFinalPermitCannotPublishAnAck(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-onboarding-retire-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val preferences = DesktopOriginalOnboardingPreferences(context)
        val owned = AtomicBoolean(true)
        assertThrows(CancellationException::class.java) {
            runBlocking { preferences.acknowledge(owned::get) { action -> owned.set(false); action(); true } }
        }
        assertFalse(preferences.isRequired())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        assertTrue(context.store.preferences(APP_WELCOME_PREFS_NAME).isEmpty())
        assertThrows(CancellationException::class.java) { runBlocking { preferences.acknowledge({ true }, { false }) } }
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun failedDurableRenameLeavesAllAckFieldsUnpublished(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-onboarding-failure-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val preferences = DesktopOriginalOnboardingPreferences(context)
        val obstructedFile = Files.createDirectory(root.resolve("plugin-settings.json"))
        Files.writeString(obstructedFile.resolve("block"), "prevent replacement")
        assertThrows(Exception::class.java) { runBlocking { preferences.acknowledge({ true }, { it(); true }) } }
        assertFalse(preferences.isRequired())
        assertTrue(context.store.preferences(APP_WELCOME_PREFS_NAME).isEmpty())
    }
}
