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
    var remoteUrl by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<Job?>(null) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        operation = scope.launch {
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
                        val chooser = JFileChooser().apply { dialogTitle = "选择 JS 插件"; fileFilter = FileNameExtensionFilter("BiliPai JS 插件 / 布局", "js", "bplayout") }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
                    }
                    path?.let {
                        if (it.fileName.toString().endsWith(".bplayout", ignoreCase = true)) repository.importLayoutPreset(it)
                        else { preview = repository.preview(it); granted = emptySet() }
                    }
                } }, enabled = !busy) { Text("预览本地 JS 插件") }
                OutlinedTextField(remoteUrl, { remoteUrl = it }, label = { Text("远程 JS 插件链接") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { action { preview = repository.previewRemote(remoteUrl); granted = emptySet() } },
                        enabled = !busy && remoteUrl.isNotBlank()) { Text("下载并预览") }
                    if (busy) TextButton(onClick = { operation?.cancel() }) { Text("取消操作") }
                }
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
                    item { Text(candidate.manifest.description); Text("${candidate.manifest.author} · ${candidate.manifest.version}");
                        candidate.sourceUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(candidate.scriptSha256, style = MaterialTheme.typography.bodySmall) }
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
    modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val revision by repository.host.executionRevision.collectAsState()
    val installed by repository.state.collectAsState()
    val record = installed.plugins.firstOrNull { it.installed.manifest.id == pluginId }
    val context = LocalDesktopHomeEnvironment.current
    val homeSettings by context.settings.homeSettings.collectAsState()
    val scope = rememberCoroutineScope()
    val share = LocalDesktopTextShareBindings.current
    val gallery = LocalDesktopDynamicCardBindings.current
    val alive = remember(repository, pluginId, revision) { java.util.concurrent.atomic.AtomicBoolean(true) }
    fun owned() = alive.get() && gallery.isOwned() && repository.host.executionRevision.value == revision &&
        record?.authorizationMatches == true && record.installed.enabled
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    val authorizedGallery = remember(gallery, repository, pluginId, revision, alive) {
        object : DesktopDynamicCardPlatform by gallery { override fun isOwned() = owned() }
    }
    val images = remember(repository, pluginId, revision, alive) {
        DesktopJsImageBindings(repository, pluginId, revision,
            { owned() && PluginCapability.NETWORK in (record?.installed?.grantedCapabilities ?: emptySet()) })
    }
    CompositionLocalProvider(LocalDesktopJsImageBindings provides images,
        LocalDesktopDynamicCardBindings provides authorizedGallery) {
        key(pluginId, revision) { BiliPaiJsPluginContentScreen(repository, pluginId, onBack, onPlayMedia, onFeedModule,
            homeSettings.pinchToChangeGridColumnsEnabled,
            shareLayout = { title, text, stillOwned -> requestDesktopTextShare(share, scope, title, text,
                { owned() && stillOwned() }, context.feedback) }, modifier = modifier) }
    }
}

/** The upstream params JSON carries strings for every declared type; enum selection preserves that. */
@Composable
private fun DesktopJsParameter(param: BiliPaiJsParam, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean) {
    if (param.type.equals("enum", true) && param.options.isNotEmpty()) {
        var expanded by remember(param) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            Text(param.title, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                    Text(param.options.firstOrNull { it.value == value }?.title ?: value.ifBlank { "请选择" })
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    param.options.forEach { option -> DropdownMenuItem(text = { Text(option.title) },
                        onClick = { onValueChange(option.value); expanded = false }, enabled = enabled) }
                }
            }
            if (param.options.none { it.value == value }) {
                // Unknown previously saved values stay editable rather than silently becoming the first option.
                OutlinedTextField(value, onValueChange, label = { Text("自定义值") }, singleLine = true,
                    enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
        }
    } else OutlinedTextField(value, onValueChange, label = { Text(param.title) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth())
}
