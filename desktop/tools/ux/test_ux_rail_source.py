"""Slice S1 source checks: the shell frame, the navigation rail, the now-playing strip, the insets and hunk RH2. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_rail_source.py

What is proved. The files the slice adds (ui/DesktopWindowsShellFrame.kt, ui/DesktopWindowsNavigationRail.kt,
ui/DesktopWindowsNowPlayingBar.kt, ui/DesktopShellInsets.kt) and the pure additions to ui/DesktopWindowsRailModel.kt have the
shape the spec asks for, as text:

  FrameSourceTest     the frame has exactly one content() call at a fixed place (toggling the rail never remounts Home or the
                      NavDisplay), the Root chrome has exactly one frame call and no content() call of its own, the classic block
                      and the classic audio row stay inline, the three locals are provided once each, the zoom toast reads the two
                      densities, the rail flag has one reader, the Root SnackbarHost is not replaced, the toggle is the frame's
                      (it writes only where that changes the rail, else it says the window is too narrow), a second failed write
                      in a row is looked at again after a short wait
  Rh2HunkTest         the three RH2 hunks, reverse-applied to the Root chrome, give the base blob byte for byte (sha256 and size
                      pinned), and the hunk stays small
  RailSourceTest      every row is one kit DesktopNavRow, the rail has exactly one clickable of its own (the search row), the
                      tooltip wrapper adds no action, the accessible names BiliPai, search and downloads are kept, roving focus
                      makes one tab stop, every click keeps the routes.owns() guard and the admitted push entry, the selection rule
                      is the existing one, the unread badge and its announcement, the right-click menu, the keys the rail names
                      stay behind DESKTOP_RAIL_SHORTCUT_HINTS (true since slice S2 added the shortcut host), the row keys sit at the
                      call sites
  NowPlayingSourceTest the strip reads no state, calls no player, and has the five buttons in order
  ModelMirrorTest     the Python mirrors of the new pure functions agree with independent oracles, and the metrics are the contract
  FilesTest           line endings, the program scopes of the hygiene suite, the JVM tests exist and use no window

What is not proved. That the Kotlin compiles or runs. The JVM tests (DesktopWindowsRailModelTest and the ImageComposeScene class
DesktopWindowsNavigationRailUiTest) are the proof, on the Linux compile later; the Windows fixtures that look for BiliPai, the
search row and the downloads row are not run here either.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as
spec 8.2 says; Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.

Slice notes (S1 and its review fix), kept here with the pins they explain.
  * S1 must not ship without S2 (same release R2). The tooltips and the search hint name Ctrl+B, Ctrl+K and Ctrl+1 to 9, which only
    the S2 shortcut host handles. While S1 stood alone they were behind DESKTOP_RAIL_SHORTCUT_HINTS = false; S2 set it to true in
    the same change as the host and updated that pin (pinned below as true).
  * The default rail_mode is AUTO (spec 5.2, release R2), so the merge of S1 replaces the 172 dp text block for everyone who has
    chosen nothing; CLASSIC_TEXT keeps the old block. Spec section 2 would ship a [W] item behind a flag that defaults to the
    existing behaviour until accepted, and hard rule 10 asks that for batch A; S1 is a batch B slice and the spec table makes AUTO the
    default, so the default was left alone. Root is asked to confirm that this visible change at merge is intended for R2, or to say
    that the rail must wait for the L4 look check behind an availability constant like DESKTOP_PLAYER_OVERLAY_AVAILABLE.
  * Size: the main-source change is over the cap of about 600 lines (rule 10, spec 9.1); the figures are in the slice report. The
    coherent cut, if Root wants one, is DesktopWindowsNowPlayingBar.kt with its nowPlaying slot branch of hunk RH2 and the zoom
    toast, as a follow-up slice S1b.
"""
import difflib
import hashlib
import re
import sys
import unittest
from fractions import Fraction
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
FRAME_KT = source.K / "ui/DesktopWindowsShellFrame.kt"
RAIL_KT = source.K / "ui/DesktopWindowsNavigationRail.kt"
BAR_KT = source.K / "ui/DesktopWindowsNowPlayingBar.kt"
INSETS_KT = source.K / "ui/DesktopShellInsets.kt"
MODEL_KT = shell.RAIL_KT
STRINGS_KT = shell.STRINGS_KT
CHROME_KT = shell.ROOT_CHROME
KIT_CONTROLS_KT = source.K / "ui/kit/DesktopKitControls.kt"
SETTINGS_GROUP_KT = source.K / "ui/DesktopWindowsUxSettings.kt"
SHELL_KT = source.K / "DesktopShell.kt"
FIXTURE_KT = source.KT / "ui/WindowsVideoActualRootUiFixture.kt"
MODEL_TEST_KT = source.KT / "ui/DesktopWindowsRailModelTest.kt"
UI_TEST_KT = source.KT / "ui/DesktopWindowsNavigationRailUiTest.kt"

FRAME = source.read(FRAME_KT)
RAIL = source.read(RAIL_KT)
BAR = source.read(BAR_KT)
INSETS = source.read(INSETS_KT)
MODEL_TEXT = source.read(MODEL_KT)
STRINGS = source.read(STRINGS_KT)
CHROME = source.read(CHROME_KT)
FRAME_CODE = source.mask_kotlin(FRAME)
RAIL_CODE = source.mask_kotlin(RAIL)
BAR_CODE = source.mask_kotlin(BAR)
CHROME_CODE = source.mask_kotlin(CHROME)
RAIL_FLAT = shell.squash(RAIL_CODE)
FRAME_FLAT = shell.squash(FRAME_CODE)
CHROME_FLAT = shell.squash(CHROME_CODE)
CONSTS = shell.int_constants(MODEL_TEXT)
LIMITS = shell.thresholds(MODEL_TEXT)


def function_block(code, name):
    """Masked source from the signature of the top-level function `name` to its closing brace at column 0."""
    match = re.search(r"(?m)^(?:internal |private )?fun (?:<[^>]+> )?(?:(?:Modifier|BoxScope)\.)?%s\(" % re.escape(name), code)
    if match is None:
        raise AssertionError("no function " + name)
    end = code.find("\n}\n", match.end())
    if end < 0:
        raise AssertionError("no closing brace for " + name)
    return code[match.start():end + 2]


# --------------------------------------------------------------------------------------
# Hunk RH2
# --------------------------------------------------------------------------------------

# The three anchor-based hunks of slice RH2 in file order: (before, after). Each "after" block occurs exactly once in the Root chrome.
RH2_HUNKS = (
    (
        "        Row(Modifier.fillMaxSize()) {\n"
        "            if (!video) DesktopWindowsGlassSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {\n",
        "        // UX S1 (hunk RH2): the rail and the content column are one DesktopWindowsShellFrame, whose single content() call site keeps\n"
        "        // the content in place whatever the rail does. The classic text rail and the audio row below stay inline here for rail_mode\n"
        "        // CLASSIC_TEXT; every other mode mounts the glass rail and the 56 dp now-playing strip.\n"
        "        DesktopWindowsShellFrame(railVisible = !video, nowPlayingVisible = audioVisible, content = content,\n"
        "            rail = { shell ->\n"
        "            if (shell.presentation != DesktopRailPresentation.CLASSIC) DesktopWindowsRailHost(routes, binding, currentItem, visibleItems, onItemClick, shell)\n"
        "            else DesktopWindowsGlassSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {\n",
    ),
    (
        "            Column(Modifier.weight(1f).fillMaxHeight()) {\n"
        "                Box(Modifier.weight(1f).fillMaxWidth()) { content() }\n"
        "                if (audioVisible && audio != null && snapshot != null) DesktopWindowsGlassSurface(Modifier.fillMaxWidth()) {\n",
        "            },\n"
        "            nowPlaying = { shell ->\n"
        "            if (audio != null && snapshot != null) {\n"
        "                if (shell.presentation != DesktopRailPresentation.CLASSIC) DesktopWindowsNowPlayingBar(\n"
        "                    title = snapshot.item.title, playing = snapshot.isPlaying,\n"
        "                    onPrevious = { audio.previous(snapshot) }, onPlayPause = { audio.playPause(snapshot, navigation) },\n"
        "                    onNext = { audio.next(snapshot) }, onOpen = { audio.expand(snapshot, navigation) },\n"
        "                    onClose = { audio.dismiss(snapshot, navigation) })\n"
        "                else DesktopWindowsGlassSurface(Modifier.fillMaxWidth()) {\n",
    ),
    (
        "                }\n"
        "            }\n"
        "        }\n"
        "        }\n"
        "        }\n"
        "    }\n"
        "}\n",
        "                }\n"
        "            }\n"
        "            })\n"
        "        }\n"
        "        }\n"
        "    }\n"
        "}\n",
    ),
)
BASE_SHA256 = "3ce4aa0ff94dc8320fa53a80923e24d852ae6ee584c32bbe4dfd2baee48477f1"
BASE_BYTES = 7873
ADDED_LINES, REMOVED_LINES = 17, 6


