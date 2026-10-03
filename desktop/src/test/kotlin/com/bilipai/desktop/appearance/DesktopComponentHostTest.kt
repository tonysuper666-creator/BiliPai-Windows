package com.bilipai.desktop.appearance

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class DesktopComponentHostTest {
    @TempDir lateinit var root: Path

    @Test fun originalComponentKeysSurviveSharedFacadeAndReopen(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        store.update("settings", mapOf("future_component" to JsonPrimitive("preserved")))
        val prefs = DesktopThemePrefs(store)
        prefs.setIconStyle(AppIconStyle.THEME_CONTAINER)
        prefs.setListItemStyle(AppListItemStyle.CUSTOM)
        prefs.setGlobalTextTapCopy(true)
        prefs.setHapticFeedback(false)
        prefs.setUiEntranceAnimation(false)
        prefs.setRuntimeVisualGuard(false)
        val restored = DesktopThemePrefs(DesktopPluginStore(root)).initialSettings()
        assertEquals(AppIconStyle.THEME_CONTAINER, restored.appIconStyle)
        assertEquals(AppListItemStyle.CUSTOM, restored.appListItemStyle)
        assertEquals(restored, prefs.initialSettings())
        val config = buildDesktopAppThemeConfig(restored)
        assertTrue(config.globalTextTapCopyEnabled)
        assertFalse(config.hapticFeedbackEnabled)
        assertFalse(config.uiEntranceAnimationEnabled)
        assertFalse(config.runtimeVisualGuardEnabled)
        assertTrue(config.nativeMiuixPopupsEnabled)
        assertFalse(config.liquidGlassEnabled)
        assertEquals("preserved", store.preferences("settings")["future_component"]?.jsonPrimitive?.content)
    }

    @Test fun invalidComponentPreferencesKeepOriginalDefaults() {
        val state = decodeDesktopThemeSettings(JsonObject(mapOf(
            "app_icon_style" to JsonPrimitive("gone"), "app_list_item_style" to JsonPrimitive(2),
            "global_text_tap_copy_enabled" to JsonPrimitive("true"), "haptic_feedback_enabled" to JsonPrimitive("false"),
        )))
        assertEquals(AppIconStyle.AUTO, state.appIconStyle)
        assertEquals(AppListItemStyle.AUTO, state.appListItemStyle)
        assertEquals(AppThemeConfig(progressiveTopBlurEnabled = false), buildDesktopAppThemeConfig(state))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun rootThemePropagatesObservedOriginalComponentLocals(): Unit = runBlocking {
        val settings = mutableStateOf(DesktopThemeSettings(themeMode = AppThemeMode.LIGHT))
        var observed: Triple<AppIconStyle, AppListItemStyle, AppThemeConfig>? = null
        val scene = ImageComposeScene(400, 250, Density(1f)) {
            DesktopAppearanceTheme(settings.value, systemLanguageTags = listOf("en")) {
                observed = Triple(LocalAppIconStyle.current, LocalAppListItemStyle.current, LocalAppThemeConfig.current)
                DesktopAppearanceText("Original component host")
            }
        }
        try {
            scene.render().close()
            assertEquals(Triple(AppIconStyle.AUTO, AppListItemStyle.AUTO, AppThemeConfig(progressiveTopBlurEnabled = false)), observed)
            settings.value = settings.value.copy(appIconStyle = AppIconStyle.MD3_STANDARD, appListItemStyle = AppListItemStyle.CUSTOM,
                globalTextTapCopyEnabled = true, uiEntranceAnimationEnabled = false,
                progressiveTopBlurEnabled = true, headerBlurEnabled = false)
            scene.render().close()
            val updated = requireNotNull(observed)
            assertEquals(AppIconStyle.MD3_STANDARD, updated.first)
            assertEquals(AppListItemStyle.CUSTOM, updated.second)
            assertTrue(updated.third.globalTextTapCopyEnabled)
            assertFalse(updated.third.uiEntranceAnimationEnabled)
            assertFalse(updated.third.liquidGlassEnabled)
            assertTrue(updated.third.progressiveTopBlurEnabled)
            assertFalse(updated.third.headerBlurEnabled)
        } finally { scene.close() }
    }
}
