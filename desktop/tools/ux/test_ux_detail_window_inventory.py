"""Slice X5 source checks: the vendored page baseline, DesktopDetailWindow flag defaults and the inventory behind them (spec 3.8, D15). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_detail_window_inventory.py

Slice X5 changes one line of Kotlin, the default of precisePointerConnected in ui/DesktopDetailWindow.kt (false to true), and documents both defaults in
the KDoc. The keyboard default stays false. This suite is the evidence for both defaults and the pin that nothing else moved. ux_detail_window_model.py
holds the analysis (a text scan of the committed sources, no generator run, no compiler); this file pins its answers.

What the inventory found (every statement below is checked by a test):

  providers     DesktopDetailWindow has 17 calls in desktop/src/main. Twelve are in DesktopShell.kt: one outer site around the whole original Root content
                (DesktopReadyOriginalRootMount: HomeScreen, SearchScreen, LiveListScreen, ListenVideoScreen, DownloadListScreen, ProfileScreen, StoryScreen and the
                offline player are reached from it and take its flags) and eleven nested sites around vendored pages (icon settings, Aicu, space, Bangumi pages,
                messages, comment detail, favorites, following, watch later, history, article), whose lambdas run under their own flags, not the outer ones. Five
                more are elsewhere: the cache clear progress host, the community dynamic detail, the comment composer window, the popup material host and the
                Bangumi player leaf, which alone passes flags (both true). A composable sees the flags of its nearest enclosing provider.
  readers       The raw flags are read by exactly one file, InputDevicePolicy.kt. Its three outputs have these readers: enableKeyboardNavigation by the key handlers
                of VideoPlayerSection and FullscreenPlayerOverlay; enableHoverEffects by the adaptiveCardHoverEffect default, which extract-upstream-home-full-card.py
                rewrites to "enabled: Boolean = true"; enablePointerFocus by nobody. So the pointer default has no live reader that it still switches: the spec's
                sentence that the flip "turns on hover effects and pointer focus" describes the policy, not an observable change of this tree. The flip is the
                baseline for pages that start reading the output (and for a vendored update that adds a reader), and the inventory is what notices that.
  hosting       Of the twelve shell sites only the outer one governs a vendored player overlay, and only statically: DesktopOriginalVideoPhysicalLeaf reaches
                DesktopOriginalVideoHolderRoute, VideoDetailScreenStateHolder, VideoPlayerSection (the keyboard reader) and VideoPlayerOverlay. The shell hands a
                VideoDetail key to the desktop leaf and gives the original leaf only the audio routes, so that route is dormant in this source (slice V12a measures
                whether it mounts at all). The eleven page sites govern none. The Bangumi leaf governs BangumiPlayerScreen and the overlay under its own explicit flags.
                FullscreenPlayerOverlay is called only by the Android MainActivity, which the upstream manifest lists as a policy extract, not as a selected file.
  decision D15  "If only Bangumi hosts a vendored player, hardwareKeyboardConnected may default to true; otherwise it stays false for the sites that host one."
                The outer site takes the default and hosts one, so the default stays false. Flipping it for the eleven page sites alone would change nothing (they
                have no keyboard reader) and cannot be done per site without editing the Root-reserved DesktopShell.kt, which this slice does not do.
  key maps      Bangumi keeps its explicit (true, true) and the vendored key maps it enables; offline keeps its Space-only desktop input and the three mpv bindings.
                Both are pinned by exact hashes below, computed from the tree before this slice's edit (X5 edits neither file), so a later change must update a pin.

  DetailWindowEditTest   the two hunks of X5, reverse-applied, give the base file byte for byte; the code differs from the base in one default; the signature,
                         the remember keys, the constructor argument order and the single provider call are unchanged; the defaults are the decision
  ProviderInventoryTest  the 17 providers in order with nesting, explicit flags, a witness host and the player entry points each governs; the files that
                         wrap DesktopDetailWindow (main and test) are exactly the known ones
  ReaderInventoryTest    the reader sets of the raw flags and of each policy output, the generator rewrite of the hover default, the generator and slice
                         inputs that mention the policy, the manifest modes, and the finding that the pointer flag has no live reader
  HostingTest            witness paths, the nesting cut, the route facts and the keyboard readers per provider
  DecisionTest           the rule of spec 3.8 on the inventory, the defaults of the Kotlin file, the KDoc says what the evidence says
  KeyMapPinsTest         Bangumi and offline key maps unchanged: leaf flags, vendored key policy hashes, the offline Space-only input, the mpv bindings
  ScannerTest            the scanner and the call graph on small Kotlin sources, including the nesting cut and the decision on synthetic providers
  MutationTest           each check fails on an edited copy of the real sources (in memory, nothing is written)

A later slice that adds a DesktopDetailWindow site, a flag reader or a new default updates the pinned tables in the same commit and re-reads D15; it never
loosens a check.
"""
import contextlib
import difflib
import functools
import io
import re
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402
import ux_detail_window_model as model  # noqa: E402

BASE_COMMIT = "fa6d2922775041b282f8cc687b5c3c5c75ee707d"  # the branch head before slice X5; the hash below is of its DesktopDetailWindow.kt blob
BASE_SHA256 = "f52b7be19ca2b9040ac4b31685ce8e6a9fef89999c55f3008afa2827f935e918"
BASE_BYTES = 2026

DOC_BEFORE = (
    "/** Window measurement is actual Compose constraints; this host has no Android hinge sensor. */",
)
DOC_AFTER = (
    "/** Window measurement is actual Compose constraints; this host has no Android hinge sensor.",
    " *",
    " * Input flags (spec 3.8, slice X5, decision D15). A Windows desktop always has a mouse, so [precisePointerConnected] defaults to",
    " * true: the vendored input policy derives hover effects and pointer focus from it, and a page that starts reading either should",
    " * find them on. [hardwareKeyboardConnected] stays false by default: its only readers are the key handlers of the vendored",
    " * VideoPlayerSection and FullscreenPlayerOverlay, and the root site that takes these defaults can mount the original video",
    " * route that hosts the former, which would then compete with the window-level player shortcuts. The Bangumi player leaf passes",
    " * both flags as true on its own. The inventory behind both defaults is desktop/tools/ux/test_ux_detail_window_inventory.py.",
    " */",
)
FLAG_BEFORE = "    precisePointerConnected: Boolean = false,\n"
FLAG_AFTER = "    precisePointerConnected: Boolean = true,\n"

SIGNATURE = (
    ("modifier", "Modifier", "Modifier"),
    ("precisePointerConnected", "Boolean", "true"),
    ("hardwareKeyboardConnected", "Boolean", "false"),
    ("content", "@Composable () -> Unit", None),
)

