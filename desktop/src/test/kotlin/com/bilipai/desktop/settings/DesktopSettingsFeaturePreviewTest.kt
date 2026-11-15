package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.R
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.SettingsFeaturePreview
import com.android.purebilibili.feature.settings.SettingsFeaturePreviewEntry
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

/** Exercise the original preview controls, including the card renderer, on the desktop CPU scene. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopSettingsFeaturePreviewTest {
    @Test fun everyOriginalPreviewOpensAndTogglesWithoutWritingTheMountedPreferences(): Unit = runBlocking {
        for (style in AppUiStyle.entries) for (feature in SettingsFeaturePreview.entries) {
            val root = Files.createTempDirectory("bp-settings-preview-")
            val store = DesktopPluginStore(root)
            store.update("settings", mapOf("hw_decode" to JsonPrimitive(false)))
            val before = store.preferences("settings")
            var writeAdmissions = 0
            val page = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, {
                writeAdmissions++
                it()
                true
            })
            val lifecycle = object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                override val lifecycle: Lifecycle get() = registry
            }
            val navigation = object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
            val background = object : DesktopHomeWindowBackgroundPort {
                override val isInBackground = false
                override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
                override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            }
            val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
            val scene = ImageComposeScene(720, 900, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false)) {
                    CompositionLocalProvider(
                        LocalDesktopHomePlatform provides platform,
                        LocalLifecycleOwner provides lifecycle,
                        LocalNavigationEventDispatcherOwner provides navigation,
                        LocalDesktopUpstreamAndroidContext provides page.androidContext,
                    ) {
                        DesktopDetailWindow {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    SettingsFeaturePreviewEntry(feature, currentEnabled = false)
                                }
                            }
                        }
                    }
                }
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            fun choices() = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
            suspend fun await(description: String, condition: () -> Boolean) {
                withTimeout(5_000) {
                    do {
                        scene.render(System.nanoTime()).close()
                        if (condition()) return@withTimeout
                        delay(12)
                    } while (true)
                }
                assertTrue(condition(), "$style/$feature: $description")
            }
            fun click(node: SemanticsNode) {
                assertEquals(true, node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            }
            try {
                val title = page.androidContext.getString(feature.titleResId)
                val entry = page.androidContext.getString(R.string.preview_accessibility, title)
                await("preview entry") { nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(entry) == true } }
                click(nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(entry) == true })
                await("original disabled and enabled choices") { choices().size == 2 }
                val originallySelected = choices().single { it.config[SemanticsProperties.Selected] }.id
                click(choices().single { !it.config[SemanticsProperties.Selected] })
                await("demo enabled") {
                    val selected = choices().singleOrNull { it.config[SemanticsProperties.Selected] }
                    selected != null && selected.id != originallySelected
                }
                assertEquals(2, choices().size)
                val close = page.androidContext.getString(R.string.common_close)
                click(nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
                    .filter { descendants(it).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == close } == true } }
                    .minBy { it.boundsInRoot.width * it.boundsInRoot.height })
                await("preview dismissed") { choices().isEmpty() }
                assertEquals(0, writeAdmissions, "$style/$feature must remain a demonstration")
                assertEquals(before, DesktopPluginStore(root).preferences("settings"))
            } finally {
                scene.close()
                lifecycle.registry.currentState = Lifecycle.State.DESTROYED
                navigation.navigationEventDispatcher.dispose()
            }
        }
    }
}
