"""Anchor guard of the ordinary-video leaf (slice V2, spec 4.15 and 10.1). No JVM, no network, nothing is generated.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_leaf_anchor_guard.py

ui/DesktopWindowsVideoPhysicalLeaf.kt is a 1757 line, text-pinned, Root-reserved file that the player slices (V2, V3, V4, V5, V6,
V7) all edit. This suite was written BEFORE the first leaf edit, and it passes on the unedited leaf. It pins what the older Python
pins need and what the spec says must survive every hunk, so a slice that moves one of them fails here with a name, not in an
unrelated suite:

  ExistingPinsTest      every substring and ordering that test_v041_player_cover_source.py, test_extract_upstream_video_feedback_host.py,
                        test_extract_upstream_comment_renderer.py and test_ux_player_policy.py read from the leaf
  MustKeepTest          spec 4.15 "MUST keep": the two cover markers, DesktopVideoCommandPopup(viewportSize, { once, the entry cover once
                        and before actions.overlay(), the confirmed feedback host behind its presentation guard, the like call, the
                        engagement binding key, the comment search section once and no DesktopWindowsVideoComments(
  LegacyBranchTest      the LEGACY layout stays frozen: exactly one DesktopWindowsVideoControlBar( call with its 49 named arguments in order,
                        one top strip, one details panel with its ten arguments in order, the composition order of the whole layout, the
                        accessible names the leaf owns, the hit regions and the focus handling anchors
  FutureOrderingTest    spec 10.1: DesktopPlayerInputLayer( precedes the entry cover and DesktopPlayerOverlay( follows
                        DesktopWindowsVideoSeekPreviewHost( whenever those calls exist (they do not exist yet)
  MutationTest          every check above fails on a text that breaks it, shown on edited copies of the real leaf, so the guard is
                        never vacuous

Not pinned on purpose: `if (engagementBinding?.coin(count, false) == true)`. test_extract_upstream_video_feedback_host.py asserts it
in the leaf, but the leaf has not held it since the coin dialog moved into DesktopWindowsVideoCoinSection; that assertion already
fails on the base of this branch (a pre-existing failure, reported unchanged). Pinning an absent string here would make this guard fail.

A later slice that changes an anchor on purpose (V3 mounts the input layer, V4a the overlay, V6 the key handler, V7 the panel) edits
the matching constant below in the same commit and says so; it never loosens a check. The allowed hunks are L1 to L10 of spec 4.15.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402

LEAF = source.K / "ui/DesktopWindowsVideoPhysicalLeaf.kt"

COVER_MARKER_START = "    // Cover inputs never borrow"
COVER_MARKER_END = "    var showSponsorSegment "
POPUP = "DesktopVideoCommandPopup(viewportSize, {"
ENTRY_COVER = "DesktopWindowsVideoEntryCover("
OVERLAY = "actions.overlay()"
FEEDBACK_CALL = "DesktopWindowsConfirmedVideoFeedback(binding, viewportSize, native.surface, feedbackBounds)"
FEEDBACK_GUARD = "if (presentationAlive && !pipActive)"
ENGAGEMENT_KEY = "remember(assembly, feedbackSource, engagementSubject, presentationAlive, pipActive, brandEvents)"
CONTROL_BAR = "DesktopWindowsVideoControlBar("
DETAILS_PANEL = "DesktopWindowsVideoDetailsPanel("
TOP_STRIP = "DesktopWindowsPlayerSurface("
SEEK_PREVIEW_HOST = "DesktopWindowsVideoSeekPreviewHost("
INPUT_LAYER = "DesktopPlayerInputLayer("
PLAYER_OVERLAY = "DesktopPlayerOverlay("

QUALITIES_LINE = ("qualities = success?.let { value -> value.qualityIds.mapIndexed { index, id -> id to "
                  "(value.qualityLabels.getOrNull(index) ?: id.toString()) } }.orEmpty(),")
SELECTED_QUALITY_LINE = "selectedQuality = success?.currentQuality,"

# The 49 named arguments of the one DesktopWindowsVideoControlBar( call, in order. The call is the LEGACY bar: V2b restyles the
# composable behind it and keeps the names and the signature, so this list stays as it is until the V14 removal.
BAR_ARGUMENTS = (
    "state", "sourceVersion", "enabled", "fullscreen", "detailsOpen", "onInteractionHoldChanged", "onChromePointerInput",
    "hasPrevious", "hasNext", "canPictureInPicture", "speedOptions", "pbpRidgeSamples", "seekPreviewSource", "seekPreviewEnabled",
    "onSeekPreviewChanged", "qualities", "selectedQuality", "canOpenCollection", "canOpenPlaybackQueue", "onOpenCollection",
    "onOpenPlaybackQueue", "canOpenInteraction", "onSendDanmaku", "onShareVideo", "canDownloadAudio", "canSaveCover",
    "onDownloadAudio", "onSaveCover", "canScreenshot", "screenshotBusy", "onScreenshot", "chapters", "chaptersSource",
    "onChapterSeek", "onPlayPause", "onPrevious", "onNext", "onMute", "onVolume", "onSpeed", "onQuality", "onSeek",
    "onPictureInPicture", "onFullscreen", "onDetails", "onOpenIntroduction", "sponsorMoreActions", "sponsorSkip", "enhancement",
)
PANEL_ARGUMENTS = ("modifier", "selectedTab", "onTabChange", "onClose", "current", "loading", "failureContent", "related",
                   "introduction", "comments")

# One entry per anchor of the layout, in the order the composition meets them. Each occurs exactly once. A hoist that keeps the code
# where it was (V2) keeps this order; a slice that really moves a piece edits this list in the same commit.
LAYOUT_ORDER = (
    ("shell box", "BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 8.dp)) {"),
    ("row", "Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {"),
    ("column", "Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (chromeVisible) 6.dp else 0.dp)) {"),
    ("top strip", TOP_STRIP + "Modifier.fillMaxWidth()"),
    ("back name", 'contentDescription = "返回")'),
    ("video box", "BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {"),
    ("command popup", POPUP),
    ("failure content", "if (showPageError) DesktopWindowsPageErrorOverlay("),
    ("entry cover", ENTRY_COVER),
    ("original overlay", OVERLAY),
    ("subtitle host", "VideoSubtitleOverlayHost("),
    ("interactive choice", "DesktopWindowsVideoInteractiveChoiceOverlay("),
    ("sponsor community", "DesktopWindowsSponsorCommunityOverlay("),
    ("next watch", "NextWatchOverlay("),
    ("seek preview host", SEEK_PREVIEW_HOST),
    ("popup end", "}, native.surface)"),
    ("confirmed feedback", FEEDBACK_CALL),
    ("recovering chip", "if (playback.recovering && playback.recoveryMessage != null)"),
    ("control bar", CONTROL_BAR),
    ("details panel", DETAILS_PANEL),
    ("audio language dialog", "audioLanguageMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }"),
    ("coin section", "DesktopWindowsVideoCoinSection(engagementBinding)"),
)

# The hit regions and focus handling of the LEGACY chrome and the viewport, each with the number of times it occurs in the leaf.
HIT_AND_FOCUS_ANCHORS = (
    (1, ".desktopWindowsChromePointerInput(::observeChromePointer)"),
    (1, "onChromePointerInput = ::observeChromePointer,"),
    (1, ".onFocusChanged { topFocused = it.hasFocus }.focusGroup().hoverable(topInteractions)"),
    (1, ".focusRequester(viewportFocus).onFocusChanged {"),
    (1, "viewportKeyboardFocused = it.hasFocus"),
    (1, "}.focusable()) {"),
    (1, "onInteractionHoldChanged = { interaction ->"),
    (1, "chromeActivity(DesktopWindowsFullscreenChromeActivity.CONTROL_HOLD_CHANGED)"),
    (1, "var topFocused by remember(chrome) { mutableStateOf(false) }"),
    (1, "val topHovered by topInteractions.collectIsHoveredAsState()"),
    (1, "var barInteraction by remember(chrome) { mutableStateOf(DesktopWindowsFullscreenChromeInteraction()) }"),
    (1, "val chromeVisible = !fullscreen || chrome.visible"),
    # Slice V6 (the window-level shortcut router) changed three of these on purpose: the one leaf slot of the window key input now takes the
    # existing input first and the router second, the viewport takes the focus once more at page entry (the second requestFocus), and the router
    # and its page-entry focus are new anchors. test_ux_player_keys_source.py pins the rest of what V6 did.
    (1, "windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) }"),
    (1, "val input = DesktopWindowsVideoKeyInput("),
    (1, "val shortcuts = DesktopWindowsVideoShortcutInput("),
    (1, "desktopWindowsRequestPageEntryFocus("),
    (1, "surface.isFocusable = true"),
    (1, "surface.requestFocusInWindow()"),
    (1, "val chromeCanAutoHide = desktopWindowsFullscreenChromeCanAutoHide("),
    (1, "val chrome = remember(assembly, DesktopWindowsFullscreenChromeEntryKey(route), chromeSource) { DesktopWindowsFullscreenChromeState() }"),
)

# The accessible names the leaf itself owns: the strip back button and the loading page back button.
LEAF_NAMES = (
    (1, 'Icon(Icons.Default.ArrowBack, contentDescription = "返回")'),
    (1, 'TextButton(onClick = actions.back) { Text("返回") }'),
    (2, '"返回"'),
)


def read_leaf():
    return source.read(LEAF)


def top_level_split(text):
    """Pieces of text between the commas that sit outside every bracket (strings and comments are masked)."""
    code = source.mask_kotlin(text)
    pieces, depth, begin = [], 0, 0
    for index, char in enumerate(code):
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            pieces.append(text[begin:index])
            begin = index + 1
    pieces.append(text[begin:])
    return [piece for piece in pieces if piece.strip()]


def call_arguments(text, callee, occurrence=0):
    """(name or None, value) of every argument of the occurrence-th call of callee (the name includes its opening parenthesis)."""
    code = source.mask_kotlin(text)
    start = -1
    for _ in range(occurrence + 1):
        start = code.index(callee, start + 1)
    begin = start + len(callee)
    depth, end = 1, begin
    while depth:
        char = code[end]
        depth += (char in "([{") - (char in ")]}")
        end += 1
    arguments = []
    for piece in top_level_split(text[begin:end - 1]):
        named = re.match(r"\s*(\w+)\s*=(?!=)\s*", piece)
        arguments.append((named.group(1), piece[named.end():].strip()) if named else (None, piece.strip()))
    return arguments


def argument_names(text, callee):
    return tuple(name for name, _ in call_arguments(text, callee))


class Findings:
    """Collects the reasons a check fails; a check passes when none were collected."""

    def __init__(self):
        self.items = []

    def need(self, condition, message):
        if not condition:
            self.items.append(message)

    def order(self, text, first, second, message):
        if first in text and second in text:
            self.need(text.index(first) < text.index(second), message)
        else:
            self.items.append(message + " (an anchor is missing)")


def check_cover_pins(leaf):
    """test_v041_player_cover_source.test_actual_ordinary_bv_leaf_consumes_only_its_request_and_admitted_native_frame, replayed."""
    found = Findings()
    if COVER_MARKER_START not in leaf or COVER_MARKER_END not in leaf:
        return ["a cover marker is missing"]
    start = leaf.index(COVER_MARKER_START)
    end = leaf.index(COVER_MARKER_END, start)
    binding = leaf[start:end]
    found.need("load.currentRequest === coverRequest" in binding, "cover binding: load.currentRequest === coverRequest")
    found.need("load.currentLoadRequestToken == coverRequestToken" in binding, "cover binding: request token")
    found.need("load.currentCid == coverRequest.cid" not in binding, "cover binding must not compare the current cid")
    gate = binding.find("assembly.native.admitPlaybackDispatch(expected)")
    found.need(gate >= 0, "cover binding: the admission gate")
    found.need(binding.find("assembly.section.capturePresentationState()") > gate >= 0, "cover binding: presentation capture after the gate")
    found.need(binding.find("native.captureNativeVideoCanvasLease") > gate >= 0, "cover binding: canvas lease after the gate")
    for needle in ("it.lease.peer === captured.lease.peer", "it.lease.playbackRevision == captured.lease.playbackRevision",
                   "?.state?.firstVideoFrameReady == true", "playPlayerFromUserAction(assembly.section)"):
        found.need(needle in binding, "cover binding: " + needle)
    found.need(POPUP in leaf, "the command popup call")
    popup = leaf.find(POPUP)
    call = leaf.find(ENTRY_COVER, popup) if popup >= 0 else -1
    overlay = leaf.find(OVERLAY, popup) if popup >= 0 else -1
    found.need(0 <= call < overlay, "the entry cover is called after the popup and before actions.overlay()")
    found.need(leaf.count(ENTRY_COVER) == 1, "DesktopWindowsVideoEntryCover( occurs exactly once")
    return found.items


def check_feedback_pins(leaf):
    """test_extract_upstream_video_feedback_host.test_actual_leaf_dispatch_and_each_completion_use_confirmed_source_receipts,
    lines 105 to 114 (line 115 already fails on the base and is not pinned, see the module docstring)."""
    found = Findings()
    found.need(leaf.count(FEEDBACK_CALL) == 1, "the confirmed feedback host is called exactly once with its four arguments")
    found.need("engagementBinding?.like()" in leaf, "engagementBinding?.like()")
    read_start = leaf.find("val feedbackSource = assembly.native.current()?.takeIf")
    read_end = leaf.find("val engagementBinding = remember", read_start) if read_start >= 0 else -1
    found.need(read_start >= 0 and read_end > read_start, "the feedback source read precedes the engagement binding")
    if read_start >= 0 and read_end > read_start:
        found.need("feedbackPresentationCurrent()" in leaf[read_start:read_end], "the feedback source is read under feedbackPresentationCurrent()")
        found.need("current() &&" not in leaf[read_start:read_end], "the feedback source read does not use the foreground current() gate")
    found.need(ENGAGEMENT_KEY in leaf, "the engagement binding remember key")
    found.need("val expected = feedbackSource" in leaf, "val expected = feedbackSource")
    index = leaf.find(FEEDBACK_CALL)
    found.need(index >= 0 and FEEDBACK_GUARD in leaf[max(0, index - 150):index],
               "the presentation guard stands within 150 characters before the confirmed feedback host")
    return found.items


def check_comment_pins(leaf):
    """test_extract_upstream_comment_renderer.test_actual_bounded_caller_keeps_one_vm_and_search_entry, the leaf lines."""
    found = Findings()
    found.need(leaf.count("DesktopWindowsCommentSearchSection(") == 1, "DesktopWindowsCommentSearchSection( occurs exactly once")
    found.need("DesktopWindowsVideoComments(" not in leaf, "DesktopWindowsVideoComments( is not called")
    return found.items


def check_quality_pins(leaf):
    """test_ux_player_policy.test_the_leaf_builds_its_quality_list_from_the_same_readbacks (it compares after squashing blanks)."""
    squashed = re.sub(r"\s+", " ", leaf)
    found = Findings()
    found.need(QUALITIES_LINE in squashed, "the bar's qualities argument")
    found.need(SELECTED_QUALITY_LINE in squashed, "the bar's selectedQuality argument")
    found.need("isQualitySwitching" in leaf, "isQualitySwitching is read")
    return found.items


def check_must_keep(leaf):
    """Spec 4.15 MUST keep, beyond what the four replays above already cover."""
    found = Findings()
    found.need(leaf.count(COVER_MARKER_START) == 1, "the cover marker occurs once")
    found.need(leaf.count(COVER_MARKER_END) == 1, "the sponsor marker occurs once")
    found.need(leaf.count(POPUP) == 1, "DesktopVideoCommandPopup(viewportSize, { occurs once")
    found.need(leaf.count(ENTRY_COVER) == 1, "the entry cover occurs once")
    found.order(leaf, ENTRY_COVER, OVERLAY, "the entry cover precedes actions.overlay()")
    found.need(leaf.count(OVERLAY) == 1, "actions.overlay() occurs once")
    found.need(leaf.count(FEEDBACK_CALL) == 1, "the confirmed feedback host occurs once")
    found.need(leaf.count(ENGAGEMENT_KEY) == 1, "the engagement binding key occurs once")
    found.need(leaf.count("engagementBinding?.like()") == 1, "the like call occurs once")
    found.need(leaf.count("DesktopWindowsCommentSearchSection(") == 1, "the comment search section occurs once")
    found.need(leaf.count("class DesktopWindowsVideoActions(") == 1, "DesktopWindowsVideoActions lives at the top of the leaf")
    found.order(leaf, "class DesktopWindowsVideoActions(", "fun DesktopWindowsVideoPhysicalLeaf(", "the actions class precedes the leaf composable")
    return found.items


def check_legacy_branch(leaf):
    found = Findings()
    found.need(leaf.count(CONTROL_BAR) == 1, "the LEGACY branch holds exactly one DesktopWindowsVideoControlBar( call")
    found.need(source.mask_kotlin(leaf).count(CONTROL_BAR) == 1, "...and no other mention of it in code")
    found.need(leaf.count(TOP_STRIP) == 1, "one top strip surface")
    found.need(leaf.count(DETAILS_PANEL) == 1, "one details panel")
    for label, needle in LAYOUT_ORDER:
        found.need(leaf.count(needle) == 1, "layout anchor %s occurs exactly once: %s" % (label, needle))
    indices = [leaf.find(needle) for _, needle in LAYOUT_ORDER]
    if all(index >= 0 for index in indices):
        for (label, _), (next_label, _), before, after in zip(LAYOUT_ORDER, LAYOUT_ORDER[1:], indices, indices[1:]):
            found.need(before < after, "layout order: %s must come before %s" % (label, next_label))
    if leaf.count(CONTROL_BAR) == 1:
        found.need(argument_names(leaf, CONTROL_BAR) == BAR_ARGUMENTS, "the control bar keeps its 49 named arguments in order")
    if leaf.count(DETAILS_PANEL) == 1:
        found.need(argument_names(leaf, DETAILS_PANEL) == PANEL_ARGUMENTS, "the details panel keeps its ten named arguments in order")
    for expected, needle in HIT_AND_FOCUS_ANCHORS + LEAF_NAMES:
        found.need(leaf.count(needle) == expected, "%d x %s" % (expected, needle))
    # The recovery overlay also returns focus. Check the original admission paths rather than
    # freezing the total number of calls to the shared focus target.
    found.need("observeNativePointer(null)" in leaf and
               "viewportFocus.requestFocus()\n                    surface.requestFocusInWindow()" in leaf,
               "a native surface press restores viewport and surface focus")
    found.need("desktopWindowsRequestPageEntryFocus(desktopWindowsTextEditorFocused()) { viewportFocus.requestFocus() }" in leaf,
               "page entry retains the text-editor focus guard")
    return found.items


def check_future_order(leaf):
    """Spec 10.1, as a rule that holds from the day the calls exist: the input layer wraps the cover, the overlay follows the preview host."""
    found = Findings()
    if INPUT_LAYER in leaf:
        found.need(leaf.count(INPUT_LAYER) == 1, "DesktopPlayerInputLayer( occurs once")
        found.order(leaf, INPUT_LAYER, ENTRY_COVER, "DesktopPlayerInputLayer( precedes the entry cover")
    if PLAYER_OVERLAY in leaf:
        found.need(leaf.count(PLAYER_OVERLAY) == 1, "DesktopPlayerOverlay( occurs once")
        found.order(leaf, SEEK_PREVIEW_HOST, PLAYER_OVERLAY, "DesktopPlayerOverlay( follows DesktopWindowsVideoSeekPreviewHost(")
    return found.items


ALL_CHECKS = (check_cover_pins, check_feedback_pins, check_comment_pins, check_quality_pins, check_must_keep, check_legacy_branch,
              check_future_order)


class ExistingPinsTest(unittest.TestCase):
    LEAF_TEXT = read_leaf()

    def test_the_cover_pins_of_test_v041_player_cover_source(self):
        self.assertEqual([], check_cover_pins(self.LEAF_TEXT))

    def test_the_feedback_pins_of_test_extract_upstream_video_feedback_host(self):
        self.assertEqual([], check_feedback_pins(self.LEAF_TEXT))

    def test_the_comment_pins_of_test_extract_upstream_comment_renderer(self):
        self.assertEqual([], check_comment_pins(self.LEAF_TEXT))

    def test_the_quality_pins_of_test_ux_player_policy(self):
        self.assertEqual([], check_quality_pins(self.LEAF_TEXT))

    def test_the_replays_read_the_same_file_the_old_suites_read(self):
        # The three old suites name the leaf by this path; if it moved, a replay would pin the wrong file.
        for old in ("test_v041_player_cover_source.py", "test_extract_upstream_video_feedback_host.py",
                    "test_extract_upstream_comment_renderer.py"):
            text = source.read(source.REPO / "desktop/tools/tests" / old)
            self.assertIn("DesktopWindowsVideoPhysicalLeaf.kt", text, old)
        self.assertIn("ui/DesktopWindowsVideoPhysicalLeaf.kt", source.read(Path(__file__).resolve().parent / "test_ux_player_policy.py"))

    def test_the_coin_assertion_that_already_fails_is_not_pinned_as_present(self):
        # Honest record of the one pin that is stale on the base: the string is in the video engagement section, not in the leaf.
        self.assertNotIn("if (engagementBinding?.coin(count, false) == true)", self.LEAF_TEXT)
        self.assertIn("fun coin(count: Int, alsoLike: Boolean): Boolean", source.read(source.K / "ui/DesktopWindowsVideoEngagementSection.kt"))


class MustKeepTest(unittest.TestCase):
    LEAF_TEXT = read_leaf()

    def test_the_spec_4_15_must_keep_list(self):
        self.assertEqual([], check_must_keep(self.LEAF_TEXT))

    def test_the_entry_cover_file_is_the_generated_projection_and_the_leaf_only_calls_it(self):
        # The cover file is byte-compared against a generated projection by test_v041_player_cover_source.py and is never edited.
        cover = source.read(source.K / "ui/DesktopWindowsVideoEntryCover.kt")
        self.assertIn(".desktopCommandHitRegion(\"windows-entry-cover\")", cover)
        self.assertEqual(1, self.LEAF_TEXT.count(ENTRY_COVER))


class LegacyBranchTest(unittest.TestCase):
    LEAF_TEXT = read_leaf()

    def test_the_legacy_layout_is_frozen(self):
        self.assertEqual([], check_legacy_branch(self.LEAF_TEXT))

    def test_the_argument_parser_reads_the_real_call_the_way_the_pins_expect(self):
        arguments = dict(call_arguments(self.LEAF_TEXT, CONTROL_BAR))
        self.assertEqual(49, len(BAR_ARGUMENTS))
        self.assertEqual(49, len(arguments))
        self.assertTrue(arguments["state"].startswith("state"))
        self.assertTrue(arguments["selectedQuality"].startswith("success?.currentQuality"))
        self.assertIn("val skip = sponsorPresentation.skip", arguments["sponsorSkip"])
        self.assertEqual(10, len(call_arguments(self.LEAF_TEXT, DETAILS_PANEL)))

    def test_the_leaf_still_owns_the_playback_chrome_state_that_the_bar_reads(self):
        # The hold is read twice: once for the chrome gate and once inside the bar's hold callback (previouslyHeld).
        for expected, needle in ((1, "DesktopWindowsFullscreenChromeInteraction(topHovered, topFocused)"),
                                 (2, "barInteraction.held(nativePointerOnVideo, nativeKeyboardOnVideo)"),
                                 (1, "desktopWindowsFullscreenChromeCanAutoHide(fullscreen, active && !pipActive,")):
            self.assertEqual(expected, self.LEAF_TEXT.count(needle), needle)


class FutureOrderingTest(unittest.TestCase):
    LEAF_TEXT = read_leaf()

    def test_the_ordering_rules_hold_for_the_leaf_as_it_is(self):
        self.assertEqual([], check_future_order(self.LEAF_TEXT))

    def test_the_rule_accepts_a_correct_future_layout(self):
        # A synthetic leaf with the V3 and V4a calls in the right places passes, so the rule is not a stub.
        text = self.LEAF_TEXT.replace(ENTRY_COVER, INPUT_LAYER + " { " + ENTRY_COVER, 1).replace(
            SEEK_PREVIEW_HOST, SEEK_PREVIEW_HOST + ") " + PLAYER_OVERLAY, 1)
        self.assertEqual([], check_future_order(text))

    def test_the_overlay_calls_are_not_mounted_in_the_leaf_yet(self):
        # State of slice V2: no input layer and no carrier overlay. V3 and V4a move this line in the commit that mounts them.
        for needle in (INPUT_LAYER, PLAYER_OVERLAY, "DesktopPlayerInputProbe", "DesktopWindowsCarrierScaleInput"):
            self.assertNotIn(needle, self.LEAF_TEXT, needle)


class MutationTest(unittest.TestCase):
    """Each mutation breaks one rule; the matching check must name it. The real leaf is the control."""
    LEAF_TEXT = read_leaf()

    def mutated(self, old, new, count=1):
        self.assertIn(old, self.LEAF_TEXT, "the mutation anchor is gone: " + old)
        text = self.LEAF_TEXT.replace(old, new, count)
        self.assertNotEqual(self.LEAF_TEXT, text)
        return text

    def test_every_check_passes_on_the_real_leaf(self):
        for check in ALL_CHECKS:
            self.assertEqual([], check(self.LEAF_TEXT), check.__name__)

    def test_a_second_control_bar_call_is_found(self):
        text = self.LEAF_TEXT + "\n    DesktopWindowsVideoControlBar(state = state)\n"
        self.assertTrue(check_legacy_branch(text))

    def test_a_control_bar_call_that_vanished_is_found(self):
        self.assertTrue(check_legacy_branch(self.mutated(CONTROL_BAR, "DesktopWindowsVideoControlBarX(")))

    def test_a_renamed_or_dropped_bar_argument_is_found(self):
        self.assertTrue(check_legacy_branch(self.mutated("onChapterSeek = ::seekChapter,", "onChapterJump = ::seekChapter,")))
        self.assertTrue(check_legacy_branch(self.mutated("                    enhancement = actions.enhancement,\n", "")))

    def test_a_reordered_layout_is_found(self):
        # The strip anchor moved behind the video box.
        moved = self.mutated(TOP_STRIP + "Modifier.fillMaxWidth()", "DesktopWindowsPlayerSurfaceMoved(Modifier.fillMaxWidth()")
        moved = moved.replace("BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {",
                              "BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {\n" + TOP_STRIP + "Modifier.fillMaxWidth()", 1)
        self.assertTrue(check_legacy_branch(moved))

    def test_a_removed_hit_region_or_focus_handler_is_found(self):
        self.assertTrue(check_legacy_branch(self.mutated(".desktopWindowsChromePointerInput(::observeChromePointer)", "")))
        self.assertTrue(check_legacy_branch(self.mutated(".focusRequester(viewportFocus).onFocusChanged {", ".onFocusChanged {")))
        self.assertTrue(check_legacy_branch(self.mutated("windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) }", "null")))
        self.assertTrue(check_legacy_branch(self.mutated("desktopWindowsRequestPageEntryFocus(", "desktopWindowsRequestFocus(")))

    def test_a_changed_accessible_name_is_found(self):
        self.assertTrue(check_legacy_branch(self.mutated('Icon(Icons.Default.ArrowBack, contentDescription = "返回")',
                                                         'Icon(Icons.Default.ArrowBack, contentDescription = "退出")')))

    def test_a_second_entry_cover_call_is_found(self):
        text = self.LEAF_TEXT.replace(OVERLAY, ENTRY_COVER + ")\n" + OVERLAY, 1)
        self.assertTrue(check_cover_pins(text))
        self.assertTrue(check_must_keep(text))

    def test_the_entry_cover_behind_the_overlay_is_found(self):
        text = self.mutated(ENTRY_COVER, "DesktopWindowsVideoEntryCoverX(")
        text = text.replace(OVERLAY, OVERLAY + "\n" + ENTRY_COVER + ")", 1)
        self.assertTrue(check_cover_pins(text))
        self.assertTrue(check_must_keep(text))

    def test_a_lost_cover_marker_is_found(self):
        self.assertTrue(check_cover_pins(self.mutated(COVER_MARKER_START, "    // Cover input")))
        self.assertTrue(check_must_keep(self.mutated(COVER_MARKER_END, "    var showSponsorSegmentX ")))

    def test_a_cover_binding_that_compares_the_current_cid_is_found(self):
        self.assertTrue(check_cover_pins(self.mutated("load.currentRequest === coverRequest", "load.currentCid == coverRequest.cid")))

    def test_a_lost_or_moved_presentation_guard_of_the_feedback_host_is_found(self):
        self.assertTrue(check_feedback_pins(self.mutated(FEEDBACK_GUARD, "if (presentationAlive)")))
        far = self.mutated(FEEDBACK_GUARD, "")
        self.assertTrue(check_feedback_pins(far))

    def test_a_duplicated_feedback_host_or_like_call_is_found(self):
        self.assertTrue(check_feedback_pins(self.LEAF_TEXT + "\n" + FEEDBACK_CALL + "\n"))
        self.assertTrue(check_must_keep(self.LEAF_TEXT + "\nengagementBinding?.like()\n"))

    def test_a_feedback_source_read_under_the_foreground_gate_is_found(self):
        text = self.mutated("feedbackPresentationCurrent() && success?.info?.let {", "current() && success?.info?.let {")
        self.assertTrue(check_feedback_pins(text))

    def test_a_changed_engagement_key_is_found(self):
        self.assertTrue(check_feedback_pins(self.mutated(ENGAGEMENT_KEY, "remember(assembly, feedbackSource)")))

    def test_the_old_comment_renderer_call_returning_is_found(self):
        self.assertTrue(check_comment_pins(self.LEAF_TEXT + "\nDesktopWindowsVideoComments(x)\n"))
        self.assertTrue(check_comment_pins(self.mutated("DesktopWindowsCommentSearchSection(", "DesktopWindowsCommentSearch(")))

    def test_a_changed_quality_readback_is_found(self):
        self.assertTrue(check_quality_pins(self.mutated(SELECTED_QUALITY_LINE, "selectedQuality = success?.currentQuality ?: 0,")))
        self.assertTrue(check_quality_pins(self.mutated("value.qualityLabels.getOrNull(index)", "value.qualityLabels.getOrNull(index + 1)")))

    def test_an_input_layer_after_the_cover_and_an_overlay_before_the_preview_host_are_found(self):
        wrong_layer = self.LEAF_TEXT.replace(ENTRY_COVER, ENTRY_COVER + ") " + INPUT_LAYER + " {", 1)
        self.assertTrue(check_future_order(wrong_layer))
        wrong_overlay = self.LEAF_TEXT.replace(SEEK_PREVIEW_HOST, PLAYER_OVERLAY + ") " + SEEK_PREVIEW_HOST, 1)
        self.assertTrue(check_future_order(wrong_overlay))
        self.assertTrue(check_future_order(self.LEAF_TEXT + "\n" + INPUT_LAYER + "\n" + INPUT_LAYER))

    def test_the_findings_say_which_anchor_failed(self):
        problems = check_legacy_branch(self.mutated(".desktopWindowsChromePointerInput(::observeChromePointer)", ""))
        self.assertTrue(any(".desktopWindowsChromePointerInput(::observeChromePointer)" in problem for problem in problems), problems)


class SourceTest(unittest.TestCase):
    def test_this_suite_and_the_leaf_are_plain_lf(self):
        source.assert_lf_only(self, LEAF, Path(__file__).resolve())

    def test_the_suite_holds_no_tab_and_is_valid_utf8(self):
        # The pins of the two accessible names hold real Chinese text; the bytes must decode and no tab may stand in a source line.
        text = Path(__file__).read_bytes().decode("utf-8")
        self.assertNotIn("\t", text)


if __name__ == "__main__":
    unittest.main()
