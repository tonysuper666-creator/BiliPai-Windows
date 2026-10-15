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
    val fullscreen: Boolean
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

/** Keep original screen/control identity tied to its actual Root entry and Store.
 * Fullscreen/native-peer refresh may replace platform adapters while this same
 * backend and source remain live. Forward to the latest adapters without
 * disposing the original player or closing the borrowed backend. */
@Composable
internal fun rememberDesktopOriginalOfflinePlayerBindings(
    context: DesktopPluginContext,
    backend: DesktopOfflineTaskPlayerBinding,
    entryScope: CoroutineScope,
    preferences: DesktopOriginalDanmakuPreferences,
    presentation: DesktopDanmakuPresentationBinding,
    window: DesktopOfflineWindowEffects,
    surface: DesktopOriginalOfflineSurface,
    media: DesktopOfflineMediaEffects,
    feedback: (String) -> Unit,
): DesktopOriginalOfflinePlayerBindings {
    val latestPresentation = rememberUpdatedState(presentation)
    val latestWindow = rememberUpdatedState(window)
    val latestSurface = rememberUpdatedState(surface)
    val latestMedia = rememberUpdatedState(media)
    val latestFeedback = rememberUpdatedState(feedback)
    return remember(backend, entryScope, context, preferences) {
        DesktopOriginalOfflinePlayerBindings(context, backend, entryScope, preferences,
            DesktopDanmakuPresentationBinding { latestPresentation.value.currentPresentation() },
            object : DesktopOfflineWindowEffects {
                override val fullscreen: Boolean get() = latestWindow.value.fullscreen
                override fun applyFullscreen(fullscreen: Boolean) = latestWindow.value.applyFullscreen(fullscreen)
                override fun restoreCurrentRootChrome() = latestWindow.value.restoreCurrentRootChrome()
            },
            object : DesktopOriginalOfflineSurface {
                @Composable override fun Render(player: DesktopOfflineMpvControl, modifier: Modifier,
                    foreground: @Composable () -> Unit) {
                    latestSurface.value.Render(player, modifier, foreground)
                }
            },
            object : DesktopOfflineMediaEffects {
                override fun publish(payload: OfflineMiniPlayerPayload, player: DesktopOfflineMpvControl) =
                    latestMedia.value.publish(payload, player)
                override fun clearIfOwned(player: DesktopOfflineMpvControl) = latestMedia.value.clearIfOwned(player)
            }, { latestFeedback.value(it) })
    }
}

/** Navigation uses the visible entry's admission, not media readiness. A failed
 * or already retired native source must never trap the user in its screen. The
 * Root navigation command still enforces its own entry/account ownership. */
internal fun desktopOfflineNavigateBack(isCurrentEntry: () -> Boolean, ownsNativeSource: () -> Boolean,
    closePictureInPicture: () -> Unit, onBack: () -> Unit) {
    if (!isCurrentEntry()) return
    if (ownsNativeSource()) closePictureInPicture()
    if (isCurrentEntry()) onBack()
}

@Composable internal fun DesktopOriginalOfflinePlayerHost(
    taskId:String,
    bindings:DesktopOriginalOfflinePlayerBindings,
    onBack:()->Unit,
) {
    DesktopOfflineBackendLifetime(bindings.backend) {
        CompositionLocalProvider(LocalDesktopOriginalOfflineBindings provides bindings) {
            com.android.purebilibili.feature.download.OfflineVideoPlayerScreen(taskId,onBack)
        }
    }
}

@Composable
internal fun DesktopOfflineBackendLifetime(backend: DesktopOfflineTaskPlayerBinding, content: @Composable () -> Unit) {
    DisposableEffect(backend) { onDispose { backend.close() } }
    content()
}
