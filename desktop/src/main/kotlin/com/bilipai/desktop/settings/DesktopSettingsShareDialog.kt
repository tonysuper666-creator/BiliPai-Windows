package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppPopupSurface
import com.android.purebilibili.core.ui.AppPopupSurfaceKind
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.share.*
import com.bilipai.desktop.backup.DesktopBackupUpdateHold
import com.bilipai.desktop.ui.DesktopWindowsPlayerDialog
import com.bilipai.desktop.update.DesktopUpdater
import kotlinx.coroutines.*

private data class SettingsSharePreview(
    val inspection: DesktopSettingsShareInspection,
    val saved: SavedSettingsProfile? = null,
)
private enum class SettingsShareConfirmation { REPLACE, DELETE }

/** Original adaptive preference/dialog components on the existing owned Windows dialog.
 * JSON preview is an immutable session. Saved actions retain that same record/raw pair;
 * the service rechecks it in each real Store CAS replay, not against mutable UI text. */
@Composable
internal fun DesktopSettingsShareDialog(
    binding: DesktopSettingsShareBindings,
    updateHold: DesktopBackupUpdateHold,
    onOpenCompleteBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var operation by remember(binding) { mutableStateOf<Any?>(null) }
    var status by remember(binding) { mutableStateOf<String?>(null) }
    var profiles by remember(binding) { mutableStateOf<List<SavedSettingsProfile>>(emptyList()) }
    var preview by remember(binding) { mutableStateOf<SettingsSharePreview?>(null) }
    var showSkipped by remember(binding) { mutableStateOf(false) }
    var savePrompt by remember(binding) { mutableStateOf(false) }
    var name by remember(binding) { mutableStateOf("") }
    var confirmation by remember(binding) { mutableStateOf<SettingsShareConfirmation?>(null) }

    fun change(action: () -> Unit) { binding.tryCommit { if (operation == null) action() } }
    fun start(expected: () -> Boolean = { true }, work: suspend () -> Unit) {
        val ticket = Any()
        var accepted = false
        binding.tryCommit {
            if (operation == null && expected()) { operation = ticket; status = null; accepted = true }
        }
        if (!accepted) return
        // Launch/IO/native chooser and cancellation stay outside Root admission.
        scope.launch {
            try {
                val caller = currentCoroutineContext()
                caller.ensureActive(); binding.checkpoint()
                if (!updateHold.beginOperation(caller.job)) throw CancellationException("Update activation rejected settings edit")
                work()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                binding.tryCommit { if (operation === ticket) status = failure.message ?: "设置操作失败，请重试。" }
            } finally {
                binding.tryCommit { if (operation === ticket) operation = null }
            }
        }
    }
    suspend fun refreshProfiles() {
        val loaded = binding.service.listSavedProfiles()
        currentCoroutineContext().ensureActive()
        binding.commit { profiles = loaded }
    }
    fun setPreview(inspection: DesktopSettingsShareInspection, saved: SavedSettingsProfile? = null) {
        binding.commit {
            preview = SettingsSharePreview(inspection, saved)
            confirmation = null; showSkipped = false; savePrompt = false
        }
    }
    LaunchedEffect(binding) { start { refreshProfiles() } }

    DesktopWindowsPlayerDialog("设置共享与本机配置", onDismiss, preferredHeightDp = 680) {
        val window = LocalAwtWindow.current ?: return@DesktopWindowsPlayerDialog
        val busy = operation != null
        // DesktopWindowsPlayerDialog provides its own recorded background and scale.
        // The surface resolves the original material against this dialog's window only.
        AppPopupSurface(kind = AppPopupSurfaceKind.DIALOG, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppText("设置共享", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (preview == null) AppTextButton(enabled = !busy, onClick = { start { refreshProfiles() } }) { AppText("刷新") }
                    AppTextButton(onClick = onDismiss) { AppText("关闭") }
                }
                val notice = status
                if (busy || notice != null) {
                    AppText(if (busy) "正在处理…" else requireNotNull(notice),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                val captured = preview
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (captured == null) {
                        item {
                            AppPreferenceSectionTitle("设置文件")
                            AppPreferenceGroup {
                                AppPreference(title = "导出 JSON", subtitle = "分享当前可兼容的外观、播放、弹幕和导航设置",
                                    onClick = if (busy) null else ({ start {
                                        val artifact = withContext(Dispatchers.IO) {
                                            binding.service.buildExportArtifact("我的 Windows 设置", DesktopUpdater.packagedVersion())
                                        }
                                        val selected = binding.chooseFile(window, true, artifact.fileName)
                                        if (selected != null) {
                                            val capability = binding.files.select(selected, true)
                                            // The captured file fingerprint must remain exact until the
                                            // existing atomic publication gate.
                                            binding.files.writeUtf8(capability, artifact.json)
                                            binding.commit { status = "设置已导出：${selected.fileName}" }
                                        }
                                    } }))
                                AppPreferenceDivider(startIndent = 16.dp)
                                AppPreference(title = "导入 JSON", subtitle = "先查看内容，确认后再应用；账号和设备专属配置会跳过",
                                    onClick = if (busy) null else ({ start {
                                        val selected = binding.chooseFile(window, false)
                                        if (selected != null) {
                                            val inspection = withContext(Dispatchers.IO) {
                                                val session = binding.service.readImportSession(selected)
                                                binding.service.inspectImport(session)
                                            }
                                            setPreview(inspection)
                                        }
                                    } }))
                                AppPreferenceDivider(startIndent = 16.dp)
                                AppPreference(title = "完整数据备份", subtitle = "ZIP 备份设置、历史、队列和插件；恢复后退出客户端",
                                    onClick = if (busy) null else ({ change { onOpenCompleteBackup() } }))
                            }
                        }
                        item {
                            AppPreferenceSectionTitle("本机配置")
                            AppPreferenceGroup {
                                AppPreference(title = "保存当前配置", subtitle = "同名另存一份；已有配置不会自动覆盖",
                                    onClick = if (busy) null else ({ change { name = ""; savePrompt = true } }))
                                if (profiles.isEmpty()) {
                                    AppPreferenceDivider(startIndent = 16.dp)
                                    AppPreference(title = "尚无已保存配置", subtitle = "保存后可预览、恢复、显式替换或删除",
                                        showChevron = false)
                                }
                            }
                        }
                        if (savePrompt) {
                            item {
                                AppPreferenceGroup {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        AppTextField(name, { value -> change { name = value.take(80) } },
                                            label = "配置名称", placeholder = "例如：我的大屏布局")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            AppPrimaryButton("保存", enabled = !busy && name.isNotBlank(), onClick = {
                                                val capturedName = name
                                                start(expected = { savePrompt }) {
                                                    withContext(Dispatchers.IO) {
                                                        binding.service.saveCurrentProfile(capturedName, DesktopUpdater.packagedVersion())
                                                    }
                                                    refreshProfiles()
                                                    binding.commit { savePrompt = false; status = "配置已保存。" }
                                                }
                                            })
                                            AppTextButton(onClick = { change { savePrompt = false } }, enabled = !busy) { AppText("取消") }
                                        }
                                    }
                                }
                            }
                        }
                        items(profiles, key = { it.fileName }) { profile ->
                            AppPreferenceGroup {
                                AppPreference(title = profile.name, subtitle = "保存于 ${profile.exportedAtIso}",
                                    onClick = if (busy) null else ({ start {
                                        val inspection = withContext(Dispatchers.IO) {
                                            val session = binding.service.readSavedProfile(profile)
                                            binding.service.inspectImport(session)
                                        }
                                        setPreview(inspection, profile)
                                    } }))
                            }
                        }
                        item {
                            AppText("仅共享 Windows 已兼容的非隐私设置，不包含账号、保存路径或设备调试数据。设置以外的历史、队列和插件请使用“完整数据备份”。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        item {
                            AppPreferenceSectionTitle(if (captured.saved == null) "导入预览" else "配置预览")
                            AppPreferenceGroup {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SelectionContainer { AppText(buildImportPreviewSummary(captured.inspection.session)) }
                                    if (captured.inspection.baselineDanmakuKeys.isNotEmpty()) {
                                        AppText("弹幕设置会更新共享默认值；已有竖屏或横屏专属覆盖继续保留。",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (captured.inspection.skippedReasons.isNotEmpty()) {
                                        AppTextButton(enabled = !busy, onClick = {
                                            change { if (preview === captured) showSkipped = !showSkipped }
                                        }) { AppText(if (showSkipped) "收起跳过项目" else "查看跳过项目") }
                                        if (showSkipped) SelectionContainer {
                                            AppText(captured.inspection.skippedReasons.entries.joinToString("\n") { (key, reason) ->
                                                "$key：${if (reason == "invalid type or value") "值或类型无效" else "尚未兼容 Windows"}"
                                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (confirmation == null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            AppPrimaryButton(if (captured.saved == null) "应用设置" else "恢复配置",
                                                enabled = !busy && captured.inspection.session.preview.importableSections.isNotEmpty(),
                                                onClick = { start(expected = { preview === captured && confirmation == null }) {
                                                    val result = withContext(Dispatchers.IO) {
                                                        if (captured.saved == null) binding.service.applyImport(captured.inspection.session)
                                                        else binding.service.restoreSavedProfile(captured.saved, captured.inspection.session)
                                                    }
                                                    refreshProfiles()
                                                    binding.commit {
                                                        preview = null; status = "已应用 ${result.appliedKeys.size} 项设置，跳过 ${result.skippedKeys.size} 项。"
                                                    }
                                                } })
                                            AppTextButton(enabled = !busy, onClick = {
                                                change { if (preview === captured) preview = null }
                                            }) { AppText("返回") }
                                        }
                                        if (captured.saved != null) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                AppTextButton(enabled = !busy, onClick = { change {
                                                    if (preview === captured) { name = captured.saved.name; confirmation = SettingsShareConfirmation.REPLACE }
                                                } }) { AppText("用当前设置替换…") }
                                                AppTextButton(enabled = !busy, onClick = { change {
                                                    if (preview === captured) confirmation = SettingsShareConfirmation.DELETE
                                                } }) { AppText("删除…") }
                                            }
                                        }
                                    } else {
                                        val action = requireNotNull(confirmation)
                                        val saved = requireNotNull(captured.saved)
                                        AppText(if (action == SettingsShareConfirmation.REPLACE)
                                            "确认用当前设置替换「${saved.name}」？此操作不会改变当前播放或设置。"
                                            else "确认删除「${saved.name}」？当前正在使用的设置会保留。")
                                        if (action == SettingsShareConfirmation.REPLACE) {
                                            AppTextField(name, { value -> change { name = value.take(80) } }, label = "配置名称")
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            AppPrimaryButton(if (action == SettingsShareConfirmation.REPLACE) "确认替换" else "确认删除",
                                                enabled = !busy && (action == SettingsShareConfirmation.DELETE || name.isNotBlank()),
                                                onClick = {
                                                    val capturedName = name
                                                    start(expected = { preview === captured && confirmation == action }) {
                                                        withContext(Dispatchers.IO) {
                                                            if (action == SettingsShareConfirmation.REPLACE) {
                                                                binding.service.replaceSavedProfile(saved, captured.inspection.session,
                                                                    capturedName, DesktopUpdater.packagedVersion())
                                                            } else binding.service.deleteSavedProfile(saved, captured.inspection.session)
                                                        }
                                                        refreshProfiles()
                                                        binding.commit {
                                                            preview = null; confirmation = null
                                                            status = if (action == SettingsShareConfirmation.REPLACE) "配置已替换。" else "配置已删除。"
                                                        }
                                                    }
                                                })
                                            AppTextButton(enabled = !busy, onClick = {
                                                change { if (preview === captured) confirmation = null }
                                            }) { AppText("取消") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildImportPreviewSummary(session: SettingsShareImportSession): String {
    val sectionSummary = session.preview.importableSections
        .joinToString(separator = " / ") { it.label }
        .ifBlank { "无可导入分类" }
    val skippedCount = session.preview.skippedKeys.size
    val skippedSummary = if (skippedCount > 0) {
        "将跳过 $skippedCount 项本机专属或未知配置"
    } else {
        "没有需要跳过的项目"
    }
    return buildString {
        appendLine("配置名：${session.profile.profileName}")
        appendLine("来源版本：${session.profile.appVersion}")
        appendLine("导出时间：${session.profile.exportedAtIso}")
        appendLine("可导入分类：$sectionSummary")
        append(skippedSummary)
    }.trim()
}
