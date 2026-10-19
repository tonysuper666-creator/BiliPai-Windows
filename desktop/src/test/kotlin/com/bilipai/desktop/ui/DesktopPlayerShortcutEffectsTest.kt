@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.android.purebilibili.feature.video.player.PlayerKeyAction
import com.android.purebilibili.feature.video.player.resolvePlayerKeyAction
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure part of the window-level player shortcuts against the shared contract (section player_keys): key names, chapter jumps, speed
 * steps, the focus rule, one effect per action kind with a fake player, and the table of the vendored key map run against the real vendored
 * resolver and the router side by side. No window, no GPU, no network.
 */
class DesktopPlayerShortcutEffectsTest {
    private val keys = DesktopUxContract.section("player_keys")
    private val codes = keys.getValue("key_codes").jsonObject
    private val playerRows = DesktopUxContract.objects(DesktopUxContract.section("shortcuts").getValue("rows"))
        .filter { DesktopUxContract.string(it, "scope") == "player" }

    private fun range(name: String): IntRange {
        val (first, last) = DesktopUxContract.ints(codes.getValue(name))
        return first..last
    }

    @Test fun keyNamesAreTheContractTableAndNothingElseIsNamed() {
        val named = codes.getValue("named").jsonObject.mapValues { it.value.jsonPrimitive.int }
        val letters = range("letters")
        val digits = range("digits")
        val numpad = range("numpad_digits")
        val functions = range("function_keys")
        for (code in 0..700) {
            val expected: String? = when (code) {
                in letters, in digits -> Char(code).toString()
                in numpad -> "Numpad" + (code - numpad.first)
                in functions -> "F" + (code - functions.first + 1)
                else -> named.entries.firstOrNull { it.value == code }?.key
            }
            assertEquals(expected, desktopPlayerKeyName(code), "key code $code")
        }
        for ((name, code) in named) assertEquals(name, desktopPlayerKeyName(code))
        assertEquals("Menu", desktopPlayerKeyName(525))
        assertEquals("F10", desktopPlayerKeyName(121))
        assertNull(desktopPlayerKeyName(9))
    }

    @Test fun everyKeyOfTheShortcutTablesCanBePressed() {
        val pressable = (0..700).mapNotNull { desktopPlayerKeyName(it) }.toSet()
        for (row in playerRows) {
            val key = DesktopUxContract.string(row, "chord").split("+").last()
            assertTrue(key in pressable, "no key code names $key")
        }
        for (zoomKey in DesktopUxContract.strings(DesktopUxContract.section("shortcuts").getValue("zoom_decoder_keys")))
            assertTrue(zoomKey in pressable, "no key code names the zoom key $zoomKey")
    }

    @Test fun theConstantsAreTheContractNumbers() {
        assertEquals(DesktopUxContract.int(keys, "chapter_back_margin_ms").toLong(), DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS)
        assertEquals(DesktopUxContract.double(keys, "speed_epsilon"), DESKTOP_PLAYER_SPEED_EPSILON)
        assertEquals(DesktopUxContract.int(keys.getValue("volume").jsonObject, "max").toDouble(), DESKTOP_PLAYER_VOLUME_MAX)
        assertEquals(DesktopUxContract.strings(keys.getValue("control_navigation_keys")).toSet(), DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS)
        assertEquals("Space", DESKTOP_PLAYER_SPACE_KEY_NAME)
        for (kind in DesktopPlayerActionKind.entries)
            assertEquals(kind.name in DesktopUxContract.strings(keys.getValue("repeat_actions")), desktopPlayerShortcutRepeats(kind), kind.name)
    }

    @Test fun aChapterJumpGoesToTheNextStartOrToThePreviousOneBeyondTheMargin() {
        val starts = listOf(0L, 60_000L, 120_000L)
        assertEquals(60_000L, desktopPlayerChapterTarget(starts, 30_000L, 1))
        assertEquals(120_000L, desktopPlayerChapterTarget(starts, 60_000L, 1))
        assertNull(desktopPlayerChapterTarget(starts, 120_000L, 1))
        assertEquals(60_000L, desktopPlayerChapterTarget(starts, 121_999L, -1))
        assertEquals(120_000L, desktopPlayerChapterTarget(starts, 123_000L, -1))
        assertEquals(0L, desktopPlayerChapterTarget(starts, 2_001L, -1))
        assertNull(desktopPlayerChapterTarget(starts, 2_000L, -1))
        assertNull(desktopPlayerChapterTarget(starts, 0L, -1))
        val late = listOf(10_000L, 60_000L)
        assertEquals(0L, desktopPlayerChapterTarget(late, 5_000L, -1))
        assertNull(desktopPlayerChapterTarget(late, 1_000L, -1))
        assertNull(desktopPlayerChapterTarget(emptyList(), 50_000L, 1))
        assertNull(desktopPlayerChapterTarget(emptyList(), 50_000L, -1))
    }

