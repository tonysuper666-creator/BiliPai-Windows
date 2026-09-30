package com.bilipai.desktop.player

import com.bilipai.desktop.ui.DesktopRetainedMedia
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Window/account jobs and media ownership are tested against an actual attached native surface. */
internal object DesktopRetainedMediaNativeSmoke {
    fun run(player: MpvPlayer, originalHost: JFrame, video: File) {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val memory = DesktopRetainedMedia(parent, player) { }
        var miniHost: JFrame? = null
        val progress = AtomicInteger()
        var work: Job? = null
        try {
            SwingUtilities.invokeAndWait {
                memory.acquire(memory.offline)
                memory.offline.sourceVersion = player.loadVersioned(PlaybackSource(video.absolutePath, referer = "",
                    title = "Retained offline native fixture", startPositionSeconds = 2.0, startPaused = true))
                memory.offline.loaded = true
                work = memory.offline.scope.launch { while (isActive) { progress.incrementAndGet(); delay(25) } }
            }
            waitFor(player, "retained offline native source") { !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - 2.0) < 0.3 }
            val version = player.currentSourceVersion; val position = player.state.value.positionSeconds
            val before = progress.get()
            SwingUtilities.invokeAndWait {
                originalHost.contentPane.remove(player.surface); originalHost.validate()
                miniHost = JFrame("Retained native media fixture").apply {
                    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                    contentPane.add(player.surface); setSize(520, 330); setLocationRelativeTo(originalHost)
                    isAlwaysOnTop = true; isVisible = true
                }
            }
            waitFor(player, "retained source attaches to a second native host") {
                it.ready && !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - position) < 0.3
            }
            Thread.sleep(150)
            check(player.currentSourceVersion == version && memory.offline.ownsNativeSource && memory.offline.loaded)
            check(progress.get() > before && work?.isActive == true) { "Media scope stopped when the page host was removed." }
            SwingUtilities.invokeAndWait {
                requireNotNull(miniHost).contentPane.remove(player.surface)
                originalHost.contentPane.add(player.surface); originalHost.validate()
                requireNotNull(miniHost).dispose(); miniHost = null
            }
            waitFor(player, "retained source returns to original native host") {
                it.ready && !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - position) < 0.3
            }
            check(player.currentSourceVersion == version)
            val foreign = player.loadVersioned(PlaybackSource(video.absolutePath, referer = "", title = "Foreign media owner", startPaused = true))
            SwingUtilities.invokeAndWait { memory.offline.close() }
            waitFor(player, "old retained memory cannot stop foreign media") { !it.loading && it.paused && it.videoCodec != null }
            check(player.currentSourceVersion == foreign && !memory.offline.scope.isActive && work?.isCancelled == true)
            SwingUtilities.invokeAndWait {
                memory.acquire(memory.bangumi)
                memory.bangumi.sourceVersion = player.loadVersioned(PlaybackSource(video.absolutePath, referer = "", title = "Owned retained root close", startPaused = true))
                memory.bangumi.loaded = true
            }
            waitFor(player, "new retained memory owns native media") { !it.loading && it.videoCodec != null }
            val owned = player.currentSourceVersion
            SwingUtilities.invokeAndWait { memory.close() }
            waitFor(player, "root closes owned retained native source") { !it.loading && it.videoCodec == null && it.audioCodec == null }
            check(player.currentSourceVersion != owned && memory.pages.all { !it.scope.isActive && !it.loaded })
        } finally {
            SwingUtilities.invokeAndWait {
                miniHost?.let { host ->
                    host.contentPane.remove(player.surface); originalHost.contentPane.add(player.surface)
                    originalHost.validate(); host.dispose()
                }
                memory.close()
            }
            parent.cancel()
        }
    }

    private fun waitFor(player: MpvPlayer, operation: String, condition: (PlayerState) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = player.state.value
            check(state.error == null) { "$operation: ${state.error}" }
            if (condition(state)) return
            Thread.sleep(25)
        }
        error("Timed out waiting for $operation")
    }
}
