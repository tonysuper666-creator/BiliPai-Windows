package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilipai.desktop.platform.DesktopWindowsImageSaveDirectory
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** One application/window lifetime, independent of account and popup visibility.
 * Its lock is taken after the existing account and save-owner locks. Retirement
 * releases this lock before the plugin store or account store is shut down.
 */
internal class DesktopImageSaveLifetime(private val isClosing: () -> Boolean) : AutoCloseable {
    private val lock = Any()
    @Volatile private var closed = false
    fun isActive(): Boolean = !closed && !isClosing()
    fun withCommit(block: () -> Unit): Boolean = synchronized(lock) {
        if (!isActive()) false else { block(); true }
    }
    override fun close() { synchronized(lock) { closed = true } }
}

/** Original custom-directory-first/default-on-write-failure policy. Windows file
 * URIs use the same global original preference key; Android content URIs remain
 * stored and fall back until the user chooses a Windows directory. KnownFolder
 * maps the Android album destination to the user's actual Pictures/BiliPai.
 * The caller owns its input, stage, cleanup and final account/save commit gate.
 */
internal class DesktopImageSaveLocations(
    private val preferences: DesktopImageSaveLocationPreferences,
    private val stillActive: () -> Boolean,
    private val withActiveCommit: ((() -> Unit) -> Boolean),
    private val resolveDefaultDirectory: () -> Result<Path> = DesktopWindowsImageSaveDirectory::resolveDefault,
) {
    fun isActive(): Boolean = stillActive()
    fun withCommit(block: () -> Unit): Boolean = withActiveCommit {
        if (!isActive()) throw CancellationException("图片保存设置已结束")
        block()
    }

    suspend fun save(
        fileName: String,
        checkpoint: suspend () -> Unit,
        withOwnedCommit: ((() -> Unit) -> Boolean),
        write: suspend (DesktopDynamicSaveTarget) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        require(Path.of(fileName).fileName.toString() == fileName && fileName.isNotBlank())
        checkpoint()
        val context = currentCoroutineContext()
        val saved = preferences.getImageSaveTreeUriSync()
        var customFailure: Exception? = null
        suspend fun attempt(directory: Path, createDefault: Boolean): Boolean {
            checkpoint()
            var selected: Path? = null
            val admitted = withOwnedCommit {
                context.ensureActive()
                // Canonicalize a user-selected or redirected base first, then keep
                // the product's existing no-link checks for actual file operations.
                val parent = if (createDefault) {
                    require(directory.isAbsolute && directory.fileName.toString() == "BiliPai")
                    val base = requireNotNull(directory.parent).toRealPath()
                    UpdateStorage.existingPathWithoutLinks(base)
                    require(Files.isDirectory(base, NOFOLLOW_LINKS))
                    val destination = base.resolve("BiliPai")
                    if (!Files.exists(destination, NOFOLLOW_LINKS)) Files.createDirectory(destination)
                    destination
                } else {
                    require(directory.isAbsolute)
                    directory.toRealPath()
                }
                UpdateStorage.existingPathWithoutLinks(parent)
                require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "图片保存目录不可用" }
                // Android MediaStore accepts duplicate display names. On Windows,
                // preserve the original name and add a suffix only for collisions.
                val dot = fileName.lastIndexOf('.')
                val stem = if (dot > 0) fileName.substring(0, dot) else fileName
                val extension = if (dot > 0) fileName.substring(dot) else ""
                var target = parent.resolve(fileName)
                var suffix = 1
                while (Files.exists(target, NOFOLLOW_LINKS)) {
                    require(suffix <= 10_000) { "图片文件名冲突过多" }
                    target = parent.resolve("$stem ($suffix)$extension")
                    suffix++
                }
                selected = target
            }
            if (!admitted) throw CancellationException("图片保存会话已结束")
            checkpoint()
            return write(DesktopDynamicSaveTarget(checkNotNull(selected), false))
        }
        if (!saved.isNullOrBlank()) {
            try {
                val uri = URI(saved)
                require(uri.scheme.equals("file", ignoreCase = true)) { "图片保存目录需重新选择" }
                if (attempt(Path.of(uri), false)) return@withContext true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                checkpoint()
                customFailure = failure
            }
        }
        try {
            checkpoint()
            attempt(resolveDefaultDirectory().getOrThrow(), true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            customFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
    }
}

internal val LocalDesktopImageSaveLocations = staticCompositionLocalOf<DesktopImageSaveLocations?> { null }
