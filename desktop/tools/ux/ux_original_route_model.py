"""Slice V12a: the checks behind the original-route mount fixture (spec 4.13, 9.2 and 10.3). No JVM, no network, nothing is generated or compiled.

The slice adds a Windows fixture, WindowsOriginalVideoRouteMountFixture, that opens an ordinary video in the actual Main window and records
whether the dormant original VideoDetail branch of the shell mounts, or whether the page under a VideoDetail key is the desktop-owned leaf
alone. This module holds everything a Python suite can say about it without running it:

  1. The premise. The shell's VideoDetail branch calls DesktopWindowsVideoPhysicalLeaf; the vendored VideoDetailScreenStateHolder,
     VideoPlayerSection and VideoPlayerOverlay are reachable only through DesktopOriginalVideoHolderRoute, which only the original leaf calls
     and the shell calls for the audio routes. ux_detail_window_model.route_facts (slice X5) states this as nine booleans; the call graph
     of the same scan says which composables each side can mount.
  2. The anchors. The fixture decides by accessible names. A leaf anchor must be spelled by the desktop leaf's control bar and by no file
     that defines a composable the original route reaches. An original anchor must be a literal in the body of a composable that the
     original route reaches and the desktop leaf does not, and a literal of no desktop file. Both lists are re-derived from the sources on
     every run, so a later change that makes a name ambiguous fails here instead of silently weakening the measurement.
  3. The verdict. The truth table of WindowsOriginalVideoRouteMount.classify, mirrored here and compared with the cases of the JVM test.
  4. The fixture text. Its protocol order, the receipt keys, the exit codes, and that it is read-only (no route, preference, key, input or
     file write beyond the report directory), each as a function of the source text, so the suite can also show that an edited copy fails.
  5. The startup premise of the protocol. The fixture follows the start of the live WindowsVideoActualRootUiFixture (first drawn Root,
     which must not be the agreement gate, then the health marker, then the startup --video= argument) and accepts no agreement, because
     Windows starts directly in Home: DesktopOriginalOnboardingPreferences.isRequired() is the constant false and no other desktop source
     asks for the gate. The model pins that policy, the parity with the live fixture's start, and that the fixture never calls the
     agreement helpers of the onboarding fixture (its protocol predates the removal of the gate and cannot be a template any more).
     The fixture presses and writes nothing and plays the video unmuted, so it is for the Windows cloud runner only; the check pins the
     KDoc sentence that says so.

The call graph is the one of ux_detail_window_model (PascalCase function names, an invocation Name( or Name { is an edge, equal names merge).
It over-approximates what can mount, which is the safe direction for an anchor: a name that a side "could" emit is rejected. Generated
sources are not part of the scan; the vendored files are used as written upstream, and the policy-extract generators keep string literals.

    python desktop/tools/ux/ux_original_route_model.py           print the premise, both anchor tables and the verdict table
    python desktop/tools/ux/ux_original_route_model.py --check   exit 1 on any problem
"""
import bisect
import functools
import itertools
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_source as source  # noqa: E402
import ux_detail_window_model as detail  # noqa: E402

REPO = source.REPO
UI_TEST = source.KT / "ui"
VERDICT_KT = UI_TEST / "WindowsOriginalVideoRouteMountVerdict.kt"
FIXTURE_KT = UI_TEST / "WindowsOriginalVideoRouteMountFixture.kt"
JVM_TEST_KT = UI_TEST / "WindowsOriginalVideoRouteMountVerdictTest.kt"
ACTIONS_KT = UI_TEST / "OriginalOnboardingUiActions.kt"
LIVE_FIXTURE_KT = UI_TEST / "WindowsVideoActualRootUiFixture.kt"
POLICY_REL = "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalOnboardingRootHost.kt"
STRINGS_KT = source.K / "ui/DesktopWindowsVideoControlBarStrings.kt"
BAR_KT = source.K / "ui/DesktopWindowsVideoControlBar.kt"
LEAF_KT = source.K / "ui/DesktopWindowsVideoPhysicalLeaf.kt"
MAIN_KT = source.K / "Main.kt"
BUILD_GRADLE = REPO / "desktop/build.gradle.kts"

FIXTURE_CLASS = "com.bilipai.desktop.ui.WindowsOriginalVideoRouteMountFixture"
JVM_TEST_CLASS = "com.bilipai.desktop.ui.WindowsOriginalVideoRouteMountVerdictTest"
NEW_NAMES = ("WindowsOriginalVideoRouteMountFixture", "WindowsOriginalVideoRouteMount", "OriginalVideoRouteVerdict",
             "OriginalVideoRouteExpectation", "WindowsOriginalVideoRouteMountVerdictTest")
HOLDER_ROUTE = "DesktopOriginalVideoHolderRoute"
DESKTOP_LEAF = "DesktopWindowsVideoPhysicalLeaf"
# The original page and the pieces of it the anchors come from, and the desktop pieces the leaf anchors come from.
ORIGINAL_PAGE_FUNCTIONS = ("VideoDetailScreenStateHolder", "VideoPlayerSection", "VideoPlayerOverlay", "UpInfoSection",
                           "NativeDanmakuToggleButton", "VideoContentTabBar", "PortraitTopBar", "BottomControlBar")
LEAF_CONSTANTS = {"详情": "DETAILS", "更多播放操作": "MORE_ACTIONS"}

EXPECTED_LEAF_ANCHORS = ["详情", "更多播放操作"]
EXPECTED_ORIGINAL_ANCHORS = ["UP主头像", "UP主标识", "开启弹幕", "关闭弹幕", "主界面", "评论区", "不喜欢", "分集", "登录发弹幕", "关闭卡顿提示"]

