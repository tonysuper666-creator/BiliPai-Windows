package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.cast.DesktopGoogleCastDialog
import com.android.purebilibili.core.plugin.json.JsonRulePlugin
import com.android.purebilibili.feature.plugin.*
import com.bilipai.desktop.data.DesktopDiscoveryFilters
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import com.bilipai.desktop.plugins.DesktopPluginAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun PluginCenterScreen(runtime: DesktopPluginRuntime, onVideo: ((VideoCard) -> Unit)? = null,
    onPlayQueue: ((List<VideoCard>, VideoCard) -> Unit)? = null, onOpenJsPlugin: (String) -> Unit = {}) {
    val plugins by runtime.plugins.collectAsState()
    val jsonPlugins by runtime.jsonPlugins.collectAsState()
    val stats by runtime.jsonFilterStats.collectAsState()
    val config by runtime.store.feedFilterConfig.collectAsState()
    val enabled by runtime.store.feedFilterEnabled.collectAsState()
    val statisticsEnabled by DesktopPluginAnalytics.enabled.collectAsState()
    val skipRequests by DesktopPluginAnalytics.automaticSkipRequests.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var packagesOpen by remember { mutableStateOf(false) }
    var jsOpen by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JsonRulePlugin?>(null) }
    var previewUrl by remember { mutableStateOf("") }
    var jsonEditor by remember { mutableStateOf<String?>(null) }
    fun action(id: String, operation: suspend () -> Unit) {
        if (busy != null) return
        busy = id; error = null
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "插件操作失败" }
            finally { busy = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("插件", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selected = null; jsOpen = true }, enabled = busy == null) { Text("JS 插件") }
                OutlinedButton(onClick = { selected = null; packagesOpen = true }, enabled = busy == null) { Text("插件包与装扮") }
            }
        } }

        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (busy != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { DesktopWindowsVideoEnhancementSettingsContent() }
        items(plugins.filter { it.plugin.id != "anime4k" }, key = { it.plugin.id }) { info ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(info.plugin.name, style = MaterialTheme.typography.titleMedium)
                        Text(info.plugin.description, style = MaterialTheme.typography.bodySmall)
                        Text("${info.plugin.version} · ${info.plugin.author}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (info.plugin.id != runtime.dlnaCast.id) {
                        TextButton(onClick = { selected = info.plugin.id },
                            enabled = busy == null && (info.plugin.id != runtime.googleCast.id || info.enabled)) {
                            Text(if (info.plugin.id == runtime.googleCast.id) "设备与遥控" else "设置")
                        }
                    }
                    Switch(checked = info.enabled, onCheckedChange = { value -> action(info.plugin.id) { runtime.setEnabled(info.plugin.id, value) } }, enabled = busy == null && !info.plugin.unavailable)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PluginSwitch("记录本机空降请求统计", statisticsEnabled) { value -> action("local-statistics") { DesktopPluginAnalytics.setEnabled(value) } }
                Text("仅保存片段类别和请求次数", style = MaterialTheme.typography.bodySmall)
                if (statisticsEnabled) skipRequests.forEach { (category, count) -> Text("$category · $count 次自动跳过请求", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Text("JSON 规则", style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it; preview = null }, label = { Text("JSON 插件链接") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = busy == null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { val requested = url.trim(); action("json-preview") { preview = runtime.previewJsonUrl(requested).getOrThrow(); previewUrl = requested } }, enabled = busy == null && url.isNotBlank()) { Text("预览链接") }
                    OutlinedButton(onClick = { jsonEditor = "" }, enabled = busy == null) { Text("导入 JSON 文本") }
                }
                preview?.let { plugin ->
                    Text("${plugin.name} · ${plugin.version} · ${plugin.type} · ${plugin.rules.size} 条规则")
                    Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { action("json-import") { runtime.importJsonUrl(previewUrl).getOrThrow(); preview = null } }, enabled = busy == null) { Text("导入") }
                }
            }
        }
        items(jsonPlugins, key = { "json-" + it.plugin.id }) { loaded ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(loaded.plugin.name, style = MaterialTheme.typography.titleMedium)
                            Text("${loaded.plugin.type} · ${loaded.plugin.rules.size} 条规则 · 过滤 ${stats[loaded.plugin.id] ?: 0} 项", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(loaded.enabled, { runtime.setJsonEnabled(loaded.plugin.id, it) }, enabled = busy == null)
                    }
                    Text(loaded.plugin.description, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { jsonEditor = runtime.exportJsonPlugin(loaded.plugin.id) }, enabled = busy == null) { Text("编辑规则") }
                        TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(runtime.exportJsonPlugin(loaded.plugin.id)), null) }) { Text("复制 JSON") }
                        TextButton(onClick = { runtime.resetJsonStats(loaded.plugin.id) }) { Text("重置统计") }
                        TextButton(onClick = { runtime.removeJsonPlugin(loaded.plugin.id) }, enabled = busy == null) { Text("删除") }
                    }
                }
            }
        }
    }
    when (selected) {
        "bilipai_feed_filter" -> DiscoveryFilterDialog(DesktopDiscoveryFilters(enabled, config), runtime.store, { selected = null }, { selected = null })
        "danmaku_enhance" -> DanmakuPluginSettings(runtime, { selected = null })
        "eye_protection" -> EyePluginSettings(runtime, { selected = null })
        SPONSOR_BLOCK_PLUGIN_ID -> SponsorPluginSettings(runtime, { selected = null })
        runtime.googleCast.id -> DesktopGoogleCastDialog(runtime.context, runtime.googleCast, media = { null }, onDismiss = { selected = null })
        else -> selected?.let { DesktopAdditionalPluginSettings(it, runtime, { selected = null }, onVideo, onPlayQueue) }
    }
    jsonEditor?.let { content ->
        JsonTextPluginDialog(content, busy != null, { jsonEditor = null }) { text ->
            action("json-text") { runtime.importJsonText(text).getOrThrow(); jsonEditor = null }
        }
    }
    if (packagesOpen) PluginPackagesDialog(runtime.packages, onDismiss = { packagesOpen = false })
    if (jsOpen) DesktopJsPluginsDialog(runtime.jsPlugins, onOpenJsPlugin, onDismiss = { jsOpen = false })
}

