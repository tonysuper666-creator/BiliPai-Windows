package com.bilipai.desktop.enhancementfixture

import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.feature.anime4k.*
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.nio.file.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** A task-owned dispatcher gates real original-provider IO jobs, not a substitute store. */
class GatedDispatcher : CoroutineDispatcher() {
    private val work = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { work.add(block) }
    fun tick(): Boolean = work.poll()?.let { it.run(); true } ?: false
    suspend fun finish(until: () -> Boolean) = withTimeout(5_000) { while (!until()) { tick(); delay(2) } }
}

fun bindFixtureContext(context: DesktopPluginContext) {
    // Fixture-only injection into the original global manager. The actual provider and
    // original PluginStore retain their implementation, namespace and serialization.
    PluginManager::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(PluginManager, context)
}
fun providerIo(plugin: Anime4KPlugin, dispatcher: CoroutineDispatcher): CoroutineScope {
    val field = Anime4KPlugin::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
    (field.get(plugin) as CoroutineScope).cancel()
    return DesktopPluginScopeRegistry.create("fixture-owned-original-provider-io", dispatcher).also { field.set(plugin, it) }
}
suspend fun actualDiskConfig(context: DesktopPluginContext): Anime4KConfig =
    decodeVideoEnhancementConfig(assertNotNull(PluginStore.getConfigJson(context, Anime4KPlugin.PLUGIN_ID)))
suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(2) }

class ConfigurationProof {
    @Test fun disabledSavedConfigLoadsWithoutEnablingTheOriginalProvider(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-enhancement-disabled-")))
        val expected = Anime4KConfig(VideoEnhancementAlgorithm.FSR_1_0, Anime4KPreset.QUALITY, .3f, true, true)
        PluginStore.setConfigJson(context, Anime4KPlugin.PLUGIN_ID, encodeVideoEnhancementConfig(expected))
        PluginStore.setEnabled(context, Anime4KPlugin.PLUGIN_ID, false)
        bindFixtureContext(context)
        val plugin = Anime4KPlugin()
        val configuration = DesktopVideoEnhancementConfiguration(plugin, {}, dispatcher = Dispatchers.Default)
        try {
            waitUntil { plugin.configState.value == expected }
            assertFalse(PluginStore.isEnabled(context, Anime4KPlugin.PLUGIN_ID))
            assertFalse(PluginManager.plugins.any { it.plugin === plugin && it.enabled })
            assertEquals(expected, actualDiskConfig(context))
        } finally { configuration.flushAndClose() }
    }

    @Test fun nextSetterWaitsForTheActualOriginalIoChildAndDiskKeepsTheLatestFields(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-enhancement-ordered-")))
        bindFixtureContext(context)
        val plugin = Anime4KPlugin()
        val gate = GatedDispatcher()
        val io = providerIo(plugin, gate)
        val configuration = DesktopVideoEnhancementConfiguration(plugin, {}, dispatcher = Dispatchers.Default)
        try {
            val first = configuration.setPreset(Anime4KPreset.QUALITY)
            waitUntil { plugin.configState.value.preset == Anime4KPreset.QUALITY }
            assertFalse(first.isCompleted)
            assertTrue(io.coroutineContext[Job]!!.children.any(), "Original setter must have launched a real IO child")
            val second = configuration.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0)
            delay(100)
            assertEquals(VideoEnhancementAlgorithm.ANIME4K, plugin.configState.value.algorithm)
            assertFalse(second.isCompleted)
            assertNull(PluginStore.getConfigJson(context, Anime4KPlugin.PLUGIN_ID))
            gate.finish { first.isCompleted }
            first.await()
            waitUntil { plugin.configState.value.algorithm == VideoEnhancementAlgorithm.FSR_1_0 }
            assertFalse(second.isCompleted)
            assertEquals(Anime4KConfig(preset = Anime4KPreset.QUALITY), actualDiskConfig(context))
            gate.finish { second.isCompleted }
            second.await()
            assertEquals(plugin.configState.value, actualDiskConfig(context))
            assertEquals(Anime4KPreset.QUALITY, actualDiskConfig(context).preset)
        } finally { gate.finish { io.coroutineContext[Job]!!.children.none() }; configuration.flushAndClose(); io.cancel() }
    }

    @Test fun failedActualAtomicDiskWriteIsReportedAndAFollowingChangedSetterRecovers(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-enhancement-failure-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        bindFixtureContext(context)
        val plugin = Anime4KPlugin()
        val configuration = DesktopVideoEnhancementConfiguration(plugin, {}, dispatcher = Dispatchers.Default)
        try {
            // Force the real atomic file move to fail; neither store nor provider is mocked.
            Files.createDirectory(root.resolve("plugin-settings.json"))
            val failed = configuration.setPreset(Anime4KPreset.QUALITY)
            assertFailsWith<IllegalStateException> { failed.await() }
            assertNotNull(configuration.error.value)
            assertTrue(Files.isDirectory(root.resolve("plugin-settings.json")))
            Files.delete(root.resolve("plugin-settings.json"))
            configuration.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0).await()
            assertNull(configuration.error.value)
            val expected = Anime4KConfig(VideoEnhancementAlgorithm.FSR_1_0, Anime4KPreset.QUALITY)
            assertEquals(expected, actualDiskConfig(context))
            assertEquals(expected, decodeVideoEnhancementConfig(
                kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json")))
                    .let { (it as kotlinx.serialization.json.JsonObject)["plugin_prefs"] as kotlinx.serialization.json.JsonObject }
                    .let { (it["plugin_config_anime4k"] as kotlinx.serialization.json.JsonPrimitive).content }))
        } finally { configuration.flushAndClose() }
    }

    @Test fun shutdownDrainsAlreadyAcceptedPendingSettersBeforeFreezingTheRealStore(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-enhancement-close-")))
        bindFixtureContext(context)
        val plugin = Anime4KPlugin()
        val gate = GatedDispatcher()
        val io = providerIo(plugin, gate)
        var accepting = true
        val configuration = DesktopVideoEnhancementConfiguration(plugin, {}, dispatcher = Dispatchers.Default,
            acceptChanges = { accepting })
        val first = configuration.setPreset(Anime4KPreset.QUALITY)
        waitUntil { plugin.configState.value.preset == Anime4KPreset.QUALITY }
        val second = configuration.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0)
        val third = configuration.setFsrSharpness(.21f)
        val fourth = configuration.setRememberAcrossVideos(true, true)
        accepting = false
        val shutdown = async(Dispatchers.Default) { configuration.flushAndClose(); context.store.freezeWrites() }
        try {
            assertFailsWith<IllegalStateException> { configuration.setPreset(Anime4KPreset.FAST) }
            delay(100)
            assertFalse(shutdown.isCompleted)
            assertFalse(fourth.isCompleted)
            gate.finish { shutdown.isCompleted }
            shutdown.await(); first.await(); second.await(); third.await(); fourth.await()
            val expected = Anime4KConfig(VideoEnhancementAlgorithm.FSR_1_0, Anime4KPreset.QUALITY, .2f, true, true)
            assertEquals(expected, actualDiskConfig(context))
            assertEquals(expected, actualDiskConfig(DesktopPluginContext(DesktopPluginStore(context.filesDir.toPath()))))
            assertFailsWith<IllegalStateException> { PluginStore.setEnabled(context, Anime4KPlugin.PLUGIN_ID, true) }
        } finally { io.cancel() }
    }
}
