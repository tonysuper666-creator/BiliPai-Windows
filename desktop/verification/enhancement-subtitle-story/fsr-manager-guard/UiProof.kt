@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.enhancementfixture

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.anime4k.*
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopVideoEnhancementSettingsContent
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import java.nio.file.*
import kotlin.math.abs
import kotlin.test.*

/** Only the OS window primitive is substituted; original confirmation content and buttons remain intact. */
@Composable fun HeadlessDialog(onDismissRequest: () -> Unit, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null) {
    AppPopupSurface(AppPopupSurfaceType.DIALOG, modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = AppShapes.container(ContainerLevel.Dialog), containerColor = AppSurfaceTokens.surfaceContainerHigh()) {
        Column(Modifier.padding(16.dp)) { title?.invoke(); text?.invoke(); Row { dismissButton?.invoke(); confirmButton?.invoke() } }
    }
}

class UiProof {
    @Test fun originalM3AndMiuixControlsWriteTheOriginalProviderAndRealDiskViaPointerEvents(): Unit = runBlocking {
        val output = Path.of(System.getProperty("enhancement.proof.dir")).resolve("ui")
        Files.createDirectories(output)
        for (style in AppUiStyle.entries) {
            val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-enhancement-ui-")))
            bindFixtureContext(context)
            val plugin = Anime4KPlugin()
            val configuration = DesktopVideoEnhancementConfiguration(plugin, {}, dispatcher = Dispatchers.Default)
            val scene = ImageComposeScene(width = 720, height = 1000, coroutineContext = coroutineContext)
            var nanos = 0L
            try {
                scene.setContent { DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false)) {
                    DesktopVideoEnhancementSettingsContent(configuration)
                } }
                suspend fun settle() { repeat(8) { nanos += 30_000_000L; scene.render(nanos).close(); delay(2) } }
                fun all(): List<SemanticsNode> {
                    fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
                    return scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
                }
                fun labels() = all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                suspend fun pointer(point: Offset) {
                    scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                    scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 55, buttons = PointerButtons())
                    settle()
                }
                suspend fun click(label: String) {
                    val node = all().lastOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
                        ?: error("Missing $style control $label; actual ${labels()}")
                    pointer(node.boundsInRoot.center)
                }
                suspend fun switchRemember() {
                    val label = all().last { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "跨视频记忆开启状态" } == true }
                    val toggle = all().filter { it.config.contains(SemanticsProperties.ToggleableState) }.minBy { abs(it.boundsInRoot.center.y - label.boundsInRoot.center.y) }
                    pointer(toggle.boundsInRoot.center)
                }
                fun capture(name: String) { scene.render(nanos + 1).use { Files.write(output.resolve("${style.name.lowercase()}-$name.png"), it.encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
                settle()
                click("质量档")
                waitUntil { plugin.configState.value.preset == Anime4KPreset.QUALITY }
                configuration.setPreset(Anime4KPreset.QUALITY).await() // actor barrier for durable original IO
                assertEquals(Anime4KPreset.QUALITY, actualDiskConfig(context).preset)
                click("AMD FSR 1.0（通用）")
                waitUntil { plugin.configState.value.algorithm == VideoEnhancementAlgorithm.FSR_1_0 }; settle()
                assertTrue(labels().contains("FSR 锐化强度")); assertFalse(labels().contains("CNN 模型"))
                val slider = all().lastOrNull { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
                    ?: error("Missing actual $style FSR slider semantics: ${all().map { it.config }}")
                val bounds = slider.boundsInRoot
                val start = Offset(bounds.left + bounds.width * .9f, bounds.center.y)
                val end = Offset(bounds.left + bounds.width * .3f, bounds.center.y)
                scene.sendPointerEvent(PointerEventType.Press, start, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                repeat(6) { index -> scene.sendPointerEvent(PointerEventType.Move, Offset(start.x + (end.x - start.x) * (index + 1) / 6f, end.y),
                    timeMillis = nanos / 1_000_000 + 20L * (index + 1), buttons = PointerButtons(isPrimaryPressed = true)) }
                scene.sendPointerEvent(PointerEventType.Release, end, timeMillis = nanos / 1_000_000 + 160, buttons = PointerButtons())
                settle(); waitUntil { plugin.configState.value.fsrSharpness != .9f }
                val sharpness = plugin.configState.value.fsrSharpness
                assertTrue(sharpness in .2f.. .4f, "$style real drag result $sharpness")
                assertEquals(normalizeFsrSharpness(sharpness), sharpness)
                capture("fsr-pointer")
                switchRemember()
                assertTrue(labels().contains("是否记住后续视频的开关？"))
                assertFalse(plugin.configState.value.rememberAcrossVideos)
                capture("remember-confirm")
                click("取消"); assertFalse(plugin.configState.value.rememberAcrossVideos)
                switchRemember(); click("开启记忆")
                waitUntil { plugin.configState.value.rememberAcrossVideos }
                assertFalse(plugin.configState.value.rememberedEnabled, "Original confirmation sets currentVideoEnabled=false")
                switchRemember(); waitUntil { !plugin.configState.value.rememberAcrossVideos }
                assertFalse(labels().contains("是否记住后续视频的开关？"))
                click("Anime4K（动漫）"); waitUntil { plugin.configState.value.algorithm == VideoEnhancementAlgorithm.ANIME4K }; settle()
                click("效率档"); waitUntil { plugin.configState.value.preset == Anime4KPreset.FAST }
                configuration.flushAndClose()
                assertEquals(plugin.configState.value, actualDiskConfig(context))
                assertFalse(com.android.purebilibili.core.plugin.PluginStore.isEnabled(context, Anime4KPlugin.PLUGIN_ID))
                capture("anime4k-final")
            } finally { configuration.flushAndClose(); scene.close() }
        }
    }
}