SHELL = "DesktopShell.kt"
OUTER_GOVERNS = ("AudioModeScreen", "OfflineVideoPlayerScreen", "VideoPlayerOverlay", "VideoPlayerSection")
BANGUMI_GOVERNS = ("BangumiPlayerOverlayHost", "BangumiPlayerScreen", "VideoPlayerOverlay")
# (file name, depth, explicit flags, a name the lambda invokes or None, the vendored player entry points the provider governs). Source order of the scan:
# files by path, call sites by position. Lines are not pinned; the Root hunks of other slices move them.
EXPECTED_PROVIDERS = (
    (SHELL, 0, (None, None), "DesktopReadyOriginalRootMount", OUTER_GOVERNS),
    (SHELL, 1, (None, None), "DesktopOriginalIconSettingsRootHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalAicuRootHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalSpacePageRootHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalBangumiPagesRootHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalMessagePageRootHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalCommentDetailRootHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalFavoritesHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalFollowingHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalWatchLaterHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalPersonalListHost", ()),
    (SHELL, 1, (None, None), "DesktopOriginalArticleRootHost", ()),
    ("DesktopWindowsCacheClearProgressHost.kt", 0, (None, None), "CacheClearAnimationDialog", ()),
    ("CommunityDynamicScreens.kt", 0, (None, None), "DesktopOriginalDynamicDetailLayout", ()),
    ("DesktopOriginalBangumiPlayerPhysicalLeaf.kt", 0, (True, True), "BangumiPlayerScreen", BANGUMI_GOVERNS),
    ("DesktopWindowsCommentComposerWindow.kt", 0, (None, None), None, ()),
    ("DesktopWindowsPopupMaterial.kt", 0, (None, None), None, ()),
)
OUTER_ROW, BANGUMI_ROW = 0, 14
OUTER_PAGES = ("HomeScreen", "SearchScreen", "LiveListScreen", "ListenVideoScreen", "DownloadListScreen", "ProfileScreen", "StoryScreen")

MAIN = "desktop/src/main/kotlin/com/bilipai/desktop/"
WRAPPER_FILES = tuple(MAIN + name for name in (
    "DesktopShell.kt", "settings/DesktopWindowsCacheClearProgressHost.kt", "ui/CommunityDynamicScreens.kt", "ui/DesktopDetailWindow.kt",
    "ui/DesktopOriginalBangumiPlayerPhysicalLeaf.kt", "ui/DesktopWindowsCommentComposerWindow.kt", "ui/DesktopWindowsPopupMaterial.kt"))
# Test sources that wrap a composable in DesktopDetailWindow with the default flags (they run with the new pointer default). The JVM classes of the report.
TEST_WRAPPERS = {
    "brand/DesktopBrandComponentUiTest.kt": "com.bilipai.desktop.brand.DesktopBrandComponentUiTest",
    "settings/DesktopCacheClearComponentUiTest.kt": "com.bilipai.desktop.settings.DesktopCacheClearComponentUiTest",
    "ui/DesktopHistoryRecapComponentUiTest.kt": "com.bilipai.desktop.ui.DesktopHistoryRecapComponentUiTest",
    "ui/DesktopWindowsCommentComposerLayoutTest.kt": "com.bilipai.desktop.ui.DesktopWindowsCommentComposerLayoutTest",
    "ui/DesktopWindowsPopupMaterialUiTest.kt": "com.bilipai.desktop.ui.DesktopWindowsPopupMaterialUiTest",
}

APP = "app/src/main/java/com/android/purebilibili/"
KEYBOARD_READER_FILES = (APP + "feature/video/ui/overlay/FullscreenPlayerOverlay.kt", APP + "feature/video/ui/section/VideoPlayerSection.kt")
EXPECTED_READERS = {
    "raw_flags": {flag: [model.POLICY_REL, APP + "core/util/WindowSizeUtils.kt", MAIN + "ui/DesktopDetailWindow.kt",
                         MAIN + "ui/DesktopOriginalBangumiPlayerPhysicalLeaf.kt"] for flag in model.RAW_FLAGS},
    "policy_callers": [model.HOVER_MODIFIER_REL, APP + "feature/video/ui/overlay/FullscreenPlayerOverlay.kt",
                       APP + "feature/video/ui/section/VideoPlayerSection.kt"],
    "outputs": {"enableHoverEffects": [model.HOVER_MODIFIER_REL], "enableKeyboardNavigation": list(KEYBOARD_READER_FILES), "enablePointerFocus": []},
    "providers_of_the_local": [APP + "MainActivity.kt", APP + "feature/video/VideoActivity.kt", model.DETAIL_WINDOW_REL],
    "keyboard_functions": {KEYBOARD_READER_FILES[0]: ["FullscreenPlayerOverlay"], KEYBOARD_READER_FILES[1]: ["VideoPlayerSectionContent"]},
}
EXPECTED_MODES = {
    model.POLICY_REL: ("direct", ("stable-video-player-section-full", "desktop-v025-complete-player-parity")),
    model.HOVER_MODIFIER_REL: ("policy-extract", ("home-full-card",)),
    APP + "core/util/WindowSizeUtils.kt": ("policy-extract", ("settings-home-card-parity", "dynamic-detail-container", "stable-favorites")),
    model.PLAYER_KEY_POLICY_REL: ("direct", ("source-parity",)),
    model.FULLSCREEN_KEY_POLICY_REL: ("direct", ("stable-video-fullscreen-pager",)),
    KEYBOARD_READER_FILES[0]: ("selected", ("stable-video-fullscreen-pager",)),
    KEYBOARD_READER_FILES[1]: ("selected", ("stable-danmaku-list-menu", "stable-danmaku-settings-panel", "stable-video-player-section-full",
                                            "desktop-v025-complete-player-parity")),
    APP + "feature/download/OfflineVideoPlayerScreen.kt": ("extracted", ("stable-offline-player-original", "downloads", "source-parity",
                                                                          "offline-native-original-surface-scope")),
    APP + "MainActivity.kt": ("policy-extract", ("settings-crash-prompt-parity", "v025-comment-parity")),
}
# VideoActivity.kt has no entry in desktop/upstream-sources.json at all: it is not part of the desktop build.
NOT_IN_MANIFEST = (APP + "feature/video/VideoActivity.kt",)

OUTER_PATHS = {
    "AudioModeScreen": ("DesktopOriginalAuMusicHost", "MusicDetailScreen", "AudioModeScreen"),
    "OfflineVideoPlayerScreen": ("DesktopOriginalOfflineRootHost", "DesktopOriginalOfflinePlayerHost", "OfflineVideoPlayerScreen"),
    "VideoPlayerSection": ("DesktopOriginalVideoPhysicalLeaf", "DesktopOriginalVideoPhysicalLeafContent", "DesktopOriginalVideoHolderRoute",
                           "VideoDetailScreenStateHolder", "VideoPlayerSection"),
    "VideoPlayerOverlay": ("DesktopOriginalVideoPhysicalLeaf", "DesktopOriginalVideoPhysicalLeafContent", "DesktopOriginalVideoHolderRoute",
                           "VideoDetailScreenStateHolder", "VideoPlayerSection", "VideoPlayerSectionContent", "VideoPlayerOverlay"),
}
BANGUMI_PATHS = {
    "BangumiPlayerOverlayHost": ("BangumiPlayerScreen", "BangumiPlayerView", "BangumiPlayerOverlayHost"),
    "BangumiPlayerScreen": ("BangumiPlayerScreen",),
    "VideoPlayerOverlay": ("BangumiPlayerScreen", "BangumiPlayerView", "BangumiPlayerOverlayHost", "VideoPlayerOverlay"),
}

