package com.android.purebilibili.feature.video.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.purebilibili.data.model.response.EmoteItem
import com.android.purebilibili.data.model.response.EmotePackage
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentInputDialogUiRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingEmotes_preservesChooserModeAndReturnsToEditingOnInputTap() {
        composeRule.setContent {
            MaterialTheme {
                CommentInputDialog(
                    visible = true,
                    initialText = "@pending",
                    emotePackages = listOf(
                        emotePackage(1, "小黄脸", listOf("[doge]")),
                        emotePackage(53, "热词系列一", listOf("[热词系列_好耶]")),
                        emotePackage(2, "tv_小电视", listOf("[tv_doge]")),
                    ),
                    onDismiss = {},
                    onSend = { _, _, _ -> },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("comment_composer_input") and isFocused())
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("comment_composer_input").assertIsFocused()
        composeRule.onNodeWithContentDescription("表情").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("[doge]").performTouchInput { click() }
        assertChooserMode()

        composeRule.onNodeWithText("热词系列").performClick()
        composeRule.onNodeWithContentDescription("[热词系列_好耶]").performTouchInput { click() }
        assertChooserMode()

        composeRule.onNodeWithText("小电视").performClick()
        composeRule.onNodeWithContentDescription("[tv_doge]").performTouchInput { click() }
        assertChooserMode()
        composeRule.onNodeWithTag("comment_composer_input")
            .assertTextEquals("@pending[doge][热词系列_好耶][tv_doge]")

        composeRule.onNodeWithTag("comment_composer_input").performTouchInput { click() }
        composeRule.onNodeWithTag("comment_composer_input").assertIsFocused()
        composeRule.onNodeWithTag("comment_emote_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("comment_composer_input")
            .performTextInputSelection(TextRange("@pending[doge][热词系列_好耶][tv_doge]".length))
        composeRule.onNodeWithTag("comment_composer_input").performTextInput(" end")
        composeRule.onNodeWithTag("comment_composer_input")
            .assertTextEquals("@pending[doge][热词系列_好耶][tv_doge] end")
    }

    @Test
    fun kaomojis_scrollVerticallyAndInsertAtCursorWithoutLeavingChooser() {
        composeRule.setContent {
            MaterialTheme {
                CommentInputDialog(
                    visible = true,
                    initialText = "AB",
                    emotePackages = listOf(emotePackage(1, "小黄脸", listOf("[doge]"))),
                    onDismiss = {},
                    onSend = { _, _, _ -> },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("comment_composer_input") and isFocused())
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("comment_composer_input")
            .performTextInputSelection(TextRange(1))
        composeRule.onNodeWithContentDescription("表情").performTouchInput { click() }
        composeRule.onNodeWithTag("comment_kaomoji_grid").assertDoesNotExist()
        composeRule.onNodeWithText("颜文字").performTouchInput { click() }
        composeRule.onNodeWithTag("comment_kaomoji_grid")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        composeRule.onNodeWithText("(⌒▽⌒)").performTouchInput { click() }
        assertChooserMode(expectKaomojiGrid = true)
        composeRule.onNodeWithTag("comment_composer_input").assertTextEquals("A(⌒▽⌒)B")

        composeRule.onNodeWithTag("comment_kaomoji_grid")
            .performScrollToNode(hasText("⊙__⊙"))
        composeRule.onNodeWithText("⊙__⊙").assertIsDisplayed().performTouchInput { click() }
        assertChooserMode(expectKaomojiGrid = true)
        composeRule.onNodeWithTag("comment_composer_input")
            .assertTextEquals("A(⌒▽⌒)⊙__⊙B")

        composeRule.onNodeWithText("小黄脸").performTouchInput { click() }
        assertChooserMode()
        composeRule.onNodeWithContentDescription("[doge]").assertIsDisplayed()
    }

    @Test
    fun compactPackages_placeEightEmotesInFirstRowAndNinthInSecondRow() {
        val faces = List(9) { "[face_$it]" }
        val televisions = List(9) { "[tv_$it]" }
        composeRule.setContent {
            MaterialTheme {
                CommentInputDialog(
                    visible = true,
                    emotePackages = listOf(
                        emotePackage(1, "小黄脸", faces),
                        emotePackage(2, "tv_小电视", televisions),
                    ),
                    onDismiss = {},
                    onSend = { _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithContentDescription("表情").performClick()
        assertEightColumnRows(faces)
        composeRule.onNodeWithText("小电视").performClick()
        assertEightColumnRows(televisions)
    }

    @Test
    fun loadedApiKaomojiPackage_replacesLocalCategoryWithoutChangingSelection() {
        val faces = emotePackage(1, "小黄脸", listOf("[doge]"))
        val packages = mutableStateOf(listOf(faces))
        composeRule.setContent {
            MaterialTheme {
                CommentInputDialog(
                    visible = true,
                    emotePackages = packages.value,
                    onDismiss = {},
                    onSend = { _, _, _ -> },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("comment_composer_input") and isFocused())
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithContentDescription("表情").performTouchInput { click() }
        composeRule.onNodeWithText("颜文字").performTouchInput { click() }
        assertChooserMode(expectKaomojiGrid = true)
        composeRule.onNodeWithText("(⌒▽⌒)").assertIsDisplayed()

        composeRule.runOnIdle {
            packages.value = listOf(
                faces,
                emotePackage(4, "颜文字", listOf("(^_^)", "(>_<)")),
            )
        }

        composeRule.onAllNodesWithText("颜文字").assertCountEquals(1)
        assertChooserMode(expectKaomojiGrid = true)
        composeRule.onNodeWithText("(^_^)").assertIsDisplayed()
        composeRule.onNodeWithText("(>_<)").assertIsDisplayed()
        composeRule.onNodeWithText("(⌒▽⌒)").assertDoesNotExist()

        composeRule.onNodeWithText("小黄脸").performTouchInput { click() }
        assertChooserMode()
        composeRule.onNodeWithContentDescription("[doge]").assertIsDisplayed()
    }

    @Test
    fun categories_keepRequestedOrderAndSelectionWhenApiOrderChanges() {
        val faces = emotePackage(1, "小黄脸", listOf("[doge]"))
        val televisions = emotePackage(2, "tv_小电视", listOf("[tv_doge]"))
        val hotWords = emotePackage(53, "热词系列一", listOf("[热词系列_好耶]"))
        val kaomojis = emotePackage(4, "颜文字", listOf("(^_^)"))
        val packages = mutableStateOf(listOf(kaomojis, hotWords, televisions, faces))
        composeRule.setContent {
            MaterialTheme {
                CommentInputDialog(
                    visible = true,
                    emotePackages = packages.value,
                    onDismiss = {},
                    onSend = { _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithContentDescription("表情").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("[doge]").assertIsDisplayed()
        val tabs = listOf("小黄脸", "小电视", "热词系列", "颜文字").map { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }
        assertTrue(tabs.zipWithNext().all { (left, right) -> left.right <= right.left })
        assertTrue(tabs.all { abs(it.top - tabs.first().top) < 1f })

        composeRule.onNodeWithText("热词系列").performTouchInput { click() }
        composeRule.runOnIdle {
            packages.value = listOf(televisions, faces, kaomojis, hotWords)
        }
        composeRule.onNodeWithContentDescription("[热词系列_好耶]").performTouchInput { click() }
        composeRule.onNodeWithTag("comment_composer_input")
            .assertTextEquals("[热词系列_好耶]")
        assertChooserMode()
        composeRule.onNodeWithText("颜文字").performTouchInput { click() }
        composeRule.onNodeWithText("(^_^)").assertIsDisplayed()
        assertChooserMode(expectKaomojiGrid = true)
    }

    private fun assertChooserMode(expectKaomojiGrid: Boolean = false) {
        composeRule.onNodeWithTag("comment_emote_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("comment_composer_input").assertIsNotFocused()
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                hasAnyAncestor(hasTestTag("comment_emote_content")),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        if (expectKaomojiGrid) {
            composeRule.onNodeWithTag("comment_kaomoji_grid").assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("comment_kaomoji_grid").assertDoesNotExist()
        }
    }

    private fun assertEightColumnRows(tokens: List<String>) {
        val bounds = tokens.map { token ->
            composeRule.onNodeWithContentDescription(token).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }
        val firstRow = bounds.take(8)
        assertTrue(firstRow.all { abs(it.top - firstRow.first().top) < 1f })
        assertTrue(firstRow.zipWithNext().all { (left, right) -> left.right <= right.left })
        assertTrue(bounds[8].top >= firstRow.first().bottom)
    }

    private fun emotePackage(id: Long, name: String, tokens: List<String>) = EmotePackage(
        id = id,
        text = name,
        emote = tokens.mapIndexed { index, token ->
            EmoteItem(id = index.toLong(), text = token, url = "")
        },
    )
}