@Composable
private fun JsonTextPluginDialog(initial: String, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("JSON 规则插件") }, text = {
        OutlinedTextField(text, { text = it }, modifier = Modifier.width(750.dp).heightIn(min = 280.dp, max = 650.dp), minLines = 12, maxLines = 26, enabled = !busy)
    }, confirmButton = { TextButton(onClick = { onSave(text) }, enabled = !busy && text.isNotBlank()) { Text("保存并导入") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun DanmakuPluginSettings(runtime: DesktopPluginRuntime, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf<DanmakuEnhanceConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) { try { config = runtime.configuration("danmaku_enhance")?.let { Json.decodeFromString<DanmakuEnhanceConfig>(it) } ?: DanmakuEnhanceConfig() } catch (e: Exception) { error = e.message } }
    PluginSettingsDialog("弹幕增强", busy, error, onDismiss, onSave = {
        val saving = config ?: return@PluginSettingsDialog
        busy = true; scope.launch { try { runtime.saveDanmakuConfiguration(saving); onDismiss() } catch (e: Exception) { error = e.message } finally { busy = false } }
    }) {
        config?.let { current ->
            PluginSwitch("开启屏蔽", current.enableFilter) { config = current.copy(enableFilter = it) }
            PluginSwitch("开启同传高亮", current.enableHighlight) { config = current.copy(enableHighlight = it) }
            PluginText("屏蔽关键词（逗号分隔）", current.blockedKeywords) { config = current.copy(blockedKeywords = it) }
            PluginText("屏蔽用户标识（逗号分隔）", current.blockedUserIds) { config = current.copy(blockedUserIds = it) }
            PluginText("高亮关键词（逗号分隔）", current.highlightKeywords) { config = current.copy(highlightKeywords = it) }
        } ?: CircularProgressIndicator()
    }
}

@Composable
private fun EyePluginSettings(runtime: DesktopPluginRuntime, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf<EyeProtectionConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) { try { config = runtime.configuration("eye_protection")?.let { Json.decodeFromString<EyeProtectionConfig>(it) } ?: EyeProtectionConfig() } catch (e: Exception) { error = e.message } }
    PluginSettingsDialog("夜间护眼", busy, error, onDismiss, onSave = {
        val saving = config ?: return@PluginSettingsDialog
        busy = true; scope.launch { try { runtime.eyeProtection.commitConfig(saving); onDismiss() } catch (e: Exception) { error = e.message } finally { busy = false } }
    }) {
        config?.let { current ->
            PluginSwitch("按夜间时段开启", current.nightModeEnabled) { config = current.copy(nightModeEnabled = it) }
            PluginSwitch("始终开启", current.forceEnabled) { config = current.copy(forceEnabled = it) }
            Text("开始 ${current.nightModeStartHour}:00")
            Slider(current.nightModeStartHour.toFloat(), { config = current.copy(nightModeStartHour = it.toInt()) }, valueRange = 0f..23f, steps = 22)
            Text("结束 ${current.nightModeEndHour}:00")
            Slider(current.nightModeEndHour.toFloat(), { config = current.copy(nightModeEndHour = it.toInt()) }, valueRange = 0f..23f, steps = 22)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeCarePreset.entries.filter { it != EyeCarePreset.CUSTOM }.forEach { preset ->
                    FilterChip(current.carePreset == preset, { config = runtime.eyeProtection.applyPreset(current, preset) }, { Text(when (preset) { EyeCarePreset.GENTLE -> "轻柔"; EyeCarePreset.BALANCED -> "均衡"; else -> "专注" }) })
                }
            }
            Text("亮度 ${(current.brightnessLevel * 100).toInt()}%")
            Slider(current.brightnessLevel, { config = runtime.eyeProtection.persistSliderToPreset(current.copy(brightnessLevel = it)) }, valueRange = 0.3f..1f)
            Text("暖色 ${(current.warmFilterStrength * 100).toInt()}%")
            Slider(current.warmFilterStrength, { config = runtime.eyeProtection.persistSliderToPreset(current.copy(warmFilterStrength = it)) }, valueRange = 0f..0.5f)
            PluginSwitch("播放时减弱滤镜", current.weakenDuringPlayback) { config = current.copy(weakenDuringPlayback = it) }
            PluginSwitch("休息提醒", current.usageReminderEnabled) { config = current.copy(usageReminderEnabled = it) }
            PluginSwitch("仅夜间提醒", current.remindOnlyDuringNight) { config = current.copy(remindOnlyDuringNight = it) }
            Text("每 ${current.usageDurationMinutes} 分钟提醒")
            Slider(current.usageDurationMinutes.toFloat(), { config = current.copy(usageDurationMinutes = it.toInt()) }, valueRange = 5f..120f, steps = 22)
        } ?: CircularProgressIndicator()
    }
}

