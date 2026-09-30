package com.bilipai.desktop.plugins

import kotlinx.serialization.json.*
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Rect
import org.jetbrains.skia.skottie.Animation
import org.jetbrains.skia.skottie.AnimationBuilder
import org.jetbrains.skia.skottie.LogLevel
import org.jetbrains.skia.skottie.Logger
import java.nio.file.Files
import java.nio.file.Path

/** The bundled Skia Skottie engine draws real Lottie frames; no Android animation emulation. */
class DesktopLottieAsset private constructor(private val animation: Animation,
    val warnings: List<String>) : AutoCloseable {
    val durationSeconds: Double = animation.duration.toDouble()
    val framesPerSecond: Float = animation.fPS
    val width: Float = animation.width
    val height: Float = animation.height
    private var closed = false

    @Synchronized fun render(canvas: Canvas, width: Float, height: Float, seconds: Double, loop: Boolean = true) {
        check(!closed) { "动画资源已释放" }
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0 && seconds.isFinite())
        val time = if (loop && durationSeconds > 0) ((seconds % durationSeconds) + durationSeconds) % durationSeconds
            else seconds.coerceIn(0.0, durationSeconds)
        animation.seekFrameTime(time.toFloat())
        animation.render(canvas, Rect.makeWH(width, height))
    }
    @Synchronized override fun close() { if (!closed) { closed = true; animation.close() } }

    companion object {
        fun read(path: Path): DesktopLottieAsset {
            require(Files.isRegularFile(path) && Files.size(path) <= 32L * 1024 * 1024) { "Lottie 动画文件无效" }
            val bytes = Files.newInputStream(path).use { it.readNBytes(32 * 1024 * 1024 + 1) }
            return decode(bytes)
        }
        fun decode(bytes: ByteArray): DesktopLottieAsset {
            require(bytes.size <= 32 * 1024 * 1024) { "Lottie 动画超过资源大小限制" }
            val text = bytes.toString(Charsets.UTF_8)
            val root = Json.parseToJsonElement(text).jsonObject
            fun number(name: String): Double = root[name]?.jsonPrimitive?.doubleOrNull ?: error("Lottie 缺少 $name")
            val width = number("w"); val height = number("h"); val fps = number("fr")
            val duration = (number("op") - number("ip")) / fps
            require(width.isFinite() && height.isFinite() && width > 0 && height > 0 && width <= 8192 && height <= 8192 && width * height <= 16_777_216) { "Lottie 画布超过限制" }
            require(fps.isFinite() && fps > 0 && fps <= 240 && duration.isFinite() && duration > 0 && duration <= 3600) { "Lottie 时间轴无效" }
            // This builder has no external-resource provider. Report such assets instead of blank playback.
            val externalImages = root["assets"]?.jsonArray.orEmpty().any { asset ->
                (asset as? JsonObject)?.get("p")?.jsonPrimitive?.contentOrNull?.let { !it.startsWith("data:") } == true
            }
            require(!externalImages) { "此 Lottie 引用了外部图片，当前渲染器不支持该资源" }
            val warnings = mutableListOf<String>()
            val logger = object : Logger() {
                override fun log(level: LogLevel, message: String, json: String?) {
                    if (warnings.size < 16) warnings += "${level.name}: ${message.take(160)}"
                }
            }
            try {
                val animation = AnimationBuilder().use { builder ->
                    builder.setFontManager(FontMgr.default).setLogger(logger).buildFromString(text)
                }
                try {
                    require(animation.duration.isFinite() && animation.duration > 0 && animation.width > 0 && animation.height > 0) { "Lottie 动画无法解析" }
                    return DesktopLottieAsset(animation, warnings.toList())
                } catch (failure: Throwable) { animation.close(); throw failure }
            } finally { logger.close() }
        }
    }
}
