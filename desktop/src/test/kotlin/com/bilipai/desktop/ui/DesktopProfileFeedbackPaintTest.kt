package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.purebilibili.core.ui.AppAlertDialog
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopProfileFeedbackPaintTest {
    @Test fun cancellationIsPaintedAboveEditorContent() = assertPaintedAboveEditor("没有选择图片，头像未更改", upload = false)

    @Test fun uploadFailureIsPaintedAboveEditorContent() = assertPaintedAboveEditor("头像上传失败", upload = true)

    @Test fun uploadSuccessIsPaintedAboveEditorContent() = assertPaintedAboveEditor("头像已提交", upload = true)

    private fun assertPaintedAboveEditor(message: String, upload: Boolean) = runBlocking {
        val feedback = DesktopProfileFeedback { error("Editor feedback escaped to the page: $it") }
        val window = feedback.open()
        val origin = if (upload) feedback.begin(window) else window
        var background = Color.Unspecified
        val scene = ImageComposeScene(640, 620, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(), systemLanguageTags = listOf("zh-CN")) {
                background = MaterialTheme.colorScheme.inverseSurface
                AppAlertDialog(onDismissRequest = {}, title = { Text("编辑资料") }, text = {
                    DesktopProfileFeedbackHost(feedback) {
                        // Editor content may have its own drawing order. It must never
                        // compete with feedback in the host's stacking context.
                        Box(Modifier.fillMaxWidth().height(180.dp).zIndex(1f).background(Color.Magenta)) {
                            Text("头像编辑区域")
                        }
                    }
                }, confirmButton = {})
            }
        }
        var nanos = 0L
        suspend fun settle() { repeat(8) { nanos += 30_000_000; scene.render(nanos).close(); yield() } }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun ownerWith(text: String) = scene.semanticsOwners.single { owner ->
            nodes(owner.unmergedRootSemanticsNode).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }
        }
        try {
            settle()
            feedback.route(origin, message)
            settle()
            val owner = ownerWith("编辑资料")
            assertEquals(owner, ownerWith(message), "feedback must stay in the editor's actual dialog window")
            val bounds = nodes(owner.unmergedRootSemanticsNode).single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == message } == true
            }.boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0)
            // Sample a vertical strip of the banner's inner padding, away from glyphs,
            // rounded corners and shadows. Semantics alone cannot detect occlusion.
            scene.render(nanos + 1).use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocN32Pixels(image.width, image.height) && image.readPixels(bitmap))
                    val x = bounds.left.toInt() - 4
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                        assertEquals(background.toArgb(), bitmap.getColor(x, y),
                            "$message is obscured by editor content at ($x, $y)")
                    }
                }
            }
        } finally { scene.close() }
    }
}
