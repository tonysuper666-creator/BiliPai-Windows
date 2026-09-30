package com.bilipai.desktop.guardregression

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.CountDownLatch

/** Actual Runtime/original provider/store. These are two diagnostic cases, not new JUnit counts. */
fun main(args: Array<String>): Unit = runBlocking {
    val kind = args.single()
    require(kind in setOf("manager", "config-read"))
    val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-fsr-guard-regression-")))
    for (id in listOf("dlna_cast", "google_cast", Anime4KPlugin.PLUGIN_ID)) PluginStore.setEnabled(context, id, false)
    val runtime = DesktopPluginRuntime(context.store)
    val bridge = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = MpvPlayer()
    val enabled = MutableStateFlow(false)
    var pendingGuard: (() -> Boolean)? = null
    val entered = CompletableDeferred<Unit>()
    val session = DesktopVideoEnhancementSession(player, runtime.videoEnhancement.configState, enabled,
        DesktopVideoShaderResources(context.filesDir.toPath().resolve("anime4k")) { error("Unready fixture has no GPU frame") },
        context.filesDir.toPath().resolve("fsr"), MutableStateFlow(false), MutableStateFlow(true),
        { error("Use the Root guarded callback") }, { runtime.enhancementConfiguration.rememberCurrentVideoEnabled(it) },
        enablePluginGuarded = { stillOwned -> pendingGuard = stillOwned; entered.complete(Unit); runtime.setEnabled(Anime4KPlugin.PLUGIN_ID, true, stillOwned) })
    val managerMutex = PluginManager::class.java.getDeclaredField("pluginStateMutex").apply { isAccessible = true }.get(PluginManager) as Mutex
    val runtimeMutex = DesktopPluginRuntime::class.java.getDeclaredField("playerMutex").apply { isAccessible = true }.get(runtime) as Mutex
    val releaseStore = CountDownLatch(1)
    var storeHolder: Thread? = null
    var managerHeld = false
    try {
        withTimeout(5_000) { PluginManager.awaitPluginReady(Anime4KPlugin.PLUGIN_ID) }
        runtime.enhancementConfiguration.setPreset(com.android.purebilibili.feature.anime4k.Anime4KPreset.FAST).await()
        bridge.launch { runtime.plugins.collect { values -> enabled.value = values.any { it.plugin === runtime.videoEnhancement && it.enabled } } }
        val owner = player.loadVersioned(PlaybackSource("file:///C:/unmounted-guard-regression.avi"))
        session.bindVideoIdentity("BV-guard-regression", owner)
        val file = context.filesDir.toPath().resolve("plugin-settings.json")
        val beforeBytes = Files.readAllBytes(file)
        val hintBefore = runtime.effectHint.value
        if (kind == "manager") { managerMutex.lock(); managerHeld = true }
        else {
            val backing = DesktopPluginStore::class.java.getDeclaredField("backing").apply { isAccessible = true }.get(context.store)
            val acquired = CountDownLatch(1)
            storeHolder = Thread({ synchronized(backing) { acquired.countDown(); releaseStore.await() } }, "fixture-real-store-backing-owner").apply { isDaemon = true; start() }
            withContext(Dispatchers.IO) { acquired.await() }
        }
        val on = requireNotNull(session.setCurrentVideoEnabled(true))
        withTimeout(5_000) { entered.await(); while (!runtimeMutex.isLocked) delay(2) }
        var originalLoadConfigBlocked = false
        var actualStack = emptyList<String>()
        if (kind == "config-read") {
            withTimeout(5_000) {
                while (!originalLoadConfigBlocked) {
                    val candidate = Thread.getAllStackTraces().entries.firstOrNull { (thread, stack) -> thread.state == Thread.State.BLOCKED &&
                        stack.any { it.className == "com.bilipai.desktop.plugins.DesktopPluginStore" && it.methodName.startsWith("snapshot\$") } &&
                        stack.any { it.className == "com.android.purebilibili.feature.plugin.Anime4KPlugin" && it.methodName.startsWith("loadConfig") } }
                    if (candidate != null) { originalLoadConfigBlocked = true; actualStack = candidate.value.map { it.className + "." + it.methodName } }
                    else delay(2)
                }
            }
        }
        check(!on.isCompleted)
        check(requireNotNull(pendingGuard).invoke())
        check(!runtime.plugins.value.single { it.plugin === runtime.videoEnhancement }.enabled)
        val off = requireNotNull(session.setCurrentVideoEnabled(false))
        withTimeout(5_000) { off.join() }
        val guardRetiredBeforeUnlock = !requireNotNull(pendingGuard).invoke()
        check(guardRetiredBeforeUnlock)
        check(player.ownsSourceVersion(owner))
        if (managerHeld) { managerMutex.unlock(); managerHeld = false } else releaseStore.countDown()
        withTimeout(5_000) { on.join() }
        val persisted = PluginStore.isEnabled(context, Anime4KPlugin.PLUGIN_ID)
        val published = runtime.plugins.value.single { it.plugin === runtime.videoEnhancement }.enabled
        val hintUnchanged = hintBefore == runtime.effectHint.value
        val fileUnchanged = beforeBytes.contentEquals(Files.readAllBytes(file))
        check(!published && !persisted) { "Retired enable must not publish or persist a provider switch" }
        check(hintUnchanged && fileUnchanged) { "Retired enable must leave the original hint and real settings file unchanged" }
        val report = buildJsonObject {
            put("case", kind); put("passed", true); put("originalProvider", runtime.videoEnhancement.javaClass.name)
            put("guardRetiredBeforeUnlock", guardRetiredBeforeUnlock); put("sameNativeOwnerRetained", player.ownsSourceVersion(owner))
            put("originalLoadConfigBlocked", originalLoadConfigBlocked); put("originalBlockedStack", JsonArray(actualStack.map(::JsonPrimitive)))
            put("providerPublishedEnabled", published); put("providerPersistedEnabled", persisted)
            put("originalHintUnchanged", hintUnchanged); put("realStoreFileBytesUnchanged", fileUnchanged)
            put("nativeWindowOpened", false); put("accountHttpRequested", false); put("mainEdited", false)
        }
        Files.writeString(Path.of(System.getProperty("fsr.guard.dir")).resolve(kind + "-result.json"), report.toString())
        println(report)
    } finally {
        if (managerHeld) managerMutex.unlock()
        releaseStore.countDown(); withContext(Dispatchers.IO) { storeHolder?.join(3_000) }
        session.close(); player.close(); bridge.cancel(); runtime.shutdownForRestore()
    }
}
