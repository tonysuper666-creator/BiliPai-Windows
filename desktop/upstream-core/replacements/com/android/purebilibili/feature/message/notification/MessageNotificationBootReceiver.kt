// Replaces upstream app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationBootReceiver.kt sha256=d8b9734375ea27b4898cdf77850e9d90810784e0b521ee77af45797d58f3e352
// Android restores the schedule from the boot broadcast, so notifications continue after a reboot without opening the
// app. The Windows client does not start with Windows and checks messages only while it runs: nothing delivers a boot
// broadcast here, and a call is refused instead of pretending the schedule was restored.
package com.android.purebilibili.feature.message.notification

import android.content.Context
import com.bilipai.desktop.compat.UnsupportedDesktopCapability

class MessageNotificationBootReceiver {
    @Suppress("UNUSED_PARAMETER")
    fun onReceive(context: Context): Nothing =
        throw UnsupportedDesktopCapability("message notifications after a reboot", "the Windows client checks messages only while it runs")
}
