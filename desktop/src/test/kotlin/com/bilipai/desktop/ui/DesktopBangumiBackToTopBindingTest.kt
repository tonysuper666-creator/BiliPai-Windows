@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.AppLiquidGlassBackToTopButton
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopBangumiBackToTopBindingTest {
    @Test fun realStoreProjectionIsStableAndRetiredCoveredOrCancelledWritesAreRejected() = runBlocking {
        val dir = Files.createTempDirectory("bangumi-back-binding")
        val store = DesktopPluginStore(dir)
        var owned = true
        var active = true
        var permits = 0
        val acts = { owned && active }
        val commit: (() -> Unit) -> Boolean = { action -> if (owned) { permits++; action(); true } else false }
        var revision by mutableStateOf(0)
        lateinit var binding: DesktopBackToTopBindings
        val scene = ImageComposeScene(600, 800, Density(2f)) {
            revision
            binding = rememberBangumiBackToTopBindings(store, acts, commit)
        }
        try {
            scene.render(0).close()
            assertEquals(DesktopFavoriteViewport(300, 400), binding.viewport)
            val first = binding
            revision++
            Snapshot.sendApplyNotifications(); scene.render(20_000_000).close()
            assertSame(first, binding); assertSame(first.setOffset, binding.setOffset)
            assertSame(first.updateOffset, binding.updateOffset)
            binding.setOffset(-12f, -24f)
            assertEquals(1, permits)
            val homeProjection = DesktopFavoritePreferences(store)
            assertEquals(-12f to -24f, homeProjection.backToTopOffset.first())
            val before = Files.readString(dir.resolve("plugin-settings.json"))
            active = false
            assertFailsWith<CancellationException> { binding.setOffset(1f, 2f) }
            active = true; owned = false
            assertFailsWith<CancellationException> { binding.setOffset(5f, 6f) }
            owned = true
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> { withContext(cancelled) { binding.setOffset(3f, 4f) } }
            assertEquals(before, Files.readString(dir.resolve("plugin-settings.json")))
            assertEquals(1, permits)
        } finally { scene.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun realButtonDragPersistsOnceAndPreferenceSwitchHidesIt() = runBlocking {
        val dir = Files.createTempDirectory("bangumi-back-drag")
        val store = DesktopPluginStore(dir)
        val homeProjection = DesktopFavoritePreferences(store)
        var commits = 0
        val acts = { true }
        val commit: (() -> Unit) -> Boolean = { action -> commits++; action(); true }
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(600, 800, Density(1f)) {
            val binding = rememberBangumiBackToTopBindings(store, acts, commit)
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalWindowInfo provides focused, LocalLifecycleOwner provides lifecycle,
                LocalDesktopBackToTopBindings provides binding,
                LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false)) {
                MaterialTheme { Box(Modifier.fillMaxSize()) {
                    val enabled = rememberBackToTopButtonEnabled()
                    AppLiquidGlassBackToTopButton(enabled, {}, Modifier.align(Alignment.BottomEnd).padding(24.dp))
                } }
            }
        }
        var now = 0L
        fun frame() { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun button() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("回到顶部") == true }
        try {
            repeat(40) { frame() }
            val origin = assertNotNull(button()).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, origin, buttons = PointerButtons(isPrimaryPressed = true))
            // Honor the real detector's long-press timeout, not a guessed IO completion delay.
            delay(700)
            frame()
            val target = origin + Offset(-70f, -90f)
            scene.sendPointerEvent(PointerEventType.Move, target, buttons = PointerButtons(isPrimaryPressed = true))
            frame()
            scene.sendPointerEvent(PointerEventType.Release, target, buttons = PointerButtons())
            val saved = withTimeout(5000) { homeProjection.backToTopOffset.first { it != (0f to 0f) } }
            assertEquals(2, commits)
            assertTrue(saved.first < 0f && saved.second < 0f)
            repeat(40) { frame() }; assertEquals(2, commits)
            store.update("settings", mapOf("back_to_top_button_enabled" to JsonPrimitive(false)))
            withTimeout(5000) { while (button() != null) { frame(); yield() } }
            assertFalse(homeProjection.backToTopEnabled.first())
        } finally { scene.close(); dir.toFile().deleteRecursively() }
    }
}
