package com.bilipai.desktop.download

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The desktop counterpart of the upstream DownloadWorker's `Result.retry()` with
 * `setBackoffCriteria(EXPONENTIAL, 30s)`: a transfer that was cut off by the network is queued again after a doubling
 * wait instead of being left FAILED. Anything that is not a cut-off (a refused file, a failed merge, a bad manifest) stays FAILED,
 * so the user sees the real reason at once; and the number of automatic tries is bounded.
 */
internal class DesktopDownloadRetryPolicy(
    val baseDelayMs: Long = 30_000L,
    val maxDelayMs: Long = 10 * 60_000L,
    val maxAttempts: Int = 5,
) {
    init { require(baseDelayMs > 0L && maxDelayMs >= baseDelayMs && maxAttempts >= 0) }

    /** Wait before automatic try number [attemptsSpent] + 1, or null when [error] must be reported as FAILED now. */
    fun nextDelayMs(attemptsSpent: Int, error: Throwable): Long? {
        if (attemptsSpent < 0 || attemptsSpent >= maxAttempts || !isTransient(error)) return null
        return delayAfter(attemptsSpent)
    }

    /** 30 s, 60 s, 120 s ... capped at [maxDelayMs]. */
    fun delayAfter(attemptsSpent: Int): Long {
        var delay = baseDelayMs
        repeat(attemptsSpent.coerceAtLeast(0)) { delay = if (delay >= maxDelayMs / 2) maxDelayMs else delay * 2 }
        return delay.coerceAtMost(maxDelayMs)
    }

    companion object {
        private val transientStatus = Regex("""HTTP (429|5\d\d)\b""")

        fun isTransient(error: Throwable): Boolean {
            var next: Throwable? = error
            var depth = 0
            while (next != null && depth++ < 8) {
                val current: Throwable = next
                when (current) {
                    is SocketTimeoutException, is UnknownHostException, is SocketException, is EOFException -> return true
                    is SSLException -> if (current.cause is IOException || current.message?.contains("reset", true) == true) return true
                    is IOException -> {
                        val message = current.message.orEmpty()
                        if (current.javaClass.name == "okhttp3.internal.http2.StreamResetException" ||
                            message.startsWith("下载长度不匹配") || message.startsWith("分片下载未完成") ||
                            transientStatus.containsMatchIn(message)) return true
                    }
                }
                next = current.cause?.takeIf { it !== current }
            }
            return false
        }
    }
}