VERDICTS = ("DESKTOP_LEAF_ONLY", "ORIGINAL_ONLY", "BOTH", "INCONCLUSIVE")
EXPECTATION_ARGUMENTS = {"DESKTOP_LEAF": "desktop-leaf", "ORIGINAL": "original", "ANY": "any"}
EXPECTATION_ACCEPTS = {"desktop-leaf": {"DESKTOP_LEAF_ONLY"}, "original": {"ORIGINAL_ONLY"}, "any": {"DESKTOP_LEAF_ONLY", "ORIGINAL_ONLY", "BOTH"}}
CLASSIFY_LETS = ("val leafComplete = leafSeen.containsAll(LEAF_ANCHORS)", "val original = originalSeen.isNotEmpty()")
CLASSIFY_BRANCHES = (
    "leafComplete && !original -> OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY",
    "original && leafSeen.isEmpty() -> OriginalVideoRouteVerdict.ORIGINAL_ONLY",
    "original -> OriginalVideoRouteVerdict.BOTH",
    "else -> OriginalVideoRouteVerdict.INCONCLUSIVE",
)

GATE_CHECK = 'check(first.key != BiliPaiNavKey.Onboarding) { "Windows startup still mounted the mobile agreement gate" }'
# The order of main(): each token must occur after the one before it. It is the start of the live WindowsVideoActualRootUiFixture (fresh
# guest profile, observer, worker: first drawn Root, not the agreement gate, health marker, the startup video), then the measurement.
# actions.requireAcknowledged(false) stands three times: before Main starts, when the worker starts and after the measurement, which
# is what backs the receipt's preferencesWrittenByFixture=false.
PROTOCOL_ORDER = (
    "actions.requireAcknowledged(false)", "actions.requireFreshGuestStartup()", "DesktopOriginalRootValidationTap.install", "val worker = Thread({",
    "actions.requireAcknowledged(false)", "val first = awaitFirstRoot(latest)", GATE_CHECK, "actions.awaitActualHealth(health)",
    "val opened = actions.awaitVideo(0L, first.handle, bvid)", "check(opened.handle === first.handle)",
    "check(opened.key is BiliPaiNavKey.VideoDetail)", "val measurement = measure(opened)", "actions.requireAcknowledged(false)",
    "WindowsOriginalVideoRouteMount.classify(", "receipt(opened, bvid, measurement, verdict, expectation)", "Files.writeString(",
    "actions.capture(", "expectation.accepts(verdict)", "exitProcess(EXIT_EXPECTATION_MISSED)", "completed.set(true)",
    "actions.closeOwnedWindow(last)", "worker.start()", "com.bilipai.desktop.main(mainArgs)",
)
# What the route mount fixture shares, token for token, with the start of the live Windows video fixture.
LIVE_START_TOKENS = (
    "actions.requireFreshGuestStartup()", "DesktopOriginalRootValidationTap.install", "val worker = Thread({", "worker.isDaemon = true",
    "worker.start()", GATE_CHECK, "actions.awaitActualHealth(health)", '"--update-health-file", health.toString(), "--update-health-token"',
    '"--video=$', "com.bilipai.desktop.main(",
)
LIVE_FIRST_ROOT_WAIT = 'await("first actual drawn Root") { latest.get() != null }'
LIVE_SILENT_SEED = "PlayerPreferencesStore().save(PlayerPreferences(volume = 0.0, muted = true))"
AUDIO_WARNING = "Windows cloud runner only; never run on the user's machine (it plays the video unmuted)."
# Calls of the onboarding fixture's agreement protocol. Windows starts in Home, so none of them can have a purpose in this fixture.
STALE_AGREEMENT_CALLS = (
    r"\bawaitFreshAgreement\b", r"\backnowledgeThroughOriginalUi\b", r"\bacceptFreshAgreementToHome\b", r"\bclickClause\b",
    r"\bUserAgreementClause\b", r"\bdisagree\b", r"requireAcknowledged\(\s*true\s*\)",
)
# Wording of the first draft of the docstrings and the KDoc, which described the removed agreement step.
STALE_PHRASES = ("accepts the mandatory agreement", "agreement boxes", "confirm button", "onboarding fixture's deep-link",
                 "acknowledgement lands", "deep-link mode already uses")
RECEIPT_KEYS = (
    "schema", "slice", "fixture", "bvid", "keyType", "expectation", "verdict", "meetsExpectation", "originalVideoDetailBranchMounted",
    "desktopLeafMounted", "leafAnchors", "leafAnchorsSeen", "leafAnchorsMissing", "originalAnchors", "originalAnchorsSeen", "samples",
    "maxSurfaces", "readyMillis", "observedMillis", "basis", "routeMutatedByFixture", "preferencesWrittenByFixture", "realAccountUsed",
    "limits",
)
# Members of the shared helpers and of the Root handle the fixture may use. Anything else is an input the fixture must not have.
ALLOWED_ACTIONS = ("requireAcknowledged", "requireFreshGuestStartup", "awaitActualHealth", "awaitVideo", "capture", "closeOwnedWindow")
ALLOWED_ROUTES = ("currentKey", "owns")
ALLOWED_HANDLE = ("isActive", "route")
ALLOWED_FILES = ("list", "exists", "writeString")
FORBIDDEN_CODE = (
    r"\.push\s*\(", r"\.video\s*\(", r"\.back\s*\(", r"requestBack", r"doAccessibleAction", r"setTextContents", r"\bRobot\b",
    r"dispatchEvent", r"System\.setProperty", r"ProcessBuilder", r"Runtime\.getRuntime", r"deleteIfExists", r"Files\.(?:delete|move|copy|write)\b",
    r"\bKeyEvent\b", r"\bMouseEvent\b", r"\bKeyStroke\b", r"addAWTEventListener", r"\bPreferences\b", r"changePreferences", r"\.navigation\b",
    r"\bPlayerPreferences(?:Store)?\b",
)


