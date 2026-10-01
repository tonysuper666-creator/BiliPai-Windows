package com.bilipai.desktop.ui

/** Original Android policy numbers, used only as inputs to the preserved pure
 * decision functions. Root translates requests to its actual Window placement;
 * this declaration does not emulate an Android Activity or rotation sensor. */
internal object DesktopOriginalVideoOrientationRequest {
    const val SCREEN_ORIENTATION_UNSPECIFIED = -1
    const val SCREEN_ORIENTATION_LANDSCAPE = 0
    const val SCREEN_ORIENTATION_PORTRAIT = 1
    const val SCREEN_ORIENTATION_USER = 2
    const val SCREEN_ORIENTATION_BEHIND = 3
    const val SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6
    const val SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7
    const val SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8
    const val SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9
    const val SCREEN_ORIENTATION_LOCKED = 14
    const val SCREEN_ORIENTATION_FULL_SENSOR = 10
    const val SCREEN_ORIENTATION_USER_LANDSCAPE = 11
    const val SCREEN_ORIENTATION_USER_PORTRAIT = 12
}
internal object DesktopOriginalVideoWindowOrientation {
    const val ORIENTATION_UNDEFINED = 0
    const val ORIENTATION_PORTRAIT = 1
    const val ORIENTATION_LANDSCAPE = 2
}
internal object DesktopOriginalVideoPhysicalOrientation {
    const val ORIENTATION_UNKNOWN = -1
}
