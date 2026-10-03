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
    @Test fun oldFirstLaunchFlagCannotBypassVersionedAgreement(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bp-onboarding-legacy-")))
        context.getSharedPreferences(APP_WELCOME_PREFS_NAME, 0).edit().putBoolean("first_launch_shown", true).apply()
        val preferences = DesktopOriginalOnboardingPreferences(context)
        assertTrue(preferences.isRequired())
        assertEquals(listOf(BiliPaiNavKey.Onboarding), preferences.initialStack())
        assertFalse(canAcknowledgeUserAgreement(true, false, true))
        assertTrue(canAcknowledgeUserAgreement(true, true, true))
    }

    @Test fun durableOriginalFlagsAndStartupPolicyShareSameStore(): Unit = runBlocking {
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
        assertEquals(listOf(BiliPaiNavKey.MainHost, BiliPaiNavKey.Story()), restarted.initialStack())
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
        assertTrue(preferences.isRequired())
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
        assertTrue(preferences.isRequired())
        assertTrue(context.store.preferences(APP_WELCOME_PREFS_NAME).isEmpty())
    }
}
