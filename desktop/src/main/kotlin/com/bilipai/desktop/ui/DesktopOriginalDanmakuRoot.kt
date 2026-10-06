package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalDanmakuSession
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.*
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean

/** The existing comment provider supplies transport, gallery, clipboard and account admission. */
@Composable internal fun DesktopOriginalDanmakuRootHost(
    owner:DesktopOriginalCommentRootOwner,
    repository:DesktopRepository,
    globalStore:DesktopPluginStore,
    overlay:DanmakuOverlay,
    cid:Long,
    sourceVersion:Long,
    sourceLease:DesktopOriginalVideoAcceptedPublication,
    stillOwned:()->Boolean,
    upOwnerUserHash:String?,
    isSending:Boolean,
    onSendSame:(String)->Unit,
    window:Window,
    presentation:DesktopDanmakuPresentation,
    viewport:DesktopDanmakuSettingsViewport,
    currentPositionMs:()->Long,
    seekFromUser:(Long)->Unit,
    showSettings:Boolean,
    showPool:Boolean,
    onShowPool:()->Unit,
    onDismissSettings:()->Unit,
    onDismissPool:()->Unit,
    enabledChangeVersion:Long,
    hotLink:DesktopWindowsHotDanmakuLink,
) {
    val capturedEpoch=owner.operations.expectedEpoch
    val account by repository.account.collectAsState()
    val card=LocalDesktopDynamicCardBindings.current
    key(owner,cid,sourceVersion,sourceLease,capturedEpoch,window) {
        // Keep callbacks within this exact source lease; a successor must not refresh an old job's owner.
        val currentStillOwned by rememberUpdatedState(stillOwned)
        val currentPosition by rememberUpdatedState(currentPositionMs)
        val currentSeek by rememberUpdatedState(seekFromUser)
        val admissionLock=remember{Any()}
        val alive=remember{AtomicBoolean(true)}
        val pageScope=remember{CoroutineScope(owner.scope.coroutineContext+SupervisorJob(owner.scope.coroutineContext[Job]))}
        fun owned()=alive.get()&&pageScope.isActive&&owner.isOwned()&&currentStillOwned()&&repository.sessionEpoch==capturedEpoch
        fun admission(commit:()->Unit)=owner.operations.withOwnedEditorImageAdmission {
            synchronized(admissionLock) {
                if(!owned())throw CancellationException("Danmaku page preference owner retired")
                commit()
                if(!owned())throw CancellationException("Danmaku page preference owner retired")
            }
        }
        val blocks=remember{DesktopDanmakuBlockPreferences(globalStore,::admission)}
        val preferences=remember{DesktopOriginalDanmakuPreferences(globalStore,blocks,::admission)}
        val platform=remember(card,window){DesktopDanmakuWindowsPlatform(owner,pageScope,::owned,card,window)}
        val actions=remember{object:DesktopDanmakuActions {
            override suspend fun getDanmakuThumbupState(cid:Long,dmid:Long)=owner.operations.getDanmakuThumbupState(cid,dmid)
            override suspend fun recallDanmaku(cid:Long,dmid:Long)=owner.operations.recallDanmaku(cid,dmid)
            override suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean)=owner.operations.likeDanmaku(cid,dmid,like)
            override suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String)=owner.operations.reportDanmaku(cid,dmid,reason,content)
        }}
        val environment=remember{DesktopDanmakuSessionEnvironment(cid,sourceVersion,capturedEpoch,pageScope,actions,
            ::owned,{repository.account.value?.mid?:0L},owner.feedback,{if(owned())currentSeek(it)})}
        val session=remember{DesktopOriginalDanmakuSession(environment)}
        val hotAttachment=remember{DesktopWindowsHotDanmakuAttachment(sourceLease,environment,session,preferences)}
        DisposableEffect(hotLink,hotAttachment) {
            hotLink.bind(hotAttachment)
            onDispose {hotLink.release(hotAttachment)}
        }
        DisposableEffect(pageScope,session) {onDispose {
            synchronized(admissionLock){alive.set(false)}
            session.close();pageScope.cancel()
        }}
        val settingsScope=presentation.originalScope()
        val settings by remember(preferences,settingsScope){preferences.getDanmakuSettings(settingsScope)}
            .collectAsState(preferences.currentSettings(settingsScope))
        val syncSnapshot by remember(preferences){preferences.getDanmakuCloudSyncEnabled()}.collectAsState(initial=null)
        val syncEnabled=syncSnapshot
        if(syncEnabled!=null) {
            // The original 700 ms queue/effect remains mounted when a transient settings panel closes.
            val cloudSync=rememberDesktopOriginalDanmakuCloudSyncBinding(settings,syncEnabled,account!=null,platform)
            LaunchedEffect(enabledChangeVersion) {
                if(enabledChangeVersion>0L&&owned())cloudSync.queueChange{previous->previous.copy(enabled=preferences.currentSettings(settingsScope).enabled)}
            }
            if(owned()) {
                DesktopOriginalDanmakuHost(overlay,environment,session,platform,blocks,settingsScope,showPool,
                    currentPosition(),{if(owned())onDismissPool()},{preferences.currentSettings(settingsScope).blockRulesRaw},
                    upOwnerUserHash,isSending,{text->if(owned())onSendSame(text)})
                if(showSettings)DesktopOriginalDanmakuSettingsHost(preferences,presentation,viewport,platform,
                    syncEnabled,account!=null,cloudSync,{if(owned())onShowPool()},{if(owned())onDismissSettings()})
            }
        }
    }
}
