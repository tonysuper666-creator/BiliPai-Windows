package com.bilipai.desktop.ui

import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.feature.home.HomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveHomeFeedGridColumns
import com.android.purebilibili.feature.home.resolveHomeFeedMaxContentWidth
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Home feed wrappers (slice H1) against the shared contract and against the vendored layout functions they delegate to.
 * Pure: the numbers of DesktopHomeFeedLayout.kt and DesktopHomeFeedLayoutPolicy.kt, the vendored functions, no window, no
 * GPU, no network. The composable wrappers (the flag key, the content width, the card layout) read composition locals and
 * are covered by the Python source checks; every decision they make is one of the functions tested here, the card layout
 * one through desktopSelectHomeFeedCardLayout. What the feed draws is checked from the card layout itself: its padding and
 * spacing give the card width and the grid width with the formula of the vendored cover request (HomeScreen.kt).
 */
class DesktopHomeFeedLayoutTest {
    private val feed = DesktopUxContract.section("feed")
    private val sweep = feed.getValue("sweep").jsonObject
    private val firstWidth = DesktopUxContract.int(sweep, "from")
    private val lastWidth = DesktopUxContract.int(sweep, "to")
    private val desktop = DesktopWindowsUxHomeLayout.DESKTOP
    private val classic = DesktopWindowsUxHomeLayout.CLASSIC

    private fun presetFor(minCard: Int?): HomeFeedCardWidthPreset =
        HomeFeedCardWidthPreset.entries.first { it.minCardWidthDp == minCard }

    /** The card layout the desktop feed gets for a content width and a column count, through the pure selection of the wrapper. */
    private fun desktopCardLayout(wc: Int, columns: Int, style: HomeFeedCardStyle = HomeFeedCardStyle.BILIPAI): HomeFeedCardLayout =
        desktopSelectHomeFeedCardLayout(desktop, resolveHomeFeedCardLayout(style, columns, WindowWidthSizeClass.ExtraLarge), wc, columns)

    /** The card width the feed draws for a layout: the formula of the vendored cover request, from the layout's real padding and spacing. */
    private fun drawnCard(layout: HomeFeedCardLayout, wc: Int, columns: Int): Double =
        (wc - layout.outerPaddingDp * 2.0 - layout.itemSpacingDp * max(columns - 1, 0)) / max(columns, 1)

    /** The grid width the feed draws for a layout: the content width less the padding on both sides. */
    private fun drawnGrid(layout: HomeFeedCardLayout, wc: Int): Int = wc - 2 * layout.outerPaddingDp

    /** The content width of the feed for a window width and a rail mode, through the wrapper, with the rail presentation carried along. */
    private fun contentWidth(mode: DesktopWindowsUxRailMode, width: Int, previous: DesktopRailPresentation? = null): Pair<DesktopRailPresentation, Int> {
        val presentation = desktopRailPresentation(mode, width, previous)
        val measured = desktopHomeFeedMeasuredWidth(width.dp, presentation.footprintDp, desktop)
        return presentation to measured.value.toInt()
    }