# ---------------------------------------------------------------------------------------------------------------------
# Kotlin text helpers
# ---------------------------------------------------------------------------------------------------------------------

_LITERAL = re.compile(r'"((?:[^"\\\n$]|\\.)+)"')


def string_list(text, name):
    """The string literals of 'val NAME: List<String> = listOf(...)', or None when the declaration is not there."""
    code = source.mask_kotlin(text)
    match = re.search(r"\bval\s+" + re.escape(name) + r"\s*:\s*List<String>\s*=\s*listOf\s*\(", code)
    if not match:
        return None
    opening = match.end() - 1
    closing = detail.match_close(code, opening, "(", ")")
    if closing < 0:
        return None
    return re.findall(r'"([^"\\\n]*)"', text[opening:closing])


def constants(text):
    """{name: value} of the 'const val NAME = "value"' lines of a strings object."""
    return dict(re.findall(r'const val (\w+) = "([^"\\\n]*)"', text))


def function_body(text, name):
    """The text of the first function of that name, from its opening to its closing brace, or None."""
    code = source.mask_kotlin(text)
    for found, _, body_start, body_end in detail.scan_functions(code):
        if found == name:
            return text[body_start:body_end + 1]
    return None


def normalized(line):
    return " ".join(line.split())


# ---------------------------------------------------------------------------------------------------------------------
# The premise and the call graph
# ---------------------------------------------------------------------------------------------------------------------

@functools.lru_cache(maxsize=1)
def graph():
    """(universe, names reachable from the original route, names reachable from the desktop leaf)."""
    universe = detail.repo_universe()
    return universe, universe.reach({HOLDER_ROUTE}), universe.reach({DESKTOP_LEAF})


_TOKEN = re.compile(r'//|/\*|"""|"|\'')


def string_literals(text):
    """[(opening quote offset, closing quote offset, content)] of the string literals of Kotlin text, in order. Line and block comments
    (nested), raw strings and character literals are skipped, so a quoted word in a comment is not a literal; a template expression
    inside a string is skipped by brace counting. mask_kotlin cannot be used here: it blanks the quotes together with the content."""
    spans, position, size = [], 0, len(text)
    while True:
        match = _TOKEN.search(text, position)
        if not match:
            return spans
        token, opening = match.group(), match.start()
        if token == "//":
            line_end = text.find("\n", opening)
            position = size if line_end < 0 else line_end
        elif token == "/*":
            depth, cursor = 1, opening + 2
            while depth and cursor < size:
                nested, closing = text.find("/*", cursor), text.find("*/", cursor)
                if closing < 0:
                    cursor = size
                elif 0 <= nested < closing:
                    depth, cursor = depth + 1, nested + 2
                else:
                    depth, cursor = depth - 1, closing + 2
            position = cursor
        elif token == '"""':
            closing = text.find('"""', opening + 3)
            position = size if closing < 0 else closing + 3
        elif token == '"':
            cursor = opening + 1
            while cursor < size and text[cursor] != '"':
                if text[cursor] == "\\":
                    cursor += 2
                elif text.startswith("${", cursor):
                    depth, cursor = 1, cursor + 2
                    while cursor < size and depth:
                        depth += (text[cursor] == "{") - (text[cursor] == "}")
                        cursor += 1
                else:
                    cursor += 1
            spans.append((opening, cursor, text[opening + 1:cursor]))
            position = cursor + 1
        else:
            cursor = opening + 1
            while cursor < size and text[cursor] != "'":
                cursor += 2 if text[cursor] == "\\" else 1
            position = cursor + 1


@functools.lru_cache(maxsize=None)
def file_spans(rel):
    universe, _, _ = graph()
    return tuple(string_literals(universe.texts[rel]))


def spans_between(rel, start, end):
    """The string literals of a file whose opening quote lies in [start, end]."""
    spans = file_spans(rel)
    openings = [span[0] for span in spans]
    return spans[bisect.bisect_left(openings, start):bisect.bisect_right(openings, end)]


@functools.lru_cache(maxsize=None)
def file_literals(rel):
    """The string literals (comments excluded) of a whole scanned file."""
    return frozenset(content for _, _, content in file_spans(rel))


def spelled_in_file(rel, anchor):
    """True when the file has the anchor as a string literal (a cheap substring test first, then the comment-aware literals)."""
    universe, _, _ = graph()
    return anchor in universe.texts[rel] and anchor in file_literals(rel)


@functools.lru_cache(maxsize=None)
def function_literals(name):
    """The string literals (comments excluded) of the bodies of every function of that name."""
    universe, _, _ = graph()
    found = set()
    for rel, start, end in universe.nodes.get(name, ()):
        found.update(content for _, _, content in spans_between(rel, start, end))
    return frozenset(found)


def files_defining(names):
    universe, _, _ = graph()
    return {rel for name in names for rel, _, _ in universe.nodes.get(name, ())}


def desktop_main_files():
    """Repo-relative paths of the Kotlin files of desktop/src/main."""
    universe, _, _ = graph()
    return sorted(rel for rel in universe.texts if rel.startswith(detail.DESKTOP_MAIN))


_SEMANTIC_BEFORE = re.compile(r"(?:\bcontentDescription|\btext|\blabel)\s*=[^\n]*$|\b(?:App)?Text\(\s*[^\n]*$")


