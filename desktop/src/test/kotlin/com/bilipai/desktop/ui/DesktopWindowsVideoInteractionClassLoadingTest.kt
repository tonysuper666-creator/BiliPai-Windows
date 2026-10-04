package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowsVideoInteractionClassLoadingTest {
    @Test fun actualComposeInteractionClassCanBeDefinedWithoutOpeningAWindow() {
        // Define the actual generated class, without running its initializers,
        // Compose rendering, native actors, or any account/system-share action.
        val name = "com.bilipai.desktop.ui.DesktopWindowsVideoInteractionSectionKt"
        assertEquals(name, Class.forName(name, false, javaClass.classLoader).name)
    }
}
