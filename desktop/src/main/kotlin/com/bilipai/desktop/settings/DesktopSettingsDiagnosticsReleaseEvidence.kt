package com.bilipai.desktop.settings

import android.app.Application
import android.content.Context
import com.android.purebilibili.feature.settings.AppUpdateCheckResult
import com.android.purebilibili.core.util.CacheClearTarget
import com.android.purebilibili.core.util.CacheUtils

/** The VM's cache operations; the mounted Application delegates to its existing Root owner. */
internal interface DesktopSettingsCacheOwner {
    suspend fun breakdown(): CacheUtils.CacheBreakdown
    suspend fun clear(targets: Set<CacheClearTarget>)
}

/** Page-owned Application; storage and accepted release evidence belong to the same retained Root. */
internal class DesktopSettingsApplication(
    context: Context,
    internal val releaseMetadata: DesktopOriginalAboutReleaseMetadata,
    internal val owns: () -> Boolean,
    internal val cacheOwner: DesktopSettingsCacheOwner,
) : Application(context) {
    constructor(
        context: Context,
        releaseMetadata: DesktopOriginalAboutReleaseMetadata,
        owns: () -> Boolean,
        cacheOwner: DesktopStorageSettingsOwner,
    ) : this(context, releaseMetadata, owns, object : DesktopSettingsCacheOwner {
        override suspend fun breakdown() = cacheOwner.breakdown()
        override suspend fun clear(targets: Set<CacheClearTarget>) = cacheOwner.clear(targets)
    })
}

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
