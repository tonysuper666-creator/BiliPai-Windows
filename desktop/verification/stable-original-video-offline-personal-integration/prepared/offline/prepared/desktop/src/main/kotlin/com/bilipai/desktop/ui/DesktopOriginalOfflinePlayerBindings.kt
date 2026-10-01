package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.download.LocalDanmakuSource
import com.android.purebilibili.feature.download.OfflineMiniPlayerPayload
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path

/** Root's single native surface/foreground carrier. A SwingPanel behind a Compose Box is insufficient. */
internal interface DesktopOriginalOfflineSurface {
    @Composable fun Render(player:DesktopOfflineMpvControl, modifier:Modifier, foreground:@Composable ()->Unit)
}

/** Actual window-placement/chrome lease; Android phone orientation/system bars are not emulated. */
internal interface DesktopOfflineWindowEffects {
    fun applyFullscreen(fullscreen:Boolean)
    fun restoreCurrentRootChrome()
}

/** Required delegate to Root's existing SMTC/PIP owner, never another media session. */
internal interface DesktopOfflineMediaEffects {
    fun publish(payload:OfflineMiniPlayerPayload, player:DesktopOfflineMpvControl)
    fun clearIfOwned(player:DesktopOfflineMpvControl)
}

internal val LocalDesktopOriginalOfflineBindings = staticCompositionLocalOf<DesktopOriginalOfflinePlayerBindings?> { null }

/** Transient original UI view of the same queue and retained offline player. No task/player/store is created. */
internal class DesktopOriginalOfflinePlayerBindings(
    val context:DesktopPluginContext,
    val backend:DesktopOfflineTaskPlayerBinding,
    val entryScope:CoroutineScope,
    val preferences:DesktopOriginalDanmakuPreferences,
    val presentation:DesktopDanmakuPresentationBinding,
    val window:DesktopOfflineWindowEffects,
    val surface:DesktopOriginalOfflineSurface,
    val media:DesktopOfflineMediaEffects,
    val feedback:(String)->Unit,
) {
    init { require(entryScope.coroutineContext[Job]!=null) }
    val tasks:StateFlow<Map<String,DownloadTask>> = backend.manager.tasks
        .map { list -> list.associate { it.id to it.item } }
        .stateIn(entryScope,SharingStarted.Eagerly,backend.manager.tasks.value.associate { it.id to it.item })
    fun isOwned()=entryScope.isActive && backend.isOwned()
    fun control(taskId:String)=DesktopOfflineMpvControl(this,taskId)
    fun danmaku(taskId:String)=DesktopOfflineDanmakuControl(this,taskId)
    fun updatePlaybackPosition(taskId:String, positionMs:Long) {
        if(isOwned())backend.persistOriginal(taskId,positionMs)
    }
    suspend fun readLocalDanmaku(task:DownloadTask):LocalDanmakuSource {
        currentCoroutineContext().ensureActive()
        if(!isOwned())throw CancellationException("Offline entry retired")
        val current=backend.manager.tasks.value.firstOrNull {it.id==task.id}?.item
        if(current!=task)throw CancellationException("Offline task changed before local asset read")
        val files=backend.manager.offlineDanmaku(task.id)
        currentCoroutineContext().ensureActive()
        if(!isOwned() || backend.manager.tasks.value.firstOrNull {it.id==task.id}?.item!=task)
            throw CancellationException("Offline task changed during local asset read")
        return files?.let { LocalDanmakuSource(it.standardSegments.map(Path::toString),it.specialSegments.map(Path::toString)) }
            ?: LocalDanmakuSource()
    }
}

internal class DesktopOfflineDanmakuControl(
    private val bindings:DesktopOriginalOfflinePlayerBindings,
    private val taskId:String,
) {
    private fun owned()=bindings.isOwned() && bindings.backend.memory.current==taskId && bindings.backend.ownsAcceptedSource()
    fun updateSettings(settings:com.android.purebilibili.core.store.DanmakuSettings) {
        if(!owned())return
        bindings.backend.runPlayerAction {
            bindings.backend.overlay?.let {overlay -> overlay.applySettings(projectOriginalDanmakuRendererSettings(overlay.currentSettings,settings))}
        }
    }
    suspend fun loadLocalDanmaku(cid:Long,standardSegmentPaths:List<String>,specialSegmentPaths:List<String>) {
        currentCoroutineContext().ensureActive()
        if(!owned())return
        val task=bindings.backend.selectedTask(taskId) ?: return
        if(task.item.cid!=cid)throw CancellationException("Offline CID changed")
        // Root's same original parser/window actor owns publication. It takes the actual native version below.
        bindings.backend.loadOriginalDanmaku(taskId,standardSegmentPaths.map(Path::of),specialSegmentPaths.map(Path::of))
    }
    fun show(){if(owned())bindings.backend.setDanmakuEnabled(true)}
    fun hide(){if(owned())bindings.backend.setDanmakuEnabled(false)}
}

@Composable internal fun DesktopOriginalOfflinePlayerHost(
    taskId:String,
    bindings:DesktopOriginalOfflinePlayerBindings,
    onBack:()->Unit,
) {
    DisposableEffect(bindings){onDispose{bindings.backend.close()}}
    CompositionLocalProvider(LocalDesktopOriginalOfflineBindings provides bindings) {
        com.android.purebilibili.feature.download.OfflineVideoPlayerScreen(taskId,onBack)
    }
}
