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
import com.android.purebilibili.feature.home.resolveHomeFeedStoredColumnCount
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
 * The space page and the profile page in the desktop layout (TON-1060), pure. Both sides of every comparison run the whole
 * chain of their screen down to the columns and the card the grid draws, including the column preference of the page
 * (rememberFeedGridColumnPreference: resolveFeedGridColumnPreference with the customization switch, the page's own pinch
 * choice and the bounds the screen hands it). The Home side is the generated Home screen (desktop/tools/ux_home_layout.py on
 * HomeScreen.kt): the delegation from the raw stored count, the measured width on an expanded window and the window width
 * otherwise, the column function with the effective stored count, the pinch bounds of that width, the card layout with the
 * delegation, its side padding, and the box held at the width. The space side is desktopSpaceFeedGrid with the values the
 * patched SpaceScreen.kt hands it, the same preference, the card layout of the patch and the side padding the grid draws
 * (outerPaddingDp). What the patch wires is checked by test_ux_space_profile_layout.py.
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
            listOf(2103, 2104, 2730, 2731, 3015, 3016, 3017, 3413, 3414, 3840, 5120)
        ).distinct().sorted()

    /** The grid settings both pages read (HomeSettings): the customization switch and the raw stored counts (0 = automatic). */
    private data class GridSettings(val customization: Boolean, val compactCount: Int, val count: Int) {
        fun stored(sizeClass: WindowWidthSizeClass) = resolveHomeFeedStoredColumnCount(sizeClass, compactCount, count)

        /** effectiveGridColumnCount(Compact): the stored count only while customization is on. */
        fun effective(sizeClass: WindowWidthSizeClass) =
            resolveHomeFeedStoredColumnCount(sizeClass, if (customization) compactCount else 0, if (customization) count else 0)
    }

    private val defaults = GridSettings(customization = true, compactCount = 0, count = 0)

    /** The default settings, pinned counts with customization on and off (a count stays stored when the switch goes off). */
    private val settingsCases: List<GridSettings> = listOf(true, false).flatMap { on ->
        listOf(0 to 0, 2 to 2, 4 to 4, 0 to 4, 3 to 0, 0 to 7).map { (compact, wide) -> GridSettings(on, compact, wide) }
    }

    /** No pinch on the page, or a pinch that asked for these columns (onChange holds the choice in the page's bounds). */
    private val pinchCases: List<Int?> = listOf(null, 1, 3, 9)

    private fun presetFor(minCard: Int?): HomeFeedCardWidthPreset =
        HomeFeedCardWidthPreset.entries.first { it.minCardWidthDp == minCard }

    /** The width of a box that takes its slot (the window less the rail) held at a cap. */
    private fun boxWidth(slot: Int, cap: Dp): Int = if (cap == Dp.Infinity) slot else minOf(slot, cap.value.toInt())

    private fun drawnCard(box: Int, padding: Int, spacing: Int, columns: Int): Double =
        (box - 2.0 * padding - spacing * max(columns - 1, 0)) / max(columns, 1)

    /** rememberFeedGridColumnPreference for a page that has not been pinched (pinch null) or was pinched towards pinch. */
    private fun preference(settings: GridSettings, defaultColumns: Int, bounds: IntRange, pinch: Int?): Int =
        resolveFeedGridColumnPreference(settings.customization, defaultColumns, pinch?.coerceIn(bounds), bounds)

    private data class Feed(
        val contentWidth: Int,
        val box: Int,
        val bounds: IntRange,
        val columns: Int,
        val layout: HomeFeedCardLayout,
        val padding: Int,
        val card: Double,
    )

    /** The Home feed of the generated Home screen for a window, after the column preference. */
    private fun homeFeed(
        window: Int,
        footprint: Int,
        settings: GridSettings,
        preset: HomeFeedCardWidthPreset,
        style: HomeFeedCardStyle,
        pinch: Int? = null,
    ): Feed {
        val sizeClass = resolveWindowWidthSizeClass(window.dp)
        val delegated = desktopHomeFeedColumnsDelegated(0, settings.stored(sizeClass))
        val measured = desktopHomeFeedMeasuredWidth(window.dp, footprint, desktop, delegated)
        val contentWidth = if (sizeClass >= WindowWidthSizeClass.Expanded) measured.value.toInt() else window
        val gridColumns = desktopResolveHomeFeedGridColumns(desktop, contentWidth, 0, settings.effective(sizeClass), preset, sizeClass)
        val bounds = resolveHomeFeedPinchColumnBounds(sizeClass, contentWidth)
        val columns = preference(settings, gridColumns, bounds, pinch)
        val layout = desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(style, columns, sizeClass), measured.value.toInt(), columns, delegated)
        val box = boxWidth(desktopHomeFeedDesktopWidthDp(window.toFloat(), footprint).toInt(), contentWidth.dp)
        val padding = layout.outerPaddingDp
        return Feed(contentWidth, box, bounds, columns, layout, padding, drawnCard(box, padding, layout.itemSpacingDp, columns))
    }

    private fun spaceGrid(
        layout: DesktopWindowsUxHomeLayout,
        window: Int,
        footprint: Int,
        settings: GridSettings,
        preset: HomeFeedCardWidthPreset,
    ) = resolveWindowWidthSizeClass(window.dp).let { sizeClass ->
        desktopSpaceFeedGrid(
            layout, window, footprint, sizeClass >= WindowWidthSizeClass.Expanded, sizeClass,
            fixedColumnCount = settings.effective(sizeClass),
            delegationColumnCount = settings.stored(sizeClass),
            cardWidthPreset = preset,
            vendoredContentMaxWidthDp = vendoredCap,
        )
    }

    /** The contribution grid of the patched space page, after the column preference: the card layout and what it draws. */
    private fun spaceFeed(
        window: Int,
        footprint: Int,
        settings: GridSettings,
        preset: HomeFeedCardWidthPreset,
        style: HomeFeedCardStyle,
        pinch: Int? = null,
    ): Feed {
        val sizeClass = resolveWindowWidthSizeClass(window.dp)
        val grid = spaceGrid(desktop, window, footprint, settings, preset)
        val columns = preference(settings, grid.columns, grid.bounds, pinch)
        val measured = desktopHomeFeedMeasuredWidth(window.dp, footprint, desktop, grid.delegated).value.toInt()
        val cardColumns = grid.cardLayoutColumns(pageColumns = columns, contributionColumns = columns)
        val layout = desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(style, cardColumns, sizeClass), measured, cardColumns, grid.delegated)
        val box = boxWidth(desktopHomeFeedDesktopWidthDp(window.toFloat(), footprint).toInt(), grid.maxWidth)
        val padding = grid.outerPaddingDp(layout.outerPaddingDp)
        return Feed(grid.contentWidthDp, box, grid.bounds, columns, layout, padding, drawnCard(box, padding, layout.itemSpacingDp, columns))
    }

    @Test fun theContributionGridIsTheHomeGridAfterThePreferenceOfEachPage() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (window in widths) {
                val footprint = desktopRailPresentation(mode, window, null).footprintDp
                for (preset in HomeFeedCardWidthPreset.entries) {
                    for (style in HomeFeedCardStyle.entries) {
                        for (settings in settingsCases) {
                            for (pinch in pinchCases) {
                                val label = "$mode $window $preset $style $settings $pinch"
                                val home = homeFeed(window, footprint, settings, preset, style, pinch)
                                val space = spaceFeed(window, footprint, settings, preset, style, pinch)
                                assertEquals(home.contentWidth, space.contentWidth, label)
                                assertEquals(home.box, space.box, label)
                                assertEquals(home.bounds, space.bounds, label)
                                assertEquals(home.columns, space.columns, label)
                                assertEquals(home.layout, space.layout, label)
                                assertEquals(home.padding, space.padding, label)
                                assertTrue(abs(home.card - space.card) <= 1e-9, "$label ${home.card} ${space.card}")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun everyGoldenRowOfTheHomeFeedIsTheSpaceGridToo() {
        // With customization off the preference keeps the columns the grid starts with, so the grid draws the golden row itself.
        val off = GridSettings(customization = false, compactCount = 0, count = 0)
        for (row in DesktopUxContract.objects(feed.getValue("golden"))) {
            val id = DesktopUxContract.string(row, "id")
            val window = DesktopUxContract.int(row, "width_dp")
            val mode = row["mode"]?.jsonPrimitive?.content?.let { DesktopWindowsUxRailMode.valueOf(it) } ?: DesktopWindowsUxRailMode.AUTO
            val minCard = if ("min_card" in row) DesktopUxContract.int(row, "min_card") else null
            val expect = row.getValue("expect").jsonObject
            val footprint = desktopRailPresentation(mode, window, null).footprintDp
            val space = spaceFeed(window, footprint, off, presetFor(minCard), HomeFeedCardStyle.BILIPAI)
            assertEquals(DesktopUxContract.int(expect, "wc"), space.contentWidth, id)
            assertEquals(DesktopUxContract.int(expect, "cols"), space.columns, id)
            assertEquals(DesktopUxContract.int(expect, "grid"), space.box - 2 * space.padding, id)
            if (!expect.getValue("capped").jsonPrimitive.content.toBoolean()) {
                assertTrue(abs(space.card - DesktopUxContract.double(expect, "card")) <= 0.05 + 1e-9, "$id ${space.card}")
            } else {
                assertEquals(DESKTOP_HOME_MAX_CARD_DP.toDouble(), space.card, id)
            }
        }
    }

    @Test fun theWidePrimaryDisplayShowsTheHomeColumnsAndCards() {
        // The 5120 px primary display at 150 %. Home starts at the 10 columns of the density rule and its preference holds them
        // in the pinch bounds of the width (2..7) while customization is on (the default); the space grid shows the same 7 cards
        // of the same width. The vendored page showed 7 too, but in its 1280 dp box: much narrower cards than Home.
        val window = 3413
        val sizeClass = resolveWindowWidthSizeClass(window.dp)
        val footprint = desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, window, null).footprintDp
        val home = homeFeed(window, footprint, defaults, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI)
        val space = spaceFeed(window, footprint, defaults, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI)
        assertEquals(10, spaceGrid(desktop, window, footprint, defaults, HomeFeedCardWidthPreset.AUTO).columns)
        assertEquals(2..7, home.bounds)
        assertEquals(7, home.columns)
        assertEquals(7, space.columns)
        assertEquals(home.card, space.card)
        val vendoredColumns = resolveFeedGridColumnPreference(
            true,
            resolveSpaceContentGridColumnCount(window, 0, HomeFeedCardWidthPreset.AUTO, vendoredCap, sizeClass),
            null,
            resolveHomeFeedPinchColumnBounds(sizeClass, window),
        )
        assertEquals(7, vendoredColumns)
        val vendoredLayout = resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, vendoredColumns, sizeClass)
        val vendoredCard = drawnCard(
            minOf(desktopHomeFeedDesktopWidthDp(window.toFloat(), footprint).toInt(), vendoredCap),
            maxOf(16, vendoredLayout.outerPaddingDp),
            vendoredLayout.itemSpacingDp,
            vendoredColumns,
        )
        assertTrue(vendoredCard < home.card, "vendored $vendoredCard home ${home.card}")
        // With customization off neither preference holds the rule: 10 on both pages.
        val off = GridSettings(customization = false, compactCount = 0, count = 0)
        assertEquals(10, homeFeed(window, footprint, off, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI).columns)
        assertEquals(10, spaceFeed(window, footprint, off, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI).columns)
    }

    @Test fun aPinnedCountDrawsTheHomeCardWidth() {
        // A pinned 4 in a window whose delegated feed is the vendored 1280 dp: the vendored margin of the space page (at least
        // 16 dp) made its cards 307.5 dp against Home's 312.5 dp; the grid now draws Home's 6 dp padding.
        val pinned = GridSettings(customization = true, compactCount = 0, count = 4)
        val window = 1600
        val footprint = desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, window, null).footprintDp
        val home = homeFeed(window, footprint, pinned, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI)
        val space = spaceFeed(window, footprint, pinned, HomeFeedCardWidthPreset.AUTO, HomeFeedCardStyle.BILIPAI)
        assertEquals(1280, home.box)
        assertEquals(4, space.columns)
        assertEquals(6, space.padding)
        assertEquals(312.5, home.card)
        assertEquals(home.card, space.card)
    }

    @Test fun theClassicLayoutIsTheVendoredPage() {
        for (window in widths) {
            for (settings in settingsCases + GridSettings(true, -1, -1) + GridSettings(true, 1, 6)) {
                for (preset in HomeFeedCardWidthPreset.entries) {
                    for (footprint in listOf(0, 72, 216)) {
                        val sizeClass = resolveWindowWidthSizeClass(window.dp)
                        val grid = spaceGrid(classic, window, footprint, settings, preset)
                        val label = "$window $settings $preset $footprint"
                        assertTrue(grid.delegated && !grid.desktop, label)
                        assertEquals(vendoredCap.dp, grid.maxWidth, label)
                        assertEquals(
                            resolveSpaceContentGridColumnCount(window, settings.effective(sizeClass), preset, vendoredCap, sizeClass),
                            grid.columns,
                            label,
                        )
                        assertEquals(resolveHomeFeedPinchColumnBounds(sizeClass, window), grid.bounds, label)
                        assertEquals(3, grid.dynamicColumns(3, 99), label)
                        assertEquals(5, grid.cardLayoutColumns(pageColumns = 5, contributionColumns = 2), label)
                        assertEquals(16, grid.outerPaddingDp(6), label)
                        assertEquals(20, grid.outerPaddingDp(20), label)
                    }
                }
            }
        }
    }

    @Test fun aStoredCountKeepsTheVendoredCapOfTheDelegatedHomeFeed() {
        // The generated Home screen decides the delegation on the raw stored count, so a count that stays stored while
        // customization is off still caps the feed; the space grid follows it.
        for (window in widths) {
            for (count in listOf(1, 3, 6)) {
                for (on in listOf(true, false)) {
                    val grid = spaceGrid(desktop, window, 216, GridSettings(on, count, count), HomeFeedCardWidthPreset.AUTO)
                    assertTrue(grid.delegated && !grid.desktop, "$window $count $on")
                    assertEquals(resolveHomeFeedMaxContentWidth(), grid.maxWidth, "$window $count $on")
                    assertTrue(grid.contentWidthDp <= window, "$window $count $on")
                }
            }
        }
        assertEquals(Dp.Infinity, spaceGrid(desktop, 3413, 216, defaults, HomeFeedCardWidthPreset.AUTO).maxWidth)
    }

    @Test fun theDynamicTabFillsTheGridWithReadableCardsAndKeepsTheSidePadding() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (window in widths) {
                val footprint = desktopRailPresentation(mode, window, null).footprintDp
                val grid = spaceGrid(desktop, window, footprint, defaults, HomeFeedCardWidthPreset.AUTO)
                assertTrue(grid.desktop, "$mode $window")
                val sizeClass = resolveWindowWidthSizeClass(window.dp)
                val measured = desktopHomeFeedMeasuredWidth(window.dp, footprint, desktop).value.toInt()
                val contributionColumns = preference(defaults, grid.columns, grid.bounds, null)
                // Every tab resolves its card layout for the contribution columns: one side padding, so the header keeps its width.
                val contribution = grid.cardLayoutColumns(pageColumns = contributionColumns, contributionColumns = contributionColumns)
                val dynamicTab = grid.cardLayoutColumns(pageColumns = 2, contributionColumns = contributionColumns)
                assertEquals(contribution, dynamicTab, "$mode $window")
                val layout = desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, contribution, sizeClass), measured, contribution)
                val inset = grid.outerPaddingDp(layout.outerPaddingDp)
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
        val wide = spaceGrid(desktop, 3413, 216, defaults, HomeFeedCardWidthPreset.AUTO)
        assertEquals(8, wide.dynamicColumns(3, 24))
    }

    @Test fun theProfileHeaderGoesOnTopOnlyForTheDesktopLayoutOfAWideWindow() {
        assertTrue(desktopProfileHeaderOnTop(desktop, splitLayout = true))
        assertFalse(desktopProfileHeaderOnTop(desktop, splitLayout = false))
        assertFalse(desktopProfileHeaderOnTop(classic, splitLayout = true))
        assertFalse(desktopProfileHeaderOnTop(classic, splitLayout = false))
    }
}
