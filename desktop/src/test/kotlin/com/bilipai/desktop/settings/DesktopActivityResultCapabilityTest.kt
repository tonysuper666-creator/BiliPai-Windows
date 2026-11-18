package com.bilipai.desktop.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.DesktopUnavailableActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import kotlin.test.*
import org.junit.jupiter.api.Test

class DesktopActivityResultCapabilityTest {
    @Test fun contractPreservesTheRequestAndOnlyWrapsActualResultData() {
        val root = Files.createTempDirectory("bp-activity-result-contract-")
        try {
            val owner = object : Context(root.toFile()) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    error("Converting a request must not access preferences")
                override fun startActivity(intent: Intent): Unit = error("Conversion must not launch")
            }
            val contract = ActivityResultContracts.StartActivityForResult()
            val request = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${owner.packageName}"))
            assertSame(request, contract.createIntent(Application(Application(owner)), request))
            val response = Intent("test.result")
            val result = contract.parseResult(-1, response)
            assertEquals(-1, result.resultCode)
            assertSame(response, result.data)
            val cancelled = contract.parseResult(0, null)
            assertEquals(0, cancelled.resultCode)
            assertNull(cancelled.data)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun actualPageAndWrappedPageRejectActivityResultsWithoutWritingPreferences() {
        val root = Files.createTempDirectory("bp-activity-result-page-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("A rejected activity-result launch must not write preferences")
            })
            val before = plugin.store.preferences("settings")
            val contract = ActivityResultContracts.StartActivityForResult()
            for (context in listOf(page.androidContext, Application(Application(page.androidContext)))) {
                val launcher = DesktopUnavailableActivityResultLauncher(context, contract)
                assertSame(contract, launcher.contract)
                val request = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}"))
                val failure = assertFailsWith<UnsupportedDesktopCapability> { launcher.launch(request) }
                assertContains(failure.message.orEmpty(), Settings.ACTION_MANAGE_WRITE_SETTINGS)
                assertFalse(Settings.System.canWrite(context))
                assertEquals(before, DesktopPluginStore(root).preferences("settings"))
                launcher.unregister()
                launcher.unregister()
                assertFailsWith<IllegalStateException> { launcher.launch(request) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unsupportedLaunchDoesNotUseAnOwnerWithoutAResultProviderAndRegistrationIsLocal() {
        val root = Files.createTempDirectory("bp-activity-result-owner-")
        try {
            var launches = 0
            val owner = object : Context(root.toFile()) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    error("A launch must not access preferences")
                override fun startActivity(intent: Intent) { launches++ }
            }
            val contract = ActivityResultContracts.StartActivityForResult()
            val retired = DesktopUnavailableActivityResultLauncher(owner, contract)
            val current = DesktopUnavailableActivityResultLauncher(Application(owner), contract)
            retired.unregister()
            val request = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            assertFailsWith<IllegalStateException> { retired.launch(request) }
            assertFailsWith<UnsupportedDesktopCapability> { current.launch(request) }
            assertEquals(0, launches, "Starting an activity without a result provider would leave the permission request pending")
            current.unregister()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
