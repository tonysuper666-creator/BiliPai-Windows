"""Shared read-only helpers of the desktop UX source suites (no JVM, no network).

Scopes. The UX program creates files by name (spec 8.2). The globs below arm each rule for those names before the
files exist, so a later slice cannot forget a rule: the moment its file appears the rule applies. A rule
violation is either fixed or listed in ux-hygiene-allowlist.json with an owner and a reason; an entry whose
violation is gone is stale and fails, so the list can only shrink.

Rule scopes. STRICT_TEXT_GLOBS name the files whose text must live in a *Strings.kt object; PROGRAM_GLOBS are every
file the program creates (the strict files plus PROGRAM_EXTRA_GLOBS). The no-chinese rule runs over PROGRAM_GLOBS
(spec 10.1: no hard-coded Chinese outside the strings objects), the hook and key-fallback rules run over PROGRAM_GLOBS
as well, and the banned-import rule runs over BANNED_IMPORT_GLOBS. A file that cannot yet move its text into a
Strings object is listed in the allowlist with an owner, never silently skipped.

Hooks. The global-hook rule bans Toolkit.addAWTEventListener and the other global AWT and OS hooks. Slice V0 (the
carrier wheel input, spec 6.4) is modelled on DesktopWindowsDisplayScaleInput, which does use
Toolkit.addAWTEventListener: V0 must register through window.addMouseWheelListener instead, or add an allowlist entry
{rule: global-hook, owner: V0, reason: ...} that says why a global listener is unavoidable.

Material. The banned-import rule matches the pinned classpath's two Material packages, androidx.compose.material3 and
androidx.compose.material (Material 2), for the same banned names.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in
desktop/tools/tests as spec 8.2 and 9.2 say. Root adds each suite to the validation plan as an explicit
`-s desktop/tools/ux -p <file>` line.

Line endings. read() normalises CRLF to LF so a CRLF working copy reads like the LF blob; assert_lf_only() asks git
for the index line-ending word and falls back to a bare carriage-return scan.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
K = REPO / "desktop/src/main/kotlin/com/bilipai/desktop"
KT = REPO / "desktop/src/test/kotlin/com/bilipai/desktop"
ALLOWLIST = HERE / "ux-hygiene-allowlist.json"

sys.path.insert(0, str(REPO / "desktop/tools"))
from check_slice_symbol_closure import mask_kotlin  # noqa: E402  (the repository's own Kotlin lexer)

# Spec 5.5: no material3 (or Material 2) FilterChip, Switch, DropdownMenu, AlertDialog, TextButton, ModalBottomSheet or
# TabRow in ui/kit, DesktopPlayer*.kt, the rail and the re-skinned settings files. Paths are relative to K.
BANNED_NAMES = ("FilterChip", "Switch", "DropdownMenu", "AlertDialog", "TextButton", "ModalBottomSheet", "TabRow")
BANNED_IMPORT_GLOBS = (
    "ui/kit/**/*.kt",
    "ui/DesktopPlayer*.kt",
    "ui/DesktopFlyout*.kt",
    "ui/DesktopWindowsNavigationRail.kt",
    "ui/DesktopWindowsRailModel.kt",
    "ui/DesktopWindowsShellFrame.kt",
    "ui/DesktopWindowsNowPlayingBar.kt",
    "ui/DesktopWindowsToast.kt",
    # The re-skinned settings files (spec 3.7, slice X3). They exist today and are allowlisted until X3 migrates them.
    "ui/DesktopWindowsSettingsControls.kt",
    "ui/DesktopWindowsUxSettings.kt",
    "ui/DesktopWindowsTipsSettings.kt",
    "settings/DesktopSettingsTree.kt",
    "appearance/DesktopWindowsDisplayScaleSettings.kt",
)
# Files whose user text lives in a *Strings.kt object (the strict core of PROGRAM_GLOBS).
STRICT_TEXT_GLOBS = (
    "appearance/DesktopWindowsUx*.kt",
    "appearance/DesktopWindowsCarrierScaleInput.kt",
    "appearance/DesktopSystemHighContrast.kt",
    "ui/kit/**/*.kt",
    "ui/DesktopPlayer*.kt",
    "ui/DesktopFlyout*.kt",
    "ui/DesktopShell*.kt",
    "ui/DesktopWindowsNavigationRail.kt",
    "ui/DesktopWindowsRailModel.kt",
    "ui/DesktopWindowsShellFrame.kt",
    "ui/DesktopWindowsNowPlayingBar.kt",
    "ui/DesktopWindowsToast.kt",
    "ui/DesktopWindowsLayoutMetrics.kt",
    "ui/DesktopWindowsVideoShortcutInput.kt",
)
# Files the program creates that are not in STRICT_TEXT_GLOBS. They get the same text rule (no Chinese outside a
# *Strings.kt object), the hook rule and the key-fallback rule.
PROGRAM_EXTRA_GLOBS = (
    "ui/DesktopWindowsUxSettings.kt",
    "ui/DesktopWindowsCaptionTheme.kt",
    "ui/DesktopHomeFeedLayout.kt",
    "ui/DesktopCardContextMenu.kt",
    "ui/DesktopCardPointer.kt",
    "ui/DesktopLazyScrollbars.kt",
    "ui/DesktopFeedScrollbar.kt",
    "ui/DesktopFeedGridHost.kt",
    "ui/DesktopHomeCardFrame.kt",
    "ui/DesktopHomeCardMenu.kt",
)
# Every file the program creates: no Chinese outside a Strings object, no global AWT or OS hook, no second
# key-fallback registration (spec P10, 4.2 rule 8, 10.1).
PROGRAM_GLOBS = STRICT_TEXT_GLOBS + PROGRAM_EXTRA_GLOBS

HOOK_PATTERN = re.compile(
    r"addAWTEventListener|AWTEventListener|addKeyEventDispatcher|addKeyEventPostProcessor|KeyEventDispatcher|"
    r"KeyEventPostProcessor|SetWindowsHookEx|RegisterHotKey|SetWinEventHook|\bGlobalScreen\b|NativeKeyListener|"
    r"NativeMouseListener|NativeMouseWheelListener")
KEY_FALLBACK_PATTERN = re.compile(r"KeyFallback")
CJK_RANGES = ((0x3000, 0x303F), (0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0xFF00, 0xFFEF))
_CJK = re.compile("[" + "".join("\\u%04x-\\u%04x" % pair for pair in CJK_RANGES) + "]")
_UNICODE_ESCAPE = re.compile(r"\\u([0-9A-Fa-f]{4})")
# Both Material packages of the pinned classpath: material3 and material (Material 2, material-desktop-1.12.1).
# "androidx.compose.material.icons.Icons" does not match: the name must end the import, and "icons" is not banned.
_IMPORT_MATERIAL = re.compile(
    r"(?m)^[ \t]*import[ \t]+androidx\.compose\.material3?\.(\*|\w+)(?:[ \t]+as[ \t]+\w+)?[ \t]*;?[ \t]*$")
_QUALIFIED_MATERIAL = re.compile(r"androidx\.compose\.material3?\.(\w+)")
_FAMILY = "|".join(
    "|".join((name, name + "Defaults", name + "Item", "Basic" + name, "Elevated" + name, "remember" + name + "State",
              "Primary" + name, "Secondary" + name, "Scrollable" + name)) for name in BANNED_NAMES)
_STAR_USE = re.compile(r"(?<![\w.])(" + _FAMILY + r")\b")


def read(path):
    """UTF-8 text with CRLF normalised to LF."""
    return Path(path).read_bytes().replace(b"\r\n", b"\n").decode("utf-8")


def relative_posix(path):
    return Path(path).resolve().relative_to(REPO).as_posix()


def kotlin_files(globs):
    """Existing files under K that match any glob (paths relative to K), sorted."""
    found = set()
    for pattern in globs:
        found.update(path for path in K.glob(pattern) if path.is_file())
    return sorted(found)


def rel(path):
    """Path relative to K, with forward slashes."""
    return Path(path).resolve().relative_to(K).as_posix()


def is_strings_file(path):
    return Path(path).name.endswith("Strings.kt")


def _is_banned(name):
    return any(token in name for token in BANNED_NAMES)


def banned_imports(text):
    """[(line, name)] for material3 or Material 2 imports or uses of the banned families. Comments and string contents
    are ignored."""
    code = mask_kotlin(text)
    findings = []
    for match in _IMPORT_MATERIAL.finditer(code):
        name = match.group(1)
        if name != "*" and _is_banned(name):
            findings.append((code.count("\n", 0, match.start()) + 1, name))
        elif name == "*":
            # A star import is a violation only where a banned name is used.
            for use in _STAR_USE.finditer(code):
                findings.append((code.count("\n", 0, use.start()) + 1, use.group(1)))
    for match in _QUALIFIED_MATERIAL.finditer(code):
        if _is_banned(match.group(1)) and not code[:match.start()].rstrip(" \t").endswith("import"):
            findings.append((code.count("\n", 0, match.start()) + 1, match.group(1)))
    return sorted(set(findings))


def cjk_positions(text):
    """[(line, character)] of every Chinese or fullwidth character, including escapes of such characters."""
    found = []
    for match in _CJK.finditer(text):
        found.append((text.count("\n", 0, match.start()) + 1, match.group()))
    for match in _UNICODE_ESCAPE.finditer(text):
        code_point = int(match.group(1), 16)
        if any(low <= code_point <= high for low, high in CJK_RANGES):
            found.append((text.count("\n", 0, match.start()) + 1, match.group()))
    return sorted(found)


def hook_uses(text):
    """[(line, name)] of global AWT or OS hook identifiers in code. Comments and string contents are ignored."""
    code = mask_kotlin(text)
    return [(code.count("\n", 0, m.start()) + 1, m.group()) for m in HOOK_PATTERN.finditer(code)]


def key_fallback_uses(text):
    code = mask_kotlin(text)
    return [(code.count("\n", 0, m.start()) + 1, m.group()) for m in KEY_FALLBACK_PATTERN.finditer(code)]


def load_allowlist():
    return json.loads(read(ALLOWLIST))["entries"]


def load_allowlist_about():
    return json.loads(read(ALLOWLIST))["about"]


def allowlist_problems(entries, findings, existing):
    """Reasons the allowlist is wrong. findings: {rule: {relative path: [finding, ...]}} for files in scope;
    existing: {rule: set of relative paths in scope}. Entries need an owner and a reason, must name a file in the
    scope of their rule, and must still be needed (a stale entry fails)."""
    problems = []
    seen = set()
    for entry in entries:
        rule, path = entry.get("rule"), entry.get("path")
        for field in ("rule", "path", "owner", "reason"):
            if not str(entry.get(field, "")).strip():
                problems.append("entry %r lacks %s" % (entry, field))
        if (rule, path) in seen:
            problems.append("duplicate entry %s %s" % (rule, path))
        seen.add((rule, path))
        if rule not in existing:
            problems.append("unknown rule %s" % rule)
        elif path not in existing[rule]:
            problems.append("%s: %s is not an existing file in the scope of the rule" % (rule, path))
        elif not findings.get(rule, {}).get(path):
            problems.append("%s: %s no longer violates the rule; remove its allowlist entry" % (rule, path))
    for rule, by_path in findings.items():
        for path, items in by_path.items():
            if items and (rule, path) not in seen:
                problems.append("%s: %s violates the rule and is not allowlisted: %s" % (rule, path, items[:3]))
    return problems


def index_eol(paths):
    """{repo-relative path: git index line-ending word} for tracked files, or None when git cannot answer."""
    names = [relative_posix(path) for path in paths]
    if not names:
        return {}
    try:
        completed = subprocess.run(["git", "-c", "core.autocrlf=false", "ls-files", "--eol", "--"] + names,
                                   cwd=str(REPO), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    if completed.returncode != 0:
        return None
    words = {}
    for line in completed.stdout.decode("utf-8", "replace").splitlines():
        meta, tab, name = line.partition("\t")
        fields = meta.split()
        if tab and fields and fields[0].startswith("i/"):
            words[name] = fields[0][2:]
    return words


def assert_lf_only(test, *paths):
    """No bare carriage return in the working copy; the committed blob is LF; a new (untracked) file is pure LF."""
    paths = [Path(path) for path in paths]
    words = index_eol(paths)
    for path in paths:
        name = relative_posix(path)
        test.assertTrue(path.is_file(), name)
        data = path.read_bytes()
        test.assertIsNone(re.search(rb"\r(?!\n)", data), "%s holds a bare carriage return" % name)
        word = None if words is None else words.get(name)
        if word is None:
            test.assertNotIn(b"\r", data, "%s is not tracked yet and must be pure LF" % name)
        else:
            test.assertIn(word, ("lf", "none"), "%s is committed with %s line endings" % (name, word))