def reverse_apply(text):
    """The Root chrome with the three RH2 hunks undone, last hunk first."""
    for before, after in reversed(RH2_HUNKS):
        if text.count(after) != 1:
            raise AssertionError("an RH2 hunk is not in the Root chrome exactly once: " + after[:70])
        text = text.replace(after, before)
    return text


class Rh2HunkTest(unittest.TestCase):
    def test_the_hunks_undo_to_the_base_blob_byte_for_byte(self):
        base = reverse_apply(CHROME).encode("utf-8")
        self.assertEqual(BASE_BYTES, len(base))
        self.assertEqual(BASE_SHA256, hashlib.sha256(base).hexdigest())

    def test_the_hunk_is_small_and_mechanical(self):
        base = reverse_apply(CHROME).splitlines()
        diff = list(difflib.unified_diff(base, CHROME.splitlines(), lineterm="", n=0))
        added = sum(1 for line in diff if line.startswith("+") and not line.startswith("+++"))
        removed = sum(1 for line in diff if line.startswith("-") and not line.startswith("---"))
        self.assertEqual((ADDED_LINES, REMOVED_LINES), (added, removed))
        self.assertLessEqual(added, 30)

    def test_every_removed_line_is_a_frame_or_slot_line_never_a_classic_line(self):
        removed = [line for line in difflib.unified_diff(reverse_apply(CHROME).splitlines(), CHROME.splitlines(), lineterm="", n=0)
                   if line.startswith("-") and not line.startswith("---")]
        self.assertEqual([
            "-        Row(Modifier.fillMaxSize()) {",
            "-            if (!video) DesktopWindowsGlassSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {",
            "-            Column(Modifier.weight(1f).fillMaxHeight()) {",
            "-                Box(Modifier.weight(1f).fillMaxWidth()) { content() }",
            "-                if (audioVisible && audio != null && snapshot != null) DesktopWindowsGlassSurface(Modifier.fillMaxWidth()) {",
            "-        }",
        ], removed)

    def test_nothing_outside_the_block_of_the_rail_changed(self):
        base = reverse_apply(CHROME)
        anchor = "        // This publishes the real Windows shader capability gate, not feed pixels.\n"
        self.assertEqual(1, base.count(anchor))
        self.assertEqual(base[:base.index(anchor)], CHROME[:CHROME.index(anchor)], "the chrome above the rail block is unchanged")
        self.assertEqual(1, CHROME.count("    if (!routes.owns() || !binding.owner.isOwned()) return\n"))


# --------------------------------------------------------------------------------------
# Frame
# --------------------------------------------------------------------------------------