    @Test fun everyGoldenRowThroughTheWrappers() {
        for (row in DesktopUxContract.objects(feed.getValue("golden"))) {
            val id = DesktopUxContract.string(row, "id")
            val width = DesktopUxContract.int(row, "width_dp")
            val mode = row["mode"]?.jsonPrimitive?.content?.let { DesktopWindowsUxRailMode.valueOf(it) } ?: DesktopWindowsUxRailMode.AUTO
            val minCard = if ("min_card" in row) DesktopUxContract.int(row, "min_card") else null
            val expect = row.getValue("expect").jsonObject
            val (presentation, wc) = contentWidth(mode, width)
            assertEquals(DesktopUxContract.string(expect, "rail"), presentation.contractName, id)
            assertEquals(DesktopUxContract.int(expect, "wc"), wc, id)
            val columns = desktopResolveHomeFeedGridColumns(desktop, wc, 0, 0, presetFor(minCard), WindowWidthSizeClass.ExtraLarge)
            assertEquals(DesktopUxContract.int(expect, "cols"), columns, id)
            // What the feed draws: the card layout's own padding and spacing.
            val layout = desktopCardLayout(wc, columns)
            val card = drawnCard(layout, wc, columns)
            val grid = drawnGrid(layout, wc)
            val expectedGrid = DesktopUxContract.int(expect, "grid")
            assertEquals(DESKTOP_HOME_GUTTER_DP, layout.itemSpacingDp, id)
            assertEquals(DESKTOP_HOME_GUTTER_DP, layout.verticalItemSpacingDp, id)
            assertEquals(expectedGrid, grid, id)
            if (expect.getValue("capped").jsonPrimitive.boolean) {
                // The card is held at the maximum and the grid is centred: the side space is half of what the grid leaves over.
                assertEquals(DESKTOP_HOME_MAX_CARD_DP.toDouble(), card, id)
                assertEquals((wc - expectedGrid) / 2, layout.outerPaddingDp, id)
                assertTrue(layout.outerPaddingDp > DESKTOP_HOME_SIDE_PADDING_DP, id)
            } else {
                assertTrue(abs(card - DesktopUxContract.double(expect, "card")) <= 0.05 + 1e-9, id)
                assertEquals(DESKTOP_HOME_SIDE_PADDING_DP, layout.outerPaddingDp, id)
            }
        }
    }

    @Test fun theTwelveSpecRowsAreInTheContract() {
        val ids = DesktopUxContract.objects(feed.getValue("golden")).map { DesktopUxContract.string(it, "id") }.filter { it.startsWith("spec-") }
        assertEquals(12, ids.size)
    }

    @Test fun theSweepThroughTheWrappersFollowsTheDensityRule() {
        val switchPoints = mapOf(DesktopWindowsUxRailMode.AUTO to 1280, DesktopWindowsUxRailMode.EXPANDED to 1100)
        for (mode in listOf(DesktopWindowsUxRailMode.AUTO, DesktopWindowsUxRailMode.EXPANDED, DesktopWindowsUxRailMode.COLLAPSED)) {
            val drops = ArrayList<Int>()
            val lastColumns = HashMap<DesktopRailPresentation, Int>()
            var previousColumns = 0
            // Each width is a fresh evaluation of the rail (no hysteresis memory), as in the sweep of DesktopWindowsLayoutMetricsTest.
            for (width in firstWidth..lastWidth) {
                val (presentation, wc) = contentWidth(mode, width)
                val columns = desktopResolveHomeFeedGridColumns(desktop, wc, 0, 0, HomeFeedCardWidthPreset.AUTO, WindowWidthSizeClass.ExtraLarge)
                val label = "$mode $width"
                assertEquals(resolveDesktopHomeDensity(wc).columns, columns, label)
                assertTrue(wc >= DESKTOP_HOME_MIN_CONTENT_DP, label)
                assertTrue(columns >= DESKTOP_HOME_MIN_COLUMNS, label)
                assertTrue(columns >= (lastColumns[presentation] ?: 0), label)
                lastColumns[presentation] = columns
                // The card the feed draws (spec 3.6 property) is within the minimum and the maximum card.
                val card = drawnCard(desktopCardLayout(wc, columns), wc, columns)
                assertTrue(card >= DESKTOP_HOME_MIN_CARD_DP && card <= DESKTOP_HOME_MAX_CARD_DP, "$label $card")
                if (width > firstWidth && columns < previousColumns) drops.add(width)
                previousColumns = columns
            }
            val expected = switchPoints[mode]
            assertEquals(if (expected == null) emptyList<Int>() else listOf(expected), drops, mode.name)
        }
    }

