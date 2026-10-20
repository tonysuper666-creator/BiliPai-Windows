package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.home.HomeWallpaperSelection
import com.android.purebilibili.core.ui.wallpaper.WallpaperTransform
import com.android.purebilibili.core.ui.wallpaper.resolveWallpaperCropGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure checks of the Windows crop adapter over the carried v0.4.6 WallpaperTransformPolicy: width classes, preview
 * shape, drag and wheel mapping and the selection entries. No window, GPU, account or network.
 */
class DesktopWallpaperCropTest {
    private val eps = 0.001f

    @Test
    fun aWindowIsWideFromTheSame600dpTheAndroidSheetUsesForTablets() {
        assertEquals(DesktopWallpaperDevice.MOBILE, desktopWallpaperDeviceForWidth(0f))
        assertEquals(DesktopWallpaperDevice.MOBILE, desktopWallpaperDeviceForWidth(599.99f))
        assertEquals(DesktopWallpaperDevice.TABLET, desktopWallpaperDeviceForWidth(600f))
        assertEquals(DesktopWallpaperDevice.TABLET, desktopWallpaperDeviceForWidth(3413f))
        assertEquals(DesktopWallpaperDevice.MOBILE, desktopWallpaperDeviceForWidth(Float.NaN))
        assertEquals(DesktopWallpaperDevice.MOBILE, desktopWallpaperDeviceForWidth(Float.POSITIVE_INFINITY))
    }

    @Test
    fun thePreviewTakesTheShapeOfTheRealWindowOnlyForTheWindowsOwnClass() {
        val wide = desktopWallpaperPreviewSpace(DesktopWallpaperDevice.TABLET, 3413f, 1440f)
        assertEquals(DesktopWallpaperPreviewSpace(3413f, 1440f), wide)
        assertEquals(3413f / 1440f, wide.aspectRatio, eps)
        assertEquals(DesktopWallpaperPreviewSpace(400f, 800f),
            desktopWallpaperPreviewSpace(DesktopWallpaperDevice.MOBILE, 3413f, 1440f))
        assertEquals(DesktopWallpaperPreviewSpace(500f, 900f),
            desktopWallpaperPreviewSpace(DesktopWallpaperDevice.MOBILE, 500f, 900f))
        assertEquals(DesktopWallpaperPreviewSpace(960f, 600f),
            desktopWallpaperPreviewSpace(DesktopWallpaperDevice.TABLET, 500f, 900f))
        // a degenerate window never produces a zero or NaN aspect ratio
        val degenerate = desktopWallpaperPreviewSpace(DesktopWallpaperDevice.MOBILE, Float.NaN, 0f)
        assertEquals(DesktopWallpaperPreviewSpace(1f, 1f), degenerate)
        assertEquals(1f, degenerate.aspectRatio, eps)
    }

    @Test
    fun aSelectionEditsOnlyTheChosenCropAndAlwaysStoresSaneValues() {
        val selection = HomeWallpaperSelection(
            uri = "file:///C:/state/home_wallpaper/wallpaper_1.img",
            mobileTransform = WallpaperTransform(2f, 0.5f, -0.5f),
            tabletTransform = WallpaperTransform(9f, 4f, Float.NaN),
        )
        assertEquals(WallpaperTransform(2f, 0.5f, -0.5f),
            desktopWallpaperTransformFor(selection, DesktopWallpaperDevice.MOBILE))
        assertEquals(WallpaperTransform(3f, 1f, 0f),
            desktopWallpaperTransformFor(selection, DesktopWallpaperDevice.TABLET))
        val changed = desktopWallpaperSelectionWith(selection, DesktopWallpaperDevice.MOBILE, WallpaperTransform(0.1f, -3f, 3f))
        assertEquals(WallpaperTransform(1f, -1f, 1f), changed.mobileTransform)
        assertEquals(selection.tabletTransform, changed.tabletTransform)
        assertEquals(selection.uri, changed.uri)
        val wide = desktopWallpaperSelectionWith(selection, DesktopWallpaperDevice.TABLET, WallpaperTransform(1.5f, 0.25f, 0.75f))
        assertEquals(WallpaperTransform(1.5f, 0.25f, 0.75f), wide.tabletTransform)
        assertEquals(selection.mobileTransform, wide.mobileTransform)
    }

    @Test
    fun aPointerDragMovesTheImageWithThePointerAndNeverExposesAnEdge() {
        val current = WallpaperTransform(scale = 2f)
        val next = desktopWallpaperDrag(current, 20f, -30f, 400f, 800f, 400f, 800f)
        val before = resolveWallpaperCropGeometry(400f, 800f, 400f, 800f, current)
        val after = resolveWallpaperCropGeometry(400f, 800f, 400f, 800f, next)
        assertEquals(before.left + 20f, after.left, 0.01f)
        assertEquals(before.top - 30f, after.top, 0.01f)
        val edge = desktopWallpaperDrag(next, 100000f, -100000f, 400f, 800f, 400f, 800f)
        val crop = resolveWallpaperCropGeometry(400f, 800f, 400f, 800f, edge)
        assertTrue(crop.left <= 0f && crop.left + crop.width >= 400f)
        assertTrue(crop.top <= 0f && crop.top + crop.height >= 800f)
    }

    @Test
    fun aDragCannotMoveAnImageThatAlreadyFitsTheViewport() {
        val fitted = WallpaperTransform()
        assertEquals(fitted, desktopWallpaperDrag(fitted, 50f, 50f, 400f, 800f, 400f, 800f))
        assertEquals(fitted, desktopWallpaperDrag(fitted, Float.NaN, Float.NaN, 400f, 800f, 400f, 800f))
    }