class FrameSourceTest(unittest.TestCase):
    def test_exactly_one_content_call_at_a_fixed_place(self):
        self.assertEqual(1, len(re.findall(r"\bcontent\(\)", FRAME_CODE)))
        # Slice S2 changed this pin by exactly the shortcut wiring: the mouse Back modifier on the Row, one Box around the rail slot and
        # the pane modifier on the content Box. content() keeps its single call at the same place; nothing conditional wraps it.
        self.assertEqual(1, FRAME_FLAT.count(
            "Row(Modifier.fillMaxSize().desktopShellBackButton(shortcuts)) { "
            "if (railVisible) Box(Modifier.fillMaxHeight().desktopShellPane(shortcuts, DesktopShellPane.RAIL, railPane)) { rail(shell) } "
            "Column(Modifier.weight(1f).fillMaxHeight()) { "
            "Box(Modifier.weight(1f).fillMaxWidth().desktopShellPane(shortcuts, DesktopShellPane.CONTENT, contentPane)) { "
            "content() DesktopToastHost(toaster) } "
            "if (nowPlayingVisible) nowPlaying(shell) } }"))
        # Nothing conditional wraps the call: the Row, the Column and the Box are plain containers, and the only branches of the
        # frame body sit beside the Column (the rail slot and the strip slot), never around it.
        body = function_block(FRAME_CODE, "DesktopWindowsShellFrame")
        self.assertEqual(2, len(re.findall(r"\bif \(", body[body.index("CompositionLocalProvider("):])))
        self.assertIsNone(re.search(r"\b(when|else|for|while)\b", body[body.index("CompositionLocalProvider("):]))

    def test_the_rail_slot_comes_before_the_content_column_and_the_strip_after_the_content(self):
        text = FRAME_FLAT
        self.assertLess(text.index("if (railVisible) Box("), text.index("{ rail(shell) }"))
        self.assertLess(text.index("{ rail(shell) }"), text.index("content()"))
        self.assertLess(text.index("content()"), text.index("if (nowPlayingVisible) nowPlaying(shell)"))

    def test_the_root_chrome_has_one_frame_call_and_no_content_call_of_its_own(self):
        self.assertEqual(1, len(re.findall(r"\bDesktopWindowsShellFrame\(", CHROME_CODE)))
        self.assertEqual(0, len(re.findall(r"\bcontent\(\)", CHROME_CODE)))
        self.assertEqual(1, CHROME_FLAT.count("content = content,"))
        self.assertIn("DesktopWindowsShellFrame(railVisible = !video, nowPlayingVisible = audioVisible, content = content,", CHROME_FLAT)
        # The rail and the strip are the only slots, and each has the classic branch next to the new one.
        self.assertEqual(2, CHROME_FLAT.count("if (shell.presentation != DesktopRailPresentation.CLASSIC)"))
        self.assertEqual(1, CHROME_FLAT.count("DesktopWindowsRailHost(routes, binding, currentItem, visibleItems, onItemClick, shell)"))
        self.assertEqual(1, len(re.findall(r"\bDesktopWindowsNowPlayingBar\(", CHROME_CODE)))
        self.assertEqual(1, len(re.findall(r"\bDesktopWindowsRailHost\(", CHROME_CODE)))

    def test_the_classic_text_block_and_the_classic_audio_row_stay_inline_and_unedited(self):
        for needle in ("Box(Modifier.width(172.dp).fillMaxHeight())", 'Text("BiliPai", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))',
                       'TextButton(onClick = { if (routes.owns()) binding.actions.searchClick() }, modifier = Modifier.fillMaxWidth()) { Text("搜索") }',
                       'TextButton(onClick = { if (routes.owns()) routes.push(BiliPaiNavKey.DownloadList) }, modifier = Modifier.fillMaxWidth()) { Text("下载与离线") }',
                       "val selected = key == BiliPaiNavKey.MainHost && item == currentItem || key.toLegacyRoute() == item.route",
                       'TextButton(onClick = { if (routes.owns()) switch() }, modifier = Modifier.fillMaxWidth()) { Text("切换账号") }',
                       'TextButton(onClick = { audio.previous(snapshot) }) { Text("上一首") }',
                       'Button(onClick = { audio.playPause(snapshot, navigation) }) { Text(if (snapshot.isPlaying) "暂停" else "播放") }',
                       'TextButton(onClick = { audio.next(snapshot) }) { Text("下一首") }',
                       'TextButton(onClick = { audio.expand(snapshot, navigation) }) { Text("打开播放器") }',
                       'TextButton(onClick = { audio.dismiss(snapshot, navigation) }) { Text("关闭") }',
                       "unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f)",
                       "hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)"):
            self.assertEqual(1, CHROME.count(needle), needle)
        self.assertEqual(1, CHROME.count("if (sidebarScroll.maxValue > 0) VerticalScrollbar("))

    def test_the_frame_provides_the_three_locals_once_each(self):
        for needle in ("LocalDesktopKitReducedMotion provides reducedMotion,", "LocalDesktopToaster provides toaster,",
                       "LocalDesktopShellInsets provides DesktopShellInsets(footprintDp),"):
            self.assertEqual(1, FRAME_FLAT.count(needle), needle)
        self.assertEqual(1, len(re.findall(r"\bCompositionLocalProvider\(", FRAME_CODE)))
        self.assertEqual(1, len(re.findall(r"\bDesktopToastHost\(", FRAME_CODE)))
        self.assertEqual(1, len(re.findall(r"\brememberDesktopToastController\(", FRAME_CODE)))
        self.assertIn("val footprintDp = if (railVisible) presentation.footprintDp else 0", FRAME_FLAT)

    def test_reduced_motion_is_read_once_through_the_named_accessor(self):
        self.assertEqual(1, len(re.findall(r"\brememberSystemReduceMotion\(\)", FRAME_CODE)))
        self.assertIn("import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion", FRAME)
        for name, code in (("rail", RAIL_CODE), ("bar", BAR_CODE), ("insets", source.mask_kotlin(INSETS))):
            self.assertIsNone(re.search(r"rememberSystemReduceMotion|rememberDesktopDynamicReduceMotion", code), name)

    def test_the_rail_presentation_is_the_pure_rule_with_a_non_state_memory_committed_after_composition(self):
        for needle in ("val windowWidthDp = (LocalWindowInfo.current.containerSize.width / density).toInt()",
                       "val presentation = desktopRailPresentation(settings.railMode, windowWidthDp, memory.presentation)",
                       "SideEffect { memory.presentation = presentation }",
                       "val density = LocalDensity.current.density"):
            self.assertEqual(1, FRAME_FLAT.count(needle), needle)
        self.assertIn("private class DesktopShellFrameMemory {", FRAME_CODE)
        self.assertIsNone(re.search(r"mutableStateOf", FRAME_CODE), "the memory is a plain holder, not snapshot state")

    def test_a_mode_change_is_a_command_of_the_flags_store_and_a_refusal_or_a_late_error_is_the_rails_save_failure(self):
        for needle in ("val setMode: (DesktopWindowsUxRailMode) -> Unit = { mode -> memory.railWriteMs = desktopShellNowMs() "
                       "val standing = ux.writeError.value != null "
                       "if (!ux.submit(DesktopWindowsUxCommand.SetRailMode(mode))) { toaster.show(DesktopShellStrings.RAIL_SAVE_FAILED) } "
                       "else if (standing) { scope.launch { delay(DESKTOP_RAIL_SAVE_RECHECK_MS) "
                       "if (ux.writeError.value != null) toaster.show(DesktopShellStrings.RAIL_SAVE_FAILED) } } }",
                       "LaunchedEffect(ux, toaster) { ux.writeError.collect { problem -> "
                       "if (desktopRailWriteFailureShown(memory.railWriteMs, desktopShellNowMs(), problem != null)) { "
                       "toaster.show(DesktopShellStrings.RAIL_SAVE_FAILED) } } }",
                       "private fun desktopShellNowMs(): Long = System.nanoTime() / 1_000_000L"):
            self.assertEqual(1, FRAME_FLAT.count(needle), needle)
        self.assertEqual(1, len(re.findall(r"\.submit\(", FRAME_CODE)), "the frame writes through one submit")

    def test_a_second_failed_write_in_a_row_is_looked_at_again_because_the_published_error_does_not_change(self):
        # The controller publishes one value (WRITE_FAILED) for every failure and a StateFlow drops an equal value, so the collector
        # above sees the first failure only. A rail write that finds the error standing waits a moment and looks again; a write that
        # succeeds clears the error inside the wait, so the toast is not shown for it.
        self.assertEqual(1, len(re.findall(r"\brememberCoroutineScope\(\)", FRAME_CODE)))
        self.assertEqual(1, len(re.findall(r"\bscope\.launch\s*\{", FRAME_CODE)))
        self.assertEqual(1, len(re.findall(r"\bdelay\(", FRAME_CODE)))
        for line in ("import androidx.compose.runtime.rememberCoroutineScope", "import kotlinx.coroutines.delay",
                     "import kotlinx.coroutines.launch"):
            self.assertEqual(1, FRAME.count(line + "\n"), line)
        self.assertIn("internal const val DESKTOP_RAIL_SAVE_RECHECK_MS = 750L\n", MODEL_TEXT)
        controller = source.read(source.K / "appearance/DesktopWindowsUx.kt")
        # Both halves of the reason are in the controller: one shared error text, and a success that clears it.
        self.assertEqual(2, controller.count("mutableWriteError.value = DesktopWindowsUxStrings.WRITE_FAILED"))
        self.assertEqual(1, controller.count("mutableWriteError.value = null"))

    def test_the_comment_on_a_refused_command_says_it_changes_nothing(self):
        # A refused submit (nothing owns the store, or it is closed) changes no mode: the old comment said the mode stayed in force.
        self.assertIn("A refused command (nothing owns\n    // the store, or it is closed) changes nothing at all.", FRAME)
        self.assertNotIn("the mode stays in force for this session and a toast says it was not saved", FRAME)

    def test_the_toggle_is_the_frames_and_writes_only_where_it_changes_what_the_rail_shows(self):
        # The default window (1360 dp, 1088 dp of content at the 125% zoom) shows the collapsed rail in AUTO. Writing EXPANDED there
        # changes nothing on screen and leaves the toggle without a target, so the frame says the window is too narrow instead.
        self.assertEqual(1, FRAME_FLAT.count(
            "val toggle: () -> Unit = { val target = desktopRailToggleMode(settings.railMode, presentation) "
            "if (target != null && desktopRailToggleEffective(settings.railMode, presentation, windowWidthDp)) { setMode(target) } "
            "else if (presentation != DesktopRailPresentation.CLASSIC) { toaster.show(DesktopShellStrings.RAIL_TOO_NARROW) } }"))
        self.assertEqual(1, FRAME_FLAT.count("val shell = DesktopWindowsShellRail(settings.railMode, presentation, setMode, toggle)"))
        self.assertIn("internal class DesktopWindowsShellRail(val mode: DesktopWindowsUxRailMode, val presentation: DesktopRailPresentation, "
                      "val setMode: (DesktopWindowsUxRailMode) -> Unit, val toggle: () -> Unit,)", FRAME_FLAT)
        self.assertEqual(1, len(re.findall(r"\bdesktopRailToggleEffective\(", FRAME_CODE)))
        self.assertEqual(1, len(re.findall(r"\bdesktopRailToggleMode\(", FRAME_CODE)))
        self.assertEqual('const val RAIL_TOO_NARROW = "窗口太窄，无法展开导航栏"', re.search(r'const val RAIL_TOO_NARROW = "[^"]*"', STRINGS).group(0))
        # The toggle keeps the explicit-mode rule: the frame writes through setMode, never through a second submit.
        self.assertEqual(1, len(re.findall(r"\bsetMode\(target\)", FRAME_CODE)))

    def test_the_zoom_toast_reads_the_scaled_and_the_system_density(self):
        for needle in ("val zoomPercent = desktopZoomPercent(density, LocalDesktopWindowsSystemDensity.current?.density)",
                       "LaunchedEffect(zoomPercent) { val shown = desktopZoomToastPercent(memory.zoomPercent, zoomPercent) "
                       "if (zoomPercent != null) memory.zoomPercent = zoomPercent "
                       "if (shown != null) toaster.show(DesktopShellStrings.zoomToast(shown)) }"):
            self.assertEqual(1, FRAME_FLAT.count(needle), needle)
        # LocalDesktopWindowsDisplayScale throws without its provider; the frame must work in a fixture that has none.
        self.assertNotIn("LocalDesktopWindowsDisplayScale", FRAME_CODE)
        display = source.read(source.K / "appearance/DesktopWindowsDisplayScale.kt")
        self.assertIn("internal val LocalDesktopWindowsSystemDensity = staticCompositionLocalOf<Density?> { null }", display)
        self.assertIn("return Density(system.density * percent / 100f, system.fontScale)", display)

    def test_the_toast_host_is_added_beside_the_root_snackbar_host_not_instead_of_it(self):
        for name, code in (("frame", FRAME_CODE), ("rail", RAIL_CODE), ("bar", BAR_CODE), ("chrome", CHROME_CODE)):
            self.assertIsNone(re.search(r"\bSnackbar\w*", code), name)
        shell_code = source.mask_kotlin(source.read(SHELL_KT))
        self.assertIn("SnackbarHost(notices, Modifier.align(Alignment.BottomCenter))", shell_code)
        self.assertIn("val rootFeedback = remember { SnackbarHostState() }", shell_code)

    def test_the_toast_sits_above_the_strip_by_layout(self):
        # The host is a child of the box that ends where the strip begins, so no inset arithmetic can put it under the strip.
        self.assertIn("DesktopToastHost(toaster) }", FRAME_FLAT)
        self.assertNotIn("bottomInset", FRAME_CODE)

    def test_the_insets_are_a_dynamic_local_with_a_zero_default(self):
        flat = shell.squash(source.mask_kotlin(INSETS))
        self.assertIn("internal data class DesktopShellInsets(val leadingDp: Int = 0)", flat)
        self.assertIn("internal val LocalDesktopShellInsets = compositionLocalOf { DesktopShellInsets() }", flat)
        self.assertNotIn("staticCompositionLocalOf", flat)
        self.assertEqual(["import androidx.compose.runtime.compositionLocalOf"], re.findall(r"(?m)^import .*$", INSETS))

    def test_the_rail_flag_has_exactly_one_reader_and_the_settings_row_is_on(self):
        readers = []
        for path in sorted(source.K.rglob("*.kt")):
            if path == SETTINGS_GROUP_KT or source.rel(path).startswith("appearance/DesktopWindowsUx"):
                continue
            if re.search(r"\.railMode\b", source.mask_kotlin(source.read(path))):
                readers.append(source.rel(path))
        self.assertEqual(["ui/DesktopWindowsShellFrame.kt"], readers)
        self.assertIn("private const val RAIL_MODE_HAS_CONSUMER = true\n", source.read(SETTINGS_GROUP_KT))
        for other in ("PLAYER_LAYOUT", "HOME_LAYOUT", "CARD_INTERACTIONS"):
            self.assertIn("private const val %s_HAS_CONSUMER = false\n" % other, source.read(SETTINGS_GROUP_KT), other)

    def test_the_default_mode_is_automatic_and_the_classic_mode_is_the_old_block(self):
        defaults = source.read(source.K / "appearance/DesktopWindowsUxModel.kt")
        self.assertIn("val railMode: DesktopWindowsUxRailMode = DesktopWindowsUxRailMode.AUTO,", defaults)
        self.assertEqual("classic", shell.presentations(MODEL_TEXT)["CLASSIC"][0])
        self.assertIn("DesktopWindowsUxRailMode.CLASSIC_TEXT -> DesktopRailPresentation.CLASSIC", shell.squash(source.mask_kotlin(MODEL_TEXT)))