def semantic_occurrences(name, anchor):
    """How many times a function of that name spells the anchor where a screen reader or a button reads it: as the right side of
    contentDescription =, text = or label = on the same line, or as the first argument of Text( / AppText(."""
    universe, _, _ = graph()
    count = 0
    for rel, start, end in universe.nodes.get(name, ()):
        raw = universe.texts[rel]
        for opening, _, content in spans_between(rel, start, end):
            if content != anchor:
                continue
            line_start = raw.rfind("\n", 0, opening) + 1
            if _SEMANTIC_BEFORE.search(raw[line_start:opening]):
                count += 1
    return count


def premise_problems(universe=None):
    """Reasons the premise "the original VideoDetail branch is dormant and exclusive" does not hold in the sources."""
    universe, original_reach, leaf_reach = graph() if universe is None else (universe, universe.reach({HOLDER_ROUTE}), universe.reach({DESKTOP_LEAF}))
    problems = []
    facts = detail.route_facts(universe.code[detail.SHELL_REL], universe.code[detail.ORIGINAL_LEAF_REL], universe.code)
    problems += ["route fact is false: " + name for name, value in facts.items() if not value]
    for name in ORIGINAL_PAGE_FUNCTIONS:
        if name not in original_reach:
            problems.append("the original route no longer reaches " + name)
        if name in leaf_reach:
            problems.append("the desktop leaf now reaches " + name + ", so it is no longer an original-only witness")
    if "DesktopWindowsVideoControlBar" not in leaf_reach:
        problems.append("the desktop leaf no longer reaches its control bar")
    for entry in (HOLDER_ROUTE, DESKTOP_LEAF):
        if len(universe.nodes.get(entry, ())) != 1:
            problems.append("%s is declared %d times" % (entry, len(universe.nodes.get(entry, ()))))
    return problems


# ---------------------------------------------------------------------------------------------------------------------
# The anchors
# ---------------------------------------------------------------------------------------------------------------------

def indentation(line):
    return len(line) - len(line.lstrip())


def unconditional_in_bar(bar_text, constant):
    """True when the control bar button labelled with that strings constant is composed without any if or when between it and the bar row."""
    lines = bar_text.split("\n")
    wanted = "DesktopWindowsVideoControlBarStrings." + constant
    indexes = [i for i, line in enumerate(lines) if "label = " + wanted in line]
    if len(indexes) != 1:
        return False
    index = indexes[0]
    if re.search(r"\b(?:if|when)\s*\(", lines[index]):
        return False
    current = indentation(lines[index])
    for i in range(index - 1, -1, -1):
        line = lines[i]
        if not line.strip() or indentation(line) >= current:
            continue
        current = indentation(line)
        if re.search(r"\b(?:if|when)\s*\(", line):
            return False
        if "Row(Modifier.fillMaxWidth().heightIn(min = DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP.dp)" in line:
            return True
    return False


def leaf_anchor_problems(leaf, original, strings_text=None, bar_text=None, leaf_text=None):
    """Reasons the leaf anchors are not a sound identification of the desktop leaf's LEGACY control bar."""
    strings_text = source.read(STRINGS_KT) if strings_text is None else strings_text
    bar_text = source.read(BAR_KT) if bar_text is None else bar_text
    leaf_text = source.read(LEAF_KT) if leaf_text is None else leaf_text
    universe, original_reach, _ = graph()
    values = constants(strings_text)
    original_files = files_defining(original_reach)
    problems = []
    if not leaf:
        problems.append("no leaf anchor")
    for anchor in leaf:
        constant = LEAF_CONSTANTS.get(anchor)
        if constant is None or values.get(constant) != anchor:
            problems.append("%r is not a constant of DesktopWindowsVideoControlBarStrings that the bar uses as a label" % anchor)
        elif not unconditional_in_bar(bar_text, constant):
            problems.append("%r (%s) is composed under a condition in the control bar" % (anchor, constant))
        clash = sorted(rel.rsplit("/", 1)[1] for rel in original_files if spelled_in_file(rel, anchor))
        if clash:
            problems.append("%r is also a literal of a file the original route reaches: %s" % (anchor, clash[:4]))
        if anchor in original:
            problems.append("%r is both a leaf and an original anchor" % anchor)
    if "if (chromeVisible && legacyChrome) legacyControlBar()" not in leaf_text:
        problems.append("the leaf no longer mounts the LEGACY control bar under chromeVisible and legacyChrome")
    if "val chromeVisible = !fullscreen || chrome.visible" not in leaf_text:
        problems.append("chromeVisible is no longer true on page entry (not fullscreen)")
    return problems


def original_anchor_problems(original, leaf):
    """Reasons the original anchors are not names that only the vendored original page can emit."""
    universe, original_reach, leaf_reach = graph()
    original_only = sorted(set(original_reach) - set(leaf_reach))
    desktop = desktop_main_files()
    leaf_literals = set()
    for name in leaf_reach:
        leaf_literals |= function_literals(name)
    problems = []
    if not original:
        problems.append("no original anchor")
    if len(set(original)) != len(original):
        problems.append("duplicate original anchors")
    for anchor in original:
        witnesses = [name for name in original_only if anchor in function_literals(name)]
        if not witnesses:
            problems.append("%r is a literal of no composable that only the original route reaches" % anchor)
        elif not any(semantic_occurrences(name, anchor) for name in witnesses):
            problems.append("%r is never spelled as a content description, text or label by the composables %s" % (anchor, witnesses))
        if anchor in leaf_literals:
            problems.append("%r is also a literal of a composable the desktop leaf reaches" % anchor)
        clash = sorted(rel.rsplit("/", 1)[1] for rel in desktop if spelled_in_file(rel, anchor))
        if clash:
            problems.append("%r is also a literal of desktop sources: %s" % (anchor, clash[:4]))
        if anchor in leaf:
            problems.append("%r is both a leaf and an original anchor" % anchor)
    return problems


