package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.material3.*
import com.android.purebilibili.core.store.DesktopOriginalStorageSettings as SettingsManager
import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.settings.*
import kotlinx.coroutines.*
import java.nio.file.Path

/** Original section/options/confirmation/animation. Windows chooser changes only future queue admission. */
@Composable
internal fun DesktopStorageSettings(
    owner: DesktopStorageSettingsOwner, imagePath: String?, target: SettingsSearchTarget?,
    chooseDirectory: suspend () -> Path?, openDetail: (SettingsSearchTarget) -> Unit,
    onFailure: (Throwable) -> Unit, onMessage: (String) -> Unit,
) {
    val context=owner.context
    val path by remember(context) { SettingsManager.getDownloadPath(context) }.collectAsState(null)
    val interval by remember(context) { SettingsManager.getAutoCacheClearInterval(context) }.collectAsState(SettingsManager.AutoCacheClearInterval.NEVER)
    val threshold by remember(context) { SettingsManager.getAutoCacheClearThresholdGb(context) }.collectAsState(SettingsManager.DEFAULT_AUTO_CACHE_CLEAR_THRESHOLD_GB)
    var breakdown by remember(owner) { mutableStateOf<CacheUtils.CacheBreakdown?>(null) }
    var showDirectory by remember(target) { mutableStateOf(target==SettingsSearchTarget.DOWNLOAD_PATH) }
    var showClear by remember(target) { mutableStateOf(target==SettingsSearchTarget.CLEAR_CACHE) }
    var selected by remember { mutableStateOf(resolveDefaultCacheClearTargets()) }
    var progress by remember { mutableStateOf<CacheClearProgress?>(null) }
    val scope=rememberCoroutineScope() // Disposal cancels accepted UI callers; physical owner drains before restore.
    val latestFailure by rememberUpdatedState(onFailure)
    val latestMessage by rememberUpdatedState(onMessage)
    val latestChoose by rememberUpdatedState(chooseDirectory)
    fun report(block: suspend () -> Unit) { scope.launch {
        try { block() } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { if(owner.isActive())latestFailure(failure) }
    } }
    LaunchedEffect(owner) { try { breakdown=owner.breakdown() } catch(cancelled: CancellationException) {throw cancelled} catch(failure: Exception) {latestFailure(failure)} }
    DesktopOriginalDataStorageSection(path,imagePath,breakdown?.format() ?: "计算中…",interval,threshold,
        {openDetail(SettingsSearchTarget.SETTINGS_SHARE)}, {openDetail(SettingsSearchTarget.WEBDAV_BACKUP)},
        {showDirectory=true}, {openDetail(SettingsSearchTarget.IMAGE_SAVE_PATH)}, { selected=resolveDefaultCacheClearTargets();showClear=true },
        {next->report { owner.saveInterval(next) }},
        {next->report { owner.saveThreshold(next) }})
    if(showDirectory)AlertDialog(onDismissRequest={showDirectory=false},title={Text("下载目录")},
        text={Text("当前目录：${path ?: com.bilipai.desktop.download.DesktopDownloadManager.defaultDownloadRoot()}\n只影响以后加入的任务，现有下载保持原位置。")},
        confirmButton={TextButton(onClick={showDirectory=false;report {
            val selectedDirectory=latestChoose() ?: return@report
            currentCoroutineContext().ensureActive();owner.selectDownloadPath(selectedDirectory);latestMessage("已设置新任务的下载目录")
        }}){Text("选择文件夹")}},
        dismissButton={TextButton(onClick={showDirectory=false;report {owner.selectDownloadPath(null);latestMessage("已恢复默认下载目录")}}){Text("恢复默认")}})
    if(showClear)CacheClearConfirmDialog(breakdown,resolveSelectedCacheSizeSummary(breakdown,selected),resolveCacheClearOptions(),selected,
        {item,on->selected=if(on)selected+item else selected-item},
        onConfirm={
            val captured=selected.toSet();showClear=false
            if(captured.isNotEmpty())report {
                val before=owner.breakdown();progress=CacheClearProgress(0,1)
                try { owner.clear(captured);val after=owner.breakdown();breakdown=after
                    progress=CacheClearProgress(1,1,true,formatCacheClearBytes((before.reclaimableDiskSize-after.reclaimableDiskSize).coerceAtLeast(0)))
                } catch(failure: Throwable) {progress=null;throw failure}
            }
        }, onDismiss={showClear=false})
    progress?.let { capturedProgress ->
        DesktopWindowsCacheClearProgressHost(
            progress = capturedProgress,
            stillOwned = owner::isActive,
            isCurrent = { progress === capturedProgress },
            onDismiss = { if (owner.isActive() && progress === capturedProgress && capturedProgress.isComplete) progress = null },
        )
    }
}
