@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings

// The whole production settings frame is mounted: DesktopSettingsTree routes a search hit to its appearance
// detail, the detail mounts the real DesktopAppearanceSettings page, the page mounts the display-scale card,
// and the card mounts DesktopWindowsUxLayoutGroup. Nothing here hand-wires the page or the group: the rows the
// search directory registers are the rows this test finds, clicks and reads back out of the real controller.

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.settings.SettingsSearchFocusController
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.appearance.DesktopAppearanceSettings
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.appearance.DesktopWindowsDisplayScaleController
import com.bilipai.desktop.appearance.DesktopWindowsUxController
import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxSettings
import com.bilipai.desktop.appearance.LocalDesktopWindowsDisplayScale
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx
import com.bilipai.desktop.appearance.decodeDesktopWindowsUx
import com.bilipai.desktop.appearance.WINDOWS_UX_NAMESPACE
import com.bilipai.desktop.data.DesktopDiscoveryRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.DesktopWindowsUxSettingsStrings
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.LocalDesktopUpstreamAndroidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopWindowsUxSearchMountTest {
    @TempDir lateinit var root: Path

    @AfterEach fun clearFocusRequests() {
        SettingsSearchFocusController.clear()
        desktopSettingsItemFocus.clear()
    }

    @Test fun aSearchHitMountsThePageWhereItsControlIsEnabledAndChangesTheModel(): Unit = runBlocking {
        Fixture(root.resolve("scene"), coroutineContext).use { f ->
            f.settle()
            assertTrue(f.page() is DesktopSettingsPage.Search, "the frame opens on the search page")
            assertEquals(0, f.scroll.value, "the appearance page starts at its top, so the display card is below the fold")

            // A real query through the mounted bar, and the row the directory registered for it.
            f.search("播放器")
            assertEquals(
                listOf("windows_ux.player_layout"),
                f.hits().filter { it.startsWith("windows_ux.") },
                "the mounted search must offer the registered row",
            )
            f.clickResultRow(DesktopWindowsUxSettingsStrings.PLAYER)
            f.settle()

            assertEquals(
                DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, "windows_display_scale"),
                f.page(),
                "the hit must open the appearance detail that owns the layout group",
            )
            assertTrue(f.scroll.value > 0f, "the item request must scroll the page down to the anchored card")
            assertTrue(
                f.isVisible(DesktopWindowsUxSettingsStrings.TITLE),
                "after the scroll the mounted page must show the layout group rather than leave it below the fold (scroll=${f.scroll.value}, ${f.describe(DesktopWindowsUxSettingsStrings.TITLE)})",
            )

            // Every registered row is a mounted, enabled control of the production group.
            for (title in f.registeredRowTitles()) {
                assertTrue(f.isVisible(title), "$title must be visible in the mounted page")
                assertTrue(f.hasEnabledOption(title), "$title must be enabled, not a greyed-out placeholder")
            }

            f.choose(DesktopWindowsUxSettingsStrings.RAIL, DesktopWindowsUxSettingsStrings.RAIL_COLLAPSED)
            f.awaitUx { it.railMode.name == "COLLAPSED" }

            f.choose(DesktopWindowsUxSettingsStrings.PLAYER, DesktopWindowsUxSettingsStrings.PLAYER_DESKTOP)
            f.awaitUx { it.playerLayout == DesktopWindowsUxPlayerLayout.DESKTOP }

            f.choose(DesktopWindowsUxSettingsStrings.DETAILS, DesktopWindowsUxSettingsStrings.DETAILS_OPEN)
            f.awaitUx { it.detailsPanel == DesktopWindowsUxDetailsPanel.OPEN }

            f.setWidth(520f)
            f.awaitUx { it.detailsPanelWidthDp == 520 }

            f.choose(DesktopWindowsUxSettingsStrings.HOME, DesktopWindowsUxSettingsStrings.HOME_CLASSIC)
            f.awaitUx { it.homeLayout.name == "CLASSIC" }

            f.choose(DesktopWindowsUxSettingsStrings.CARDS, DesktopWindowsUxSettingsStrings.CARDS_OFF)
            f.awaitUx { it.cardInteractions.name == "OFF" }

            // The reset row is the seventh registered row and has to be operable too.
            f.clickRow(DesktopWindowsUxSettingsStrings.RESET)
            f.awaitUx {
                it.playerLayout == DesktopWindowsUxPlayerLayout.LEGACY && it.detailsPanel == DesktopWindowsUxDetailsPanel.AUTO &&
                    it.detailsPanelWidthDp == null
            }

            // Nothing above was written by a test-owned call: the file the controller opened now holds the result.
            val disk = f.persisted()
            assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, disk.playerLayout)
            assertEquals(DesktopWindowsUxDetailsPanel.AUTO, disk.detailsPanel)
            assertEquals(null, disk.detailsPanelWidthDp)
        }
    }

    private class Fixture(directory: Path, sceneContext: CoroutineContext) : AutoCloseable {
        private var nanos = 0L
        private val size = IntSize(1400, 1200)
        private val scope = CoroutineScope(SupervisorJob())
        private val settingsPath = directory.resolve("preferences")
        private val store = DesktopPluginStore(settingsPath)
        val ux: DesktopWindowsUxController
        private val display: DesktopWindowsDisplayScaleController
        private val prefs: DesktopThemePrefs
        private val navigator = DesktopSettingsNavigator()
        private val search = DesktopSettingsSearchController(
            DesktopSettingsSearchRepository(DesktopPluginContext(DesktopPluginStore(directory.resolve("search")))) { false },
        )
        private val scene: ImageComposeScene
        val scroll = ScrollState(0)
        private val viewport = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())

        init {
            check(GraphicsEnvironment.isHeadless()) { "This fixture requires CPU headless rendering" }
            Files.createDirectories(directory)
            ux = DesktopWindowsUxController(store, scope)
            display = DesktopWindowsDisplayScaleController(store, scope)
            prefs = DesktopThemePrefs(store)
            val context = DesktopPluginContext(store)
            val settingsContext = DesktopOriginalPlayerSettingsContext(
                pluginContext = context, isCurrent = { true }, commitIfCurrent = { block -> block(); true },
                largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false },
            )
            val home = DesktopHomeCardPreferences(context)
            val discovery = DesktopDiscoveryRepository(DesktopRepository(DesktopSessionStore.temporary()))
            val privacy = DesktopPrivacySectionBindings(context, DesktopSearchPreferences(directory.resolve("search-prefs")))
            navigator.openSearch()
            scene = ImageComposeScene(size.width, size.height, Density(1f), coroutineContext = sceneContext) {
                val actualWindow = LocalWindowInfo.current
                val currentWindow = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val containerSize: IntSize get() = size
                } }
                CompositionLocalProvider(
                    LocalDesktopWindowsUx provides ux,
                    LocalDesktopWindowsDisplayScale provides display,
                    LocalDesktopOriginalPlayerSettingsContext provides settingsContext,
                    LocalWindowInfo provides currentWindow,
                    LocalDesktopHomeCardPreferences provides home,
                    LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                ) {
                    DesktopAppearanceTheme(
                        DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                        systemLanguageTags = listOf("zh-CN"),
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            DesktopSettingsTree(
                                navigator = navigator, search = search, historyWritesScope = scope,
                                discovery = discovery, privacy = privacy, onFailure = {},
                                appearanceContent = {
                                    DesktopAppearanceSettings(prefs, onRestartRequested = {}, onNavigateToIconSettings = {}, scrollState = scroll)
                                },
                                pluginsContent = {}, playbackContent = { _, _ -> }, backupContent = { _, _ -> },
                                blockedListContent = {}, donateContent = { _, _ -> }, commentFraudHistoryContent = { _, _ -> },
                                homeContent = { _, _ -> }, systemContent = { _, _ -> }, storageContent = { },
                            )
                        }
                    }
                }
            }
            scene.constraints = Constraints.fixed(size.width, size.height)
        }

        fun page(): DesktopSettingsPage? = navigator.state.value.current

        private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }

        private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        private fun SemanticsNode.description() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        private fun SemanticsNode.disabled() = config.contains(SemanticsProperties.Disabled)
        private fun SemanticsNode.clickable() = config.getOrNull(SemanticsActions.OnClick)

        fun registeredRowTitles(): List<String> = listOf(
            DesktopWindowsUxSettingsStrings.RAIL, DesktopWindowsUxSettingsStrings.PLAYER,
            DesktopWindowsUxSettingsStrings.DETAILS, DesktopWindowsUxSettingsStrings.DETAILS_WIDTH,
            DesktopWindowsUxSettingsStrings.HOME, DesktopWindowsUxSettingsStrings.CARDS,
            DesktopWindowsUxSettingsStrings.RESET,
        )

        private fun group(title: String) = all().firstOrNull { title in it.description() }
        private fun slider() = all().firstOrNull {
            it.config.getOrNull(SemanticsActions.SetProgress) != null &&
                DesktopWindowsUxSettingsStrings.DETAILS_WIDTH in it.description()
        }

        /** The node that carries a row: the choice's own group, the width slider, or the reset button. */
        private fun anchor(title: String): SemanticsNode? = when (title) {
            DesktopWindowsUxSettingsStrings.DETAILS_WIDTH -> slider()
            else -> group(title) ?: all().firstOrNull { it.clickable() != null && it.texts().contains(title) } ?: textNode(title)
        }

        private fun textNode(title: String) = all().firstOrNull {
            it.texts().contains(title) && it.config.getOrNull(SemanticsProperties.EditableText) == null
        }

        fun isVisible(title: String): Boolean {
            val node = anchor(title) ?: return false
            return viewport.contains(node.boundsInRoot.center) && node.boundsInRoot.overlaps(viewport)
        }

        fun describe(title: String): String =
            (anchor(title)?.let { "${it.boundsInRoot} visible=${isVisible(title)}" }) ?: "absent"

        fun hits(): List<String> = search.itemResults.value.map { it.item.settingId }

        fun search(query: String) {
            search.setQuery(query)
        }

        /**
         * The result row is a preference: its caption is a child text and the click sits on the enclosing row, so the
         * click is taken from the smallest clickable node that still contains the caption.
         */
        suspend fun clickResultRow(title: String) {
            settle()
            val caption = all().firstOrNull {
                it.texts().contains(title) && it.config.getOrNull(SemanticsProperties.EditableText) == null
            }
            val node = caption?.let(::clickableAround)
            assertNotNull(
                node,
                "search result row $title is not a clickable row (captions: " +
                    all().mapNotNull { it.texts().firstOrNull() }.take(15) + ")",
            )
            press(node)
        }

        private fun clickableAround(node: SemanticsNode, within: List<SemanticsNode> = all()): SemanticsNode? = within
            .filter { it.clickable() != null && it.boundsInRoot.contains(node.boundsInRoot.center) }
            .minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }

        suspend fun clickRow(title: String) {
            val caption = assertNotNull(textNode(title), "mounted row $title")
            press(assertNotNull(clickableAround(caption), "clickable row $title"))
        }

        /** A row whose first option still accepts a click: a disabled choice has no enabled option node. */
        fun hasEnabledOption(title: String): Boolean {
            if (title == DesktopWindowsUxSettingsStrings.DETAILS_WIDTH) {
                val node = slider() ?: return false
                return !node.disabled() && node.config[SemanticsActions.SetProgress].action != null
            }
            val container = group(title)
            if (container != null) {
                return nodes(container).any { it.clickable() != null && !it.disabled() }
            }
            // A one-action row (the reset button) has no option group of its own: the row around the caption must be clickable.
            val caption = textNode(title) ?: return false
            val button = clickableAround(caption) ?: return false
            return !button.disabled()
        }

        suspend fun choose(title: String, label: String) {
            val container = assertNotNull(group(title), "mounted choice $title")
            val subtree = nodes(container)
            val caption = assertNotNull(subtree.firstOrNull { it.texts().contains(label) }, "option $label of $title")
            val node = assertNotNull(clickableAround(caption, subtree), "enabled option $label of $title")
            assertFalse(node.disabled(), "$label of $title must be enabled")
            press(node)
        }

        suspend fun setWidth(value: Float) {
            val node = assertNotNull(slider(), "the mounted details width control")
            assertFalse(node.disabled(), "the details width control must be enabled")
            assertTrue(node.config[SemanticsActions.SetProgress].action!!.invoke(value), "the width slider must accept the value")
            settle()
        }

        private suspend fun press(node: SemanticsNode) {
            val bounds = node.boundsInRoot
            scene.sendPointerEvent(PointerEventType.Press, bounds.center, timeMillis = nanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, bounds.center, timeMillis = nanos / 1_000_000 + 40,
                buttons = PointerButtons())
            settle()
        }

        suspend fun settle() {
            repeat(12) {
                nanos += 40_000_000
                scene.render(nanos).close()
                yield()
            }
        }

        /** Model writes are serialised on the controller's worker thread, so wait for the value rather than assume it. */
        suspend fun awaitUx(predicate: (DesktopWindowsUxSettings) -> Boolean) {
            withTimeout(10_000) {
                while (!predicate(ux.settings.value)) {
                    nanos += 16_000_000
                    scene.render(nanos).close()
                    delay(5)
                }
            }
        }

        suspend fun persisted(): DesktopWindowsUxSettings {
            ux.closeAndJoin()
            return decodeDesktopWindowsUx(DesktopPluginStore(settingsPath).snapshot(WINDOWS_UX_NAMESPACE).value)
        }

        override fun close() {
            try { scene.close() } finally { runBlocking { ux.closeAndJoin(); display.close() }; scope.cancel() }
        }
    }
}
