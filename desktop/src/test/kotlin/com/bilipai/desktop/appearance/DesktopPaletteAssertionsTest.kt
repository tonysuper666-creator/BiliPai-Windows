package com.bilipai.desktop.appearance

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme
import kotlin.test.*

class DesktopPaletteAssertionsTest {
    @Test fun separateObjectsWithEveryRoleEqualHavePaletteValueEquality() {
        val material = lightColorScheme()
        val materialCopy = material.copy()
        val miuix = miuixLightColorScheme()
        val miuixCopy = miuix.copy()
        assertNotSame(material, materialCopy)
        assertNotSame(miuix, miuixCopy)
        assertDesktopPaletteEquals(material, materialCopy)
        assertDesktopPaletteEquals(miuix, miuixCopy)
    }

    @Test fun materialComparisonIncludesAll48RolesAndExactColorSpaceBits() {
        val base = lightColorScheme()
        val roles = desktopPaletteRoleValues(base)
        assertEquals(48, roles.size)
        assertTrue(setOf("primary", "onSurfaceVariant", "tertiaryFixedDim", "onTertiaryFixedVariant").all { it in roles })
        for (changed in listOf(base.copy(primary = Color.Red),
            base.copy(onSurfaceVariant = Color.Transparent), base.copy(tertiaryFixedDim = Color.Magenta))) {
            assertFailsWith<AssertionError> { assertDesktopPaletteEquals(base, changed) }
        }
        val srgb = base.copy(primary = Color(0.2f, 0.3f, 0.4f, 0.5f, ColorSpaces.Srgb))
        val p3 = base.copy(primary = Color(0.2f, 0.3f, 0.4f, 0.5f, ColorSpaces.DisplayP3))
        assertFailsWith<AssertionError> { assertDesktopPaletteEquals(srgb, p3) }
    }

    @Test fun miuixComparisonIncludesAll53RolesIncludingLateAndDisabledColors() {
        val base = miuixLightColorScheme()
        val roles = desktopPaletteRoleValues(base)
        assertEquals(53, roles.size)
        assertTrue(setOf("disabledSecondaryVariant", "onSurfaceVariantSummary", "sliderBackground").all { it in roles })
        for (changed in listOf(base.copy(disabledSecondaryVariant = Color.Red),
            base.copy(onSurfaceVariantSummary = Color.Transparent), base.copy(sliderBackground = Color.Magenta))) {
            assertFailsWith<AssertionError> { assertDesktopPaletteEquals(base, changed) }
        }
    }

    @Test fun otherTypesAndDifferentPaletteTypesRetainNormalAssertionSemantics() {
        assertDesktopPaletteEquals(21, 21)
        assertDesktopPaletteEquals(listOf("first", "last"), listOf("first", "last"))
        assertDesktopPaletteEquals(null, null)
        assertFailsWith<AssertionError> { assertDesktopPaletteEquals(1, 2) }
        assertFailsWith<AssertionError> { assertDesktopPaletteEquals("a", "b") }
        assertFailsWith<AssertionError> { assertDesktopPaletteEquals<Any?>(null, "present") }
        assertFailsWith<AssertionError> { assertDesktopPaletteEquals<Any>(lightColorScheme(), miuixLightColorScheme()) }
    }
}