# Exact pins of the key maps (LF-normalised text, sha256). Bangumi: the two vendored direct sources that the keyboard flag enables and their resolvers.
# Offline: the Space-only window key input of the desktop bridge. Neither is touched by X5; a later change has to update the pin and say why.
KEY_POLICY_FILE_SHA256 = {
    model.PLAYER_KEY_POLICY_REL: "d9315792c605c4acc4f7c3de98c888b335de729fba741c6e7f0eb966a3101441",
    model.FULLSCREEN_KEY_POLICY_REL: "86cd0968201445d8bda29fcca43f5676f9d0c32d8809188426385d407036836e",
}
KEY_RESOLVER_SHA256 = {
    (model.PLAYER_KEY_POLICY_REL, "resolvePlayerKeyAction"): ("b7c609b4c5eb15da2b6462e29ad9475d1846c808655d197bf16c64a84eb012a0",),
    (model.FULLSCREEN_KEY_POLICY_REL, "resolveFullscreenKeyboardAction"): ("114069a391b843b429bd710b9360251e7d03952690827a323bd3ce248f69538a",),
}
OFFLINE_KEY_FUNCTION_SHA256 = {
    "windowKeyInput": ("d7ed5e7a09ec34a2290d690c33f5b5be504c4108b3f8a6fd60a11509e9049f9c", "ace85d4fd6da72da286252814f1366dd818f1c9fd25b44bc55d11077e20f5345"),
    "keyPressed": ("69dd5a61117e1f5b2fbd5f5c259c25f485de3631be8ef40599a75b4bd99adfcd", "29babc55225c23dedefea32c32c7b90ee8af97e471c711d01a856878e037fb27"),
    "keyReleased": ("e9d5c2787cf3778b80074f16273b64beee45cf5b5c07fcf6949794f3bc002dec", "486b922e0e6a7f693bdf9a6df49f18503c4b00d9ab103a55023cca3cad9d5599"),
    "pressSpace": ("f58ab0b4b5fc128c3a54c66d0d9412d21836ed0ae10c8f4a6328004a0ae439ae",),
    "releaseSpace": ("d21322c4ae0fd0184ce0cefa878bb8d13ab6c21609962326d72e6c7e2588e245",),
}
MPV_BINDINGS_LINE = '    val bindings: String = listOf("MBTN_LEFT" to "reveal", "MOUSE_MOVE" to "motion", "SPACE" to "toggle")'
OFFLINE_REGISTRATION = "windowKeyFallback?.registerLeaf { event -> input.windowKeyInput(event) }"
BANGUMI_ARGS = "precisePointerConnected = true, hardwareKeyboardConnected = true"
BANGUMI_CALL = "DesktopDetailWindow(" + BANGUMI_ARGS + ")"


# ---------------------------------------------------------------------------------------------------------------------
# Checks (each returns a list of problems; the tests assert [] on the real sources and a non-empty list on mutated copies)
# ---------------------------------------------------------------------------------------------------------------------

def detail_text():
    return source.read(source.REPO / model.DETAIL_WINDOW_REL)


def reverse_apply(text):
    """The base file (the branch head before slice X5) from the file as slice X5 left it: both hunks undone, each exactly once."""
    after_doc, before_doc = "\n".join(DOC_AFTER), "\n".join(DOC_BEFORE)
    for needle in (after_doc, FLAG_AFTER):
        if text.count(needle) != 1:
            raise AssertionError("hunk text not found exactly once: %r" % needle[:60])
    return text.replace(after_doc, before_doc).replace(FLAG_AFTER, FLAG_BEFORE)


def squash_code(text):
    """Comment-free code lines of a Kotlin file: comments and strings blanked, blank lines dropped, trailing blanks stripped."""
    return "\n".join(line.rstrip() for line in source.mask_kotlin(text).splitlines() if line.strip())


def split_top_level(text):
    parts, depth, current = [], 0, []
    for index, char in enumerate(text):
        if char in "([{<":
            depth += 1
        elif char in ")]}>" and not (char == ">" and text[index - 1:index] == "-"):
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if "".join(current).strip():
        parts.append("".join(current))
    return [" ".join(part.split()) for part in parts]


def signature_of(text):
    """[(name, type, default or None)] of the parameters of fun DesktopDetailWindow."""
    code = source.mask_kotlin(text)
    start = code.index("fun DesktopDetailWindow(") + len("fun DesktopDetailWindow")
    inner = code[start + 1:model.match_close(code, start, "(", ")")]
    result = []
    for part in split_top_level(inner):
        head, _, default = part.partition(" = ")
        name, _, kind = head.partition(": ")
        result.append((name, kind, default or None))
    return result


def detail_window_problems(text):
    problems = []
    if signature_of(text) != list(SIGNATURE):
        problems.append("signature is %s" % (signature_of(text),))
    code = source.mask_kotlin(text)
    for needle, count in (("fun DesktopDetailWindow(", 1),
                          ("remember(size,precisePointerConnected,hardwareKeyboardConnected)", 1),
                          ("LocalAppWindowAdaptiveInfo provides adaptive", 1),
                          ("precisePointerConnected,hardwareKeyboardConnected)", 2),
                          ("AppWindowAdaptiveInfo(size, AppFoldingFeatureInfo(),", 1)):
        if code.count(needle) != count:
            problems.append("%r occurs %d times, expected %d" % (needle, code.count(needle), count))
    try:
        defaults = model.declared_defaults(text)
    except AssertionError as error:
        problems.append("defaults are not declared once each: %s" % (error,))
        defaults = None
    if defaults is not None and defaults != (True, False):
        problems.append("defaults are %s, expected (True, False)" % (defaults,))
    for word in ("D15", "VideoPlayerSection", "FullscreenPlayerOverlay", "test_ux_detail_window_inventory.py", "[precisePointerConnected]",
                 "[hardwareKeyboardConnected]"):
        if word not in text:
            problems.append("the KDoc does not mention %s" % word)
    return problems


@functools.lru_cache(maxsize=1)
def real_providers():
    return model.providers(model.repo_universe())


def provider_table_problems(provider_list):
    problems = []
    if len(provider_list) != len(EXPECTED_PROVIDERS):
        return ["%d providers, expected %d" % (len(provider_list), len(EXPECTED_PROVIDERS))]
    for index, (provider, expected) in enumerate(zip(provider_list, EXPECTED_PROVIDERS)):
        name, depth, explicit, witness, governs = expected
        actual = (Path(provider.rel).name, provider.depth, provider.explicit, provider.governs)
        if actual != (name, depth, explicit, governs):
            problems.append("provider %d is %s, expected %s" % (index, actual, (name, depth, explicit, governs)))
        if witness is not None and witness not in provider.calls:
            problems.append("provider %d (%s) does not invoke %s" % (index, name, witness))
        if provider.args != ("" if explicit == (None, None) else BANGUMI_ARGS):
            problems.append("provider %d has arguments %r" % (index, provider.args))
    return problems


def decision_problems(provider_list, detail):
    try:
        pointer = model.decide_pointer_default(provider_list)
    except AssertionError as error:
        return ["a provider opted out of the pointer flag: %s" % (error,)]
    keyboard, _ = model.decide_keyboard_default(provider_list)
    try:
        defaults = model.declared_defaults(detail)
    except AssertionError as error:
        return ["defaults are not declared once each: %s" % (error,)]
    return [] if defaults == (pointer, keyboard) else ["Kotlin defaults %s, inventory decides %s" % (defaults, (pointer, keyboard))]


def reader_problems(universe):
    problems = []
    found = model.readers(universe)
    for key, expected in EXPECTED_READERS.items():
        if found[key] != expected:
            problems.append("readers %s: %s, expected %s" % (key, found[key], expected))
    return problems


def pointer_live_readers(universe, generator_text=None):
    """The files that read a pointer-flag output and are still switched by it: hover readers other than the generator-rewritten modifier, and every
    pointer-focus reader."""
    found = model.readers(universe)
    generator_text = source.read(model.HOME_FULL_CARD_GENERATOR) if generator_text is None else generator_text
    replaces, adapted, once = model.hover_override(generator_text, universe.texts[model.HOVER_MODIFIER_REL])
    rewritten = {model.HOVER_MODIFIER_REL} if replaces and adapted and once else set()
    return sorted((set(found["outputs"]["enableHoverEffects"]) - rewritten) | set(found["outputs"]["enablePointerFocus"]))


