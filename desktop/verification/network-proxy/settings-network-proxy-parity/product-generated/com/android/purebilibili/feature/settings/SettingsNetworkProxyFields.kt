// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt; do not edit.
// LF-normalized SHA-256: c8178e7a048512348678555b529180f7a969a658eca4019d484aff8603581a1b
package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.NetworkProxyStore
import com.android.purebilibili.core.network.policy.*
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.bilipai.desktop.settings.LocalDesktopNetworkProxyBindings
import com.bilipai.desktop.settings.DesktopProxySettingsVectors
import com.bilipai.desktop.settings.DesktopProxySettingsSymbols
@Composable
internal fun DesktopNetworkProxyFields() {
    val bindings=LocalDesktopNetworkProxyBindings.current
    val context=bindings.context
    val siblingTints = remember { resolveSettingsSiblingIconTints(6, paletteOffset = 5) }
    val proxySettings by NetworkProxyStore.settings.collectAsState()
    var showProxyDialog by remember { mutableStateOf(false) }
    SettingsCardGroup {
        SettingSwitchItem(
            icon = DesktopProxySettingsVectors.vector(DesktopProxySettingsSymbols.ms_lan_24),
            title = "HTTP 代理",
            subtitle = formatAppHttpProxySummary(proxySettings) +
                "（仅应用接口和登录请求，视频播放仍直接连接）",
            checked = proxySettings.enabled,
            onCheckedChange = { enabled ->
                if (enabled && !isAppHttpProxyConfigured(proxySettings)) {
                    showProxyDialog = true
                } else {
                    bindings.save(proxySettings.copy(enabled = enabled))
                }
            },
            iconTint = siblingTints[3],
        )
        SettingsAdaptiveDivider()
        SettingClickableItem(
            icon = DesktopProxySettingsVectors.vector(DesktopProxySettingsSymbols.ms_hub_24),
            title = "代理地址",
            value = formatAppHttpProxyEndpoint(proxySettings),
            subtitle = "填写“主机:端口”，例如 127.0.0.1:7890",
            onClick = { showProxyDialog = true },
            iconTint = siblingTints[4],
        )
    }
    if (showProxyDialog) {
        NetworkProxyEditDialog(
            initial = proxySettings,
            onDismiss = { showProxyDialog = false },
            onConfirm = { next ->
                bindings.save(next)
                showProxyDialog = false
            },
        )
    }
}

@Composable
private fun NetworkProxyEditDialog(
    initial: AppHttpProxySettings,
    onDismiss: () -> Unit,
    onConfirm: (AppHttpProxySettings) -> Unit,
) {
    var host by remember(initial) { mutableStateOf(initial.host) }
    var portText by remember(initial) { mutableStateOf(initial.portText) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    val draft = AppHttpProxySettings(
        enabled = enabled,
        host = sanitizeProxyHostInput(host),
        portText = sanitizeProxyPortInput(portText),
    )
    val canSave = !enabled || isAppHttpProxyConfigured(draft)

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            AppText(
                text = "设置 HTTP 代理",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppText(
                    text = "仅作用于 API / 登录请求。播放媒体仍直连，避免本地代理端口不可用导致卡顿。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "主机",
                    placeholder = "127.0.0.1",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = portText,
                    onValueChange = { portText = sanitizeProxyPortInput(it) },
                    label = "端口",
                    placeholder = "7890",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AppText(
                        text = "启用代理",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    AppAdaptiveSwitch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
                if (enabled && !isAppHttpProxyConfigured(draft)) {
                    AppText(
                        text = "请填写有效的主机与端口（1–65535）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            AppDialogAction(
                onClick = {
                    if (canSave) {
                        onConfirm(draft)
                    }
                },
            ) {
                AppText(
                    text = "保存",
                    color = if (canSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            AppDialogAction(onClick = onDismiss) {
                AppText("取消")
            }
        },
    )
}
