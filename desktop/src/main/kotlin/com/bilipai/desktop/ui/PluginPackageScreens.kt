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
import coil3.compose.AsyncImage
import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.core.plugin.skin.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun PluginPackagesDialog(repository: DesktopPackageRepository, onDismiss: () -> Unit) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var kotlinPreview by remember { mutableStateOf<DesktopKotlinPackagePreview?>(null) }
    var granted by remember { mutableStateOf(emptySet<PluginCapability>()) }
    var skinPreview by remember { mutableStateOf<DesktopSkinPackagePreview?>(null) }
    var catalog by remember { mutableStateOf<SkinCatalog?>(null) }
    var search by remember { mutableStateOf("") }
    var personalBackgroundOnly by remember { mutableStateOf(false) }
    var playingVideo by remember { mutableStateOf<String?>(null) }
    fun importMode() = if (personalBackgroundOnly) UiSkinImportMode.PERSONAL_BACKGROUND_ONLY else UiSkinImportMode.FULL_SKIN
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null; message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "插件包操作失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) { try { repository.load() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error.message } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } },
        title = { Text("插件包与装扮") },
        text = {
            Column(Modifier.width(760.dp).heightIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(page) { listOf("Kotlin 插件包", "已安装装扮", "装扮目录").forEachIndexed { index, title ->
                    Tab(page == index, onClick = { page = index; if (index == 2 && catalog == null) action { catalog = repository.catalog() } }, text = { Text(title) })
                } }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
                when (page) {
                    0 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Button(onClick = { action { choosePluginPackage("bpplugin")?.let { kotlinPreview = repository.previewKotlin(it); granted = emptySet() } } }, enabled = !busy) { Text("预览本地 Kotlin 插件包") } }
                        kotlinPreview?.let { preview -> item {
                            val model = buildExternalPluginInstallPreview(preview.decision)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(model.title, style = MaterialTheme.typography.titleMedium); Text(model.subtitle)
                                Text(model.packageHashText, style = MaterialTheme.typography.bodySmall); Text(model.signerText)
                                Text(buildExternalPluginPayloadSummary(preview.original.payloadEntries))
                                resolvePluginCapabilityUiModels(preview.original.descriptor.manifest.capabilities).forEach { capability ->
                                    Row { Checkbox(capability.capability in granted, { value -> granted = if (value) granted + capability.capability else granted - capability.capability }, enabled = !busy)
                                        Column(Modifier.weight(1f)) { Text(capability.label); Text(capability.description, style = MaterialTheme.typography.bodySmall) } }
                                }
                                Button(onClick = { action { repository.installKotlin(preview, granted); kotlinPreview = null; message = "插件包已保存，当前不会运行" } },
                                    enabled = !busy && preview.decision is ExternalPluginInstallDecision.RequiresUserApproval) { Text("授权并保存") }
                            }
                        } }
                        items(state.kotlinPackages, key = { it.manifest.pluginId }) { installed ->
                            val model = buildInstalledExternalPluginUiModels(listOf(installed)).single()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(model.title, style = MaterialTheme.typography.titleMedium); Text(model.subtitle); Text(model.stateText)
                                Text(model.packageHashText, style = MaterialTheme.typography.bodySmall)
                                Text(model.grantedCapabilityLabels.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                                Row { TextButton(onClick = { action { repository.revokeKotlinAuthorization(installed.manifest.pluginId, installed.packageSha256); message = "已撤销此包版本的授权" } }, enabled = !busy) { Text("撤销授权") }
                                    TextButton(onClick = { action { repository.removeKotlin(installed.manifest.pluginId) } }, enabled = !busy) { Text("移除") } }
                            }
                        }
                    }
                    1 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Row { Checkbox(personalBackgroundOnly, { personalBackgroundOnly = it }, enabled = !busy); Text("仅导入个人页背景", Modifier.padding(top = 12.dp)) } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { action { choosePluginPackage("bpskin", "zip")?.let { skinPreview = repository.previewSkin(it, importMode()) } } }, enabled = !busy) { Text("导入本地装扮") }
                            OutlinedButton(onClick = { action { repository.selectSkin(null) } }, enabled = !busy && state.skin.enabled) { Text("恢复默认装扮") }
                        } }
                        items(state.skins, key = { it.installId }) { installed ->
                            val model = buildInstalledUiSkinPreview(installed, state.skin.activeSkin?.installId == installed.installId)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(model.title, style = MaterialTheme.typography.titleMedium); Text(model.subtitle); Text(model.assetSummaryText)
                                Text(model.sourceText); Text(model.licenseText); Text(model.officialAssetText)
                                Row { TextButton(onClick = { action { repository.selectSkin(installed.installId) } }, enabled = !busy) { Text("应用") }
                                    TextButton(onClick = { skinPreview = DesktopSkinPackagePreview(UiSkinPackagePreview(installed.manifest, installed.packageSha256, emptyList()), byteArrayOf(), installed.assetFiles) }, enabled = !busy) { Text("预览资源") }
                                    TextButton(onClick = { action { repository.deleteSkin(installed.installId) } }, enabled = !busy) { Text("删除") } }
                            }
                        }
                    }
                    2 -> {
                        OutlinedTextField(search, { search = it }, label = { Text("搜索装扮") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        catalog?.let { source ->
                            Text("${source.sourceRepo} · ${source.sourceBranch} · ${source.themes.size} 款", style = MaterialTheme.typography.bodySmall)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(source.themes.filter { search.isBlank() || it.name.contains(search.trim(), true) || it.id.contains(search.trim(), true) }, key = { it.id }) { entry ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AsyncImage(entry.previewUrl, null, Modifier.size(96.dp))
                                        Column(Modifier.weight(1f)) { Text(entry.name); entry.licenseNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                            TextButton(onClick = { action { skinPreview = repository.previewCatalog(entry, importMode()) } }, enabled = !busy && entry.preferredPackageUrl() != null) { Text("下载并预览") } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    skinPreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!busy) skinPreview = null },
            confirmButton = { if (preview.bytes.isNotEmpty()) Button(onClick = { action { repository.installSkin(preview); skinPreview = null; message = "装扮已保存并应用" } }, enabled = !busy) { Text("安装并应用") } },
            dismissButton = { TextButton(onClick = { skinPreview = null }, enabled = !busy) { Text("关闭预览") } },
            title = { Text(preview.original.manifest.displayName) }, text = {
                Column(Modifier.width(700.dp).heightIn(max = 700.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val model = buildUiSkinPackagePreview(preview.original)
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(model.packageHashText); Text(model.sourceText); Text(model.licenseText); Text(model.officialAssetText)
                    buildUiSkinImagePreviewItems(preview.previewAssets).forEach { asset ->
                        Text(asset.label)
                        if (asset.isVideo) TextButton(onClick = { playingVideo = asset.localPath.takeIf { playingVideo != asset.localPath } }) { Text(if (playingVideo == asset.localPath) "停止视频预览" else "播放视频预览") }
                        if (!asset.isVideo || playingVideo == asset.localPath) DesktopUiSkinAsset(asset.localPath, Modifier.fillMaxWidth().height(160.dp),
                            videoPlayMode = preview.original.manifest.motion.profileVideoPlayMode, onError = { failure = it }, onWarning = { message = it })
                    }
                }
            })
    }
}

private suspend fun choosePluginPackage(vararg extensions: String): java.nio.file.Path? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply { dialogTitle = "选择本地插件或装扮包"; fileFilter = FileNameExtensionFilter(extensions.joinToString(" / "), *extensions) }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
