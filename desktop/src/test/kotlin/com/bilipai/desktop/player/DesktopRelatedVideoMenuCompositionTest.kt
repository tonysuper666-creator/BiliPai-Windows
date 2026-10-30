package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.RelatedVideoActionSheet
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopRelatedVideoMenuCompositionTest {
    @Test fun watchLaterWaitsForHostDismissalAndUsesRealWindowHeight() = runBlocking {
        val host = Host()
        try {
            host.ready()
            val request = host.presenter.active!!.second
            assertEquals("视频操作", request.title)
            assertEquals(AppGlassMenuPlacement.CENTER, request.placement)
            assertEquals(765.dp, request.maxHeight)
            assertEquals(420.dp, request.maxWidth)
            assertTrue(request.interactiveContent)
            host.click("添加至稍后再看")
            assertEquals(emptyList(), host.events)
            assertTrue(host.pendingDismiss)
            host.finishDismissal()
            assertEquals(listOf("dismiss", "watch"), host.events)
        } finally { host.close() }
    }

    @Test fun feedbackKeepsExactReasonAndCancelDoesNotSubmitAnAction() = runBlocking {
        for (label in listOf("恐怖血腥", "取消")) {
            val host = Host()
            try {
                host.ready()
                host.click(label)
                assertEquals(emptyList(), host.reasons)
                assertEquals(emptyList(), host.events)
                host.finishDismissal()
                if (label == "取消") {
                    assertEquals(listOf("dismiss"), host.events)
                    assertTrue(host.reasons.isEmpty())
                } else {
                    assertEquals(listOf("dismiss", "reason"), host.events)
                    assertEquals(RecommendationFeedbackReason(name = label, toast = "将优化此类推荐",
                        type = RecommendationFeedbackType.FEEDBACK,
                        localAction = RecommendationFeedbackLocalAction.VIDEO_ONLY), host.reasons.single())
                }
            } finally { host.close() }
        }
    }

    // Records the host boundary; real AppGlassMenu builds and composes its interactive content.
    private class Presenter(override val ownerView: Any) : AppGlassMenuPresenter {
        var active by mutableStateOf<Pair<Any, AppGlassMenuRequest>?>(null)
        override fun show(owner: Any, request: AppGlassMenuRequest) { active = owner to request }
        override fun dismiss(owner: Any) { if (active?.first === owner) active = null }
    }

    private class Host {
        val events = mutableListOf<String>()
        val reasons = mutableListOf<RecommendationFeedbackReason>()
        var pending: (() -> Unit)? = null
        var pendingDismiss = false
        private val visible = mutableStateOf(true)
        lateinit var presenter: Presenter
        private val menuScope = AppGlassMenuScope({ null }, { _, _, _, _, _ -> }, {},
            { action, dismiss -> check(pending == null); pending = action; pendingDismiss = dismiss })
        private val scene = ImageComposeScene(640, 900, Density(1f)) {
            val window = LocalWindowInfo.current
            presenter = remember(window) { Presenter(window) }
            MaterialTheme {
                CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = true),
                    LocalAppGlassMenuPresenter provides presenter) {
                    if (visible.value) RelatedVideoActionSheet(RelatedVideo(bvid = "BV1fixture"),
                        onWatchLater = { events += "watch" },
                        onReasonSelected = { reasons += it; events += "reason" },
                        onDismissRequest = { events += "dismiss"; visible.value = false })
                    presenter.active?.second?.content?.invoke(menuScope)
                }
            }
        }
        suspend fun frames() { repeat(5) { scene.render().close(); yield() } }
        suspend fun ready() { withTimeout(10_000) { while (presenterOrNull() == null) frames() }; frames() }
        private fun presenterOrNull() = if (::presenter.isInitialized) presenter.active else null
        private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun click(label: String) {
            var node = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = checkNotNull(node.parent)
            val point = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
            scene.render().close()
        }
        suspend fun finishDismissal() {
            val action = checkNotNull(pending)
            presenter.active!!.second.onDismissRequest()
            withTimeout(10_000) { while (visible.value) frames() }
            action()
            pending = null
        }
        fun close() = scene.close()
    }
}
