package com.bilipai.desktop.ui

import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.diagnostics.sanitizeDesktopDiagnosticText
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Windows local diagnostic consumer of the original three owner effects.
 * Root supplies its existing global Store/diagnostic writer and exact entry gate.
 * No Firebase/Crashlytics, upload, client, account identity or fatal snapshot is created.
 * Original sensitive video ID/title/author parameters remain omitted.
 */
internal class DesktopOriginalVideoOwnerDiagnosticsBinding(
    private val globalStore: DesktopPluginStore,
    private val diagnostics: DesktopDiagnostics?,
    private val stillOwned: () -> Boolean,
    private val admission: ((() -> Unit) -> Boolean),
) : DesktopOriginalVideoOwnerAnalytics, DesktopOriginalVideoOwnerCrash {
    val firebaseTransportAvailable: Boolean get() = false
    val localDiagnosticConsumerAvailable: Boolean get() = diagnostics != null

    // Same original non-fatal rate policy, confined to this entry's effect view.
    // These timestamps contain only sanitized error type/text, never video identity.
    private val nonFatalRateLimiter = ConcurrentHashMap<String, Long>()

    private fun record(level: String, tag: String, message: String, consentKey: String,
        nonFatalKey: String? = null) {
        try {
            val consumer = diagnostics ?: return
            if (!stillOwned()) return
            val safe = sanitizeDesktopDiagnosticText(message)
            admission {
                if (!stillOwned()) return@admission
                // Defaults are the ORIGINAL SettingsManager defaults (both true).
                // DesktopDiagnostics independently enforces enhanced-local-I consent.
                if (globalStore.preferences("settings")[consentKey]?.jsonPrimitive?.booleanOrNull == false)
                    return@admission
                if (nonFatalKey != null &&
                    (!hasNonFatalReportingHeadroom() || shouldDropByRateLimit(nonFatalKey)))
                    return@admission
                // Only enqueue on the same serial writer; file IO occurs on its worker.
                consumer.record(level, tag, safe)
            }
        } catch (_: Exception) {
            // Original analytics/reporting failures do not fail load or quality actions.
        }
    }

    override fun logVideoPlay(videoId: String, title: String, author: String) =
        record("I", "VideoAnalytics", "event=video_play", "analytics_enabled")

    override fun logQualityChange(videoId: String, fromQuality: Int, toQuality: Int) =
        record("I", "VideoAnalytics",
            "event=quality_change, from_quality=$fromQuality, to_quality=$toQuality",
            "analytics_enabled")

    override fun reportVideoError(videoId: String, type: String, message: String) {
        val safeType = sanitizeDesktopDiagnosticText(type)
        val safeMessage = sanitizeDesktopDiagnosticText(message)
        record("E", "CrashReporter", "Video Error: [$safeType] ${safeMessage.take(300)}",
            "crash_tracking_enabled", "video:$safeType:${safeMessage.take(80)}")
    }

    private fun shouldDropByRateLimit(key: String): Boolean {
        val now = System.currentTimeMillis()
        val lastTs = nonFatalRateLimiter[key]
        if (lastTs != null && now - lastTs < 60_000L) {
            return true
        }
        nonFatalRateLimiter[key] = now
        if (nonFatalRateLimiter.size > 300) {
            val expireBefore = now - 60_000L * 2
            nonFatalRateLimiter.entries.removeIf { it.value < expireBefore }
        }
        return false
    }

    private fun hasNonFatalReportingHeadroom(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemoryBytes = runtime.maxMemory()
        val totalMemoryBytes = runtime.totalMemory()
        val freeMemoryBytes = runtime.freeMemory()
        val availableBytes = (maxMemoryBytes - totalMemoryBytes + freeMemoryBytes).coerceAtLeast(0L)
        val requiredBytes = max(8L * 1024 * 1024, (maxMemoryBytes * 0.03).toLong())
        return availableBytes >= requiredBytes
    }
}
