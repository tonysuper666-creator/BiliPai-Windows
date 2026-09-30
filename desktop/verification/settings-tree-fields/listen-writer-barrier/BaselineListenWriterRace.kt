package com.bilipai.desktop.audio

import com.bilipai.desktop.backup.DesktopBackupArchive
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Runs the captured unmodified production Session/Store, not an emulation of its locking. */
fun main(args: Array<String>) {
    var exit = 1
    try { runBlocking {
        val root = Files.createTempDirectory("bp-l-old-")
        Files.writeString(root.resolve("fixture-owner.json"), "{\"owner\":\"listen-baseline-race\"}")
        val restoreRoot = Files.createTempDirectory("bp-l-bak-")
        val file = root.resolve("listen-state.json")
        fun state(id: String) = ListenAudioSaved(listOf(PlaylistItem(id, 501, id, "", "")), 0)
        val final = state("final"); val obsolete = state("obsolete"); val restored = state("restored")
        val store = ListenAudioStore(file); store.save(final)
        ListenAudioStore(restoreRoot.resolve("listen-state.json")).save(restored)
        val zip = DesktopBackupArchive(restoreRoot).create(1_700_000_000_000L)
        val repository = DesktopRepository(DesktopSessionStore.temporary()); val player = MpvPlayer()
        lateinit var session: ListenAudioSession
        SwingUtilities.invokeAndWait { session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player, store = store) }
        val entered = CountDownLatch(1); val thread = AtomicReference<Thread>(); val committed = AtomicBoolean()
        lateinit var queued: Job
        try {
            SwingUtilities.invokeAndWait {
                synchronized(store) {
                    val scope = ListenAudioSession::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(session) as CoroutineScope
                    queued = scope.launch(Dispatchers.IO) {
                        thread.set(Thread.currentThread()); entered.countDown()
                        store.save(obsolete); committed.set(true)
                    }
                    check(entered.await(3, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (thread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
                    check(thread.get().state == Thread.State.BLOCKED)
                    session.close()
                    check(store.read() == final)
                    DesktopBackupArchive(root).restore(zip)
                    check(store.read() == restored)
                }
            }
            withTimeout(3_000) { queued.join() }
            check(queued.isCancelled); check(committed.get()); check(store.read() == obsolete)
            val report = buildJsonObject {
                put("baselineRaceReproduced", true); put("actualUnmodifiedSessionClose", true)
                put("actualCancelledIoWasBlockedOnMonitor", true); put("restoredArchiveOverwritten", true)
                put("root", root.toString()); put("finalDiskBvid", store.read().queue.single().bvid)
                put("hwndCreated", false); put("realAccountRequests", false)
            }
            Files.writeString(Path.of(args.single()), report.toString())
            println("Captured unmodified Session.close: cancelled monitor-queued IO overwrote the actual restored archive.")
        } finally { SwingUtilities.invokeAndWait { session.close() }; player.close() }
    }; exit = 0 } catch (failure: Throwable) { failure.printStackTrace() }
    finally { kotlin.system.exitProcess(exit) }
}
