@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
)

package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.settings.PlaybackSettingItem
import com.android.purebilibili.feature.settings.SettingsItemAnchor
import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.settingsItemDirectory
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.bilipai.desktop.player.DesktopWindowsAudioOutputController
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.DesktopPlaybackRightPaneContent
import com.bilipai.desktop.ui.DesktopWindowsVideoEnhancementUiBinding
import com.bilipai.desktop.ui.LocalDesktopDetailForeground
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.LocalDesktopWindowsAudioOutputController
import com.bilipai.desktop.ui.LocalDesktopWindowsVideoEnhancement
import com.bilipai.desktop.ui.PageTestLifecycleOwner
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Inspects the real pane called by DesktopOriginalPlaybackSettingsRootHost, not search results.
 * The sibling witness uses the real upstream anchor to prove that runtime inspection works.
 * It is excluded from coverage. Lazy rows are visited by scrolling the actual pane.
 */
class DesktopPlaybackPaneCoverageTest {
    private fun playbackItems(upstream: List<PlaybackSettingItem> = settingsItemDirectory): List<PlaybackSettingItem> {
        // Use the published destination: a future item without a placement row
        // remains visible at runtime and must not silently escape coverage here.
        val published = buildDesktopSettingsItemDirectory(upstream).associateBy { it.settingId }
        return upstream.filter {
            published[it.settingId]?.category == SettingsRootCategory.PLAYBACK_QUALITY
        }
    }

    private fun requireCoverage(directory: List<PlaybackSettingItem>, rendered: Set<String>) {
        val expected = directory.map { it.settingId }.toSet()
        val missing = (expected - rendered).sorted()
        assertTrue(missing.isEmpty(), "Playback right pane missing ${missing.size}/${expected.size} SettingsItemAnchor IDs:\n" + missing.joinToString("\n"))
    }

    private fun renderedIds(): Set<String> {
        val directory = Files.createTempDirectory("bp-playback-pane-coverage-")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = DesktopPluginStore(directory)
        val audio = DesktopWindowsAudioOutputController(store, owner, null, null)
        val enhancement = DesktopVideoEnhancementConfiguration(store, dispatcher = Dispatchers.Default)
        val context = DesktopOriginalPlayerSettingsContext(
            DesktopPluginContext(store), { true }, { it(); true },
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false },
        )
        val bindings = DesktopOriginalPlaybackSettingsBindings(
            context, owner, DesktopOriginalPlaybackSettingsState(), MutableStateFlow(HomeSettings()),
            owns = { true }, admit = { it(); true }, onFailure = { throw it }, onNotice = {},
            pictureInPicture = { false }, playbackLoggedIn = { false }, playbackVip = { false }, settingChange = { _, _ -> },
        )
        val lifecycle = PageTestLifecycleOwner()
        val tables = mutableSetOf<CompositionData>()
        val witness = "coverage.witness"
        var nanos = 0L
        val scene = ImageComposeScene(1000, 900, Density(1f)) {
            currentComposer.collectParameterInformation()
            tables += currentComposer.compositionData
            CompositionLocalProvider(LocalInspectionTables provides tables, LocalLifecycleOwner provides lifecycle,
                LocalDesktopDetailForeground provides true,
                LocalDesktopWindowsAudioOutputController provides audio,
                LocalDesktopOriginalPlayerSettingsContext provides context,
                LocalDesktopWindowsVideoEnhancement provides DesktopWindowsVideoEnhancementUiBinding(
                    enhancement, MutableStateFlow(DesktopVideoEnhancementState()))) {
                DesktopAppearanceTheme(
                    DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("en"),
                ) {
                    Box {
                        DesktopPlaybackRightPaneContent(bindings, onBack = {})
                        SettingsItemAnchor(witness) { Text("playback.coverage_text_decoy") }
                    }
                }
            }
        }
        fun render() { repeat(4) { nanos += 30_000_000L; scene.render(nanos).close() } }
        // Read only key2 in this anchor's own LaunchedEffect(request?.token, settingId).
        // Its direct remember slots retain the key; never inspect content or nested anchors.
        fun anchors(data: CompositionData): Set<String> = data.compositionGroups.flatMap { group ->
            if (group.sourceInfo?.startsWith("C(SettingsItemAnchor)") == true) {
                val effect = group.compositionGroups.single { it.sourceInfo?.startsWith("C(LaunchedEffect)") == true }
                val ids = effect.compositionGroups.flatMap { it.data.filterIsInstance<String>().toList() }
                assertEquals(1, ids.size, "Each SettingsItemAnchor must expose exactly its own settingId key")
                ids + anchors(group)
            } else anchors(group).toList()
        }.toSet()
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        try {
            render()
            val found = mutableSetOf<String>()
            var bottomStable = false
            var previous = emptySet<String>()
            var scrollAttempts = 0
            while (!bottomStable) {
                tables.forEach { found += anchors(it) }
                val scrollNodes = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                assertTrue(scrollNodes.isNotEmpty(), "Right pane must expose its vertical scroll range")
                val atBottom = scrollNodes.all { node ->
                    val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!
                    range.value() >= range.maxValue()
                }
                bottomStable = atBottom && previous == found
                previous = found.toSet()
                if (!atBottom) scrollNodes.forEach { node ->
                    val scroll = node.config.getOrNull(SemanticsActions.ScrollBy)?.action
                    checkNotNull(scroll) { "Scrollable pane has no ScrollBy action" }(0f, 650f)
                }
                assertTrue(++scrollAttempts <= 1000, "Right pane never reached a stable bottom after $scrollAttempts scroll attempts")
                render()
            }
            assertTrue(witness in found, "Runtime observer did not see the real SettingsItemAnchor witness; coverage cannot pass vacuously")
            assertTrue("playback.coverage_text_decoy" !in found, "Anchor content text must not count as settingId")
            return found - witness
        } finally {
            scene.close()
            audio.close()
            runBlocking { enhancement.flushAndClose() }
            lifecycle.lifecycle.currentState = Lifecycle.State.DESTROYED
            owner.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun everyAvailablePlaybackDirectoryItemHasAnAnchorInTheRenderedRightPane() {
        assertTrue(playbackItems().isNotEmpty(), "Placement must select playback entries")
        requireCoverage(playbackItems(), renderedIds())
    }

    @Test fun aDirectoryOnlyItemIsReportedAlongsideAnyOtherMissingIds() {
        val actual = renderedIds()
        val fake = settingsItemDirectory.first { it.page == SettingsRootCategory.FULLSCREEN_GESTURE }
            .copy(settingId = "playback.coverage_directory_only", title = "Directory-only coverage witness")
        assertTrue(fake.settingId !in desktopSettingsItemPlacement, "Witness must have no placement row")
        val injectedDirectory = playbackItems(settingsItemDirectory + fake)
        assertEquals(fake, injectedDirectory.single { it.settingId == fake.settingId }, "New upstream entries must survive canonical placement into playback")
        val failure = assertFailsWith<AssertionError> { requireCoverage(injectedDirectory, actual) }
        val missing = (injectedDirectory.map { it.settingId }.toSet() - actual).sorted()
        assertTrue(fake.settingId in missing)
        assertEquals("Playback right pane missing ${missing.size}/${injectedDirectory.size} SettingsItemAnchor IDs:\n" + missing.joinToString("\n"), failure.message)
    }
}