def key_map_problems(universe):
    problems = []
    for rel, expected in KEY_POLICY_FILE_SHA256.items():
        if model.sha256_of(universe.texts[rel]) != expected:
            problems.append("%s changed" % rel)
    for (rel, name), expected in KEY_RESOLVER_SHA256.items():
        actual = tuple(model.sha256_of(text) for text in universe.function_texts(rel, name))
        if actual != expected:
            problems.append("%s in %s changed" % (name, rel))
    for name, expected in OFFLINE_KEY_FUNCTION_SHA256.items():
        actual = tuple(model.sha256_of(text) for text in universe.function_texts(model.OFFLINE_INPUT_REL, name))
        if actual != expected:
            problems.append("offline %s changed" % name)
    if MPV_BINDINGS_LINE not in universe.texts[model.MPV_INPUT_REL].splitlines():
        problems.append("the three mpv native input bindings changed")
    if universe.texts[model.OFFLINE_INPUT_REL].count(OFFLINE_REGISTRATION) != 1:
        problems.append("the offline key registration changed")
    bangumi = [provider for provider in model.providers(universe) if provider.rel == model.BANGUMI_LEAF_REL]
    if len(bangumi) != 1 or bangumi[0].explicit != (True, True) or "BangumiPlayerScreen" not in bangumi[0].calls:
        problems.append("the Bangumi leaf no longer passes both flags around BangumiPlayerScreen")
    return problems


def flat_regions(code, start=0, end=None):
    """Every DesktopDetailWindow lambda region of the text, an outer one before the ones nested in it, in document order."""
    found = []
    for region in model.provider_regions(code, start, len(code) if end is None else end):
        found.append(region)
        found += flat_regions(code, region[1] + 1, region[2])
    return found


def splice_arguments(universe, rel, which, arguments):
    """Universe in which the which-th DesktopDetailWindow call of the file (document order, nested ones included) gets an argument list.
    A copy; nothing is written."""
    code = universe.code[rel]
    call_start = flat_regions(code)[which][0]
    insert = call_start + len("DesktopDetailWindow")
    return universe.replace(rel, universe.texts[rel][:insert] + "(" + arguments + ")" + universe.texts[rel][insert:])


# ---------------------------------------------------------------------------------------------------------------------

class DetailWindowEditTest(unittest.TestCase):

    def test_the_file_is_lf_only(self):
        source.assert_lf_only(self, source.REPO / model.DETAIL_WINDOW_REL, Path(__file__), Path(model.__file__))

    def test_reverse_applying_the_two_hunks_gives_the_base_file_byte_for_byte(self):
        base = reverse_apply(detail_text())
        self.assertEqual(BASE_BYTES, len(base.encode("utf-8")))
        self.assertEqual(BASE_SHA256, model.sha256_of(base), "the base is the blob of %s" % BASE_COMMIT)

    def test_the_diff_to_the_base_is_the_doc_comment_and_one_flag_line(self):
        base, now = reverse_apply(detail_text()), detail_text()
        diff = [line for line in difflib.unified_diff(base.splitlines(), now.splitlines(), lineterm="", n=0)
                if line[:1] in "+-" and not line.startswith(("+++", "---"))]
        removed = [line for line in diff if line[0] == "-"]
        added = [line for line in diff if line[0] == "+"]
        self.assertEqual(["-" + DOC_BEFORE[0], "-" + FLAG_BEFORE.rstrip("\n")], removed)
        self.assertEqual(["+" + line for line in DOC_AFTER] + ["+" + FLAG_AFTER.rstrip("\n")], added)

    def test_the_code_differs_from_the_base_in_the_pointer_default_only(self):
        base, now = reverse_apply(detail_text()), detail_text()
        self.assertNotEqual(squash_code(base), squash_code(now))
        self.assertEqual(squash_code(base), squash_code(now).replace("precisePointerConnected: Boolean = true,",
                                                                    "precisePointerConnected: Boolean = false,"))

    def test_signature_remember_keys_constructor_order_and_the_single_provider_call_are_unchanged(self):
        self.assertEqual([], detail_window_problems(detail_text()))
        self.assertEqual(list(SIGNATURE), signature_of(detail_text()))
        self.assertEqual(list(model.DETAIL_WINDOW_PARAMETERS), [name for name, _, _ in SIGNATURE])

    def test_the_positional_constructor_order_matches_the_data_class(self):
        # DesktopDetailWindow passes the two flags positionally, so the data class must still declare the pointer before the keyboard.
        code = source.mask_kotlin(source.read(source.REPO / (APP + "core/util/WindowSizeUtils.kt")))
        declaration = code[code.index("data class AppWindowAdaptiveInfo("):]
        declaration = declaration[:model.match_close(declaration, declaration.index("("), "(", ")")]
        self.assertLess(declaration.index("precisePointerConnected"), declaration.index("hardwareKeyboardConnected"))
        self.assertEqual(["windowSizeClass", "foldingFeature", "displayContext", "precisePointerConnected", "hardwareKeyboardConnected"],
                         [re.match(r"\s*(?:val\s+)?(\w+)", part).group(1) for part in split_top_level(declaration[declaration.index("(") + 1:])])

    def test_the_defaults_are_pointer_true_and_keyboard_false(self):
        self.assertEqual((True, False), model.declared_defaults(detail_text()))


class ProviderInventoryTest(unittest.TestCase):

    def test_twelve_shell_sites_one_outer_and_eleven_nested(self):
        sites = [provider for provider in real_providers() if provider.rel.endswith("/" + SHELL)]
        self.assertEqual(12, len(sites))
        self.assertEqual([0] + [1] * 11, [site.depth for site in sites])
        self.assertEqual([None] + [0] * 11, [site.parent for site in sites])
        self.assertEqual([""] * 12, [site.args for site in sites], "no shell site passes a flag: all twelve take the defaults")
        self.assertEqual(sorted(site.line for site in sites), [site.line for site in sites])

    def test_the_nested_sites_sit_inside_the_outer_lambda(self):
        code = model.repo_universe().code[model.SHELL_REL]
        outer = model.provider_regions(code, 0, len(code))
        self.assertEqual(1, len(outer), "the outer site is the only DesktopDetailWindow call outside another one")
        _, lambda_start, lambda_end, _ = outer[0]
        nested = model.provider_regions(code, lambda_start + 1, lambda_end)
        self.assertEqual(11, len(nested))

    def test_the_table_of_providers(self):
        self.assertEqual([], provider_table_problems(real_providers()))

    def test_only_the_bangumi_leaf_passes_flags(self):
        explicit = [(Path(p.rel).name, p.explicit) for p in real_providers() if p.explicit != (None, None)]
        self.assertEqual([("DesktopOriginalBangumiPlayerPhysicalLeaf.kt", (True, True))], explicit)
        self.assertIn(BANGUMI_CALL, model.repo_universe().texts[model.BANGUMI_LEAF_REL])
        self.assertEqual(1, model.repo_universe().texts[model.BANGUMI_LEAF_REL].count("DesktopDetailWindow("))

    def test_effective_flags_after_the_default_flip(self):
        defaults = model.declared_defaults(detail_text())
        effective = [(Path(p.rel).name, p.effective(defaults)) for p in real_providers()]
        self.assertTrue(all(flags[0] is True for _, flags in effective), "every site sees a precise pointer")
        self.assertEqual([("DesktopOriginalBangumiPlayerPhysicalLeaf.kt", True)], [(name, flags[1]) for name, flags in effective if flags[1]],
                         "only the Bangumi leaf sees a hardware keyboard")

    def test_the_files_that_wrap_desktop_detail_window_are_the_known_ones(self):
        universe = model.repo_universe()
        wrappers = [rel for rel, code in sorted(universe.code.items())
                    if rel.startswith(model.DESKTOP_MAIN) and re.search(r"\bDesktopDetailWindow\s*[({]", code)]
        self.assertEqual(list(WRAPPER_FILES), wrappers)

    def test_the_test_sources_that_wrap_it_take_the_defaults(self):
        root = source.KT
        found = {}
        for path in sorted(root.rglob("*.kt")):
            text = source.mask_kotlin(source.read(path))
            if re.search(r"\bDesktopDetailWindow\s*[({]", text):
                found[path.relative_to(root).as_posix()] = re.findall(r"\bDesktopDetailWindow\s*\(([^)]*)\)", text)
        self.assertEqual(sorted(TEST_WRAPPERS), sorted(found))
        self.assertEqual({name: [] for name in TEST_WRAPPERS}, found, "no test source passes a flag")
        for name, fqcn in TEST_WRAPPERS.items():
            text = source.read(root / name)
            self.assertIn("package " + fqcn.rsplit(".", 1)[0] + "\n", text)
            self.assertRegex(text, r"(?m)^(?:internal )?class " + fqcn.rsplit(".", 1)[1] + r"\b")


