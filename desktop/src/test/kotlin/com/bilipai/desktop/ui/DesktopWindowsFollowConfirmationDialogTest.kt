package com.bilipai.desktop.ui

import androidx.compose.runtime.Composer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure presentation rules of the Windows unfollow confirmation adapter. No window or composition is created. */
class DesktopWindowsFollowConfirmationDialogTest {
    @Test
    fun theOwnedWindowIsUsedOnlyWhileTheMainWindowIsShowing() {
        assertTrue(desktopFollowConfirmationUsesOwnedWindow(true))
        assertFalse(desktopFollowConfirmationUsesOwnedWindow(false), "a hidden Main window has no canvas to cover the alert")
        assertFalse(desktopFollowConfirmationUsesOwnedWindow(null), "without a Main window the plain alert is the only option")
    }

    @Test
    fun theOwnedWindowIsTitledForTheUnfollowChoiceAndTallEnoughForTheAlert() {
        assertEquals("取消关注", DESKTOP_FOLLOW_CONFIRMATION_TITLE)
        assertTrue(DESKTOP_FOLLOW_CONFIRMATION_HEIGHT_DP in 240..420, "must fit a three-line message and both buttons")
    }

    @Test
    fun theAdapterIsACompositionThatTakesTheUpstreamDismissRequestFirst() {
        val adapter = Class.forName("com.bilipai.desktop.ui.DesktopWindowsFollowConfirmationDialogKt")
            .declaredMethods.single { it.name == "DesktopWindowsFollowConfirmationDialog" }
        assertEquals(Function0::class.java, adapter.parameterTypes.first(), "onDismissRequest is the first parameter")
        assertTrue(adapter.parameterTypes.any { it == Composer::class.java }, "it is a composable function")
    }
}
