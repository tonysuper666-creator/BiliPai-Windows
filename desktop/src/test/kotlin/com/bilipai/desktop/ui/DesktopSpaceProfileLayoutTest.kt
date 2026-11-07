package com.bilipai.desktop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.core.util.resolveWindowWidthSizeClass
import com.android.purebilibili.feature.home.HomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveFeedGridColumnPreference
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveHomeFeedMaxContentWidth
import com.android.purebilibili.feature.home.resolveHomeFeedPinchColumnBounds
import com.android.purebilibili.feature.space.resolveSpaceContentGridColumnCount
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The space page and the profile page in the desktop layout (TON-1060), pure. The Home side of every comparison is the Home
 * feed as the generated Home screen computes it (DesktopHomeFeedLayoutTest proves those functions against the contract): the
 * measured width on an expanded window and the window width otherwise, the Home column function, the card layout of the
 * width the rail leaves, and the box held at the measured width. The space side is desktopSpaceFeedGrid with the values the
 * patched SpaceScreen.kt hands it, and the card it draws is computed from the card layout with the padding the space grid
 * draws (maxOf(16, outerPaddingDp)) and its spacing. What the patch wires is checked by test_ux_space_profile_layout.py.
 */
class DesktopSpaceProfileLayoutTest {
    private val desktop = DesktopWindowsUxHomeLayout.DESKTOP
    private val classic = DesktopWindowsUxHomeLayout.CLASSIC
    private val feed = DesktopUxContract.section("feed")
    private val vendoredCap = 1280

    /** Narrow, wide and both sides of every threshold: the size classes, the rail switch, the 2800 dp column cap, 5120 / 1.5. */
    private val widths: List<Int> = (
        (360..4200 step 23) +
            listOf(599, 600, 839, 840, 841, 959, 960, 1099, 1100, 1199, 1200, 1279, 1280, 1281, 1599, 1600) +
            listOf(2730, 2731, 3015, 3016, 3017, 3413, 3414, 3840, 5120)
        ).distinct().sorted()

    private fun presetFor(minCard: Int?): HomeFeedCardWidthPreset =
        HomeFeedCardWidthPreset.entries.first { it.minCardWidthDp == minCard }

    /** The width of a box that takes its slot (the window less the rail) held at a cap. */
    private fun boxWidth(slot: Int, cap: Dp): Int = if (cap == Dp.Infinity) slot else minOf(slot, cap.value.toInt())

    private fun drawnCard(layout: HomeFeedCardLayout, box: Int, columns: Int, minimumPadding: Int = 0): Double =
        (box - 2.0 * max(minimumPadding, layout.outerPaddingDp) - layout.itemSpacingDp * max(columns - 1, 0)) / max(columns, 1)

    private data class Feed(val contentWidth: Int, val box: Int, val columns: Int, val layout: HomeFeedCardLayout, val card: Double)

