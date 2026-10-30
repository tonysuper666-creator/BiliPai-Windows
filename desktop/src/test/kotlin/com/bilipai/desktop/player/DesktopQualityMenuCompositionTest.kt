package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real menu content with the existing presenter boundary; no native popup or playback request. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopQualityMenuCompositionTest {
    @Test fun authoritativePlayableIdsEnableOnlyUnselectedReturnedTracks() = runBlocking {
        val host = Host()
        try {
            host.until { host.actions.size == 1 }
            assertTrue(host.selected.isEmpty())
            // 112 is playable even without VIP; 80 is selected; 64/120 are unavailable.
            host.actions.values.single().invoke()
            assertEquals(listOf(2), host.selected)
            host.playable.value = emptyList()
            host.until { host.actions.isEmpty() }
            assertEquals(listOf(2), host.selected)
            host.playable.value = listOf(64)
            host.until { host.actions.size == 1 }
            host.actions.values.single().invoke()
            assertEquals(listOf(2, 0), host.selected)
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
        val playable = mutableStateOf(listOf(80, 112))
        val actions = linkedMapOf<Any, () -> Unit>()
        val selected = mutableListOf<Int>()
        private val menuScope = AppGlassMenuScope(
            pressedItem = { null },
            register = { key, _, enabled, _, action -> if (enabled) actions[key] = action else actions.remove(key) },
            unregister = { actions.remove(it) }, dispatch = { action, _ -> action() },
        )
        lateinit var presenter: RecordingPresenter
        private val scene = ImageComposeScene(640, 480, Density(1f)) {
            val window = LocalWindowInfo.current
            presenter = remember(window) { RecordingPresenter(window) }
            CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true),
                LocalAppGlassMenuPresenter provides presenter) {
                com.android.purebilibili.feature.video.ui.components.QualitySelectionMenu(
                    qualities = listOf("720P", "1080P", "1080P+", "4K"),
                    qualityIds = listOf(64, 80, 112, 120), switchableQualityIds = playable.value,
                    currentQuality = "1080P", isLoggedIn = false, isVip = false,
                    onQualitySelected = { selected += it }, onDismiss = {},
                )
                presenter.active?.second?.content?.invoke(menuScope)
            }
        }
        suspend fun frames() { repeat(5) { scene.render().close(); yield() } }
        suspend fun until(condition: () -> Boolean) {
            withTimeout(10_000) { do { frames() } while (!condition()) }
        }
        fun close() = scene.close()
    }
}
