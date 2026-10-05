package com.bilipai.desktop.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CPU image arithmetic only. These tests do not create a window, Robot,
 * player or account, and cannot prove native composition or input delivery. */
class DesktopFeedbackCarrierPixelOracleTest {
    private fun background(): BufferedImage = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB).also { image ->
        image.createGraphics().let { graphics ->
            try { graphics.color = Color(220, 20, 150); graphics.fillRect(0, 0, image.width, image.height) }
            finally { graphics.dispose() }
        }
    }

    private fun composite(before: BufferedImage, alpha: Float = .5f, magenta: Boolean = true): BufferedImage =
        BufferedImage(before.width, before.height, BufferedImage.TYPE_INT_RGB).also { image ->
            image.createGraphics().let { graphics ->
                try {
                    graphics.drawImage(before, 0, 0, null)
                    if (magenta) { graphics.color = Color.MAGENTA; graphics.fillRect(50, 40, 30, 40) }
                    graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                    graphics.color = Color.CYAN; graphics.fillRect(110, 40, 30, 40)
                } finally { graphics.dispose() }
            }
        }

    @Test fun halfAlphaDecorationAndUnchangedBackgroundAreAccepted() {
        val before = background()
        assertTrue(DesktopFeedbackCarrierNativeSmoke.pixelsMatch(before, composite(before)))
    }

    @Test fun OpaqueCarrierBackgroundIsRejected() {
        val before = background()
        val after = composite(before)
        after.setRGB(20, 100, Color.BLACK.rgb)
        assertFalse(DesktopFeedbackCarrierNativeSmoke.pixelsMatch(before, after))
    }

    @Test fun MissingOpaqueDecorationIsRejected() {
        val before = background()
        assertFalse(DesktopFeedbackCarrierNativeSmoke.pixelsMatch(before, composite(before, magenta = false)))
    }

    @Test fun FullyOpaqueCyanCannotProveHalfAlphaComposition() {
        val before = background()
        assertFalse(DesktopFeedbackCarrierNativeSmoke.pixelsMatch(before, composite(before, alpha = 1f)))
    }

    @Test fun GeometryChangedDuringCaptureIsRejected() {
        assertFalse(DesktopFeedbackCarrierNativeSmoke.pixelsMatch(background(), BufferedImage(201, 200, BufferedImage.TYPE_INT_RGB)))
    }
}
