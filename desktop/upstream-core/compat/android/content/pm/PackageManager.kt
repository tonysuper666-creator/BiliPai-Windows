package android.content.pm

import android.content.ComponentName
import com.bilipai.desktop.compat.UnsupportedDesktopCapability

/** Android launcher aliases do not exist on the desktop; callers cannot report a successful change. */
class PackageManager {
    @Suppress("UNUSED_PARAMETER")
    fun setComponentEnabledSetting(component: ComponentName, newState: Int, flags: Int): Nothing =
        throw UnsupportedDesktopCapability("Android launcher alias ${component.className}", "Windows has no Android launcher components")

    companion object {
        const val COMPONENT_ENABLED_STATE_ENABLED = 1
        const val COMPONENT_ENABLED_STATE_DISABLED = 2
        const val DONT_KILL_APP = 1
    }
}
