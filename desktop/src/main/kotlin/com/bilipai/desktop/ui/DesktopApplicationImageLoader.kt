package com.bilipai.desktop.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.app.newImageLoader
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.update.UpdateStorage
import okhttp3.Call
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Application-wide original Coil configuration, installed before any UI image request.
 * Only the network Call.Factory is adapted: every call captures the current primary epoch,
 * shares the existing client/proxy/dispatcher/pool, and uses its existing visitor boundary.
 * No request is given the separately selected playback account's credentials.
 */
internal class DesktopApplicationImageLoader(
    repository: DesktopRepository,
    cacheDirectory: Path,
    rootAlive: () -> Boolean,
) : AutoCloseable {
    private val accepting = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    val imageLoader: ImageLoader
    internal val networkCalls: Call.Factory

    init {
        val directory = cacheDirectory.toAbsolutePath().normalize()
        var ancestor = directory
        while (!Files.exists(ancestor)) ancestor = requireNotNull(ancestor.parent)
        UpdateStorage.existingPathWithoutLinks(ancestor)
        Files.createDirectories(directory)
        UpdateStorage.existingPathWithoutLinks(directory)
        val imageDirectory = directory.resolve("image_cache")
        Files.createDirectories(imageDirectory)
        UpdateStorage.existingPathWithoutLinks(imageDirectory)
        val created = AtomicReference<ImageLoader?>()
        val owned = { accepting.get() && rootAlive() }
        networkCalls = Call.Factory { request ->
            // Selecting an epoch when OkHttp executes would authorize an old queued image.
            val capturedEpoch = repository.sessionEpoch
            repository.ownedHomeCallFactory(capturedEpoch, owned, guest = true).newCall(request)
        }
        SingletonImageLoader.setSafe { platform ->
            newImageLoader(platform, networkCalls, directory.toFile()).also { created.set(it) }
        }
        imageLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        check(imageLoader === created.get()) {
            "Coil was initialized before the original application image-loader owner"
        }
    }

    fun stopAccepting() { accepting.set(false) }

    /** Existing Coil jobs/cache drain here. Root calls this on IO, outside admission monitors. */
    override fun close() {
        stopAccepting()
        if (closed.compareAndSet(false, true)) imageLoader.shutdown()
    }
}

internal val LocalDesktopApplicationImageLoader = staticCompositionLocalOf<DesktopApplicationImageLoader> {
    error("Original image loading requires the actual application owner installed before UI requests")
}