class ReaderInventoryTest(unittest.TestCase):

    def test_reader_sets(self):
        self.assertEqual([], reader_problems(model.repo_universe()))

    def test_only_the_policy_reads_the_raw_flags(self):
        found = model.readers(model.repo_universe())["raw_flags"]
        for flag in model.RAW_FLAGS:
            others = [rel for rel in found[flag] if not rel.startswith(model.DESKTOP_MAIN) and rel not in (APP + "core/util/WindowSizeUtils.kt", model.POLICY_REL)]
            self.assertEqual([], others)
        policy = model.repo_universe().code[model.POLICY_REL]
        self.assertIn("enableHoverEffects = hasPrecisePointer", policy)
        self.assertIn("enableKeyboardNavigation = hasHardwareKeyboard", policy)
        self.assertIn("enablePointerFocus = hasPrecisePointer || hasHardwareKeyboard", policy)

    def test_the_keyboard_readers_are_player_functions_the_overlay_set_covers(self):
        universe = model.repo_universe()
        readers = model.readers(universe)["keyboard_functions"]
        for rel, names in readers.items():
            for name in names:
                covered = name in model.PLAYER_OVERLAYS or any(name in universe.reach([overlay]) for overlay in model.PLAYER_OVERLAYS)
                self.assertTrue(covered, "%s in %s is outside the vendored player overlay set" % (name, rel))
        self.assertIn("VideoPlayerSectionContent", universe.edges("VideoPlayerSection"))

    def test_the_hover_default_is_rewritten_to_always_on_by_the_home_card_generator(self):
        universe = model.repo_universe()
        self.assertEqual((True, True, True), model.hover_override(source.read(model.HOME_FULL_CARD_GENERATOR), universe.texts[model.HOVER_MODIFIER_REL]))

    def test_generator_inputs_that_mention_the_policy(self):
        self.assertEqual({"extract-upstream-home-full-card.py": ["enableHoverEffects", "resolveInputDevicePolicy"]}, model.generator_mentions())
        self.assertEqual(["VideoPlayerSection.kt"], model.slice_reader_names(),
                         "no generator input but copies of the player section reads a policy output")

    def test_manifest_modes_of_the_files_involved(self):
        modes = model.manifest_modes(list(EXPECTED_MODES) + list(NOT_IN_MANIFEST))
        self.assertEqual(EXPECTED_MODES, modes)
        for rel in NOT_IN_MANIFEST:
            self.assertNotIn(rel, modes)
            self.assertIn(rel, model.repo_universe().texts)

    def test_fullscreen_player_overlay_has_no_desktop_caller(self):
        universe = model.repo_universe()
        callers = model.files_with(universe, r"\bFullscreenPlayerOverlay\s*\(", exclude=(KEYBOARD_READER_FILES[0],))
        self.assertEqual([APP + "MainActivity.kt"], callers)
        self.assertEqual([], [name for name in universe.nodes if "FullscreenPlayerOverlay" in universe.edges(name)
                              and any(rel.startswith(model.DESKTOP_MAIN) for rel, _, _ in universe.nodes[name])])

    def test_the_pointer_flag_has_no_live_reader(self):
        # The spec's sentence "the flip turns on hover effects and pointer focus on the vendored pages" is true of the policy; no reader of this tree is
        # still switched by it. If this list becomes non-empty, a page reads the pointer output and the 17 providers above decide where it shows.
        self.assertEqual([], pointer_live_readers(model.repo_universe()))

    def test_the_only_provider_of_the_adaptive_info_on_the_desktop_is_desktop_detail_window(self):
        providers = model.readers(model.repo_universe())["providers_of_the_local"]
        self.assertEqual([model.DETAIL_WINDOW_REL], [rel for rel in providers if rel.startswith(model.DESKTOP_MAIN)])


class HostingTest(unittest.TestCase):

    def test_outer_paths_to_the_vendored_players(self):
        outer = real_providers()[OUTER_ROW]
        self.assertEqual(OUTER_PATHS, outer.paths)

    def test_bangumi_paths_to_the_vendored_player(self):
        self.assertEqual(BANGUMI_PATHS, real_providers()[BANGUMI_ROW].paths)

    def test_a_nested_provider_cuts_the_reach_of_the_outer_one(self):
        universe = model.repo_universe()
        outer = real_providers()[OUTER_ROW]
        self.assertIn("DesktopOriginalBangumiPlayerPhysicalLeaf", outer.calls, "the shell hands a BangumiPlayer key to the leaf")
        self.assertIn("DesktopOriginalBangumiPlayerPhysicalLeaf", outer.previous)
        self.assertNotIn("BangumiPlayerScreen", outer.previous, "the leaf wraps the screen in its own provider, so its flags decide there")
        rel, start, end = universe.nodes["DesktopOriginalBangumiPlayerPhysicalLeaf"][0]
        self.assertIn("BangumiPlayerScreen", model.invocations(universe.code[rel], start, end))
        self.assertNotIn("BangumiPlayerScreen", universe.edges("DesktopOriginalBangumiPlayerPhysicalLeaf"))

    def test_the_outer_site_carries_the_pages_no_nested_site_wraps_and_none_of_the_nested_pages(self):
        outer = real_providers()[OUTER_ROW]
        for name in OUTER_PAGES:
            self.assertIn(name, outer.previous, "%s is reached from the outer site and takes its flags" % name)
        for _, depth, _, witness, _ in EXPECTED_PROVIDERS:
            if depth == 1:
                self.assertNotIn(witness, outer.previous, "%s sits in a nested site, which owns its flags" % witness)
                self.assertNotIn(witness, outer.calls)

    def test_route_facts_say_the_original_video_route_is_dormant_in_this_source(self):
        universe = model.repo_universe()
        facts = model.route_facts(universe.code[model.SHELL_REL], universe.code[model.ORIGINAL_LEAF_REL], universe.code)
        self.assertEqual(9, len(facts))
        self.assertEqual([], [name for name, value in facts.items() if not value])

    def test_only_the_outer_site_governs_a_keyboard_reader(self):
        readers = {"VideoPlayerSection", "FullscreenPlayerOverlay"}
        governing = [index for index, provider in enumerate(real_providers()) if readers & set(provider.governs)]
        self.assertEqual([OUTER_ROW], governing)
        self.assertEqual({"VideoPlayerSection"}, readers & set(real_providers()[OUTER_ROW].governs))

    def test_the_eleven_page_sites_and_the_other_wrappers_govern_no_player(self):
        for index, provider in enumerate(real_providers()):
            if index not in (OUTER_ROW, BANGUMI_ROW):
                self.assertEqual((), provider.governs, (index, provider.rel))


