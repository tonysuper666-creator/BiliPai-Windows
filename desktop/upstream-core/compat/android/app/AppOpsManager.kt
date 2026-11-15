package android.app

import com.bilipai.desktop.compat.UnsupportedDesktopCapability

/**
 * Android picture-in-picture has no desktop permission or activity. Reporting MODE_ERRORED keeps
 * upstream's permission query false without pretending a desktop mini-player is an Android grant.
 * Other operations need an explicit desktop implementation; they must not inherit a permissive mode.
 */
class AppOpsManager private constructor() {
    @Suppress("UNUSED_PARAMETER")
    fun checkOpNoThrow(op: String, uid: Int, packageName: String): Int = when (op) {
        OPSTR_PICTURE_IN_PICTURE -> MODE_ERRORED
        else -> throw UnsupportedDesktopCapability("AppOpsManager.checkOpNoThrow($op)",
            "this Android app-op has no desktop implementation")
    }

    companion object {
        const val OPSTR_PICTURE_IN_PICTURE = "android:picture_in_picture"
        const val MODE_ALLOWED = 0
        const val MODE_ERRORED = 2
        internal val desktop = AppOpsManager()
    }
}
