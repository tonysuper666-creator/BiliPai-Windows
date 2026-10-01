package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.controller.PlaybackProgressManager
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual temp disk and the installed Store. Blocking its backing monitor proves
 * immediate original Manager reads do not wait for the queued atomic disk write. */
fun main() = runBlocking {
    var assertions = 0
    fun expect(value: Boolean) { check(value); assertions++ }
    val root = Files.createTempDirectory("bilipai-original-progress-proof-")
    val store = DesktopPluginStore(root)
    store.update("settings", mapOf("keep" to JsonPrimitive("unrelated")))
    val errors = mutableListOf<String>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val writer = DesktopOriginalProgressWriter(store, scope, errors::add)
    val prefs = DesktopOriginalProgressPreferences(DesktopPluginContext(store), writer)
    val manager = PlaybackProgressManager().also { it.init(prefs) }
    val backing = DesktopPluginStore::class.java.getDeclaredField("backing").apply { isAccessible = true }.get(store)
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val blockerFailure = AtomicReference<Throwable?>()
    val blocker = Thread {
        try { synchronized(backing) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        catch (error: Throwable) { blockerFailure.set(error) }
    }
    blocker.start(); check(entered.await(2, TimeUnit.SECONDS))
    try {
        manager.savePosition("BV-exact", 701L, 6_123L, 100_000L)
        expect(manager.getCachedPosition("BV-exact", 701L) == 6_123L)
        expect(manager.getCachedPosition("BV-exact") == 6_123L)
        expect(manager.getCachedPosition("BV-exact", 702L) == 0L)
        manager.savePosition("BV-too-short", cid = 1L, positionMs = 4_999L)
        expect(manager.getCachedPosition("BV-too-short", 1L) == 0L)
        manager.savePosition("BV-complete", 99L, 95_000L, 100_000L)
        expect(manager.getCachedPosition("BV-complete", 99L) == 95_000L)
        manager.savePosition("BV-complete", 99L, 95_001L, 100_000L)
        expect(manager.getCachedPosition("BV-complete", 99L) == 0L)
        expect(manager.getCachedPosition("BV-complete") == 0L)
    } finally { release.countDown(); blocker.join(2_000L) }
    blockerFailure.get()?.let { throw it }
    expect(writer.closeAndJoin())
    expect(errors.isEmpty())
    expect(store.preferences("settings")["keep"]?.jsonPrimitive?.content == "unrelated")
    val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
    expect(disk["video_progress"]!!.jsonObject["BV-exact#701"]?.jsonPrimitive?.long == 6_123L)
    expect("BV-complete#99" !in disk["video_progress"]!!.jsonObject)
    expect(runCatching { prefs.edit().putLong("late", 1L).apply() }.isFailure)
    scope.cancel()

    val boundedRoot = Files.createTempDirectory("bilipai-original-progress-bound-proof-")
    val boundedStore = DesktopPluginStore(boundedRoot)
    boundedStore.update("video_progress", (0 until 4_100).associate { "old-$it" to JsonPrimitive(6_000L + it) } +
        mapOf("not-long" to JsonPrimitive("6000"), "negative" to JsonPrimitive(-1L)))
    val boundedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val boundedWriter = DesktopOriginalProgressWriter(boundedStore, boundedScope, errors::add)
    val boundedPrefs = DesktopOriginalProgressPreferences(DesktopPluginContext(boundedStore), boundedWriter)
    val boundedManager = PlaybackProgressManager().also { it.init(boundedPrefs) }
    expect(boundedManager.getCacheSize() == 4_100)
    expect(boundedManager.getCachedPosition("not-long") == 0L)
    boundedManager.savePosition("BV-new", cid = 888L, positionMs = 12_345L)
    expect(boundedManager.getCacheSize() == 4_096)
    expect(boundedManager.getCachedPosition("old-0") == 0L)
    expect(boundedManager.getCachedPosition("old-5") == 0L)
    expect(boundedManager.getCachedPosition("old-6") == 6_006L)
    expect(boundedWriter.closeAndJoin())
    val actual = boundedStore.preferences("video_progress")
    expect(actual.keys.count { it.startsWith("old-") || it.startsWith("BV-new") } == 4_096)
    expect(actual["BV-new#888"]?.jsonPrimitive?.long == 12_345L)
    boundedScope.cancel()
    println("ProgressStorageProof PASS $assertions assertions; actual temp Store, queued disk, immediate original memory, shutdown drain; no HTTP/window/account")
    println("Progress manager: ${PlaybackProgressManager::class.java.protectionDomain.codeSource.location}")
    println("Actual Store: ${DesktopPluginStore::class.java.protectionDomain.codeSource.location}")
    println("Task temp roots: $root ; $boundedRoot")
}
