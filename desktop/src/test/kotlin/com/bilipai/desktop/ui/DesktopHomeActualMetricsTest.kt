package com.bilipai.desktop.ui

import androidx.compose.runtime.MonotonicFrameClock
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

class DesktopHomeActualMetricsTest {
    private class Clock : MonotonicFrameClock {
        val frames = Channel<Long>(Channel.UNLIMITED)
        var requests = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests++
            return onFrame(frames.receive())
        }
    }

    private fun logger(level: Level) = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        this.level = level
    }

    @Test fun normalLoggingDoesNotRequestEvenTheFirstFrame(): Unit = runBlocking {
        val clock = Clock()
        val metrics = DesktopHomeActualMetrics({ true }, logger(Level.INFO))
        try {
            withContext(clock) { withTimeout(2_000) { metrics.startFrameClock(this).join() } }
            assertEquals(0, clock.requests)
        } finally { metrics.close() }
    }

    @Test fun debugLoggingRecordsIntervalsAndStopsRequestingWhenDisabled(): Unit = runBlocking {
        val clock = Clock()
        val log = logger(Level.FINE)
        val intervals = mutableListOf<String>()
        log.addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.parameters?.firstOrNull() == "ComposeFrameIntervalNanos") {
                    intervals += record.parameters[1].toString()
                }
            }
            override fun flush() {}
            override fun close() {}
        })
        val metrics = DesktopHomeActualMetrics({ true }, log)
        try {
            withContext(clock) {
                withTimeout(2_000) {
                    val job = metrics.startFrameClock(this)
                    clock.frames.send(1_000_000L)
                    clock.frames.send(7_000_000L)
                    while (clock.requests < 3) yield()
                    assertEquals(listOf("6000000"), intervals)
                    log.level = Level.INFO
                    clock.frames.send(13_000_000L) // Complete the already pending frame.
                    job.join()
                    assertEquals(3, clock.requests)
                    assertEquals(listOf("6000000"), intervals)
                }
            }
        } finally { metrics.close() }
    }

    @Test fun closedOrRetiredWindowDoesNotRequestFramesEvenInDebugMode(): Unit = runBlocking {
        val clock = Clock()
        val closed = DesktopHomeActualMetrics({ true }, logger(Level.FINE)).apply { close() }
        val retired = DesktopHomeActualMetrics({ false }, logger(Level.FINE))
        try {
            withContext(clock) {
                withTimeout(2_000) {
                    closed.startFrameClock(this).join()
                    retired.startFrameClock(this).join()
                }
            }
            assertEquals(0, clock.requests)
        } finally { retired.close() }
    }
}