@Composable
private fun SponsorPluginSettings(runtime: DesktopPluginRuntime, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf<SponsorBlockConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) { try { config = (runtime.configuration(SPONSOR_BLOCK_PLUGIN_ID)?.let { Json.decodeFromString<SponsorBlockConfig>(it) } ?: SponsorBlockConfig()).normalized() } catch (e: Exception) { error = e.message } }
    PluginSettingsDialog("空降助手", busy, error, onDismiss, onSave = {
        val saving = config ?: return@PluginSettingsDialog
        busy = true; scope.launch { try { runtime.saveSponsorConfiguration(saving); onDismiss() } catch (e: Exception) { error = e.message } finally { busy = false } }
    }) {
        config?.let { current ->
            PluginText("服务地址", current.serverBaseUrl) { config = current.copy(serverBaseUrl = it) }
            PluginSwitch("跳过时显示提示", current.skipToastEnabled) { config = current.copy(skipToastEnabled = it) }
            PluginSwitch("向社区记录已跳过片段", current.communityTrackingEnabled) { config = current.copy(communityTrackingEnabled = it) }
            PluginSwitch("允许手动提交社区片段", current.communityContributionEnabled) { config = current.copy(communityContributionEnabled = it) }
            Text("类别行为", style = MaterialTheme.typography.titleMedium)
            resolveSponsorBlockCategorySettings(current).forEach { category ->
                var open by remember(category.category) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(category.title); Text(category.description, style = MaterialTheme.typography.bodySmall) }
                    Box {
                        TextButton(onClick = { open = true }) { Text(category.behavior.label) }
                        DropdownMenu(open, { open = false }) {
                            SponsorBlockSegmentBehavior.entries.forEach { behavior -> DropdownMenuItem({ Text(behavior.label) }, {
                                config = current.copy(categoryBehaviorRaw = current.categoryBehaviorRaw + (category.category to behavior.name)); open = false
                            }) }
                        }
                    }
                }
            }
        } ?: CircularProgressIndicator()
    }
}

@Composable
fun PluginCareReminder(runtime: DesktopPluginRuntime) {
    val reminder by runtime.eyeProtection.careReminder.collectAsState()
    reminder?.let { item -> AlertDialog(onDismissRequest = { runtime.eyeProtection.dismissReminder() }, title = { Text(item.title) },
        text = { Column { Text(item.message); Text(item.suggestion) } },
        confirmButton = { TextButton(onClick = { runtime.eyeProtection.confirmRest() }) { Text("已休息") } },
        dismissButton = { TextButton(onClick = { runtime.eyeProtection.snoozeReminder() }) { Text("${runtime.eyeProtection.getSnoozeMinutes()} 分钟后提醒") } }) }
}

@Composable
private fun PluginSettingsDialog(title: String, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.width(650.dp).heightIn(max = 650.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content(); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable private fun PluginSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(checked, onChange) }
}
@Composable private fun PluginText(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
