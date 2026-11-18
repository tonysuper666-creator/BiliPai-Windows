package com.bilipai.desktop.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.feature.settings.normalizeSystemBrightnessSetting
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test

class DesktopAndroidSettingsCapabilityTest {
    @Test fun unavailableAndroidBrightnessUsesOriginalNormalizationAndAdmittedPreferences(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-android-brightness-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val initial: Map<String, JsonElement> = mapOf("set_system_brightness" to JsonPrimitive(true), "hw_decode" to JsonPrimitive(false))
            plugin.store.update("settings", initial)
            var current = true
            var admissions = 0
            val page = DesktopOriginalPlayerSettingsContext(plugin, { current }, {
                if (current) { admissions++; it(); true } else false
            })
            val application = Application(Application(page.androidContext))
            assertFalse(Settings.System.canWrite(page.androidContext))
            assertFalse(Settings.System.canWrite(application))
            // This is the original page's normalization and original SettingsManager write, not a
            // desktop imitation of the permission policy. Existing unrelated preferences survive.
            val stored = SettingsManager.getSetSystemBrightnessEnabled(application).first()
            assertTrue(stored)
            val normalized = normalizeSystemBrightnessSetting(stored, Settings.System.canWrite(application))
            assertFalse(normalized)
            SettingsManager.setSetSystemBrightnessEnabled(application, normalized)
            assertTrue(admissions > 0)
            assertEquals(initial + ("set_system_brightness" to JsonPrimitive(false)),
                DesktopPluginStore(root).preferences("settings"))
            current = false
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                SettingsManager.setSetSystemBrightnessEnabled(application, true)
            }
            assertFalse(SettingsManager.getSetSystemBrightnessEnabled(application).first())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun androidSettingsLaunchesFailThroughTheActualPageAndApplicationWrappers() {
        val root = Files.createTempDirectory("bp-android-settings-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, { error("A launch must not write preferences") })
            val before = plugin.store.preferences("settings")
            for (context in listOf(page.androidContext, Application(Application(page.androidContext)))) {
                for (action in listOf(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "android.settings.PICTURE_IN_PICTURE_SETTINGS")) {
                    val request = Intent(action, Uri.parse("package:${context.packageName}"))
                    val failure = assertFailsWith<UnsupportedDesktopCapability> { context.startActivity(request) }
                    assertContains(failure.message.orEmpty(), action)
                    assertEquals(before, DesktopPluginStore(root).preferences("settings"))
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun applicationDelegatesTheExactLaunchRequestToItsContextOwner() {
        val root = Files.createTempDirectory("bp-settings-launch-owner-")
        try {
            val failure = UnsupportedDesktopCapability("test activity", "the owner rejected the launch")
            val requests = mutableListOf<Intent>()
            val owner = object : Context(root.toFile()) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    error("Launching must not read storage")
                override fun startActivity(intent: Intent) {
                    requests += intent
                    throw failure
                }
            }
            val wrapped = Application(Application(owner))
            val request = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            request.data = Uri.parse("package:${wrapped.packageName}")
            assertSame(failure, assertFailsWith<UnsupportedDesktopCapability> { wrapped.startActivity(request) })
            assertEquals(1, requests.size)
            assertSame(request, requests.single())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
