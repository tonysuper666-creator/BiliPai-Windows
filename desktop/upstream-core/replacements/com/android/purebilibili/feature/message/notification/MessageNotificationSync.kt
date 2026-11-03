// Replaces upstream app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationSync.kt sha256=8d96ace269f3dae6f0c34b6c1cba849b738ea1bbde2c358d4c9a1cb9938cb27e
// Same decisions under the same transaction lock; only Android's service start/stop (startForegroundService,
// stopService) becomes starting or stopping the resident task of this window.
package com.android.purebilibili.feature.message.notification

import android.content.Context
import kotlinx.coroutines.flow.first

internal object MessageNotificationSync {
    // Called when this window starts and after every settings change.
    // Re-read inside the lock: a stale UI snapshot must not undo a more recent switch change.
    suspend fun sync(context: Context): Boolean = MessageNotificationTransaction.withLock(context) {
        val appContext = context.applicationContext
        val settings = MessageNotificationSettingsStore.getSettings(appContext).first()
        MessageNotificationScheduler.sync(appContext, settings)
        if (!settings.enabled) {
            MessageNotificationService.stop(appContext)
            MessageNotificationStateStore.clearAll(appContext)
            MessageNotificationNotifier.cancelMessages(appContext)
        } else if (!settings.residentEnabled || !MessageNotificationNotifier.canPost(appContext)) {
            MessageNotificationService.stop(appContext)
        } else {
            MessageNotificationService.start(appContext)
        }
        true
    }
}
