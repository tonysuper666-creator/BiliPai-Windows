package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.ViewPoint
import com.android.purebilibili.feature.video.ui.components.ChapterListPanel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopChapterMenuCompositionTest {
    @Test fun currentChapterUpdatesAndSelectionSeeksBeforeDismiss() = runBlocking {
        val h = Host()
        try {
            h.until { h.actions.size == 2 && h.texts().any { "正在播放" in it } }
            assertEquals("视频章节", h.presenter.active!!.second.title)
            assertEquals(AppGlassMenuPlacement.START, h.presenter.active!!.second.placement)
            assertTrue(h.selectedText().contains("第一章"))
            h.position.longValue = 35_000
            h.until { h.selectedText().contains("第二章") }
            h.actions.values.first().invoke()
            h.until { !h.visible.value && h.actions.isEmpty() }
            assertEquals(listOf("seek:10000", "dismiss"), h.events)
        } finally { h.close() }
    }

    @Test fun dismissDoesNotSeekAndCurrentChapterCanBeSelectedAgain() = runBlocking {
        val h = Host()
        try {
            h.until { h.actions.size == 2 }
            h.presenter.active!!.second.onDismissRequest()
            h.until { !h.visible.value }
            assertEquals(listOf("dismiss"), h.events)
            h.visible.value = true
            h.until { h.actions.size == 2 }
            h.actions.values.first().invoke()
            h.until { h.events.size == 3 }
            assertEquals(listOf("dismiss", "seek:10000", "dismiss"), h.events)
        } finally { h.close() }
    }

    private class Presenter(override val ownerView: Any) : AppGlassMenuPresenter {
        var active by mutableStateOf<Pair<Any, AppGlassMenuRequest>?>(null)
        override fun show(owner: Any, request: AppGlassMenuRequest) { active = owner to request }
        override fun dismiss(owner: Any) { if (active?.first === owner) active = null }
    }
    private class Host {
        val position = mutableLongStateOf(15_000)
        val visible = mutableStateOf(true)
        val events = mutableListOf<String>()
        val actions = linkedMapOf<Any, () -> Unit>()
        lateinit var presenter: Presenter
        private val scope = AppGlassMenuScope(pressedItem = { null },
            register = { key, _, enabled, _, action -> if (enabled) actions[key] = action else actions.remove(key) },
            unregister = { actions.remove(it) }, dispatch = { action, _ -> action() })
        private val scene = ImageComposeScene(640, 300, Density(1f)) {
            val window = LocalWindowInfo.current
            presenter = remember(window) { Presenter(window) }
            CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true),
                LocalAppGlassMenuPresenter provides presenter) {
                if (visible.value) ChapterListPanel(
                    listOf(ViewPoint(from = 10, to = 30, content = "第一章"), ViewPoint(from = 30, to = 60, content = "第二章")),
                    position.longValue, onSeek = { events += "seek:$it" },
                    onDismiss = { events += "dismiss"; visible.value = false })
                presenter.active?.second?.content?.invoke(scope)
            }
        }
        private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun texts() = all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        fun selectedText(): String = all().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .flatMap { nodes(it) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
        suspend fun until(condition: () -> Boolean) { withTimeout(10_000) { do { scene.render().close(); yield() } while (!condition()) } }
        fun close() = scene.close()
    }
}
