package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.feature.video.screen.TabletDanmakuChromeState
import com.android.purebilibili.feature.video.screen.rememberTabletDanmakuChromeState
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopTabletDanmakuCompositionTest {
    @Test fun routeChangesUseTheCurrentSharedManagerAndPersistTheSamePortraitSetting() = runBlocking {
        val directory = Files.createTempDirectory("tablet-danmaku-composition")
        val store = DesktopPluginStore(directory)
        val admission: ((() -> Unit) -> Boolean) = { it(); true }
        val preferences = DesktopOriginalDanmakuPreferences(store, DesktopDanmakuBlockPreferences(store, admission), admission)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, admission)
        val calls = mutableListOf<String>()
        fun manager(name: String) = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(DesktopOriginalSectionDanmakuPort::class.java)) { proxy, method, args ->
            when (method.name) {
                "setEnabled" -> { calls += "$name:enabled:${args!![0]}"; null }
                "clear" -> { calls += "$name:clear"; null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> error("Unexpected manager call: ${method.name}")
            }
        } as DesktopOriginalSectionDanmakuPort
        val first = manager("first")
        val second = manager("second")
        val current = mutableStateOf(first)
        val route = mutableStateOf("BV-first")
        val platform = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(DesktopOriginalVideoSectionPlatform::class.java)) { _, method, _ ->
            when (method.name) {
                "getDanmaku" -> current.value
                "getDanmakuPreferences" -> preferences
                "getSettingsContext" -> context
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as DesktopOriginalVideoSectionPlatform
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        lateinit var state: TabletDanmakuChromeState
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(80, 80, Density(1f), coroutineContext = scope.coroutineContext) {
            CompositionLocalProvider(LocalDesktopOriginalVideoSectionPlatform provides platform, LocalLifecycleOwner provides owner) {
                state = rememberTabletDanmakuChromeState(route.value)
            }
        }
        fun render() {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), errors.toString())
        }
        suspend fun awaitEnabled(enabled: Boolean) {
            withTimeout(10_000) {
                while (true) {
                    render()
                    if (preferences.currentSettings(DanmakuSettingsScope.PORTRAIT).enabled == enabled && state.enabled == enabled) break
                    delay(10)
                }
            }
        }
        try {
            render()
            assertTrue(state.enabled)
            state.onToggle()
            awaitEnabled(false)
            assertEquals(listOf("first:enabled:false", "first:clear"), calls)
            route.value = "BV-next"
            current.value = second
            render()
            state.onToggle()
            awaitEnabled(true)
            assertEquals(listOf("first:enabled:false", "first:clear", "second:enabled:true"), calls)
        } finally {
            scene.close()
            scope.cancel()
            scheduler.runCurrent()
            directory.toFile().deleteRecursively()
        }
    }
}
