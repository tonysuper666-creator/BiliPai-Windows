package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.settings.BlockedListContent
import com.android.purebilibili.feature.settings.buildBlockedListJsonFileName
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.data.DesktopBlockedUpRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/** Uses the original management content; Windows document picking and copying are explicit adapters. */
@Composable
fun DesktopBlockedListScreen(repository: DesktopBlockedUpRepository, onLogin: () -> Unit,
    modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val rows by repository.store.records.collectAsState()
    val latestRows by rememberUpdatedState(rows)
    val migrationError by repository.store.migrationError.collectAsState()
    var syncing by remember(repository) { mutableStateOf(false) }
    var refreshing by remember(repository) { mutableStateOf(false) }
    var fileBusy by remember(repository) { mutableStateOf(false) }
    var message by remember(repository) { mutableStateOf<String?>(null) }
    var error by remember(repository) { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(repository.store) { withContext(Dispatchers.IO) { repository.store.migrateLegacyDiscoveryMids() } }
    fun fileAction(action: suspend () -> String?) {
        if (fileBusy || syncing || refreshing) return
        fileBusy = true; error = null; message = null
        scope.launch { try { action()?.let { message = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure; message = null }
            finally { fileBusy = false } }
    }
    Column(modifier.fillMaxSize()) {
        migrationError?.let { notice -> Row(Modifier.padding(16.dp)) {
            Text(notice, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repository.store.migrateLegacyDiscoveryMids() } } }) { Text("重试迁移") }
        } }
        error?.let { CommunityFailure(it, onLogin) }
        if (fileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BlockedListContent(rows, syncingBlockedList = syncing || fileBusy, refreshingProfiles = refreshing,
            blockedListSyncMessage = message, modifier = Modifier.weight(1f).fillMaxWidth(),
            onSyncBlockedList = {
                if (!syncing && !refreshing && !fileBusy) {
                    syncing = true; error = null; message = "正在同步 B站黑名单…"
                    val epoch = repository.sessionEpoch
                    scope.launch { try {
                        repository.importFromBilibili(epoch).fold(onSuccess = { message = it.message }, onFailure = { error = it; message = null })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    finally { syncing = false } }
                }
            }, onRefreshProfiles = {
                if (!refreshing && !syncing && !fileBusy) {
                    refreshing = true; error = null; message = "正在刷新黑名单用户资料…"
                    val epoch = repository.sessionEpoch
                    scope.launch { try { message = repository.refreshBlockedUpProfiles(epoch).message }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure; message = null }
                        finally { refreshing = false } }
                }
            }, onShareBlockedList = { fileAction {
                val text = buildBlockedUpShareText(latestRows)
                withContext(Dispatchers.Swing) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
                "已复制黑名单分享文本，可粘贴给他人"
            } }, onExportBlockedListJson = { fileAction {
                val snapshot = latestRows.toList()
                val target = chooseBlockedListFile(true, buildBlockedListJsonFileName())
                if (target == null) null else { withContext(Dispatchers.IO) { Files.writeString(target, buildBlockedUpShareJson(snapshot)) }
                    "已导出 ${snapshot.size} 个黑名单用户到 JSON 文件" }
            } }, onImportBlockedListJsonRequest = { fileAction {
                val target = chooseBlockedListFile(false)
                if (target == null) null else repository.importBlockedUps(withContext(Dispatchers.IO) { readDesktopBlockedImportFile(target) }).message
            } }, onImportBlockedList = { text -> fileAction { repository.importBlockedUps(text).message } },
            onUnblock = { mid ->
                if (!fileBusy && !syncing && !refreshing) {
                    fileBusy = true; error = null; message = null
                    val epoch = repository.sessionEpoch
                    scope.launch { try { message = repository.unblockUpWithBilibiliSync(mid, expectedSessionEpoch = epoch).message }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure; message = null }
                        finally { fileBusy = false } }
                }
            })
    }
}

internal fun readDesktopBlockedImportFile(path: Path): String {
    require(Files.isRegularFile(path) && Files.size(path) <= 8L * 1024 * 1024) { "请选择不超过 8MB 的黑名单 JSON 或文本文件" }
    return Files.readString(path)
}

private suspend fun chooseBlockedListFile(save: Boolean, name: String = ""): Path? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        dialogTitle = if (save) "导出黑名单 JSON" else "导入黑名单 JSON 或文本"
        fileFilter = FileNameExtensionFilter("黑名单 JSON / 文本", "json", "txt")
        if (save) selectedFile = java.io.File(name)
    }
    val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return@withContext null
    val target = chooser.selectedFile.toPath()
    if (save && Files.exists(target) && JOptionPane.showConfirmDialog(null, "覆盖所选文件？", "导出黑名单",
        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) null else target
}

/** Space/comment/related-video callers pass the real author metadata and the original relation source. */
@Composable
internal fun DesktopBlockedUpAction(repository: DesktopBlockedUpRepository, mid: Long, name: String, face: String,
    onLogin: () -> Unit, source: BlockedUpRelationSource = BlockedUpRelationSource.PROFILE,
    remoteBlocked: Boolean = false, onChanged: () -> Unit = {}) {
    if (mid <= 0) return
    val blocked by repository.store.mids.collectAsState()
    val scope = rememberCoroutineScope()
    var pending by remember(repository, mid) { mutableStateOf<Pair<Boolean, Long>?>(null) }
    var busy by remember(repository, mid) { mutableStateOf(false) }
    var message by remember(repository, mid) { mutableStateOf<String?>(null) }
    var error by remember(repository, mid) { mutableStateOf<Throwable?>(null) }
    Column {
        TextButton(enabled = !busy, onClick = { pending = (!(mid in blocked || remoteBlocked)) to repository.sessionEpoch }) {
            Text(if (mid in blocked || remoteBlocked) "解除屏蔽" else "屏蔽 UP 主")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { CommunityFailure(it, onLogin) }
    }
    pending?.let { (blocking, epoch) -> AlertDialog(onDismissRequest = { if (!busy) pending = null },
        title = { Text(if (blocking) "屏蔽 UP 主" else "解除屏蔽") },
        text = { Text(if (blocking) "屏蔽后将减少该 UP 主的内容，并尝试同步 B站黑名单。" else "解除本地屏蔽，并尝试同步 B站黑名单。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            if (!busy) {
                busy = true; error = null
                scope.launch { try {
                    val result = if (blocking) repository.blockUpWithBilibiliSync(mid, name, face, source, epoch)
                        else repository.unblockUpWithBilibiliSync(mid, source, epoch)
                    message = result.message; pending = null; onChanged()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure; pending = null }
                finally { busy = false } }
            }
        }) { Text(if (busy) "处理中…" else if (blocking) "屏蔽" else "解除") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } }) }
}
