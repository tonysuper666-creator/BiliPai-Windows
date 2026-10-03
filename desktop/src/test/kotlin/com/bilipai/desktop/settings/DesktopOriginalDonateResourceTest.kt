package com.bilipai.desktop.settings

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopOriginalDonateResourceTest {
    @Test fun actualClasspathAuthorQrIsTheUntouchedOriginalAndDecodesAsJpeg() {
        val raw=desktopOriginalDonateQrBytes()
        assertEquals(DESKTOP_ORIGINAL_DONATE_QR_SHA256,MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
        assertEquals(0xff,raw[0].toInt() and 0xff);assertEquals(0xd8,raw[1].toInt() and 0xff)
        val decoded=checkNotNull(ImageIO.read(ByteArrayInputStream(raw)))
        assertTrue(decoded.width>0 && decoded.height>0)
        // Resource decoding does not prove native modal size, key routing or Skia draw.
    }
}
