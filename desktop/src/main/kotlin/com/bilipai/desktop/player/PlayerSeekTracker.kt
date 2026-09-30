package com.bilipai.desktop.player

import kotlin.math.abs

internal data class PendingPlayerSeek(val id: Long, val sourceVersion: Long, val positionSeconds: Double, val submittedNanos: Long)

/** Native worker only. Polling never completes a seek; playback-restart or a matching terminal EOF may acknowledge it. */
internal class PlayerSeekTracker {
    private var pending: PendingPlayerSeek? = null
    fun submit(id: Long, sourceVersion: Long, positionSeconds: Double, nowNanos: Long = System.nanoTime()) {
        pending = PendingPlayerSeek(id, sourceVersion, positionSeconds, nowNanos)
    }
    fun reset() { pending = null }
    fun acknowledge(sourceVersion: Long, positionSeconds: Double, nowNanos: Long = System.nanoTime()): PendingPlayerSeek? {
        val request = pending ?: return null
        if (sourceVersion != request.sourceVersion || nowNanos - request.submittedNanos > 15_000_000_000L) {
            reset(); return null
        }
        if (!positionSeconds.isFinite() || abs(positionSeconds - request.positionSeconds) > 0.5) return null
        reset(); return request
    }
}
