package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import com.android.purebilibili.feature.settings.DesktopOriginalImageSavePathDialog
import com.android.purebilibili.feature.settings.SettingsImageSavePathEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Uses the Root's existing global preference facade, scope, window chooser and
 * settings-intent lifetime. No account MID, card epoch, new store or new picker.
 * The supplied write gate in preferences remains the final commit authority.
 */
@Composable
internal fun DesktopImageSavePathSettings(
    preferences: DesktopImageSaveLocationPreferences,
    settingsScope: CoroutineScope,
    chooseDirectory: suspend () -> Path?,
    stillOwned: () -> Boolean,
    onFailure: (Throwable) -> Unit,
    onMessage: (String) -> Unit = {},
    openInitially: Boolean = false,
    onDialogDismissed: () -> Unit = {},
) {
    val saved by preferences.getImageSaveTreeUri().collectAsState(preferences.getImageSaveTreeUriSync())
    var shown by remember { mutableStateOf(openInitially) }
    val latestChoose by rememberUpdatedState(chooseDirectory)
    val latestOwned by rememberUpdatedState(stillOwned)
    val latestFailure by rememberUpdatedState(onFailure)
    val latestMessage by rememberUpdatedState(onMessage)
    fun dismiss() { shown = false; onDialogDismissed() }
    fun report(block: suspend () -> Unit) {
        settingsScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (latestOwned()) latestFailure(failure) }
        }
    }
    SettingsImageSavePathEntry(saved) { if (latestOwned()) shown = true }
    if (shown) DesktopOriginalImageSavePathDialog(saved, ::dismiss,
        onSelectDirectory = {
            // The dialog dismisses first, as in the original. The Root settings
            // scope outlives that popup, while the settings-window intent guard
            // rejects a chooser result after close/restore; account changes do not.
            report {
                if (selectAndRememberDesktopImageSaveDirectory(preferences, latestChoose, latestOwned)) {
                    latestMessage("已设置图片保存目录")
                }
            }
        },
        onRestoreDefault = {
            report {
                resetDesktopImageSaveDirectory(preferences, latestOwned)
                latestMessage("已恢复默认图片保存位置")
            }
        })
}

private suspend fun ensureImageSaveSettingsOwned(stillOwned: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    if (!stillOwned()) throw CancellationException("图片保存位置设置已结束")
}

internal suspend fun selectAndRememberDesktopImageSaveDirectory(
    preferences: DesktopImageSaveLocationPreferences,
    chooseDirectory: suspend () -> Path?,
    stillOwned: () -> Boolean,
): Boolean {
    ensureImageSaveSettingsOwned(stillOwned)
    val selected = chooseDirectory() ?: return false
    ensureImageSaveSettingsOwned(stillOwned)
    require(selected.isAbsolute) { "请选择有效的图片保存目录" }
    // Windows file URI is a platform representation only. No claim that the
    // original Android content:// resolver accepts file:// or grants SAF access.
    preferences.setImageSaveTreeUri(selected.toUri().toString())
    ensureImageSaveSettingsOwned(stillOwned)
    return true
}

internal suspend fun resetDesktopImageSaveDirectory(
    preferences: DesktopImageSaveLocationPreferences,
    stillOwned: () -> Boolean,
) {
    ensureImageSaveSettingsOwned(stillOwned)
    preferences.setImageSaveTreeUri(null)
    ensureImageSaveSettingsOwned(stillOwned)
}
