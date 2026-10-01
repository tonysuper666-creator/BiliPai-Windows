package com.bilipai.desktop.ui

import com.android.purebilibili.feature.dynamic.components.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path

private var assertions = 0
private val cases = mutableListOf<String>()
private fun verify(value: Boolean, message: String) { assertions++; check(value) { message } }
private const val MAX_BYTES = 32L * 1024 * 1024
private const val DECODED_BYTES = 64L * 1024 * 1024

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0])
    val output = root.resolve("outputs"); Files.createDirectories(output)
    val selection = listOf(
        "x.GIF" to ("gif" to "image/gif"),
        "x.webp" to ("webp" to "image/webp"),
        "x.gif.webp.png" to ("gif" to "image/gif"),
        "x.png" to ("png" to "image/png"),
        "x.PNG?foo=bar" to ("png" to "image/png"),
        "x.jpg" to ("jpg" to "image/jpeg"),
        "x.avif" to ("jpg" to "image/jpeg"),
    )
    selection.forEach { (url, expected) ->
        verify(desktopOriginalStaticGalleryExtension(url) == expected.first, "extension: $url")
        verify(desktopOriginalStaticGalleryMimeType(url) == expected.second, "MIME: $url")
        val name = desktopOriginalStaticGalleryFileName(url)
        verify(name.matches(Regex("BiliPai_[0-9]+\\.${expected.first}")), "original filename: $url")
    }
    cases += "original-url-priority-format-naming"

    suspend fun encode(name: String, sourceName: String, url: String) {
        val source = root.resolve("inputs").resolve(sourceName)
        val stage = output.resolve(name); Files.createFile(stage)
        encodeDesktopStaticGalleryImageToStage(source, stage, url, MAX_BYTES, DECODED_BYTES) {
            currentCoroutineContext().ensureActive()
        }
        verify(Files.size(stage) > 0, "encoded output: $name")
    }
    encode("alpha.png", "alpha.png", "x.png")
    cases += "decoded-png-alpha"
    encode("jpeg95.jpg", "rgb.png", "x.jpg")
    cases += "remaining-static-jpeg95"
    encode("raw.gif", "animated.gif", "x.gif")
    verify(Files.readAllBytes(output.resolve("raw.gif")).contentEquals(Files.readAllBytes(root.resolve("inputs/animated.gif"))), "GIF exact bytes")
    cases += "raw-gif"
    encode("raw.webp", "animated.webp", "x.webp")
    verify(Files.readAllBytes(output.resolve("raw.webp")).contentEquals(Files.readAllBytes(root.resolve("inputs/animated.webp"))), "WebP exact bytes")
    cases += "raw-webp"
    for (origin in 1..8) {
        encode("origin-$origin.jpg", "origin-$origin.jpg", "x.jpg")
        cases += "exif-origin-$origin"
    }

    val limited = output.resolve("budget-rejected.png")
    val sentinel = "stage not opened".toByteArray(); Files.write(limited, sentinel)
    val budgetFailure = runCatching {
        encodeDesktopStaticGalleryImageToStage(root.resolve("inputs/alpha.png"), limited, "x.png", MAX_BYTES, 8L) {
            currentCoroutineContext().ensureActive()
        }
    }.exceptionOrNull()
    verify(budgetFailure is IllegalArgumentException, "decoded budget rejected")
    verify(Files.readAllBytes(limited).contentEquals(sentinel), "budget preflight before opening stage")
    cases += "decoded-budget-preflight"

    val canceled = output.resolve("canceled-stage.png"); Files.write(canceled, sentinel)
    val canceledJob = launch {
        var checkpointCount = 0
        encodeDesktopStaticGalleryImageToStage(root.resolve("inputs/alpha.png"), canceled, "x.png", MAX_BYTES, DECODED_BYTES) {
            checkpointCount++
            if (checkpointCount == 4) currentCoroutineContext().cancel(CancellationException("fixture cancels only codec job"))
            currentCoroutineContext().ensureActive()
        }
    }
    canceledJob.join()
    verify(canceledJob.isCancelled, "real codec Job canceled")
    verify(currentCoroutineContext().isActive, "caller scope remains active")
    verify(Files.readAllBytes(canceled).contentEquals(sentinel), "cancellation checked before stage opening")
    cases += "actual-job-cancel-before-output"
    val names = cases.joinToString(",") { "\"$it\"" }
    val result = "{\"passed\":true,\"assertions\":$assertions,\"caseCount\":${cases.size},\"cases\":[$names],\"SkiaCodecExecuted\":true,\"HTTP\":false,\"HWND\":false,\"MainOverrides\":false,\"AssetsOwnerOrFinalCommitIntegrated\":false}"
    Files.writeString(root.resolve("kotlin-result.json"), result)
    println(result)
}
