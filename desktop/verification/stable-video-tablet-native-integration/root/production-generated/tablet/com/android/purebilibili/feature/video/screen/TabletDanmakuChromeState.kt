package com.android.purebilibili.feature.video.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.core.store.DanmakuSettingsScope
import kotlinx.coroutines.launch

internal data class TabletDanmakuChromeState(
    val enabled: Boolean,
    val onToggle: () -> Unit,
)

@Composable
internal fun rememberTabletDanmakuChromeState(bvid: String): TabletDanmakuChromeState {
    val platform = LocalDesktopOriginalVideoSectionPlatform.current
    val context = platform.settingsContext
    val scope = rememberCoroutineScope()
    val danmakuManager = platform.danmaku
    val danmakuSettings by platform.danmakuPreferences
        .getDanmakuSettings(DanmakuSettingsScope.PORTRAIT)
        .collectAsStateWithLifecycle(initialValue = platform.danmakuPreferences.currentSettings(DanmakuSettingsScope.PORTRAIT))
    val latestEnabled = rememberUpdatedState(danmakuSettings.enabled)
    val onToggle = remember(danmakuManager, context, scope) {
        {
            val newValue = !latestEnabled.value
            danmakuManager.isEnabled = newValue
            if (!newValue) {
                danmakuManager.clear()
            }
            scope.launch {
                platform.danmakuPreferences.setDanmakuEnabled(
                    newValue,
                    DanmakuSettingsScope.PORTRAIT,
                )
            }
            Unit
        }
    }
    return TabletDanmakuChromeState(
        enabled = danmakuSettings.enabled,
        onToggle = onToggle,
    )
}
