package com.bilipai.desktop.backup

import com.android.purebilibili.feature.settings.webdav.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

data class DesktopBackupState(val snapshot: DesktopBackupSnapshot = DesktopBackupSnapshot(), val busy: Boolean = false,
    val message: String? = null, val error: Boolean = false, val backups: List<WebDavBackupEntry> = emptyList(),
    val restartRequired: Boolean = false)

class DesktopBackupCoordinator(private val store: DesktopBackupStore,
    private val scheduler: DesktopBackupScheduler = WindowsBackupScheduler(store.directory),
    private val beforeRestore: suspend () -> Unit = {},
    private val afterRestore: suspend () -> Unit = {},
    private val beforeOperation: suspend (Job) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis) {
    private val archive = DesktopBackupArchive(store.directory)
    private val service = WebDavBackupService(archive)
    private val operations = Mutex()
    private val initial = runCatching { store.read() }
    private val mutableState = MutableStateFlow(DesktopBackupState(snapshot = initial.getOrDefault(DesktopBackupSnapshot()),
        message = initial.exceptionOrNull()?.let { "无法读取已保存的 WebDAV 配置" }, error = initial.isFailure))
    val state = mutableState.asStateFlow()

    suspend fun configure(config: WebDavBackupConfig) = operation("配置已保存") {
        if (config.enabled) {
            require(shouldScheduleWebDavAutoBackup(config)) { "自动备份需要完整的服务器地址、用户名和密码" }
            validateServer(config)
            scheduler.install()
            try { store.save(DesktopBackupSnapshot(config, store.read().lastSuccessfulBackupMs)) }
            catch (failure: Exception) { runCatching { scheduler.uninstall() }; throw failure }
        } else {
            store.save(DesktopBackupSnapshot(config, store.read().lastSuccessfulBackupMs))
            scheduler.uninstall()
        }
        mutableState.update { it.copy(snapshot = store.read()) }
    }

    suspend fun testConnection(config: WebDavBackupConfig) = operation("WebDAV 连接成功") {
        validateServer(config); service.testConnection(config).getOrThrow()
    }

    suspend fun listBackups() = operation("远端备份列表已刷新") {
        val config = store.read().config; validateServer(config)
        val entries = service.listBackups(config).getOrThrow()
        mutableState.update { it.copy(backups = entries.sortedByDescending(WebDavBackupEntry::lastModifiedEpochMs)) }
    }

    suspend fun backupNow() = operation("设置备份已上传") { upload(store.read()) }

    suspend fun restoreLatest() = restoreOperation { restoreArchive, onCommitted ->
        val config = store.read().config; validateServer(config)
        WebDavBackupService(restoreArchive, onCommitted).restoreLatest(config).getOrThrow()
    }

    suspend fun exportLocal(target: Path) = operation("本地设置备份已导出") { Files.write(target, archive.create(clock())); Unit }
    suspend fun importLocal(source: Path) = restoreOperation { restoreArchive, onCommitted ->
        require(Files.size(source) <= 32L * 1024 * 1024) { "备份压缩包过大" }
        restoreArchive.restore(Files.readAllBytes(source), onCommitted)
    }

    private suspend fun restoreOperation(
        block: suspend (DesktopBackupArchive, () -> Unit) -> Unit
    ): Result<Unit> = operation("设置已恢复，请退出并重新打开客户端") {
        var committed = false
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        val restoreArchive = DesktopBackupArchive(store.directory) {
            // A cancelled download/validation must not retire the app or replace settings.
            operationContext.ensureActive()
            mutableState.update { it.copy(restartRequired = true) }
            runBlocking { beforeRestore() }
        }
        try {
            block(restoreArchive) { committed = true }
        } finally {
            // beforeRestore may dispose the initiating UI scope. Once files committed, exit must still run.
            if (committed) withContext(NonCancellable) { afterRestore() }
        }
    }

    /** Both the application and the scheduled EXE use this path and the same cross-process file lock. */
    suspend fun automaticBackupIfDue(): Result<Unit> = operation(null) {
        val snapshot = store.read()
        if (shouldScheduleWebDavAutoBackup(snapshot.config) &&
            clock() - snapshot.lastSuccessfulBackupMs >= TimeUnit.HOURS.toMillis(WEBDAV_AUTO_BACKUP_INTERVAL_HOURS - WEBDAV_AUTO_BACKUP_FLEX_HOURS)) upload(snapshot)
    }

    private suspend fun upload(snapshot: DesktopBackupSnapshot) {
        validateServer(snapshot.config)
        val entry = service.backupNow(snapshot.config).getOrThrow()
        val saved = snapshot.copy(lastSuccessfulBackupMs = clock())
        store.save(saved)
        mutableState.update { it.copy(snapshot = saved, backups = (listOf(entry) + it.backups).distinctBy(WebDavBackupEntry::href)) }
    }

    private suspend fun operation(success: String?, block: suspend () -> Unit): Result<Unit> = coroutineScope {
        // Reserve the actual operation before IO can publish its later busy state.
        currentCoroutineContext().ensureActive()
        if (!beforeOperation(currentCoroutineContext().job)) {
            return@coroutineScope Result.failure(IllegalStateException("更新安装已开始，暂时无法执行备份操作"))
        }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            if (!operations.tryLock()) return@withContext Result.failure(IllegalStateException("已有备份操作正在执行"))
            mutableState.update { it.copy(busy = true, message = null, error = false) }
            try {
                Files.createDirectories(store.directory)
                FileChannel.open(store.directory.resolve(".webdav-backup.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    val lock = runCatching { channel.tryLock() }.getOrNull() ?: error("另一客户端正在执行备份操作")
                    lock.use { block() }
                }
                mutableState.update { it.copy(message = success) }
                Result.success(Unit)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                // Do not show credentials or server URLs from OkHttp/parser errors.
                val message = failure.message.orEmpty()
                val status = Regex("HTTP\\s+(\\d{3})").find(message)?.value
                val visible = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    message.substringBefore("http", message).take(200).ifBlank { "备份操作失败" }
                else "备份操作失败" + (status?.let { "：$it" } ?: "，请检查网络与服务器配置")
                mutableState.update { it.copy(message = visible, error = true) }
                Result.failure(failure)
            } finally { mutableState.update { it.copy(busy = false) }; operations.unlock() }
        }
    }

    private fun validateServer(config: WebDavBackupConfig) {
        val url = config.baseUrl.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务器地址应使用纯 HTTP(S) 地址，账号密码请填在单独的输入框" }
    }
}
