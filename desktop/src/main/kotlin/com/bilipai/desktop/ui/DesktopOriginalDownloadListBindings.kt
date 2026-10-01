package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalDownloadListSettings
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.download.resolveDisplayedDownloadLocation
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path

/** A transient view of the existing queue. Its original tasks and persistence stay authoritative. */
class DesktopOriginalDownloadListBindings(
    val context: DesktopPluginContext,
    private val manager: DesktopDownloadManager,
    private val entryScope: CoroutineScope,
    private val stillOwned: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean),
    private val networkAvailable: () -> Boolean,
    private val feedback: (String) -> Unit,
) {
    val tasks: StateFlow<Map<String, DownloadTask>> = manager.tasks
        .map { queue -> queue.associate { it.id to it.item } }
        .stateIn(entryScope, SharingStarted.Eagerly, manager.tasks.value.associate { it.id to it.item })

    fun isOwned(): Boolean = entryScope.coroutineContext[Job]?.isActive == true && stillOwned()
    fun isNetworkAvailable(): Boolean = isOwned() && networkAvailable()
    fun showFeedback(message: String) { if (isOwned()) feedback(message) }

    private fun mutate(taskId: String, effect: () -> Unit) {
        if (!isOwned()) return
        withOwnedAdmission {
            if (!isOwned()) throw CancellationException("缓存列表已关闭")
            if (manager.tasks.value.any { it.id == taskId }) effect()
        }
    }
    fun pauseDownload(taskId: String) = mutate(taskId) { manager.pause(taskId) }
    fun startDownload(taskId: String) = mutate(taskId) { manager.resume(taskId) }
    /** Original confirmation explicitly removes video, audio and Danmaku assets. */
    fun removeTask(taskId: String) = mutate(taskId) { manager.remove(taskId, deleteFiles = true) }

    fun displayedDownloadLocation(defaultManagedPath: String, customManagedPath: String?, exportTreeUri: String?): String {
        val effective = runCatching { resolveDesktopOriginalDownloadDestination(customManagedPath, Path.of(defaultManagedPath)) }
            .getOrDefault(Path.of(defaultManagedPath))
        // Android SAF is not a Windows export capability. The real existing queue writes managed files.
        return resolveDisplayedDownloadLocation(defaultManagedPath, effective.toString(), exportTreeUri = null)
    }

    fun reportUnavailableLocation(customManagedPath: String?, exportTreeUri: String?) {
        if (!isOwned()) return
        if (!exportTreeUri.isNullOrBlank()) {
            showFeedback("旧 Android 授权导出目录在 Windows 不可用，当前缓存保存在实际下载目录")
        }
        try { resolveDesktopOriginalDownloadDestination(customManagedPath, DesktopDownloadManager.defaultDownloadRoot()) }
        catch (failure: IllegalArgumentException) { showFeedback(failure.message ?: "下载目录无效") }
    }
}

/** Validate only a Windows managed path; the same manager validates and owns actual publication. */
internal fun resolveDesktopOriginalDownloadDestination(customPath: String?, defaultRoot: Path): Path {
    if (customPath.isNullOrBlank()) return defaultRoot.toAbsolutePath().normalize()
    val raw = customPath.trim()
    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(raw)
    val drivePath = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(raw)
    require(scheme == null || drivePath) { "下载目录必须是 Windows 文件夹，旧 Android 授权 URI 在此不可用" }
    val path = try { Path.of(raw) } catch (_: java.nio.file.InvalidPathException) {
        throw IllegalArgumentException("下载目录不是有效的 Windows 文件夹")
    }
    require(path.isAbsolute) { "下载目录必须是绝对路径" }
    return path.toAbsolutePath().normalize()
}

/** Root video/download consumers use this same canonical setting immediately before enqueue. */
suspend fun desktopOriginalDownloadDestination(context: DesktopPluginContext, stillOwned: () -> Boolean): Path {
    currentCoroutineContext().ensureActive()
    if (!stillOwned()) throw CancellationException("下载页面或账号已失效")
    val custom = DesktopOriginalDownloadListSettings.getDownloadPath(context).first()
    val path = resolveDesktopOriginalDownloadDestination(custom, DesktopDownloadManager.defaultDownloadRoot())
    currentCoroutineContext().ensureActive()
    if (!stillOwned()) throw CancellationException("下载页面或账号已失效")
    return path
}
