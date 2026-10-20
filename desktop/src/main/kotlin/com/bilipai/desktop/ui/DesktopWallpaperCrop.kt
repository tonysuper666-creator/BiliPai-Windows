package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.android.purebilibili.core.store.home.HomeWallpaperSelection
import com.android.purebilibili.core.ui.wallpaper.WallpaperTransform
import com.android.purebilibili.core.ui.wallpaper.applyWallpaperGesture
import com.android.purebilibili.core.ui.wallpaper.resolveWallpaperCropGeometry
import com.android.purebilibili.core.ui.wallpaper.sanitizeWallpaperTransform
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Windows crop adapter for the v0.4.6 wallpaper foundation.
 *
 * The carried WallpaperTransformPolicy owns the geometry (one normalised focal alignment and zoom, shared by the layout
 * and by the preview gesture). This file binds it to a Windows window:
 *  - the phone and tablet transforms of the Android selection become a narrow and a wide window transform, split at the
 *    same 600 dp the Android sheet uses, so a stored selection means the same on both platforms;
 *  - a touch pan becomes a pointer drag and a pinch becomes the mouse wheel;
 *  - the crop is laid out with the policy's rectangle, so a 4K window and its 1x preview agree.
 * Everything above the two composables at the end is pure and has a plain JVM test.
 */

/** The two stored crops. Android calls them phone and tablet; Windows keys them by the width class of the window. */
internal enum class DesktopWallpaperDevice { MOBILE, TABLET }

/** The Android sheet treats a screen of at least 600 dp as a tablet; a Windows window uses the same boundary. */
internal const val DESKTOP_WALLPAPER_TABLET_MIN_WIDTH_DP = 600f

internal fun desktopWallpaperDeviceForWidth(windowWidthDp: Float): DesktopWallpaperDevice =
    if (windowWidthDp.isFinite() && windowWidthDp >= DESKTOP_WALLPAPER_TABLET_MIN_WIDTH_DP) DesktopWallpaperDevice.TABLET
    else DesktopWallpaperDevice.MOBILE

/** The logical window a crop preview imitates, in dp. Only its aspect ratio and its proportions matter. */
internal data class DesktopWallpaperPreviewSpace(val widthDp: Float, val heightDp: Float) {
    val aspectRatio: Float get() = widthDp / heightDp
}

/**
 * The real window when the selected crop is the window's own width class; otherwise the reference sizes of the Android
 * sheet (400x800 phone, 960x600 tablet). The normalised transform means the same at any resolution, so this is only the
 * shape the preview has to take.
 */
internal fun desktopWallpaperPreviewSpace(
    selected: DesktopWallpaperDevice,
    windowWidthDp: Float,
    windowHeightDp: Float,
): DesktopWallpaperPreviewSpace {
    val width = windowWidthDp.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val height = windowHeightDp.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    return when {
        desktopWallpaperDeviceForWidth(width) == selected -> DesktopWallpaperPreviewSpace(width, height)
        selected == DesktopWallpaperDevice.MOBILE -> DesktopWallpaperPreviewSpace(400f, 800f)
        else -> DesktopWallpaperPreviewSpace(960f, 600f)
    }
}

internal fun desktopWallpaperTransformFor(
    selection: HomeWallpaperSelection,
    device: DesktopWallpaperDevice,
): WallpaperTransform =
    sanitizeWallpaperTransform(
        if (device == DesktopWallpaperDevice.MOBILE) selection.mobileTransform else selection.tabletTransform,
    )

internal fun desktopWallpaperSelectionWith(
    selection: HomeWallpaperSelection,
    device: DesktopWallpaperDevice,
    transform: WallpaperTransform,
): HomeWallpaperSelection {
    val safe = sanitizeWallpaperTransform(transform)
    return if (device == DesktopWallpaperDevice.MOBILE) selection.copy(mobileTransform = safe)
    else selection.copy(tabletTransform = safe)
}

/** One wheel event scales by at most this factor (events multiply, so two full notches are 1.21). */
internal const val DESKTOP_WALLPAPER_WHEEL_ZOOM_BASE = 1.1f

/**
 * Wheel up (a negative scroll delta) zooms in, wheel down zooms out. The delta of one event is clamped to a single unit
 * before it is used: how many units Compose Desktop reports per notch depends on the platform and on the system
 * "lines per notch" setting (one or three on a typical Windows mouse), and the zoom rate must not depend on that. So a
 * notch is 1.1x whatever the platform reports, a fractional delta from a smooth wheel or a precision touchpad scales
 * proportionally less, and a free-spinning wheel can only raise the rate by sending more events. The policy still clamps
 * the final scale to 1x..3x. A non-finite delta changes nothing.
 */
internal fun desktopWallpaperWheelZoomFactor(wheelDeltaY: Float): Float =
    if (wheelDeltaY.isFinite()) DESKTOP_WALLPAPER_WHEEL_ZOOM_BASE.pow(-wheelDeltaY.coerceIn(-1f, 1f)) else 1f

/** A pointer drag moves the image with the pointer, exactly like a one-finger pan in the Android sheet. */
internal fun desktopWallpaperDrag(
    current: WallpaperTransform,
    dragX: Float,
    dragY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    sourceWidth: Float,
    sourceHeight: Float,
): WallpaperTransform = applyWallpaperGesture(
    current, dragX, dragY, 1f, viewportWidth, viewportHeight, sourceWidth, sourceHeight,
)

