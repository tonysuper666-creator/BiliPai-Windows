// Replaces upstream app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationService.kt sha256=1905aff378dc4f19c4b7abc5b225ba9c17e18462bf27d07f51f9558145b19f46
// Upstream's resident mode is an Android foreground service in the :message process, with a status-bar entry and
// screen/charging receivers, that keeps polling after the app UI is gone. The desktop has no service process and does
// not run after the client exits: the resident lane is a task of this window (DesktopMessageNotificationTasks) that
// polls at the original cadence while the client runs. "Screen on" is this window being shown and not minimised;
// charging is not read on Windows, so a minimised window drops to the original 15-minute idle floor.
package com.android.purebilibili.feature.message.notification

import android.content.Context
import com.android.purebilibili.core.util.Logger
import com.bilipai.desktop.ui.desktopMessageNotificationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

class MessageNotificationService internal constructor(private val context: Context) {
    internal suspend fun run() {
        val windowShown = desktopMessageNotificationContext(context).windowShown
        while (currentCoroutineContext().isActive) {
            val settings = MessageNotificationSettingsStore.getSettings(context).first()
            if (!settings.enabled || !settings.residentEnabled || !MessageNotificationNotifier.canPost(context)) break
            try {
                MessageNotificationChecker(context).runCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("MessageNotification", "Resident check failed", e)
            }
            val idleMs = resolveResidentPollDelayMs(settings.mode, windowShown.value, charging = false)
            withTimeoutOrNull(idleMs) {
                // Settings changes wake every mode immediately; showing the window only wakes the long idle wait.
                combine(windowShown, MessageNotificationSettingsStore.getSettings(context)) { shown, latest ->
                    latest != settings || (idleMs == RESIDENT_IDLE_POLL_MS && shown)
                }.first { it }
            }
        }
    }

    companion object {
        internal fun start(context: Context) {
            desktopMessageNotificationContext(context).tasks.startResident { MessageNotificationService(context).run() }
        }

        internal fun stop(context: Context) {
            desktopMessageNotificationContext(context).tasks.stopResident()
        }
    }
}
