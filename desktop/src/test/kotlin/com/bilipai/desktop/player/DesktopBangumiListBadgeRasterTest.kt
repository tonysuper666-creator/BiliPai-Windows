package com.bilipai.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.components.resolveUserVipPink
import com.android.purebilibili.feature.bangumi.ui.list.BangumiBadge
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiListBadgeRasterTest {
    @Test fun actualListMembershipBadgePaintsVipBrandInLightAndDarkThemes() {
        for (dark in listOf(false, true)) {
            for (label in listOf("会员", "大会员", "会员专享")) {
                val pixels = render(label, dark)
                assertTrue(pixels.count { it == resolveUserVipPink(dark).toArgb() } > 1000)
                assertEquals(0, pixels.count { it == Color.Green.toArgb() })
            }
        }
    }
    @Test fun actualListFreeBadgePaintsThemeBrandAndUnknownBadgeRetainsCallerColor() {
        for (dark in listOf(false, true)) {
            val expected = (if (dark) Color(0xFFD66011) else Color(0xFFFF7F24)).toArgb()
            for (label in listOf("免费", "限免")) {
                val pixels = render(label, dark)
                assertTrue(pixels.count { it == expected } > 1000)
                assertEquals(0, pixels.count { it == Color.Green.toArgb() })
            }
            assertTrue(render("出品", dark).count { it == Color.Green.toArgb() } > 1000)
        }
    }
    private fun render(label: String, dark: Boolean): IntArray {
        val scene = ImageComposeScene(160, 48, Density(1f)) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                BangumiBadge(label, Modifier.fillMaxSize(), containerColor = Color.Green)
            }
        }
        try {
            val bytes = scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            return ImageIO.read(ByteArrayInputStream(bytes)).getRGB(0, 0, 160, 48, null, 0, 160)
        } finally { scene.close() }
    }
}