    @Test fun theDesktopColumnsFollowTheCardWidthPreset() {
        for (preset in HomeFeedCardWidthPreset.entries) {
            for (width in firstWidth..lastWidth step 13) {
                val wc = contentWidth(DesktopWindowsUxRailMode.AUTO, width).second
                val columns = desktopResolveHomeFeedGridColumns(desktop, wc, 0, 0, preset, WindowWidthSizeClass.Large)
                assertEquals(resolveDesktopHomeDensity(wc, preset.minCardWidthDp).columns, columns, "$preset $width")
                // The card the feed draws is within the wanted minimum (the floor at least) and the maximum card. The wanted minimum
                // gets the tolerance of the rounding of the side space (a card is at most 1 / columns narrower), which only the
                // 320 dp preset can meet: its cards are the maximum card.
                val card = drawnCard(desktopCardLayout(wc, columns), wc, columns)
                val wanted = max(preset.minCardWidthDp ?: DESKTOP_HOME_MIN_CARD_DP, DESKTOP_HOME_CARD_FLOOR_DP)
                assertTrue(card >= wanted - 1.0 / columns - 1e-9 && card <= DESKTOP_HOME_MAX_CARD_DP, "$preset $width $card")
            }
        }
    }

    @Test fun theClassicLayoutIsTheVendoredFunctionForEveryInput() {
        for (preset in HomeFeedCardWidthPreset.entries) {
            for (sizeClass in WindowWidthSizeClass.entries) {
                for (displayMode in 0..2) {
                    for (fixed in listOf(-1, 0, 1, 3, 6)) {
                        for (width in 200..4200 step 37) {
                            assertEquals(
                                resolveHomeFeedGridColumns(width, displayMode, fixed, preset, sizeClass),
                                desktopResolveHomeFeedGridColumns(classic, width, displayMode, fixed, preset, sizeClass),
                                "$preset $sizeClass $displayMode $fixed $width",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun aPinnedCountAndTheSingleColumnModeStayVendoredInTheDesktopLayoutToo() {
        for (preset in HomeFeedCardWidthPreset.entries) {
            for (sizeClass in WindowWidthSizeClass.entries) {
                for (width in firstWidth..lastWidth step 29) {
                    for ((displayMode, fixed) in listOf(0 to 4, 0 to 1, 1 to 0, 1 to 4, 2 to 3)) {
                        assertEquals(
                            resolveHomeFeedGridColumns(width, displayMode, fixed, preset, sizeClass),
                            desktopResolveHomeFeedGridColumns(desktop, width, displayMode, fixed, preset, sizeClass),
                            "$preset $sizeClass $displayMode $fixed $width",
                        )
                    }
                }
            }
        }
        assertEquals(4, desktopResolveHomeFeedGridColumns(desktop, 3000, 0, 4, HomeFeedCardWidthPreset.AUTO, WindowWidthSizeClass.ExtraLarge))
        assertEquals(2, desktopResolveHomeFeedGridColumns(desktop, 3000, 1, 4, HomeFeedCardWidthPreset.AUTO, WindowWidthSizeClass.ExtraLarge))
    }

    @Test fun theColumnWrapperKeepsTheVendoredDefaultSizeClass() {
        for (width in listOf(300, 800, 1600)) {
            assertEquals(
                resolveHomeFeedGridColumns(width, 0, 0, HomeFeedCardWidthPreset.AUTO),
                desktopResolveHomeFeedGridColumns(classic, width, 0, 0, HomeFeedCardWidthPreset.AUTO),
                width.toString(),
            )
        }
    }

    @Test fun theClassicFeedWidthIsTheVendoredCap() {
        for (width in 300..4200 step 17) {
            for (footprint in listOf(0, 72, 172, 216)) {
                assertEquals(
                    minOf(width.dp, resolveHomeFeedMaxContentWidth()),
                    desktopHomeFeedMeasuredWidth(width.dp, footprint, classic),
                    "$width $footprint",
                )
            }
        }
        assertEquals(1280.dp, desktopHomeFeedMeasuredWidth(3000.dp, 216, classic))
        assertEquals(1000.5f.dp, desktopHomeFeedMeasuredWidth(1000.5f.dp, 216, classic))
    }

    @Test fun theDesktopFeedWidthIsTheWindowMinusTheRailAndKeepsTheFraction() {
        assertEquals(2514f, desktopHomeFeedDesktopWidthDp(2730f, 216))
        assertEquals(928.5f, desktopHomeFeedDesktopWidthDp(1000.5f, 72))
        assertEquals(928, desktopHomeFeedMeasuredWidth(1000.5f.dp, 72, desktop).value.toInt())
        assertEquals(3624f, desktopHomeFeedMeasuredWidth(3840.dp, 216, desktop).value)
        // The rail would leave nothing: the whole window width stays, so the feed never measures zero or less.
        assertEquals(100f, desktopHomeFeedDesktopWidthDp(100f, 216))
        assertEquals(216f, desktopHomeFeedDesktopWidthDp(216f, 216))
        assertEquals(1f, desktopHomeFeedDesktopWidthDp(217f, 216))
        assertEquals(0f, desktopHomeFeedDesktopWidthDp(0f, 0))
        // The vendored cap is gone in the desktop layout.
        assertTrue(desktopHomeFeedMeasuredWidth(3000.dp, 216, desktop) > resolveHomeFeedMaxContentWidth())
    }

    @Test fun aCardWidthIsTheRoomOfTheColumnsDividedByThem() {
        assertEquals(312.0, desktopHomeDensityCardWidthDp(360, 1))
        // A card wider than the maximum is held at it by the side space; an odd rest is rounded up, so the card is a dp narrower.
        assertEquals(320.0, desktopHomeDensityCardWidthDp(400, 1))
        assertEquals(319.0, desktopHomeDensityCardWidthDp(401, 1))
        assertEquals(desktopHomeDensityCardWidthDp(400, 1), desktopHomeDensityCardWidthDp(400, 0))
        assertEquals(desktopHomeDensityCardWidthDp(400, 1), desktopHomeDensityCardWidthDp(400, -3))
        assertTrue(abs(desktopHomeDensityCardWidthDp(2514, 8) - 294.25) <= 1e-9)
        assertEquals(320.0, desktopHomeDensityCardWidthDp(2784, 8))
        // For the rule's own columns it is the card of the rule when the grid is not held at the maximum card, and the maximum
        // card (less the rounding of the side space) when it is.
        for (width in firstWidth..lastWidth) {
            val wc = contentWidth(DesktopWindowsUxRailMode.AUTO, width).second
            val layout = resolveDesktopHomeDensity(wc)
            val card = desktopHomeDensityCardWidthDp(wc, layout.columns)
            if (layout.capped) {
                assertTrue(card <= DESKTOP_HOME_MAX_CARD_DP && card >= DESKTOP_HOME_MAX_CARD_DP - 1.0 / layout.columns - 1e-9, "$width $card")
            } else {
                assertTrue(abs(card - layout.cardWidthDp) <= 1e-9, width.toString())
            }
        }
    }

    @Test fun aSideInsetIsTheSidePaddingOrTheCentringRestRoundedUp() {
        assertEquals(DESKTOP_HOME_SIDE_PADDING_DP, desktopHomeDensitySideInsetDp(360, 1))
        assertEquals(40, desktopHomeDensitySideInsetDp(400, 1))
        assertEquals(41, desktopHomeDensitySideInsetDp(401, 1))
        assertEquals(desktopHomeDensitySideInsetDp(400, 1), desktopHomeDensitySideInsetDp(400, 0))
        assertEquals(desktopHomeDensitySideInsetDp(400, 1), desktopHomeDensitySideInsetDp(400, -3))
        // The contract rows that the rule holds at the maximum card: the grid is centred exactly (the rest is even).
        assertEquals(56, desktopHomeDensitySideInsetDp(2784, 8))
        assertEquals(140, desktopHomeDensitySideInsetDp(3624, 10))
        assertEquals(28, desktopHomeDensitySideInsetDp(1384, 4))
        // For the rule's own columns it is the rule's side inset rounded up, which is the side padding for a grid that is not held.
        for (width in firstWidth..lastWidth) {
            val wc = contentWidth(DesktopWindowsUxRailMode.AUTO, width).second
            for (minCard in listOf(null, 160, 200, 260, 320)) {
                val layout = resolveDesktopHomeDensity(wc, minCard)
                val inset = desktopHomeDensitySideInsetDp(wc, layout.columns)
                assertEquals(ceil(layout.sideInsetDp).toInt(), inset, "$width $minCard")
                assertTrue(layout.capped == (inset > DESKTOP_HOME_SIDE_PADDING_DP), "$width $minCard")
            }
        }
    }

    @Test fun theCardLayoutCarriesTheRuleGeometryAndChangesTheCompactMetadataOnlyToFalse() {
        for (style in HomeFeedCardStyle.entries) {
            for (sizeClass in WindowWidthSizeClass.entries) {
                for (columns in 1..10) {
                    for (width in 884..3624 step 61) {
                        val original = resolveHomeFeedCardLayout(style, columns, sizeClass)
                        val result = desktopHomeDensityCardLayout(original, width, columns)
                        val label = "$style $sizeClass $columns $width"
                        val card = desktopHomeDensityCardWidthDp(width, columns)
                        val expectedCompact = original.compactMetadata && card < DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP
                        assertEquals(expectedCompact, result.compactMetadata, label)
                        // The geometry of the density rule is what the feed draws.
                        assertEquals(desktopHomeDensitySideInsetDp(width, columns), result.outerPaddingDp, label)
                        assertEquals(DESKTOP_HOME_GUTTER_DP, result.itemSpacingDp, label)
                        assertEquals(DESKTOP_HOME_GUTTER_DP, result.verticalItemSpacingDp, label)
                        assertEquals(
                            original.copy(
                                outerPaddingDp = result.outerPaddingDp,
                                itemSpacingDp = DESKTOP_HOME_GUTTER_DP,
                                verticalItemSpacingDp = DESKTOP_HOME_GUTTER_DP,
                                density = original.density.copy(compactMetadata = expectedCompact),
                            ),
                            result,
                            label,
                        )
                        if (expectedCompact == original.compactMetadata) assertSame(original.density, result.density, label)
                        assertEquals(original.storyCardHorizontalPaddingDp, result.storyCardHorizontalPaddingDp, label)
                        assertEquals(original.coverAspectRatio, result.coverAspectRatio, label)
                        assertEquals(original.titleMinLines, result.titleMinLines, label)
                        assertEquals(original.titleMaxLines, result.titleMaxLines, label)
                        assertEquals(original.compactStatsOnCover, result.compactStatsOnCover, label)
                    }
                }
            }
        }
    }

    @Test fun theClassicCardLayoutIsTheVendoredInstanceAndTheDesktopOneIsNot() {
        for (style in HomeFeedCardStyle.entries) {
            for (sizeClass in WindowWidthSizeClass.entries) {
                for (columns in 1..10) {
                    val original = resolveHomeFeedCardLayout(style, columns, sizeClass)
                    val label = "$style $sizeClass $columns"
                    // The vendored geometry that the Python port and the drawn-card checks rely on.
                    assertEquals(6, original.outerPaddingDp, label)
                    assertEquals(6, original.itemSpacingDp, label)
                    assertEquals(6, original.verticalItemSpacingDp, label)
                    for (width in 884..3624 step 137) {
                        assertSame(original, desktopSelectHomeFeedCardLayout(classic, original, width, columns), "$label $width")
                        assertEquals(
                            desktopHomeDensityCardLayout(original, width, columns),
                            desktopSelectHomeFeedCardLayout(desktop, original, width, columns),
                            "$label $width",
                        )
                    }
                }
            }
        }
    }

    @Test fun theDelegationIsTheSingleColumnModeOrAStoredCount() {
        for (displayMode in 0..2) {
            for (fixed in listOf(-1, 0, 1, 3, 6)) {
                val expected = displayMode == 1 || fixed > 0
                assertEquals(expected, desktopHomeFeedColumnsDelegated(displayMode, fixed), "$displayMode $fixed")
                // It is the decision that the column function of the desktop layout makes.
                assertEquals(expected, desktopHomeDensityDelegation(desktop, displayMode, fixed) != null, "$displayMode $fixed")
            }
        }
        assertEquals(false, desktopHomeFeedColumnsDelegated(0, 0))
        assertEquals(true, desktopHomeFeedColumnsDelegated(1, 0))
        assertEquals(true, desktopHomeFeedColumnsDelegated(0, 4))
    }

    @Test fun aDelegatedDesktopFeedIsTheVendoredFeedInWidthAndCards() {
        for (width in 300..4200 step 17) {
            for (footprint in listOf(0, 72, 172, 216)) {
                val free = desktopHomeFeedMeasuredWidth(width.dp, footprint, desktop)
                val held = desktopHomeFeedMeasuredWidth(width.dp, footprint, desktop, delegated = true)
                // The room the rail leaves, held at the vendored cap; never wider than the undelegated desktop feed.
                assertEquals(minOf(free, resolveHomeFeedMaxContentWidth()), held, "$width $footprint")
                assertTrue(held <= resolveHomeFeedMaxContentWidth() && held <= free, "$width $footprint")
                // The layout flag still decides first: CLASSIC is the vendored cap on the window, delegated or not.
                assertEquals(
                    desktopHomeFeedMeasuredWidth(width.dp, footprint, classic),
                    desktopHomeFeedMeasuredWidth(width.dp, footprint, classic, delegated = true),
                    "$width $footprint",
                )
            }
        }
        for (style in HomeFeedCardStyle.entries) {
            for (sizeClass in WindowWidthSizeClass.entries) {
                for (columns in 1..10) {
                    val original = resolveHomeFeedCardLayout(style, columns, sizeClass)
                    for (width in 884..3624 step 137) {
                        val label = "$style $sizeClass $columns $width"
                        assertSame(original, desktopSelectHomeFeedCardLayout(desktop, original, width, columns, delegated = true), label)
                        assertSame(original, desktopSelectHomeFeedCardLayout(classic, original, width, columns, delegated = true), label)
                        // Without the delegation nothing changes: the default of the parameter is false.
                        assertEquals(
                            desktopSelectHomeFeedCardLayout(desktop, original, width, columns, delegated = false),
                            desktopSelectHomeFeedCardLayout(desktop, original, width, columns),
                            label,
                        )
                    }
                }
            }
        }
    }

    @Test fun theStoryModeAndAPinnedCountDrawTheVendoredCardsOfTheReview() {
        // The window width, the rail footprint of the expanded rail, the display mode and the stored count: the width, the columns
        // and the card the delegated feed draws, from the vendored column function and the vendored card layout.
        fun delegatedFeed(window: Int, displayMode: Int, fixed: Int): Triple<Int, Int, Double> {
            assertTrue(desktopHomeFeedColumnsDelegated(displayMode, fixed), "$window $displayMode $fixed")
            val wc = desktopHomeFeedMeasuredWidth(window.dp, 216, desktop, delegated = true).value.toInt()
            val columns = desktopResolveHomeFeedGridColumns(desktop, wc, displayMode, fixed, HomeFeedCardWidthPreset.AUTO, WindowWidthSizeClass.ExtraLarge)
            assertEquals(
                resolveHomeFeedGridColumns(wc, displayMode, fixed, HomeFeedCardWidthPreset.AUTO, WindowWidthSizeClass.ExtraLarge),
                columns,
                "$window $displayMode $fixed",
            )
            val original = resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, columns, WindowWidthSizeClass.ExtraLarge)
            val layout = desktopSelectHomeFeedCardLayout(desktop, original, wc, columns, delegated = true)
            assertSame(original, layout, "$window $displayMode $fixed")
            assertEquals(6, layout.outerPaddingDp, "$window $displayMode $fixed")
            return Triple(wc, columns, drawnCard(layout, wc, columns))
        }
        // Story mode (display mode 1, two columns): the cap of 1280 dp bounds the feed, as before the desktop layout.
        assertEquals(Triple(1064, 2, 523.0), delegatedFeed(1280, 1, 0))
        assertEquals(Triple(1280, 2, 631.0), delegatedFeed(1920, 1, 0))
        assertEquals(Triple(1280, 2, 631.0), delegatedFeed(2731, 1, 0))
        // A pinned count of 2 or 3 at a window of 2731 dp.
        assertEquals(Triple(1280, 2, 631.0), delegatedFeed(2731, 0, 2))
        val (wc, columns, card) = delegatedFeed(2731, 0, 3)
        assertEquals(1280, wc)
        assertEquals(3, columns)
        assertTrue(abs(card - 418.6667) <= 1e-3, card.toString())
        // What the same inputs drew before the delegation reached the feed: cards of the density rule, 320 dp, with the rest empty on both sides.
        for ((window, empty) in listOf(1280 to 204, 1920 to 524)) {
            val free = desktopHomeFeedMeasuredWidth(window.dp, 216, desktop).value.toInt()
            val original = resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, 2, WindowWidthSizeClass.ExtraLarge)
            val old = desktopSelectHomeFeedCardLayout(desktop, original, free, 2)
            assertEquals(320.0, drawnCard(old, free, 2), window.toString())
            assertEquals(empty, old.outerPaddingDp, window.toString())
        }
    }

    @Test fun theDefaultGridNeverKeepsCompactMetadataOnTheDesktopFeed() {
        for (width in firstWidth..lastWidth) {
            val wc = contentWidth(DesktopWindowsUxRailMode.AUTO, width).second
            val columns = desktopResolveHomeFeedGridColumns(desktop, wc, 0, 0, HomeFeedCardWidthPreset.AUTO, WindowWidthSizeClass.ExtraLarge)
            for (style in HomeFeedCardStyle.entries) {
                val result = desktopCardLayout(wc, columns, style)
                assertEquals(false, result.compactMetadata, "$style $width")
            }
        }
        // A pinned eight columns on a narrow feed keeps compact metadata: the card is under 200 dp.
        val narrow = desktopHomeDensityCardLayout(resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, 8, WindowWidthSizeClass.Large), 1384, 8)
        assertEquals(true, narrow.compactMetadata)
    }

    @Test fun theDrawnFeedGeometryFollowsTheRuleForEveryWidthAndPreset() {
        for (mode in listOf(DesktopWindowsUxRailMode.AUTO, DesktopWindowsUxRailMode.EXPANDED, DesktopWindowsUxRailMode.COLLAPSED)) {
            for (preset in HomeFeedCardWidthPreset.entries) {
                for (width in firstWidth..lastWidth) {
                    val wc = contentWidth(mode, width).second
                    val rule = resolveDesktopHomeDensity(wc, preset.minCardWidthDp)
                    val columns = desktopResolveHomeFeedGridColumns(desktop, wc, 0, 0, preset, WindowWidthSizeClass.ExtraLarge)
                    val label = "$mode $preset $width"
                    assertEquals(rule.columns, columns, label)
                    val layout = desktopCardLayout(wc, columns)
                    val card = drawnCard(layout, wc, columns)
                    val grid = drawnGrid(layout, wc)
                    // The feed never draws a card above the maximum, and never below the card the preset wants (the floor at least),
                    // less the rounding of the side space of a held grid (at most 1 / columns, met only by the 320 dp preset).
                    assertTrue(card <= DESKTOP_HOME_MAX_CARD_DP, "$label $card")
                    val wanted = max(preset.minCardWidthDp ?: DESKTOP_HOME_MIN_CARD_DP, DESKTOP_HOME_CARD_FLOOR_DP)
                    assertTrue(card >= wanted - 1.0 / columns - 1e-9, "$label $card")
                    assertEquals(DESKTOP_HOME_GUTTER_DP, layout.itemSpacingDp, label)
                    assertEquals(DESKTOP_HOME_GUTTER_DP, layout.verticalItemSpacingDp, label)
                    assertEquals(desktopHomeDensityCardWidthDp(wc, columns), card, label)
                    if (rule.capped) {
                        // Held at the maximum and centred: the grid is the capped grid of the rule, one dp narrower at most (odd rest).
                        assertTrue(grid <= rule.gridWidthDp && grid >= rule.gridWidthDp - 1, "$label $grid")
                        assertTrue(card >= DESKTOP_HOME_MAX_CARD_DP - 1.0 / columns - 1e-9, "$label $card")
                    } else {
                        assertEquals(DESKTOP_HOME_SIDE_PADDING_DP, layout.outerPaddingDp, label)
                        assertEquals(rule.gridWidthDp, grid, label)
                        assertTrue(abs(card - rule.cardWidthDp) <= 1e-9, label)
                    }
                }
            }
        }
    }
}
