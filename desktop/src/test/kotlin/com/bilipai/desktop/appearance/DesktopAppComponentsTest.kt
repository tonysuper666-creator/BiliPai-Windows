package com.bilipai.desktop.appearance

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal fun runOriginalAppComponentsFixture(directory: Path): Int = runBlocking {
    Files.createDirectories(directory)
    var assertions = 0
    for (style in AppUiStyle.entries) for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
        val bounds = linkedMapOf<String, Rect>()
        fun Modifier.target(name: String) = onGloballyPositioned { bounds[name] = it.boundsInRoot() }
        var clicks = 0; var disabledClicks = 0; var cardClicks = 0; var surfaceClicks = 0
        var check by mutableStateOf(false); var switch by mutableStateOf(false)
        var radio by mutableStateOf(false); var selected by mutableStateOf(false)
        var slider by mutableStateOf(0.25f); var field by mutableStateOf("")
        val copied = mutableListOf<String>()
        val scene = ImageComposeScene(960, 940, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode),
                systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 960) {
                ProvideAppThemeConfig(AppThemeConfig(globalTextTapCopyEnabled = true, hapticFeedbackEnabled = false)) {
                    CompositionLocalProvider(LocalDesktopTextClipboard provides DesktopTextClipboard { copied += it; true }) {
                        AppSurface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                AppText("原 App* Windows: $style / $mode", style = MaterialTheme.typography.titleLarge, tapToCopyEnabled = false)
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    AppButton(onClick = { clicks++ }, modifier = Modifier.target("button")) { AppText("保存", tapToCopyEnabled = false) }
                                    AppButton(onClick = { disabledClicks++ }, enabled = false, modifier = Modifier.target("disabled")) { AppText("禁用", tapToCopyEnabled = false) }
                                    AppOutlinedButton(onClick = {}, modifier = Modifier) { AppText("描边", tapToCopyEnabled = false) }
                                    AppTextButton(onClick = {}) { AppText("文字按钮", tapToCopyEnabled = false) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    AppCheckbox(check, { check = it }, modifier = Modifier.target("checkbox"))
                                    AppSwitch(switch, { switch = it }, modifier = Modifier.target("switch"))
                                    AppRadioButton(radio, { radio = true }, modifier = Modifier.target("radio"))
                                    AppFilterChip(selected, { selected = !selected }, label = { AppText("筛选", tapToCopyEnabled = false) }, modifier = Modifier.target("chip"))
                                    AppAssistChip({}, label = { AppText("帮助", tapToCopyEnabled = false) })
                                }
                                AppOutlinedTextField(field, { field = it }, modifier = Modifier.width(500.dp).target("field"), labelText = "真实文本输入", singleLine = true)
                                AppSlider(slider, { slider = it }, Modifier.width(500.dp).target("slider"))
                                AppCard(modifier = Modifier.width(500.dp).target("card"), onClick = { cardClicks++ }) {
                                    Column(Modifier.padding(20.dp)) {
                                        AppText("原语义圆角卡片", tapToCopyEnabled = false)
                                        AppText("鼠标点击真实调用宿主回调", tapToCopyEnabled = false)
                                    }
                                }
                                AppSurface(onClick = { surfaceClicks++ }, modifier = Modifier.width(500.dp).target("surface"),
                                    color = MaterialTheme.colorScheme.secondaryContainer) {
                                    AppText("原 Surface 点击区域", Modifier.padding(20.dp), tapToCopyEnabled = false)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    AppCircularProgressIndicator(Modifier.size(32.dp))
                                    AppLinearProgressIndicator(progress = { 0.65f }, modifier = Modifier.width(320.dp))
                                    AppBadge { AppText("3", tapToCopyEnabled = false) }
                                }
                                AppText("  可复制文本  ", Modifier.target("copy"))
                                AppPrimaryButton("主要按钮", {}, isLoading = false)
                            }
                        }
                    }
                }
            }
        }
        var nanos = 0L
        suspend fun settle() { repeat(3) { nanos += 30_000_000L; scene.render(nanos).close(); yield() } }
        suspend fun click(name: String, fraction: Float = 0.5f) {
            val r = requireNotNull(bounds[name]) { "Missing actual layout: $name" }
            val point = Offset(r.left + r.width * fraction, r.center.y)
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = nanos / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000 + 1,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 41,
                buttons = PointerButtons())
            settle()
        }
        suspend fun dragSlider() {
            val r = requireNotNull(bounds["slider"])
            fun point(fraction: Float) = Offset(r.left + r.width * fraction, r.center.y)
            scene.sendPointerEvent(PointerEventType.Press, point(0.25f), timeMillis = nanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            settle()
            for (fraction in listOf(0.4f, 0.6f, 0.85f)) {
                scene.sendPointerEvent(PointerEventType.Move, point(fraction), timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = true))
                settle()
            }
            scene.sendPointerEvent(PointerEventType.Release, point(0.85f), timeMillis = nanos / 1_000_000,
                buttons = PointerButtons())
            settle()
        }
        fun verify(value: Boolean, text: String) { check(value) { "$style/$mode: $text" }; assertions++ }
        try {
            settle()
            click("button"); verify(clicks == 1, "enabled button must call callback once")
            click("disabled"); verify(disabledClicks == 0, "disabled button must ignore click")
            click("checkbox"); verify(check, "checkbox must update hoisted state")
            click("switch"); verify(switch, "switch must update hoisted state")
            click("radio"); verify(radio, "radio must call original action")
            click("chip"); verify(selected, "filter chip must update hoisted selection")
            click("card"); verify(cardClicks == 1, "card must dispatch actual click")
            click("surface"); verify(surfaceClicks == 1, "surface must dispatch actual click")
            dragSlider(); verify(slider > 0.65f, "slider must dispatch actual pointer drag")
            click("copy"); verify(copied == listOf("可复制文本"), "global copy must preserve original trimmed short-tap behavior")
            click("field")
            val typed = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED,
                nanos / 1_000_000L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, 'a')
            scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.Unknown, 'a'.code, nativeEvent = typed))
            settle(); verify(field == "a", "text field must accept actual keyboard input")
            scene.render(nanos + 500_000_000L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(directory.resolve("${style.name.lowercase()}-${mode.name.lowercase()}.png"), data.bytes) }
            }
            println("Actual App* render / pointer / keyboard PASS: $style / $mode")
        } finally { scene.close() }
    }
    Files.writeString(directory.resolve("result.json"), """{"passed":true,"styles":2,"modes":2,"actualInteractionAssertions":$assertions,"clipboard":"injected; user clipboard untouched","verificationSurface":"ImageComposeScene","nativeWindowTested":false}""")
    assertions
}


class DesktopAppComponentsTest {
    @org.junit.jupiter.api.Test
    fun originalWidgetsRenderAndDispatchRealPointerAndKeyboardInput(): Unit {
        val output = Files.createTempDirectory("bilipai-components-")
        org.junit.jupiter.api.Assertions.assertEquals(44, runOriginalAppComponentsFixture(output))
    }
}
