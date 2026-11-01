package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.video.playback.audio.AudioQualityOption
import com.android.purebilibili.feature.video.ui.components.AudioQualitySelectionMenu
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopAudioMenuCompositionTest {
    @Test fun legacyCallerDisablesItsRequestedTrackAndCanSelectAuto() = runBlocking {
        val host = Host(explicitSelected = false)
        try {
            host.until { host.actions.size == 2 }
            assertEquals("音质选择", host.presenter.active!!.second.title)
            assertTrue("AAC" in host.selectedTexts())
            assertTrue("AAC 192 kbps" in host.texts())
            host.actions.values.first().invoke()
            host.until { !host.visible.value }
            assertEquals(listOf(0), host.selections)
        } finally { host.close() }
    }

    @Test fun explicitActualTrackIsHighlightedWhileRequestedAutoRemainsDisabled() = runBlocking {
        val host = Host(explicitSelected = true)
        try {
            host.until { host.actions.size == 2 }
            assertTrue("AAC" in host.selectedTexts())
            assertFalse("自动" in host.selectedTexts())
            assertTrue("FLAC 24-bit" in host.texts())
            // AUTO is requested and disabled; actual AAC remains selectable as an explicit preference.
            host.actions.values.first().invoke()
            host.until { !host.visible.value }
            assertEquals(listOf(30280), host.selections)
        } finally { host.close() }
    }

    private class Presenter(override val ownerView: Any) : AppGlassMenuPresenter {
        var active by mutableStateOf<Pair<Any, AppGlassMenuRequest>?>(null)
        override fun show(owner: Any, request: AppGlassMenuRequest) { active = owner to request }
        override fun dismiss(owner: Any) { if (active?.first === owner) active = null }
    }

    private class Host(explicitSelected: Boolean) {
        val visible = mutableStateOf(true)
        val actions = linkedMapOf<Any, () -> Unit>()
        val selections = mutableListOf<Int>()
        lateinit var presenter: Presenter
        private val scope = AppGlassMenuScope(
            pressedItem = { null },
            register = { key, _, enabled, _, action -> if (enabled) actions[key] = action else actions.remove(key) },
            unregister = { actions.remove(it) },
            dispatch = { action, _ -> action() },
        )
        private val options = listOf(
            AudioQualityOption(0, null, "自动"),
            AudioQualityOption(30280, null, "AAC", codecDescription = "AAC 192 kbps"),
            AudioQualityOption(30251, null, "Hi-Res", isHiRes = true, codecDescription = "FLAC 24-bit"),
        )
        private val scene = ImageComposeScene(640, 400, Density(1f)) {
            val window = LocalWindowInfo.current
            presenter = remember(window) { Presenter(window) }
            CompositionLocalProvider(
                LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true),
                LocalAppGlassMenuPresenter provides presenter,
            ) {
                val choose: (Int) -> Unit = { selections += it; visible.value = false }
                if (visible.value) {
                    if (explicitSelected) AudioQualitySelectionMenu(options, 0, choose, { visible.value = false }, selectedAudioQuality = 30280)
                    else AudioQualitySelectionMenu(options, 30280, choose, { visible.value = false })
                }
                presenter.active?.second?.content?.invoke(scope)
            }
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun texts(): List<String> = all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        fun selectedTexts(): List<String> = all().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .flatMap { nodes(it) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        suspend fun until(condition: () -> Boolean) {
            withTimeout(10_000) { do { repeat(5) { scene.render().close(); yield() } } while (!condition()) }
        }
        fun close() = scene.close()
    }
}
