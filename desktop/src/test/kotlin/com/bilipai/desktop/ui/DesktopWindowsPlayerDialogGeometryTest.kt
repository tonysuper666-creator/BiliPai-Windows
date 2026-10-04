package com.bilipai.desktop.ui

import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowsPlayerDialogGeometryTest {
    @Test
    fun initialOuterBoundsUseActualClientOriginAndIncludeNativeDecoration() {
        val client = Rectangle(55, 78, 1346, 863)
        val actual = desktopWindowsPlayerDialogInitialBounds(
            client, Insets(30, 7, 7, 7), 1.875f, 1.5, 1.5,
            preferredHeightDp = 640,
        )
        assertEquals(Rectangle(321, 91, 814, 837), actual)
        assertTrue(client.contains(actual))
    }

    @Test
    fun systemDpiIsRemovedOnceWhileTheUserZoomRemains() {
        val client = Rectangle(-1300, 80, 1346, 863)
        val decoration = Insets(30, 7, 7, 7)
        val at150Percent = desktopWindowsPlayerDialogInitialBounds(
            client, decoration, 1.875f, 1.5, 1.5, preferredHeightDp = 640,
        )
        val at250Percent = desktopWindowsPlayerDialogInitialBounds(
            client, decoration, 3.125f, 2.5, 2.5, preferredHeightDp = 640,
        )
        assertEquals(at150Percent, at250Percent)
        assertEquals(814, at250Percent.width)
        assertTrue(client.contains(at250Percent))
    }

    @Test
    fun smallOwnerCapsTheCompleteOuterWindowAtEveryUserZoom() {
        val client = Rectangle(350, 270, 420, 280)
        for (density in listOf(.75f, 1.875f, 3f)) {
            val actual = desktopWindowsPlayerDialogInitialBounds(
                client, Insets(30, 7, 7, 7), density, 1.5, 1.5,
            )
            assertTrue(client.contains(actual))
            assertTrue(actual.width > 0 && actual.height > 0)
            assertEquals(client.x + (client.width - actual.width) / 2, actual.x)
            assertEquals(client.y + (client.height - actual.height) / 2, actual.y)
        }
    }

    @Test
    fun eachAxisUsesItsActualNativeCoordinateScale() {
        val actual = desktopWindowsPlayerDialogInitialBounds(
            Rectangle(0, 0, 2000, 2000), Insets(30, 7, 7, 7),
            1.875f, 1.5, 1.25, preferredHeightDp = 640,
        )
        assertEquals(814, actual.width)
        assertEquals(997, actual.height)
    }
}