    @Test fun aSpeedStepSkipsTheCurrentSpeedAndStopsAtTheEnds() {
        val options = listOf(0.5, 1.0, 1.5, 2.0)
        assertEquals(1.5, desktopPlayerSpeedStep(options, 1.0, 1))
        assertEquals(0.5, desktopPlayerSpeedStep(options, 1.0, -1))
        assertNull(desktopPlayerSpeedStep(options, 2.0, 1))
        assertNull(desktopPlayerSpeedStep(options, 0.5, -1))
        assertEquals(1.5, desktopPlayerSpeedStep(options, 1.25, 1))
        assertEquals(1.0, desktopPlayerSpeedStep(options, 1.25, -1))
        // A speed within the epsilon of an option is that option.
        assertEquals(1.5, desktopPlayerSpeedStep(options, 0.9996, 1))
        assertEquals(0.5, desktopPlayerSpeedStep(options, 1.0004, -1))
        assertNull(desktopPlayerSpeedStep(emptyList(), 1.0, 1))
        assertEquals(0.5, desktopPlayerSpeedStep(listOf(2.0, 0.5, 1.5), 1.0, -1))
    }

    @Test fun theFocusRuleKeepsTheLegacyGateAndFreesTheDesktopLayoutUnlessAnEditorOrAFlyoutHasTheKeys() {
        for (gate in listOf(true, false)) for (editor in listOf(true, false)) for (flyout in listOf(true, false)) {
            assertEquals(gate, desktopPlayerShortcutOwnsFocus(DesktopWindowsUxPlayerLayout.LEGACY, gate, editor, flyout), "LEGACY $gate $editor $flyout")
            assertEquals(!editor && !flyout, desktopPlayerShortcutOwnsFocus(DesktopWindowsUxPlayerLayout.DESKTOP, gate, editor, flyout), "DESKTOP $gate $editor $flyout")
        }
    }