/** The mouse wheel is the pinch: a zoom change with no pan. */
internal fun desktopWallpaperWheel(
    current: WallpaperTransform,
    wheelDeltaY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    sourceWidth: Float,
    sourceHeight: Float,
): WallpaperTransform = applyWallpaperGesture(
    current, 0f, 0f, desktopWallpaperWheelZoomFactor(wheelDeltaY),
    viewportWidth, viewportHeight, sourceWidth, sourceHeight,
)

/** The preference keys of the Android selection store (SettingsManager.getHomeWallpaperSelection), unchanged. */
internal object DesktopHomeWallpaperKeys {
    const val URI = "home_wallpaper_uri"
    const val SCALE_MOBILE = "home_wallpaper_scale_mobile"
    const val OFFSET_X_MOBILE = "home_wallpaper_offset_x_mobile"
    const val OFFSET_Y_MOBILE = "home_wallpaper_offset_y_mobile"
    const val SCALE_TABLET = "home_wallpaper_scale_tablet"
    const val OFFSET_X_TABLET = "home_wallpaper_offset_x_tablet"
    const val OFFSET_Y_TABLET = "home_wallpaper_offset_y_tablet"
    val ALL = listOf(URI, SCALE_MOBILE, OFFSET_X_MOBILE, OFFSET_Y_MOBILE, SCALE_TABLET, OFFSET_X_TABLET, OFFSET_Y_TABLET)
}

/**
 * The selection as one set of entries for a key/value store. All seven keys are always present and every transform is
 * sanitised first, so a writer that stores the whole map publishes the URI and both crops together (the original
 * setHomeWallpaperSelection edits them in one transaction). Persisting it is the job of the settings owner.
 */
internal fun desktopHomeWallpaperSelectionEntries(selection: HomeWallpaperSelection): Map<String, Any> {
    val mobile = sanitizeWallpaperTransform(selection.mobileTransform)
    val tablet = sanitizeWallpaperTransform(selection.tabletTransform)
    return linkedMapOf<String, Any>(
        DesktopHomeWallpaperKeys.URI to selection.uri,
        DesktopHomeWallpaperKeys.SCALE_MOBILE to mobile.scale,
        DesktopHomeWallpaperKeys.OFFSET_X_MOBILE to mobile.offsetX,
        DesktopHomeWallpaperKeys.OFFSET_Y_MOBILE to mobile.offsetY,
        DesktopHomeWallpaperKeys.SCALE_TABLET to tablet.scale,
        DesktopHomeWallpaperKeys.OFFSET_X_TABLET to tablet.offsetX,
        DesktopHomeWallpaperKeys.OFFSET_Y_TABLET to tablet.offsetY,
    )
}

/** Reads the selection back from any store: a missing, mistyped or out-of-range value falls back as the original does. */
internal fun desktopHomeWallpaperSelectionFromEntries(read: (String) -> Any?): HomeWallpaperSelection {
    fun number(key: String, fallback: Float): Float = (read(key) as? Number)?.toFloat() ?: fallback
    return HomeWallpaperSelection(
        uri = read(DesktopHomeWallpaperKeys.URI) as? String ?: "",
        mobileTransform = sanitizeWallpaperTransform(WallpaperTransform(
            scale = number(DesktopHomeWallpaperKeys.SCALE_MOBILE, 1f),
            offsetX = number(DesktopHomeWallpaperKeys.OFFSET_X_MOBILE, 0f),
            offsetY = number(DesktopHomeWallpaperKeys.OFFSET_Y_MOBILE, 0f),
        )),
        tabletTransform = sanitizeWallpaperTransform(WallpaperTransform(
            scale = number(DesktopHomeWallpaperKeys.SCALE_TABLET, 1f),
            offsetX = number(DesktopHomeWallpaperKeys.OFFSET_X_TABLET, 0f),
            offsetY = number(DesktopHomeWallpaperKeys.OFFSET_Y_TABLET, 0f),
        )),
    )
}

/** Compose constraints cannot represent a dimension above 262143 px; a 200:1 panorama at 3x would exceed it. */
internal const val DESKTOP_WALLPAPER_MAX_RENDERED_PX = 262_000

/**
 * Lays the child out at the rectangle the shared policy computes for this viewport and clips to the viewport. The child
 * is measured at its own rendered size (never the viewport size), so the image keeps its aspect ratio and no edge can
 * show empty space. The viewport is the size this modifier is given; the child's own content scale should fill bounds.
 */
internal fun Modifier.desktopWallpaperCrop(sourceWidth: Int, sourceHeight: Int, transform: WallpaperTransform): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val crop = resolveWallpaperCropGeometry(
            viewportWidth.toFloat(), viewportHeight.toFloat(), sourceWidth.toFloat(), sourceHeight.toFloat(), transform,
        )
        val placeable = measurable.measure(Constraints.fixed(
            crop.width.roundToInt().coerceIn(1, DESKTOP_WALLPAPER_MAX_RENDERED_PX),
            crop.height.roundToInt().coerceIn(1, DESKTOP_WALLPAPER_MAX_RENDERED_PX),
        ))
        layout(viewportWidth, viewportHeight) { placeable.place(crop.left.roundToInt(), crop.top.roundToInt()) }
    }

/** A local wallpaper image drawn with the crop of [transform]; [sourceWidth] and [sourceHeight] come from the header probe. */
@Composable
internal fun DesktopWallpaperCropImage(
    uri: String,
    sourceWidth: Int,
    sourceHeight: Int,
    transform: WallpaperTransform,
    modifier: Modifier = Modifier,
    onError: () -> Unit = {},
) {
    val context = LocalPlatformContext.current
    val request = remember(context, uri) { ImageRequest.Builder(context).data(uri).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.desktopWallpaperCrop(sourceWidth, sourceHeight, transform),
        onError = { onError() },
    )
}
