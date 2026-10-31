package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.Owner
import com.android.purebilibili.feature.video.ui.components.UpPreviewSheet
import com.bilipai.desktop.ui.DesktopOriginalPortraitPlatform
import com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopUpPreviewCompositionTest {
    @Test fun narrowWindowKeepsCreatorActionsInsideTheSheet() = runBlocking { verifyHeader(320) }
    @Test fun wideWindowKeepsCreatorActionsInsideTheSheet() = runBlocking { verifyHeader(1280) }

    private suspend fun verifyHeader(width: Int) {
        // A non-fetching owner exercises the real UI; any unexpected platform/network access fails.
        val platform = Proxy.newProxyInstance(DesktopOriginalPortraitPlatform::class.java.classLoader,
            arrayOf(DesktopOriginalPortraitPlatform::class.java)) { _, method, _ ->
            error("Unexpected platform access: ${method.name}")
        } as DesktopOriginalPortraitPlatform
        val scene = ImageComposeScene(width, 800, Density(1f)) {
            CompositionLocalProvider(LocalDesktopOriginalPortraitPlatform provides platform) {
                MaterialTheme {
                    UpPreviewSheet(visible = true,
                        owner = Owner(mid = 0L, name = "很长的创作者名称用于窄窗口布局验证", face = ""),
                        isFollowing = false, followStatusKnown = true,
                        onDismiss = {}, onFollowClick = {}, onEnterSpace = {}, onVideoClick = { _, _ -> })
                }
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        try {
            repeat(35) { scene.render().close(); delay(12) }
            val nodes = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            for (label in listOf("+ 关注", "进入空间  >")) {
                val node = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
                val bounds = node.boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width,
                    "$label outside $width px viewport: $bounds")
            }
        } finally { scene.close() }
    }
}
