package android.provider

import android.content.Context

/** Android settings capabilities are absent on desktop; Windows owns display brightness. */
object Settings {
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_MANAGE_WRITE_SETTINGS = "android.settings.action.MANAGE_WRITE_SETTINGS"

    object System {
        /** There is no Android settings provider to grant this permission on desktop. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun canWrite(context: Context): Boolean = false
    }
}
