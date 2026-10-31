// Replaces upstream app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationProcess.kt sha256=b268657dc877b1b1c4ed228af78153a18269a02ca0cd8b002a74f1d7ae5e727c
// Android runs the resident service in a private ":message" process (and playback in ":playback"). The Windows client
// is a single process: no code ever runs in such a process, and there is none to initialise.
package com.android.purebilibili.feature.message.notification

import android.content.Context
import com.bilipai.desktop.compat.UnsupportedDesktopCapability

internal const val MESSAGE_PROCESS_SUFFIX = ":message"

@Suppress("UNUSED_PARAMETER")
internal fun isMessageNotificationProcess(context: Context): Boolean = false

@Suppress("UNUSED_PARAMETER")
internal fun isLightweightServiceProcess(context: Context): Boolean = false

@Suppress("UNUSED_PARAMETER")
internal fun initializeMessageNotificationProcess(context: Context): Nothing =
    throw UnsupportedDesktopCapability("message notification process", "the Windows client checks messages inside its window")
