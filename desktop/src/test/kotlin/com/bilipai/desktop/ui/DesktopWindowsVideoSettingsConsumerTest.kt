@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

// Uses TON-1056's public Leaf resource seam; original frozen T1–T3 remain untouched.
// The resource replacement supplies content only. All settings reads, selection, panel state,
// entry effects, docking and stage geometry execute in production composables.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.DesktopHomeCardPreferences
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.bilipai.desktop.appearance.DesktopWindowsUxController
import com.bilipai.desktop.appearance.DesktopWindowsUxSettings
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxCommand
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx
import com.bilipai.desktop.appearance.decodeDesktopWindowsUx
import com.bilipai.desktop.appearance.WINDOWS_UX_NAMESPACE

class DesktopWindowsVideoSettingsConsumerTest {
    @TempDir lateinit var root: Path

    @Test fun actualSettingsChoicesChangeStageAndPanelAndPersist(): Unit = runBlocking {
        Fixture(root.resolve("settings"), coroutineContext).use { f ->
            f.settle()
            f.click(DesktopWindowsUxSettingsStrings.DETAILS_CLOSED, DesktopWindowsUxSettingsStrings.DETAILS)
            assertEquals(0, f.livePanels)
            f.snapshot("legacy")
            val legacy = f.viewportBounds
            val identity = f.stageIdentity
            f.click(DesktopWindowsUxSettingsStrings.PLAYER_DESKTOP, DesktopWindowsUxSettingsStrings.PLAYER)
            assertEquals(DesktopWindowsUxPlayerLayout.DESKTOP, f.ux.settings.value.playerLayout)
            assertTrue(f.viewportBounds.height > legacy.height + 100, "DESKTOP restores the sibling rows' stage height")
            assertTrue(f.controlsBounds.bottom < f.viewportBounds.bottom, "desktop controls lie inside the viewport")
            assertTrue(f.headerBounds.top > f.viewportBounds.top, "desktop header lies inside the viewport")
            assertSame(identity, f.stageIdentity)
            assertEquals(0, f.stageDisposes)
            f.click(DesktopWindowsUxSettingsStrings.DETAILS_OPEN, DesktopWindowsUxSettingsStrings.DETAILS)
            assertEquals(1, f.livePanels)
            f.width(320f)
            assertEquals(320, f.ux.settings.value.detailsPanelWidthDp)
            assertEquals(320f, f.panelBounds.width)
            f.snapshot("desktop-320")
            val narrowPanelStage = f.viewportBounds.width
            f.width(560f)
            f.snapshot("desktop-560")
            assertEquals(560f, f.panelBounds.width)
            println("layout legacy=$legacy desktop=${f.viewportBounds} panel=${f.panelBounds} controls=${f.controlsBounds} stageDisposes=${f.stageDisposes}")
            assertEquals(240f, narrowPanelStage - f.viewportBounds.width)
            f.resize(IntSize(3600, 1500), density = 1.5f); f.settle()
            assertEquals(840f, f.panelBounds.width, "560dp is 840 physical pixels at 150%")
            assertSame(identity, f.stageIdentity)
            f.resize(IntSize(2400, 1000)); f.settle()
            f.click(DesktopWindowsUxSettingsStrings.PLAYER_LEGACY)
            assertEquals(legacy.height, f.viewportBounds.height)
            assertTrue(f.controlsBounds.top >= f.viewportBounds.bottom, "legacy controls reserve a sibling row")
            assertSame(identity, f.stageIdentity)
            assertEquals(0, f.stageDisposes)
            val disk = f.persisted()
            assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, disk.playerLayout)
            assertEquals(DesktopWindowsUxDetailsPanel.OPEN, disk.detailsPanel)
            assertEquals(560, disk.detailsPanelWidthDp)
        }
    }

    @Test fun liveAutoAndManualCloseKeepEntryIntentAndAutomaticWidth(): Unit = runBlocking {
        Fixture(root.resolve("auto"), coroutineContext).use { f ->
            f.settle()
            f.click(DesktopWindowsUxSettingsStrings.DETAILS_CLOSED, DesktopWindowsUxSettingsStrings.DETAILS)
            assertEquals(0, f.livePanels)
            f.click(DesktopWindowsUxSettingsStrings.DETAILS_AUTO, DesktopWindowsUxSettingsStrings.DETAILS)
            assertEquals(1, f.livePanels, "switching to AUTO at a wide window decides now")
            f.click("panel toggle")
            assertEquals(0, f.livePanels)
            f.resize(IntSize(2500, 1000)); f.settle()
            assertEquals(0, f.livePanels, "resize must not undo manual close")
            f.covered.value = true; f.settle()
            f.covered.value = false; f.settle()
            assertEquals(0, f.livePanels, "same-entry restore keeps the manual close")
            f.click(DesktopWindowsUxSettingsStrings.DETAILS_OPEN, DesktopWindowsUxSettingsStrings.DETAILS)
            f.width(320f)
            f.click(DesktopWindowsUxSettingsStrings.DETAILS_WIDTH_RESET)
            assertEquals(null, f.ux.settings.value.detailsPanelWidthDp)
            assertEquals(560f, f.panelBounds.width, "automatic width comes from the real wide-window policy")
            val disk = f.persisted()
            assertEquals(null, disk.detailsPanelWidthDp)
        }
    }

    @Test fun crampedRowsAndIneligibleFullscreenNeverMountDespiteOpenIntent(): Unit = runBlocking {
        Fixture(root.resolve("budget"), coroutineContext, initialSize = IntSize(1300, 1000),
            initialMode = DesktopWindowsUxDetailsPanel.OPEN).use { f ->
            f.settle()
            assertTrue(f.state().open.value)
            assertEquals(0, f.livePanels, "client is wide but real row cannot fit minimum stage plus panel")
            f.resize(IntSize(1999, 1000)); f.fullscreen.value = true; f.settle()
            assertEquals(0, f.livePanels)
            f.resize(IntSize(2000, 1000)); f.settle()
            assertEquals(1, f.livePanels)
            assertEquals(440f, f.panelBounds.width)
            assertEquals(0, f.stageDisposes)
        }
    }

    private class Fixture(
        directory: Path, sceneContext: CoroutineContext,
        initialSize: IntSize = IntSize(2400, 1000),
        initialMode: DesktopWindowsUxDetailsPanel = DesktopWindowsUxDetailsPanel.AUTO,
        initialFullscreen: Boolean = false,
        commentRootRpid: Long = 0L, commentTargetRpid: Long = 0L,
    ) : AutoCloseable {
        val owner = mutableStateOf<Any>(Any())
        val owned = mutableStateOf(true)
        val fullscreen = mutableStateOf(initialFullscreen)
        val covered = mutableStateOf(false)
        private val viewport = mutableStateOf(initialSize)
        private val scale = mutableStateOf(1f)
        private var observed: DesktopWindowsVideoLeafPanelState? = null
        var stageIdentity: Any? = null; var stageMounts = 0; var stageDisposes = 0
        var panelMounts = 0; var panelDisposes = 0
        var panelBounds = Rect.Zero
        val livePanels get() = panelMounts - panelDisposes
        private var nanos = 0L
        private val scope = CoroutineScope(SupervisorJob())
        private val shell: DesktopOriginalVideoShellOwner
        private val settingsPath = directory.resolve("preferences")
        val ux = DesktopWindowsUxController(DesktopPluginStore(settingsPath), scope)
        var viewportBounds = Rect.Zero
        var headerBounds = Rect.Zero
        var controlsBounds = Rect.Zero
        private val scene: ImageComposeScene

        fun state(): DesktopWindowsVideoLeafPanelState = checkNotNull(observed) { "the public Leaf never asked the resource closure for content" }

        init {
            check(GraphicsEnvironment.isHeadless()) { "This fixture requires CPU headless rendering" }
            Files.createDirectories(directory)
            ux.submit(DesktopWindowsUxCommand.SetDetailsPanel(initialMode))
            val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("ui-store")))
            val home = DesktopHomeCardPreferences(context)
            val dynamic = DesktopDynamicTimelinePreferences(context)
            // Never installed: no factory, no player, no Window. Temporary session store, temporary plugin store.
            shell = DesktopOriginalVideoShellOwner(
                repository = DesktopRepository(DesktopSessionStore.temporary()),
                globalStore = DesktopPluginStore(directory.resolve("leaf-store")),
                scope = scope, rootAlive = { true }, rootAdmission = { action -> action(); true },
                metadata = {}, feedback = {},
            )
            val route = BiliPaiNavKey.VideoDetail("av115391124741470", commentRootRpid = commentRootRpid, commentTargetRpid = commentTargetRpid)
            scene = ImageComposeScene(initialSize.width, initialSize.height, Density(1f), coroutineContext = sceneContext) {
                val actualWindow = LocalWindowInfo.current
                val currentWindow = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val containerSize: IntSize get() = viewport.value
                } }
                CompositionLocalProvider(
                    LocalDensity provides Density(scale.value),
                    LocalDesktopWindowsUx provides ux,
                    LocalWindowInfo provides currentWindow,
                    LocalDesktopHomeCardPreferences provides home,
                    LocalDesktopDynamicTimelinePreferences provides dynamic,
                    LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                ) {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT,
                        hapticFeedbackEnabled = false), systemLanguageTags = listOf("zh-CN")) {
                        Surface(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(650.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            DesktopWindowsUxLayoutGroup()
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                        val holder = rememberSaveableStateHolder()
                        // The provider itself must leave composition for the holder to capture this entry's rememberSaveable
                        // values; gating only the Leaf inside a live provider drops them instead of saving them.
                        if (!covered.value) holder.SaveableStateProvider("leaf-entry") {   // same entry across cover / restore
                            DesktopWindowsVideoPhysicalLeaf(
                                route = route, shell = shell, active = true, presentationAlive = true,
                                fullscreen = fullscreen.value, pipActive = false,
                                preferences = PlayerPreferences(), preferencesChanged = {},
                                actions = failFastActions(),
                                resourceHost = { fakeResources() },
                            )
                        }
                        }
                        }
                        }
                    }
                }
            }
            scene.constraints = Constraints.fixed(initialSize.width, initialSize.height)
        }

        @androidx.compose.runtime.Composable private fun fakeResources() = DesktopWindowsVideoLeafResources(
            owner = owner.value, current = { owned.value },
            content = { state ->
                observed = state
                DesktopWindowsVideoLeafRenderPlan(
                    chromeVisible = true,
                    header = { TextButton(onClick = {}, modifier = Modifier.fillMaxWidth().height(44.dp)
                        .onGloballyPositioned { headerBounds = it.boundsInRoot() }) { Text("header") } },
                    controls = { TextButton(onClick = { state.open.value = !state.open.value },
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                            .onGloballyPositioned { controlsBounds = it.boundsInRoot() }) { Text("panel toggle") } },
                    viewport = { chrome ->
                        val identity = remember { Any() }
                        DisposableEffect(Unit) { stageMounts++; stageIdentity = identity; onDispose { stageDisposes++ } }
                        Box(Modifier.fillMaxSize().background(Color(0xFF17202A)).onGloballyPositioned { viewportBounds = it.boundsInRoot() }) { chrome() }
                    },
                    status = {},
                    panel = { handed ->
                        DisposableEffect(Unit) { panelMounts++; onDispose { panelDisposes++ } }
                        DesktopWindowsVideoDetailsPanel(
                            modifier = handed.onGloballyPositioned { panelBounds = it.boundsInRoot() },
                            selectedTab = state.tab.value, onTabChange = { state.tab.value = it },
                            onClose = { state.open.value = false }, current = { owned.value },
                            related = { Text("related") }, comments = { Text("comments") },
                            introduction = { Text("long intro", Modifier.height(2400.dp)) },   // Production Panel owns the INTRODUCTION LazyColumn.
                        )
                    },
                    afterLayout = {},
                )
            },
        )

        // Root action callbacks fail fast; composable presentation slots remain empty in this fixture.
        // All 24 named members match DesktopWindowsVideoActions on the pinned integration source.
        private fun failFastActions(): DesktopWindowsVideoActions = DesktopWindowsVideoActions(
            back = { error("back") }, fullscreen = { error("fullscreen") }, pictureInPicture = { error("pip") },
            user = { error("user") }, video = { error("video") }, download = { _, _ -> error("download") },
            favorite = { _, _, _ -> error("favorite") }, overlay = {}, enhancement = {},
            openLink = { error("openLink") }, honorLink = { _, _, _, _ -> error("honorLink") },
            descriptionLink = { _, _, _, _ -> error("descriptionLink") },
            relatedNavigation = { _, _, _, _ -> error("relatedNavigation") },
            metadataNavigation = { _, _, _, _ -> error("metadataNavigation") },
            login = { error("login") }, danmakuSettings = { error("danmakuSettings") }, toggleDanmaku = { error("toggleDanmaku") },
            notice = { error("notice") }, focusChanged = { error("focusChanged") }, nativeKey = { error("nativeKey") },
            registerScreenshot = { error("registerScreenshot") },
            collectionQueue = {}, bgm = {}, interaction = {},
        )

        fun resize(size: IntSize, density: Float = 1f) {
            viewport.value = size; scale.value = density
            scene.constraints = Constraints.fixed(size.width, size.height)
        }

        suspend fun settle() { repeat(6) { nanos += 40_000_000; scene.render(nanos).close(); yield() } }

        private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }

        fun snapshot(name: String) {
            val output = Path.of("build/reports/ton1592")
            Files.createDirectories(output)
            scene.render(nanos).use { rendered ->
                rendered.encodeToData()!!.use { Files.write(output.resolve("$name.png"), it.bytes) }
            }
        }

        suspend fun click(text: String, group: String? = null) {
            val candidates = if (group == null) all() else nodes(all().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(group) == true
            })
            val button = candidates.filter { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null &&
                    nodes(n).any { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == text } == true }
            }.sortedBy { it.boundsInRoot.top }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            val bounds = assertNotNull(button, "Actual clickable semantic node for $text").boundsInRoot
            scene.sendPointerEvent(PointerEventType.Press, bounds.center, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, bounds.center, timeMillis = nanos / 1_000_000 + 40, buttons = PointerButtons())
            settle()
        }

        suspend fun width(value: Float) {
            val slider = all().single { it.config.getOrNull(SemanticsActions.SetProgress) != null }
            assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(value))
            settle()
        }

        suspend fun persisted(): DesktopWindowsUxSettings {
            ux.closeAndJoin()
            return decodeDesktopWindowsUx(DesktopPluginStore(settingsPath).snapshot(WINDOWS_UX_NAMESPACE).value)
        }

        override fun close() {
            try { scene.close() } finally { runBlocking { ux.closeAndJoin(); shell.closeAndJoin() }; scope.cancel() }
        }
    }
}
