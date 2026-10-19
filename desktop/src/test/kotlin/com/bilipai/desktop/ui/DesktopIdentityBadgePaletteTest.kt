package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.ui.OfficialVerifyBadgeTone
import com.android.purebilibili.core.ui.components.resolveUpNameColor
import com.android.purebilibili.core.ui.components.resolveUserVipPink
import com.android.purebilibili.core.ui.officialVerifyColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pure checks of the v0.4.6 identity badge palette: no window, GPU, account or network.
 *
 * Each case fails against the vendored v0.2.5 palette. There an annual VIP name was the fixed FB7299 whatever the
 * caller passed, a monthly VIP name took the secondary argument, VIP status was compared with 1, and
 * officialVerifyColor did not exist. The upstream suites emitted beside this class cover the unchanged
 * corner-mark, staff-role and compact-badge rules.
 */
class DesktopIdentityBadgePaletteTest {
    private val onSurface = Color(0xFF202020)
    private val oldFixedPink = Color(0xFFFB7299)

    @Test
    fun `annual VIP name takes the supplied dark VIP pink and not the old fixed pink`() {
        val darkPink = resolveUserVipPink(isDark = true)

        assertEquals(Color(0xFFD44E7D), darkPink)
        assertEquals(
            darkPink,
            resolveUpNameColor(vipStatus = 1, vipType = 2, onSurface = onSurface, secondary = darkPink),
        )
        assertNotEquals(
            oldFixedPink,
            resolveUpNameColor(vipStatus = 1, vipType = 2, onSurface = onSurface, secondary = darkPink),
        )
    }

    @Test
    fun `annual VIP name takes the supplied light VIP pink and not the old fixed pink`() {
        val lightPink = resolveUserVipPink(isDark = false)

        assertEquals(Color(0xFFFF6699), lightPink)
        assertEquals(
            lightPink,
            resolveUpNameColor(vipStatus = 1, vipType = 2, onSurface = onSurface, secondary = lightPink),
        )
        assertNotEquals(
            oldFixedPink,
            resolveUpNameColor(vipStatus = 1, vipType = 2, onSurface = onSurface, secondary = lightPink),
        )
    }

    @Test
    fun `monthly VIP name no longer takes the secondary argument`() {
        val pink = resolveUserVipPink(isDark = true)

        assertEquals(
            onSurface,
            resolveUpNameColor(vipStatus = 1, vipType = 1, onSurface = onSurface, secondary = pink),
        )
        assertEquals(
            onSurface,
            resolveUpNameColor(vipStatus = 1, vipType = 0, onSurface = onSurface, secondary = pink),
        )
    }

    @Test
    fun `any positive VIP status counts as active and an inactive annual VIP does not`() {
        val pink = resolveUserVipPink(isDark = false)

        assertEquals(
            pink,
            resolveUpNameColor(vipStatus = 2, vipType = 2, onSurface = onSurface, secondary = pink),
        )
        assertEquals(
            onSurface,
            resolveUpNameColor(vipStatus = 0, vipType = 2, onSurface = onSurface, secondary = pink),
        )
        assertEquals(
            onSurface,
            resolveUpNameColor(vipStatus = -1, vipType = 2, onSurface = onSurface, secondary = pink),
        )
    }

    @Test
    fun `official verify colors are the single source of both bolt tones`() {
        assertEquals(Color(0xFFFFCC00), officialVerifyColor(OfficialVerifyBadgeTone.PERSONAL))
        assertEquals(Color(0xFF40C4FF), officialVerifyColor(OfficialVerifyBadgeTone.ORGANIZATION))
        assertNotEquals(
            officialVerifyColor(OfficialVerifyBadgeTone.PERSONAL),
            officialVerifyColor(OfficialVerifyBadgeTone.ORGANIZATION),
        )
    }
}
