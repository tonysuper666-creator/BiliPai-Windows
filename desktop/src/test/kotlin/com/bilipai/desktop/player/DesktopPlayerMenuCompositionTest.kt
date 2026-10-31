package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.video.ui.components.PlayerListPopupPlacement
import com.android.purebilibili.feature.video.ui.components.PlayerMiuixListPopup
import com.android.purebilibili.feature.video.ui.overlay.PlaybackOrderSelectionSheet
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Actual menu composition; the presenter records the host boundary, not a native window. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopPlayerMenuCompositionTest {
    @Test
    fun actualOrderMenuSelectsAnEnabledAlternativeAndCloses() = runBlocking {
        val host = Host(orderMenu = true)
        try {
            host.until { host.actions.size == 4 }
            assertEquals("选择播放顺序", host.presenter.requests.single().second.title)
            assertEquals(AppGlassMenuPlacement.END, host.presenter.requests.single().second.placement)
            // STOP is selected and disabled; the first enabled native menu row is PLAY_IN_ORDER.
            host.actions.values.first().invoke()
            host.until { !host.visible.value && host.actions.isEmpty() }
            assertEquals(PlaybackCompletionBehavior.PLAY_IN_ORDER, host.selection)
            assertEquals(1, host.dismissed)
        } finally { host.close() }
    }

    @Test
    fun legacyControlUsesOneUpstreamRequestAndItsDismissCallback() = runBlocking {
        val host = Host()
        try {
            host.until { host.presenter.requests.size == 1 }
            val (owner, request) = host.presenter.requests.single()
            assertEquals("画质选择", request.title)
            assertEquals(AppGlassMenuPlacement.END_BOTTOM, request.placement)
            assertEquals(120.dp, request.minWidth)
            assertEquals(280.dp, request.maxWidth)
            assertEquals(208.dp, request.maxHeight)
            assertTrue(request.fitWholeItems)
            assertTrue(request.useAvailableHeight)
            request.onDismissRequest()
            host.until { host.dismissed == 1 && owner in host.presenter.dismissals }
            assertFalse(host.visible.value)
            assertEquals(1, host.presenter.requests.size)
        } finally { host.close() }
    }

    @Test
    fun retiredMenuCallbackCannotDismissTheReplacementMenu() = runBlocking {
        val host = Host()
        try {
            host.until { host.presenter.requests.size == 1 }
            val (oldOwner, oldRequest) = host.presenter.requests.single()
            host.epoch.intValue++
            host.until { host.presenter.requests.size == 2 }
            val (newOwner, newRequest) = host.presenter.requests.last()
            assertNotSame(oldOwner, newOwner)
            assertTrue(oldOwner in host.presenter.dismissals)
            oldRequest.onDismissRequest()
            host.frames()
            assertEquals(0, host.dismissed)
            assertTrue(host.visible.value)
            assertFalse(newOwner in host.presenter.dismissals)
            newRequest.onDismissRequest()
            host.until { host.dismissed == 1 && newOwner in host.presenter.dismissals }
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

    private class Host(private val orderMenu: Boolean = false) {
        val visible = mutableStateOf(true)
        val epoch = mutableIntStateOf(0)
        var dismissed = 0
        var selection: PlaybackCompletionBehavior? = null
        val actions = linkedMapOf<Any, () -> Unit>()
        private val menuScope = AppGlassMenuScope(
            pressedItem = { null },
            register = { key, _, enabled, _, action ->
                if (enabled) actions[key] = action else actions.remove(key)
            },
            unregister = { actions.remove(it) },
            dispatch = { action, _ -> action() },
        )
        lateinit var presenter: RecordingPresenter
        private val scene = ImageComposeScene(640, 240, Density(1f)) {
            val window = LocalWindowInfo.current
            presenter = remember(window) { RecordingPresenter(window) }
            CompositionLocalProvider(
                LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true),
                LocalAppGlassMenuPresenter provides presenter,
            ) {
                if (visible.value) key(epoch.intValue) {
                    if (orderMenu) PlaybackOrderSelectionSheet(
                        currentBehavior = PlaybackCompletionBehavior.STOP_AFTER_CURRENT,
                        onSelect = { selection = it },
                        onDismiss = { dismissed++; visible.value = false },
                        isFullscreen = true,
                    ) else PlayerMiuixListPopup(
                        title = "画质选择",
                        placement = PlayerListPopupPlacement.END_BOTTOM,
                        minWidth = 120.dp,
                        maxHeight = 440.dp,
                        onDismissRequest = { dismissed++; visible.value = false },
                    ) { }
                }
                if (orderMenu) presenter.active?.second?.content?.invoke(menuScope)
            }
        }
        suspend fun frames() {
            repeat(5) { scene.render().close(); yield() }
        }
        suspend fun until(condition: () -> Boolean) {
            withTimeout(10_000) { do { frames() } while (!condition()) }
        }
        fun close() = scene.close()
    }
}
