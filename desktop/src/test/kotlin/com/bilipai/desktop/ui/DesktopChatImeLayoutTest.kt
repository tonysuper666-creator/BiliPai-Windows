@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.message.ChatInputBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopChatImeLayoutTest {
    @Test
    fun generatedChatInputAndSharedMessageHostFollowImeNavigationResizeClicksAndScroll() = runBlocking {
        val imeBottom = mutableIntStateOf(0)
        val windowWidth = mutableIntStateOf(360)
        val sent = mutableIntStateOf(0)
        val bounds = mutableMapOf<String, Rect>()
        val listStateRef = arrayOfNulls<androidx.compose.foundation.lazy.LazyListState>(1)
        val scene = ImageComposeScene(400, 800, Density(1f)) {
            CompositionLocalProvider(
                LocalDesktopChatImeInsets provides WindowInsets(bottom = imeBottom.intValue),
                LocalAppThemeConfig provides AppThemeConfig(nativeMiuixPopupsEnabled = false),
            ) {
                MaterialTheme {
                    Box(Modifier.width(windowWidth.intValue.dp).height(800.dp)) {
                        Column(Modifier.fillMaxSize().desktopChatContentInsets(PaddingValues(bottom = 24.dp))) {
                            val listState = rememberLazyListState().also { listStateRef[0] = it }
                            LazyColumn(
                                state = listState,
                                reverseLayout = true,
                                modifier = Modifier.weight(1f).fillMaxSize().testTag("messages")
                                    .onGloballyPositioned { bounds["messages"] = it.boundsInRoot() },
                            ) {
                                items((0 until 40).toList(), key = { it }) { item ->
                                    Box(Modifier.fillMaxSize().height(40.dp).testTag("message-$item")
                                        .onGloballyPositioned { if (item == 0) bounds["lastMessage"] = it.boundsInRoot() })
                                }
                            }
                            ChatInputBar(
                                text = TextFieldValue("send"), onTextChange = {}, onSend = { sent.intValue++ }, onPickImage = {},
                                isSending = false, isUploadingImage = false,
                                modifier = Modifier.testTag("composer")
                                    .onGloballyPositioned { bounds["composer"] = it.boundsInRoot() },
                            )
                        }
                    }
                }
            }
        }
        var frame = 0L
        fun settle() { repeat(5) { frame += 16_000_000L; scene.render(frame).close() } }
        suspend fun pumpFor(ms: Long) {
            val deadline = System.nanoTime() + ms * 1_000_000
            do { settle(); delay(12) } while (System.nanoTime() < deadline)
        }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, point)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = frame / 1_000_000 + 1,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = frame / 1_000_000 + 41,
                buttons = PointerButtons())
            settle()
        }
        try {
            settle()
            assertEquals(776f, bounds.getValue("composer").bottom)
            imeBottom.intValue = 200
            settle()
            assertEquals(600f, bounds.getValue("composer").bottom)
            assertEquals(bounds.getValue("composer").top, bounds.getValue("messages").bottom)
            assertTrue(bounds.getValue("lastMessage").bottom <= bounds.getValue("messages").bottom)
            windowWidth.intValue = 320
            settle()
            assertEquals(320f, bounds.getValue("composer").right)
            val composer = bounds.getValue("composer")
            click(Offset(composer.right - 32f, composer.center.y))
            assertEquals(1, sent.intValue)
            repeat(3) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 100f),
                    scrollDelta = Offset(0f, -6f), timeMillis = frame / 1_000_000 + 1)
                pumpFor(180)
            }
            val scrolledState = listStateRef[0]
            assertTrue(scrolledState != null && (scrolledState.firstVisibleItemIndex > 0 || scrolledState.firstVisibleItemScrollOffset > 0))
            imeBottom.intValue = 0
            settle()
            assertEquals(776f, bounds.getValue("composer").bottom)
            imeBottom.intValue = 200
            settle()
            assertEquals(600f, bounds.getValue("composer").bottom)
        } finally { scene.close() }
    }
}
