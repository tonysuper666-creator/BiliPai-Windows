"""Model and source proofs of the restyled LEGACY control bar (slice V2b, spec 4.5, 4.13, 5.1 and 7). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_control_bar.py

What is proved. The pure rules of ui/DesktopWindowsVideoControlBarModel.kt (the two width breakpoints, the shortcut hint of each
button, the height of every menu window, the quality pill label) equal their Python mirrors and an independent oracle over
sweeps, and the numbers equal the "control_bar" section of the contract; every hint names an active row of the contract's player
table. The composable keeps its 49-parameter signature, its seek function and its glass surface byte for byte (whitespace aside),
and its behaviour: every button, pill and menu row enables on the condition and calls the operation it did before the restyle.
Every icon button is the kit icon button inside the kit tooltip (above, after the player delay), the two pills are the kit pill
without a tooltip (the Windows keyboard fixtures know the tooltip layer of the icon buttons only), the menu rows are kit menu rows
and the volume control is the kit slider row inside one helper that gives it the 40 dp the menu heights count: no Material text
button, drop-down item, divider or slider is left, and the file spells no Chinese (the strings object does). The accessible names
are the ones the Windows fixtures click by name, equal to the pre-restyle literals and to DesktopPlayerStrings. Every menu height
carries a 4 dp rounding slack (the paddings round to whole pixels), proved against a sweep of densities.

What is not proved. That the Kotlin compiles or runs, how the bar looks, and anything about the menu windows beyond their source
(they are native dialogs): DesktopWindowsVideoControlBarModelTest and DesktopWindowsVideoControlBarUiTest run on the Linux bot,
and the Windows fixtures are Windows runs. The tooltips of the player-table keys that the LEGACY layout does not handle yet (the
mini player, the details panel) are claimed by the table, not by a working key: slice V6 wires them, and ships in the same
release as V2b (spec 9.3, release R1). That the slider row renders at 40 dp is argued from the library's class files (a 44 dp
handle, a 48 dp interactive minimum, a row height set outright), not measured.
"""
import itertools
import math
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_control_bar_model as cb  # noqa: E402
import ux_model as model  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
SECTION = cb.section(CONTRACT)
BAR = cb.read(cb.BAR_KT)
BAR_CODE = source.mask_kotlin(BAR)
BAR_SQUASHED = cb.squash(BAR)
MODEL = cb.read(cb.MODEL_KT)
MODEL_CODE = source.mask_kotlin(MODEL)
STRINGS = cb.read(cb.STRINGS_KT)
PLAYER_STRINGS = cb.read(cb.PLAYER_STRINGS_KT)
LEAF = cb.read(cb.LEAF_KT)
KIT = cb.kit_constants()
CONSTANTS = cb.constants(MODEL)
STRING_CONSTANTS = cb.string_constants(STRINGS)
S = "DesktopWindowsVideoControlBarStrings."

# The parameter list of the bar before the restyle and after it: name, type, default. The signature never changes (spec 4.13).
EXPECTED_PARAMETERS = [
    ("state", "PlayerState", None), ("sourceVersion", "Long", None), ("enabled", "Boolean", None), ("fullscreen", "Boolean", None),
    ("detailsOpen", "Boolean", None), ("hasPrevious", "Boolean", None), ("hasNext", "Boolean", None),
    ("canPictureInPicture", "Boolean", None),
    ("onInteractionHoldChanged", "(DesktopWindowsFullscreenChromeInteraction) -> Unit", None),
    ("onChromePointerInput", "() -> Unit", None), ("speedOptions", "List<Float>", None),
    ("qualities", "List<Pair<Int, String>>", None), ("selectedQuality", "Int?", None),
    ("canOpenCollection", "Boolean", None), ("canOpenPlaybackQueue", "Boolean", None),
    ("onOpenCollection", "() -> Unit", None), ("onOpenPlaybackQueue", "() -> Unit", None),
    ("canOpenInteraction", "Boolean", None), ("onSendDanmaku", "() -> Unit", None), ("onShareVideo", "() -> Unit", None),
    ("canDownloadAudio", "Boolean", None), ("canSaveCover", "Boolean", None), ("onDownloadAudio", "() -> Unit", None),
    ("onSaveCover", "() -> Unit", None), ("canScreenshot", "Boolean", None), ("screenshotBusy", "Boolean", None),
    ("onScreenshot", "() -> Unit", None), ("chapters", "DesktopOriginalVideoChapterResult?", None),
    ("chaptersSource", "DesktopOriginalVideoAcceptedPublication?", None),
    ("onChapterSeek", "(DesktopOriginalVideoChapterResult, DesktopOriginalVideoAcceptedPublication, Long) -> Unit", None),
    ("onPlayPause", "() -> Unit", None), ("onPrevious", "() -> Unit", None), ("onNext", "() -> Unit", None),
    ("onMute", "() -> Unit", None), ("onVolume", "(Double) -> Unit", None), ("onSpeed", "(Double) -> Unit", None),
    ("onQuality", "(Int) -> Unit", None), ("onSeek", "(Double) -> Unit", None), ("onPictureInPicture", "() -> Unit", None),
    ("onFullscreen", "() -> Unit", None), ("onDetails", "() -> Unit", None), ("onOpenIntroduction", "() -> Unit", None),
    ("sponsorSkip", "@Composable () -> Unit", None), ("enhancement", "@Composable () -> Unit", None),
    ("sponsorMoreActions", "@Composable (() -> Unit) -> Unit", "{}"), ("pbpRidgeSamples", "List<PbpRidgeSample>", "emptyList()"),
    ("seekPreviewSource", "DesktopOriginalVideoAcceptedPublication?", "null"), ("seekPreviewEnabled", "Boolean", "false"),
    ("onSeekPreviewChanged",
     "(DesktopOriginalVideoAcceptedPublication, DesktopWindowsVideoSeekPreview?) -> Unit", "{ _, _ -> }"),
]

# sha256 of the whitespace-insensitive text of two functions that the restyle must not change. The seek function differs from the
# pre-restyle one by one thing: its accessible name is read from the strings object (checked when the pin was made).
SEEK_DIGEST = "4c7ef8e36bb4eea34de9a242ac2303870ffac85581a6e64c27b2bb4136bce0b3"
SURFACE_DIGEST = "72882b41d9bc36a0a342697a91a562d971724cacd8299ab59453a3baa7d901ca"

# The plain Chinese literals of the bar before the restyle, and the three that were templates. Each lives in the strings object now.
PRE_RESTYLE_LITERALS = {
    "PLAY": "播放", "PAUSE": "暂停", "PREVIOUS": "上一集", "NEXT": "下一集", "VOLUME_AND_MUTE": "音量与静音", "SPEED": "倍速",
    "QUALITY": "画质", "CHAPTERS": "视频章节", "DETAILS": "详情", "MINI_PLAYER": "浮窗", "FULLSCREEN": "全屏",
    "EXIT_FULLSCREEN": "退出全屏", "MORE_ACTIONS": "更多播放操作", "SEEK_BAR": "播放进度", "MUTE": "静音", "UNMUTE": "取消静音",
    "VOLUME": "音量", "COLLECTION": "视频合集", "PLAYBACK_QUEUE": "播放队列", "SEND_DANMAKU": "发送弹幕", "SHARE_VIDEO": "分享视频",
    "DOWNLOAD_AUDIO": "仅下载音频", "SAVE_COVER": "保存封面", "SCREENSHOT": "视频截图（S）", "SCREENSHOT_BUSY": "截图中…",
    "INTRODUCTION_AND_SETTINGS": "简介、分P与播放设置",
}

