package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.feature.video.danmaku.DanmakuCloudSyncUiState
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsPanel
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Complete original panel consumes the sole global preference snapshot and all original setters. */
@Composable internal fun DesktopOriginalDanmakuSettingsHost(
    preferences:DesktopOriginalDanmakuPreferences,
    presentation:DesktopDanmakuSettingsPresentation,
    viewport:DesktopDanmakuSettingsViewport,
    platform:DesktopDanmakuSettingsPlatform,
    cloudSyncEnabled:Boolean,
    syncUiState:DanmakuCloudSyncUiState,
    onSyncNowClick:()->Unit,
    onShowDanmakuPool:()->Unit,
    onDismiss:()->Unit,
) {
    val settingsScope=presentation.originalScope()
    key(preferences,settingsScope,platform) {
        val value by remember(preferences,settingsScope){preferences.getDanmakuSettings(settingsScope)}
            .collectAsState(preferences.currentSettings(settingsScope))
        val scope=rememberCoroutineScope()
        fun update(action:suspend()->Unit) {
            if(!platform.isOwned())return
            scope.launch {
                try {action()}
                catch(cancelled:CancellationException){throw cancelled}
                catch(failure:Exception){if(platform.isOwned())platform.showFeedback(failure.message?:"弹幕设置保存失败")}
            }
        }
        if(!platform.isOwned())return@key
        CompositionLocalProvider(LocalDesktopDanmakuSettingsPlatform provides platform,
            LocalDesktopDanmakuSettingsViewport provides viewport) {
            DanmakuSettingsPanel(
                isFullscreen=presentation!=DesktopDanmakuSettingsPresentation.INLINE,
                settingsScope=settingsScope,opacity=value.opacity,fontScale=value.fontScale,
                showAdvancedSection=true,fontWeight=value.fontWeight,speed=value.speed,
                displayArea=value.displayArea,strokeWidth=value.strokeWidth,lineHeight=value.lineHeight,
                scrollDurationSeconds=value.scrollDurationSeconds,staticDurationSeconds=value.staticDurationSeconds,
                scrollFixedVelocity=value.scrollFixedVelocity,staticDanmakuToScroll=value.staticDanmakuToScroll,
                massiveMode=value.massiveMode,mergeDuplicates=value.mergeDuplicates,
                duplicateMergeWindowMs=value.duplicateMergeWindowMs,duplicateMergeCountThreshold=value.duplicateMergeCountThreshold,
                allowScroll=value.allowScroll,allowTop=value.allowTop,allowBottom=value.allowBottom,
                allowColorful=value.allowColorful,allowSpecial=value.allowSpecial,
                weightFilterLevel=value.weightFilterLevel,hideInteractiveCommands=value.hideInteractiveCommands,
                showBlockRuleEditor=true,showSmartOcclusionSection=true,showSyncSection=true,
                cloudSyncEnabled=cloudSyncEnabled,blockRulesRaw=value.blockRulesRaw,
                smartOcclusion=value.smartOcclusion,fullscreenWidthMode=value.fullscreenPanelWidthMode,
                portraitDisplayAreaMode=value.portraitDisplayAreaMode,syncUiState=syncUiState,
                onOpacityChange={update{preferences.setDanmakuOpacity(it,settingsScope)}},
                onFontScaleChange={update{preferences.setDanmakuFontScale(it,settingsScope)}},
                onFontWeightChange={update{preferences.setDanmakuFontWeight(it,settingsScope)}},
                onSpeedChange={update{preferences.setDanmakuSpeed(it,settingsScope)}},
                onDisplayAreaChange={update{preferences.setDanmakuArea(it,settingsScope)}},
                onStrokeWidthChange={update{preferences.setDanmakuStrokeWidth(it,settingsScope)}},
                onLineHeightChange={update{preferences.setDanmakuLineHeight(it,settingsScope)}},
                onScrollDurationSecondsChange={update{preferences.setDanmakuScrollDurationSeconds(it,settingsScope)}},
                onStaticDurationSecondsChange={update{preferences.setDanmakuStaticDurationSeconds(it,settingsScope)}},
                onScrollFixedVelocityChange={update{preferences.setDanmakuScrollFixedVelocity(it,settingsScope)}},
                onStaticDanmakuToScrollChange={update{preferences.setDanmakuStaticToScroll(it,settingsScope)}},
                onMassiveModeChange={update{preferences.setDanmakuMassiveMode(it,settingsScope)}},
                onMergeDuplicatesChange={update{preferences.setDanmakuMergeDuplicates(it,settingsScope)}},
                onDuplicateMergeWindowMsChange={update{preferences.setDanmakuDuplicateMergeWindowMs(it,settingsScope)}},
                onDuplicateMergeCountThresholdChange={update{preferences.setDanmakuDuplicateMergeCountThreshold(it,settingsScope)}},
                onAllowScrollChange={update{preferences.setDanmakuAllowScroll(it,settingsScope)}},
                onAllowTopChange={update{preferences.setDanmakuAllowTop(it,settingsScope)}},
                onAllowBottomChange={update{preferences.setDanmakuAllowBottom(it,settingsScope)}},
                onAllowColorfulChange={update{preferences.setDanmakuAllowColorful(it,settingsScope)}},
                onAllowSpecialChange={update{preferences.setDanmakuAllowSpecial(it,settingsScope)}},
                onWeightFilterLevelChange={update{preferences.setDanmakuWeightFilterLevel(it)}},
                onHideInteractiveCommandsChange={update{preferences.setDanmakuHideInteractiveCommands(it)}},
                onBlockRulesRawChange={update{preferences.setDanmakuBlockRulesRaw(it,settingsScope)}},
                onSmartOcclusionChange={update{preferences.setDanmakuSmartOcclusion(it,settingsScope)}},
                onFullscreenWidthModeChange={update{preferences.setDanmakuFullscreenPanelWidthMode(it)}},
                onPortraitDisplayAreaModeChange={update{preferences.setPortraitDanmakuDisplayAreaMode(it)}},
                onCloudSyncEnabledChange={update{preferences.setDanmakuCloudSyncEnabled(it)}},
                onSyncNowClick={if(platform.isOwned())onSyncNowClick()},
                onShowDanmakuPool={if(platform.isOwned())onShowDanmakuPool()},
                onDismiss={if(platform.isOwned())onDismiss()},
            )
        }
    }
}
