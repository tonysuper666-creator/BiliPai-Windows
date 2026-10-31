package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AdaptiveAccentColors
import com.android.purebilibili.core.ui.components.resolveUserVipPink
import com.android.purebilibili.feature.bangumi.bangumiBrandBadgeColors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiBrandBadgeCompositionTest {
    @Test
    fun membershipBadgesUseTheSharedVipColorInsteadOfTheAppAccent() {
        for (dark in listOf(false, true)) {
            val observed = renderBadges(dark)
            for (label in listOf("会员", "大会员", " 会员专享 ")) {
                assertEquals(AdaptiveAccentColors(resolveUserVipPink(dark), Color.White), observed[label])
            }
        }
    }

    @Test
    fun freeBadgesFollowSurfaceBrightnessAndUnknownBadgesKeepTheirFallback() {
        for (dark in listOf(false, true)) {
            val observed = renderBadges(dark)
            val free = AdaptiveAccentColors(if (dark) Color(0xFFD66011) else Color(0xFFFF7F24), Color.White)
            assertEquals(free, observed["免费"])
            assertEquals(free, observed["限免"])
            assertNull(observed["出品"])
            assertNull(observed[""])
        }
    }

    private fun renderBadges(dark: Boolean): Map<String, AdaptiveAccentColors?> {
        val result = linkedMapOf<String, AdaptiveAccentColors?>()
        val scene = ImageComposeScene(64, 64, Density(1f)) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color.Green)
                else lightColorScheme(primary = Color.Blue)) {
                for (label in listOf("会员", "大会员", " 会员专享 ", "免费", "限免", "出品", "")) {
                    result[label] = bangumiBrandBadgeColors(label)
                }
            }
        }
        try { scene.render().close(); return result.toMap() }
        finally { scene.close() }
    }
}
