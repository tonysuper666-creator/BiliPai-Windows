package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/** Verify the compiled popup facade before native tests, without initializing
 * Win32 libraries or creating any Window. A malformed class can compile yet
 * throw ClassFormatError when the first real popup tries to load it. */
class DesktopCommandPopupBytecodeTest {
    @Test fun compiledPopupFacadeCanBeLoadedAndLinkedWithoutInitializingWindows() {
        val facade = Class.forName(
            "com.bilipai.desktop.ui.DesktopCommandPopupWindowKt", false, javaClass.classLoader)
        assertTrue(facade.declaredMethods.any { it.name.startsWith("DesktopDecorativeVideoFeedbackPopup") })
        assertTrue(facade.declaredMethods.any { it.name.startsWith("DesktopDecorativeBrandSuccessPopup") })
    }
}