# --------------------------------------------------------------------------------------
# Rail
# --------------------------------------------------------------------------------------

class RailSourceTest(unittest.TestCase):
    def test_every_row_is_the_kit_nav_row_and_the_rail_has_one_clickable_of_its_own(self):
        self.assertEqual(1, len(re.findall(r"\bDesktopNavRow\(", RAIL_CODE)))
        self.assertEqual(1, len(re.findall(r"(?m)^\s+\.clickable\(", RAIL_CODE)), "the search row only")
        self.assertIsNone(re.search(r"\b(?:combinedClickable|selectable|toggleable|triStateToggleable|onLongClick|detectTapGestures|"
                                    r"clickableWithoutRipple)\b", RAIL_CODE))
        self.assertEqual(0, len(re.findall(r"\bpointerInput\(", RAIL_CODE)))
        self.assertEqual(1, len(re.findall(r"\.onPointerEvent\(", RAIL_CODE)), "the right-click press only")
        self.assertEqual(2, len(re.findall(r"\bDesktopIconButton\(|\bDesktopTooltipBox\(", RAIL_CODE)),
                         "the toggle button and the one tooltip wrapper of the search row")

    def test_the_tooltip_wrapper_adds_no_action_and_the_search_row_keeps_one_structure(self):
        block = function_block(RAIL_CODE, "DesktopRailSearchRow")
        self.assertEqual(1, block.count("DesktopTooltipBox("))
        self.assertEqual(1, block.count(".clickable("))
        flat = shell.squash(block)
        self.assertIn("DesktopTooltipBox(text = if (DESKTOP_RAIL_SHORTCUT_HINTS) DesktopShellStrings.searchTooltip() else DesktopShellStrings.SEARCH, "
                      "modifier = modifier, enabled = collapsed, positioning = TooltipAnchorPosition.Right,) {", flat)
        self.assertIn(".semantics { if (collapsed) contentDescription = DesktopShellStrings.SEARCH }", flat)
        # The hint is outside the semantics, so the name is the word alone; the word is the merged text of the expanded row.
        self.assertIn("Text(DesktopShellStrings.SEARCH_HINT, Modifier.clearAndSetSemantics { }", flat)
        self.assertEqual(1, block.count("contentDescription = DesktopShellStrings"))

    def test_the_accessible_names_the_fixtures_use_are_kept(self):
        self.assertIn('const val WORDMARK = "BiliPai"', STRINGS)
        self.assertIn('const val SEARCH = "搜索"', STRINGS)
        self.assertIn('DesktopRailItem.DOWNLOADS -> "下载与离线"', STRINGS)
        flat = RAIL_FLAT
        self.assertEqual(1, flat.count(".semantics { contentDescription = DesktopShellStrings.WORDMARK }"))
        self.assertEqual(1, flat.count(".semantics { contentDescription = DesktopShellStrings.RAIL_NAME }"))
        # The wordmark and the search word are plain text (the merged name of today's buttons), not descriptions that duplicate them.
        self.assertIn("Text(DesktopShellStrings.WORDMARK, color = colors.onSurface,", flat)
        self.assertIn("Text(DesktopShellStrings.SEARCH, Modifier.weight(1f), color = colors.onSurfaceVariant,", flat)
        # A collapsed row keeps its label as the name (the kit does it), and only the unread row overrides its name.
        self.assertEqual(1, flat.count("description = if (row.unreadCount > 0) DesktopShellStrings.unreadAnnouncement(row.label, row.unreadCount) else null,"))
        # Three descriptions are set by the rail itself: the app name, the navigation name and the collapsed search button.
        self.assertEqual(3, RAIL_CODE.count("contentDescription = DesktopShellStrings"))

    def test_the_fixtures_still_look_for_the_three_names_with_one_action_on_the_search_row(self):
        text = source.read(FIXTURE_KT)
        self.assertIn('listOf("BiliPai", "搜索", "下载与离线").all { label -> children.any { it.accessibleName == label } }', text)
        self.assertIn('listOf("BiliPai", "搜索").all { label -> children.any { it.accessibleName == label && visible(it) } }', text)
        self.assertIn('hasLabel(it, "搜索") && visible(it) &&', text)
        self.assertIn("(it.accessibleAction?.accessibleActionCount ?: 0) == 1", text)

    def test_roving_focus_makes_one_tab_stop(self):
        flat = RAIL_FLAT
        for needle in ("val tabStop = desktopRailTabStop(stopCount, roving, if (selectedIndex < 0) null else selectedIndex + DESKTOP_RAIL_FIRST_ROW_STOP)",
                       "this.focusRequester(requesters[stop]).focusProperties { this.canFocus = stop == tabStop }",
                       "val requesters = remember(stopCount) { List(stopCount) { FocusRequester() } }",
                       "val stopCount = DESKTOP_RAIL_FIRST_ROW_STOP + rows.size",
                       "val target = desktopRailRovingTarget(stopCount, tabStop, rovingKey)"):
            self.assertEqual(1, flat.count(needle), needle)
        self.assertEqual(1, len(re.findall(r"\.onKeyEvent\s*\{", RAIL_CODE)))
        self.assertEqual(0, len(re.findall(r"\bonPreviewKeyEvent\b", RAIL_CODE)))
        # The calls pass the list of requesters first; the definition names its parameter with a colon.
        self.assertEqual(3, len(re.findall(r"\.desktopRailStop\(requesters,", RAIL_CODE)))
        # The three kinds of stop: the toggle, the search row and the rows.
        for needle in ("modifier = Modifier.desktopRailStop(requesters, DESKTOP_RAIL_STOP_HEADER, tabStop),",
                       "modifier = Modifier.padding(horizontal = sidePadding).desktopRailStop(requesters, DESKTOP_RAIL_STOP_SEARCH, tabStop),",
                       "modifier = Modifier.desktopRailStop(requesters, DESKTOP_RAIL_FIRST_ROW_STOP + rows.indexOf(row), tabStop),"):
            self.assertEqual(1, flat.count(needle), needle)
        self.assertIn("private const val DESKTOP_RAIL_STOP_HEADER = 0", RAIL_CODE)
        self.assertIn("private const val DESKTOP_RAIL_STOP_SEARCH = 1", RAIL_CODE)
        self.assertIn("private const val DESKTOP_RAIL_FIRST_ROW_STOP = 2", RAIL_CODE)

    def test_the_arrow_keys_are_unmodified_keys_only_and_the_focus_request_never_throws_into_the_composition(self):
        block = function_block(RAIL_CODE, "desktopRailRovingKeyOf")
        flat = shell.squash(block)
        for needle in ("event.type != KeyEventType.KeyDown", "event.isCtrlPressed", "event.isAltPressed", "event.isMetaPressed",
                       "event.isShiftPressed", "Key.DirectionUp -> DesktopRailRovingKey.UP", "Key.DirectionDown -> DesktopRailRovingKey.DOWN",
                       "Key.MoveHome -> DesktopRailRovingKey.HOME", "Key.MoveEnd -> DesktopRailRovingKey.END", "else -> null"):
            self.assertIn(needle, flat, needle)
        self.assertIn("catch (_: IllegalStateException)", RAIL_CODE)
        self.assertIn("requesters.getOrNull(target)?.requestFocus()", RAIL_FLAT)
        # Enter and Space are the rows' own: the rail claims neither.
        self.assertNotIn("Key.Enter", RAIL_CODE)
        self.assertNotIn("Key.Spacebar", RAIL_CODE)

    def test_every_click_keeps_the_owner_guard_and_the_admitted_push_entry(self):
        block = function_block(RAIL_CODE, "desktopRailActivate")
        flat = shell.squash(block)
        self.assertTrue(flat.index("if (!routes.owns()) return") < flat.index("when (row.entry.item)"))
        self.assertEqual(1, block.count("routes.push("))
        self.assertIn("DesktopRailItem.DOWNLOADS -> { routes.push(BiliPaiNavKey.DownloadList) }", flat)
        self.assertIn("DesktopRailItem.ACCOUNT_SWITCH -> { binding.accountSwitcher?.invoke() }", flat)
        self.assertIn("DesktopRailClick.HOME_DOUBLE_TAP -> binding.actions.homeDoubleTap()", flat)
        self.assertIn("DesktopRailClick.DYNAMIC_DOUBLE_TAP -> binding.actions.dynamicDoubleTap()", flat)
        self.assertIn("DesktopRailClick.NAVIGATE -> onItemClick(item)", flat)
        self.assertIn("when (desktopRailClick(row.entry.item, row.selected))", flat)
        # The tab push is the one the Root stack passes in; the rail never touches the stack itself.
        self.assertEqual(1, len(re.findall(r"\broutes\.push\(", RAIL_CODE)))
        self.assertIsNone(re.search(r"\b(?:routes\.stack|\.stack\.add|\.stack\.remove|navigate\()", RAIL_CODE))
        self.assertIn("onSearch = { if (routes.owns()) binding.actions.searchClick() },", RAIL_FLAT)
        # The call in the Root stack is the existing admitted push.
        stack = shell.squash(source.mask_kotlin(source.read(source.K / "ui/DesktopOriginalRootStack.kt")))
        self.assertIn("DesktopOriginalRootChrome(routes, pages, chrome, currentItem, visibleItems, { item -> routes.push(bottomPagerNavKeyForItem(item)) })", stack)

    def test_the_selection_is_the_existing_rule_and_the_rows_come_from_the_pure_model(self):
        flat = RAIL_FLAT
        self.assertEqual(1, flat.count("val selected = item != null && desktopRailItemSelected(onMainHost, currentItem.name, item.name, legacyRoute, item.route)"))
        self.assertEqual(1, flat.count("val entries = remember(names, hasAccountSwitcher) { desktopRailEntries(names, hasAccountSwitcher) }"))
        self.assertIn("val onMainHost = key == BiliPaiNavKey.MainHost", flat)
        self.assertIn("val legacyRoute = key.toLegacyRoute()", flat)
        self.assertIn("label = item?.label ?: entry.item?.let { DesktopShellStrings.railLabel(it) }.orEmpty(),", flat)
        self.assertIn("icon = desktopRailIcon(entry, item, selected),", flat)
        self.assertIn("item != null -> resolveMaterialBottomBarIcon(item, selected)", flat)
        # The old expression is still in the classic block, and the model keeps its own copy of it.
        self.assertIn("key == BiliPaiNavKey.MainHost && item == currentItem || key.toLegacyRoute() == item.route", CHROME)
        self.assertIn("onMainHost && itemName == currentItemName || currentLegacyRoute == itemRoute", shell.squash(source.mask_kotlin(MODEL_TEXT)))

    def test_rows_are_grouped_in_display_order_with_the_footer_pinned_below_the_scrolling_middle(self):
        flat = RAIL_FLAT
        self.assertIn("listOf(DesktopRailGroup.BROWSE, DesktopRailGroup.LIBRARY).forEachIndexed { index, group ->", flat)
        self.assertIn("val footer = rows.filter { it.entry.group == DesktopRailGroup.FOOTER }", flat)
        self.assertEqual(1, len(re.findall(r"\.verticalScroll\(", RAIL_CODE)), "only the middle scrolls")
        self.assertEqual(1, len(re.findall(r"\bDesktopKitVerticalScrollbar\(", RAIL_CODE)))
        self.assertLess(flat.index("DesktopKitVerticalScrollbar(scroll)"), flat.index("val footer ="))

    def test_the_row_keys_sit_at_the_two_call_sites_not_inside_the_row(self):
        # A key inside DesktopRailItemRow sits inside the group that the call itself is, so it cannot move state between siblings: when
        # the account switcher row appears or goes, the footer rows lost their state and a focused one lost its focus. The key has to wrap
        # the call, in the loop of the groups and in the loop of the footer.
        flat = RAIL_FLAT
        self.assertEqual(2, len(re.findall(r"androidx\.compose\.runtime\.key\(", RAIL_CODE)))
        self.assertEqual(2, flat.count("androidx.compose.runtime.key(desktopRailRowKey(row)) { DesktopRailItemRow(row, presentation, rows, requesters, tabStop, onClick = {"))
        self.assertEqual(2, flat.count("DesktopRailItemRow(row, presentation, rows, requesters, tabStop, onClick = {"))
        self.assertIn("private fun desktopRailRowKey(row: DesktopRailRowState): String? = row.entry.item?.name ?: row.entry.bottomNavName", flat)
        self.assertEqual(0, len(re.findall(r"\bkey\(", function_block(RAIL_CODE, "DesktopRailItemRow"))))
        # The first call site is the groups loop, the second the footer loop.
        self.assertLess(flat.index("inGroup.forEach { row -> androidx.compose.runtime.key("), flat.index("DesktopKitVerticalScrollbar(scroll)"))
        self.assertGreater(flat.index("footer.forEach { row -> androidx.compose.runtime.key("), flat.index("val footer ="))

    def test_the_unread_badge_is_the_pure_kind_with_a_pill_when_expanded_and_a_dot_when_collapsed(self):
        flat = RAIL_FLAT
        self.assertEqual(1, flat.count("val badge = desktopRailBadgeKind(row.unreadCount, presentation)"))
        self.assertIn("badge = if (badge == DesktopRailBadgeKind.NONE) null else { { DesktopBadge(text = if (badge == DesktopRailBadgeKind.PILL) "
                      "desktopRailBadgeText(row.unreadCount) else null) } },", flat)
        self.assertIn("unreadCount = if (entry.item == DesktopRailItem.DYNAMIC) unreadCount else 0,", flat)
        self.assertEqual(1, len(re.findall(r"\bbinding\.dynamicUnreadCount\(\)", RAIL_CODE)))
        self.assertIn("val unreadCount = binding.dynamicUnreadCount()", flat)

    def test_tooltips_follow_the_mode_collapsed_with_the_label_expanded_with_the_shortcut_after_the_long_delay(self):
        flat = RAIL_FLAT
        self.assertIn("tooltip = if (collapsed) DesktopShellStrings.collapsedTooltip(row.label, number) else DesktopShellStrings.expandedTooltip(number),", flat)
        self.assertIn("tooltipDelayMillis = if (collapsed) DESKTOP_KIT_TOOLTIP_DELAY_MS else DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS,", flat)
        self.assertEqual(CONTRACT["tokens"]["tooltip_delay_ms"]["rail_expanded"], 700)
        tokens = shell.int_constants(source.read(source.K / "ui/kit/DesktopKitTokens.kt"))
        self.assertEqual(700, tokens["DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS"])
        self.assertEqual(500, tokens["DESKTOP_KIT_TOOLTIP_DELAY_MS"])
        self.assertIn("val number = if (DESKTOP_RAIL_SHORTCUT_HINTS) row.entry.item?.let { desktopRailNumberOf(it) } else null", flat)
        self.assertIn("shortcut = if (DESKTOP_RAIL_SHORTCUT_HINTS) DesktopShellStrings.RAIL_TOGGLE_SHORTCUT else null,", flat)

    def test_the_rail_names_its_keys_because_the_shortcut_host_of_slice_s2_exists(self):
        # Slice S2 set the constant to true together with the host (Ctrl+B, Ctrl+K, Ctrl+1 to 9) and updated this pin (it said false
        # while S1 stood alone). Every place the rail shows or names a key still sits behind the constant, and there are no others.
        self.assertIn("internal const val DESKTOP_RAIL_SHORTCUT_HINTS = true\n", MODEL_TEXT)
        host = source.read(source.K / "ui/DesktopShellShortcutHost.kt")
        self.assertIn("internal class DesktopShellShortcutHost(", host)
        self.assertIn("LocalDesktopShellShortcutHost provides shellShortcutHost,", source.read(SHELL_KT))
        flat = RAIL_FLAT
        for needle in ("val number = if (DESKTOP_RAIL_SHORTCUT_HINTS) row.entry.item?.let { desktopRailNumberOf(it) } else null",
                       "shortcut = if (DESKTOP_RAIL_SHORTCUT_HINTS) DesktopShellStrings.RAIL_TOGGLE_SHORTCUT else null,",
                       "text = if (DESKTOP_RAIL_SHORTCUT_HINTS) DesktopShellStrings.searchTooltip() else DesktopShellStrings.SEARCH,",
                       "if (DESKTOP_RAIL_SHORTCUT_HINTS) { Text(DesktopShellStrings.SEARCH_HINT, Modifier.clearAndSetSemantics { },"):
            self.assertEqual(1, flat.count(needle), needle)
        self.assertEqual(4, len(re.findall(r"\bDESKTOP_RAIL_SHORTCUT_HINTS\b", RAIL_CODE)))
        for name in (r"RAIL_TOGGLE_SHORTCUT", r"searchTooltip\(\)", r"SEARCH_HINT", r"desktopRailNumberOf\("):
            self.assertEqual(1, len(re.findall(r"\b" + name, RAIL_CODE)), name)
        self.assertEqual(0, len(re.findall(r"\bSEARCH_SHORTCUT\b", RAIL_CODE)))

    def test_the_right_click_menu_is_the_pure_menu_and_collapse_and_expand_are_the_shells_toggle(self):
        flat = RAIL_FLAT
        self.assertEqual(1, len(re.findall(r"\bDesktopContextMenu\(", RAIL_CODE)))
        self.assertEqual(1, len(re.findall(r"\.isSecondaryPressed\b", re.sub(r"(?m)^import .*$", "", RAIL_CODE))))
        self.assertIn("if (event.buttons.isSecondaryPressed && desktopRailMenuActions(shell.mode, presentation).isNotEmpty()) {", flat)
        self.assertIn("desktopRailMenuActions(shell.mode, presentation).forEach { action ->", flat)
        self.assertIn("label = DesktopShellStrings.railMenuLabel(action),", flat)
        # Collapse and expand are the toggle of the shell (the first menu entry is the toggle, so a window too narrow to expand is
        # answered in one place); the way back to automatic is the one explicit write of the rail.
        self.assertEqual(1, flat.count("menuPointer = null if (action == DesktopRailMenuAction.RESTORE_AUTO) shell.setMode(action.mode) else shell.toggle()"))
        self.assertEqual(1, flat.count("val toggle: () -> Unit = { roving = DESKTOP_RAIL_STOP_HEADER shell.toggle() }"))
        self.assertIn("onClick = toggle,", flat)
        self.assertEqual(1, len(re.findall(r"\bshell\.setMode\(", RAIL_CODE)))
        self.assertEqual(2, len(re.findall(r"\bshell\.toggle\(\)", RAIL_CODE)))
        # The rail decides nothing about the width: it never reads the toggle rule of the model.
        self.assertIsNone(re.search(r"\bdesktopRailToggle\w*\(", RAIL_CODE))
        # The box that gets the press is the parent layout of the menu call, so the pointer needs no conversion.
        press = flat.index("onPointerEvent(PointerEventType.Press)")
        menu = flat.index("DesktopContextMenu(")
        self.assertLess(press, menu)
        self.assertEqual(1, flat.count("Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press)"))

    def test_the_panel_is_glass_with_the_contract_metrics_and_an_8_dp_margin_around_it(self):
        flat = RAIL_FLAT
        self.assertIn("Box(modifier.width(presentation.footprintDp.dp).fillMaxHeight().padding(DESKTOP_RAIL_MARGIN_DP.dp)) {", flat)
        self.assertIn("DesktopWindowsGlassSurface(Modifier.fillMaxSize(), shape = RoundedCornerShape(DESKTOP_RAIL_PANEL_RADIUS_DP.dp)) {", flat)
        self.assertIn(".height(DESKTOP_RAIL_HEADER_DP.dp)", flat)
        self.assertIn("val sidePadding = if (collapsed) 4.dp else 8.dp", flat)
        self.assertEqual(1, len(re.findall(r"\bDesktopWindowsGlassSurface\(", RAIL_CODE)))

    def test_the_rail_is_a_pure_view_of_its_arguments(self):
        for pattern in (r"\bSwingPanel\(", r"\bMpvPlayer\(", r"\bassembly\.", r"\bnative\.", r"\bComposeWindow\b", r"\bComposeDialog\b",
                        r"LocalDesktopWindowsUx", r"\.submit\("):
            self.assertIsNone(re.search(pattern, RAIL_CODE), pattern)
        signature = shell.squash(function_block(RAIL_CODE, "DesktopWindowsNavigationRail"))
        self.assertIn("shell: DesktopWindowsShellRail, rows: List<DesktopRailRowState>, onSearch: () -> Unit, "
                      "onRowClick: (DesktopRailRowState) -> Unit, modifier: Modifier = Modifier,", signature)


