@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ImageComposeScene test (CPU scene, headless JVM; no native window, no GPU, no network) of the restyled LEGACY control bar:
 * the real composable on real layout, real semantics and real pointer events. It checks that every button is one named
 * Button with one click action of the kit size, that a click reaches its operation, that the disabled and narrow layouts
 * keep the behaviour of the bar before the restyle, that the pills show their value and that opening a menu holds the
 * chrome. The harness is the one of DesktopKitUiTest. The menu windows themselves (DesktopWindowsPlayerMenu) are native
 * dialogs and do not compose in a scene without an owner window, so their rows are judged by the Windows fixtures; the
 * tooltip (a real-time delay and a popup) has no UI test here either. Assumption only a compile run can disprove: the
 * unmerged semantics tree reports the content description and the click action on the node of the clickable.
 */
class DesktopWindowsVideoControlBarUiTest {
    private class Calls {
        val events = mutableListOf<String>()
        val holds = mutableListOf<DesktopWindowsFullscreenChromeInteraction>()
    }

    private class BarScene(density: Float, widthDp: Int, content: @Composable () -> Unit) : AutoCloseable {
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        }

        private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(density)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("en"),
            ) {
                CompositionLocalProvider(LocalDesktopKitReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.width(widthDp.dp)) { content() }
                    }
                }
            }
        }
        private var nanos = 0L

        fun settle(frames: Int = 6) {
            repeat(frames) {
                nanos += 30_000_000L
                scene.render(nanos).close()
            }
        }

        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }

        fun clickable(): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }

        fun named(name: String): List<SemanticsNode> =
            nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(name) }

        fun hasText(text: String): Boolean =
            nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

        fun click(point: Offset) {
            val at = nanos / 1_000_000
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = at)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = at + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = at + 41, buttons = PointerButtons())
            settle()
        }

        fun move(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = nanos / 1_000_000)
            settle()
        }

        override fun close() = scene.close()
    }

    @Composable
    private fun TestBar(
        calls: Calls,
        state: PlayerState,
        enabled: Boolean = true,
        fullscreen: Boolean = false,
        detailsOpen: Boolean = false,
        qualities: List<Pair<Int, String>> = listOf(80 to "1080P", 64 to "720P"),
        selectedQuality: Int? = 80,
    ) {
        DesktopWindowsVideoControlBar(
            state = state, sourceVersion = 1L, enabled = enabled, fullscreen = fullscreen, detailsOpen = detailsOpen,
            hasPrevious = true, hasNext = true, canPictureInPicture = true,
            onInteractionHoldChanged = { calls.holds += it }, onChromePointerInput = { calls.events += "pointer" },
            speedOptions = listOf(1f, 1.5f), qualities = qualities, selectedQuality = selectedQuality,
            canOpenCollection = false, canOpenPlaybackQueue = false, onOpenCollection = {}, onOpenPlaybackQueue = {},
            canOpenInteraction = true, onSendDanmaku = {}, onShareVideo = {},
            canDownloadAudio = true, canSaveCover = true, onDownloadAudio = {}, onSaveCover = {},
            canScreenshot = true, screenshotBusy = false, onScreenshot = {},
            chapters = null, chaptersSource = null, onChapterSeek = { _, _, _ -> },
            onPlayPause = { calls.events += "playPause" }, onPrevious = { calls.events += "previous" },
            onNext = { calls.events += "next" }, onMute = { calls.events += "mute" },
            onVolume = { calls.events += "volume" }, onSpeed = { calls.events += "speed" },
            onQuality = { calls.events += "quality" }, onSeek = { calls.events += "seek" },
            onPictureInPicture = { calls.events += "pip" }, onFullscreen = { calls.events += "fullscreen" },
            onDetails = { calls.events += "details" }, onOpenIntroduction = { calls.events += "introduction" },
            sponsorSkip = {}, enhancement = {},
        )
    }

    private fun playing() = PlayerState(ready = true, paused = false, positionSeconds = 12.0, durationSeconds = 100.0, speed = 1.5)

    private fun paused() = playing().copy(paused = true)

    private fun withBar(
        density: Float,
        widthDp: Int,
        calls: Calls = Calls(),
        content: @Composable (Calls) -> Unit,
        inspect: (BarScene, Calls) -> Unit,
    ) {
        val scene = BarScene(density, widthDp) { content(calls) }
        try {
            scene.settle()
            inspect(scene, calls)
        } finally {
            scene.close()
        }
    }

    private val wideNames = listOf(
        DesktopWindowsVideoControlBarStrings.PLAY, DesktopWindowsVideoControlBarStrings.PREVIOUS,
        DesktopWindowsVideoControlBarStrings.NEXT, DesktopWindowsVideoControlBarStrings.VOLUME_AND_MUTE,
        DesktopWindowsVideoControlBarStrings.SPEED, DesktopWindowsVideoControlBarStrings.QUALITY,
        DesktopWindowsVideoControlBarStrings.DETAILS, DesktopWindowsVideoControlBarStrings.MINI_PLAYER,
        DesktopWindowsVideoControlBarStrings.FULLSCREEN, DesktopWindowsVideoControlBarStrings.MORE_ACTIONS,
    )

    private val narrowNames = listOf(
        DesktopWindowsVideoControlBarStrings.PLAY, DesktopWindowsVideoControlBarStrings.DETAILS,
        DesktopWindowsVideoControlBarStrings.FULLSCREEN, DesktopWindowsVideoControlBarStrings.MORE_ACTIONS,
    )

    @Test fun everyWideButtonIsOneNamedButtonWithOneClickActionAndTheKitSizeAtEveryDensity() {
        for (density in DENSITIES) {
            withBar(density, WIDE_DP, content = { TestBar(it, paused()) }) { scene, _ ->
                val clickable = scene.clickable()
                assertEquals(wideNames.size, clickable.size, "$density: ten actions, the tooltips and the seek bar add none")
                for (name in wideNames) {
                    val found = scene.named(name)
                    assertEquals(1, found.size, "$density: exactly one node is named $name")
                    val node = found.single()
                    assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role), "$density: $name is a Button")
                    assertNotNull(node.config.getOrNull(SemanticsActions.OnClick), "$density: $name has its click action")
                    assertNull(node.config.getOrNull(SemanticsActions.OnLongClick), "$density: $name has no long-click action")
                }
                val iconNames = wideNames - setOf(DesktopWindowsVideoControlBarStrings.SPEED, DesktopWindowsVideoControlBarStrings.QUALITY)
                for (name in iconNames) {
                    val bounds = scene.named(name).single().boundsInRoot
                    assertEquals(32f * density, bounds.width, "$density: $name is a 32 dp hit")
                    assertEquals(32f * density, bounds.height, "$density: $name is a 32 dp hit")
                }
                for (name in listOf(DesktopWindowsVideoControlBarStrings.SPEED, DesktopWindowsVideoControlBarStrings.QUALITY)) {
                    val bounds = scene.named(name).single().boundsInRoot
                    assertEquals(32f * density, bounds.height, "$density: the $name pill is 32 dp tall")
                    assertTrue(bounds.width >= 32f * density, "$density: the $name pill is at least 32 dp wide")
                }
                assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.SEEK_BAR).size, "$density: the seek bar keeps its name")
            }
        }
    }

    @Test fun aClickReachesTheOperationOfItsButtonOnce() {
        withBar(1f, WIDE_DP, content = { TestBar(it, paused()) }) { scene, calls ->
            val expected = listOf(
                DesktopWindowsVideoControlBarStrings.PLAY to "playPause", DesktopWindowsVideoControlBarStrings.PREVIOUS to "previous",
                DesktopWindowsVideoControlBarStrings.NEXT to "next", DesktopWindowsVideoControlBarStrings.MINI_PLAYER to "pip",
                DesktopWindowsVideoControlBarStrings.FULLSCREEN to "fullscreen", DesktopWindowsVideoControlBarStrings.DETAILS to "details",
            )
            for ((name, event) in expected) {
                calls.events.clear()
                scene.click(scene.named(name).single().boundsInRoot.center)
                assertEquals(listOf(event), calls.events.filter { it != "pointer" }, "a click on $name")
            }
        }
    }

    @Test fun theNamesFollowThePlayerStateLikeTheBarBeforeTheRestyle() {
        var state by mutableStateOf(paused())
        var fullscreen by mutableStateOf(false)
        val calls = Calls()
        val scene = BarScene(1f, WIDE_DP) { TestBar(calls, state, fullscreen = fullscreen) }
        try {
            scene.settle()
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.PLAY).size, "paused shows play")
            assertEquals(0, scene.named(DesktopWindowsVideoControlBarStrings.PAUSE).size)
            state = playing()
            scene.settle()
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.PAUSE).size, "playing shows pause")
            assertEquals(0, scene.named(DesktopWindowsVideoControlBarStrings.PLAY).size)
            state = playing().copy(ended = true)
            scene.settle()
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.PLAY).size, "ended shows play")
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.FULLSCREEN).size)
            fullscreen = true
            scene.settle()
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.EXIT_FULLSCREEN).size, "fullscreen shows exit")
            assertEquals(0, scene.named(DesktopWindowsVideoControlBarStrings.FULLSCREEN).size)
            state = playing().copy(muted = true)
            scene.settle()
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.VOLUME_AND_MUTE).size, "the volume button keeps its name when muted")
        } finally {
            scene.close()
        }
    }

    @Test fun aDisabledBarIgnoresItsOperationButtonsAndKeepsDetailsFullscreenAndMoreLive() {
        withBar(1f, WIDE_DP, content = { TestBar(it, paused(), enabled = false) }) { scene, calls ->
            val dead = wideNames - setOf(
                DesktopWindowsVideoControlBarStrings.DETAILS, DesktopWindowsVideoControlBarStrings.FULLSCREEN,
                DesktopWindowsVideoControlBarStrings.MORE_ACTIONS,
            )
            for (name in dead) {
                assertNotNull(scene.named(name).single().config.getOrNull(SemanticsProperties.Disabled), "$name is disabled")
            }
            for (name in listOf(DesktopWindowsVideoControlBarStrings.DETAILS, DesktopWindowsVideoControlBarStrings.FULLSCREEN,
                DesktopWindowsVideoControlBarStrings.MORE_ACTIONS)) {
                assertNull(scene.named(name).single().config.getOrNull(SemanticsProperties.Disabled), "$name stays enabled")
            }
            scene.click(scene.named(DesktopWindowsVideoControlBarStrings.PLAY).single().boundsInRoot.center)
            scene.click(scene.named(DesktopWindowsVideoControlBarStrings.NEXT).single().boundsInRoot.center)
            assertEquals(emptyList<String>(), calls.events.filter { it != "pointer" }, "a disabled button ignores a click")
            scene.click(scene.named(DesktopWindowsVideoControlBarStrings.DETAILS).single().boundsInRoot.center)
            assertEquals(listOf("details"), calls.events.filter { it != "pointer" })
        }
    }

    @Test fun thePlayButtonStaysDisabledUntilThePlayerIsReady() {
        withBar(1f, WIDE_DP, content = { TestBar(it, paused().copy(ready = false)) }) { scene, _ ->
            assertNotNull(scene.named(DesktopWindowsVideoControlBarStrings.PLAY).single().config.getOrNull(SemanticsProperties.Disabled))
        }
    }

    @Test fun detailsIsSelectedOnlyWhileThePanelIsOpen() {
        for (open in listOf(false, true)) {
            withBar(1f, WIDE_DP, content = { TestBar(it, paused(), detailsOpen = open) }) { scene, _ ->
                val node = scene.named(DesktopWindowsVideoControlBarStrings.DETAILS).single()
                assertEquals(open, node.config.getOrNull(SemanticsProperties.Selected), "details open = $open")
            }
        }
    }

    @Test fun belowTheBreakpointsTheBarKeepsTheNarrowSetAndNothingElse() {
        for (width in listOf(800, 600, 400)) {
            withBar(1f, width, content = { TestBar(it, paused()) }) { scene, _ ->
                val clickable = scene.clickable()
                assertEquals(narrowNames.size, clickable.size, "$width dp: four actions")
                for (name in narrowNames) assertEquals(1, scene.named(name).size, "$width dp: $name is there")
                for (name in wideNames - narrowNames.toSet()) assertEquals(0, scene.named(name).size, "$width dp: $name is not there")
            }
        }
    }

    // The breakpoint is read from the BoxWithConstraints inside the bar's Column, which pads both sides by
    // DESKTOP_CONTROL_BAR_SIDE_PADDING_DP. The scene width below is the OUTER width, so the inner width the bar tests is
    // the outer one minus that padding twice: the edge is at EXPANDED_MIN + 2 * SIDE_PADDING, not at EXPANDED_MIN.
    @Test fun theWideSetStartsExactlyAtTheBreakpoint() {
        val edge = DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP + 2 * DESKTOP_CONTROL_BAR_SIDE_PADDING_DP
        withBar(1f, edge - 1, content = { TestBar(it, paused()) }) { scene, _ ->
            assertEquals(0, scene.named(DesktopWindowsVideoControlBarStrings.PREVIOUS).size,
                "an inner width of ${DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP - 1} dp is still narrow")
        }
        withBar(1f, edge, content = { TestBar(it, paused()) }) { scene, _ ->
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.PREVIOUS).size,
                "an inner width of $DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP dp is wide")
        }
    }

    @Test fun thePillsShowTheirValueAndKeepTheirAccessibleNames() {
        withBar(1f, WIDE_DP, content = { TestBar(it, paused()) }) { scene, _ ->
            assertTrue(scene.hasText("1.5×"), "the speed pill shows the speed and the multiplication sign")
            assertTrue(scene.hasText("1080P"), "the quality pill shows the selected quality")
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.SPEED).size)
            assertEquals(1, scene.named(DesktopWindowsVideoControlBarStrings.QUALITY).size)
        }
        withBar(1f, WIDE_DP, content = { TestBar(it, paused(), qualities = emptyList(), selectedQuality = null) }) { scene, _ ->
            assertTrue(scene.hasText(DesktopWindowsVideoControlBarStrings.QUALITY), "no quality shows the plain word")
            assertNotNull(scene.named(DesktopWindowsVideoControlBarStrings.QUALITY).single().config.getOrNull(SemanticsProperties.Disabled),
                "a pill without qualities is disabled")
        }
    }

    @Test fun aPlainButtonHoldsNothingAndTheBarReportsItsInteraction() {
        withBar(1f, WIDE_DP, content = { TestBar(it, paused()) }) { scene, calls ->
            assertTrue(calls.holds.isNotEmpty(), "the bar reports its interaction")
            assertTrue(calls.holds.none { it.operationHeld }, "no menu is open yet")
            scene.click(scene.named(DesktopWindowsVideoControlBarStrings.PLAY).single().boundsInRoot.center)
            assertTrue(calls.holds.none { it.operationHeld }, "a plain button holds nothing")
        }
    }

    @Test fun openingAMenuHoldsTheChrome() {
        for (name in listOf(DesktopWindowsVideoControlBarStrings.SPEED, DesktopWindowsVideoControlBarStrings.QUALITY,
            DesktopWindowsVideoControlBarStrings.VOLUME_AND_MUTE, DesktopWindowsVideoControlBarStrings.MORE_ACTIONS)) {
            withBar(1f, WIDE_DP, content = { TestBar(it, paused()) }) { scene, calls ->
                scene.click(scene.named(name).single().boundsInRoot.center)
                assertTrue(calls.holds.last().operationHeld, "opening $name holds the chrome")
            }
        }
    }

    @Test fun thePointerOverTheBarIsReportedToTheChrome() {
        withBar(1f, WIDE_DP, content = { TestBar(it, paused()) }) { scene, calls ->
            scene.move(scene.named(DesktopWindowsVideoControlBarStrings.FULLSCREEN).single().boundsInRoot.center)
            assertTrue("pointer" in calls.events, "a pointer move over the bar reaches onChromePointerInput")
        }
    }

    @Test fun theLeavingBarReleasesItsHold() {
        val calls = Calls()
        var visible by mutableStateOf(true)
        val scene = BarScene(1f, WIDE_DP) { if (visible) TestBar(calls, paused()) }
        try {
            scene.settle()
            visible = false
            scene.settle()
            assertEquals(DesktopWindowsFullscreenChromeInteraction(), calls.holds.last(), "disposing the bar clears the hold")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 2000
        const val HEIGHT = 300
        const val WIDE_DP = 1000
        val DENSITIES = listOf(1f, 1.25f, 1.5f)
    }
}
