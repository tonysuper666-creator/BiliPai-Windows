package com.bilipai.desktop.plugins

import java.nio.file.AccessDeniedException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Only the JDK Windows ERROR_ACCESS_DENIED translation is retried. Other IO
 * failures and permanent denial remain failures. Every caller invokes this after
 * releasing backing/Root admission and starts a new CAS/permit attempt afterwards. */
internal object DesktopPreferenceReplacementRetry {
    private val delaysMillis = longArrayOf(20, 40, 80, 120, 160)
    internal val maximumRetries: Int get() = delaysMillis.size
    internal fun waitOutsideAdmissionOrThrow(failure: AccessDeniedException,
        failedAttempts: Int, checkRequest: () -> Unit) {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") || failure.reason != null ||
            failedAttempts !in 1..delaysMillis.size) throw failure
        checkRequest()
        try { Thread.sleep(delaysMillis[failedAttempts - 1]) }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Preference replacement retry interrupted").apply { initCause(interrupted) }
        }
        checkRequest()
    }
}