# --------------------------------------------------------------------------------------
# Now-playing strip
# --------------------------------------------------------------------------------------

class NowPlayingSourceTest(unittest.TestCase):
    def test_the_strip_reads_no_state_and_calls_no_player_only_plain_lambdas(self):
        flat = shell.squash(function_block(BAR_CODE, "DesktopWindowsNowPlayingBar"))
        self.assertIn("title: String, playing: Boolean, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, "
                      "onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,", flat)
        for pattern in (r"\bDesktopOriginalAudioNowPlayingBinding\b", r"\bDesktopOriginalNowPlayingNavigation\b", r"\bMpvPlayer\b",
                        r"\bassembly\.", r"\bnative\.", r"\bcollectAsState\b", r"\bremember\b", r"\bLaunchedEffect\b", r"\bListenAudioSession\b",
                        r"\bSnackbar\w*"):
            self.assertIsNone(re.search(pattern, BAR_CODE), pattern)

    def test_five_buttons_in_the_order_of_the_old_bar_with_the_old_names(self):
        flat = shell.squash(function_block(BAR_CODE, "DesktopWindowsNowPlayingBar"))
        order = [flat.index(needle) for needle in (
            "DesktopShellStrings.NOW_PLAYING_PREVIOUS, Icons.Filled.SkipPrevious, onPrevious",
            "if (playing) DesktopShellStrings.NOW_PLAYING_PAUSE else DesktopShellStrings.NOW_PLAYING_PLAY",
            "DesktopShellStrings.NOW_PLAYING_NEXT, Icons.Filled.SkipNext, onNext",
            "DesktopShellStrings.NOW_PLAYING_OPEN, Icons.Filled.OpenInFull, onOpen",
            "DesktopShellStrings.NOW_PLAYING_CLOSE, Icons.Filled.Close, onClose")]
        self.assertEqual(sorted(order), order)
        # Five calls and the one definition.
        self.assertEqual(6, len(re.findall(r"\bDesktopNowPlayingButton\(", BAR_CODE)))
        self.assertIn("if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, onPlayPause, large = true,", flat)
        for name, text in (("NOW_PLAYING_PREVIOUS", "上一首"), ("NOW_PLAYING_PLAY", "播放"), ("NOW_PLAYING_PAUSE", "暂停"),
                           ("NOW_PLAYING_NEXT", "下一首"), ("NOW_PLAYING_OPEN", "打开播放器"), ("NOW_PLAYING_CLOSE", "关闭")):
            self.assertEqual(1, STRINGS.count('const val %s = "%s"' % (name, text)), name)
            # The classic audio row still spells the same words.
            if text in ("播放", "暂停"):
                self.assertEqual(1, CHROME.count('Text(if (snapshot.isPlaying) "暂停" else "播放")'))
            else:
                self.assertEqual(1, CHROME.count('{ Text("%s") }' % text), text)

    def test_each_button_is_one_kit_icon_button_inside_the_kit_tooltip_that_adds_no_action(self):
        block = shell.squash(function_block(BAR_CODE, "DesktopNowPlayingButton"))
        self.assertEqual(1, len(re.findall(r"\bDesktopIconButton\(", BAR_CODE)))
        self.assertEqual(1, len(re.findall(r"\bDesktopTooltipBox\(", BAR_CODE)))
        self.assertIn("DesktopTooltipBox(text = label, positioning = TooltipAnchorPosition.Above) {", block)
        self.assertIn("DesktopIconButton(onClick = onClick, label = label, large = large, tooltip = null) {", block)
        self.assertIsNone(re.search(r"\b(?:clickable|combinedClickable|selectable|toggleable|pointerInput)\b", BAR_CODE))

    def test_the_strip_is_a_56_dp_glass_strip_with_margins(self):
        flat = shell.squash(function_block(BAR_CODE, "DesktopWindowsNowPlayingBar"))
        self.assertIn("Box(modifier.fillMaxWidth().padding(DESKTOP_RAIL_MARGIN_DP.dp)) {", flat)
        self.assertIn("Modifier.fillMaxWidth().height(DESKTOP_RAIL_NOW_PLAYING_BAR_DP.dp),", flat)
        self.assertIn("shape = RoundedCornerShape(DESKTOP_KIT_RADIUS_PANEL_DP.dp),", flat)
        self.assertEqual(1, len(re.findall(r"\bDesktopWindowsGlassSurface\(", BAR_CODE)))
        self.assertEqual(56, CONSTS["DESKTOP_RAIL_NOW_PLAYING_BAR_DP"])


