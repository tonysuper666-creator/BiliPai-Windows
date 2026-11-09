package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.AppAlertDialog
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopProfileFeedbackComponentTest {
    @Test fun avatarFeedbackIsInsideTheActualDialog() = runBlocking {
        val pageMessage = mutableStateOf<String?>(null)
        val feedback = DesktopProfileFeedback { pageMessage.value = it }
        val window = feedback.open()
        val editorVisible = mutableStateOf(true)
        val scene = ImageComposeScene(640, 620, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(), systemLanguageTags = listOf("zh-CN")) {
                Box(Modifier.fillMaxSize()) {
                    pageMessage.value?.let { Text(it) }
                    if (editorVisible.value) {
                        AppAlertDialog(onDismissRequest = {}, title = { Text("编辑资料") }, text = {
                            DesktopProfileFeedbackHost(feedback) { Text("头像") }
                        }, confirmButton = {})
                    }
                }
            }
        }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun ownerWith(value: String) = scene.semanticsOwners.indexOfFirst { owner ->
            nodes(owner.unmergedRootSemanticsNode).any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
            }
        }
        fun nodeWith(value: String) = scene.semanticsOwners.asSequence()
            .flatMap { nodes(it.unmergedRootSemanticsNode).asSequence() }
            .first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true }
        var nanos = 0L
        suspend fun settle() { repeat(8) { nanos += 30_000_000; scene.render(nanos).close(); yield() } }
        try {
            settle()
            val dialogOwner = ownerWith("编辑资料")
            assertTrue(dialogOwner >= 0, "actual dialog must be rendered")
            for (message in listOf("没有选择图片，头像未更改", "不能选择 GIF 头像", "头像上传失败", "头像已提交")) {
                feedback.show(window, message)
                settle()
                assertEquals(dialogOwner, ownerWith(message), "avatar result must be in the actual dialog layer: $message")
                val bounds = nodeWith(message).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0, "the feedback must have visible bounds")
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 640 && bounds.bottom <= 620,
                    "the feedback must remain within the dialog window")
            }
            feedback.close(window)
            editorVisible.value = false
            settle()
            assertEquals(-1, ownerWith("头像已提交"), "closing the editor clears its feedback")
            val reopened = feedback.open()
            editorVisible.value = true
            feedback.show(window, "旧窗口迟到结果")
            settle()
            assertEquals(-1, ownerWith("旧窗口迟到结果"), "a retired window cannot write to a new one")
            feedback.close(window)
            feedback.show(reopened, "新窗口结果")
            settle()
            assertEquals(ownerWith("编辑资料"), ownerWith("新窗口结果"))
            val previousOperation = feedback.begin(reopened)
            val currentOperation = feedback.begin(reopened)
            feedback.route(previousOperation, "旧操作结果")
            settle()
            assertEquals(-1, ownerWith("旧操作结果"), "a later operation owns the window")
            feedback.route(currentOperation, "当前操作结果")
            settle()
            assertEquals(ownerWith("编辑资料"), ownerWith("当前操作结果"))
            feedback.route(null, "昵称修改结果")
            settle()
            assertEquals("昵称修改结果", pageMessage.value, "non-avatar feedback remains on the page")
            feedback.close(reopened)
            feedback.show(reopened, "退场后结果")
            settle()
            assertEquals(-1, ownerWith("退场后结果"), "retired page or account cannot show a late result")
            val nextAccount = DesktopProfileFeedback { pageMessage.value = it }
            val nextWindow = nextAccount.open()
            nextAccount.route(currentOperation, "旧账号结果")
            assertEquals(null, nextAccount.notice, "an old account's operation cannot address the new account")
            nextAccount.show(nextWindow, "新账号结果")
            assertEquals("新账号结果", nextAccount.notice?.message)
        } finally { scene.close() }
    }
}