class DecisionTest(unittest.TestCase):

    def test_the_rule_of_spec_3_8_on_the_inventory(self):
        keyboard, blockers = model.decide_keyboard_default(real_providers())
        self.assertFalse(keyboard)
        self.assertEqual([OUTER_ROW], [real_providers().index(blocker) for blocker in blockers])
        self.assertTrue(model.decide_pointer_default(real_providers()))

    def test_the_defaults_of_the_kotlin_file_are_the_decision(self):
        self.assertEqual([], decision_problems(real_providers(), detail_text()))

    def test_the_rule_flips_when_no_provider_that_takes_the_default_hosts_an_overlay(self):
        def fake(explicit, governs):
            return SimpleNamespace(explicit=explicit, governs=governs, takes_default=lambda index: explicit[index] is None,
                                   governs_overlay=lambda: tuple(sink for sink in governs if sink in model.PLAYER_OVERLAYS))
        only_bangumi = [fake((None, None), ()), fake((True, True), ("BangumiPlayerScreen", "VideoPlayerOverlay"))]
        keyboard, blockers = model.decide_keyboard_default(only_bangumi)
        self.assertTrue(keyboard, "spec 3.8: only the Bangumi site hosts an overlay, so the default may flip")
        self.assertEqual([], blockers)
        also_default = only_bangumi + [fake((None, None), ("VideoPlayerSection",))]
        self.assertFalse(model.decide_keyboard_default(also_default)[0])
        screen_only = [fake((None, None), ("OfflineVideoPlayerScreen",))]
        self.assertTrue(model.decide_keyboard_default(screen_only)[0], "a player screen without an overlay or section does not read the keyboard flag")

    def test_the_spec_row_and_the_root_decision_name_the_inventory(self):
        text = detail_text()
        for word in ("X5", "D15", "spec 3.8"):
            self.assertIn(word, text)
        self.assertIn("test_ux_detail_window_inventory.py", text)

    def test_the_shell_sites_pass_no_flag_so_the_kotlin_default_is_the_only_lever_of_this_slice(self):
        # DesktopShell.kt is Root-reserved and X5 does not edit it: the twelve sites keep taking the defaults of the free DesktopDetailWindow.kt.
        self.assertEqual([""] * 12, [p.args for p in real_providers() if p.rel == model.SHELL_REL])


class KeyMapPinsTest(unittest.TestCase):

    def test_the_bangumi_and_offline_key_maps_are_unchanged(self):
        self.assertEqual([], key_map_problems(model.repo_universe()))

    def test_the_bangumi_screen_and_the_offline_screen_have_no_key_handler_of_their_own(self):
        universe = model.repo_universe()
        pattern = re.compile(r"\b(?:onKeyEvent|onPreviewKeyEvent|resolvePlayerKeyAction|resolveFullscreenKeyboardAction|resolveInputDevicePolicy)\b")
        bangumi = [rel for rel, code in universe.code.items() if rel.startswith(APP + "feature/bangumi/") and pattern.search(code)]
        self.assertEqual([], bangumi)
        self.assertIsNone(pattern.search(universe.code[APP + "feature/download/OfflineVideoPlayerScreen.kt"]))

    def test_the_offline_key_input_is_space_only(self):
        universe = model.repo_universe()
        texts = [text for name in OFFLINE_KEY_FUNCTION_SHA256 for text in universe.function_texts(model.OFFLINE_INPUT_REL, name)]
        code = "\n".join(source.mask_kotlin(text) for text in texts)
        self.assertEqual({"VK_SPACE"}, set(re.findall(r"\bVK_\w+", code)))
        self.assertEqual({"Key.Spacebar"}, set(re.findall(r"\bKey\.\w+", code)))
        self.assertEqual(1, universe.texts[model.OFFLINE_INPUT_REL].count(OFFLINE_REGISTRATION))

    def test_the_mpv_native_input_keeps_three_bindings(self):
        text = model.repo_universe().texts[model.MPV_INPUT_REL]
        self.assertEqual(1, text.splitlines().count(MPV_BINDINGS_LINE))
        self.assertEqual(3, MPV_BINDINGS_LINE.count(" to "))

    def test_the_player_key_policies_are_vendored_direct_sources(self):
        modes = model.manifest_modes([model.PLAYER_KEY_POLICY_REL, model.FULLSCREEN_KEY_POLICY_REL])
        self.assertEqual({"direct"}, {mode for mode, _ in modes.values()}, "direct sources are byte pinned by the upstream manifest as well")


