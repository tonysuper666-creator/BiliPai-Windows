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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.store.FullscreenActionCount
import com.android.purebilibili.core.store.FullscreenActionCountSettings
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.FullscreenActionCountPreference
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

/** Compiling the original control does not establish a desktop player consumer. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopFullscreenActionCountPreferenceTest {
    @Test fun originalDialogPersistsEachCountInTheMountedStoreAndRestoresItOnReopen(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val root = Files.createTempDirectory("bp-action-counts-")
            val store = DesktopPluginStore(root)
            store.update("settings", mapOf("hw_decode" to JsonPrimitive(false)))
            val before = store.preferences("settings")
            var admissions = 0
            val page = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, {
                admissions++
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
                                    FullscreenActionCountPreference()
                                }
                            }
                        }
                    }
                }
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            fun hasText(node: SemanticsNode, title: String) = descendants(node).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == title } == true
            }
            fun switches() = nodes().filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            fun clickLabel(title: String) {
                val node = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null && hasText(it, title) }
                    .minBy { it.boundsInRoot.width * it.boundsInRoot.height }
                assertEquals(true, node.config[SemanticsActions.OnClick].action?.invoke(), "$style: click $title")
            }
            fun switchFor(count: FullscreenActionCount): SemanticsNode {
                val row = nodes().filter { hasText(it, count.title) && descendants(it).count {
                    child -> child.config.getOrNull(SemanticsProperties.ToggleableState) != null
                } == 1 }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
                return descendants(row).single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            }
            suspend fun await(description: String, condition: () -> Boolean) {
                withTimeout(5_000) {
                    do {
                        scene.render(System.nanoTime()).close()
                        if (condition()) return@withTimeout
                        delay(12)
                    } while (true)
                }
                assertTrue(condition(), "$style: $description")
            }
            val keys = mapOf(
                FullscreenActionCount.LIKE to "show_fullscreen_like_count",
                FullscreenActionCount.COMMENT to "show_fullscreen_comment_count",
                FullscreenActionCount.FAVORITE to "show_fullscreen_favorite_count",
            )
            try {
                await("entry loaded") { nodes().any { hasText(it, FullscreenActionCountSettings.TITLE) } }
                clickLabel(FullscreenActionCountSettings.TITLE)
                await("three enabled defaults") {
                    switches().size == 3 && switches().all {
                        it.config[SemanticsProperties.ToggleableState] == ToggleableState.On &&
                            it.config.getOrNull(SemanticsProperties.Disabled) == null
                    }
                }
                val disabled = mutableSetOf<FullscreenActionCount>()
                for (count in FullscreenActionCount.entries) {
                    val toggle = switchFor(count)
                    assertEquals(true, toggle.config[SemanticsActions.OnClick].action?.invoke())
                    disabled += count
                    await("${count.title} persisted and reflected") {
                        val persisted = DesktopPluginStore(root).preferences("settings")
                        disabled.all { persisted[keys.getValue(it)] == JsonPrimitive(false) } &&
                            disabled.all { switchFor(it).config[SemanticsProperties.ToggleableState] == ToggleableState.Off }
                    }
                    assertEquals(FullscreenActionCount.entries.toSet() - disabled,
                        FullscreenActionCountSettings.getVisibleCounts(page.androidContext).first())
                    assertEquals(before + disabled.associate { keys.getValue(it) to JsonPrimitive(false) },
                        DesktopPluginStore(root).preferences("settings"), "$style: unrelated preferences unchanged")
                }
                assertTrue(admissions > 0, "$style: writes must use the mounted owner")
                clickLabel("完成")
                await("dialog closed") { switches().isEmpty() }
                clickLabel(FullscreenActionCountSettings.TITLE)
                await("reopened with all counts off") {
                    switches().size == 3 && switches().all { it.config[SemanticsProperties.ToggleableState] == ToggleableState.Off }
                }
                clickLabel("完成")
                await("closed again") { switches().isEmpty() }
            } finally {
                scene.close()
                lifecycle.registry.currentState = Lifecycle.State.DESTROYED
                navigation.navigationEventDispatcher.dispose()
            }
        }
    }
}
