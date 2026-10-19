package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxSettings
import com.bilipai.desktop.ux.DesktopUxContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The player layout selection of the ordinary-video leaf against the shared contract. No window, no GPU, no network. */
class DesktopPlayerLayoutSelectionTest {
    private val flag = DesktopUxContract.objects(DesktopUxContract.section("flags").getValue("fields"))
        .first { DesktopUxContract.string(it, "key") == "player_layout" }
    private val values = DesktopUxContract.strings(flag.getValue("values"))

    @Test fun theContractAndTheEnumListTheSameValuesAndTheDefaultIsLegacy() {
        assertEquals(DesktopWindowsUxPlayerLayout.entries.map { it.name }, values)
        assertEquals("LEGACY", DesktopUxContract.string(flag, "default"))
        assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, DesktopWindowsUxSettings().playerLayout)
    }

    @Test fun withoutAnOverlayEveryRequestResolvesToLegacy() {
        for (name in values) {
            val requested = DesktopWindowsUxPlayerLayout.valueOf(name)
            assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, desktopPlayerEffectiveLayout(requested, overlayAvailable = false), name)
            assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, desktopPlayerEffectiveLayout(requested, DESKTOP_PLAYER_OVERLAY_AVAILABLE), name)
        }
    }

    @Test fun anOverlayIsShownOnlyForAnExplicitDesktopRequest() {
        assertEquals(DesktopWindowsUxPlayerLayout.DESKTOP,
            desktopPlayerEffectiveLayout(DesktopWindowsUxPlayerLayout.DESKTOP, overlayAvailable = true))
        assertEquals(DesktopWindowsUxPlayerLayout.LEGACY,
            desktopPlayerEffectiveLayout(DesktopWindowsUxPlayerLayout.LEGACY, overlayAvailable = true))
    }

    // State of slice V2. The slice that mounts the carrier overlay (V4a) flips the constant and this test in the same commit.
    @Test fun noOverlayIsMountedYet() {
        assertFalse(DESKTOP_PLAYER_OVERLAY_AVAILABLE)
    }
}