    /** The Home feed of the generated Home screen for a window. */
    private fun homeFeed(window: Int, footprint: Int, fixed: Int, preset: HomeFeedCardWidthPreset, style: HomeFeedCardStyle): Feed {
        val sizeClass = resolveWindowWidthSizeClass(window.dp)
        val delegated = desktopHomeFeedColumnsDelegated(0, fixed)
        val measured = desktopHomeFeedMeasuredWidth(window.dp, footprint, desktop, delegated)
        val contentWidth = if (sizeClass >= WindowWidthSizeClass.Expanded) measured.value.toInt() else window
        val columns = desktopResolveHomeFeedGridColumns(desktop, contentWidth, 0, fixed, preset, sizeClass)
        val layout = desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(style, columns, sizeClass), measured.value.toInt(), columns, delegated)
        val box = boxWidth(desktopHomeFeedDesktopWidthDp(window.toFloat(), footprint).toInt(), contentWidth.dp)
        return Feed(contentWidth, box, columns, layout, drawnCard(layout, box, columns))
    }

    private fun spaceGrid(layout: DesktopWindowsUxHomeLayout, window: Int, footprint: Int, fixed: Int, preset: HomeFeedCardWidthPreset) =
        resolveWindowWidthSizeClass(window.dp).let { sizeClass ->
            desktopSpaceFeedGrid(layout, window, footprint, sizeClass >= WindowWidthSizeClass.Expanded, sizeClass, fixed, preset, vendoredCap)
        }

    /** The contribution grid of the patched space page: the preference of the grid's columns, the card layout and what it draws. */
    private fun spaceFeed(window: Int, footprint: Int, fixed: Int, preset: HomeFeedCardWidthPreset, style: HomeFeedCardStyle): Feed {
        val sizeClass = resolveWindowWidthSizeClass(window.dp)
        val grid = spaceGrid(desktop, window, footprint, fixed, preset)
        val columns = resolveFeedGridColumnPreference(true, grid.columns, null, grid.bounds)
        val measured = desktopHomeFeedMeasuredWidth(window.dp, footprint, desktop, grid.delegated).value.toInt()
        val cardColumns = grid.cardLayoutColumns(pageColumns = columns, contributionColumns = columns)
        val layout = desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(style, cardColumns, sizeClass), measured, cardColumns, grid.delegated)
        val box = boxWidth(desktopHomeFeedDesktopWidthDp(window.toFloat(), footprint).toInt(), grid.maxWidth)
        return Feed(grid.contentWidthDp, box, columns, layout, drawnCard(layout, box, columns, minimumPadding = 16))
    }

    @Test fun theContributionGridIsTheHomeGridAtTheSameWindow() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (window in widths) {
                val footprint = desktopRailPresentation(mode, window, null).footprintDp
                for (preset in HomeFeedCardWidthPreset.entries) {
                    for (style in HomeFeedCardStyle.entries) {
                        for (fixed in listOf(0, 2, 4)) {
                            val label = "$mode $window $preset $style $fixed"
                            val home = homeFeed(window, footprint, fixed, preset, style)
                            val space = spaceFeed(window, footprint, fixed, preset, style)
                            assertEquals(home.contentWidth, space.contentWidth, label)
                            assertEquals(home.box, space.box, label)
                            assertEquals(home.columns, space.columns, label)
                            assertEquals(home.layout, space.layout, label)
                            if (home.layout.outerPaddingDp >= 16) {
                                assertTrue(abs(home.card - space.card) <= 1e-9, "$label ${home.card} ${space.card}")
                            } else {
                                // A pinned count keeps the vendored cards (6 dp); the space page keeps its vendored 16 dp margin.
                                assertTrue(desktopHomeFeedColumnsDelegated(0, fixed), label)
                                assertTrue(abs(drawnCard(home.layout, home.box, home.columns, 16) - space.card) <= 1e-9, label)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun everyGoldenRowOfTheHomeFeedIsTheSpaceGridToo() {
        for (row in DesktopUxContract.objects(feed.getValue("golden"))) {
            val id = DesktopUxContract.string(row, "id")
            val window = DesktopUxContract.int(row, "width_dp")
            val mode = row["mode"]?.jsonPrimitive?.content?.let { DesktopWindowsUxRailMode.valueOf(it) } ?: DesktopWindowsUxRailMode.AUTO
            val minCard = if ("min_card" in row) DesktopUxContract.int(row, "min_card") else null
            val expect = row.getValue("expect").jsonObject
            val footprint = desktopRailPresentation(mode, window, null).footprintDp
            val space = spaceFeed(window, footprint, 0, presetFor(minCard), HomeFeedCardStyle.BILIPAI)
            assertEquals(DesktopUxContract.int(expect, "wc"), space.contentWidth, id)
            assertEquals(DesktopUxContract.int(expect, "cols"), space.columns, id)
            assertEquals(DesktopUxContract.int(expect, "grid"), space.box - 2 * space.layout.outerPaddingDp, id)
            if (!expect.getValue("capped").jsonPrimitive.content.toBoolean()) {
                assertTrue(abs(space.card - DesktopUxContract.double(expect, "card")) <= 0.05 + 1e-9, "$id ${space.card}")
            } else {
                assertEquals(DESKTOP_HOME_MAX_CARD_DP.toDouble(), space.card, id)
            }
        }
    }

    @Test fun theVendoredPageClampedAWideWindowToFewerColumnsThanHome() {
        // The 5120 px primary display at 150 %: the vendored page capped the grid at 1280 dp and its preference clamped to the
        // pinch bounds (at most 7); Home shows 10 columns. The new grid holds its columns through the preference.
        val window = 3413
        val sizeClass = resolveWindowWidthSizeClass(window.dp)
        val footprint = desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, window, null).footprintDp
        val home = homeFeed(window, footprint, 0, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI)
        val vendoredColumns = resolveFeedGridColumnPreference(
            true,
            resolveSpaceContentGridColumnCount(window, 0, HomeFeedCardWidthPreset.AUTO, vendoredCap, sizeClass),
            null,
            resolveHomeFeedPinchColumnBounds(sizeClass, window),
        )
        assertEquals(10, home.columns)
        assertTrue(vendoredColumns < home.columns, "vendored $vendoredColumns home ${home.columns}")
        assertEquals(10, spaceFeed(window, footprint, 0, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI).columns)
        // The bounds never hold the grid below its own columns, and a pinch still reaches below them.
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (w in widths) {
                val grid = spaceGrid(desktop, w, desktopRailPresentation(mode, w, null).footprintDp, 0, HomeFeedCardWidthPreset.AUTO)
                assertEquals(grid.columns, resolveFeedGridColumnPreference(true, grid.columns, null, grid.bounds), "$mode $w")
                assertTrue(grid.bounds.last >= grid.columns && grid.bounds.first <= grid.columns, "$mode $w ${grid.bounds}")
                assertTrue(grid.bounds.first >= 1, "$mode $w")
            }
        }
    }

    @Test fun theClassicLayoutIsTheVendoredPage() {
        for (window in widths) {
            for (fixed in listOf(-1, 0, 1, 3, 6)) {
                for (preset in HomeFeedCardWidthPreset.entries) {
                    for (footprint in listOf(0, 72, 216)) {
                        val sizeClass = resolveWindowWidthSizeClass(window.dp)
                        val grid = spaceGrid(classic, window, footprint, fixed, preset)
                        val label = "$window $fixed $preset $footprint"
                        assertTrue(grid.delegated && !grid.desktop, label)
                        assertEquals(vendoredCap.dp, grid.maxWidth, label)
                        assertEquals(resolveSpaceContentGridColumnCount(window, fixed, preset, vendoredCap, sizeClass), grid.columns, label)
                        assertEquals(resolveHomeFeedPinchColumnBounds(sizeClass, window), grid.bounds, label)
                        assertEquals(3, grid.dynamicColumns(3, 99), label)
                        assertEquals(5, grid.cardLayoutColumns(pageColumns = 5, contributionColumns = 2), label)
                    }
                }
            }
        }
    }

    @Test fun aPinnedCountKeepsTheVendoredCapOfTheDelegatedHomeFeed() {
        for (window in widths) {
            for (fixed in listOf(1, 3, 6)) {
                val grid = spaceGrid(desktop, window, 216, fixed, HomeFeedCardWidthPreset.AUTO)
                assertTrue(grid.delegated && !grid.desktop, "$window $fixed")
                assertEquals(resolveHomeFeedMaxContentWidth(), grid.maxWidth, "$window $fixed")
                assertTrue(grid.contentWidthDp <= window, "$window $fixed")
            }
        }
        assertEquals(Dp.Infinity, spaceGrid(desktop, 3413, 216, 0, HomeFeedCardWidthPreset.AUTO).maxWidth)
    }

    @Test fun theDynamicTabFillsTheGridWithReadableCardsAndKeepsTheSidePadding() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (window in widths) {
                val footprint = desktopRailPresentation(mode, window, null).footprintDp
                val grid = spaceGrid(desktop, window, footprint, 0, HomeFeedCardWidthPreset.AUTO)
                assertTrue(grid.desktop, "$mode $window")
                val sizeClass = resolveWindowWidthSizeClass(window.dp)
                val measured = desktopHomeFeedMeasuredWidth(window.dp, footprint, desktop).value.toInt()
                // Every tab resolves its card layout for the contribution columns: one side padding, so the header keeps its width.
                val contribution = grid.cardLayoutColumns(pageColumns = grid.columns, contributionColumns = grid.columns)
                val dynamicTab = grid.cardLayoutColumns(pageColumns = 2, contributionColumns = grid.columns)
                assertEquals(contribution, dynamicTab, "$mode $window")
                val layout = desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, contribution, sizeClass), measured, contribution)
                val inset = layout.outerPaddingDp
                val columns = grid.dynamicColumns(vendoredDynamicColumns = 3, sideInsetDp = inset)
                val usable = grid.contentWidthDp - 2 * inset
                val card = (usable - DESKTOP_HOME_GUTTER_DP * (columns - 1)).toDouble() / columns
                assertTrue(columns >= 1, "$mode $window")
                if (columns > 1) assertTrue(card >= DESKTOP_SPACE_DYNAMIC_MIN_CARD_DP, "$mode $window $card")
                val oneMore = (usable - DESKTOP_HOME_GUTTER_DP * columns).toDouble() / (columns + 1)
                assertTrue(oneMore < DESKTOP_SPACE_DYNAMIC_MIN_CARD_DP, "$mode $window $oneMore")
            }
        }
        // The 5120 px display at 150 %: the vendored page showed at most 3 dynamic cards in 1280 dp; the grid now holds 8.
        val wide = spaceGrid(desktop, 3413, 216, 0, HomeFeedCardWidthPreset.AUTO)
        assertEquals(8, wide.dynamicColumns(3, 24))
    }

    @Test fun theProfileHeaderGoesOnTopOnlyForTheDesktopLayoutOfAWideWindow() {
        assertTrue(desktopProfileHeaderOnTop(desktop, splitLayout = true))
        assertFalse(desktopProfileHeaderOnTop(desktop, splitLayout = false))
        assertFalse(desktopProfileHeaderOnTop(classic, splitLayout = true))
        assertFalse(desktopProfileHeaderOnTop(classic, splitLayout = false))
    }
}
