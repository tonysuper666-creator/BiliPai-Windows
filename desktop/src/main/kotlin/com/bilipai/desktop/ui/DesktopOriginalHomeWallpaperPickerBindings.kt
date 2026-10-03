package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager
import com.android.purebilibili.feature.profile.DesktopOriginalHomeWallpaperViewModel
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** One modal's transient file leases and original wallpaper state. No Profile overview VM,
 * client, account cache or persistent store is constructed. Native chooser and IO use the
 * actual existing Root platform factory, with a stricter sheet child owner.
 */
internal class DesktopOriginalHomeWallpaperPickerBindings(
    val scope: CoroutineScope,
    val context: DesktopOriginalPlayerSettingsContext,
    val environment: DesktopProfileEnvironment,
    private val alive: AtomicBoolean,
    private val parentContext: DesktopOriginalPlayerSettingsContext,
    private val noticeActual: (String) -> Unit,
) : AutoCloseable {
    private val previewLock = Any()
    private val previews = linkedMapOf<Path, Any?>()
    private val previewParent = environment.platform.stateDirectory.toAbsolutePath().normalize().resolve("home_wallpaper")
    val viewModel = DesktopOriginalHomeWallpaperViewModel(environment, context)
    fun notice(message: String) {
        parentContext.commit { if (context.isCurrentForOriginalWrite()) noticeActual(message) }
    }

    suspend fun importPreview(uri: String, imageOnly: Boolean): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        context.requireCurrent()
        val file = if (imageOnly) environment.platform.importWallpaperImage(uri, previewParent.toFile())
            else environment.platform.importWallpaperMedia(uri, previewParent.toFile())
        val path = file.toPath().toAbsolutePath().normalize()
        require(path.parent == previewParent) { "Original wallpaper preview must remain in the owned Root import directory" }
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink)
        try {
            currentCoroutineContext().ensureActive()
            context.commit {
                environment.ensureOwned()
                synchronized(previewLock) {
                    check(alive.get()) { "Original wallpaper sheet retired" }
                    previews[path] = attrs.fileKey()
                }
            }
        } catch (failure: Throwable) {
            cleanup(path, attrs.fileKey())
            throw failure
        }
        // Registration completes in this same IO block. If prompt cancellation discards
        // the return value, disposal still owns the exact tracked file lease.
        file
    }

    /** Disposal cleans only this modal's exact imported file identity, also after retirement.
     * Copy the lease under its local lock; all filesystem IO is outside Source/Image gates.
     */
    fun releasePreview(file: File) {
        val path = file.toPath().toAbsolutePath().normalize()
        val lease = synchronized(previewLock) {
            if (!previews.containsKey(path)) return
            previews.remove(path)
        }
        cleanup(path, lease)
    }

    private fun cleanup(path: Path, fileKey: Any?) {
        require(path.parent == previewParent)
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                UpdateStorage.existingPathWithoutLinks(previewParent)
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isRegularFile && !attrs.isSymbolicLink && attrs.fileKey() == fileKey) Files.delete(path)
            }
        } catch (_: java.io.IOException) {
            // Windows can retain a native preview file until its media composition disposes.
            // Root runtime smoke must verify this cleanup path; it is not claimed as validated.
        }
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        scope.coroutineContext[Job]?.cancel()
    }

    /** Called by the actual composition disposal, after its nested preview composition retires.
     * An explicit dismiss first retires the request owner; it does not unlink a still rendered
     * native preview. The original imported-file DisposableEffect also releases its exact lease.
     */
    fun dispose() {
        close()
        val retired = synchronized(previewLock) { previews.toMap().also { previews.clear() } }
        retired.forEach { (path, key) -> cleanup(path, key) }
    }
}

@Composable
internal fun rememberDesktopOriginalHomeWallpaperPickerBindings(): DesktopOriginalHomeWallpaperPickerBindings {
    val services = LocalDesktopOriginalHomeSettingsRootServices.current
    val parentContext = LocalDesktopOriginalPlayerSettingsContext.current
    val scope = rememberCoroutineScope()
    val alive = remember { AtomicBoolean(true) }
    val owns = { alive.get() && scope.coroutineContext[Job]?.isActive == true && parentContext.isCurrentForOriginalWrite() }
    val admit: ((() -> Unit) -> Boolean) = { action ->
        var applied = false
        try {
            parentContext.commit { if (owns()) { action(); applied = true } }
            applied
        } catch (_: CancellationException) { false }
    }
    val context = remember(parentContext, scope) { DesktopOriginalPlayerSettingsContext(parentContext.pluginContext, owns, admit) }
    val environment = remember(services, context, scope) {
        services.createProfileEnvironment(scope, owns, admit) { uri ->
            DesktopOriginalPlaybackPreferenceOperation.run(context, owns) {
                DesktopOriginalHomeSettingsManager.setHomeWallpaperUri(context, uri)
            }
        }
    }
    return remember(scope, context, environment) { DesktopOriginalHomeWallpaperPickerBindings(scope, context, environment, alive, parentContext, services.notice) }
}
