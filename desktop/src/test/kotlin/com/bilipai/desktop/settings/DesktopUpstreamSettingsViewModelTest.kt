@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bilipai.desktop.settings

import android.app.Application
import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.feature.settings.AppUpdateCheckResult
import com.android.purebilibili.feature.settings.SettingsViewModel
import com.android.purebilibili.feature.settings.SettingsViewModelFactory
import com.android.purebilibili.feature.settings.calculateInstalledApkSha256
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class DesktopUpstreamSettingsViewModelTest {
    private class Fixture {
        val root = Files.createTempDirectory("bp-upstream-settings-vm-")
        val plugin = DesktopPluginContext(DesktopPluginStore(root))
        val current = AtomicBoolean(true)
        val page = DesktopOriginalPlayerSettingsContext(plugin, current::get, {
            if (current.get()) { it(); true } else false
        })
        val application = Application(page.androidContext)
    }

    @Test fun vmPatchHasOnlyTheTwoApprovedBoundaries() {
        val patch = Files.readAllLines(Path.of("upstream-core/patches/app/src/main/java/com/android/purebilibili/feature/settings/SettingsViewModel.kt.patch"))
        val headers = patch.filter { it.startsWith("@@ ") }
        assertEquals(2, headers.size, "The original VM may have only the release-evidence and Factory patches")
        // Bound each hunk as well as their count: a large deletion cannot hide inside either hunk.
        val sizes = headers.map { Regex("@@ -\\d+,(\\d+) \\+\\d+,(\\d+) @@").matchEntire(it)!! }
        sizes.forEach { assertTrue(it.groupValues.drop(1).all { size -> size.toInt() <= 15 }) }
        val removed = patch.filter { it.startsWith("-") && !it.startsWith("---") }.map { it.drop(1).trim() }
        assertEquals(listOf(
            "AppUpdateChecker", ".check(",
            "currentVersion = com.android.purebilibili.BuildConfig.VERSION_NAME,",
            "currentVersionCode = com.android.purebilibili.BuildConfig.VERSION_CODE",
            ")", ".getOrNull()",
            "override fun <T : ViewModel> create(modelClass: Class<T>): T {",
            "if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {",
            "throw IllegalArgumentException(\"Unknown ViewModel class: \${modelClass.name}\")",
        ), removed)
        val generated = Files.readString(Path.of("build/patched-upstream/app/src/main/java/com/android/purebilibili/feature/settings/SettingsViewModel.kt"))
        assertTrue("DesktopSettingsDiagnosticsReleaseEvidence.lastAccepted(" in generated)
        assertTrue("fun ensureDiagnosticsLoaded()" in generated)
        assertTrue("fun recordSearchQuery(query: String)" in generated)
        assertFalse(Files.readAllLines(Path.of("upstream-core/m4d/later-steps.tsv"))
            .any { it.startsWith("feature/settings/SettingsViewModel.kt\t") })
        assertTrue(Files.readAllLines(Path.of("upstream-core/m4d/later-steps.tsv"))
            .any { it.startsWith("feature/settings/update/AppUpdateChecker.kt\t") })
    }

    @Test fun applicationPreservesTheAdmittedStoreAndExistingSettings(): Unit = runBlocking {
        val f = Fixture()
        f.plugin.store.update("settings", mapOf("hw_decode" to JsonPrimitive(false)))
        val original = (f.page.androidContext as DesktopPreferencesDataStoreOwner).desktopPreferencesDataStore("settings_prefs")
        val delegated = f.application.desktopPreferencesDataStore("settings_prefs")
        assertSame(original, delegated)
        assertFalse(SettingsManager.getHwDecode(f.application).first())
        SettingsManager.setHwDecode(f.application, true)
        assertEquals(JsonPrimitive(true), DesktopPluginStore(f.root).preferences("settings")["hw_decode"])
        f.current.set(false)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            delegated.edit { it[booleanPreferencesKey("hw_decode")] = false }
        }
        assertEquals(JsonPrimitive(true), DesktopPluginStore(f.root).preferences("settings")["hw_decode"])
        assertSame(f.application, AndroidViewModel(f.application).getApplication<Application>())
    }

    @Test fun lifecycleFactoryConstructsTheOriginalVmAndRejectsOtherModels() {
        val f = Fixture()
        val dispatcher = StandardTestDispatcher()
        val store = ViewModelStore()
        Dispatchers.setMain(dispatcher)
        try {
            val factory = SettingsViewModelFactory(f.application)
            val vm = ViewModelProvider.create(store, factory)[SettingsViewModel::class]
            assertSame(f.application, vm.getApplication<Application>())
            assertFailsWith<IllegalArgumentException> { factory.create(ViewModel::class, CreationExtras.Empty) }
        } finally {
            // No mounted cache owner is supplied in this factory test. Cancel the real VM before
            // its scheduled refresh runs; cache execution is a separate host integration contract.
            store.clear()
            dispatcher.scheduler.runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun acceptedReleaseEvidenceRequiresBothInstalledVersionFields() {
        val result = AppUpdateCheckResult(
            isUpdateAvailable = false, currentVersion = "0.6.0", latestVersion = "0.6.0",
            releaseUrl = "", releaseNotes = "", publishedAt = null, assets = emptyList(), message = "checked",
        )
        val state = DesktopOriginalAboutReleaseState(result = result, checkedVersionCode = 600)
        assertSame(result, DesktopSettingsDiagnosticsReleaseEvidence.select(state, "0.6.0", 600))
        assertNull(DesktopSettingsDiagnosticsReleaseEvidence.select(state, "0.6.1", 600))
        assertNull(DesktopSettingsDiagnosticsReleaseEvidence.select(state, "0.6.0", 601))
        assertNull(DesktopSettingsDiagnosticsReleaseEvidence.select(state.copy(checkedVersionCode = null), "0.6.0", 600))
        assertNull(DesktopSettingsDiagnosticsReleaseEvidence.select(DesktopOriginalAboutReleaseState(), "0.6.0", 600))
    }

    @Test fun uncheckedContextDoesNotFetchOrInventReleaseEvidence(): Unit = runBlocking {
        val application = Fixture().application
        repeat(3) {
            assertNull(DesktopSettingsDiagnosticsReleaseEvidence.lastAccepted(application, "0.6.0", 600))
        }
        // The original digest path reports desktop's lack of an APK without throwing.
        assertNull(application.applicationInfo.sourceDir)
        assertNull(calculateInstalledApkSha256(application))
    }

    @Test fun androidLauncherAliasesFailLoudly() {
        val application = Fixture().application
        assertFailsWith<UnsupportedDesktopCapability> {
            application.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(application.packageName, "PhoneLauncherAlias"), 1, 1)
        }
    }
}
