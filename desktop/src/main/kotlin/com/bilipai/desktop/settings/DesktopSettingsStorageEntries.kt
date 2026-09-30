package com.bilipai.desktop.settings

import androidx.compose.runtime.Composable
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.SettingsStorageBackupCategoryEntrySection
import com.bilipai.desktop.ui.DesktopBackupSettingsSection

/** Route identity only. Unsupported storage consumers do not fall through to a generic backup dialog. */
internal fun resolveDesktopBackupEntrySection(target: SettingsSearchTarget): DesktopBackupSettingsSection? = when (target) {
    SettingsSearchTarget.SETTINGS_SHARE -> DesktopBackupSettingsSection.LOCAL_SETTINGS
    SettingsSearchTarget.WEBDAV_BACKUP -> DesktopBackupSettingsSection.WEBDAV
    else -> null
}

/** Original selected backup rows. Opening an entry does not submit any backup or restore operation. */
@Composable
internal fun DesktopSettingsStorageEntries(onOpenBackup: (SettingsSearchTarget) -> Unit) {
    SettingsStorageBackupCategoryEntrySection(
        onSettingsShareClick = { onOpenBackup(SettingsSearchTarget.SETTINGS_SHARE) },
        onWebDavBackupClick = { onOpenBackup(SettingsSearchTarget.WEBDAV_BACKUP) },
    )
}
