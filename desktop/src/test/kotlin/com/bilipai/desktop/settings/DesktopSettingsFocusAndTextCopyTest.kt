package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.LocalAppTextSearchQuery
import com.android.purebilibili.feature.settings.*
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

/** Exercises the original focus effect and copy preference, not the pending full playback page. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopSettingsFocusAndTextCopyTest {
    private class Pane(
        val page: DesktopOriginalPlayerSettingsContext,
        style: AppUiStyle = AppUiStyle.entries.first(),
        content: @Composable () -> Unit,
    ) : AutoCloseable {
        private val lifecycle = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        private val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
        }
        private val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
        val scene = ImageComposeScene(720, 320, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false)) {
                CompositionLocalProvider(
                    LocalDesktopHomePlatform provides platform,
                    LocalLifecycleOwner provides lifecycle,
                    LocalNavigationEventDispatcherOwner provides navigation,
                    LocalDesktopUpstreamAndroidContext provides page.androidContext,
                ) {
                    DesktopDetailWindow { Surface(Modifier.fillMaxSize()) { content() } }
                }
            }
        }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        fun toggle() = nodes().single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(7_000) {
                do {
                    scene.render(System.nanoTime()).close()
                    if (condition()) return@withTimeout
                    delay(12)
                } while (true)
            }
        }
        override fun close() {
            scene.close()
            lifecycle.registry.currentState = Lifecycle.State.DESTROYED
            navigation.navigationEventDispatcher.dispose()
            SettingsSearchFocusController.clear()
        }
    }

    private fun page() = DesktopOriginalPlayerSettingsContext(
        DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bp-section-focus-"))),
        { true }, { it(); true },
    )

    @Test fun sectionRequestsRespectTheTargetResolveLegacyKeysAndClearAfterScrolling(): Unit = runBlocking {
        SettingsSearchFocusController.clear()
        val state = LazyListState()
        val keys = (0..49).map { "section-$it" }
        Pane(page()) {
            SettingsSectionFocusEffect(state, SettingsSearchTarget.PLAYBACK, keys, mapOf("legacy" to keys[25]))
            LazyColumn(state = state) { items(keys, key = { it }) { Text(it, Modifier.height(100.dp)) } }
        }.use { pane ->
            pane.await { state.layoutInfo.totalItemsCount == keys.size }
            SettingsSearchFocusController.submit(SettingsSearchTarget.APPEARANCE, "legacy")
            val other = SettingsSearchFocusController.request.value
            // Give collection and effects a real frame before checking the other page's request.
            repeat(5) { pane.scene.render(System.nanoTime()).close(); delay(12) }
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(other, SettingsSearchFocusController.request.value)
            SettingsSearchFocusController.submit(SettingsSearchTarget.PLAYBACK, "legacy")
            pane.await { state.firstVisibleItemIndex == 25 && SettingsSearchFocusController.request.value == null }
            SettingsSearchFocusController.submit(SettingsSearchTarget.PLAYBACK, "not-mounted")
            pane.await { SettingsSearchFocusController.request.value == null }
            assertEquals(25, state.firstVisibleItemIndex)
        }
    }

    @Test fun itemRequestUsesTheDirectorySectionAndTheRealAnchorConsumesItsQuery(): Unit = runBlocking {
        SettingsSearchFocusController.clear()
        val item = settingsItemDirectory.single { it.settingId == "playback.hw_decode" }
        val state = LazyListState()
        val keys = (0..49).map { if (it == 25) item.sectionKey else "section-$it" }
        Pane(page()) {
            SettingsSectionFocusEffect(state, SettingsSearchTarget.PLAYBACK, keys)
            LazyColumn(state = state) {
                items(keys, key = { it }) { key ->
                    if (key == item.sectionKey) SettingsItemAnchor(item.settingId) {
                        Text("anchor-query:" + LocalAppTextSearchQuery.current, Modifier.height(100.dp))
                    } else Text(key, Modifier.height(100.dp))
                }
            }
        }.use { pane ->
            pane.await { state.layoutInfo.totalItemsCount == keys.size }
            SettingsSearchFocusController.submit(SettingsSearchTarget.PLAYBACK, "wrong-section", item.settingId, "  解码  ")
            pane.await {
                state.firstVisibleItemIndex == 25 && pane.nodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "anchor-query:解码" } == true
                }
            }
            pane.await { SettingsSearchFocusController.request.value == null }
            assertEquals(25, state.firstVisibleItemIndex)
        }
    }

    @Test fun itemWithoutAnAnchorScrollsToItsSectionThenTimesOutWithoutLeavingARequest(): Unit = runBlocking {
        SettingsSearchFocusController.clear()
        val item = settingsItemDirectory.single { it.settingId == "playback.hw_decode" }
        val state = LazyListState()
        val keys = (0..49).map { if (it == 25) item.sectionKey else "section-$it" }
        Pane(page()) {
            SettingsSectionFocusEffect(state, SettingsSearchTarget.PLAYBACK, keys)
            LazyColumn(state = state) { items(keys, key = { it }) { Text(it, Modifier.height(100.dp)) } }
        }.use { pane ->
            pane.await { state.layoutInfo.totalItemsCount == keys.size }
            SettingsSearchFocusController.submit(SettingsSearchTarget.PLAYBACK, "wrong-section", item.settingId)
            pane.await { state.firstVisibleItemIndex == 25 }
            assertEquals(item.settingId, SettingsSearchFocusController.request.value?.settingId)
            pane.await { SettingsSearchFocusController.request.value == null }
        }
    }

    @Test fun originalCopySwitchPersistsThroughTheMountedOwnerAndRestoresOnReopen(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val root = Files.createTempDirectory("bp-text-copy-")
            val store = DesktopPluginStore(root)
            store.update("settings", mapOf("hw_decode" to JsonPrimitive(false)))
            val before = store.preferences("settings")
            var admissions = 0
            val page = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, {
                admissions++; it(); true
            })
            Pane(page, style) { Box { SettingsTextCopyPreference() } }.use { pane ->
                pane.await { pane.nodes().any { it.config.getOrNull(SemanticsProperties.ToggleableState) != null } }
                assertEquals(ToggleableState.Off, pane.toggle().config[SemanticsProperties.ToggleableState])
                for (enabled in listOf(true, false, true)) {
                    assertEquals(true, pane.toggle().config[SemanticsActions.OnClick].action?.invoke())
                    pane.await {
                        DesktopPluginStore(root).preferences("settings")["global_text_tap_copy_enabled"] == JsonPrimitive(enabled) &&
                            pane.toggle().config[SemanticsProperties.ToggleableState] ==
                                if (enabled) ToggleableState.On else ToggleableState.Off
                    }
                    assertEquals(enabled, SettingsManager.getGlobalTextTapCopyEnabled(page.androidContext).first())
                    assertEquals(before + ("global_text_tap_copy_enabled" to JsonPrimitive(enabled)),
                        DesktopPluginStore(root).preferences("settings"), "$style: only the requested preference changes")
                }
                assertTrue(admissions > 0, "$style: write admission must use the mounted owner")
            }
            Pane(page, style) { SettingsTextCopyPreference() }.use { pane ->
                pane.await { pane.nodes().any {
                    it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
                } }
            }
        }
    }
}
