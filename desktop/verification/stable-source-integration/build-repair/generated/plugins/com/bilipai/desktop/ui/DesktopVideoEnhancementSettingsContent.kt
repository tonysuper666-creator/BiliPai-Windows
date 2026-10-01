// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/Anime4KPlugin.kt; do not edit.
// LF-normalized SHA-256: 0979f3131d293cddfdeec2b41e00eb89d90a49043c1782e1a105e1b99b11baab
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppSliderPreference
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.feature.anime4k.FSR_SHARPNESS_SLIDER_STEPS
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.anime4k.VideoEnhancementConfigLoadGuard
import com.android.purebilibili.feature.anime4k.decodeVideoEnhancementConfig
import com.android.purebilibili.feature.anime4k.encodeVideoEnhancementConfig
import com.android.purebilibili.feature.anime4k.normalizeFsrSharpness
import com.android.purebilibili.feature.anime4k.resolveConfigAfterRememberAcrossVideosChange
import com.android.purebilibili.feature.anime4k.resolveConfigAfterVideoEnhancementToggle
import com.android.purebilibili.feature.anime4k.resolveAnime4KPresetLabel
import com.android.purebilibili.feature.anime4k.shouldConfirmRememberAcrossVideosChange
import androidx.compose.runtime.collectAsState

@Composable
fun DesktopVideoEnhancementSettingsContent(configuration: com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration) {
    val configSnapshot by configuration.configState.collectAsState()
    var showRememberWarning by remember { mutableStateOf(false) }
    val anime4kOptions = remember {
        listOf(
            Anime4KPreset.FAST,
            Anime4KPreset.QUALITY
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppText("增强算法", style = MaterialTheme.typography.titleSmall)
        VideoEnhancementAlgorithm.entries.forEach { algorithm ->
            AppFilterChip(
                selected = configSnapshot.algorithm == algorithm,
                onClick = { configuration.setAlgorithm(algorithm) },
                label = {
                    AppText(
                        when (algorithm) {
                            VideoEnhancementAlgorithm.ANIME4K -> "Anime4K（动漫）"
                            VideoEnhancementAlgorithm.FSR_1_0 -> "AMD FSR 1.0（通用）"
                        }
                    )
                }
            )
        }
        AppText(
            text = "HDR、杜比视界、小窗和后台播放会自动使用原始视频输出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (configSnapshot.algorithm == VideoEnhancementAlgorithm.ANIME4K) {
            AppText("CNN 模型", style = MaterialTheme.typography.titleSmall)
            anime4kOptions.forEach { preset ->
                AppFilterChip(
                    selected = configSnapshot.preset == preset,
                    onClick = { configuration.setPreset(preset) },
                    label = { AppText(resolveAnime4KPresetLabel(preset)) }
                )
            }
        } else {
            AppSliderPreference(
                title = "FSR 锐化强度",
                subtitle = "默认值来自公开 FSR 1.0 视频实现；过高可能产生锐化边缘。",
                value = configSnapshot.fsrSharpness,
                onValueChange = { configuration.setFsrSharpness(it) },
                valueRange = 0f..1f,
                steps = FSR_SHARPNESS_SLIDER_STEPS,
                valueLabel = "${(configSnapshot.fsrSharpness * 100).toInt()}%"
            )
        }

        AppSwitchPreference(
            title = "跨视频记忆开启状态",
            subtitle = if (configSnapshot.rememberAcrossVideos) {
                "后续视频沿用开关，请留意较高分辨率内容"
            } else {
                "默认关闭；每个视频需要单独开启"
            },
            checked = configSnapshot.rememberAcrossVideos,
            onCheckedChange = { requestedValue ->
                if (
                    shouldConfirmRememberAcrossVideosChange(
                        currentValue = configSnapshot.rememberAcrossVideos,
                        requestedValue = requestedValue
                    )
                ) {
                    showRememberWarning = true
                } else {
                    configuration.setRememberAcrossVideos(
                        enabled = requestedValue,
                        currentVideoEnabled = false
                    )
                }
            }
        )
    }

    if (showRememberWarning) {
        AppAlertDialog(
            onDismissRequest = { showRememberWarning = false },
            title = { AppText("是否记住后续视频的开关？") },
            text = {
                AppText(
                    "后续视频会沿用最近一次开关。请留意播放器中的开启状态，" +
                        "避免之后忘记关闭，在较高分辨率视频上持续增强而出现发热、耗电或卡顿。" +
                        "HDR 等自动旁路场景不会执行增强。"
                )
            },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        configuration.setRememberAcrossVideos(
                            enabled = true,
                            currentVideoEnabled = false
                        )
                        showRememberWarning = false
                    }
                ) {
                    AppText("开启记忆")
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showRememberWarning = false }) {
                    AppText("取消")
                }
            }
        )
    }
}
