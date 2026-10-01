package com.bilipai.desktop.ui

import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.feature.audio.lyrics.halcyon.*
import coil3.PlatformContext
import kotlinx.coroutines.*
import okhttp3.Call
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

private val results = linkedMapOf<String, Any>()
private var assertions = 0
private fun prove(condition: Boolean, message: String) { check(condition) { message }; assertions++ }
private fun pixelsHash(bitmap: DesktopMusicRaster): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val p = bitmap.getPixel(x, y)
        digest.update(byteArrayOf((p ushr 24).toByte(), (p ushr 16).toByte(), (p ushr 8).toByte(), p.toByte()))
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]); Files.createDirectories(root)
    val input = DesktopMusicRaster.createBitmap(96, 64, DesktopMusicRaster.Config.ARGB_8888)
    val row = IntArray(96)
    for (y in 0 until 64) {
        for (x in 0 until 96) row[x] = when {
            x < 48 && y < 32 -> 0xffdf1020.toInt()
            x >= 48 && y < 32 -> 0xff10c850.toInt()
            x < 48 -> 0xff2030df.toInt()
            else -> 0xffd8d8d8.toInt()
        }
        input.setPixels(row, 0, 96, 0, y, 96, 1)
    }
    prove(PlayerPalette.from(null) == PlayerPalette.Default, "Original no-artwork fallback changed")
    val palette = PlayerPalette.fromCoverBackground(input, false)
    prove(palette != PlayerPalette.Default, "Original weighted cover palette failed to select bands")
    prove(palette.top != palette.middle && palette.middle != palette.bottom, "Original darkening hierarchy changed")
    val hsv = FloatArray(3)
    DesktopMusicRasterColor.RGBToHSV(0, 255, 0, hsv)
    prove(hsv.contentEquals(floatArrayOf(120f, 1f, 1f)), "Real Windows HSV green anchor")
    prove(DesktopMusicRasterColor.HSVToColor(hsv) == 0xff00ff00.toInt(), "Real Windows HSV roundtrip")
    val scaled = DesktopMusicRaster.createScaledBitmap(input, 48, 32, true)
    prove(scaled.width == 48 && scaled.height == 32, "Real Java2D resize dimensions")
    prove(scaled.getPixel(3, 3) == input.getPixel(3, 3), "Opaque constant band rescale")
    val cropped = DesktopMusicRaster.createBitmap(input, 48, 0, 24, 24)
    prove(cropped.getPixel(0, 0) == 0xff10c850.toInt(), "Actual independent region crop")
    prove(cropped !== input, "Crop must not recycle a borrowed source")
    val target = DesktopMusicRaster.createBitmap(20, 20, DesktopMusicRaster.Config.ARGB_8888)
    val matrix = DesktopMusicRasterMatrix(); matrix.setScale(1f, 1f); matrix.postTranslate(5f, 4f)
    DesktopMusicRasterCanvas(target).use { it.drawBitmap(cropped, matrix, DesktopMusicRasterPaint(1)) }
    prove(target.getPixel(5, 4) == 0xff10c850.toInt() && target.getPixel(0, 0) == 0, "Actual affine placement and clip")
    val a = createAppleFlowFrameBitmap(input, 320, 200, 0L, 160, 45f, 0x22004080, 0x1a702030)
    val b = createAppleFlowFrameBitmap(input, 320, 200, 0L, 160, 45f, 0x22004080, 0x1a702030)
    val later = createAppleFlowFrameBitmap(input, 320, 200, 31_000L, 160, 45f, 0x22004080, 0x1a702030)
    prove(a.width > 0 && a.height > 0 && a.width != a.height, "Original non-square viewport downsampling")
    prove(pixelsHash(a) == pixelsHash(b), "Original clock/layer/blur/crop deterministic")
    prove(pixelsHash(a) != pixelsHash(later), "Original three-layer rotation must change actual pixels")
    val highDpi = createAppleFlowFrameBitmap(input, 320, 200, 0L, 480, 45f, 0x22004080, 0x1a702030)
    prove(highDpi.width < a.width && highDpi.height < a.height, "Original DPI sampling thresholds consumed by the actual full frame algorithm")
    val bitmap = a.asImageBitmap()
    prove(bitmap.width == a.width && bitmap.height == a.height, "Actual Skia Compose image conversion")
    val copy = desktopMusicRasterCopy(bitmap)
    prove(pixelsHash(copy) == pixelsHash(a), "Owned Compose bitmap copy preserves unpremultiplied pixels")
    results["cpu"] = mapOf("frame" to listOf(a.width, a.height), "initialHash" to pixelsHash(a), "laterHash" to pixelsHash(later))
    val file = root.resolve("synthetic-bands.png")
    ImageIO.write(input.image, "png", file.toFile())
    val requests = AtomicInteger()
    val sameFactory = Call.Factory { requests.incrementAndGet(); error("Fixture has no external HTTP authorization") }
    val loader = newImageLoader(PlatformContext.INSTANCE, sameFactory, root.resolve("cache").toFile())
    val owned = AtomicBoolean(true)
    val reader = DesktopOriginalMusicArtworkLoader(PlatformContext.INSTANCE, loader, owned::get)
    try {
        val decoded = checkNotNull(reader.load(file.toUri().toString()))
        prove(decoded.width in 1..512 && decoded.height in 1..512 &&
            kotlin.math.abs(decoded.width.toFloat() / decoded.height - 1.5f) < .02f,
            "Actual Coil original 512x512 request keeps the input aspect ratio: ${decoded.width}x${decoded.height}")
        prove(decoded.getPixel(10, 10) == input.getPixel(10, 10), "Borrowed cache -> owned pixel copy")
        decoded.recycle()
        val decodedAgain = checkNotNull(reader.load(file.toUri().toString()))
        prove(decodedAgain.getPixel(10, 10) == input.getPixel(10, 10), "Recycling owned copy must not close borrowed Coil Bitmap")
        decodedAgain.recycle()
        owned.set(false)
        val retired = runCatching { reader.load(file.toUri().toString()) }.exceptionOrNull()
        prove(retired is CancellationException, "Retired entry must reject before acquisition")
        owned.set(true)
        val cancelled = Job().also { it.cancel() }
        val cancelledResult = runCatching { withContext(cancelled) { reader.load(file.toUri().toString()) } }.exceptionOrNull()
        prove(cancelledResult is CancellationException, "Cancelled caller Job must reject acquisition")
        prove(requests.get() == 0, "Local fixture must not execute HTTP")
    } finally { loader.shutdown() }
    listOf(input, scaled, cropped, target, a, b, later, highDpi, copy).forEach(DesktopMusicRaster::recycle)
    // This resolves the unchanged product/prospective original renderer ABI without
    // constructing an account VM, Window or required fake runtime platform.
    for (name in listOf(
        "com.android.purebilibili.feature.audio.screen.MusicPlayerContentKt",
        "com.android.purebilibili.feature.audio.screen.ExternalPlaylistImportDialogKt",
        "com.android.purebilibili.feature.audio.lyrics.halcyon.AppleMusicKaraokeTextKt",
        "com.android.purebilibili.feature.audio.lyrics.halcyon.AppleMusicLyricsViewKt")) {
        prove(Class.forName(name, false, Thread.currentThread().contextClassLoader).declaredMethods.isNotEmpty(), "Full original renderer class load")
    }
    results["assertions"] = assertions; results["httpCalls"] = requests.get()
    results["windowOrAccount"] = false; results["actualRootMounted"] = false
    println("PASS " + results)
}
