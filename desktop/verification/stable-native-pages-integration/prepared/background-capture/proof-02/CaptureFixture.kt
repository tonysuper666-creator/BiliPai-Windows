@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.captureMain14Proof

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.LiquidGlassReadabilityMode
import com.android.purebilibili.feature.home.components.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private class Owner: LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}

fun main(args: Array<String>): Unit = runBlocking {
    val out = Path.of(args[0]).toRealPath()
    val actualMain = Path.of(args[1]).toRealPath()
    var assertions = 0
    fun verify(value: Boolean, message: String) { check(value) { message }; assertions++ }
    val identities = mutableListOf<JsonObject>()
    for (name in listOf("com.bilipai.desktop.ui.DesktopLiquidReadabilityEnvironment", "com.bilipai.desktop.ui.DesktopLiquidReadabilityPlatformKt",
        "com.android.purebilibili.core.ui.components.AppLiquidAwareTabRowKt", "com.android.purebilibili.feature.home.components.FloatingBottomBarKt",
        "com.bilipai.desktop.settings.DesktopLiquidTabSettings", "com.bilipai.desktop.plugins.DesktopPluginStore")) {
        val type = Class.forName(name)
        val codeSource = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
        verify(codeSource == actualMain, "zero override actual code source: $name")
        val bytes = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        identities += buildJsonObject { put("class", name); put("path", codeSource.toString());put("sha256ClassBytes", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
    }
    val store = DesktopPluginStore(out.resolve("fixture-global-store"))
    store.update("settings", mapOf("android_native_liquid_glass_enabled" to JsonPrimitive(true), "liquid_glass_readability_mode" to JsonPrimitive(LiquidGlassReadabilityMode.ADAPTIVE.value)))
    val bridge = DesktopLiquidTabSettings(store)
    val settings = bridge.homeSettings.first()
    verify(settings.androidNativeLiquidGlassEnabled, "actual global-store enabled original glass")
    verify(settings.liquidGlassReadabilityMode == LiquidGlassReadabilityMode.ADAPTIVE, "actual original integer readability preference decoded")
    val alive = AtomicBoolean(true)
    val scene = ImageComposeScene(width = 360, height = 160, coroutineContext = coroutineContext)
    val lifecycle = Owner()
    var color by mutableStateOf(Color.White)
    var bounds by mutableStateOf<Rect?>(null)
    var environment: DesktopLiquidReadabilityEnvironment? = null
    var recordPasses = 0
    var suppressedDuringRecord = 0
    var foregroundNormalDraws = 0
    var nanos = 0L
    var samples = 0
    fun nextImage(): org.jetbrains.skia.Image { nanos += 32_000_000;return scene.render(nanos) }
    suspend fun settle() { repeat(25) { nextImage().close();delay(3) } }
    try {
        scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, hapticFeedbackEnabled = false)) {
                val layer = rememberGraphicsLayer()
                val backdrop = rememberLayerBackdrop(layer)
                val source = remember(layer) { DesktopLiquidReadabilityEnvironment(layer, { bounds }, { IntSize(360, 160) }, alive::get) }
                SideEffect { environment = source }
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalDesktopLiquidTabSettings provides bridge,
                    LocalDesktopLiquidReadabilityEnvironment provides source,
                    LocalAppThemeConfig provides LocalAppThemeConfig.current.copy(liquidGlassEnabled = true),
                    LocalLiquidGlassRenderConfig provides LiquidGlassRenderConfig(tuning = resolveLiquidGlassTuning(settings.liquidGlassProgress, settings.liquidGlassAdvancedSettings, LiquidGlassReadabilityMode.ADAPTIVE), preset = settings.bottomBarLiquidGlassPreset),
                    LocalDesktopDetailForeground provides true) {
                    // Root DesktopShell's exact recordBackground/layer.record/drawContent order.
                    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() }.drawWithContent {
                        if (settings.androidNativeLiquidGlassEnabled && settings.liquidGlassReadabilityMode == LiquidGlassReadabilityMode.ADAPTIVE) source.recordBackground {
                            recordPasses++
                            layer.record { this@drawWithContent.drawContent() }
                        }
                        drawContent()
                    }.background(color)) {
                        AppThemeAdaptiveTabRow(options = listOf(AppSegmentOption(0, "First"), AppSegmentOption(1, "Second")), selectedValue = 0,
                            onSelectionChange = {}, modifier = Modifier.fillMaxWidth().padding(12.dp), compactMiuixWhenTwoOptions = false,
                            miuixBackdrop = backdrop)
                        // Observe the actual platform modifier independently from the original row.
                        Box(Modifier.size(8.dp).excludeFromLiquidBackground().drawWithContent { foregroundNormalDraws++;drawRect(Color.Red);drawContent() })
                        Box(Modifier.size(1.dp).drawWithContent { if (source.isRecordingBackground) suppressedDuringRecord++;drawContent() })
                    }
                }
            }
        }
        settle()
        val actualEnvironment = checkNotNull(environment)
        verify(recordPasses > 0 && suppressedDuringRecord > 0, "actual Root pattern executed record flag")
        verify(foregroundNormalDraws > 0, "normal draw executed foreground platform modifier")
        verify(!actualEnvironment.isRecordingBackground, "normal draw exits capture scope")
        suspend fun sample(expectedRgb: Int, filename: String) {
            val sampled = checkNotNull(actualEnvironment.sampleBitmap(DesktopLiquidSampleRect(0, 0, 360, 160), 24, 8))
            val pixels = IntArray(24 * 8);sampled.getPixels(pixels, 0, 24, 0, 0, 24, 8)
            verify(pixels.all { it and 0xffffff == expectedRgb }, "actual recorded whole background excludes original row and red foreground: $filename")
            samples++
            val image = nextImage()
            try {
                val normal = image.toComposeImageBitmap();val rendered = IntArray(normal.width * normal.height);normal.readPixels(rendered)
                verify(rendered.count { it and 0xffffff != expectedRgb } > 50, "original liquid foreground remains in normal pixels: $filename")
                val originalRowPixels = (16 until 90).sumOf { y -> (16 until 344).count { x -> rendered[y * normal.width + x] and 0xffffff != expectedRgb } }
                verify(originalRowPixels > 100, "actual compiled original row pixels remain independently of red sentinel: $filename")
                verify(rendered.any { it and 0xffffff == 0xff0000 }, "foreground platform guard is not hidden in normal draw: $filename")
                image.encodeToData()?.use { Files.write(out.resolve(filename), it.bytes) } ?: error("PNG encoding failed")
            } finally { image.close() }
        }
        sample(0xffffff, "white-normal-with-foreground.png")
        color = Color.Black;settle();sample(0, "black-normal-with-foreground.png")
        var sawException = false
        try { actualEnvironment.recordBackground { verify(actualEnvironment.isRecordingBackground, "exception scope entered");throw IllegalArgumentException("fixture capture failure") } }
        catch (_: IllegalArgumentException) { sawException = true }
        verify(sawException && !actualEnvironment.isRecordingBackground, "record flag finally resets after exception")
        var nestedRejected = false
        actualEnvironment.recordBackground { try { actualEnvironment.recordBackground {} } catch (_: IllegalStateException) { nestedRejected = true };verify(actualEnvironment.isRecordingBackground, "nested failure retains outer capture flag") }
        verify(nestedRejected && !actualEnvironment.isRecordingBackground, "nested record rejection does not poison next draw")
        settle();verify(foregroundNormalDraws > 1 && !actualEnvironment.isRecordingBackground, "normal original draw survives failed capture")
        alive.set(false)
        verify(!actualEnvironment.isSupported && actualEnvironment.sampleBitmap(DesktopLiquidSampleRect(0, 0, 360, 160), 24, 8) == null, "retired owner rejects old recorded layer")
        val result = buildJsonObject {
            put("status", "PASS");put("caseCount", 1);put("assertions", assertions);put("actualBackgroundSamples", samples)
            put("recordPasses", recordPasses);put("observedRecordFlagPasses", suppressedDuringRecord);put("foregroundNormalDraws", foregroundNormalDraws)
            put("actualCodeSources", JsonArray(identities));put("productOverrides", 0);put("sameCompiledMain14", true)
            put("offscreenActualComposeScene", true);put("actualRootMounted", false);put("noHWND", true);put("noHTTP", true);put("noMainEdits", true)
        }
        Files.writeString(out.resolve("result.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n")
        println(result)
    } finally { alive.set(false);scene.close();lifecycle.lifecycle.currentState = Lifecycle.State.DESTROYED }
}
