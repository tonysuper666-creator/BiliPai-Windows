package com.android.purebilibili.feature.video

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.purebilibili.feature.video.ui.components.DanmakuSendDialog
import com.android.purebilibili.feature.video.ui.components.CommentInputDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DanmakuComposerUiRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun commentComposer_restoresDraftAndSelectionWithoutReseedingTheVisibleSession() {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            MaterialTheme {
                CommentInputDialog(
                    visible = true,
                    initialText = "旧评论",
                    onDismiss = {},
                    onSend = { _, _, _ -> },
                )
            }
        }
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("折叠前的评论草稿")
        composeTestRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(2, 4))
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNode(hasSetTextAction())
            .assertTextEquals("折叠前的评论草稿")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 4)))
    }

    @Test
    fun compactComposer_restoresEditedDraftWithoutReplacingItWithInitialText() {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            MaterialTheme {
                DanmakuSendDialog(
                    visible = true,
                    initialText = "旧草稿",
                    onDismiss = {},
                    onSend = { _, _, _, _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithTag("danmaku_compact_input")
            .performTextReplacement("折叠前编辑的草稿")
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithTag("danmaku_compact_input")
            .assertTextEquals("折叠前编辑的草稿")
    }

    @Test
    fun compactComposer_draftFeedbackDoesNotCloseAdvancedSettings() {
        val draft = mutableStateOf("")
        composeTestRule.setContent {
            MaterialTheme {
                DanmakuSendDialog(
                    visible = true,
                    initialText = draft.value,
                    onDraftChange = { text, _ -> draft.value = text },
                    onDismiss = {},
                    onSend = { _, _, _, _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText("颜色、位置与大小").performClick()
        composeTestRule.onNodeWithTag("danmaku_compact_input")
            .performTextReplacement("继续编辑")
        composeTestRule.onNodeWithText("颜色").assertIsDisplayed()
        composeTestRule.onNodeWithTag("danmaku_compact_input").assertTextEquals("继续编辑")
    }

    @Test
    fun compactComposer_focusesInputAndKeepsAdvancedSettingsCollapsed() {
        composeTestRule.setContent {
            MaterialTheme {
                DanmakuSendDialog(
                    visible = true,
                    initialText = "未发送草稿",
                    onDismiss = {},
                    onSend = { _, _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("danmaku_compact_input").assertIsFocused()
        composeTestRule.onNodeWithText("未发送草稿").assertIsDisplayed()
        composeTestRule.onNodeWithText("颜色").assertDoesNotExist()

        composeTestRule.onNodeWithText("颜色、位置与大小").performClick()
        composeTestRule.onNodeWithText("颜色").assertIsDisplayed()
    }
}