BUTTON_CALLS = [
    "DesktopControlBarButton(label = if (showPlay) %sPLAY else %sPAUSE, onClick = onPlayPause, enabled = enabled && state.ready, "
    "hint = hints[DesktopControlBarHintTarget.PLAY_PAUSE])" % (S, S),
    "DesktopControlBarButton(label = %sPREVIOUS, onClick = onPrevious, enabled = enabled && hasPrevious, "
    "hint = hints[DesktopControlBarHintTarget.PREVIOUS])" % S,
    "DesktopControlBarButton(label = %sNEXT, onClick = onNext, enabled = enabled && hasNext, "
    "hint = hints[DesktopControlBarHintTarget.NEXT])" % S,
    "DesktopControlBarButton(label = %sVOLUME_AND_MUTE, onClick = { volumeMenu = true }, enabled = enabled, "
    "hint = hints[DesktopControlBarHintTarget.MUTE])" % S,
    "DesktopControlBarButton(label = %sCHAPTERS, onClick = { chapterMenu = true }, enabled = enabled)" % S,
    "DesktopControlBarButton(label = %sDETAILS, onClick = onDetails, active = detailsOpen, "
    "hint = hints[DesktopControlBarHintTarget.DETAILS])" % S,
    "DesktopControlBarButton(label = %sMINI_PLAYER, onClick = onPictureInPicture, enabled = enabled && canPictureInPicture, "
    "hint = hints[DesktopControlBarHintTarget.MINI_PLAYER])" % S,
    "DesktopControlBarButton(label = if (fullscreen) %sEXIT_FULLSCREEN else %sFULLSCREEN, onClick = onFullscreen, "
    "hint = hints[DesktopControlBarHintTarget.FULLSCREEN])" % (S, S),
    "DesktopControlBarButton(label = %sMORE_ACTIONS, onClick = { more = true })" % S,
]
PILL_CALLS = [
    "DesktopControlBarPill(text = %sspeedValue(playbackSpeedLabel(state.speed)), label = %sSPEED, onClick = { speedMenu = true }, "
    "enabled = enabled)" % (S, S),
    "DesktopControlBarPill(text = desktopControlBarQualityLabel(qualities, selectedQuality), label = %sQUALITY, "
    "onClick = { qualityMenu = true }, enabled = enabled && qualities.isNotEmpty())" % S,
]
CHAPTER_ROW = ("DesktopControlBarMenuRow(%schapterRow(FormatUtils.formatDuration(segment.fromMs), segment.content), enabled = enabled, "
               "onClick = { onChapterSeek(chapters, chaptersSource, segment.fromMs); chapterMenu = false })" % S)
MENU_ROW_CALLS = [
    "DesktopControlBarMenuRow(if (state.muted) %sUNMUTE else %sMUTE, onClick = onMute)" % (S, S),
    "DesktopControlBarMenuRow(%sspeedValue(speed.toString()), onClick = { onSpeed(speed.toDouble()); speedMenu = false }, "
    "marksCurrent = true, current = speed == state.speed.toFloat())" % S,
    "DesktopControlBarMenuRow(label, onClick = { onQuality(id); qualityMenu = false }, marksCurrent = true, current = id == selectedQuality)",
    CHAPTER_ROW,
    "DesktopControlBarMenuRow(%sPREVIOUS, enabled = enabled && hasPrevious, onClick = { onPrevious(); more = false })" % S,
    "DesktopControlBarMenuRow(%sNEXT, enabled = enabled && hasNext, onClick = { onNext(); more = false })" % S,
    "DesktopControlBarMenuRow(if (state.muted) %sUNMUTE else %sMUTE, enabled = enabled, onClick = onMute)" % (S, S),
    "DesktopControlBarMenuRow(%sspeedRow(speed.toString()), enabled = enabled, onClick = { onSpeed(speed.toDouble()); more = false })" % S,
    "DesktopControlBarMenuRow(%squalityRow(label), enabled = enabled, onClick = { onQuality(id); more = false })" % S,
    "DesktopControlBarMenuRow(%sMINI_PLAYER, enabled = enabled && canPictureInPicture, onClick = { onPictureInPicture(); more = false })" % S,
    "DesktopControlBarMenuRow(%sCHAPTERS, enabled = enabled, onClick = { more = false; chapterMenu = true })" % S,
    "DesktopControlBarMenuRow(%sCOLLECTION, enabled = enabled, onClick = { more = false; onOpenCollection() })" % S,
    "DesktopControlBarMenuRow(%sPLAYBACK_QUEUE, enabled = enabled, onClick = { more = false; onOpenPlaybackQueue() })" % S,
    "DesktopControlBarMenuRow(%sSEND_DANMAKU, enabled = enabled && canOpenInteraction, onClick = { more = false; onSendDanmaku() })" % S,
    "DesktopControlBarMenuRow(%sSHARE_VIDEO, enabled = enabled && canOpenInteraction, onClick = { more = false; onShareVideo() })" % S,
    "DesktopControlBarMenuRow(%sDOWNLOAD_AUDIO, enabled = enabled && canDownloadAudio, onClick = { more = false; onDownloadAudio() })" % S,
    "DesktopControlBarMenuRow(%sSAVE_COVER, enabled = enabled && canSaveCover, onClick = { more = false; onSaveCover() })" % S,
    "DesktopControlBarMenuRow(if (screenshotBusy) %sSCREENSHOT_BUSY else %sSCREENSHOT, "
    "enabled = enabled && canScreenshot && !screenshotBusy, onClick = { more = false; onScreenshot() })" % (S, S),
    "DesktopControlBarMenuRow(%sINTRODUCTION_AND_SETTINGS, onClick = { onOpenIntroduction(); more = false })" % S,
    CHAPTER_ROW,
]
MENU_HEIGHT_CALLS = [
    "preferredHeight = desktopControlBarMenuHeightDp(rows = 1, sliderRows = 1).dp)",
    "preferredHeight = desktopControlBarMenuHeightDp(rows = menuSpeeds.size).dp)",
    "preferredHeight = desktopControlBarMenuHeightDp(rows = qualities.size).dp)",
    "preferredHeight = desktopControlBarMenuHeightDp(rows = segments.size).dp)",
    "preferredHeight = desktopControlBarMoreMenuHeightDp(expanded, menuSpeeds.size, qualities.size, chapterRow = chapterRowInMore, "
    "collectionRow = canOpenCollection, queueRow = canOpenPlaybackQueue).dp)",
    "preferredHeight = desktopControlBarMenuHeightDp(rows = segments.size).dp)",
]
VOLUME_SLIDER = (
    "DesktopSliderRow(title = %sVOLUME, value = state.volume.toFloat().coerceIn(0f, 100f), onValueChange = { onVolume(it.toDouble()) }, "
    "modifier = Modifier.padding(start = DESKTOP_CONTROL_BAR_MENU_START_INSET_DP.dp).height(DESKTOP_KIT_ROW_LIST_DP.dp), "
    "enabled = enabled && state.ready, valueRange = 0f..100f, valueLabel = %svolumePercent(state.volume.toInt()))" % (S, S))
VOLUME_ROW_CALL = "DesktopControlBarVolumeRow(state, enabled, onVolume)"


