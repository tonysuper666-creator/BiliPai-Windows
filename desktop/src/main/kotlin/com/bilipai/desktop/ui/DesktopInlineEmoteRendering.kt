package com.bilipai.desktop.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.roundToInt

internal data class DesktopInlineEmoteMetrics(
    val logicalFontSize: Float, val logicalSize: Int, val logicalPadding: Int,
    val rasterSize: Int, val rasterPadding: Int,
)

/** The Main scope supplies system DPI before app zoom. Owned dialogs inherit this
 * same-monitor density; moving only a dialog across monitors is a separate scope issue. */
internal fun desktopInlineEmoteMetrics(density: Density, system: Density, fontSize: TextUnit): DesktopInlineEmoteMetrics {
    require(system.density.isFinite() && system.density > 0f)
    return DesktopInlineEmoteMetrics(
        (with(density) { fontSize.toPx() } / system.density).takeIf { it.isFinite() && it > 0f } ?: 14f,
        (with(density) { 22.dp.toPx() } / system.density).roundToInt().coerceAtLeast(1),
        (with(density) { 2.dp.toPx() } / system.density).roundToInt().coerceAtLeast(0),
        with(density) { 22.dp.roundToPx() }.coerceAtLeast(1),
        with(density) { 2.dp.roundToPx() }.coerceAtLeast(0),
    )
}

/** Owns only the view's temporary raster, never the Coil cache bitmap. The padded
 * physical source maps its content to the exact logical square used by IconView. */
internal class DesktopInlineRasterEmoteIcon(
    private val raster: BufferedImage,
    private val logicalSize: Int,
    private val logicalPadding: Int,
    private val rasterSize: Int,
    private val rasterPadding: Int,
) : Icon, AutoCloseable {
    private var closed = false
    override fun getIconWidth() = logicalSize + logicalPadding * 2
    override fun getIconHeight() = logicalSize
    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        if (!closed) graphics.drawImage(raster,
            x + logicalPadding, y, x + logicalPadding + logicalSize, y + logicalSize,
            rasterPadding, 0, rasterPadding + rasterSize, rasterSize, component)
    }
    override fun close() { if (!closed) { closed = true; raster.flush() } }
}
