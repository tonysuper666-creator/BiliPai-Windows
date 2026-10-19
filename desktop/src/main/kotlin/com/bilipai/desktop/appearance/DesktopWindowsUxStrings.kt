package com.bilipai.desktop.appearance

/**
 * Every user-facing text of the windows_ux flags store. The UX source hygiene suite exempts only
 * files named *Strings.kt from its no-Chinese rule, so no other windows_ux file may spell a label out.
 */
internal object DesktopWindowsUxStrings {
    /** A stored value was not understood; the default is used for that value. */
    const val INVALID_VALUES = "界面布局设置无效，已暂用默认值。"

    /** The whole namespace could not be read; every flag uses its default. */
    const val UNREADABLE = "界面布局设置无法读取，已暂用默认值。"

    /** A change was applied for this session but could not be saved. */
    const val WRITE_FAILED = "界面布局设置无法保存"
}
