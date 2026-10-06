package com.bilipai.desktop.player

import java.nio.file.Files
import java.nio.file.Path

/** A completed diagnostic transaction; no claim about decoded or physical pixel correctness. */
internal data class DesktopNativeSoftwareScreenshotReceipt(
    val captured: Boolean,
    val restored: Boolean,
    val sourceRetained: Boolean,
    val fields: Map<String, String>,
)

/** Called synchronously by the sole existing MPV worker, only after a physical failure. */
internal fun captureDesktopNativeSoftwareScreenshot(
    destination: Path,
    expectedEntry: String?,
    identity: Map<String, String>,
    current: () -> Boolean,
    read: (String) -> String?,
    setSoftware: (String) -> Unit,
    capture: (Path) -> Unit,
    publish: (Path, Path) -> Boolean,
): DesktopNativeSoftwareScreenshotReceipt {
    val fields = linkedMapOf<String, String>().apply { putAll(identity) }
    var temporary: Path? = null
    var mutationAttempted = false
    var captured = false
    var restored = false
    var sourceRetained = false
    var stage = "admission"
    fun required(name: String): String = checkNotNull(read(name)) { "Required native diagnostic property unavailable" }
    fun windowId(): String {
        val value = required("window-id")
        val parsed = if (value.startsWith("0x", true)) java.lang.Long.parseUnsignedLong(value.substring(2), 16)
            else java.lang.Long.parseUnsignedLong(value)
        check(parsed != 0L)
        return java.lang.Long.toUnsignedString(parsed)
    }
    fun observe(suffix: String) {
        fields["entry$suffix"] = required("playlist/0/id")
        fields["playlistCount$suffix"] = required("playlist-count")
        fields["pause$suffix"] = required("pause")
        fields["position${suffix}Seconds"] = required("time-pos").also { check(it.toDouble().isFinite()) }
        fields["windowId$suffix"] = windowId()
    }
    try {
        check(current() && expectedEntry != null)
        require(destination.fileName.toString().endsWith(".png", true))
        check(!Files.exists(destination))
        observe("Before")
        check(fields["entryBefore"] == expectedEntry && fields["playlistCountBefore"] == "1" &&
            fields["pauseBefore"] in setOf("yes", "no"))
        fields["screenshotSwBefore"] = required("options/screenshot-sw")
        check(fields["screenshotSwBefore"] == "no") // Never convert an unknown/original yes state into no.
        val directory = checkNotNull(destination.parent)
        Files.createDirectories(directory)
        val staging = Files.createTempFile(directory, ".bilipai-failure-cpu-", ".png")
        temporary = staging
        check(current())
        stage = "select-software"
        mutationAttempted = true // A rejected native call could still have modified the option.
        setSoftware("yes")
        fields["screenshotSwDuring"] = required("options/screenshot-sw")
        check(fields["screenshotSwDuring"] == "yes" && current())
        stage = "capture"
        fields["captureStartedNanos"] = System.nanoTime().toString()
        capture(staging)
        fields["captureFinishedNanos"] = System.nanoTime().toString()
        check(Files.size(staging) > 0)
        captured = true
    } catch (failure: Exception) {
        fields["captureErrorType"] = failure.javaClass.simpleName
        fields["captureErrorStage"] = stage
    } finally {
        if (mutationAttempted) {
            try {
                // Do not test current/completion here: cancellation or retirement must not skip cleanup.
                setSoftware("no")
                fields["screenshotSwAfter"] = required("options/screenshot-sw")
                restored = fields["screenshotSwAfter"] == "no"
                check(restored)
            } catch (failure: Exception) {
                restored = false
                fields["restoreErrorType"] = failure.javaClass.simpleName
            }
            try {
                observe("After")
                sourceRetained = current() && fields["entryAfter"] == expectedEntry &&
                    fields["playlistCountAfter"] == "1" && fields["pauseAfter"] == fields["pauseBefore"] &&
                    fields["windowIdAfter"] == fields["windowIdBefore"]
            } catch (failure: Exception) {
                fields["sourceCheckErrorType"] = failure.javaClass.simpleName
            }
        }
    }
    try {
        captured = captured && restored && sourceRetained
        if (captured) captured = publish(checkNotNull(temporary), destination)
        if (!captured) sourceRetained = sourceRetained && current()
    } catch (failure: Exception) {
        captured = false
        fields["publishErrorType"] = failure.javaClass.simpleName
    } finally {
        temporary?.let { Files.deleteIfExists(it) }
    }
    return DesktopNativeSoftwareScreenshotReceipt(captured, restored, sourceRetained,
        java.util.Collections.unmodifiableMap(LinkedHashMap(fields)))
}