# --------------------------------------------------------------------------------------
# Pure additions: mirrors against independent oracles
# --------------------------------------------------------------------------------------

def oracle_roving(count, current, key):
    """The stop after a key, from the prose of spec 3.2: Up and Down step one stop and stop at the ends, Home and End jump."""
    if count <= 0:
        return -1
    if key == "HOME":
        return 0
    if key == "END":
        return count - 1
    if key == "UP":
        return 0 if current <= 0 else min(current - 1, count - 1)
    if current < 0:
        return 0  # nothing focused yet: Down starts at the first stop
    return min(current + 1, count - 1)


def oracle_tab_stop(count, roving, selected):
    if count <= 0:
        return -1
    for candidate in (roving, selected, 0):
        if candidate is not None:
            return max(0, min(candidate, count - 1))
    raise AssertionError("unreachable")


def oracle_zoom(density, system):
    """The zoom in percent as an exact fraction of the two Float values, rounded half to even; None for anything that is not a
    positive finite density."""
    if system is None:
        return None
    values = []
    for raw in (density, system):
        value = shell.f32(raw)
        if value != value or value in (float("inf"), float("-inf")):
            return None
        values.append(Fraction(value))
    scaled, base = values
    if scaled <= 0 or base <= 0:
        return None
    return int(round(scaled / base * 100))


