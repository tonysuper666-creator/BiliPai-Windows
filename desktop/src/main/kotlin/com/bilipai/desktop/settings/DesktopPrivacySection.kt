package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.launch

val LocalDesktopPrivacySectionBindings = staticCompositionLocalOf<DesktopPrivacySectionBindings> {
    error("PrivacySection requires Root shared settings and authoritative search preferences")
}

/** The desktop page offers only existing, effective Windows privacy controls. */
@Composable
fun DesktopPrivacySection(bindings: DesktopPrivacySectionBindings,
    onPermissionClick: () -> Unit, onMessageNotificationClick: () -> Unit,
    onBlockedListClick: () -> Unit, onCommentFraudHistoryClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val privacy by bindings.searchPreferences.privacyMode.collectAsState()
    val suggestions by bindings.searchPreferences.suggestionsEnabled.collectAsState()
    val hints = desktopWindowsSettingsValue(remember(bindings) { bindings.defaultHintEnabled })
    val recap = desktopWindowsSettingsValue(remember(bindings) { bindings.personalRecapEnabled })
    val settings = LocalDesktopOriginalPlayerSettingsContext.current
    val error by bindings.error.collectAsState()
    Column {
        DesktopWindowsSettingsGroup("搜索与历史") {
            DesktopWindowsSettingsSwitch("显示默认搜索词", hints) { value -> scope.launch { bindings.setDefaultHintEnabled(value) } }
            DesktopWindowsSettingsSwitch("个性化搜索推荐", suggestions,
                description = "使用官方搜索推荐；关闭时使用公开热搜。") { value ->
                scope.launch { bindings.setSuggestionsEnabled(value) }
            }
            DesktopWindowsSettingsSwitch("我的回顾", recap,
                description = "在历史页显示阅读与观看统计、趋势图和最近爱看的 UP 主。") { value ->
                scope.launch { bindings.setPersonalRecapEnabled(settings, value) }
            }
            DesktopWindowsSettingsSwitch("不新增播放和搜索历史", privacy,
                description = "已有记录保留。") { value -> scope.launch { bindings.setPrivacyMode(value) } }
        }
        OutlinedButton(onClick = onBlockedListClick) { Text("管理已屏蔽的 UP 主") }
        OutlinedButton(onClick = onCommentFraudHistoryClick) { Text("发评反诈历史") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
