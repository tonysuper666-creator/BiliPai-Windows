package com.bilipai.desktop.settings

import android.app.Application
import android.content.Context
import com.android.purebilibili.feature.settings.AppUpdateCheckResult

/** Page-owned Application; storage and accepted release evidence belong to the same retained Root. */
internal class DesktopSettingsApplication(
    context: Context,
    internal val releaseMetadata: DesktopOriginalAboutReleaseMetadata,
    internal val owns: () -> Boolean,
    internal val cacheOwner: DesktopStorageSettingsOwner,
) : Application(context)

/** This bridge only reads an already accepted About result. It owns no HTTP client or request. */
internal object DesktopSettingsDiagnosticsReleaseEvidence {
    fun lastAccepted(context: Context, currentVersion: String, currentVersionCode: Int): AppUpdateCheckResult? {
        val application = context.applicationContext as? DesktopSettingsApplication ?: return null
        if (!application.owns()) return null
        return select(application.releaseMetadata.state.value, currentVersion, currentVersionCode)
    }

    internal fun select(accepted: DesktopOriginalAboutReleaseState, currentVersion: String, currentVersionCode: Int): AppUpdateCheckResult? {
        return accepted.result?.takeIf {
            it.currentVersion == currentVersion && accepted.checkedVersionCode == currentVersionCode
        }
    }
}