RAIL_MODES = ("AUTO", "EXPANDED", "COLLAPSED", "CLASSIC_TEXT")
PRESENTATIONS = ("expanded", "collapsed", "classic")


def oracle_toggle_effective(mode, presentation, width):
    """Spec 3.3, from the prose: the toggle writes the opposite explicit mode, and that only helps when it changes what the rail shows.
    Collapsing always does (the COLLAPSED mode is collapsed at any width). Expanding does only when the window reaches the re-expand
    width of the EXPANDED mode, because the rail is collapsed now and the rule keeps a collapsed rail collapsed below it. Nothing is
    written for the classic block, or when the mode in force already is the opposite."""
    if presentation == "classic":
        return False
    if presentation == "expanded":
        return mode != "COLLAPSED"
    return mode != "EXPANDED" and width >= CONTRACT["rail"]["thresholds"]["EXPANDED"]["reexpand_at"]


class ModelMirrorTest(unittest.TestCase):
    def test_the_new_metrics_are_the_contract_numbers_and_a_footprint_is_a_panel_plus_two_margins(self):
        rail = CONTRACT["rail"]
        metrics = rail["metrics"]
        self.assertEqual(rail["panel_expanded"], CONSTS["DESKTOP_RAIL_PANEL_EXPANDED_DP"])
        self.assertEqual(rail["panel_collapsed"], CONSTS["DESKTOP_RAIL_PANEL_COLLAPSED_DP"])
        self.assertEqual(rail["margin"], CONSTS["DESKTOP_RAIL_MARGIN_DP"])
        self.assertEqual(rail["panel_radius"], CONSTS["DESKTOP_RAIL_PANEL_RADIUS_DP"])
        self.assertEqual(metrics["header"], CONSTS["DESKTOP_RAIL_HEADER_DP"])
        self.assertEqual(metrics["search_row"], CONSTS["DESKTOP_RAIL_SEARCH_ROW_DP"])
        self.assertEqual(metrics["search_collapsed"], CONSTS["DESKTOP_RAIL_SEARCH_COLLAPSED_DP"])
        self.assertEqual(metrics["now_playing_bar"], CONSTS["DESKTOP_RAIL_NOW_PLAYING_BAR_DP"])
        self.assertEqual(rail["footprint"]["expanded"], CONSTS["DESKTOP_RAIL_PANEL_EXPANDED_DP"] + 2 * CONSTS["DESKTOP_RAIL_MARGIN_DP"])
        self.assertEqual(rail["footprint"]["collapsed"], CONSTS["DESKTOP_RAIL_PANEL_COLLAPSED_DP"] + 2 * CONSTS["DESKTOP_RAIL_MARGIN_DP"])
        self.assertEqual((200, 56, 8, 16, 48, 36, 40, 56), tuple(CONSTS[name] for name in (
            "DESKTOP_RAIL_PANEL_EXPANDED_DP", "DESKTOP_RAIL_PANEL_COLLAPSED_DP", "DESKTOP_RAIL_MARGIN_DP", "DESKTOP_RAIL_PANEL_RADIUS_DP",
            "DESKTOP_RAIL_HEADER_DP", "DESKTOP_RAIL_SEARCH_ROW_DP", "DESKTOP_RAIL_SEARCH_COLLAPSED_DP", "DESKTOP_RAIL_NOW_PLAYING_BAR_DP")))
        # The collapsed row is the panel less the 4 dp padding of the composable on both sides and must hold the 12 dp padding of
        # the kit row and the 20 dp icon.
        self.assertGreaterEqual(CONSTS["DESKTOP_RAIL_PANEL_COLLAPSED_DP"] - 8, 24 + CONTRACT["tokens"]["icon"])
        self.assertGreaterEqual(CONSTS["DESKTOP_RAIL_SEARCH_COLLAPSED_DP"] - 20, CONTRACT["tokens"]["icon"])

    def test_the_metrics_block_stays_marked_and_ascii(self):
        block = shell.marked_block(MODEL_TEXT, "RAIL-METRICS")
        self.assertEqual(12, len(re.findall(r"internal const val DESKTOP_RAIL_\w+ = \d+", block)))
        self.assertTrue(MODEL_TEXT.isascii())

    def test_roving_targets_agree_with_the_oracle_for_every_count_stop_and_key(self):
        checked = 0
        for count in range(-1, 16):
            for current in range(-4, 20):
                for key in shell.ROVING_KEYS:
                    self.assertEqual(oracle_roving(count, current, key), shell.rail_roving_target_kt(count, current, key), (count, current, key))
                    checked += 1
        self.assertEqual(17 * 24 * 4, checked)
        self.assertEqual([0, 0, 1, 4, 4], [shell.rail_roving_target_kt(5, 0, "UP"), shell.rail_roving_target_kt(5, 0, "HOME"),
                                           shell.rail_roving_target_kt(5, 0, "DOWN"), shell.rail_roving_target_kt(5, 4, "DOWN"),
                                           shell.rail_roving_target_kt(5, 0, "END")])

    def test_walking_with_the_keys_reaches_every_stop_and_never_leaves_the_list(self):
        for count in range(1, 13):
            stop, seen = 0, {0}
            for _ in range(count + 3):
                stop = shell.rail_roving_target_kt(count, stop, "DOWN")
                seen.add(stop)
            self.assertEqual(set(range(count)), seen)
            self.assertEqual(count - 1, stop)
            for _ in range(count + 3):
                stop = shell.rail_roving_target_kt(count, stop, "UP")
            self.assertEqual(0, stop)

    def test_the_tab_stop_agrees_with_the_oracle(self):
        for count in range(-1, 14):
            for roving in (None, -3, 0, 1, count - 1, count + 4):
                for selected in (None, 0, 2, count - 1, count + 4):
                    self.assertEqual(oracle_tab_stop(count, roving, selected), shell.rail_tab_stop_kt(count, roving, selected), (count, roving, selected))
        self.assertEqual([0, 3, 2, 4, 0, 4, -1], [shell.rail_tab_stop_kt(5, None, None), shell.rail_tab_stop_kt(5, None, 3),
                                                  shell.rail_tab_stop_kt(5, 2, 3), shell.rail_tab_stop_kt(5, 9, None),
                                                  shell.rail_tab_stop_kt(5, -4, None), shell.rail_tab_stop_kt(5, None, 40),
                                                  shell.rail_tab_stop_kt(0, 0, 0)])

    def test_the_toggle_is_effective_only_where_its_write_changes_what_the_rail_shows(self):
        checked = 0
        for mode in RAIL_MODES:
            for presentation in PRESENTATIONS:
                for width in range(0, 5001):
                    expected = oracle_toggle_effective(mode, presentation, width)
                    self.assertEqual(expected, shell.rail_toggle_effective_kt(LIMITS, mode, presentation, width), (mode, presentation, width))
                    checked += 1
        self.assertEqual(4 * 3 * 5001, checked)

    def test_the_default_window_cannot_expand_the_rail_and_the_toggle_never_gets_stuck(self):
        reexpand = CONTRACT["rail"]["thresholds"]["EXPANDED"]["reexpand_at"]
        for width in (1088, 1075):  # Main.kt opens the window 1360 dp wide: 1088 dp of content at the 125% default zoom
            self.assertEqual("collapsed", shell.rail_presentation_kt(LIMITS, "AUTO", width))
            self.assertFalse(shell.rail_toggle_effective_kt(LIMITS, "AUTO", "collapsed", width), width)
            # What the toggle used to do: EXPANDED is still the collapsed rail here, and then the toggle has no target at all.
            self.assertEqual("collapsed", shell.rail_presentation_kt(LIMITS, "EXPANDED", width, "collapsed"))
            self.assertIsNone(shell.rail_toggle_mode_kt("EXPANDED", "collapsed"))
            self.assertFalse(shell.rail_toggle_effective_kt(LIMITS, "EXPANDED", "collapsed", width), width)
        self.assertFalse(shell.rail_toggle_effective_kt(LIMITS, "AUTO", "collapsed", reexpand - 1))
        self.assertTrue(shell.rail_toggle_effective_kt(LIMITS, "AUTO", "collapsed", reexpand))
        self.assertTrue(shell.rail_toggle_effective_kt(LIMITS, "COLLAPSED", "collapsed", reexpand))
        # Collapsing is always visible, at any width.
        self.assertTrue(all(shell.rail_toggle_effective_kt(LIMITS, "AUTO", "expanded", width) for width in range(0, 5001, 13)))
        self.assertFalse(any(shell.rail_toggle_effective_kt(LIMITS, "CLASSIC_TEXT", "classic", width) for width in range(0, 5001, 13)))

    def test_the_zoom_percent_is_recovered_for_every_zoom_step_and_system_scale(self):
        zoom = CONTRACT["tokens"]["zoom_percent"]
        for system in (1.0, 1.25, 1.5, 1.75, 1.875, 2.0, 2.25, 2.5):
            for percent in range(zoom["min"], zoom["max"] + 1, zoom["step"]):
                # desktopWindowsScaledDensity: system.density * percent / 100f in Float arithmetic.
                scaled = shell.f32(shell.f32(shell.f32(system) * percent) / 100.0)
                self.assertEqual(percent, shell.zoom_percent_kt(scaled, system), (system, percent))
                self.assertEqual(percent, oracle_zoom(scaled, system), (system, percent))

    def test_the_zoom_percent_refuses_what_is_not_a_density(self):
        inf, nan = float("inf"), float("nan")
        for density, system in ((1.5, None), (1.5, 0.0), (1.5, -1.0), (1.5, nan), (1.5, inf), (0.0, 1.5), (-1.0, 1.5), (nan, 1.5), (inf, 1.5)):
            self.assertIsNone(shell.zoom_percent_kt(density, system), (density, system))
            self.assertIsNone(oracle_zoom(density, system), (density, system))
        self.assertEqual(100, shell.zoom_percent_kt(1.0, 1.0))
        self.assertEqual(125, shell.zoom_percent_kt(1.25, 1.0))

    def test_the_zoom_toast_shows_only_a_change_between_two_known_reads(self):
        for previous in (None, 50, 125, 200):
            for current in (None, 50, 125, 200):
                expected = current if (previous is not None and current is not None and previous != current) else None
                self.assertEqual(expected, shell.zoom_toast_percent_kt(previous, current), (previous, current))
        self.assertEqual("界面缩放 150%", re.search(r'fun zoomToast\(percent: Int\): String = "([^"]*)"', STRINGS).group(1).replace("${percent}", "150"))

    def test_a_write_error_is_the_rails_own_only_inside_the_window_after_the_rail_wrote(self):
        window = int(re.search(r"const val DESKTOP_RAIL_SAVE_WINDOW_MS = (\d+)L", MODEL_TEXT).group(1))
        self.assertEqual(5000, window)
        for issued in (None, 1000):
            for now in (500, 999, 1000, 1001, 6000, 6001, 20000):
                for error in (False, True):
                    expected = bool(error and issued is not None and 0 <= now - issued <= window)
                    self.assertEqual(expected, shell.rail_write_failure_shown_kt(issued, now, error, window), (issued, now, error))

    def test_the_kotlin_functions_are_the_transliterated_lines(self):
        flat = shell.squash(source.mask_kotlin(MODEL_TEXT))
        for needle in (
            "if (count <= 0) return -1 val last = count - 1 return when (key) { "
            "DesktopRailRovingKey.UP -> (current - 1).coerceIn(0, last) DesktopRailRovingKey.DOWN -> (current + 1).coerceIn(0, last) "
            "DesktopRailRovingKey.HOME -> 0 DesktopRailRovingKey.END -> last }",
            "if (count <= 0) return -1 return (roving ?: selected ?: 0).coerceIn(0, count - 1)",
            "if (systemDensity == null) return null if (!(density > 0f) || !(systemDensity > 0f)) return null "
            "if (density.isInfinite() || systemDensity.isInfinite()) return null "
            "return kotlin.math.round(density.toDouble() / systemDensity.toDouble() * 100.0).toInt()",
            "if (previous != null && current != null && previous != current) current else null",
            "if (!errorPublished || writeIssuedAtMs == null) return false return (nowMs - writeIssuedAtMs) in 0L..DESKTOP_RAIL_SAVE_WINDOW_MS",
            "internal const val DESKTOP_RAIL_SAVE_WINDOW_MS = 5000L",
            "val target = desktopRailToggleMode(mode, presentation) ?: return false "
            "return desktopRailPresentation(target, windowWidthDp, presentation) != presentation",
            "internal const val DESKTOP_RAIL_SAVE_RECHECK_MS = 750L",
            "internal const val DESKTOP_RAIL_SHORTCUT_HINTS = true",
        ):
            self.assertEqual(1, flat.count(needle), needle)

    def test_the_model_stays_pure(self):
        code = source.mask_kotlin(MODEL_TEXT)
        for banned in ("androidx.", "java.awt", "javax.swing", "kotlinx.coroutines", "@Composable"):
            self.assertNotIn(banned, code, banned)
        self.assertEqual(["import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode"], re.findall(r"(?m)^import .*$", MODEL_TEXT))
        for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"!!", r"\bTODO\("):
            self.assertIsNone(re.search(pattern, code), pattern)