def calls(text, name):
    """Squashed text of every call of `name(` (not its declaration), in source order."""
    masked = source.mask_kotlin(text)
    found = []
    for match in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\(", masked):
        if masked[max(0, match.start() - 4):match.start()] == "fun ":
            continue
        found.append(cb.squash(text[match.start():cb.matching(masked, match.end() - 1) + 1]))
    return found


def call_arguments(text, name):
    """[[(argument name or None, value)]] of every call of `name(`, in source order."""
    masked = source.mask_kotlin(text)
    found = []
    for match in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\(", masked):
        if masked[max(0, match.start() - 4):match.start()] == "fun ":
            continue
        close = cb.matching(masked, match.end() - 1)
        arguments = []
        for raw, _masked in cb.split_top_level(text[match.end():close], masked[match.end():close], angles=False):
            named = re.match(r"(\w+) = (.*)$", raw, re.S)
            arguments.append((named.group(1), cb.squash(named.group(2))) if named else (None, cb.squash(raw)))
        found.append(arguments)
    return found


def imports(text):
    return re.findall(r"(?m)^import ([\w.*]+(?: as \w+)?)$", text)


# --------------------------------------------------------------------------------------
# The contract
# --------------------------------------------------------------------------------------

class ContractTest(unittest.TestCase):
    def test_the_section_has_its_five_parts_and_is_ascii_like_the_rest_of_the_contract(self):
        self.assertEqual(["about", "layout", "menu", "hints", "golden"], list(SECTION))
        self.assertEqual(["breakpoints", "menu_height", "more_menu_height"], sorted(SECTION["golden"]))
        self.assertIn("V2b", SECTION["about"])
        self.assertTrue(cb.model.CONTRACT_PATH.read_bytes().isascii())

    def test_every_constant_of_the_kotlin_block_is_a_contract_number_and_the_reverse(self):
        self.assertEqual(cb.constant_map(CONTRACT), CONSTANTS)

    def test_the_layout_and_menu_numbers_follow_the_kit_tokens_the_spec_and_the_menu_host(self):
        tokens, dock = CONTRACT["tokens"], CONTRACT["player"]["overlay"]["dock"]
        self.assertEqual(tokens["row_height"]["list"], SECTION["layout"]["row_min_height"])
        self.assertEqual(dock["button_gap"], SECTION["layout"]["button_gap"])
        self.assertEqual(KIT["DESKTOP_KIT_CONTROL_HEIGHT_DP"], tokens["control_height"])
        self.assertEqual(KIT["DESKTOP_KIT_ROW_LIST_DP"], tokens["row_height"]["list"])
        host = cb.squash(cb.read(cb.PLAYER_MENU_KT))
        self.assertIn(".padding(top = 4.dp, bottom = 4.dp, end = 12.dp), content = content)", host)
        self.assertEqual(SECTION["menu"]["host_padding"], 4 + 4, "the host pads 4 dp above and below")
        self.assertEqual(SECTION["menu"]["start_inset"], 12, "the rows start where the host's 12 dp end padding mirrors them")
        self.assertEqual(SECTION["menu"]["divider_block"], 1 + 2 * 4, "a one dp line with 4 dp above and below")
        self.assertEqual(4, SECTION["menu"]["rounding_slack"], "the slack added on top of the exact content height")

    def test_the_rounding_slack_is_needed_at_the_measured_density_and_covers_the_paddings_at_every_density(self):
        # Compose rounds every padding to whole pixels (Dp.roundToPx: half up). The measured density of the user's display is Windows
        # 150% times the 125% app zoom. The paddings a menu holds: the host's 4 dp above and 4 dp below, and 4 dp above and below
        # each divider block (whose line is one pixel). The compact more menu holds three divider blocks, the most of any menu.
        menu = SECTION["menu"]
        slack, dividers = menu["rounding_slack"], menu["more_compact_dividers"]

        def px(dp, density):
            return math.floor(dp * density + 0.5)

        def padding_cost(density):
            """(pixels the paddings take, pixels the dp budget of the paddings is worth) of the compact more menu."""
            taken = 2 * px(4, density) + dividers * (2 * px(4, density) + 1)
            budget = (menu["host_padding"] + dividers * menu["divider_block"]) * density
            return taken, budget

        measured = 1.5 * 1.25
        self.assertEqual(1.875, measured)
        self.assertEqual(8, px(4, measured), "4 dp is 7.5 px and rounds up")
        self.assertEqual((16, 15.0), (2 * px(4, measured), menu["host_padding"] * measured),
                         "the host paddings alone cost 16 px against a budget of 15: a menu with no slack overflows by a pixel")
        taken, budget = padding_cost(measured)
        self.assertGreater(taken, budget, "with its dividers too the compact more menu is over its dp budget at the measured density")
        for step in range(64, 193):
            density = step / 64
            taken, budget = padding_cost(density)
            self.assertLessEqual(taken - budget, slack * density, density)

    def test_the_legacy_numbers_are_the_ones_the_bar_had_before_the_restyle(self):
        # maxWidth >= 940.dp, maxWidth >= 520.dp, coerceIn(64, 420) and 564.dp of the pre-V2b bar.
        layout, menu = SECTION["layout"], SECTION["menu"]
        self.assertEqual((940, 520), (layout["expanded_min_width"], layout["status_min_width"]))
        self.assertEqual((64, 420, 564), (menu["min_height"], menu["max_height"], menu["compact_more_max_height"]))

    def test_every_hint_names_an_active_row_of_the_player_table_with_the_same_action(self):
        rows = cb.contract_player_rows(CONTRACT)
        for hint in SECTION["hints"]:
            self.assertIn(hint["chord"], rows, hint["target"])
            action, arg, active = rows[hint["chord"]]
            self.assertEqual((hint["action"], hint.get("arg", 0), True), (action, arg, active), hint["target"])
        self.assertEqual(len(SECTION["hints"]), len({hint["target"] for hint in SECTION["hints"]}), "one hint per target")

    def test_every_hint_has_exactly_one_label_rule(self):
        for hint in SECTION["hints"]:
            self.assertEqual(1, len([key for key in ("label", "label_from") if key in hint]), hint["target"])
            if "label_from" in hint:
                self.assertEqual("KEY_SPACE", hint["label_from"])
                self.assertEqual("Space", hint["chord"])
            else:
                self.assertEqual(cb.key_label_kt(hint["chord"], None), hint["label"], hint["target"])

    def test_the_kotlin_hint_table_is_the_contract_hint_table_in_order(self):
        expected = [(hint["target"], hint["chord"], hint["action"], hint.get("arg", 0)) for hint in SECTION["hints"]]
        self.assertEqual(expected, cb.hint_table(MODEL))
        self.assertEqual([hint["target"] for hint in SECTION["hints"]], cb.hint_targets(MODEL))

    def test_the_kotlin_player_table_agrees_with_the_contract_on_every_hinted_chord(self):
        kotlin, contract = cb.kotlin_player_rows(cb.read(cb.PLAYER_SHORTCUTS_KT)), cb.contract_player_rows(CONTRACT)
        for hint in SECTION["hints"]:
            self.assertEqual(contract[hint["chord"]], kotlin[hint["chord"]], hint["chord"])

    def test_the_golden_ids_are_unique(self):
        for name in ("menu_height", "more_menu_height"):
            ids = [row["id"] for row in SECTION["golden"][name]]
            self.assertEqual(len(ids), len(set(ids)), name)


# --------------------------------------------------------------------------------------
# The pure model: mirrors, oracles, sweeps
# --------------------------------------------------------------------------------------

class MirrorSourceTest(unittest.TestCase):
    """The Kotlin lines that the mirrors transliterate, so editing one side without the other fails."""
    SQUASHED = cb.squash(MODEL_CODE)
    RAW = cb.squash(MODEL)

    def test_the_breakpoints(self):
        self.assertIn("internal fun desktopControlBarExpanded(widthDp: Float): Boolean = widthDp >= DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP", self.RAW)
        self.assertIn("internal fun desktopControlBarShowsStatus(widthDp: Float): Boolean = widthDp >= DESKTOP_CONTROL_BAR_STATUS_MIN_DP", self.RAW)

    def test_the_key_label(self):
        body = cb.function_block(MODEL, "desktopControlBarKeyLabel")
        squashed = cb.squash(body)
        for needle in ('"Space" -> DesktopWindowsVideoControlBarStrings.KEY_SPACE', '"LeftBracket" -> "["', '"RightBracket" -> "]"', "else -> chord"):
            self.assertIn(needle, squashed, needle)

    def test_the_shortcut_hint(self):
        body = cb.squash(cb.function_block(MODEL, "desktopControlBarShortcutHint"))
        for needle in ("val hint = hints.firstOrNull { it.target == target } ?: return null",
                       "it.chord == hint.chord && it.action == hint.action && it.status == DesktopPlayerShortcutStatus.ACTIVE",
                       "return if (bound) desktopControlBarKeyLabel(hint.chord) else null"):
            self.assertIn(needle, body, needle)
        self.assertIn("shortcuts: List<DesktopPlayerShortcut> = DESKTOP_PLAYER_SHORTCUTS,", cb.squash(MODEL))
        self.assertIn("hints: List<DesktopControlBarHint> = DESKTOP_CONTROL_BAR_HINTS,", cb.squash(MODEL))

    def test_the_menu_heights(self):
        content = cb.squash(cb.function_block(MODEL, "desktopControlBarMenuContentDp"))
        for needle in ("DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP +", "DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP +",
                       "rows.coerceAtLeast(0) * DESKTOP_KIT_CONTROL_HEIGHT_DP +",
                       "sliderRows.coerceAtLeast(0) * DESKTOP_KIT_ROW_LIST_DP +", "dividers.coerceAtLeast(0) * DESKTOP_CONTROL_BAR_MENU_DIVIDER_BLOCK_DP"):
            self.assertIn(needle, content, needle)
        self.assertIn("): Int = desktopControlBarMenuContentDp(rows, dividers, sliderRows).coerceAtLeast(DESKTOP_CONTROL_BAR_MENU_MIN_DP)"
                      ".coerceAtMost(maxDp)", cb.squash(MODEL))
        self.assertIn("maxDp: Int = DESKTOP_CONTROL_BAR_MENU_MAX_DP,", cb.squash(MODEL))

    def test_the_more_menu_height(self):
        body = cb.squash(cb.function_block(MODEL, "desktopControlBarMoreMenuHeightDp"))
        for needle in ("val optional = (if (chapterRow) 1 else 0) + (if (collectionRow) 1 else 0) + (if (queueRow) 1 else 0)",
                       "val tail = DESKTOP_CONTROL_BAR_MORE_BASE_ROWS + optional", "desktopControlBarMenuHeightDp(tail)",
                       "DESKTOP_CONTROL_BAR_MORE_COMPACT_FIXED_ROWS + speedRows.coerceAtLeast(0) + qualityRows.coerceAtLeast(0) + tail,",
                       "dividers = DESKTOP_CONTROL_BAR_MORE_COMPACT_DIVIDERS,", "sliderRows = DESKTOP_CONTROL_BAR_MORE_COMPACT_SLIDER_ROWS,",
                       "maxDp = DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP,"):
            self.assertIn(needle, body, needle)

    def test_the_quality_label(self):
        self.assertIn("DesktopPlayerStrings.qualityPill(qualities.firstOrNull { it.first == selected }?.second, switching = false)",
                      cb.squash(MODEL))


class MirrorTest(unittest.TestCase):
    def test_every_breakpoint_row_gets_its_layout_from_the_mirror_and_the_oracle(self):
        rows = SECTION["golden"]["breakpoints"]
        self.assertGreaterEqual(len(rows), 6)
        for row in rows:
            self.assertEqual(row["expanded"], cb.expanded_kt(CONSTANTS, row["width"]), row)
            self.assertEqual(row["status"], cb.shows_status_kt(CONSTANTS, row["width"]), row)
            self.assertEqual(row["expanded"], row["width"] >= SECTION["layout"]["expanded_min_width"], row)
            self.assertEqual(row["status"], row["width"] >= SECTION["layout"]["status_min_width"], row)

    def test_the_breakpoints_over_a_sweep_of_widths(self):
        for tenths in range(0, 30001, 5):
            width = tenths / 10
            self.assertEqual(width >= 940, cb.expanded_kt(CONSTANTS, width), width)
            self.assertEqual(width >= 520, cb.shows_status_kt(CONSTANTS, width), width)

    def test_every_menu_height_row_matches_the_mirror_and_the_oracle(self):
        for row in SECTION["golden"]["menu_height"]:
            args = (row["rows"], row["dividers"], row["slider_rows"])
            self.assertEqual(row["expect"], cb.menu_height_dp_kt(CONSTANTS, KIT, *args), row["id"])
            self.assertEqual(row["expect"], cb.oracle_menu_height(CONTRACT, *args), row["id"])

    def test_every_more_menu_row_matches_the_mirror_and_the_oracle(self):
        for row in SECTION["golden"]["more_menu_height"]:
            args = (row["expanded"], row["speed_rows"], row["quality_rows"], row["chapter_row"], row["collection_row"], row["queue_row"])
            self.assertEqual(row["expect"], cb.more_menu_height_dp_kt(CONSTANTS, KIT, *args), row["id"])
            self.assertEqual(row["expect"], cb.oracle_more_menu_height(CONTRACT, *args), row["id"])

    def test_the_menu_height_over_a_sweep_agrees_with_the_oracle_and_stays_between_the_floor_and_the_limit(self):
        for rows, dividers, sliders, limit in itertools.product(range(-3, 61), range(-1, 5), range(-1, 4), (None, 564, 420, 100, 40)):
            expected = cb.oracle_menu_height(CONTRACT, rows, dividers, sliders, limit)
            actual = cb.menu_height_dp_kt(CONSTANTS, KIT, rows, dividers, sliders, limit)
            self.assertEqual(expected, actual, (rows, dividers, sliders, limit))
            cap = 420 if limit is None else limit
            self.assertTrue(min(64, cap) <= actual <= cap, (rows, dividers, sliders, limit))

    def test_the_menu_height_grows_with_every_count_and_never_shrinks(self):
        for dividers, sliders, limit in itertools.product(range(0, 4), range(0, 3), (None, 564)):
            previous = 0
            for rows in range(0, 61):
                height = cb.menu_height_dp_kt(CONSTANTS, KIT, rows, dividers, sliders, limit)
                self.assertGreaterEqual(height, previous)
                previous = height

    def test_the_more_menu_over_a_sweep_agrees_with_the_oracle(self):
        for expanded, speeds, qualities, chapter, collection, queue in itertools.product(
                (True, False), range(0, 13), range(0, 13), (False, True), (False, True), (False, True)):
            args = (expanded, speeds, qualities, chapter, collection, queue)
            self.assertEqual(cb.oracle_more_menu_height(CONTRACT, *args), cb.more_menu_height_dp_kt(CONSTANTS, KIT, *args), args)

    def test_the_wide_more_menu_ignores_the_lists_and_the_compact_one_may_grow_to_its_own_limit(self):
        wide = {cb.more_menu_height_dp_kt(CONSTANTS, KIT, True, speeds, qualities, False, False, False)
                for speeds, qualities in itertools.product(range(0, 20), range(0, 20))}
        self.assertEqual({204}, wide)
        self.assertEqual(564, cb.more_menu_height_dp_kt(CONSTANTS, KIT, False, 30, 30, True, True, True))
        self.assertLessEqual(cb.more_menu_height_dp_kt(CONSTANTS, KIT, True, 0, 0, True, True, True), 420)

    def test_the_shipped_hints_equal_the_oracle_for_every_target(self):
        kotlin_rows = cb.kotlin_player_rows(cb.read(cb.PLAYER_SHORTCUTS_KT))
        shortcuts = [(chord, (kind, arg), active) for chord, (kind, arg, active) in kotlin_rows.items()]
        hints = [(target, chord, (kind, arg)) for target, chord, kind, arg in cb.hint_table(MODEL)]
        key_space = STRING_CONSTANTS["KEY_SPACE"]
        for target in cb.hint_targets(MODEL):
            expected = cb.oracle_hint(CONTRACT, target, key_space)
            self.assertIsNotNone(expected, target)
            self.assertEqual(expected, cb.shortcut_hint_kt(target, shortcuts, hints, key_space), target)

    def test_the_hint_labels_of_the_spec_tooltips(self):
        labels = {hint["target"]: cb.oracle_hint(CONTRACT, hint["target"], "KEYSPACE") for hint in SECTION["hints"]}
        self.assertEqual({"PLAY_PAUSE": "KEYSPACE", "PREVIOUS": "[", "NEXT": "]", "MUTE": "M", "MINI_PLAYER": "P", "DETAILS": "Ctrl+B",
                          "FULLSCREEN": "F"}, labels)

    def test_a_hint_disappears_when_its_chord_is_not_an_active_row_with_the_same_action(self):
        hints = [("MUTE", "M", ("MUTE", 0))]
        active = [("M", ("MUTE", 0), True)]
        self.assertEqual("M", cb.shortcut_hint_kt("MUTE", active, hints, "K"))
        self.assertIsNone(cb.shortcut_hint_kt("MUTE", [], hints, "K"))
        self.assertIsNone(cb.shortcut_hint_kt("MUTE", [("M", ("MUTE", 0), False)], hints, "K"), "not claimed")
        self.assertIsNone(cb.shortcut_hint_kt("MUTE", [("M", ("PLAY_PAUSE", 0), True)], hints, "K"), "rebound")
        self.assertIsNone(cb.shortcut_hint_kt("MUTE", [("N", ("MUTE", 0), True)], hints, "K"), "another chord")
        self.assertIsNone(cb.shortcut_hint_kt("MUTE", active, [], "K"), "no hint for the target")
        self.assertIsNone(cb.shortcut_hint_kt("FULLSCREEN", active, hints, "K"), "another target")

    def test_the_quality_label_is_the_first_match_or_the_plain_word(self):
        qualities = [(116, "1080P60"), (80, "1080P"), (80, "second"), (64, "720P")]
        self.assertEqual("1080P", cb.quality_label_kt(qualities, 80, "Q"))
        self.assertEqual("Q", cb.quality_label_kt(qualities, 32, "Q"))
        self.assertEqual("Q", cb.quality_label_kt(qualities, None, "Q"))
        self.assertEqual("Q", cb.quality_label_kt([], 80, "Q"))
        self.assertEqual(STRING_CONSTANTS["QUALITY"], re.search(r'QUALITY_PILL_FALLBACK = "([^"]*)"', PLAYER_STRINGS).group(1))


class ModelPurityTest(unittest.TestCase):
    def test_the_model_has_no_compose_awt_or_windows_dependency_and_no_failing_construct(self):
        for banned in ("androidx", "java.awt", "javax.swing", "KeyEvent", "Toolkit", "Composable", "remember(", "mutableStateOf",
                       "KeyFallback", "MpvPlayer", "SwingPanel", "LocalDensity", "native.", "assembly.", "System."):
            self.assertNotIn(banned, MODEL_CODE, banned)
        for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"!!", r"\bTODO\(", r"\bassert\("):
            self.assertIsNone(re.search(pattern, MODEL_CODE), pattern)
        self.assertEqual(["com.bilipai.desktop.ui.kit.DESKTOP_KIT_CONTROL_HEIGHT_DP", "com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_LIST_DP"],
                         imports(MODEL))

    def test_the_model_declares_only_its_own_names(self):
        names = re.findall(r"(?m)^(?:internal |private |public )?(?:const val|val|fun|enum class|data class) (\w+)", MODEL_CODE)
        self.assertTrue(all(name.startswith(("DESKTOP_CONTROL_BAR", "desktopControlBar", "DesktopControlBar")) for name in names), names)
        self.assertEqual(len(names), len(set(names)))

    def test_no_top_level_name_of_the_slice_clashes_with_another_file_of_the_desktop_sources(self):
        mine = {"DesktopWindowsVideoControlBarModel.kt", "DesktopWindowsVideoControlBar.kt", "DesktopWindowsVideoControlBarStrings.kt"}
        declared = set(re.findall(r"(?m)^(?:internal |private )?(?:const val|val|fun|object|enum class|data class|class) (?:@Composable )?(\w+)",
                                  MODEL_CODE + source.mask_kotlin(STRINGS) + BAR_CODE))
        # The private composables of the bar are file-private; the others are top level names of the package.
        public = {name for name in declared if not name.startswith("DesktopControlBar")}
        clashes = []
        for path in sorted(source.K.rglob("*.kt")) + sorted(source.KT.rglob("*.kt")):
            if path.name in mine:
                continue
            code = source.mask_kotlin(source.read(path))
            for name in public:
                if re.search(r"(?m)^(?:internal |private |public )?(?:const val|val|fun|object|enum class|data class|class) (?:\w+\.)?" + name + r"\b", code):
                    clashes.append((path.name, name))
        self.assertEqual([], clashes)


# --------------------------------------------------------------------------------------
# The frozen parts of the composable
# --------------------------------------------------------------------------------------

class FrozenTest(unittest.TestCase):
    def test_the_signature_is_the_one_of_the_bar_before_the_restyle(self):
        self.assertEqual(EXPECTED_PARAMETERS, cb.parameters(BAR, "DesktopWindowsVideoControlBar"))
        self.assertEqual(49, len(EXPECTED_PARAMETERS))
        self.assertEqual(1, BAR.count("internal fun DesktopWindowsVideoControlBar("))
        self.assertEqual(1, BAR_CODE.count("@Composable\ninternal fun DesktopWindowsVideoControlBar("))

    def test_the_leaf_calls_the_bar_once_with_named_arguments_that_are_all_parameters_and_cover_the_required_ones(self):
        self.assertEqual(1, len(calls(LEAF, "DesktopWindowsVideoControlBar")))
        names = [name for name, _value in call_arguments(LEAF, "DesktopWindowsVideoControlBar")[0]]
        self.assertNotIn(None, names)
        declared = {name: default for name, _kind, default in EXPECTED_PARAMETERS}
        self.assertTrue(set(names) <= set(declared))
        self.assertEqual({name for name, default in declared.items() if default is None}, {name for name in names if declared[name] is None})
        self.assertEqual(len(names), len(set(names)))

    def test_the_seek_function_and_the_glass_surface_are_unchanged(self):
        self.assertEqual(SEEK_DIGEST, cb.block_digest(cb.function_block(BAR, "DesktopWindowsThinSeek")))
        self.assertEqual(SURFACE_DIGEST, cb.block_digest(cb.function_block(BAR, "DesktopWindowsPlayerSurface")))

    def test_the_seek_bar_names_itself_from_the_strings_object_and_nothing_else_changed_in_it(self):
        seek = cb.function_block(BAR, "DesktopWindowsThinSeek")
        self.assertIn("contentDescription = " + S + "SEEK_BAR", seek)
        self.assertEqual(1, seek.count("contentDescription"))

    def test_the_surface_hold_and_effects_are_the_ones_of_the_bar_before_the_restyle(self):
        for needle in (
            "DesktopWindowsPlayerSurface(Modifier.fillMaxWidth().desktopWindowsChromePointerInput(onChromePointerInput) "
            ".onFocusChanged { focused = it.hasFocus } .focusGroup().hoverable(interactions)) {",
            "val latestHold by rememberUpdatedState(onInteractionHoldChanged)",
            "DisposableEffect(Unit) { onDispose { latestHold(DesktopWindowsFullscreenChromeInteraction()) } }",
            "SideEffect { latestHold(DesktopWindowsFullscreenChromeInteraction(hovered, focused, "
            "scrubbing || more || speedMenu || qualityMenu || volumeMenu || chapterMenu || enhancementMenu)) }",
            "LaunchedEffect(enabled) { if (!enabled) { more = false; speedMenu = false; qualityMenu = false; volumeMenu = false; "
            "chapterMenu = false; enhancementMenu = false } }",
            "var chapterMenu by remember(chapters, chaptersSource, sourceVersion) { mutableStateOf(false) }",
            "var enhancementMenu by remember(sourceVersion) { mutableStateOf(false) }",
            "var scrubbing by remember(seekPreviewSource, sourceVersion, chapters, chaptersSource) { mutableStateOf(false) }",
            "val menuSpeeds = remember(speedOptions, state.speed) { resolveDesktopWindowsVideoSpeedOptions(speedOptions, state.speed.toFloat()) }",
            "DesktopWindowsThinSeek(state.positionSeconds, state.durationSeconds, sourceVersion, enabled && state.durationSeconds > 0.0, "
            "chapters, chaptersSource, onChapterSeek, { scrubbing = it }, onSeek, pbpRidgeSamples, seekPreviewSource, seekPreviewEnabled, "
            "onSeekPreviewChanged)",
        ):
            self.assertEqual(1, BAR_SQUASHED.count(needle), needle)

    def test_the_nvidia_trigger_and_the_sponsor_slots_are_called_as_before(self):
        self.assertEqual(1, BAR_SQUASHED.count(
            "DesktopVideoEnhancementCompactSlot(showStatus = showEnhancementStatus, menuEnabled = enabled, "
            "onMenuExpandedChanged = { enhancementMenu = it }) { enhancement() }"))
        self.assertEqual(1, len(re.findall(r"(?m)^\s+sponsorSkip\(\)$", BAR_CODE)))
        self.assertEqual(1, BAR_SQUASHED.count("sponsorMoreActions { more = false }"))
        # The slot sits between the sponsor skip and the details button, as before.
        self.assertLess(BAR.index("sponsorSkip()\n"), BAR.index("DesktopVideoEnhancementCompactSlot("))
        self.assertLess(BAR.index("DesktopVideoEnhancementCompactSlot("), BAR.index("label = " + S + "DETAILS"))

    def test_the_breakpoints_and_the_row_come_from_the_model(self):
        for needle in ("val expanded = desktopControlBarExpanded(maxWidth.value)", "val showEnhancementStatus = desktopControlBarShowsStatus(maxWidth.value)",
                       "Column(Modifier.padding(horizontal = DESKTOP_CONTROL_BAR_SIDE_PADDING_DP.dp)) {",
                       "Row(Modifier.fillMaxWidth().heightIn(min = DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP.dp), "
                       "horizontalArrangement = Arrangement.spacedBy(DESKTOP_CONTROL_BAR_BUTTON_GAP_DP.dp), "
                       "verticalAlignment = Alignment.CenterVertically) {"):
            self.assertEqual(1, BAR_SQUASHED.count(needle), needle)
        for literal in ("940", "520"):
            self.assertNotRegex(BAR_CODE, r"\b" + literal + r"\b")

    def test_the_order_of_the_controls_is_the_order_of_the_bar_before_the_restyle(self):
        order = [BAR.index(marker) for marker in (
            "label = if (showPlay)", "label = " + S + "PREVIOUS, onClick = onPrevious", "label = " + S + "NEXT, onClick = onNext",
            'Text("${FormatUtils.formatDuration', "label = " + S + "VOLUME_AND_MUTE", "label = " + S + "SPEED",
            "label = " + S + "QUALITY", "label = " + S + "CHAPTERS, onClick = { chapterMenu = true }", "sponsorSkip()\n",
            "DesktopVideoEnhancementCompactSlot(", "label = " + S + "DETAILS", "label = " + S + "MINI_PLAYER, onClick = onPictureInPicture",
            "label = if (fullscreen)", "label = " + S + "MORE_ACTIONS")]
        self.assertEqual(sorted(order), order)

    def test_the_wide_only_controls_sit_under_the_expanded_branch_and_the_chapter_button_under_the_status_branch(self):
        for needle in ("if (expanded) { DesktopControlBarButton(label = " + S + "PREVIOUS",
                       "if (expanded) { Box { DesktopControlBarButton(label = " + S + "VOLUME_AND_MUTE",
                       "if (expanded) DesktopControlBarButton(label = " + S + "MINI_PLAYER",
                       "if (showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty()) Box { "
                       "DesktopControlBarButton(label = " + S + "CHAPTERS",
                       "if (!expanded) { DesktopControlBarMenuRow(" + S + "PREVIOUS",
                       "if (!showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty()) "
                       "DesktopControlBarMenuRow(" + S + "CHAPTERS",
                       "if (!showEnhancementStatus && chapters != null && chaptersSource != null) "
                       "DesktopWindowsPlayerMenu(chapterMenu"):
            self.assertEqual(1, BAR_SQUASHED.count(needle), needle)

    def test_the_menus_close_where_they_closed_before(self):
        # Only the mute rows keep the menu open, as the drop-down items did; every other row of a menu closes it.
        for row in MENU_ROW_CALLS:
            if "onMute" in row:
                self.assertNotIn("= false", row)
            elif "onClick" in row:
                self.assertRegex(row, r"(Menu|more) = false")

    def test_the_chapter_seek_sees_the_smart_cast_parameters_and_nothing_is_forced(self):
        self.assertNotIn("!!", BAR_CODE)


# --------------------------------------------------------------------------------------
# The kit restyle
# --------------------------------------------------------------------------------------

class RestyleSourceTest(unittest.TestCase):
    def test_every_button_is_one_kit_icon_button_with_the_enable_condition_and_operation_it_had(self):
        self.assertEqual(BUTTON_CALLS, calls(BAR, "DesktopControlBarButton"))

    def test_the_two_pills_keep_their_names_conditions_and_menus(self):
        self.assertEqual(PILL_CALLS, calls(BAR, "DesktopControlBarPill"))

    def test_every_menu_row_keeps_its_text_condition_and_operation(self):
        self.assertEqual(MENU_ROW_CALLS, calls(BAR, "DesktopControlBarMenuRow"))
        self.assertEqual(20, len(MENU_ROW_CALLS))
        self.assertEqual(3, len(calls(BAR, "DesktopControlBarMenuDivider")))

    def test_the_volume_slider_is_the_kit_slider_row_in_the_volume_menu_and_the_compact_more_menu_through_one_helper(self):
        self.assertEqual([VOLUME_SLIDER], calls(BAR, "DesktopSliderRow"), "the one slider call is inside the helper")
        self.assertEqual([VOLUME_ROW_CALL, VOLUME_ROW_CALL], calls(BAR, "DesktopControlBarVolumeRow"))

    def test_the_volume_row_is_the_kit_list_row_height_the_model_counts_whatever_the_slider_style_wants(self):
        # The Material 3 slider of the pinned library enforces a 48 dp minimum interactive size and its own handle is 44 dp tall (the
        # slider is never lower than the handle): either would make the row taller than the 40 dp the menu heights count. The helper
        # switches the enforcement off, as DesktopWindowsNativeAudioTrackMenu and DesktopWindowsVideoDescriptionSection do, and gives
        # the row its height outright; a Placeable never exceeds the constraints it was measured with, so the handle fits inside.
        helper = cb.squash(cb.function_block(BAR, "DesktopControlBarVolumeRow"))
        self.assertIn("private fun DesktopControlBarVolumeRow(state: PlayerState, enabled: Boolean, onVolume: (Double) -> Unit) {", helper)
        self.assertIn("CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { DesktopSliderRow(", helper)
        self.assertIn(".height(DESKTOP_KIT_ROW_LIST_DP.dp)", helper)
        self.assertEqual(KIT["DESKTOP_KIT_ROW_LIST_DP"], 40)
        self.assertEqual(SECTION["layout"]["row_min_height"], KIT["DESKTOP_KIT_ROW_LIST_DP"])
        self.assertEqual(1, BAR_CODE.count("LocalMinimumInteractiveComponentSize provides"), "only the volume row drops the enforcement")
        self.assertEqual(1, BAR_CODE.count("CompositionLocalProvider("))
        # The precedent that compiles today names the same local the same way.
        for name in ("DesktopWindowsNativeAudioTrackMenu.kt", "DesktopWindowsVideoDescriptionSection.kt"):
            self.assertIn("import androidx.compose.material3.LocalMinimumInteractiveComponentSize", cb.read(source.K / "ui" / name), name)
            self.assertIn("CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp)", cb.read(source.K / "ui" / name), name)

    def test_every_menu_window_asks_for_the_height_the_model_computes(self):
        heads = [text for text in calls(BAR, "DesktopWindowsPlayerMenu")]
        self.assertEqual(6, len(heads))
        for head, expected in zip(heads, MENU_HEIGHT_CALLS):
            self.assertTrue(head.endswith(expected), head)
        self.assertIsNone(re.search(r"preferredHeight = \(?\d", BAR_CODE), "no literal height is left")

    def test_the_wrapper_builds_the_button_from_the_kit_tooltip_and_the_kit_icon_button_without_a_second_tooltip(self):
        self.assertEqual(["DesktopTooltipBox(text = label, shortcut = hint, enabled = enabled, delayMillis = DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS, "
                          "positioning = TooltipAnchorPosition.Above)"], calls(BAR, "DesktopTooltipBox"))
        self.assertEqual(["DesktopIconButton(onClick = onClick, label = label, enabled = enabled, active = active, tooltip = null, content = content)"],
                         calls(BAR, "DesktopIconButton"))
        button = cb.function_block(BAR, "DesktopControlBarButton")
        self.assertEqual(1, source.mask_kotlin(button).count("DesktopTooltipBox("), "the one tooltip box belongs to the icon button wrapper")

    def test_the_two_pills_have_no_tooltip_like_the_text_buttons_they_replace(self):
        # The Windows fixtures that step through the bar by keyboard (WindowsVideoActualRootUiFixture: playerIconTooltipNames,
        # focusedPlayerTooltipButtons, settledMainInputLayers) know the tooltip layer of the icon buttons only. A pill that opened a
        # tooltip layer on focus would add a layer they do not count, so the pills stay as the focusable text buttons were.
        pill = source.mask_kotlin(cb.function_block(BAR, "DesktopControlBarPill"))
        for name in ("DesktopTooltipBox", "TooltipAnchorPosition", "DESKTOP_KIT_TOOLTIP_DELAY", "MiuixTooltipBox", "Popup"):
            self.assertNotIn(name, pill, name)
        self.assertEqual(2, len(calls(BAR, "DesktopControlBarPill")))

    def test_the_tooltip_names_the_label_then_the_key_and_never_spells_the_full_width_bracket_form(self):
        self.assertNotIn("DesktopPlayerStrings", BAR_CODE, "the dock tooltips of the DESKTOP layout are not the ones of this bar")
        self.assertEqual(7, len(re.findall(r"hint = hints\[DesktopControlBarHintTarget\.\w+\]", BAR_CODE)))
        self.assertIn("val hints = remember { DesktopControlBarHintTarget.entries.associateWith { desktopControlBarShortcutHint(it) } }", BAR_SQUASHED)
        targets = re.findall(r"hint = hints\[DesktopControlBarHintTarget\.(\w+)\]", BAR_CODE)
        self.assertEqual(["PLAY_PAUSE", "PREVIOUS", "NEXT", "MUTE", "DETAILS", "MINI_PLAYER", "FULLSCREEN"], targets, "the order of the buttons")
        self.assertEqual(sorted(cb.hint_targets(MODEL)), sorted(targets), "every hint target belongs to exactly one button")

    def test_the_glyph_is_a_decorative_vector_at_the_kit_icon_size(self):
        glyph = cb.squash(cb.function_block(BAR, "DesktopControlBarGlyph"))
        self.assertIn("Icon(icon, contentDescription = null, modifier = Modifier.size(DESKTOP_KIT_ICON_DP.dp), tint = tint)", glyph)
        self.assertEqual(0, len(re.findall(r"Icon\([^)]*contentDescription = [^n]", BAR_CODE)), "no icon names itself")

    def test_the_pill_is_one_clickable_of_the_kit_control_height_with_the_kit_interaction(self):
        pill = cb.squash(cb.function_block(BAR, "DesktopControlBarPill"))
        for needle in (".height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)", ".defaultMinSize(minWidth = DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)",
                       ".alpha(desktopKitDisabledAlpha(enabled))", ".desktopKitInteraction(source, (DESKTOP_KIT_CONTROL_HEIGHT_DP / 2).dp, enabled)",
                       ".clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)",
                       ".semantics { contentDescription = label }", ".padding(horizontal = DESKTOP_CONTROL_BAR_PILL_PADDING_DP.dp)",
                       "Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)"):
            self.assertIn(needle, pill, needle)
        self.assertEqual(1, len(re.findall(r"\bclickable\b", BAR_CODE.replace("import androidx.compose.foundation.clickable", ""))))
        self.assertEqual(1, BAR_CODE.count("Role.Button"))
        self.assertEqual(1, BAR_CODE.count("desktopKitInteraction("))

    def test_the_menu_row_wrapper_is_the_kit_menu_row_with_the_inset_and_an_optional_check(self):
        wrapper = cb.squash(cb.function_block(BAR, "DesktopControlBarMenuRow"))
        for needle in ("DesktopMenuRow(label = label, onClick = onClick, modifier = Modifier.padding(start = DESKTOP_CONTROL_BAR_MENU_START_INSET_DP.dp), "
                       "enabled = enabled, icon = mark)", "val mark: (@Composable () -> Unit)? = if (marksCurrent) ({ if (current) "
                       "DesktopControlBarGlyph(Icons.Default.Check, MaterialTheme.colorScheme.primary) }) else null"):
            self.assertIn(needle, wrapper, needle)
        divider = cb.squash(cb.function_block(BAR, "DesktopControlBarMenuDivider"))
        self.assertIn("DesktopKitDivider(Modifier.padding(start = DESKTOP_CONTROL_BAR_MENU_START_INSET_DP.dp, top = 4.dp, bottom = 4.dp))", divider)

    def test_no_other_action_site_exists_outside_the_seek_function(self):
        outside = BAR_CODE.replace(cb.function_block(BAR_CODE, "DesktopWindowsThinSeek"), "")
        for forbidden in ("combinedClickable", "onLongClick", "selectable", "toggleable", "pointerInput", "onKeyEvent", "focusable("):
            self.assertNotIn(forbidden, outside, forbidden)

    def test_no_phone_sized_hit_remains(self):
        self.assertNotIn("size(44.dp)", BAR_CODE)
        self.assertEqual(1, BAR_CODE.count("44.dp"), "only the seek band with chapter labels is 44 dp tall")

    def test_the_menu_rows_are_the_only_names_that_come_from_other_files(self):
        # sponsorMoreActions is a parameter of the leaf: its rows keep the look of their own file.
        self.assertEqual(1, BAR_SQUASHED.count("sponsorMoreActions { more = false }"))


class HygieneTest(unittest.TestCase):
    FILES = (cb.BAR_KT, cb.MODEL_KT, cb.STRINGS_KT)

    def test_the_three_files_are_armed_by_the_hygiene_scopes_and_pass_every_rule(self):
        armed = {source.rel(path) for path in source.kotlin_files(source.BANNED_IMPORT_GLOBS)}
        strict = {source.rel(path) for path in source.kotlin_files(source.STRICT_TEXT_GLOBS)}
        for path in self.FILES:
            name = source.rel(path)
            self.assertIn(name, armed, name)
            self.assertIn(name, strict, name)
            text = cb.read(path)
            self.assertEqual([], source.banned_imports(text), name)
            self.assertEqual([], source.hook_uses(text), name)
            self.assertEqual([], source.key_fallback_uses(text), name)
            if not source.is_strings_file(path):
                self.assertEqual([], source.cjk_positions(text), name + " spells Chinese outside the strings object")
        self.assertTrue(source.cjk_positions(STRINGS))

    def test_the_allowlist_names_none_of_the_files(self):
        listed = {entry["path"] for entry in source.load_allowlist()}
        for path in self.FILES:
            self.assertNotIn(source.rel(path), listed)

    def test_the_bar_imports_exactly_five_material3_names_and_no_star_and_no_material_two(self):
        material = sorted(name for name in imports(BAR) if name.startswith("androidx.compose.material3"))
        self.assertEqual(["androidx.compose.material3.Icon", "androidx.compose.material3.LocalContentColor",
                          "androidx.compose.material3.LocalMinimumInteractiveComponentSize",
                          "androidx.compose.material3.MaterialTheme", "androidx.compose.material3.Text"], material)
        self.assertEqual([], [name for name in imports(BAR) if re.match(r"androidx\.compose\.material\.(?!icons)", name)])
        self.assertEqual([], [name for name in imports(BAR) if name.startswith("top.yukonga") and name != "top.yukonga.miuix.kmp.basic.TooltipAnchorPosition"])

    def test_no_material_text_button_chip_switch_menu_item_divider_slider_or_icon_button_is_called(self):
        for name in ("TextButton", "FilterChip", "Switch", "DropdownMenu", "DropdownMenuItem", "AlertDialog", "TabRow", "HorizontalDivider",
                     "Divider", "Slider", "IconButton", "OutlinedButton", "Button", "AssistChip", "InputChip", "Card"):
            self.assertIsNone(re.search(r"(?<![\w.])" + name + r"\(", BAR_CODE), name)

    def test_the_bar_imports_what_it_uses_from_the_kit_and_nothing_it_does_not(self):
        kit = sorted(name for name in imports(BAR) if name.startswith("com.bilipai.desktop.ui.kit."))
        self.assertEqual(sorted("com.bilipai.desktop.ui.kit." + name for name in (
            "DESKTOP_KIT_CONTROL_HEIGHT_DP", "DESKTOP_KIT_ICON_DP", "DESKTOP_KIT_ROW_LIST_DP", "DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS", "DesktopIconButton",
            "DesktopKitDivider", "DesktopMenuRow", "DesktopSliderRow", "DesktopTooltipBox", "desktopKitDisabledAlpha", "desktopKitInteraction")), kit)
        for name in kit:
            simple = name.rsplit(".", 1)[1]
            self.assertGreaterEqual(len(re.findall(r"\b" + re.escape(simple) + r"\b", re.sub(r"(?m)^import .*$", "", BAR_CODE))), 1, simple)

    def test_every_import_of_the_bar_is_used_or_a_star_and_every_icon_exists(self):
        body = re.sub(r"(?m)^import .*$", "", BAR_CODE)
        for name in imports(BAR):
            if name.endswith("*"):
                continue
            simple = name.rsplit(" as ", 1)[-1].rsplit(".", 1)[-1]
            self.assertRegex(body, r"\b" + re.escape(simple) + r"\b", name + " is imported and never used")
        used = set(re.findall(r"Icons\.Default\.(\w+)", BAR_CODE))
        self.assertEqual({"PlayArrow", "Pause", "SkipPrevious", "SkipNext", "VolumeOff", "VolumeUp", "ListAlt", "Info", "PictureInPictureAlt",
                          "FullscreenExit", "Fullscreen", "MoreVert", "Check"}, used)


# --------------------------------------------------------------------------------------
# Strings and the fixtures
# --------------------------------------------------------------------------------------

class StringsTest(unittest.TestCase):
    def test_the_constants_are_the_literals_of_the_bar_before_the_restyle(self):
        for name, text in PRE_RESTYLE_LITERALS.items():
            self.assertEqual(text, STRING_CONSTANTS[name], name)
        self.assertEqual(set(PRE_RESTYLE_LITERALS) | {"KEY_SPACE"}, set(STRING_CONSTANTS))

    def test_the_names_both_strings_objects_hold_are_equal(self):
        player = cb.string_constants(PLAYER_STRINGS)
        for name in ("PLAY", "PAUSE", "FULLSCREEN", "EXIT_FULLSCREEN", "DETAILS", "MINI_PLAYER", "MORE_ACTIONS", "INTRODUCTION_AND_SETTINGS"):
            self.assertEqual(player[name], STRING_CONSTANTS[name], name)
        self.assertEqual(player["QUALITY_PILL_FALLBACK"], STRING_CONSTANTS["QUALITY"])

    def test_the_spec_section_seven_names_of_the_bar_are_present(self):
        for name in ("播放", "暂停", "全屏", "退出全屏", "详情", "更多播放操作", "简介、分P与播放设置"):
            self.assertIn(name, STRING_CONSTANTS.values(), name)

    def test_the_key_name_of_the_space_tooltip(self):
        self.assertEqual("空格", STRING_CONSTANTS["KEY_SPACE"])
        self.assertEqual(["空", "格"], list(STRING_CONSTANTS["KEY_SPACE"]))

    def test_the_templates(self):
        templates = {
            "speedValue(speedText: String): String": '"${speedText}×"', "speedRow(speedText: String): String": '"倍速 ${speedText}×"',
            "qualityRow(label: String): String": '"画质 · ${label}"', "chapterRow(time: String, title: String): String": '"${time} · ${title}"',
            "volumePercent(volume: Int): String": '"${volume}%"',
        }
        squashed = cb.squash(STRINGS)
        for head, body in templates.items():
            self.assertIn("fun %s = %s" % (head, body), squashed, head)

    def test_every_constant_is_used_by_the_bar_or_the_model_and_every_use_names_a_constant(self):
        used = set(re.findall(r"DesktopWindowsVideoControlBarStrings\.(\w+)", BAR_CODE + MODEL_CODE))
        constants = set(STRING_CONSTANTS)
        functions = {"speedValue", "speedRow", "qualityRow", "chapterRow", "volumePercent"}
        self.assertEqual(set(), used - constants - functions)
        self.assertEqual(set(), constants - used)
        self.assertEqual(functions, used & functions)

    def test_the_strings_object_has_only_constants_and_pure_templates(self):
        code = source.mask_kotlin(STRINGS)
        self.assertEqual([], imports(STRINGS))
        for banned in ("androidx", "Composable", "remember(", "@"):
            self.assertNotIn(banned, code, banned)


class FixtureCouplingTest(unittest.TestCase):
    FIXTURE = cb.read(cb.FIXTURE_KT)

    def fixture_set(self):
        text = self.FIXTURE[self.FIXTURE.index("private val playerIconTooltipNames = setOf("):]
        return re.findall(r'"([^"]+)"', text[:text.index(")")])

    def test_every_name_the_tooltip_fixture_looks_for_is_the_label_of_a_button_or_a_state_variant_of_one(self):
        labels = set()
        for text in calls(BAR, "DesktopControlBarButton"):
            labels.update(re.findall(r"DesktopWindowsVideoControlBarStrings\.(\w+)", text))
        names = {STRING_CONSTANTS[name] for name in labels}
        wanted = self.fixture_set()
        self.assertEqual(11, len(wanted))
        self.assertEqual(set(wanted), names, "the fixture set is the set of button names, state variants included")

    def test_every_control_the_windows_fixtures_click_by_name_exists_in_the_strings(self):
        values = set(STRING_CONSTANTS.values())
        for name in ("播放", "暂停", "详情", "浮窗", "全屏", "退出全屏", "更多播放操作", "视频章节", "视频合集", "播放队列", "简介、分P与播放设置"):
            self.assertIn('"%s"' % name, self.FIXTURE, name + " is looked up by the fixture")
            self.assertIn(name, values, name)

    def test_a_clicked_icon_button_is_one_node_with_one_action_by_the_kit_contract(self):
        kit = cb.read(source.K / "ui/kit/DesktopKitControls.kt")
        block = cb.function_block(kit, "DesktopIconButton")
        self.assertEqual(1, len(re.findall(r"\bclickable\b", source.mask_kotlin(block))))
        self.assertIn("role = Role.Button", block)
        self.assertIn("contentDescription = label", block)


# --------------------------------------------------------------------------------------
# The JVM tests of the slice and the files
# --------------------------------------------------------------------------------------

class JvmTestSourceTest(unittest.TestCase):
    MODEL_TEST = cb.read(cb.MODEL_TEST_KT)
    UI_TEST = cb.read(cb.UI_TEST_KT)

    def test_the_model_test_reads_the_byte_identical_contract_copy_and_needs_no_window(self):
        self.assertIn('DesktopUxContract.section("control_bar")', self.MODEL_TEST)
        self.assertEqual(cb.model.CONTRACT_PATH.read_bytes(), (source.REPO / "desktop/src/test/resources/desktop-ux-contract.json").read_bytes())
        for banned in ("ImageComposeScene", "Robot", "Thread.sleep", "ComposeWindow", "Window(", "java.awt", "java.net", "HttpURLConnection"):
            self.assertNotIn(banned, self.MODEL_TEST, banned)
        self.assertEqual(16, len(re.findall(r"@Test fun ", self.MODEL_TEST)))

    def test_the_ui_test_is_marked_as_a_scene_test_and_opens_no_window_or_menu(self):
        self.assertIn("ImageComposeScene test (CPU scene, headless JVM; no native window, no GPU, no network)", self.UI_TEST)
        self.assertIn('check(GraphicsEnvironment.isHeadless())', self.UI_TEST)
        code = source.mask_kotlin(self.UI_TEST)
        for banned in ("ComposeWindow", "Robot", "Thread.sleep", "DesktopWindowsPlayerMenu", "LocalDesktopWindowsPlayerWindow", "java.net"):
            self.assertNotIn(banned, code, banned)
        self.assertEqual(13, len(re.findall(r"@Test fun ", self.UI_TEST)))

    def test_the_ui_test_calls_the_bar_with_every_parameter_without_a_default_and_only_those(self):
        names = [name for name, _value in call_arguments(self.UI_TEST, "DesktopWindowsVideoControlBar")[0]]
        required = [name for name, _kind, default in EXPECTED_PARAMETERS if default is None]
        self.assertEqual(sorted(required), sorted(names))

    def test_the_test_classes_are_in_the_package_of_the_files_they_test(self):
        for text in (self.MODEL_TEST, self.UI_TEST):
            self.assertEqual(1, len(re.findall(r"(?m)^package com\.bilipai\.desktop\.ui$", text)))


class LineEndingTest(unittest.TestCase):
    def test_every_file_of_the_slice_is_lf_only_without_control_characters(self):
        source.assert_lf_only(self, cb.BAR_KT, cb.MODEL_KT, cb.STRINGS_KT, cb.MODEL_TEST_KT, cb.UI_TEST_KT,
                              Path(__file__).resolve(), Path(cb.__file__).resolve(), cb.model.CONTRACT_PATH,
                              source.REPO / "desktop/src/test/resources/desktop-ux-contract.json", source.HERE / "ux_source.py",
                              source.HERE / "test_ux_api_probe_source.py")


if __name__ == "__main__":
    unittest.main()
