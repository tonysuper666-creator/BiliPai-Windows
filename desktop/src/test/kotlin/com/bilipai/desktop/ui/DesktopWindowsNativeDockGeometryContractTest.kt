package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.components.FloatingBottomBarGeometryMode
import com.android.purebilibili.feature.home.components.resolveFloatingDockIndicatorHeightDp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DesktopWindowsNativeDockGeometryContractTest {
    @Test fun segmentedControlsKeepTheRequestedHeightAsTheirUpperBound() {
        assertEquals(30f, resolveFloatingDockIndicatorHeightDp(30f,48f,FloatingBottomBarGeometryMode.Segmented,40f),0.001f)
        assertEquals(30f, resolveFloatingDockIndicatorHeightDp(30f,72f,FloatingBottomBarGeometryMode.Segmented,44f),0.001f)
        assertEquals(30f, resolveFloatingDockIndicatorHeightDp(30f,90f,FloatingBottomBarGeometryMode.Segmented,56f),0.001f)
        assertEquals(30f, resolveFloatingDockIndicatorHeightDp(30f,12f,FloatingBottomBarGeometryMode.TopNavigation,40f),0.001f)
    }
    @Test fun authoredDockHeightOnlyShrinksForAnActuallyNarrowSlotOrExplicitReference() {
        assertEquals(39f, resolveFloatingDockIndicatorHeightDp(39f,42f),0.001f)
        assertEquals(20f, resolveFloatingDockIndicatorHeightDp(39f,27f),0.001f)
        assertEquals(19.5f, resolveFloatingDockIndicatorHeightDp(39f,42f,proportionalReferenceWidthDp=84f),0.001f)
        assertEquals(49f, resolveFloatingDockIndicatorHeightDp(52f,75f,FloatingBottomBarGeometryMode.Dock,56f),0.001f)
    }
}
