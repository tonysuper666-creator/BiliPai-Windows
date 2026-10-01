package com.bilipai.desktop.ui

import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.CacheStrategy
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.android.purebilibili.app.resolveOriginalImageMemoryCachePercent
import com.android.purebilibili.core.lifecycle.*
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.time.Instant

@OptIn(coil3.annotation.ExperimentalCoilApi::class)
fun main(args: Array<String>) = runBlocking {
    var assertions = 0
    fun verify(value: Boolean) { check(value); assertions++ }
    val platform = PlatformContext.INSTANCE
    val root = Path.of(args.single())
    Files.createDirectories(root)
    val repository = DesktopRepository(DesktopSessionStore(root.resolve("fixture-session.json")))
    val alive = AtomicBoolean(true)
    val application = DesktopApplicationImageLoader(repository, root.resolve("cache"), alive::get)
    try {
        val loader = application.imageLoader
        verify(loader === SingletonImageLoader.get(platform))
        verify(resolveOriginalImageMemoryCachePercent() == 0.10)
        verify(loader.diskCache!!.maxSize == 100L * 1024 * 1024)
        verify(loader.memoryCache!!.maxSize in 1..Runtime.getRuntime().maxMemory())
        val call = application.networkCalls.newCall(okhttp3.Request.Builder().url("https://i0.hdslb.com/fixture.png").build())
        // Existing request tags are intentionally file-private. Inspect only their nonsecret
        // epoch/visitor-presence/lifetime metadata in the fixture, without changing visibility.
        @Suppress("UNCHECKED_CAST")
        val stampType = Class.forName("com.bilipai.desktop.data.DesktopSessionEpoch") as Class<Any>
        val stamp = call.request().tag(stampType)!!
        fun value(name: String) = stampType.getDeclaredMethod(name).apply { isAccessible = true }.invoke(stamp)
        @Suppress("UNCHECKED_CAST")
        val stampOwned = value("getStillOwned") as (() -> Boolean)
        verify(value("getValue") == repository.sessionEpoch)
        verify(value("getGuestBuvid3") != null && stampOwned())
        call.cancel() // The fixture never enqueues or executes an HTTP request.

        val strip = BufferedImage(8000, 20, BufferedImage.TYPE_INT_RGB)
        val file = root.resolve("synthetic-wide-strip.png").toFile()
        try { check(ImageIO.write(strip, "png", file)) } finally { strip.flush() }
        val result = loader.execute(ImageRequest.Builder(platform).data(file).build()) as SuccessResult
        val bitmap = (result.image as BitmapImage).bitmap
        verify(bitmap.width in 1..4608 && bitmap.height > 0)
        verify(!bitmap.isClosed)
        // Cache-owned pixels stay borrowed; application close releases the real loader/cache.

        val now = Instant.fromEpochMilliseconds(10_000)
        val strategy = CacheControlCacheStrategy { now }
        val options = Options(platform)
        val request = NetworkRequest("https://i0.hdslb.com/cache-fixture.png")
        fun response(headers: NetworkHeaders) = NetworkResponse(code = 200,
            requestMillis = 9000, responseMillis = 9000, headers = headers)
        val fresh = response(NetworkHeaders.Builder().set("Cache-Control", "max-age=3600").build())
        verify(strategy.read(fresh, request, options).response != null)
        val stale = response(NetworkHeaders.Builder().set("Cache-Control", "max-age=0").set("ETag", "synthetic-tag").build())
        verify(strategy.read(stale, request, options).request!!.headers["If-None-Match"] == "synthetic-tag")
        val noStore = response(NetworkHeaders.Builder().set("Cache-Control", "no-store").build())
        verify(strategy.write(null, request, noStore, options) == CacheStrategy.WriteResult.DISABLED)
        val noCacheRequest = NetworkRequest(request.url, headers = NetworkHeaders.Builder().set("Cache-Control", "no-cache").build())
        verify(strategy.read(fresh, noCacheRequest, options).request != null)

        verify(BACKGROUND_IMAGE_TRIM_DELAY_MS == 45_000L)
        verify(resolveBackgroundImageCacheTrimTargetBytes(32L * 1024 * 1024, 0) == 24L * 1024 * 1024)
        verify(resolveBackgroundImageCacheTrimTargetBytes(32L * 1024 * 1024, 44_999) == 24L * 1024 * 1024)
        verify(resolveBackgroundImageCacheTrimTargetBytes(32L * 1024 * 1024, 45_000) == 8L * 1024 * 1024)
        verify(resolveBackgroundImageCacheTrimTargetBytes(-1, 45_000) == 0L)
        verify(!shouldTrimImageCacheAfterBackgroundDelay(true, true, 45_000))
        verify(!shouldTrimImageCacheAfterBackgroundDelay(false, false, 45_000))
        verify(shouldTrimImageCacheAfterBackgroundDelay(true, false, 45_000))

        alive.set(false)
        verify(!stampOwned())
        try { application.networkCalls.newCall(okhttp3.Request.Builder().url(request.url).build()); error("retired image owner admitted") }
        catch (_: java.io.IOException) { verify(true) }
    } finally {
        application.close(); application.close()
    }
    println("PASS application image loader / 3 groups / $assertions assertions")
    println("LIMIT generated image and same temporary Store only; no HTTP, personal cache, Window background timer or Root acceptance")
}
