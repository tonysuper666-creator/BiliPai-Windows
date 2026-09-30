package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.js.*
import com.android.purebilibili.feature.plugin.js.*
import com.android.purebilibili.feature.settings.resolvePluginCapabilityUiModels
import com.bilipai.desktop.plugins.js.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun DesktopJsPluginsDialog(repository: DesktopJsPluginRepository, onOpenPlugin: (String) -> Unit,
    onDismiss: () -> Unit) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<DesktopJsPluginPreview?>(null) }
    var granted by remember { mutableStateOf(emptySet<PluginCapability>()) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "JS 插件操作失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) {
        try { repository.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } },
        title = { Text("JS 插件") }, text = {
            Column(Modifier.width(720.dp).heightIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) DesktopLoadingIndicator(size = 28.dp)
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { action {
                    val path = withContext(Dispatchers.Swing) {
                        val chooser = JFileChooser().apply { dialogTitle = "选择 JS 插件"; fileFilter = FileNameExtensionFilter("BiliPai JS 插件", "js") }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
                    }
                    path?.let { preview = repository.preview(it); granted = emptySet() }
                } }, enabled = !busy) { Text("预览本地 JS 插件") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.plugins, key = { it.installed.manifest.id }) { item ->
                        val installed = item.installed
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(installed.manifest.title, style = MaterialTheme.typography.titleMedium)
                                Text("${installed.manifest.author} · ${installed.manifest.version}")
                                Text(installed.manifest.description)
                                if (!item.authorizationMatches) Text("脚本或声明缺少匹配的批准记录，需要重新导入预览。", color = MaterialTheme.colorScheme.error)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Switch(installed.enabled && item.authorizationMatches,
                                        { enabled -> action { repository.setEnabled(installed.manifest.id, enabled) } },
                                        enabled = !busy && item.authorizationMatches)
                                    TextButton(onClick = { onOpenPlugin(installed.manifest.id); onDismiss() },
                                        enabled = !busy && installed.enabled && item.authorizationMatches) { Text("打开模块") }
                                    TextButton(onClick = { action { repository.remove(installed.manifest.id) } }, enabled = !busy) { Text("移除") }
                                }
                            }
                        }
                    }
                }
            }
        })
    preview?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text(candidate.manifest.title) },
            confirmButton = { Button(onClick = { action { repository.install(candidate, granted); preview = null } }, enabled = !busy) { Text("批准所选权限并安装") } },
            dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("取消") } }, text = {
                LazyColumn(Modifier.width(650.dp).heightIn(max = 650.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(candidate.manifest.description); Text("${candidate.manifest.author} · ${candidate.manifest.version}"); Text(candidate.scriptSha256, style = MaterialTheme.typography.bodySmall) }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(resolvePluginCapabilityUiModels(candidate.manifest.permissions), key = { it.capability.name }) { capability ->
                        Row {
                            Checkbox(capability.capability in granted, { checked -> granted = if (checked) granted + capability.capability else granted - capability.capability }, enabled = !busy)
                            Column(Modifier.weight(1f)) { Text(capability.label); Text(capability.description, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    item { Text("安装后默认关闭；启用后打开模块才执行脚本。", style = MaterialTheme.typography.bodySmall) }
                }
            })
    }
}

/** This owns the module list, never the player. Root retains and releases the original launch request. */
@Composable
fun DesktopJsPluginContentScreen(repository: DesktopJsPluginRepository, pluginId: String,
    onPlayMedia: (launchId: String, executionRevision: Long) -> Unit,
    onFeedModule: (pluginId: String, moduleId: String) -> Unit,
    modifier: Modifier = Modifier) {
    val state by repository.state.collectAsState()
    val revision by repository.host.executionRevision.collectAsState()
    val record = state.plugins.firstOrNull { it.installed.manifest.id == pluginId }
    val installed = record?.installed
    var selectedId by remember(pluginId) { mutableStateOf<String?>(null) }
    val module = installed?.manifest?.modules?.firstOrNull { it.id.ifBlank { it.functionName } == selectedId }
        ?: installed?.manifest?.modules?.firstOrNull()
    val values = remember(pluginId, module, revision) { mutableStateMapOf<String, String>().apply {
        module?.let { putAll(resolveBiliPaiJsInitialParamValues(it.params, readBiliPaiJsParamValues(repository.context, pluginId, it))) }
    } }
    var reload by remember(pluginId) { mutableIntStateOf(0) }
    var busy by remember(pluginId) { mutableStateOf(false) }
    var error by remember(pluginId) { mutableStateOf<String?>(null) }
    var media by remember(pluginId) { mutableStateOf(emptyList<BiliPaiJsMediaItem>()) }
    val loadGeneration = remember(pluginId) { java.util.concurrent.atomic.AtomicLong() }
    LaunchedEffect(repository) {
        try { repository.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "JS 插件安装记录无法读取" }
    }
    LaunchedEffect(pluginId, module, revision, reload, record?.authorizationMatches, installed?.enabled) {
        val generation = loadGeneration.incrementAndGet()
        media = emptyList(); error = null
        if (module == null || installed?.enabled != true || record?.authorizationMatches != true) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        try {
            persistBiliPaiJsParamValues(repository.context, pluginId, module, values.toMap())
            if (module.kind.equals("feed", true)) onFeedModule(pluginId, module.id.ifBlank { module.functionName })
            else media = repository.host.loadModuleItems(pluginId, module.id.ifBlank { module.functionName }, buildParamsJson(module, values.toMap()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "JS 插件模块执行失败" }
        finally { if (loadGeneration.get() == generation) busy = false }
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(installed?.manifest?.title ?: "JS 插件不存在", style = MaterialTheme.typography.headlineSmall)
            if (installed?.enabled != true || record?.authorizationMatches != true) Text("请先在插件中心批准当前脚本并启用。", color = MaterialTheme.colorScheme.error)
        }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            installed?.manifest?.modules?.forEach { candidate ->
                FilterChip(module == candidate, onClick = { selectedId = candidate.id.ifBlank { candidate.functionName } }, label = { Text(candidate.title) })
            }
        } }
        module?.params?.let { parameters -> items(parameters, key = { it.name }) { param ->
            OutlinedTextField(values[param.name] ?: param.defaultValue, { values[param.name] = it }, label = { Text(param.title) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { reload++ }, enabled = !busy && installed?.enabled == true && record?.authorizationMatches == true) { Text("重新加载") }
                if (busy) DesktopLoadingIndicator(size = 28.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        itemsIndexed(flattenMediaItems(media), key = { index, item -> resolveBiliPaiJsMediaItemLazyKey(index, item) }) { _, item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium); Text(item.description)
                    val streams = resolveBiliPaiJsMediaStreams(item)
                    if (streams.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        streams.forEachIndexed { index, stream ->
                            TextButton(onClick = {
                                try { onPlayMedia(repository.host.createExternalLaunch(pluginId, item, index), revision) }
                                catch (failure: Exception) { error = failure.message ?: "JS 外部媒体无法打开" }
                            }, enabled = !busy) { Text(stream.title.ifBlank { "线路 ${index + 1}" }) }
                        }
                    }
                }
            }
        }
    }
}
