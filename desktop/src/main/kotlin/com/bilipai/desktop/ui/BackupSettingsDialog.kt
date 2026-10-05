package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.android.purebilibili.feature.settings.webdav.WebDavBackupConfig
import com.bilipai.desktop.backup.DesktopBackupCoordinator
import com.bilipai.desktop.backup.DesktopBackupUpdateHold
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

enum class DesktopBackupSettingsSection { ALL, LOCAL_SETTINGS, WEBDAV }

@Composable
internal fun BackupSettingsDialog(backup: DesktopBackupCoordinator, onDismiss: () -> Unit, onExit: () -> Unit = onDismiss,
    updateHold: DesktopBackupUpdateHold,
    initialSection: DesktopBackupSettingsSection = DesktopBackupSettingsSection.ALL) {
    val instance = remember(backup, updateHold) { Any() }
    var admitted by remember(instance) { mutableStateOf(false) }
    DisposableEffect(instance) {
        admitted = updateHold.mountEditor(instance)
        onDispose { updateHold.unmountEditor(instance) }
    }
    if (!admitted) return
    val state by backup.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(state.snapshot.config.baseUrl) }
    var username by remember { mutableStateOf(state.snapshot.config.username) }
    var password by remember { mutableStateOf(state.snapshot.config.password) }
    var directory by remember { mutableStateOf(state.snapshot.config.remoteDir) }
    var enabled by remember { mutableStateOf(state.snapshot.config.enabled) }
    var restoreConfirmation by remember { mutableStateOf(false) }
    var localRestore by remember { mutableStateOf<Path?>(null) }
    fun config() = WebDavBackupConfig(url, username, password, directory, enabled)
    fun choose(save: Boolean): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = if (save) "导出 Windows 设置备份" else "选择 Windows 设置备份"
            fileFilter = FileNameExtensionFilter("Windows 设置备份 (*.zip)", "zip")
            if (save) selectedFile = java.io.File("BiliPai-Windows-settings.zip")
        }
        val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        return chooser.selectedFile?.toPath()?.takeIf { result == JFileChooser.APPROVE_OPTION }
            ?.let { if (save && !it.fileName.toString().endsWith(".zip", true)) it.resolveSibling("${it.fileName}.zip") else it }
    }
    Dialog(onDismissRequest = { if (!state.busy && !state.restartRequired) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(740.dp).heightIn(max = 800.dp).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (initialSection) {
                    DesktopBackupSettingsSection.ALL -> "WebDAV 与设置备份"
                    DesktopBackupSettingsSection.LOCAL_SETTINGS -> "Windows 设置备份"
                    DesktopBackupSettingsSection.WEBDAV -> "WebDAV 云备份"
                }, style = MaterialTheme.typography.headlineSmall)
                Text("备份 Windows 本地设置、历史、听视频队列和插件。账号凭证、WebDAV 密码与下载的视频不写入备份。恢复成功后客户端将退出，再次打开即可生效。",
                    style = MaterialTheme.typography.bodyMedium)
                if (initialSection != DesktopBackupSettingsSection.LOCAL_SETTINGS) {
                OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(directory, { directory = it }, label = { Text("远端目录") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Row {
                    Switch(enabled, { enabled = it }, enabled = !state.busy)
                    Text("每天自动备份（Windows 登录期间，关闭客户端后仍可执行）", Modifier.padding(start = 12.dp, top = 12.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { scope.launch { backup.configure(config()) } }, enabled = !state.busy) { Text("保存配置") }
                    OutlinedButton(onClick = { scope.launch { backup.testConnection(config()) } }, enabled = !state.busy) { Text("测试连接") }
                    OutlinedButton(onClick = { scope.launch { backup.backupNow() } }, enabled = !state.busy && state.snapshot.config.baseUrl.isNotBlank()) { Text("立即备份") }
                    OutlinedButton(onClick = { scope.launch { backup.listBackups() } }, enabled = !state.busy && state.snapshot.config.baseUrl.isNotBlank()) { Text("远端列表") }
                    OutlinedButton(onClick = { localRestore = null; restoreConfirmation = true }, enabled = !state.busy && state.backups.isNotEmpty()) { Text("恢复最新备份") }
                }
                }
                if (initialSection != DesktopBackupSettingsSection.WEBDAV) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { choose(true)?.let { scope.launch { backup.exportLocal(it) } } }, enabled = !state.busy) { Text("导出本地备份") }
                    TextButton(onClick = { choose(false)?.let { localRestore = it; restoreConfirmation = true } }, enabled = !state.busy) { Text("从文件恢复") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                if (initialSection != DesktopBackupSettingsSection.LOCAL_SETTINGS && state.snapshot.lastSuccessfulBackupMs > 0) Text("上次备份：" + formatBackupTime(state.snapshot.lastSuccessfulBackupMs))
                if (initialSection != DesktopBackupSettingsSection.LOCAL_SETTINGS) state.backups.forEach { entry -> Text("${entry.fileName}  ·  ${entry.sizeBytes / 1024} KB  ·  ${formatBackupTime(entry.lastModifiedEpochMs)}") }
                TextButton(onClick = if (state.restartRequired) onExit else onDismiss, enabled = !state.busy) { Text(if (state.restartRequired) "退出客户端" else "关闭") }
            }
        }
    }
    if (restoreConfirmation) AlertDialog(onDismissRequest = { restoreConfirmation = false },
        title = { Text("恢复设置备份") }, text = { Text("备份中的设置、历史与队列会覆盖当前本地文件。恢复成功后客户端将退出，请再次打开。" +
            (localRestore?.let { "\n文件：${it.fileName}" } ?: "\n使用远端最新备份。")) },
        confirmButton = { Button(onClick = {
            val selected = localRestore; restoreConfirmation = false
            scope.launch { if (selected == null) backup.restoreLatest() else backup.importLocal(selected) }
        }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreConfirmation = false }) { Text("取消") } })
}

private fun formatBackupTime(value: Long) = if (value <= 0) "未知时间" else
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
