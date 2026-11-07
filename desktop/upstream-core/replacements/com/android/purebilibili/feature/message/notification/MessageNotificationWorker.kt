// Replaces upstream app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationWorker.kt sha256=6594d8228b1d838d904fa2c6fa23fbe5ee670bb24e54d636f474656f6480a1f6
// Upstream schedules the Worker as WorkManager unique periodic work that survives the app. The desktop has no
// WorkManager: the same unique periodic work is a task of this window (DesktopMessageNotificationTasks), with the
// original period, an immediate first run and the original linear 5-minute retry backoff. It runs while the client
// is open and ends with it. The network and battery-not-low constraints are not read on Windows.
package com.android.purebilibili.feature.message.notification

import android.content.Context
import com.android.purebilibili.core.util.Logger
import com.bilipai.desktop.ui.desktopMessageNotificationContext
import java.io.IOException
import kotlinx.coroutines.CancellationException

class MessageNotificationWorker internal constructor(private val context: Context) {
    enum class Result { SUCCESS, RETRY }

    suspend fun doWork(): Result = try {
        if (MessageNotificationChecker(context).runCheck()) Result.SUCCESS else Result.RETRY
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        Result.RETRY
    } catch (e: Exception) {
        Logger.e("MessageNotification", "Background check failed", e)
        Result.SUCCESS
    }
}

internal object MessageNotificationScheduler {
    const val UNIQUE_WORK_NAME = "message_notification_poll"
    const val BACKOFF_MS = 5 * 60_000L

    fun sync(context: Context, settings: MessageNotificationSettings) {
        val tasks = desktopMessageNotificationContext(context).tasks
        if (!settings.enabled) {
            tasks.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val timing = resolveMessageNotificationTiming(settings.mode)
        tasks.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, timing.periodicMinutes * 60_000L, BACKOFF_MS) {
            MessageNotificationWorker(context).doWork() == MessageNotificationWorker.Result.SUCCESS
        }
    }
}
