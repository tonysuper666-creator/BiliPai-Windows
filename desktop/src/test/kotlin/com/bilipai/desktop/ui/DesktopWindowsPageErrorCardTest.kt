@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.android.purebilibili.data.model.VideoLoadError
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsPageErrorCardTest {
    @Test fun transportAndApiDetailsNeverBecomeVisibleCopy() {
        val raw = "java.lang.IllegalStateException: secret fixture detail\n at example.Player.start(Player.kt:42)"
        for (error in listOf(VideoLoadError.UnknownError(IllegalStateException(raw)),
            VideoLoadError.ApiError(-500, raw), null)) {
            val message = desktopWindowsVideoErrorMessage(error)
            assertEquals("请稍后重试，或返回选择其他视频。", message)
            assertFalse(message.contains(raw))
        }
        assertEquals("请检查网络连接后重试。", desktopWindowsVideoErrorMessage(VideoLoadError.NetworkError))
        assertEquals("连接超时，请稍后重试。", desktopWindowsVideoErrorMessage(VideoLoadError.Timeout))
        assertTrue(desktopWindowsVideoErrorMessage(VideoLoadError.VideoNotFound).contains("已被删除"))
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun text(node: SemanticsNode): List<String> = flatten(node).flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text }
    }

    @Test fun theErrorFitsTheStageAndRetryReturnsToPageContent() {
        for (width in listOf(280, 800)) {
            val error = mutableStateOf(true)
            var retries = 0
            val scene = ImageComposeScene(width, 400) {
                MaterialTheme {
                    if (error.value) DesktopWindowsPageErrorOverlay { DesktopWindowsPageErrorCard("请检查网络连接后重试。", onRetry = {
                        retries++
                        error.value = false
                    }) } else Text("视频内容")
                }
            }
            try {
                scene.render(0).close()
                val nodes = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                val card = nodes.single { it.config.getOrNull(SemanticsProperties.LiveRegion) != null }
                assertEquals(minOf(360, width).toFloat(), card.boundsInRoot.width)
                assertEquals(width / 2f, card.boundsInRoot.center.x, .5f)
                assertEquals(200f, card.boundsInRoot.center.y, .5f)
                assertTrue(nodes.any { "暂时无法播放" in text(it) })
                val retry = nodes.single { it.config.getOrNull(SemanticsActions.OnClick) != null && "重试" in text(it) }
                assertTrue(retry.config[SemanticsActions.OnClick].action!!.invoke())
                scene.render(30_000_000).close()
                val after = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                assertEquals(1, retries)
                assertTrue(after.any { "视频内容" in text(it) })
                assertFalse(after.any { "暂时无法播放" in text(it) })
            } finally { scene.close() }
        }
    }
}
