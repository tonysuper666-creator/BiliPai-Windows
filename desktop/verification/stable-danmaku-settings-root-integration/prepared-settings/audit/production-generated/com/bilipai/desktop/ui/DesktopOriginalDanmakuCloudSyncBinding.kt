package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.data.repository.DanmakuCloudSyncSettings
import com.android.purebilibili.feature.video.danmaku.*
import kotlinx.coroutines.ensureActive

internal class DesktopDanmakuCloudSyncBinding(
    val uiState:DanmakuCloudSyncUiState,
    val queueChange:((DanmakuCloudSyncSettings)->DanmakuCloudSyncSettings)->Unit,
    val requestNow:()->Unit,
    val onEnabledChange:(Boolean)->Unit,
)
/** Mount under the existing player/page owner, outside the transient settings popup. */
@Composable internal fun rememberDesktopOriginalDanmakuCloudSyncBinding(
    settings:DanmakuSettings,cloudSyncEnabled:Boolean,isLoggedIn:Boolean,
    platform:DesktopDanmakuSettingsPlatform,
):DesktopDanmakuCloudSyncBinding {
    val danmakuEnabled=settings.enabled
    val danmakuAllowScroll=settings.allowScroll
    val danmakuAllowTop=settings.allowTop
    val danmakuAllowBottom=settings.allowBottom
    val danmakuAllowColorful=settings.allowColorful
    val danmakuAllowSpecial=settings.allowSpecial
    val danmakuOpacity=settings.opacity
    val danmakuDisplayArea=settings.displayArea
    val danmakuSpeed=settings.speed
    val danmakuFontScale=settings.fontScale
    val danmakuCloudSyncEnabled=cloudSyncEnabled
    val canSyncDanmakuCloud=shouldSyncDanmakuSettingsToCloud(isLoggedIn,cloudSyncEnabled)
        var pendingDanmakuCloudSync by remember {
            mutableStateOf<com.android.purebilibili.data.repository.DanmakuCloudSyncSettings?>(null)
        }
        var danmakuCloudSyncUiState by remember {
            mutableStateOf(DanmakuCloudSyncUiState())
        }
        var danmakuManualSyncRequestVersion by remember {
            mutableStateOf<Long?>(null)
        }
        var lastHandledDanmakuManualSyncRequestVersion by remember {
            mutableStateOf<Long?>(null)
        }

        fun buildDanmakuCloudSyncSettings(
            enabled: Boolean = danmakuEnabled,
            allowScroll: Boolean = danmakuAllowScroll,
            allowTop: Boolean = danmakuAllowTop,
            allowBottom: Boolean = danmakuAllowBottom,
            allowColorful: Boolean = danmakuAllowColorful,
            allowSpecial: Boolean = danmakuAllowSpecial,
            opacity: Float = danmakuOpacity,
            displayAreaRatio: Float = danmakuDisplayArea,
            speed: Float = danmakuSpeed,
            fontScale: Float = danmakuFontScale
        ): com.android.purebilibili.data.repository.DanmakuCloudSyncSettings {
            return com.android.purebilibili.data.repository.DanmakuCloudSyncSettings(
                enabled = enabled,
                allowScroll = allowScroll,
                allowTop = allowTop,
                allowBottom = allowBottom,
                allowColorful = allowColorful,
                allowSpecial = allowSpecial,
                opacity = opacity,
                displayAreaRatio = displayAreaRatio,
                speed = speed,
                fontScale = fontScale
            )
        }

        fun queueDanmakuCloudSync(
            enabled: Boolean = danmakuEnabled,
            allowScroll: Boolean = danmakuAllowScroll,
            allowTop: Boolean = danmakuAllowTop,
            allowBottom: Boolean = danmakuAllowBottom,
            allowColorful: Boolean = danmakuAllowColorful,
            allowSpecial: Boolean = danmakuAllowSpecial,
            opacity: Float = danmakuOpacity,
            displayAreaRatio: Float = danmakuDisplayArea,
            speed: Float = danmakuSpeed,
            fontScale: Float = danmakuFontScale
        ) {
            if (!platform.isOwned() || !canSyncDanmakuCloud) return
            pendingDanmakuCloudSync = buildDanmakuCloudSyncSettings(
                enabled = enabled,
                allowScroll = allowScroll,
                allowTop = allowTop,
                allowBottom = allowBottom,
                allowColorful = allowColorful,
                allowSpecial = allowSpecial,
                opacity = opacity,
                displayAreaRatio = displayAreaRatio,
                speed = speed,
                fontScale = fontScale
            )
            danmakuCloudSyncUiState = resolveDanmakuCloudSyncStateAfterQueued(danmakuCloudSyncUiState)
        }

        fun requestDanmakuCloudSyncNow() {
            if (!platform.isOwned() || !canSyncDanmakuCloud) return
            pendingDanmakuCloudSync = buildDanmakuCloudSyncSettings()
            danmakuManualSyncRequestVersion = platform.elapsedRealtimeMillis()
            danmakuCloudSyncUiState = resolveDanmakuCloudSyncStateAfterQueued(danmakuCloudSyncUiState)
        }

        LaunchedEffect(canSyncDanmakuCloud, danmakuCloudSyncEnabled) {
            if (!platform.isOwned()) return@LaunchedEffect
            if (canSyncDanmakuCloud) return@LaunchedEffect
            pendingDanmakuCloudSync = null
            danmakuCloudSyncUiState = DanmakuCloudSyncUiState()
        }

        LaunchedEffect(pendingDanmakuCloudSync, canSyncDanmakuCloud, danmakuManualSyncRequestVersion) {
            if (!platform.isOwned()) return@LaunchedEffect
            val settings = pendingDanmakuCloudSync ?: return@LaunchedEffect
            if (!canSyncDanmakuCloud) return@LaunchedEffect

            val manualSyncRequested = shouldRunDanmakuManualCloudSync(
                manualRequestVersion = danmakuManualSyncRequestVersion,
                lastHandledManualRequestVersion = lastHandledDanmakuManualSyncRequestVersion
            )
            if (!manualSyncRequested) {
                kotlinx.coroutines.delay(700)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!platform.isOwned()) return@LaunchedEffect
            danmakuCloudSyncUiState = resolveDanmakuCloudSyncStateAfterStarted(danmakuCloudSyncUiState)
            val result = platform.cloud.syncDanmakuCloudConfig(settings)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!platform.isOwned()) return@LaunchedEffect
            val completedAtMillis = System.currentTimeMillis()
            danmakuCloudSyncUiState = resolveDanmakuCloudSyncStateAfterResult(
                previous = danmakuCloudSyncUiState,
                result = result,
                completedAtMillis = completedAtMillis
            )
            if (manualSyncRequested) {
                lastHandledDanmakuManualSyncRequestVersion = danmakuManualSyncRequestVersion
            }
            if (pendingDanmakuCloudSync == settings) {
                pendingDanmakuCloudSync = null
            }
            if (result.isFailure) {
                platform.onCloudSyncFailure(result.exceptionOrNull()?.message)
            }
        }
    return DesktopDanmakuCloudSyncBinding(danmakuCloudSyncUiState,
        queueChange={ change ->
            if(platform.isOwned()) {
                val changed=change(buildDanmakuCloudSyncSettings())
                queueDanmakuCloudSync(changed.enabled,changed.allowScroll,changed.allowTop,changed.allowBottom,
                    changed.allowColorful,changed.allowSpecial,changed.opacity,changed.displayAreaRatio,changed.speed,changed.fontScale)
            }
        },requestNow=::requestDanmakuCloudSyncNow,onEnabledChange={ enabled ->
            if(platform.isOwned()&&!enabled) {
                pendingDanmakuCloudSync=null
                danmakuCloudSyncUiState=DanmakuCloudSyncUiState()
            }
        })
}