def anchor_witnesses(original):
    """{anchor: sorted original-only composables that spell it as a content description, text or label}, for the report."""
    _, original_reach, leaf_reach = graph()
    original_only = sorted(set(original_reach) - set(leaf_reach))
    return {anchor: [name for name in original_only if semantic_occurrences(name, anchor)] for anchor in original}


def anchor_tables(verdict_text=None):
    text = source.read(VERDICT_KT) if verdict_text is None else verdict_text
    return string_list(text, "LEAF_ANCHORS"), string_list(text, "ORIGINAL_ANCHORS")


# ---------------------------------------------------------------------------------------------------------------------
# The verdict
# ---------------------------------------------------------------------------------------------------------------------

def classify(leaf_seen, original_seen, leaf_anchors):
    """The truth table of WindowsOriginalVideoRouteMount.classify."""
    leaf_complete = set(leaf_anchors) <= set(leaf_seen)
    original = bool(original_seen)
    if leaf_complete and not original:
        return "DESKTOP_LEAF_ONLY"
    if original and not leaf_seen:
        return "ORIGINAL_ONLY"
    if original:
        return "BOTH"
    return "INCONCLUSIVE"


def accepts(expectation, verdict):
    return verdict in EXPECTATION_ACCEPTS[expectation]


def kotlin_cases(jvm_test_text):
    """[(leaf anchors seen, original anchors seen, verdict)] of the CASES table of the JVM test."""
    return [(int(a), int(b), verdict) for a, b, verdict in re.findall(
        r"Case\(leafSeen = (\d+), originalSeen = (\d+), verdict = OriginalVideoRouteVerdict\.(\w+)\)", jvm_test_text)]


def verdict_problems(verdict_text, jvm_test_text):
    """Reasons the Kotlin truth table, expectations or enums differ from the model."""
    problems = []
    body = function_body(verdict_text, "classify")
    if body is None:
        return ["classify is missing"]
    lets = [normalized(line) for line in re.findall(r"(?m)^\s*(val \w+ = [^\n]+)$", body)]
    if tuple(lets) != CLASSIFY_LETS:
        problems.append("classify declares %s" % lets)
    when = re.search(r"when\s*\{\n(.*?)\n\s*\}\s*\n?\s*\}$", body, re.S)
    branches = [normalized(line) for line in when.group(1).split("\n") if line.strip()] if when else []
    if tuple(branches) != CLASSIFY_BRANCHES:
        problems.append("classify branches are %s" % branches)
    entries = re.search(r"enum class OriginalVideoRouteVerdict \{(.*?)\n\}", verdict_text, re.S)
    names = re.findall(r"(?m)^\s+([A-Z_]+),?\s*$", entries.group(1)) if entries else []
    if tuple(names) != VERDICTS:
        problems.append("the verdict enum holds %s" % names)
    expectation_entries = re.findall(r'(?m)^\s+([A-Z_]+)\("([a-z-]+)"\)[,;]\s*$', verdict_text)
    if dict(expectation_entries) != EXPECTATION_ARGUMENTS:
        problems.append("the expectation arguments are %s" % dict(expectation_entries))
    accepted = {}
    accepts_body = function_body(verdict_text, "accepts") or ""
    for entry, verdict in re.findall(r"(?m)^\s+([A-Z_]+) -> verdict == OriginalVideoRouteVerdict\.(\w+)\s*$", accepts_body):
        accepted[EXPECTATION_ARGUMENTS.get(entry, entry)] = {verdict}
    for entry in re.findall(r"(?m)^\s+([A-Z_]+) -> verdict != OriginalVideoRouteVerdict\.INCONCLUSIVE\s*$", accepts_body):
        accepted[EXPECTATION_ARGUMENTS.get(entry, entry)] = {"DESKTOP_LEAF_ONLY", "ORIGINAL_ONLY", "BOTH"}
    if accepted != EXPECTATION_ACCEPTS:
        problems.append("accepts() does not match the expectations: %s" % accepted)
    leaf, original = anchor_tables(verdict_text)
    if leaf is None or original is None:
        problems.append("the anchor lists are missing")
        return problems
    cases = kotlin_cases(jvm_test_text)
    if len(cases) != 8:
        problems.append("the JVM test holds %d cases instead of 8" % len(cases))
    for leaf_count, original_count, verdict in cases:
        expected = classify(leaf[:leaf_count], original[:original_count], leaf)
        if expected != verdict:
            problems.append("case (%d, %d): the model says %s, the JVM test says %s" % (leaf_count, original_count, expected, verdict))
    return problems


def model_table(leaf, original):
    """[(leaf seen, original seen, verdict)] for every subset of the leaf anchors against none, each single, and all original anchors."""
    rows = []
    for size in range(len(leaf) + 1):
        for chosen in itertools.combinations(leaf, size):
            for original_seen in [()] + [(anchor,) for anchor in original] + [tuple(original)]:
                rows.append((chosen, original_seen, classify(chosen, original_seen, leaf)))
    return rows


# ---------------------------------------------------------------------------------------------------------------------
# The fixture text
# ---------------------------------------------------------------------------------------------------------------------

def worker_body(fixture_text):
    return function_body(fixture_text, "main") or ""


def structure_problems(text):
    problems = []
    first = next((line for line in text.split("\n") if line.strip()), "")
    if first != "package com.bilipai.desktop.ui":
        problems.append("the fixture is not in package com.bilipai.desktop.ui")
    code = source.mask_kotlin(text)
    if len(re.findall(r"(?m)^object WindowsOriginalVideoRouteMountFixture \{", code)) != 1:
        problems.append("there is not exactly one public object WindowsOriginalVideoRouteMountFixture")
    if len(re.findall(r"@JvmStatic fun main\(args: Array<String>\)", code)) != 1:
        problems.append("there is not exactly one @JvmStatic main(args: Array<String>)")
    if code.count("com.bilipai.desktop.main(") != 1:
        problems.append("the actual Main is not started exactly once")
    return problems