    @Test
    fun theWheelZoomsInOnTheWayUpAndOutOnTheWayDownWithinOneToThreeTimes() {
        assertEquals(1.1f, desktopWallpaperWheelZoomFactor(-1f), eps)
        assertEquals(1f / 1.1f, desktopWallpaperWheelZoomFactor(1f), eps)
        assertEquals(1f, desktopWallpaperWheelZoomFactor(0f), eps)
        // a smooth wheel or a touchpad reports a fraction of a notch and zooms proportionally less
        assertEquals(kotlin.math.sqrt(1.1f), desktopWallpaperWheelZoomFactor(-0.5f), eps)
        // the zoom rate does not depend on how many units the platform reports per notch (one or three lines, or a
        // free-spinning wheel): an event moves by one notch at most
        assertEquals(1.1f, desktopWallpaperWheelZoomFactor(-2f), eps)
        assertEquals(desktopWallpaperWheelZoomFactor(-1f), desktopWallpaperWheelZoomFactor(-3f), eps)
        assertEquals(desktopWallpaperWheelZoomFactor(1f), desktopWallpaperWheelZoomFactor(3f), eps)
        assertEquals(1.1f, desktopWallpaperWheelZoomFactor(-80f), eps)
        assertEquals(1f / 1.1f, desktopWallpaperWheelZoomFactor(80f), eps)
        assertEquals(1f, desktopWallpaperWheelZoomFactor(Float.NaN), eps)
        assertEquals(1f, desktopWallpaperWheelZoomFactor(Float.POSITIVE_INFINITY), eps)
        val start = WallpaperTransform()
        val zoomed = desktopWallpaperWheel(start, -1f, 400f, 800f, 1600f, 900f)
        assertEquals(1.1f, zoomed.scale, eps)
        assertEquals(1f, desktopWallpaperWheel(start, 5f, 400f, 800f, 1600f, 900f).scale, eps)
        var scale = WallpaperTransform()
        repeat(40) { scale = desktopWallpaperWheel(scale, -1f, 400f, 800f, 1600f, 900f) }
        assertEquals(3f, scale.scale, eps)
    }

    @Test
    fun theSelectionEntriesAlwaysCarryAllSevenKeysUnderTheAndroidNames() {
        assertEquals(
            listOf(
                "home_wallpaper_uri",
                "home_wallpaper_scale_mobile", "home_wallpaper_offset_x_mobile", "home_wallpaper_offset_y_mobile",
                "home_wallpaper_scale_tablet", "home_wallpaper_offset_x_tablet", "home_wallpaper_offset_y_tablet",
            ),
            DesktopHomeWallpaperKeys.ALL,
        )
        val selection = HomeWallpaperSelection("file:///w/a.png", WallpaperTransform(2f, 0.5f, -0.25f), WallpaperTransform(1.5f, -1f, 1f))
        val entries = desktopHomeWallpaperSelectionEntries(selection)
        assertEquals(DesktopHomeWallpaperKeys.ALL, entries.keys.toList())
        assertEquals("file:///w/a.png", entries[DesktopHomeWallpaperKeys.URI])
        assertEquals(2f, entries[DesktopHomeWallpaperKeys.SCALE_MOBILE])
        assertEquals(1f, entries[DesktopHomeWallpaperKeys.OFFSET_Y_TABLET])
        // an insane draft is sanitised before it can be written
        val insane = desktopHomeWallpaperSelectionEntries(
            HomeWallpaperSelection("x", WallpaperTransform(Float.NaN, 5f, -5f), WallpaperTransform(0f, Float.POSITIVE_INFINITY, 0f)),
        )
        assertEquals(1f, insane[DesktopHomeWallpaperKeys.SCALE_MOBILE])
        assertEquals(1f, insane[DesktopHomeWallpaperKeys.OFFSET_X_MOBILE])
        assertEquals(-1f, insane[DesktopHomeWallpaperKeys.OFFSET_Y_MOBILE])
        assertEquals(1f, insane[DesktopHomeWallpaperKeys.SCALE_TABLET])
        assertEquals(0f, insane[DesktopHomeWallpaperKeys.OFFSET_X_TABLET])
    }

    @Test
    fun theSelectionRoundTripsThroughAnyKeyValueStoreAndFallsBackLikeTheOriginal() {
        val selection = HomeWallpaperSelection("file:///w/a.png", WallpaperTransform(2f, 0.5f, -0.25f), WallpaperTransform(1.5f, -1f, 1f))
        val stored = desktopHomeWallpaperSelectionEntries(selection)
        assertEquals(selection, desktopHomeWallpaperSelectionFromEntries { stored[it] })
        // a store that keeps numbers as doubles reads back the same selection
        val doubles = stored.mapValues { (_, value) -> if (value is Float) value.toDouble() else value }
        assertEquals(selection, desktopHomeWallpaperSelectionFromEntries { doubles[it] })
        // nothing stored: the empty selection with the identity crops
        assertEquals(HomeWallpaperSelection(), desktopHomeWallpaperSelectionFromEntries { null })
        // wrong types and out of range values never escape
        val hostile = mapOf<String, Any?>(
            DesktopHomeWallpaperKeys.URI to 7,
            DesktopHomeWallpaperKeys.SCALE_MOBILE to "big",
            DesktopHomeWallpaperKeys.OFFSET_X_MOBILE to 40.0,
            DesktopHomeWallpaperKeys.OFFSET_Y_MOBILE to Double.NaN,
            DesktopHomeWallpaperKeys.SCALE_TABLET to 0.0,
        )
        val read = desktopHomeWallpaperSelectionFromEntries { hostile[it] }
        assertEquals("", read.uri)
        assertEquals(WallpaperTransform(1f, 1f, 0f), read.mobileTransform)
        assertEquals(WallpaperTransform(1f, 0f, 0f), read.tabletTransform)
    }
}
