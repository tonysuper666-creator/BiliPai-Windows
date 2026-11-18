package com.bilipai.desktop.settings

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class DesktopAndroidAppOpsCapabilityTest {
    @Test fun actualPageAndWrappedApplicationCannotGrantAndroidPictureInPicture() {
        val root = Files.createTempDirectory("bp-pip-capability-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            plugin.store.update("settings", mapOf("hw_decode" to JsonPrimitive(false)))
            val before = plugin.store.preferences("settings")
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("A permission query must not write preferences")
            })
            val original = page.androidContext
            val wrapped = Application(Application(original))
            for (context in listOf(original, wrapped)) {
                assertEquals(-1, context.applicationInfo.uid, "No Android package UID on desktop")
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                // The original PlaybackSettingsScreen.kt:231-237 uses exactly this service,
                // package identity and MODE_ALLOWED comparison. This does not render the page.
                val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    context.applicationInfo.uid, context.packageName)
                assertEquals(AppOpsManager.MODE_ERRORED, mode)
                assertFalse(mode == AppOpsManager.MODE_ALLOWED)
                assertEquals(before, DesktopPluginStore(root).preferences("settings"))
            }
            assertEquals(original.applicationInfo.dataDir, wrapped.applicationInfo.dataDir)
            assertSame(original.getSystemService(Context.APP_OPS_SERVICE),
                wrapped.getSystemService(Context.APP_OPS_SERVICE))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unknownServiceAndAppOpFailLoudlyThroughApplication() {
        val root = Files.createTempDirectory("bp-appops-unknown-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("An unavailable capability must not write preferences")
            })
            val context = Application(Application(page.androidContext))
            val missingService = assertFailsWith<UnsupportedDesktopCapability> {
                context.getSystemService("missing-service")
            }
            assertContains(missingService.message.orEmpty(), "missing-service")
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val missingOp = assertFailsWith<UnsupportedDesktopCapability> {
                appOps.checkOpNoThrow("missing-op", context.applicationInfo.uid, context.packageName)
            }
            assertContains(missingOp.message.orEmpty(), "missing-op")
            assertTrue(DesktopPluginStore(root).preferences("settings").isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun applicationAsksTheOriginalOwnerForTheServiceExactlyOnce() {
        val root = Files.createTempDirectory("bp-appops-owner-")
        try {
            val service = Any()
            val requests = mutableListOf<String>()
            val owner = object : Context(root.toFile()) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    error("A service query must not read preferences")
                override fun getSystemService(name: String): Any {
                    requests += name
                    return service
                }
            }
            assertSame(service, Application(Application(owner)).getSystemService(Context.APP_OPS_SERVICE))
            assertEquals(listOf(Context.APP_OPS_SERVICE), requests)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
