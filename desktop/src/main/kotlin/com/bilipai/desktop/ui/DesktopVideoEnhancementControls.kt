package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration

@Composable
fun DesktopVideoEnhancementSettingsDialog(configuration: DesktopVideoEnhancementConfiguration, onDismiss: () -> Unit) {
    val error by configuration.error.collectAsState()
    AppAlertDialog(onDismissRequest = onDismiss, title = { AppText("画质增强") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            DesktopVideoEnhancementSettingsContent(configuration)
            error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { AppTextButton(onClick = onDismiss) { AppText("完成") } })
}

@Composable
fun DesktopVideoEnhancementControls(state: DesktopVideoEnhancementState,
    configuration: DesktopVideoEnhancementConfiguration, onToggle: (Boolean) -> Unit, onSettings: () -> Unit) {
    if (state.identity == null) return
    val config by configuration.configState.collectAsState()
    val error by configuration.error.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSwitchPreference(title = "画质增强", subtitle = state.error ?: if (state.pending) "正在初始化画质增强" else
            resolveAnime4KSettingsSubtitle(state.requested, state.available, state.bypassReason),
            checked = state.requested, onCheckedChange = onToggle)
        if (state.requested) {
            VideoEnhancementAlgorithmOptions(config.algorithm, { configuration.setAlgorithm(it) })
            when (config.algorithm) {
                VideoEnhancementAlgorithm.ANIME4K -> Anime4KPresetOptions(config.preset, { configuration.setPreset(it) })
                VideoEnhancementAlgorithm.FSR_1_0 -> FsrSharpnessOptions(config.fsrSharpness, { configuration.setFsrSharpness(it) })
            }
        }
        error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        AppTextButton(onClick = onSettings) { AppText("画质增强设置") }
    }
}
