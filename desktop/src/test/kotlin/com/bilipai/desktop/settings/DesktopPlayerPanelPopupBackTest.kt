@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackCompletedFallback
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.feature.video.ui.components.PlayerPanelPopupBackHandler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlayerPanelPopupBackTest {
    @Test
    fun oneRootBackEventCompletesOnceAndClosesThePopup() {
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val open = mutableStateOf(true)
        var completed = 0
        var predictiveCallbacks = 0
        var fallbacks = 0
        val root = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher(
                onBackCompletedFallback = object : OnBackCompletedFallback {
                    override fun onBackCompletedFallback() { fallbacks++ }
                },
            )
        }
        val input = DirectNavigationEventInput()
        root.navigationEventDispatcher.addInput(input)
        val scene = ImageComposeScene(400, 300,
            coroutineContext = StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, error -> errors += error }) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides root) {
                if (open.value) Popup {
                    PlayerPanelPopupBackHandler(
                        predictive = true,
                        onStarted = { predictiveCallbacks++ },
                        onProgress = { predictiveCallbacks++ },
                        onCancelled = { predictiveCallbacks++ },
                        onCompleted = { completed++; open.value = false },
                    )
                    Box(Modifier.size(100.dp).testTag("player-panel"))
                }
            }
        }
        var time = 0L
        fun settle() = repeat(10) {
            scheduler.runCurrent()
            time += 30_000_000L
            scene.render(time).close()
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), "Composition failed: $errors")
        }
        fun containsPanel(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.TestTag) == "player-panel" ||
                node.children.any(::containsPanel)
        fun panelVisible() = scene.semanticsOwners.any { containsPanel(it.unmergedRootSemanticsNode) }
        try {
            settle()
            assertTrue(panelVisible(), "Real Popup must be mounted before dispatch")
            assertTrue(root.navigationEventDispatcher.isEnabled)
            // Same completed-back input used by DesktopRootWindowNavigationOwner for Escape/back.
            input.backCompleted()
            settle()
            assertFalse(open.value)
            assertFalse(panelVisible())
            assertEquals(1, completed)
            assertEquals(0, predictiveCallbacks)
            assertEquals(0, fallbacks, "Popup must consume the event before Root route-back")
        } finally {
            scene.close()
            root.navigationEventDispatcher.removeInput(input)
            root.navigationEventDispatcher.dispose()
        }
    }
}