class ScannerTest(unittest.TestCase):
    FUNCTIONS = (
        "@Composable\n"
        "fun Alpha(x: Int) {\n"
        "    Beta(x)\n"
        "    if (x > 0) Gamma { Delta() }\n"
        "}\n"
        "\n"
        "@Composable internal fun Beta(x: Int) = Epsilon(x)\n"
        "\n"
        "private fun helper(x: Int): Int = x + 1\n"
        "\n"
        "interface Port {\n"
        "    @Composable fun Render(modifier: Modifier)\n"
        "}\n"
        "\n"
        "fun <T> List<T>.Zeta(block: (T) -> Unit) {\n"
        "    block(first())\n"
        "}\n"
        "\n"
        "val text = \"fun Fake(x: Int) { Hidden() }\" // fun Comment() { Hidden() }\n"
    )
    SHELL = (
        "@Composable fun Shell() {\n"
        "    DesktopDetailWindow {\n"
        "        Page()\n"
        "        DesktopDetailWindow(hardwareKeyboardConnected = true) {\n"
        "            Player()\n"
        "        }\n"
        "    }\n"
        "}\n"
        "@Composable fun Page() { Deep() }\n"
        "@Composable fun Deep() { VideoPlayerOverlay(state) }\n"
        "@Composable fun Player() { VideoPlayerSection() }\n"
        "@Composable fun VideoPlayerOverlay(state: Int) { }\n"
        "@Composable fun VideoPlayerSection() { }\n"
    )
    REL = model.DESKTOP_MAIN + "/com/bilipai/desktop/Synthetic.kt"

    def functions(self, text):
        code = source.mask_kotlin(text)
        return code, model.scan_functions(code)

    def test_scan_functions_finds_bodies_expression_bodies_and_receivers_and_skips_members_without_a_body(self):
        code, found = self.functions(self.FUNCTIONS)
        self.assertEqual(["Alpha", "Beta", "helper", "Zeta"], [name for name, _, _, _ in found])
        bodies = {name: self.FUNCTIONS[start:end + 1] for name, start, _, end in found}
        self.assertIn("Beta(x)", bodies["Alpha"])
        self.assertTrue(bodies["Alpha"].rstrip().endswith("}"))
        self.assertEqual("fun Beta(x: Int) = Epsilon(x)", " ".join(bodies["Beta"].replace("@Composable internal ", "").split()))
        self.assertEqual("private fun helper(x: Int): Int = x + 1", bodies["helper"].strip())
        self.assertIn("block(first())", bodies["Zeta"])
        self.assertNotIn("Fake", [name for name, _, _, _ in found])
        self.assertNotIn("Comment", [name for name, _, _, _ in found])

    def test_invocations_in_source_order_without_class_headers_and_hidden_text(self):
        code, found = self.functions(self.FUNCTIONS)
        alpha = found[0]
        self.assertEqual(["Beta", "Gamma", "Delta"], model.invocations(code, alpha[2], alpha[3]))
        self.assertEqual([], model.invocations("class Foo : Bar {\n}\nobject Baz {\n}\n", 0, 40))
        self.assertEqual(["Qux"], model.invocations(source.mask_kotlin('val s = "Hidden()"; Qux<Int>(1)'), 0, 40))

    def test_provider_regions_nest_and_the_exclusive_scan_cuts_at_the_nested_one(self):
        code = source.mask_kotlin(self.SHELL)
        regions = model.provider_regions(code, 0, len(code))
        self.assertEqual(1, len(regions))
        inner = model.provider_regions(code, regions[0][1] + 1, regions[0][2])
        self.assertEqual(1, len(inner))
        self.assertEqual("hardwareKeyboardConnected = true", inner[0][3].strip())
        self.assertEqual(["Page"], model.exclusive_invocations(code, regions[0][1] + 1, regions[0][2]))
        self.assertEqual(["Page", "DesktopDetailWindow", "Player"], model.invocations(code, regions[0][1] + 1, regions[0][2]))
        declaration = source.mask_kotlin("@Composable\nfun DesktopDetailWindow(\n    content: @Composable () -> Unit,\n) {\n    Box { }\n}\n")
        self.assertEqual([], model.provider_regions(declaration, 0, len(declaration)))

    def test_governance_on_a_synthetic_universe_with_the_nesting_cut(self):
        universe = model.Universe({self.REL: self.SHELL})
        outer, nested = model.providers(universe)
        self.assertEqual((0, 1), (outer.depth, nested.depth))
        self.assertEqual(("VideoPlayerOverlay",), outer.governs)
        self.assertEqual(("VideoPlayerSection",), nested.governs)
        self.assertEqual(("Page", "Deep", "VideoPlayerOverlay"), outer.paths["VideoPlayerOverlay"])
        self.assertEqual((None, None), outer.explicit)
        self.assertEqual((None, True), nested.explicit)
        self.assertEqual((True, False), outer.effective((True, False)))
        self.assertEqual((True, True), nested.effective((True, False)))
        keyboard, blockers = model.decide_keyboard_default([outer, nested])
        self.assertFalse(keyboard)
        self.assertEqual([outer], blockers)

    def test_an_explicit_flag_on_the_outer_site_lets_the_default_flip(self):
        text = self.SHELL.replace("    DesktopDetailWindow {\n", "    DesktopDetailWindow(hardwareKeyboardConnected = false) {\n", 1)
        outer, nested = model.providers(model.Universe({self.REL: text}))
        self.assertEqual((None, False), outer.explicit)
        self.assertTrue(model.decide_keyboard_default([outer, nested])[0])

    def test_lowercase_functions_are_not_graph_nodes_and_unknown_names_are_ignored(self):
        universe = model.Universe({self.REL: self.FUNCTIONS})
        self.assertEqual(["Alpha", "Beta", "Zeta"], sorted(universe.nodes))
        self.assertEqual({"Alpha": None, "Beta": "Alpha"}, universe.reach(["Alpha"]))
        self.assertEqual(("Alpha", "Beta"), model.Universe.path_to(universe.reach(["Alpha"]), "Beta"))
        self.assertIsNone(model.Universe.path_to(universe.reach(["Alpha"]), "Zeta"))

    def test_explicit_flags_and_declared_defaults(self):
        self.assertEqual((None, None), model.explicit_flags(""))
        self.assertEqual((True, False), model.explicit_flags("precisePointerConnected = true, hardwareKeyboardConnected = false"))
        self.assertEqual((None, True), model.explicit_flags("modifier = Modifier, hardwareKeyboardConnected=true"))
        self.assertEqual((False, True), model.declared_defaults("precisePointerConnected: Boolean = false,\nhardwareKeyboardConnected: Boolean = true,"))
        with self.assertRaises(AssertionError):
            model.declared_defaults("precisePointerConnected: Boolean = true,")
        with self.assertRaises(AssertionError):
            model.declared_defaults("precisePointerConnected: Boolean = true,\nprecisePointerConnected: Boolean = true,\nhardwareKeyboardConnected: Boolean = false,")

    def test_the_pointer_decision_refuses_an_opt_out(self):
        text = self.SHELL.replace("    DesktopDetailWindow {\n", "    DesktopDetailWindow(precisePointerConnected = false) {\n", 1)
        with self.assertRaises(AssertionError):
            model.decide_pointer_default(model.providers(model.Universe({self.REL: text})))

    def test_split_top_level_and_reverse_apply_helpers(self):
        self.assertEqual(["a: Int = f(1, 2)", "b: (Int, Int) -> Unit"], split_top_level("a: Int = f(1, 2), b: (Int, Int) -> Unit"))
        with self.assertRaises(AssertionError):
            reverse_apply("no hunks here")


