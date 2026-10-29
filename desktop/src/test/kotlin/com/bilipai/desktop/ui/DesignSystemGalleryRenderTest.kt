package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppCard
import com.android.purebilibili.core.ui.components.AppOutlinedButton
import com.android.purebilibili.core.ui.components.AppPrimaryButton
import com.android.purebilibili.core.ui.components.AppSlider
import com.android.purebilibili.core.ui.components.AppSwitch
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the shared design-system primitives (text, buttons, switch, slider, card) with the desktop theme, in both UI
 * styles and both color modes, on the CPU without a window. The pixels must not be blank; with
 * BILIPAI_GALLERY_OUT=<dir> the images are written, so two builds can be compared pixel by pixel (TON-997).
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesignSystemGalleryRenderTest {
    @Composable
    private fun Gallery() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppText("Design system gallery 设计系统样张")
                AppCard(Modifier.width(420.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppText("Card title 卡片标题")
                        AppText("Card body text 卡片正文，用来比较字号、行距和颜色。")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppSwitch(checked = true, onCheckedChange = {})
                            AppSwitch(checked = false, onCheckedChange = {})
                        }
                        AppSlider(value = 0.4f, onValueChange = {}, modifier = Modifier.width(320.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppPrimaryButton(text = "Primary 主要", onClick = {})
                    AppOutlinedButton(onClick = {}) { AppText("Outlined 描边") }
                    AppTextButton(onClick = {}) { AppText("Text 文字") }
                }
            }
        }
    }

    private fun render(style: AppUiStyle, mode: AppThemeMode): ByteArray {
        val scene = ImageComposeScene(520, 400, Density(1f)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("zh-CN"),
            ) { Gallery() }
        }
        try {
            var nanos = 0L
            repeat(30) { scene.render(nanos); nanos += 16_000_000L }
            return scene.render(nanos).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    @Test fun primitivesRenderNonBlankInBothStylesAndModes() {
        check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the headless test JVM" }
        val out = System.getenv("BILIPAI_GALLERY_OUT")?.let { Path.of(it) }?.also { Files.createDirectories(it) }
        for (style in AppUiStyle.entries) for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            val png = render(style, mode)
            out?.resolve("gallery-${style.name.lowercase()}-${mode.name.lowercase()}.png")?.let { Files.write(it, png) }
            val image = org.jetbrains.skia.Image.makeFromEncoded(png)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
            val distinct = HashSet<Int>()
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) distinct += bitmap.getColor(x, y)
            assertTrue(distinct.size > 12, "$style $mode rendered only ${distinct.size} distinct colors")
        }
    }
}