def protocol_problems(text):
    problems = []
    body = worker_body(text)
    if not body:
        return ["main() is missing"]
    position = -1
    for token in PROTOCOL_ORDER:
        found = body.find(token, position + 1)
        if found < 0:
            problems.append("main() lacks %r after position %d" % (token, position))
            break
        position = found
    catch = body.rfind("catch (failure: Throwable)")
    if catch < 0 or "exitProcess(EXIT_MEASUREMENT_FAILED)" not in body[catch:]:
        problems.append("a failure does not leave through EXIT_MEASUREMENT_FAILED")
    for token in ('"--video=$bvid"', '"--update-health-file"', '"--update-health-token"'):
        if token not in body:
            problems.append("the Main arguments lack " + token)
    consts = dict(re.findall(r"private const val (EXIT_\w+) = (\d+)", text))
    if consts != {"EXIT_MEASUREMENT_FAILED": "92", "EXIT_EXPECTATION_MISSED": "93"}:
        problems.append("exit codes are %s" % consts)
    if "CREATE_NEW" not in body:
        problems.append("the receipt is not written with CREATE_NEW")
    return problems


def argument_problems(text):
    problems = []
    body = worker_body(text)
    if "require(args.size in 4..5)" not in body:
        problems.append("the argument count is not 4 or 5")
    usage = re.search(r'"Usage: ([^"]*)"', body)
    if not usage or usage.group(1) != "<empty-report-dir> <health-file> <token> <public-BVID> [desktop-leaf|original|any]":
        problems.append("the usage line changed: %s" % (usage.group(1) if usage else None))
    for token in ("val report = Path.of(args[0])", "val health = Path.of(args[1])", "val token = args[2]", "val bvid = args[3]",
                  "OriginalVideoRouteExpectation.parse(args.getOrNull(4) ?: OriginalVideoRouteExpectation.DESKTOP_LEAF.argument)",
                  'Regex("BV[0-9A-Za-z]{10}").matches(bvid)', "UUID.fromString(token).toString() == token"):
        if token not in body:
            problems.append("main() lacks " + token)
    return problems


def receipt_keys(text):
    body = function_body(text, "receipt") or ""
    return re.findall(r'(?m)^\s*put\("(\w+)",', body)


def receipt_problems(text):
    problems = []
    keys = receipt_keys(text)
    if tuple(keys) != RECEIPT_KEYS:
        problems.append("receipt keys are %s" % keys)
    body = function_body(text, "receipt") or ""
    for token in ('put("schema", 1)', 'put("slice", "V12a")', 'put("routeMutatedByFixture", false)',
                  'put("preferencesWrittenByFixture", false)', 'put("realAccountUsed", false)'):
        if token not in body:
            problems.append("the receipt lacks " + token)
    return problems


def read_only_problems(text):
    """The fixture changes nothing: no route, preference, key, pointer or file write beyond the report directory."""
    code = source.mask_kotlin(text)
    problems = []
    for pattern in FORBIDDEN_CODE:
        match = re.search(pattern, code)
        if match:
            problems.append("forbidden %r at line %d" % (match.group(), code.count("\n", 0, match.start()) + 1))
    for owner, allowed in (("routes", ALLOWED_ROUTES), ("handle", ALLOWED_HANDLE), ("Files", ALLOWED_FILES)):
        used = set(re.findall(r"\b" + owner + r"\.(\w+)", code))
        if not used <= set(allowed):
            problems.append("%s members used beyond %s: %s" % (owner, allowed, sorted(used - set(allowed))))
    actions_used = set(re.findall(r"\bactions\.(\w+)", code))
    if not actions_used <= set(ALLOWED_ACTIONS) or not set(ALLOWED_ACTIONS) <= actions_used:
        problems.append("actions members used are %s" % sorted(actions_used))
    writes = re.findall(r"Files\.writeString\(([^\n]*)", code)
    if len(writes) != 1 or "report.resolve(RECEIPT_NAME)" not in text[text.find("Files.writeString("):text.find("Files.writeString(") + 120]:
        problems.append("the fixture writes something other than the receipt in the report directory")
    return problems


def observation_problems(text):
    """The sampling reads names of the main window and of the windows it owns, with the traversal bound of the other fixtures."""
    problems = []
    for token in ("Window.getWindows().filter { it.isShowing && ownedBy(main, it) }", "depth > 80", "node.accessibleName",
                  "check(EventQueue.isDispatchThread())", 'private const val MAIN_WINDOW_TITLE = "BiliPai Windows"',
                  "check(frame.routes.currentKey is BiliPaiNavKey.VideoDetail)", "check(opened.key is BiliPaiNavKey.VideoDetail)"):
        if token not in text:
            problems.append("the fixture lacks " + token)
    return problems


