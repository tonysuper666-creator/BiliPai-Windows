package android.os

/** Desktop stand-in for android.os.SystemClock: one monotonic clock for the whole process. */
object SystemClock {
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L
    @JvmStatic fun elapsedRealtime(): Long = uptimeMillis()
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime()
}
