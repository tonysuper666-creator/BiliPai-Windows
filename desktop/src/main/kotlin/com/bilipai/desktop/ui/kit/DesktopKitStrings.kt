package com.bilipai.desktop.ui.kit

/**
 * The only user-facing text the component kit owns. Every other kit file takes its labels from the caller. The UX
 * source hygiene suite exempts only files named *Strings.kt from its no-Chinese rule.
 */
internal object DesktopKitStrings {
    /** Shown in a toggle or select row while its value has not been read from the store yet. */
    const val LOADING_SETTING = "读取设置…"
}
