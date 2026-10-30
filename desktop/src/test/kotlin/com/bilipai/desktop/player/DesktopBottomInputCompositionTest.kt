package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.AppNavigationSettings
import com.android.purebilibili.feature.video.ui.components.BottomInputBar
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBottomInputCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    private fun verify(width: Int) {
        val directory = Files.createTempDirectory("bottom-input-")
        val home = MutableStateFlow(HomeSettings(androidNativeLiquidGlassEnabled = false, isBottomBarBlurEnabled = false))
        val navigation = MutableStateFlow(AppNavigationSettings())
        val port = Proxy.newProxyInstance(DesktopHomeSettingsPort::class.java.classLoader,
            arrayOf(DesktopHomeSettingsPort::class.java)) { _, method, _ ->
            when (method.name) {
                "getHomeSettings" -> home
                "getNavigation" -> navigation
                else -> error("Unexpected settings access: ${method.name}")
            }
        } as DesktopHomeSettingsPort
        val services = DesktopOriginalHomeSettingsRootServices(port,
            DesktopFavoritePreferences(DesktopPluginStore(directory)),
            { error("Unexpected notice") }, { _, _, _, _ -> error("Unexpected profile creation") })
        var comments = 0
        val scene = ImageComposeScene(width, 180, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopOriginalHomeSettingsRootServices provides services) {
                    BottomInputBar(isLiked = false, likeStatusKnown = true, isFavorited = false, isCoined = false,
                        onLikeClick = { error("like") }, onFavoriteClick = { error("favorite") },
                        onCoinClick = { error("coin") }, onShareClick = { error("share") },
                        onCommentClick = { comments++ }, showActionButtons = false)
                }
            }
        }
        try {
            scene.render().close()
            assertEquals(0, comments)
            val all = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            var target = all.single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "发评论" } == true }
            assertTrue(target.boundsInRoot.left >= 0 && target.boundsInRoot.right <= width)
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = checkNotNull(target.parent)
            repeat(2) {
                scene.sendPointerEvent(PointerEventType.Press, target.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, target.boundsInRoot.center, buttons = PointerButtons())
                scene.render().close()
                assertEquals(it + 1, comments)
            }
        } finally { scene.close(); directory.toFile().deleteRecursively() }
    }
    @Test fun narrowCommentEntryDispatchesOnlyTheCommentAction() = verify(240)
    @Test fun wideCommentEntryDispatchesOnlyTheCommentAction() = verify(800)
}
