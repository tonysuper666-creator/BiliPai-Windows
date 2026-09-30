package com.bilipai.desktop.enhancementfixture

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.feature.anime4k.*
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Run in its own fresh JVM: registry shutdown is deliberately a process-terminal operation. */
class RuntimeProof {
    @Test fun originalRuntimeRegistersOneProviderRejectsLateEnableAndDrainsPendingConfigBeforeClose(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-runtime-enhancement-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        // Real optional casting providers remain disabled, avoiding any LAN scan.
        PluginStore.setEnabled(context, "dlna_cast", false)
        PluginStore.setEnabled(context, "google_cast", false)
        PluginStore.setEnabled(context, Anime4KPlugin.PLUGIN_ID, false)
        val saved = Anime4KConfig(VideoEnhancementAlgorithm.FSR_1_0, Anime4KPreset.QUALITY, .4f, true, true)
        PluginStore.setConfigJson(context, Anime4KPlugin.PLUGIN_ID, encodeVideoEnhancementConfig(saved))
        val runtime = DesktopPluginRuntime(context.store)
        try {
            withTimeout(5_000) { PluginManager.awaitPluginReady(Anime4KPlugin.PLUGIN_ID) }
            waitUntil { runtime.enhancementConfiguration.configState.value == saved }
            assertEquals(1, runtime.plugins.value.count { it.plugin.id == Anime4KPlugin.PLUGIN_ID })
            assertSame(runtime.videoEnhancement, Anime4KPlugin.getInstance())
            assertFalse(runtime.plugins.value.single { it.plugin === runtime.videoEnhancement }.enabled)
            assertFalse(PluginStore.isEnabled(context, Anime4KPlugin.PLUGIN_ID))
            assertEquals(saved, actualDiskConfig(context))

            // Real awaitPluginReady suspension: ownership changes before readiness resumes.
            @Suppress("UNCHECKED_CAST")
            val ready = PluginManager::class.java.getDeclaredField("_readyPluginIds").apply { isAccessible = true }.get(PluginManager) as MutableStateFlow<Set<String>>
            ready.value = ready.value - Anime4KPlugin.PLUGIN_ID
            val owned = AtomicBoolean(true)
            val beforeReady = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.setEnabled(Anime4KPlugin.PLUGIN_ID, true) { owned.get() }
            }
            assertFalse(beforeReady.isCompleted)
            owned.set(false); ready.value = ready.value + Anime4KPlugin.PLUGIN_ID
            withTimeout(3_000) { beforeReady.await() }
            assertFalse(PluginStore.isEnabled(context, Anime4KPlugin.PLUGIN_ID))

            // Real playerMutex suspension: the predicate is checked again after acquisition.
            val mutex = DesktopPluginRuntime::class.java.getDeclaredField("playerMutex").apply { isAccessible = true }.get(runtime) as Mutex
            mutex.lock(); owned.set(true)
            val beforeMutex = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.setEnabled(Anime4KPlugin.PLUGIN_ID, true) { owned.get() }
            }
            assertFalse(beforeMutex.isCompleted)
            owned.set(false); mutex.unlock()
            withTimeout(3_000) { beforeMutex.await() }
            assertFalse(PluginStore.isEnabled(context, Anime4KPlugin.PLUGIN_ID))
            assertFalse(runtime.plugins.value.single { it.plugin === runtime.videoEnhancement }.enabled)

            val gate = GatedDispatcher()
            val io = providerIo(runtime.videoEnhancement, gate)
            val first = runtime.enhancementConfiguration.setAlgorithm(VideoEnhancementAlgorithm.ANIME4K)
            waitUntil { runtime.videoEnhancement.configState.value.algorithm == VideoEnhancementAlgorithm.ANIME4K }
            assertFalse(first.isCompleted)
            val second = runtime.enhancementConfiguration.setPreset(Anime4KPreset.FAST)
            val last = runtime.enhancementConfiguration.setFsrSharpness(.7f)
            val close = async(Dispatchers.Default) { runtime.shutdownForRestore() }
            delay(100)
            assertFalse(close.isCompleted, "Shutdown must join the original setter's held IO child")
            assertFalse(last.isCompleted)
            assertFailsWith<IllegalStateException> { runtime.enhancementConfiguration.setPreset(Anime4KPreset.QUALITY) }
            gate.finish { close.isCompleted }
            close.await(); first.await(); second.await(); last.await()
            assertFalse(io.isActive)
            val expected = saved.copy(algorithm = VideoEnhancementAlgorithm.ANIME4K, preset = Anime4KPreset.FAST, fsrSharpness = .7f)
            assertEquals(expected, actualDiskConfig(context))
            assertEquals(expected, actualDiskConfig(DesktopPluginContext(DesktopPluginStore(root))))
            assertFailsWith<IllegalStateException> { PluginStore.setEnabled(context, Anime4KPlugin.PLUGIN_ID, true) }
        } finally { runtime.shutdownForRestore() }
    }
}