class MutationTest(unittest.TestCase):

    def test_the_detail_window_checks_fail_on_edited_copies(self):
        text = detail_text()
        self.assertEqual([], detail_window_problems(text))
        for old, new in (
            ("precisePointerConnected: Boolean = true,", "precisePointerConnected: Boolean = false,"),
            ("hardwareKeyboardConnected: Boolean = false,", "hardwareKeyboardConnected: Boolean = true,"),
            ("    modifier: Modifier = Modifier,\n    precisePointerConnected: Boolean = true,\n    hardwareKeyboardConnected: Boolean = false,\n",
             "    modifier: Modifier = Modifier,\n    hardwareKeyboardConnected: Boolean = false,\n    precisePointerConnected: Boolean = true,\n"),
            ("remember(size,precisePointerConnected,hardwareKeyboardConnected)", "remember(size,precisePointerConnected)"),
            ("test_ux_detail_window_inventory.py", "something_else.py"),
            ("decision D15", "a decision"),
        ):
            self.assertEqual(1, text.count(old), old)
            self.assertTrue(detail_window_problems(text.replace(old, new)), old)
        self.assertTrue(detail_window_problems(text + "\nfun DesktopDetailWindow(x: Int) {}\n"))

    def test_the_reverse_apply_check_fails_on_an_edited_default_or_an_edited_doc(self):
        text = detail_text()
        self.assertEqual(BASE_SHA256, model.sha256_of(reverse_apply(text)))
        self.assertNotEqual(BASE_SHA256, model.sha256_of(reverse_apply(text.replace("fillMaxSize()", "fillMaxWidth()"))))
        with self.assertRaises(AssertionError):
            reverse_apply(text.replace(" * true: the vendored input policy derives", " * true: the vendored policy derives"))

    def test_the_decision_check_fails_when_the_default_or_the_inventory_moves(self):
        text = detail_text()
        self.assertEqual([], decision_problems(real_providers(), text))
        self.assertTrue(decision_problems(real_providers(), text.replace("hardwareKeyboardConnected: Boolean = false,", "hardwareKeyboardConnected: Boolean = true,")))
        self.assertTrue(decision_problems(real_providers(), text.replace("precisePointerConnected: Boolean = true,", "precisePointerConnected: Boolean = false,")))
        # the outer site opts out of the keyboard flag: nothing that takes the default hosts an overlay any more, so the decision becomes true and the
        # Kotlin default (false) no longer agrees: the inventory and the file must be moved together.
        universe = splice_arguments(model.repo_universe(), model.SHELL_REL, 0, "hardwareKeyboardConnected = false")
        self.assertTrue(model.decide_keyboard_default(model.providers(universe))[0])
        self.assertTrue(decision_problems(model.providers(universe), text))
        # a site opts out of the pointer flag: refused (an opt-out needs a reason, so the decision does not guess)
        universe = splice_arguments(model.repo_universe(), model.SHELL_REL, 1, "precisePointerConnected = false")
        self.assertTrue(decision_problems(model.providers(universe), text))

    def test_the_provider_table_fails_on_a_new_flag_a_new_site_or_a_dropped_flag(self):
        universe = model.repo_universe()
        self.assertEqual([], provider_table_problems(model.providers(universe)))
        flagged = splice_arguments(universe, model.SHELL_REL, 1, "hardwareKeyboardConnected = true")
        self.assertTrue(provider_table_problems(model.providers(flagged)))
        leaf = universe.texts[model.BANGUMI_LEAF_REL]
        self.assertEqual(1, leaf.count(BANGUMI_CALL))
        dropped = universe.replace(model.BANGUMI_LEAF_REL, leaf.replace(BANGUMI_CALL, "DesktopDetailWindow()"))
        self.assertTrue(provider_table_problems(model.providers(dropped)))
        extra_rel = model.DESKTOP_MAIN + "/com/bilipai/desktop/ui/ProbeWrapper.kt"
        added = universe.replace(extra_rel, "@Composable fun ProbeWrapper() { DesktopDetailWindow { VideoPlayerSection() } }\n")
        self.assertTrue(provider_table_problems(model.providers(added)))
        self.assertEqual(("VideoPlayerOverlay", "VideoPlayerSection"), model.providers(added)[-1].governs, "the section mounts the overlay")

    def test_the_reader_check_fails_on_a_new_reader_a_new_provider_of_the_local_or_a_hover_rewrite_that_disappears(self):
        universe = model.repo_universe()
        self.assertEqual([], reader_problems(universe))
        probe = model.DESKTOP_MAIN + "/com/bilipai/desktop/ui/ProbeReader.kt"
        for body in ("fun Probe(policy: Any) { if (policy.enablePointerFocus) { } }\n",
                     "fun Probe2(policy: Any) { if (policy.enableKeyboardNavigation) { } }\n",
                     "fun Probe3() { CompositionLocalProvider(LocalAppWindowAdaptiveInfo provides info) { } }\n",
                     "fun Probe4(i: Any) { resolveInputDevicePolicy(i) }\n"):
            self.assertTrue(reader_problems(universe.replace(probe, body)), body)
        self.assertEqual([], pointer_live_readers(universe))
        self.assertEqual([probe], pointer_live_readers(universe.replace(probe, "fun Probe(policy: Any) { if (policy.enablePointerFocus) { } }\n")))
        # without the generator rewrite the hover modifier would still be switched by the pointer flag
        self.assertEqual([model.HOVER_MODIFIER_REL], pointer_live_readers(universe, "nothing replaces anything here\n"))
        self.assertEqual((False, False, True), model.hover_override("nothing replaces anything here\n", universe.texts[model.HOVER_MODIFIER_REL]))
        edited = universe.texts[model.HOVER_MODIFIER_REL].replace("resolveInputDevicePolicy", "resolveOtherPolicy")
        self.assertNotEqual((True, True, True), model.hover_override(source.read(model.HOME_FULL_CARD_GENERATOR), edited))

    def test_the_key_map_check_fails_on_an_edited_policy_resolver_bridge_binding_or_registration(self):
        universe = model.repo_universe()
        self.assertEqual([], key_map_problems(universe))
        edits = (
            (model.PLAYER_KEY_POLICY_REL, "Key.Spacebar", "Key.Enter"),
            (model.FULLSCREEN_KEY_POLICY_REL, "FullscreenShortcutKey.KeyF -> FullscreenKeyboardAction.ToggleFullscreen",
             "FullscreenShortcutKey.KeyF -> FullscreenKeyboardAction.None"),
            (model.OFFLINE_INPUT_REL, "event.keyCode != java.awt.event.KeyEvent.VK_SPACE ||", "event.keyCode != java.awt.event.KeyEvent.VK_ENTER ||"),
            (model.OFFLINE_INPUT_REL, "if (event.key != Key.Spacebar) return false", "if (event.key != Key.Enter) return false"),
            (model.OFFLINE_INPUT_REL, OFFLINE_REGISTRATION, "windowKeyFallback?.registerLeaf { event -> false }"),
            (model.MPV_INPUT_REL, '"SPACE" to "toggle")', '"SPACE" to "toggle", "ENTER" to "toggle")'),
            (model.BANGUMI_LEAF_REL, BANGUMI_CALL, "DesktopDetailWindow(precisePointerConnected = true)"),
        )
        for rel, old, new in edits:
            self.assertEqual(1, universe.texts[rel].count(old), (rel, old))
            self.assertTrue(key_map_problems(universe.replace(rel, universe.texts[rel].replace(old, new))), (rel, old))

    def test_the_route_facts_fail_when_the_shell_hands_a_video_detail_key_to_the_original_leaf(self):
        universe = model.repo_universe()
        text = universe.texts[model.SHELL_REL]
        self.assertEqual(1, text.count("DesktopWindowsVideoPhysicalLeaf("))
        changed = universe.replace(model.SHELL_REL, text.replace("DesktopWindowsVideoPhysicalLeaf(", "DesktopOriginalVideoPhysicalLeaf("))
        facts = model.route_facts(changed.code[model.SHELL_REL], changed.code[model.ORIGINAL_LEAF_REL], changed.code)
        self.assertTrue([name for name, value in facts.items() if not value])
        gate = universe.texts[model.ORIGINAL_LEAF_REL]
        self.assertEqual(1, gate.count("if (key is BiliPaiNavKey.VideoDetail) {"))
        ungated = universe.replace(model.ORIGINAL_LEAF_REL, gate.replace("if (key is BiliPaiNavKey.VideoDetail) {", "if (key != null) {"))
        facts = model.route_facts(ungated.code[model.SHELL_REL], ungated.code[model.ORIGINAL_LEAF_REL], ungated.code)
        self.assertFalse(facts["holder route sits behind the VideoDetail key test"])


class RenderTest(unittest.TestCase):

    def test_the_inventory_is_printed_and_check_agrees_with_the_kotlin_defaults(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = model.main(["--check"])
        self.assertEqual(0, code)
        text = output.getvalue()
        for _, depth, _, witness, _ in EXPECTED_PROVIDERS:
            if depth == 1:
                self.assertIn(witness, text)
        self.assertIn("keyboard default may be true: False", text)
        self.assertIn("pointer default: True", text)
        self.assertIn("DesktopDetailWindow defaults: precisePointerConnected=True hardwareKeyboardConnected=False", text)
        self.assertEqual(len(EXPECTED_PROVIDERS), sum(1 for line in text.splitlines() if re.match(r"^\S+\.kt\s+\d+\s+\d+\s", line)))

    def test_the_tests_that_run_with_the_new_default_are_listed_for_the_bot(self):
        self.assertEqual(5, len(TEST_WRAPPERS))
        self.assertEqual(len(TEST_WRAPPERS), len(set(TEST_WRAPPERS.values())))


if __name__ == "__main__":
    unittest.main()
