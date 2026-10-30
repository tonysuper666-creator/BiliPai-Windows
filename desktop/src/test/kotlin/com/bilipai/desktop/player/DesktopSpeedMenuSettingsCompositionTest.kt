package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import com.android.purebilibili.feature.video.ui.components.SpeedSelectionMenu
import com.android.purebilibili.feature.video.ui.components.SpeedSelectionMenuPlacement
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real menu content with the existing presenter boundary; no native popup or playback request. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopSpeedMenuSettingsCompositionTest {
    @Test fun actualMenuTracksSettingsAndTemporaryCurrentSpeed() = runBlocking {
        val host = Host()
        try {
            host.until { host.actions.size == 3 }
            assertTrue(host.selected.isEmpty())
            host.invokeInVisualOrder()
            assertEquals(listOf(0.5f, 1.3f, 2f), host.selected)
            host.selected.clear()
            host.store.update("settings", mapOf("playback_speed_options" to JsonPrimitive("1,1.5,3")))
            host.until { host.actions.size == 2 }
            host.invokeInVisualOrder()
            assertEquals(listOf(1.5f, 3f), host.selected)
            host.selected.clear()
            host.current.value = 1.25f
            host.until { host.actions.size == 3 }
            host.invokeInVisualOrder()
            assertEquals(listOf(1f, 1.5f, 3f), host.selected)
        } finally { host.close() }
    }

    private class RecordingPresenter(override val ownerView: Any) : AppGlassMenuPresenter {
        val requests = mutableListOf<Pair<Any, AppGlassMenuRequest>>()
        val dismissals = mutableListOf<Any>()
        var active by mutableStateOf<Pair<Any, AppGlassMenuRequest>?>(null)
        override fun show(owner: Any, request: AppGlassMenuRequest) {
            requests += owner to request
            active = owner to request
        }
        override fun dismiss(owner: Any) {
            dismissals += owner
            if (active?.first === owner) active = null
        }
    }

    private class Host {
        private val directory = Files.createTempDirectory("speed-menu-settings-")
        val store = DesktopPluginStore(directory).also {
            it.update("settings", mapOf("playback_speed_options" to JsonPrimitive("0.5,1,1.3,2")))
        }
        private val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        val current = mutableStateOf(1f)
        val actions = linkedMapOf<Any, Pair<LayoutCoordinates, () -> Unit>>()
        val selected = mutableListOf<Float>()
        private val menuScope = AppGlassMenuScope(
            pressedItem = { null },
            register = { key, coordinates, enabled, _, action ->
                if (enabled) actions[key] = coordinates to action else actions.remove(key)
            },
            unregister = { actions.remove(it) }, dispatch = { action, _ -> action() },
        )
        lateinit var presenter: RecordingPresenter
        private val scene = ImageComposeScene(640, 480, Density(1f)) {
            val window = LocalWindowInfo.current
            presenter = remember(window) { RecordingPresenter(window) }
            CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true),
                LocalAppGlassMenuPresenter provides presenter,
                LocalDesktopOriginalPlayerSettingsContext provides context) {
                SpeedSelectionMenu(currentSpeed = current.value, onSpeedSelected = { selected += it },
                    onDismiss = {}, placement = SpeedSelectionMenuPlacement.CENTER)
                presenter.active?.second?.content?.invoke(menuScope)
            }
        }
        fun invokeInVisualOrder() = actions.values.sortedBy { it.first.positionInRoot().y }.forEach { it.second() }
        suspend fun frames() { repeat(5) { scene.render().close(); yield() } }
        suspend fun until(condition: () -> Boolean) {
            withTimeout(10_000) { do { frames() } while (!condition()) }
        }
        fun close() { scene.close(); directory.toFile().deleteRecursively() }
    }
}