def helper_problems(fixture_text, actions_text):
    """The shared helper still has every member the fixture calls (and only those, see ALLOWED_ACTIONS), public, with the parameters the call
    passes. The agreement members of the same helper are deliberately not among them: Windows starts in Home."""
    problems = []
    if not re.search(r"(?m)^internal class OriginalOnboardingUiActions\(\s*$", actions_text):
        problems.append("OriginalOnboardingUiActions is not an internal class")
    constructor = re.search(r"OriginalOnboardingUiActions\(\s*private val latest: \(\) -> DesktopOriginalRootValidationTap\.Frame\?,\s*"
                            r"private val report: Path,\s*private val token: String,\s*\)", actions_text)
    if not constructor:
        problems.append("the constructor of OriginalOnboardingUiActions changed")
    if "OriginalOnboardingUiActions(latest::get, report, token)" not in fixture_text:
        problems.append("the fixture builds the helper differently")
    signatures = {
        "requireAcknowledged": r"(?m)^    fun requireAcknowledged\(expected: Boolean\)",
        "requireFreshGuestStartup": r"(?m)^    fun requireFreshGuestStartup\(\)",
        "awaitActualHealth": r"(?m)^    fun awaitActualHealth\(health: Path\)",
        "awaitVideo": r"(?m)^    fun awaitVideo\(after: Long, owner: DesktopReadyOriginalRootHandle, bvid: String\): DesktopOriginalRootValidationTap\.Frame",
        "capture": r"(?m)^    fun capture\(id: String, observation: DesktopOriginalRootValidationTap\.Frame\)",
        "closeOwnedWindow": r"(?m)^    fun closeOwnedWindow\(frame: DesktopOriginalRootValidationTap\.Frame\)",
    }
    for name, pattern in signatures.items():
        if not re.search(pattern, actions_text):
            problems.append("the helper member %s changed or became private" % name)
    return problems


def first_root_problems(text):
    """awaitFirstRoot waits for the first drawn Root like the live fixture does: it polls every 100 ms up to FIRST_ROOT_SECONDS, then fails."""
    body = function_body(text, "awaitFirstRoot")
    if body is None:
        return ["awaitFirstRoot is missing"]
    problems = []
    signature = (r"private fun awaitFirstRoot\(latest: AtomicReference<DesktopOriginalRootValidationTap\.Frame\?>\): "
                 r"DesktopOriginalRootValidationTap\.Frame \{")
    if not re.search(signature, source.mask_kotlin(text)):
        problems.append("the signature of awaitFirstRoot changed")
    for token in ("latest.get()", "Thread.sleep(100)", "Duration.ofSeconds(FIRST_ROOT_SECONDS)", "System.nanoTime()", "error("):
        if token not in body:
            problems.append("awaitFirstRoot lacks " + token)
    if not re.search(r"(?m)^    private const val FIRST_ROOT_SECONDS = 45L$", text):
        problems.append("FIRST_ROOT_SECONDS is not 45 seconds, the first-Root wait of the live fixture")
    return problems


def agreement_problems(text):
    """Windows starts in Home, so the fixture neither waits for nor accepts an agreement (code only; a comment may name the helpers)."""
    code = source.mask_kotlin(text)
    problems = []
    for pattern in STALE_AGREEMENT_CALLS:
        match = re.search(pattern, code)
        if match:
            problems.append("the fixture uses %r at line %d, but Windows startup has no agreement gate" % (
                match.group(), code.count("\n", 0, match.start()) + 1))
    if text.count(GATE_CHECK) != 1:
        problems.append("the fixture does not check exactly once that the first Root is not the agreement gate")
    return problems


def audio_problems(text):
    """The fixture seeds no silent profile, so its KDoc must keep the Windows-cloud-runner-only sentence (the seed itself is in FORBIDDEN_CODE)."""
    declaration = text.find("object WindowsOriginalVideoRouteMountFixture {")
    position = text.find(AUDIO_WARNING)
    if position < 0:
        return ["the KDoc lacks: " + AUDIO_WARNING]
    if declaration < 0 or position > declaration:
        return ["the cloud-runner-only sentence is not in the KDoc above the object"]
    return []


def stale_phrase_problems(texts):
    """texts is {label: text}; the wording of the removed agreement step must not survive in documentation."""
    return ["%s still says %r" % (label, phrase) for label, text in texts.items() for phrase in STALE_PHRASES if phrase in text]


def startup_policy_problems(codes=None, main_text=None):
    """Reasons "Windows starts in Home and --video= opens the video" no longer holds, which is what the fixture's protocol stands on.
    codes is {path: masked Kotlin} of the desktop main sources (default: the scanned tree); main_text the text of Main.kt."""
    if codes is None:
        universe, _, _ = graph()
        codes = {rel: universe.code[rel] for rel in desktop_main_files()}
    main_text = source.read(MAIN_KT) if main_text is None else main_text
    problems = []
    policy = codes.get(POLICY_REL)
    if policy is None:
        return ["%s is gone" % POLICY_REL]
    if len(re.findall(r"\bfun isRequired\(\): Boolean = false\b", policy)) != 1:
        problems.append("DesktopOriginalOnboardingPreferences.isRequired() is no longer the constant false: Windows may start on the agreement gate")
    values = sorted(value.strip() for code in codes.values() for value in re.findall(r"\bonboardingRequired\s*=\s*([^,\n]+)", code))
    if values != ["false", "isRequired()"]:
        problems.append("the values passed as onboardingRequired are %s instead of [false, isRequired()]" % values)
    shell = codes.get(detail.SHELL_REL, "")
    if not re.search(r"if\(destination != BiliPaiNavKey\.Onboarding && !initialVideoConsumed && initialVideo!=null\)\s*\{\s*"
                     r"initialVideoConsumed=true;rootRoutes\(\)\?\.video\(BiliPaiNavKey\.VideoDetail\(initialVideo,", shell):
        problems.append("the shell no longer pushes the startup video once the destination is not the agreement gate")
    if "val initialVideo = args.firstOrNull { it.startsWith(\"--video=\") }?.substringAfter('=')" not in main_text:
        problems.append("Main.kt no longer reads the startup argument --video=")
    return problems