# --------------------------------------------------------------------------------------
# Files
# --------------------------------------------------------------------------------------

class FilesTest(unittest.TestCase):
    def test_the_new_files_are_inside_the_armed_scopes_and_clean(self):
        program = {source.rel(path) for path in source.kotlin_files(source.PROGRAM_GLOBS)}
        banned = {source.rel(path) for path in source.kotlin_files(source.BANNED_IMPORT_GLOBS)}
        for name in ("ui/DesktopWindowsShellFrame.kt", "ui/DesktopWindowsNavigationRail.kt", "ui/DesktopWindowsNowPlayingBar.kt",
                     "ui/DesktopShellInsets.kt", "ui/DesktopWindowsRailModel.kt", "ui/DesktopShellStrings.kt"):
            self.assertIn(name, program, name)
        for name in ("ui/DesktopWindowsShellFrame.kt", "ui/DesktopWindowsNavigationRail.kt", "ui/DesktopWindowsNowPlayingBar.kt"):
            self.assertIn(name, banned, name)
        for path in (FRAME_KT, RAIL_KT, BAR_KT, INSETS_KT, MODEL_KT):
            text = source.read(path)
            self.assertEqual([], source.cjk_positions(text), path.name)
            self.assertEqual([], source.banned_imports(text), path.name)
            self.assertEqual([], source.hook_uses(text), path.name)
            self.assertEqual([], source.key_fallback_uses(text), path.name)

    def test_the_kit_nav_row_gained_exactly_one_optional_parameter(self):
        text = source.mask_kotlin(source.read(KIT_CONTROLS_KT))
        block = function_block(text, "DesktopNavRow")
        self.assertIn("    description: String? = null,\n) {", block)
        self.assertEqual(1, block.count(".selectable("))
        self.assertIn("val name = description ?: if (collapsed) label else null", block)

    def test_the_jvm_tests_exist_and_need_no_window(self):
        model_test = source.read(MODEL_TEST_KT)
        for name in ("thePanelMetricsAreTheContractNumbersAndAFootprintIsThePanelPlusItsMargins", "rovingTargetsStopAtBothEndsAndNeverWrap",
                     "rovingStaysInsideTheStopsAndMovesTheWayTheKeySays", "theTabStopIsTheRovingStopThenTheSelectedRowThenTheFirstStop",
                     "theZoomPercentIsReadBackFromTheScaledAndTheSystemDensity", "theZoomToastShowsOnlyAChangeBetweenTwoKnownReads",
                     "aWriteErrorIsTheRailsOwnOnlyJustAfterTheRailWrote", "theToggleAndSearchTooltipsAndTheShortcutsAreTheSpecTexts",
                     "theToggleIsEffectiveOnlyWhereItsWriteChangesWhatTheRailShows",
                     "theDefaultWindowCannotExpandTheRailSoTheToggleWritesNothingThereAndNeverGetsStuck"):
            self.assertEqual(1, model_test.count("@Test fun %s()" % name), name)
        self.assertNotIn("ImageComposeScene", model_test)
        ui = source.read(UI_TEST_KT)
        self.assertIn("ImageComposeScene(360, 640, Density(1f))", ui)
        self.assertIn("check(GraphicsEnvironment.isHeadless())", ui)
        self.assertEqual(5, len(re.findall(r"@Test fun ", ui)))
        # Every action kind is counted, node by node: a tooltip wrapper that added a long click or a custom action would fail.
        for needle in ("SemanticsActions.OnClick, SemanticsActions.OnLongClick", "SemanticsActions.CustomActions",
                       "fun assertOneActivationPerControl()", "if (role == Role.Tab || role == Role.Button) 1 else 0",
                       "fixture.assertOneActivationPerControl()", "toggle = onToggle"):
            self.assertIn(needle, ui, needle)
        self.assertEqual(2, ui.count("fixture.assertOneActivationPerControl()"))
        for needle in ("DesktopWindowsNavigationRail(", "Key.Tab", "Key.DirectionDown", "Key.MoveEnd", "Key.MoveHome", "secondary = true",
                       "assertEquals(2 + rows.size, fixture.actions()", "184f", "56f", "40f"):
            self.assertIn(needle, ui, needle)
        for banned in ("MpvPlayer", "SwingPanel", "ComposeWindow(", "application {"):
            self.assertNotIn(banned, ui, banned)

    def test_the_files_are_lf_only(self):
        source.assert_lf_only(self, FRAME_KT, RAIL_KT, BAR_KT, INSETS_KT, MODEL_KT, STRINGS_KT, CHROME_KT, KIT_CONTROLS_KT, SETTINGS_GROUP_KT,
                              MODEL_TEST_KT, UI_TEST_KT, Path(__file__), Path(shell.__file__))


if __name__ == "__main__":
    unittest.main()
