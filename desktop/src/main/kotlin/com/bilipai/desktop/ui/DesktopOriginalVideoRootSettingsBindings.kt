package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.editSettingsAndCommitPrefs

/** Windows synchronous readback of the original PlayerSettingsCache mirror.
 * The existing global Store is the sole settings authority; this view has no
 * singleton cache or document of its own. Keys/defaults are PlayerSettingsCache
 * and SettingsManager from stable 3d5d, not new Root preferences. */
internal fun desktopOriginalRootDashRequestsEnabled(context: DesktopOriginalPlayerSettingsContext): Boolean =
    context.getSharedPreferences("player_settings_cache", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
        .getBoolean("dash_segment_requests_enabled", false)

internal fun desktopOriginalRootDiagnosticLoggingEnabled(context: DesktopOriginalPlayerSettingsContext): Boolean =
    context.getSharedPreferences("player_settings_cache", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
        .getBoolean("player_diagnostic_logging_enabled", context.defaultPlayerDiagnosticLoggingEnabled())

/** Original settings + mirror double-write through the installed serialized
 * cache writer and actual76 CAS permit. Every disk operation remains outside
 * Store/entry admission; the callback launches this suspend work on owner scope. */
internal suspend fun desktopOriginalRootSetDiagnosticLogging(
    context: DesktopOriginalPlayerSettingsContext, enabled: Boolean,
) {
    editSettingsAndCommitPrefs(context, "player_settings_cache",
        editSettings = { this[playerBooleanPreferencesKey("player_diagnostic_logging_enabled")] = enabled },
        editPrefs = { putBoolean("player_diagnostic_logging_enabled", enabled) })
}
