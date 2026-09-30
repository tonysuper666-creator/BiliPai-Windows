package com.bilipai.desktop.data

import com.android.purebilibili.core.store.TodayWatchDislikedVideoSnapshot
import com.bilipai.desktop.backup.DesktopBackupArchive
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun main(args: Array<String>) {
    val directory = Path.of(args[0]).toAbsolutePath().normalize()
    check(directory == Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
    val root = directory.resolve("old")
    val incomingRoot = directory.resolve("incoming")
    val old = DesktopDiscoveryPreferences(root)
    old.record(11, TodayWatchDislikedVideoSnapshot("before", "before", "fixture", 1, 1), emptySet(), false)
    old.feedback(22)
    val incoming = DesktopDiscoveryPreferences(incomingRoot)
    incoming.record(11, TodayWatchDislikedVideoSnapshot("restored", "restored", "fixture", 2, 2), emptySet(), false)
    val archive = DesktopBackupArchive(incomingRoot).create(123)
    val lock = DesktopDiscoveryPreferences::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(old)
    val started = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val queued = Thread({
        started.countDown()
        try {
            old.record(11, TodayWatchDislikedVideoSnapshot("obsolete", "obsolete", "fixture", 3, 3), emptySet(), false)
        } catch (caught: Throwable) { failure.set(caught) }
    }, "owned-old-discovery-write")
    val target = root.resolve("accounts/11/discovery/plugin-settings.json")
    lateinit var restoredBytes: ByteArray
    synchronized(lock) {
        queued.start()
        check(started.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (queued.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        check(queued.state == Thread.State.BLOCKED)
        DesktopBackupArchive(root) {
            // The current Runtime only freezes the active recommendation context.
            old.recommendationContext(22).store.freezeWrites()
            DesktopPluginStore(root).freezeWrites()
        }.restore(archive)
        restoredBytes = Files.readAllBytes(target)
        check(String(restoredBytes).contains("restored"))
    }
    queued.join(3_000)
    check(!queued.isAlive && failure.get() == null)
    check(!restoredBytes.contentEquals(Files.readAllBytes(target)))
    val fresh = DesktopDiscoveryPreferences(root)
    check(fresh.feedback(11).value.dislikedBvids == setOf("before", "obsolete"))
    Files.writeString(directory.resolve("result.json"), """{"baselineBugReproduced":true,"actualArchiveRestoredBeforeRelease":true,"oldAccountWriterObservedBlocked":true,"onlyCurrentRecommendationContextFrozen":true,"restoredFeedbackOverwrittenByRetiredAccount":true,"networkRequests":false,"userSettingsRead":false}""")
    println("Baseline bug reproduced: a blocked old-account writer overwrote actually restored feedback.")
}
