package com.bilipai.desktop.appearance

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.AppThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal fun runOriginalAppPreferenceFixture(directory: Path): Int = runBlocking {
    Files.createDirectories(directory)
    var assertions = 0
    for (style in AppUiStyle.entries) for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
        val bounds = linkedMapOf<String, Rect>()
        fun Modifier.target(name: String) = onGloballyPositioned { bounds[name] = it.boundsInRoot() }
        var enabled by mutableStateOf(false); var disabledChanges = 0; var rowClicks = 0
        var choice by mutableStateOf(false); var text by mutableStateOf(""); var query by mutableStateOf("")
        var nav by mutableStateOf(0); var disabledNavChanges = 0; var tagClicks = 0
        var cardClicks = 0; var backClicks = 0; var menuExpanded = false
        var slider by mutableStateOf(25f)
        val copy = mutableListOf<Pair<String, String?>>()
        val scene = ImageComposeScene(1100, 1420, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode),
                systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 1100) {
                ProvideAppThemeConfig(AppThemeConfig(hapticFeedbackEnabled = false, nativeMiuixPopupsEnabled = false)) {
                    AppSurface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppText("原偏好 / 导航 / 列表: $style / $mode")
                            AppPreferenceSectionTitle("原主题偏好组件")
                            AppPreferenceGroup(Modifier.width(780.dp)) {
                                Box(Modifier.target("enabled")) { AppSwitchPreference(title = "允许弹幕", subtitle = "真实行点击与宿主状态", checked = enabled, onCheckedChange = { enabled = it }) }
                                Box(Modifier.target("disabled")) { AppSwitchPreference(title = "禁用设置", checked = false, onCheckedChange = { disabledChanges++ }, enabled = false) }
                                Box(Modifier.target("preference")) { AppPreference(title = "下载目录", subtitle = "保留原列表组件与行间距", value = "Windows", onClick = { rowClicks++ }) }
                                AppSliderPreference(title = "字幕大小", value = slider, onValueChange = { slider = it }, valueRange = 0f..100f, valueLabel = "${slider.toInt()}%")
                            }
                            AppSingleChoiceRow(choice, { choice = true }, Modifier.width(780.dp).target("choice")) { AppText("使用默认播放器") }
                            AppTextField(text, { text = it }, Modifier.width(780.dp).target("text"), label = "原 AppTextField", placeholder = "真实键盘输入")
                            AppSearchField(query, { query = it }, Modifier.width(780.dp).target("search"), presentation = AppSearchFieldPresentation.TOP_BAR, placeholder = "原 SearchField")
                            AppListItem(headlineContent = { AppText("列表主标题") }, overlineContent = { AppText("分组") }, supportingContent = { AppText("原 supportingContent 槽保留") },
                                trailingContent = { AppBadge { AppText("8") } }, modifier = Modifier.width(780.dp))
                            CompositionLocalProvider(LocalAppListItemStyle provides AppListItemStyle.CUSTOM) {
                                AppPreference(title = "可复制值", value = "552233", copyValue = "完整复制值", enableCopy = true, showChevron = false,
                                    onCopyRequest = { value, label -> copy += value to label })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                AppTagChip("原紧凑标签", { tagClicks++ }, Modifier.target("tag"), size = AppTagChipSize.COMPACT)
                                AppTagChip("原较小标签", {}, size = AppTagChipSize.SMALL)
                                AppBackToTopButton(true, { backClicks++ }, buttonModifier = Modifier.target("back"))
                                AppWindowActionMenu(listOf(listOf(AppWindowAction("未启用动作", {}))), enabled = false,
                                    onExpandedChange = { menuExpanded = it }, modifier = Modifier.target("menu")) { AppText("⋮") }
                            }
                            AppContentCard(Modifier.width(780.dp).target("card"), onClick = { cardClicks++ }, contentPadding = PaddingValues(16.dp)) { AppText("原内容卡片点击") }
                            if (style == AppUiStyle.MIUIX) AppPlatformNavigationBar(Modifier.width(780.dp), defaultWindowInsetsPadding = false) {
                                AppPlatformNavigationBarItem(nav == 0, { nav = 0 }, Icons.Outlined.Home, "首页", Modifier.target("nav0"))
                                AppPlatformNavigationBarItem(nav == 1, { nav = 1 }, Icons.Outlined.History, "历史", Modifier.target("nav1"))
                                AppPlatformNavigationBarItem(false, { disabledNavChanges++ }, Icons.Outlined.History, "禁用", Modifier.target("navDisabled"), enabled = false)
                            } else AppNavigationBar(Modifier.width(780.dp)) {
                                AppNavigationBarItem(nav == 0, { nav = 0 }, icon = { AppIcon(Icons.Outlined.Home, null) }, label = { AppText("首页") }, modifier = Modifier.target("nav0"))
                                AppNavigationBarItem(nav == 1, { nav = 1 }, icon = { AppIcon(Icons.Outlined.History, null) }, label = { AppText("历史") }, modifier = Modifier.target("nav1"))
                                AppNavigationBarItem(false, { disabledNavChanges++ }, icon = { AppIcon(Icons.Outlined.History, null) }, label = { AppText("禁用") }, modifier = Modifier.target("navDisabled"), enabled = false)
                            }
                        }
                    }
                }
            }
        }
        var nanos = 0L
        suspend fun settle() { repeat(4) { nanos += 30_000_000L; scene.render(nanos).close(); yield() } }
        fun verify(value: Boolean, message: String) { check(value) { "$style/$mode: $message" }; assertions++ }
        fun point(r: Rect, fraction: Float = 0.5f) = Offset(r.left + r.width * fraction, r.center.y)
        suspend fun click(r: Rect, fraction: Float = 0.5f) {
            scene.sendPointerEvent(PointerEventType.Move, point(r, fraction), timeMillis = nanos / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Press, point(r, fraction), timeMillis = nanos / 1_000_000 + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point(r, fraction), timeMillis = nanos / 1_000_000 + 41, buttons = PointerButtons())
            settle()
        }
        fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::nodes)
        fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        suspend fun typeA() {
            val typed = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED,
                nanos / 1_000_000L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, 'a')
            scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.Unknown, 'a'.code, nativeEvent = typed)); settle()
        }
        try {
            settle()
            click(requireNotNull(bounds["enabled"]), 0.93f); verify(enabled, "enabled switch preference must update hoisted state")
            click(requireNotNull(bounds["disabled"]), 0.93f); verify(disabledChanges == 0, "disabled switch preference must ignore click")
            click(requireNotNull(bounds["preference"])); verify(rowClicks == 1, "original preference row must dispatch click once")
            click(requireNotNull(bounds["choice"])); verify(choice, "single-choice row must dispatch its action")
            click(requireNotNull(bounds["text"])); typeA(); verify(text == "a", "AppTextField must accept real typed event")
            click(requireNotNull(bounds["search"])); typeA(); verify(query == "a", "AppSearchField must emit real keyboard query")
            click(requireNotNull(bounds["nav1"])); verify(nav == 1, "native navigation item must update route")
            click(requireNotNull(bounds["navDisabled"])); verify(disabledNavChanges == 0, "disabled navigation item must reject action")
            click(requireNotNull(bounds["tag"])); verify(tagClicks == 1, "original compact tag must dispatch click")
            click(requireNotNull(bounds["card"])); verify(cardClicks == 1, "original content card must dispatch click")
            click(requireNotNull(bounds["back"])); verify(backClicks == 1, "visible back-to-top button must dispatch actual callback")
            click(requireNotNull(bounds["menu"])); verify(!menuExpanded, "disabled window menu must remain closed")
            val sliderNode = allNodes().first { node -> node.config.contains(SemanticsProperties.ProgressBarRangeInfo) &&
                node.config[SemanticsProperties.ProgressBarRangeInfo].range == 0f..100f }
            val r = sliderNode.boundsInRoot
            scene.sendPointerEvent(PointerEventType.Press, point(r, 0.25f), timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true)); settle()
            for (fraction in listOf(0.4f, 0.6f, 0.85f)) {
                scene.sendPointerEvent(PointerEventType.Move, point(r, fraction), timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true)); settle()
            }
            scene.sendPointerEvent(PointerEventType.Release, point(r, 0.85f), timeMillis = nanos / 1_000_000, buttons = PointerButtons()); settle()
            verify(slider > 65f, "nested original slider preference must emit real drag value")
            val valueNode = allNodes().first { node -> node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any { it.text == "552233" } }
            val copyPoint = valueNode.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, copyPoint, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
            settle(); delay(700); nanos += 700_000_000L; settle()
            scene.sendPointerEvent(PointerEventType.Release, copyPoint, timeMillis = nanos / 1_000_000, buttons = PointerButtons()); settle()
            verify(copy == listOf("完整复制值" to "可复制值"), "original custom preference long press must dispatch exact copy value and label")
            scene.render(nanos + 500_000_000L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(directory.resolve("${style.name.lowercase()}-${mode.name.lowercase()}.png"), data.bytes) }
            }
            println("Actual original preference / navigation / list input PASS: $style / $mode")
        } finally { scene.close() }
    }
    var density by mutableStateOf(1f); var hostHeight by mutableStateOf(900); var measured = -1
    val boundsScene = ImageComposeScene(800, 900, Density(1f)) {
        // ImageComposeScene keeps its own WindowInfo at its original raster size even if layout
        // constraints change. Supply resize events through the same standard host WindowInfo local.
        val real = LocalWindowInfo.current
        val resized = remember(real) { object : WindowInfo by real {
            override val containerSize: IntSize get() = IntSize(800, hostHeight)
        } }
        CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides resized) {
            measured = DesktopWindowConfiguration.current.screenHeightDp
        }
    }
    try {
        boundsScene.render(0L).close(); check(measured == 900); assertions++
        hostHeight = 700; boundsScene.constraints = Constraints.fixed(800, 700)
        boundsScene.render(100_000_000L).close(); check(measured == 700); assertions++
        density = 2f; boundsScene.render(200_000_000L).close(); check(measured == 350); assertions++
    } finally { boundsScene.close() }
    Files.writeString(directory.resolve("result.json"), """{"passed":true,"styles":2,"modes":2,"actualInteractionAssertions":$assertions,"nativePopup":"not opened; parent native gate pending","userClipboard":"untouched","verificationSurface":"ImageComposeScene","nativeWindowTested":false}""")
    assertions
}

class DesktopAppPreferenceComponentsTest {
    @org.junit.jupiter.api.Test
    fun originalPreferencesNavigationAndWindowBoundsDispatchRealInput(): Unit {
        val directory = Files.createTempDirectory("bilipai-preferences-")
        org.junit.jupiter.api.Assertions.assertEquals(59, runOriginalAppPreferenceFixture(directory))
    }
}