    @Test fun theBindingStartsAsLegacyWithNoEffectAndFollowsWhatTheLeafFillsIn() {
        val binding = DesktopWindowsVideoShortcutBinding()
        assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, binding.layout)
        assertTrue(binding.ownsFocus(true, true))
        assertFalse(binding.ownsFocus(false, false))
        assertFalse(binding.perform(DesktopPlayerAction(DesktopPlayerActionKind.MUTE)))
        binding.legacyLayout = false
        assertEquals(DesktopWindowsUxPlayerLayout.DESKTOP, binding.layout)
        assertTrue(binding.ownsFocus(false, false))
        assertFalse(binding.ownsFocus(true, true))
        binding.flyoutOpen = true
        assertFalse(binding.ownsFocus(true, false))
        val rig = Rig()
        binding.effects = rig.effects
        assertTrue(binding.perform(DesktopPlayerAction(DesktopPlayerActionKind.MUTE)))
        assertEquals(listOf("setMuted(true)"), rig.events)
    }

    private class Rig(
        var position: Double = 100.0,
        var duration: Double = 600.0,
        var volume: Double = 50.0,
        var speed: Double = 1.0,
        var speeds: List<Double> = listOf(0.5, 1.0, 1.5, 2.0),
        var starts: List<Long> = listOf(0L, 60_000L, 120_000L),
        var hasPrevious: Boolean = true,
        var hasNext: Boolean = true,
        var canMiniPlayer: Boolean = true,
        var subtitles: Boolean = true,
        var screenshot: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val controls = DesktopPlayerControlsBinding(
            current = { true },
            command = { block -> block(); true },
            togglePause = { events.add("playPause") },
            setMuted = { value -> events.add("setMuted($value)") },
            setVolume = { value -> events.add("volume($value)") },
            isMuted = { false },
            preferences = PlayerPreferences(),
            preferencesChanged = { },
            navigate = { forward -> events.add(if (forward) "next" else "previous") },
            switchQuality = { },
            seekToSeconds = { seconds -> events.add("seek($seconds)") },
            goBack = { },
            enterPictureInPicture = { events.add("pictureInPicture") },
            toggleFullscreen = { events.add("fullscreen") },
            speed = { value -> events.add("speed($value)") },
        )
        val effects = DesktopPlayerShortcutEffects(
            controls = controls,
            snapshot = { DesktopPlayerShortcutSnapshot(position, duration, volume) },
            seekBy = { seconds -> events.add("seekBy($seconds)") },
            hasPart = { forward -> if (forward) hasNext else hasPrevious },
            canMiniPlayer = { canMiniPlayer },
            togglePanel = { events.add("togglePanel") },
            toggleSubtitles = { if (subtitles) { events.add("toggleSubtitles"); true } else false },
            toggleDanmaku = { events.add("toggleDanmaku") },
            takeScreenshot = { if (screenshot) { events.add("takeScreenshot"); true } else false },
            chapterStartsMs = { starts },
            seekToChapter = { positionMs -> events.add("seekToChapter($positionMs)") },
            speedChoices = { speeds },
            currentSpeed = { speed },
        )

        fun run(kind: DesktopPlayerActionKind, arg: Int = 0): Boolean = effects.perform(DesktopPlayerAction(kind, arg))
    }

    @Test fun everyKindIsActedOnExceptTheOnesWithoutATargetYet() {
        val notPerformed = keys.getValue("not_performed").jsonObject.keys
        assertEquals(6, notPerformed.size)
        for (kind in DesktopPlayerActionKind.entries) {
            val rig = Rig()
            val handled = rig.run(kind, 1)
            if (kind.name in notPerformed) {
                assertFalse(handled, kind.name)
                assertEquals(emptyList<String>(), rig.events, kind.name)
            } else {
                assertTrue(handled, kind.name)
                assertTrue(rig.events.isNotEmpty(), kind.name)
            }
        }
    }

    @Test fun playPauseMuteFullscreenPanelDanmakuAndTheSpeedPresetsRunTheirAdmittedAction() {
        val rig = Rig()
        assertTrue(rig.run(DesktopPlayerActionKind.PLAY_PAUSE))
        assertTrue(rig.run(DesktopPlayerActionKind.MUTE))
        assertTrue(rig.run(DesktopPlayerActionKind.FULLSCREEN_TOGGLE))
        assertTrue(rig.run(DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA))
        assertTrue(rig.run(DesktopPlayerActionKind.DANMAKU_TOGGLE))
        assertTrue(rig.run(DesktopPlayerActionKind.SPEED_SET, 2))
        assertTrue(rig.run(DesktopPlayerActionKind.SPEED_SET, 1))
        assertEquals(
            listOf("playPause", "setMuted(true)", "fullscreen", "togglePanel", "toggleDanmaku", "speed(2.0)", "speed(1.0)"),
            rig.events,
        )
    }

    @Test fun seekingIsRelativeAndTheVolumeIsClampedToTheRange() {
        val rig = Rig(volume = 98.0)
        assertTrue(rig.run(DesktopPlayerActionKind.SEEK, -10))
        assertTrue(rig.run(DesktopPlayerActionKind.SEEK, 5))
        assertTrue(rig.run(DesktopPlayerActionKind.VOLUME, 5))
        rig.volume = 2.0
        assertTrue(rig.run(DesktopPlayerActionKind.VOLUME, -5))
        rig.volume = 50.0
        assertTrue(rig.run(DesktopPlayerActionKind.VOLUME, 5))
        assertEquals(listOf("seekBy(-10.0)", "seekBy(5.0)", "volume(100.0)", "volume(0.0)", "volume(55.0)"), rig.events)
    }

    @Test fun percentStartAndEndSeekWithinTheKnownDuration() {
        val rig = Rig(duration = 600.0)
        assertTrue(rig.run(DesktopPlayerActionKind.SEEK_PERCENT, 30))
        assertTrue(rig.run(DesktopPlayerActionKind.SEEK_PERCENT, 0))
        assertTrue(rig.run(DesktopPlayerActionKind.SEEK_START))
        assertTrue(rig.run(DesktopPlayerActionKind.SEEK_END))
        assertEquals(listOf("seek(180.0)", "seek(0.0)", "seek(0.0)", "seek(600.0)"), rig.events)
        for (unknown in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val none = Rig(duration = unknown)
            assertFalse(none.run(DesktopPlayerActionKind.SEEK_PERCENT, 50), "duration $unknown")
            assertFalse(none.run(DesktopPlayerActionKind.SEEK_END), "duration $unknown")
            assertEquals(emptyList<String>(), none.events)
            assertTrue(none.run(DesktopPlayerActionKind.SEEK_START))
        }
    }

    @Test fun aSpeedStepUsesTheOfferedSpeedsAndIsNotClaimedAtTheEnd() {
        val rig = Rig(speed = 1.0)
        assertTrue(rig.run(DesktopPlayerActionKind.SPEED_STEP, 1))
        assertTrue(rig.run(DesktopPlayerActionKind.SPEED_STEP, -1))
        assertEquals(listOf("speed(1.5)", "speed(0.5)"), rig.events)
        val atTop = Rig(speed = 2.0)
        assertFalse(atTop.run(DesktopPlayerActionKind.SPEED_STEP, 1))
        assertEquals(emptyList<String>(), atTop.events)
    }

    @Test fun partsMiniPlayerSubtitlesAndScreenshotAreClaimedOnlyWhenThePlayerCanActOnThem() {
        val rig = Rig()
        assertTrue(rig.run(DesktopPlayerActionKind.PART, 1))
        assertTrue(rig.run(DesktopPlayerActionKind.PART, -1))
        assertTrue(rig.run(DesktopPlayerActionKind.MINI_PLAYER))
        assertTrue(rig.run(DesktopPlayerActionKind.SUBTITLE_TOGGLE))
        assertTrue(rig.run(DesktopPlayerActionKind.SCREENSHOT))
        assertEquals(listOf("next", "previous", "pictureInPicture", "toggleSubtitles", "takeScreenshot"), rig.events)
        val none = Rig(hasPrevious = false, hasNext = false, canMiniPlayer = false, subtitles = false, screenshot = false)
        assertFalse(none.run(DesktopPlayerActionKind.PART, 1))
        assertFalse(none.run(DesktopPlayerActionKind.PART, -1))
        assertFalse(none.run(DesktopPlayerActionKind.MINI_PLAYER))
        assertFalse(none.run(DesktopPlayerActionKind.SUBTITLE_TOGGLE))
        assertFalse(none.run(DesktopPlayerActionKind.SCREENSHOT))
        assertEquals(emptyList<String>(), none.events)
        val onlyNext = Rig(hasPrevious = false)
        assertFalse(onlyNext.run(DesktopPlayerActionKind.PART, -1))
        assertTrue(onlyNext.run(DesktopPlayerActionKind.PART, 1))
        assertEquals(listOf("next"), onlyNext.events)
    }

    @Test fun aChapterJumpSeeksARealChapterThroughTheChapterLambdaAndTheVideoStartThroughTheSeek() {
        val rig = Rig(position = 100.0)
        assertTrue(rig.run(DesktopPlayerActionKind.CHAPTER, 1))
        assertTrue(rig.run(DesktopPlayerActionKind.CHAPTER, -1))
        assertEquals(listOf("seekToChapter(120000)", "seekToChapter(60000)"), rig.events)
        val late = Rig(position = 5.0, starts = listOf(10_000L, 60_000L))
        assertTrue(late.run(DesktopPlayerActionKind.CHAPTER, -1))
        assertEquals(listOf("seek(0.0)"), late.events)
        val none = Rig(starts = emptyList())
        assertFalse(none.run(DesktopPlayerActionKind.CHAPTER, 1))
        assertFalse(none.run(DesktopPlayerActionKind.CHAPTER, -1))
        val last = Rig(position = 130.0)
        assertFalse(last.run(DesktopPlayerActionKind.CHAPTER, 1))
        assertEquals(emptyList<String>(), last.events)
    }

    // ---- the vendored key map, row by row ----

    private val digitKeys = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    private val numpadKeys = listOf(
        Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
    )

    private fun vendoredKey(name: String): Key = when {
        name == "Space" -> Key.Spacebar
        name == "Left" -> Key.DirectionLeft
        name == "Right" -> Key.DirectionRight
        name == "Up" -> Key.DirectionUp
        name == "Down" -> Key.DirectionDown
        name == "Enter" -> Key.Enter
        name == "NumpadEnter" -> Key.NumPadEnter
        name == "LeftBracket" -> Key.LeftBracket
        name == "RightBracket" -> Key.RightBracket
        name == "K" -> Key.K
        name == "J" -> Key.J
        name == "L" -> Key.L
        name == "F" -> Key.F
        name == "M" -> Key.M
        name == "D" -> Key.D
        name == "S" -> Key.S
        name == "Q" -> Key.Q
        name == "W" -> Key.W
        name == "E" -> Key.E
        name == "R" -> Key.R
        name.startsWith("Numpad") && name.length == 7 -> numpadKeys[name[6].digitToInt()]
        name.length == 1 && name[0].isDigit() -> digitKeys[name[0].digitToInt()]
        else -> error("no vendored key for $name")
    }

    private fun describe(action: PlayerKeyAction?): Pair<String, Int?>? = when (action) {
        null -> null
        is PlayerKeyAction.SeekRelative -> "SeekRelative" to (action.deltaMs / 1000).toInt()
        is PlayerKeyAction.SeekPercent -> "SeekPercent" to Math.round(action.fraction * 100f)
        is PlayerKeyAction.SetSpeed -> "SetSpeed" to action.speed.toInt()
        else -> (action::class.simpleName ?: "") to null
    }

    private fun routerEvent(chord: String): DesktopShellKeyData {
        val parts = chord.split("+")
        val modifiers = parts.dropLast(1).toSet()
        val key = parts.last().let { if (it == "NumpadEnter") "Enter" else it }
        return DesktopShellKeyData(
            key = key, ctrl = "Ctrl" in modifiers, alt = "Alt" in modifiers, shift = "Shift" in modifiers, meta = "Meta" in modifiers,
        )
    }

    private fun routerResolves(chord: String): Pair<String, Int>? =
        resolveDesktopPlayerKey(routerEvent(chord), DesktopPlayerKeyContext())?.let { it.kind.name to it.arg }

    private fun JsonObject.routerPair(): Pair<String, Int>? =
        (this["router"] as? JsonObject)?.let { DesktopUxContract.string(it, "action") to DesktopUxContract.int(it, "arg") }

    @Test fun everyVendoredBindingIsKeptOrListedAndTheTableMatchesTheRealVendoredResolverAndTheRouter() {
        val vendored = keys.getValue("vendored").jsonObject
        val bindings = DesktopUxContract.objects(vendored.getValue("bindings"))
        assertEquals(46, bindings.size)
        val statuses = bindings.groupingBy { DesktopUxContract.string(it, "status") }.eachCount()
        assertEquals(mapOf("kept" to 39, "changed" to 1, "not-taken" to 2, "dropped" to 4), statuses)
        for (row in bindings) {
            val chord = DesktopUxContract.string(row, "chord")
            val parts = chord.split("+")
            val shift = "Shift" in parts.dropLast(1)
            val event = KeyEvent(vendoredKey(parts.last()), KeyEventType.KeyDown, isShiftPressed = shift)
            val vendoredSide = row.getValue("vendored").jsonObject
            val expected = DesktopUxContract.string(vendoredSide, "action") to vendoredSide["arg"]?.jsonPrimitive?.int
            assertEquals(expected, describe(resolvePlayerKeyAction(event)), "vendored $chord")
            val router = row.routerPair()
            assertEquals(router, routerResolves(chord), "router $chord")
            when (DesktopUxContract.string(row, "status")) {
                "kept" -> assertTrue(router != null, chord)
                "changed" -> assertTrue(router != null && "reason" in row, chord)
                "not-taken", "dropped" -> assertTrue(router == null && "reason" in row, chord)
                else -> error("unknown status for $chord")
            }
        }
    }

    @Test fun theAdditionsAreRowsOfTheRouterTableThatTheVendoredMapDoesNotBind() {
        val additions = DesktopUxContract.objects(keys.getValue("vendored").jsonObject.getValue("additions"))
        val bound = DesktopUxContract.objects(keys.getValue("vendored").jsonObject.getValue("bindings"))
            .map { DesktopUxContract.string(it, "chord") }
        for (item in additions) {
            val chord = DesktopUxContract.string(item, "chord")
            assertFalse(chord in bound, chord)
            val expected = if (DesktopUxContract.string(item, "status") == "active")
                DesktopUxContract.string(item, "action") to DesktopUxContract.int(item, "arg") else null
            assertEquals(expected, routerResolves(chord), chord)
        }
        assertEquals(22, additions.size)
    }
}
