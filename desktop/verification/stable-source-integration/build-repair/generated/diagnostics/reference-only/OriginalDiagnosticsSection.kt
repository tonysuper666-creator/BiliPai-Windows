@Composable
private fun DiagnosticsSection(
    crashTrackingEnabled: Boolean,
    analyticsEnabled: Boolean,
    enhancedDiagnosticLoggingEnabled: Boolean,
    onCrashTrackingChange: (Boolean) -> Unit,
    onAnalyticsChange: (Boolean) -> Unit,
    onEnhancedDiagnosticLoggingChange: (Boolean) -> Unit,
    onExportLogsClick: () -> Unit,
) {
    val context = LocalContext.current
    val siblingTints = remember { resolveSettingsSiblingIconTints(6, paletteOffset = 5) }
    val exportLogsVisual = rememberSettingsEntryVisual(SettingsSearchTarget.EXPORT_LOGS)
    val useMd3ExportLogsDescription = LocalAppUiStyle.current == AppUiStyle.MATERIAL3
    val exportLogsDescription = "崩溃摘要优先；原始回溯需单独选择"
    val proxySettings by NetworkProxyStore.settings.collectAsStateWithLifecycle(
        initialValue = NetworkProxyStore.getSync(context)
    )
    var showProxyDialog by remember { mutableStateOf(false) }
    var showEnhancedDiagnosticConsent by remember { mutableStateOf(false) }

    SettingsCardGroup {
        SettingSwitchItem(
            icon = rememberSettingsSemanticIcon(SettingsIconRole.CRASH_TRACKING),
            title = "崩溃追踪",
            subtitle = "记录崩溃和严重故障信息，帮助定位问题；默认开启",
            checked = crashTrackingEnabled,
            onCheckedChange = onCrashTrackingChange,
            iconTint = siblingTints[0],
        )
        SettingsAdaptiveDivider()
        SettingSwitchItem(
            icon = rememberSettingsSemanticIcon(SettingsIconRole.ANALYTICS),
            title = "使用情况统计",
            subtitle = "匿名统计每日活跃和基础功能使用情况，不包含账号内容；默认开启",
            checked = analyticsEnabled,
            onCheckedChange = onAnalyticsChange,
            iconTint = siblingTints[1],
        )
        SettingsAdaptiveDivider()
        SettingSwitchItem(
            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_pest_control_24),
            title = "增强诊断日志",
            subtitle = if (enhancedDiagnosticLoggingEnabled) {
                "正在采集脱敏后的详细运行信息；关闭会清除详细日志，保留基础错误日志"
            } else {
                "基础错误日志默认保留；开启后记录更多排障信息，不含凭据与观看内容"
            },
            checked = enhancedDiagnosticLoggingEnabled,
            onCheckedChange = { enabled ->
                if (enabled) showEnhancedDiagnosticConsent = true
                else onEnhancedDiagnosticLoggingChange(false)
            },
            iconTint = siblingTints[2],
        )
        SettingsAdaptiveDivider()
        SettingSwitchItem(
            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_lan_24),
            title = "HTTP 代理",
            subtitle = formatAppHttpProxySummary(proxySettings) +
                "（仅应用接口和登录请求，视频播放仍直接连接）",
            checked = proxySettings.enabled,
            onCheckedChange = { enabled ->
                if (enabled && !isAppHttpProxyConfigured(proxySettings)) {
                    showProxyDialog = true
                } else {
                    NetworkProxyStore.save(context, proxySettings.copy(enabled = enabled))
                }
            },
            iconTint = siblingTints[3],
        )
        SettingsAdaptiveDivider()
        SettingClickableItem(
            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_hub_24),
            title = "代理地址",
            value = formatAppHttpProxyEndpoint(proxySettings),
            subtitle = "填写“主机:端口”，例如 127.0.0.1:7890",
            onClick = { showProxyDialog = true },
            iconTint = siblingTints[4],
        )
        SettingsAdaptiveDivider()
        SettingClickableItem(
            icon = exportLogsVisual.icon,
            iconPainter = exportLogsVisual.iconResId?.let { painterResource(id = it) },
            title = settingsDestinationCopy(SettingsSearchTarget.EXPORT_LOGS).title,
            subtitle = exportLogsDescription.takeIf { useMd3ExportLogsDescription },
            value = exportLogsDescription.takeUnless { useMd3ExportLogsDescription },
            onClick = onExportLogsClick,
            iconTint = siblingTints[5],
        )
    }

    if (showProxyDialog) {
        NetworkProxyEditDialog(
            initial = proxySettings,
            onDismiss = { showProxyDialog = false },
            onConfirm = { next ->
                NetworkProxyStore.save(context, next)
                showProxyDialog = false
            },
        )
    }

    if (showEnhancedDiagnosticConsent) {
        AppAlertDialog(
            onDismissRequest = { showEnhancedDiagnosticConsent = false },
            title = { AppText("开启增强诊断日志？", fontWeight = FontWeight.Bold) },
            text = {
                AppText(
                    text = "将记录应用版本、设备与系统环境、请求结果、播放器状态和错误堆栈。" +
                        "账号凭据、用户标识、视频标识、搜索词和用户输入会在写入前脱敏。" +
                        "详细日志仅保存在应用私有目录（最多 256KB），不会自动上传；关闭后会清除详细日志。" +
                        "基础错误与启动诊断另行保留（最多 64KB），崩溃快照单独保存，可在诊断设置中清理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                AppDialogAction(
                    onClick = {
                        showEnhancedDiagnosticConsent = false
                        onEnhancedDiagnosticLoggingChange(true)
                    }
                ) { AppText("同意并开启") }
            },
            dismissButton = {
                AppDialogAction(onClick = { showEnhancedDiagnosticConsent = false }) {
                    AppText("取消")
                }
            },
        )
    }
}