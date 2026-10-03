package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.js.*
import com.android.purebilibili.feature.plugin.js.buildBiliPaiJsModuleParamsJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal fun runDesktopJsMediaImageFixture(): Unit = runBlocking {
    fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xffff0000.toInt())
        image.setRGB(width - 1, height - 1, 0xff00ff00.toInt())
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
    }
    val bytes = png(2, 2)
    var passed = 0
    suspend fun case(name: String, block: suspend () -> Unit) { block(); passed++; println("PASS $name") }
    case("actual Skia thumbnail decode preserves real opaque pixels") {
        decodeDesktopJsImage(bytes).use { image ->
            check(image.width == 2 && image.height == 2)
            Surface.makeRasterN32Premul(2, 2).use { surface ->
                surface.canvas.drawImage(image, 0f, 0f)
                Bitmap().use { pixels ->
                    check(pixels.allocN32Pixels(2, 2) && surface.readPixels(pixels, 0, 0))
                    check(pixels.getColor(0, 0) == 0xffff0000.toInt())
                    check(pixels.getColor(1, 1) == 0xff00ff00.toInt())
                }
            }
        }
    }
    case("malformed input byte cap and encoded metadata pixel cap reject before thumbnail allocation") {
        check(runCatching { decodeDesktopJsImage(byteArrayOf(1, 2, 3)) }.isFailure)
        check(runCatching { decodeDesktopJsImage(ByteArray(2_097_153)) }.isFailure)
        val excessive = png(4097, 1024)
        check(excessive.size < 2_097_152)
        check(runCatching { decodeDesktopJsImage(excessive) }.exceptionOrNull()?.message?.contains("像素") == true)
    }
    case("original full candidate order and actual failed-image fallback") {
        val item = BiliPaiJsMediaItem("id", "title", coverUrl = "third", backdropUrl = "first",
            backdropPaths = listOf("second", "first"), posterPath = "fourth")
        val candidates = resolveBiliPaiJsMediaImageCandidates(item)
        check(candidates == listOf("first", "second", "third", "fourth"))
        val seen = mutableListOf<String>()
        loadDesktopJsMediaImage(candidates) { seen += it; if (it == "third") bytes else byteArrayOf(1) }
            .use { image -> check(image != null && image.width == 2) }
        check(seen == listOf("first", "second", "third"))
        check(loadDesktopJsMediaImage(candidates) { byteArrayOf(1) } == null)
        check(loadDesktopJsMediaImage(emptyList()) { error("No-image must not request") } == null)
    }
    case("cancellation propagates without trying remaining image candidates") {
        val entered = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val job = launch { loadDesktopJsMediaImage(listOf("first", "second")) {
            seen += it; entered.complete(Unit); awaitCancellation()
        } }
        entered.await(); job.cancelAndJoin()
        check(seen == listOf("first"))
    }
    case("enum numeric boolean and unknown saved parameter values remain original JSON strings") {
        val module = BiliPaiJsModule(title = "params", functionName = "load", params = listOf(
            BiliPaiJsParam("mode", "mode", "enum", "default", listOf(BiliPaiJsEnumOption("one", "1"))),
            BiliPaiJsParam("number", "number", "number", "42"),
            BiliPaiJsParam("boolean", "boolean", "boolean", "false")))
        val payload = Json.parseToJsonElement(buildBiliPaiJsModuleParamsJson(module, mapOf("mode" to "saved unknown", "boolean" to "true"), page = 1, loadedCount = 0)).jsonObject
        check(payload["mode"]?.jsonPrimitive?.content == "saved unknown")
        check(payload["number"]?.jsonPrimitive?.content == "42")
        check(payload["boolean"]?.jsonPrimitive?.content == "true")
        check(payload.values.all { it.jsonPrimitive.isString })
    }
    println("Actual image/parameter fixtures: $passed PASS")
}
