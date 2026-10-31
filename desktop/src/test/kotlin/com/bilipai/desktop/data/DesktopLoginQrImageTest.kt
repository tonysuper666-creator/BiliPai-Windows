package com.bilipai.desktop.data

import com.android.purebilibili.feature.login.BiliPaiQrDecoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLoginQrImageTest {
    private val loginUrl = "https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/auth?auth_code=" +
        "0123456789abcdef0123456789abcdef"

    @Test fun `album images decode after rotation and preserve encoded login request`() {
        // A rectangular photo, rather than a square camera frame, exercises the desktop image adapter.
        for (turns in 0..3) {
            val image = image(loginUrl)
            val photo = BufferedImage(370, 310, BufferedImage.TYPE_INT_RGB)
            photo.createGraphics().let { graphics ->
                try {
                    graphics.color = java.awt.Color.WHITE
                    graphics.fillRect(0, 0, photo.width, photo.height)
                    graphics.drawImage(image, 40, 10, null)
                } finally { graphics.dispose(); image.flush() }
            }
            val rotated = rotate(photo, turns)
            try { assertEquals(loginUrl, BiliPaiQrDecoder.decodeBitmap(rotated)) }
            finally { rotated.flush(); photo.flush() }
        }
    }

    @Test fun `unrelated QR stays rejected unless scanner explicitly requests raw text`() {
        val raw = "https://passport.bilibili.com.example.test/login?qrcode_key=untrusted"
        val image = image(raw)
        try {
            assertNull(BiliPaiQrDecoder.decodeBitmap(image))
            assertEquals(raw, BiliPaiQrDecoder.decodeBitmap(image, acceptAny = true))
        } finally { image.flush() }
    }

    @Test fun `blank album image does not produce a login request`() {
        val image = BufferedImage(300, 250, BufferedImage.TYPE_INT_RGB)
        try { assertNull(BiliPaiQrDecoder.decodeBitmap(image, acceptAny = true)) }
        finally { image.flush() }
    }

    private fun image(payload: String): BufferedImage {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 280, 280)
        return BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width)
                setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
        }
    }

    private fun rotate(source: BufferedImage, turns: Int): BufferedImage {
        val sideways = turns % 2 != 0
        val result = BufferedImage(if (sideways) source.height else source.width,
            if (sideways) source.width else source.height, BufferedImage.TYPE_INT_RGB)
        result.createGraphics().let { graphics ->
            try {
                when (turns) {
                    1 -> { graphics.translate(source.height, 0); graphics.rotate(Math.PI / 2) }
                    2 -> { graphics.translate(source.width, source.height); graphics.rotate(Math.PI) }
                    3 -> { graphics.translate(0, source.width); graphics.rotate(-Math.PI / 2) }
                }
                graphics.drawImage(source, 0, 0, null)
            } finally { graphics.dispose() }
        }
        return result
    }
}