def live_start_problems(fixture_text, live_text):
    """The fixture's start is the start of the live Windows video fixture: the shared tokens are in both, in the same order around the
    agreement-gate check and the health marker, and the live fixture still waits for its first drawn Root and seeds the silent profile."""
    problems = []
    for label, text in (("the live Windows video fixture", live_text), ("the route mount fixture", fixture_text)):
        for token in LIVE_START_TOKENS:
            if token not in text:
                problems.append("%s lacks %s" % (label, token))
        gate, health = text.find(GATE_CHECK), text.find("actions.awaitActualHealth(health)")
        if not 0 <= gate < health:
            problems.append("%s does not check the agreement gate before it waits for the health marker" % label)
    if LIVE_FIRST_ROOT_WAIT not in live_text:
        problems.append("the live fixture no longer waits for its first drawn Root the way awaitFirstRoot does")
    if LIVE_SILENT_SEED not in live_text:
        problems.append("the live fixture no longer seeds the silent profile, so the cloud-runner-only sentence may be obsolete")
    return problems


def gradle_snippet(fixture_text):
    """The Gradle task text of the KDoc of the fixture (the wiring Root adds), dedented, or None."""
    marker = "task would read as the following"
    start = fixture_text.find(marker)
    if start < 0:
        return None
    lines = []
    for raw in fixture_text[start:].split("\n")[1:]:
        stripped = raw.lstrip(" ")
        if not stripped.startswith("*"):
            return None
        body = stripped[1:]
        if body.strip() == "/":
            break
        if body.strip() == "" and not lines:
            continue
        if body.strip() == "":
            break
        lines.append(body[5:] if body.startswith("     ") else body.lstrip())
    return "\n".join(lines).rstrip() if lines else None


def build_task(build_text, name):
    """The text of tasks.register<JavaExec>(name) { ... } in the build file, or None."""
    start = build_text.find('tasks.register<JavaExec>("%s")' % name)
    if start < 0:
        return None
    opening = build_text.index("{", start)
    closing = detail.match_close(build_text, opening, "{", "}")
    return build_text[start:closing + 1] if closing > 0 else None


def snippet_problems(fixture_text, build_text):
    """The KDoc snippet is the existing windowsVideoActualRootUiSmoke task with this fixture's name, class and arguments, and the build file,
    where it registers the new task at all, registers exactly the snippet."""
    problems = []
    snippet = gradle_snippet(fixture_text)
    if snippet is None:
        return ["the KDoc has no Gradle task snippet"]
    reference = build_task(build_text, "windowsVideoActualRootUiSmoke")
    if reference is None:
        return ["the reference task windowsVideoActualRootUiSmoke is gone from the build file"]
    expected = reference.replace("windowsVideoActualRootUiSmoke", "windowsOriginalVideoRouteMountSmoke")
    expected = expected.replace("com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture", FIXTURE_CLASS)
    if [normalized(line) for line in snippet.split("\n")] != [normalized(line) for line in expected.split("\n")]:
        problems.append("the KDoc snippet differs from the reference task with the fixture's name and class")
    wired = build_task(build_text, "windowsOriginalVideoRouteMountSmoke")
    if wired is not None and [normalized(line) for line in wired.split("\n")] != [normalized(line) for line in snippet.split("\n")]:
        problems.append("the build file registers the task differently from the KDoc snippet")
    return problems


def all_problems():
    """Every problem of the real tree, grouped by check; empty lists everywhere is a pass."""
    verdict_text, jvm_text, fixture_text = source.read(VERDICT_KT), source.read(JVM_TEST_KT), source.read(FIXTURE_KT)
    leaf, original = anchor_tables(verdict_text)
    result = {
        "premise": premise_problems(),
        "leaf anchors": leaf_anchor_problems(leaf or [], original or []),
        "original anchors": original_anchor_problems(original or [], leaf or []),
        "verdict": verdict_problems(verdict_text, jvm_text),
        "structure": structure_problems(fixture_text),
        "protocol": protocol_problems(fixture_text),
        "arguments": argument_problems(fixture_text),
        "receipt": receipt_problems(fixture_text),
        "read only": read_only_problems(fixture_text),
        "observation": observation_problems(fixture_text),
        "helper": helper_problems(fixture_text, source.read(ACTIONS_KT)),
        "first root": first_root_problems(fixture_text),
        "agreement": agreement_problems(fixture_text),
        "audio": audio_problems(fixture_text),
        "startup policy": startup_policy_problems(),
        "live fixture start": live_start_problems(fixture_text, source.read(LIVE_FIXTURE_KT)),
        "stale wording": stale_phrase_problems({"the fixture": fixture_text, "the model docstring": __doc__}),
        "gradle snippet": snippet_problems(fixture_text, source.read(BUILD_GRADLE)),
    }
    if leaf != EXPECTED_LEAF_ANCHORS:
        result["leaf anchors"].append("the leaf anchor list is %s" % leaf)
    if original != EXPECTED_ORIGINAL_ANCHORS:
        result["original anchors"].append("the original anchor list is %s" % original)
    return result


def main(argv):
    verdict_text = source.read(VERDICT_KT)
    leaf, original = anchor_tables(verdict_text)
    print("leaf anchors: %s" % leaf)
    print("original anchors and the original-only composables that spell them:")
    for anchor, names in anchor_witnesses(original).items():
        print("  %-10s %s" % (anchor, ", ".join(names)))
    print("verdict table (leaf anchors seen x original anchors seen; one original anchor stands for any single one):")
    for chosen, original_seen, verdict in model_table(leaf, original):
        if len(original_seen) != 1 or original_seen == (original[0],):
            print("  leaf=%-22s original=%d  %s" % ("+".join(chosen) or "none", len(original_seen), verdict))
    problems = all_problems()
    for name, items in problems.items():
        print("%-18s %s" % (name, "ok" if not items else "; ".join(items)))
    if "--check" in argv and any(problems.values()):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
