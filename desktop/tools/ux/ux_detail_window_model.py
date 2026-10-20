"""Step 0 of slice X5 (spec 3.8, decision D15): who reads the two input flags of DesktopDetailWindow and which of its call sites
host a vendored player. No JVM, no network, nothing is generated or compiled.

DesktopDetailWindow (ui/DesktopDetailWindow.kt) provides LocalAppWindowAdaptiveInfo, whose precisePointerConnected and
hardwareKeyboardConnected flags the vendored input policy turns into enableHoverEffects, enablePointerFocus and
enableKeyboardNavigation. This module answers three questions from the committed sources, as plain text analysis:

  1. Providers. Every call of DesktopDetailWindow in desktop/src/main (the 12 sites wrapped around vendored pages in DesktopShell.kt,
     the four other wrappers, the Bangumi player leaf), with its nesting, its explicit flags and the flags it therefore sees.
  2. Readers. Which vendored files read the raw flags, call the policy and read each policy output. The result is small: the
     keyboard output is read by exactly two player files, and the hover output only by a card modifier that the home card
     generator already rewrites to "always on", so the pointer flag has no live reader that it still switches.
  3. Hosting. Which vendored player entry points (the overlay, the player section, the Bangumi, offline and audio screens) each
     provider governs. A composable sees the flags of its NEAREST enclosing provider, so the analysis cuts at nested providers: the
     lambda of a nested DesktopDetailWindow belongs to the nested provider, not to the one around it. Reachability is a call graph over
     PascalCase function names (every function of desktop/src/main/kotlin and of the vendored src/main roots): an invocation Name( or
     Name { inside a function body is an edge to every function of that name. That over-approximates (a branch that is never taken still
     counts, equal names merge) and misses mainly a composable that is stored in a value and invoked elsewhere, so a "reaches" answer says
     "can mount", which is what a default flag has to be safe against, and a "does not reach" answer holds up to that one blind spot.

The decision (decide_keyboard_default) is the rule of spec 3.8 in code: hardwareKeyboardConnected may default to true only when no
provider that takes the default hosts a vendored player overlay. The pointer flag has no such constraint. Generated sources are not
part of the scan (no generator is run); the generator edits that matter to the answer are pinned separately by the suite.

    python desktop/tools/ux/ux_detail_window_model.py           print the inventory and the decision
    python desktop/tools/ux/ux_detail_window_model.py --check   exit 1 when DesktopDetailWindow.kt disagrees with the decision
"""
import functools
import hashlib
import re
import sys
from collections import deque
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_source as source  # noqa: E402

REPO = source.REPO
DESKTOP_MAIN = "desktop/src/main/kotlin"
SCAN_ROOTS = ("app", "design-system", "core-data", "core-player", "danmaku-engine", "settings-core", "plugin-sdk",
              "network-core", "miuix-navigation", DESKTOP_MAIN)

DETAIL_WINDOW_REL = "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDetailWindow.kt"
SHELL_REL = "desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt"
BANGUMI_LEAF_REL = "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPlayerPhysicalLeaf.kt"
ORIGINAL_LEAF_REL = "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoPhysicalLeaf.kt"
OFFLINE_INPUT_REL = "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOfflinePointerInput.kt"
MPV_INPUT_REL = "desktop/src/main/kotlin/com/bilipai/desktop/player/MpvNativeWindowInput.kt"
POLICY_REL = "app/src/main/java/com/android/purebilibili/core/ui/adaptive/InputDevicePolicy.kt"
HOVER_MODIFIER_REL = "app/src/main/java/com/android/purebilibili/core/ui/adaptive/AdaptiveInputModifiers.kt"
PLAYER_KEY_POLICY_REL = "app/src/main/java/com/android/purebilibili/feature/video/player/PlayerKeyboardPolicy.kt"
FULLSCREEN_KEY_POLICY_REL = "app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/FullscreenKeyboardPolicy.kt"
HOME_FULL_CARD_GENERATOR = REPO / "desktop/tools/extract-upstream-home-full-card.py"

# The vendored entry points a provider can host. The overlay set is "a vendored player overlay" of spec 3.8; the keyboard readers are the
# files that read enableKeyboardNavigation (the suite derives that set from the text and checks it is inside the overlay set).
PLAYER_OVERLAYS = ("FullscreenPlayerOverlay", "VideoPlayerOverlay", "VideoPlayerSection")
PLAYER_SCREENS = ("AudioModeScreen", "BangumiPlayerOverlayHost", "BangumiPlayerScreen", "LivePlayerScreen", "OfflineVideoPlayerScreen")
PLAYER_SINKS = tuple(sorted(PLAYER_OVERLAYS + PLAYER_SCREENS))
POLICY_OUTPUTS = ("enableHoverEffects", "enableKeyboardNavigation", "enablePointerFocus")
RAW_FLAGS = ("precisePointerConnected", "hardwareKeyboardConnected")
DETAIL_WINDOW_PARAMETERS = ("modifier", "precisePointerConnected", "hardwareKeyboardConnected", "content")


# ---------------------------------------------------------------------------------------------------------------------
# Kotlin text scanning (on the masked text: comments and string contents are blanked, offsets are those of the source)
# ---------------------------------------------------------------------------------------------------------------------

def match_close(code, index, open_char, close_char):
    """Index of the bracket that closes the one at code[index], or -1."""
    depth = 0
    for position in range(index, len(code)):
        char = code[position]
        if char == open_char:
            depth += 1
        elif char == close_char:
            depth -= 1
            if depth == 0:
                return position
    return -1


_FUN = re.compile(r"\bfun\b")
_IDENTIFIER = re.compile(r"[A-Za-z_]\w*")
_NEXT_DECLARATION = re.compile(r"(?:@|}|fun\b|class\b|object\b|interface\b|val\b|var\b|private\b|internal\b|public\b|data\b|enum\b|sealed\b|open\b|"
                               r"abstract\b|override\b|import\b|package\b|typealias\b)")
_CALL = re.compile(r"(?<![\w`])([A-Z][A-Za-z0-9_]*)\s*(?:<[^<>()]*(?:<[^<>()]*>[^<>()]*)*>)?\s*([({])")
_BEFORE_NON_CALL = re.compile(r"(?:class|object|interface|is|as|:)$")


def scan_functions(code):
    """[(name, decl_start, body_start, body_end)] of every function with a body. A function without a body (an interface member) is
    skipped; an expression body ends at the next line that starts a declaration at the indentation of the function or less."""
    found = []
    for match in _FUN.finditer(code):
        index, depth, opening = match.end(), 0, -1
        while index < len(code) and index - match.end() < 400:
            char = code[index]
            if char == "<":
                depth += 1
            elif char == ">":
                depth -= 1
            elif char == "(" and depth <= 0:
                opening = index
                break
            elif char in "{=;" and depth <= 0:
                break
            index += 1
        if opening < 0:
            continue
        names = _IDENTIFIER.findall(code[match.end():opening])
        closing = match_close(code, opening, "(", ")")
        if not names or closing < 0:
            continue
        index = closing + 1
        while index < len(code) and code[index] not in "{=":
            if code[index] == "\n" and _NEXT_DECLARATION.match(code[index + 1:index + 80].lstrip()):
                index = -1
                break
            index += 1
        if index < 0 or index >= len(code):
            continue
        line_start = code.rfind("\n", 0, match.start()) + 1
        if code[index] == "{":
            end = match_close(code, index, "{", "}")
            if end < 0:
                continue
        else:
            indent, end, probe = match.start() - line_start, len(code), index
            while True:
                newline = code.find("\n", probe)
                if newline < 0:
                    break
                following = re.match(r"([ \t]*)(\S.*)", code[newline + 1:newline + 201])
                if following and len(following.group(1)) <= indent and _NEXT_DECLARATION.match(following.group(2)):
                    end = newline
                    break
                probe = newline + 1
        found.append((names[-1], line_start, index, end))
    return found


def invocations(code, start, end):
    """Names invoked as Name(...) or Name { ... } inside code[start:end], in source order, without repeats."""
    names = []
    for match in _CALL.finditer(code, start, end):
        if match.group(2) == "{" and _BEFORE_NON_CALL.search(code[max(0, match.start() - 12):match.start()].rstrip()):
            continue
        if match.group(1) not in names:
            names.append(match.group(1))
    return names


def provider_regions(code, start, end, skip_declaration=True):
    """[(call_start, lambda_start, lambda_end, args)] of the outermost DesktopDetailWindow calls with a lambda inside code[start:end]."""
    regions, position = [], start
    token = re.compile(r"\bDesktopDetailWindow\b")
    while True:
        match = token.search(code, position, end)
        if not match:
            return regions
        position = match.end()
        if skip_declaration and re.search(r"\bfun\s+$", code[max(0, match.start() - 8):match.start()]):
            continue
        cursor = match.end()
        while cursor < end and code[cursor] in " \t\r\n":
            cursor += 1
        args = ""
        if cursor < end and code[cursor] == "(":
            closing = match_close(code, cursor, "(", ")")
            if closing < 0:
                continue
            args = code[cursor + 1:closing]
            cursor = closing + 1
            while cursor < end and code[cursor] in " \t\r\n":
                cursor += 1
        if cursor < end and code[cursor] == "{":
            lambda_end = match_close(code, cursor, "{", "}")
            if lambda_end > 0:
                regions.append((match.start(), cursor, lambda_end, args))
                position = lambda_end + 1


def exclusive_invocations(code, start, end):
    """Invocations of code[start:end] outside every nested DesktopDetailWindow lambda (those run under the nested provider's flags)."""
    names, position = [], start
    for call_start, _, lambda_end, _ in provider_regions(code, start, end):
        names += [name for name in invocations(code, position, call_start) if name not in names]
        position = lambda_end + 1
    names += [name for name in invocations(code, position, end) if name not in names]
    return names


def sha256_of(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------------------------------------------------
# The universe of sources and the call graph
# ---------------------------------------------------------------------------------------------------------------------

def repo_texts():
    """{repo-relative path: text} of the Kotlin of the scan roots (src/main only; the desktop root is src/main already)."""
    texts = {}
    for root in SCAN_ROOTS:
        base = REPO / root
        for path in sorted(base.rglob("*.kt")) if base.is_dir() else ():
            rel = path.relative_to(REPO).as_posix()
            if "/build/" in "/" + rel:
                continue  # a local Gradle build folder is output, not source
            if root == DESKTOP_MAIN or "/src/main/" in "/" + rel:
                texts[rel] = source.read(path)
    return texts


class Universe:
    """The scanned sources with their masked text, their functions and the call graph. replace() makes a changed copy (the suite's
    mutation checks use it, nothing is written to disk)."""

    def __init__(self, texts, code=None, functions=None):
        self.texts = dict(texts)
        self.code = dict(code) if code is not None else {rel: source.mask_kotlin(text) for rel, text in self.texts.items()}
        self.functions = dict(functions) if functions is not None else {rel: scan_functions(code) for rel, code in self.code.items()}
        self.nodes = {}
        for rel in sorted(self.functions):
            for name, _, body_start, body_end in self.functions[rel]:
                if name[0].isupper():
                    self.nodes.setdefault(name, []).append((rel, body_start, body_end))
        self._edges = {}

    def replace(self, rel, text):
        """A copy in which one file has other text; the other files keep their masked text and functions."""
        texts, code, functions = dict(self.texts), dict(self.code), dict(self.functions)
        texts[rel] = text
        code[rel] = source.mask_kotlin(text)
        functions[rel] = scan_functions(code[rel])
        return Universe(texts, code, functions)

    def edges(self, name):
        if name not in self._edges:
            found = []
            for rel, start, end in self.nodes.get(name, ()):
                found += [callee for callee in exclusive_invocations(self.code[rel], start, end) if callee not in found]
            self._edges[name] = found
        return self._edges[name]

    def reach(self, start_names):
        """Breadth-first {function name: the name it was reached from (None for a start)} over the call graph, neighbours in sorted order."""
        previous, queue = {}, deque()
        for name in sorted(start_names):
            if name in self.nodes and name not in previous:
                previous[name] = None
                queue.append(name)
        while queue:
            name = queue.popleft()
            for callee in sorted(self.edges(name)):
                if callee in self.nodes and callee not in previous:
                    previous[callee] = name
                    queue.append(callee)
        return previous

    @staticmethod
    def path_to(previous, target):
        """The shortest chain of names from a start to target, or None."""
        if target not in previous:
            return None
        chain = []
        while target is not None:
            chain.append(target)
            target = previous[target]
        return tuple(reversed(chain))

    def enclosing_function(self, rel, position):
        """The outermost PascalCase function of the file whose body holds the offset, or None."""
        spans = [(start, end, name) for name, start, _, end in self.functions[rel] if name[0].isupper() and start <= position <= end]
        return min(spans)[2] if spans else None

    def function_texts(self, rel, name):
        """The source text of every function of that name in the file (any case), from the start of its first line to its closing brace."""
        return [self.texts[rel][start:end + 1] for found, start, _, end in self.functions[rel] if found == name]


@functools.lru_cache(maxsize=1)
def repo_universe():
    return Universe(repo_texts())


# ---------------------------------------------------------------------------------------------------------------------
# Question 1 and 3: the providers and what each of them governs
# ---------------------------------------------------------------------------------------------------------------------

def declared_defaults(detail_window_text):
    """(precisePointerConnected, hardwareKeyboardConnected) defaults of the parameter list, as booleans."""
    code = source.mask_kotlin(detail_window_text)
    values = []
    for flag in RAW_FLAGS:
        found = re.findall(r"\b" + flag + r"\s*:\s*Boolean\s*=\s*(true|false)\b", code)
        assert len(found) == 1, (flag, found)
        values.append(found[0] == "true")
    return tuple(values)


def explicit_flags(args):
    """(pointer, keyboard) as written in the argument list of a call: True, False or None where the flag is not passed."""
    result = []
    for flag in RAW_FLAGS:
        found = re.findall(r"\b" + flag + r"\s*=\s*(true|false)\b", args)
        assert len(found) <= 1, (flag, args)
        result.append(None if not found else found[0] == "true")
    return tuple(result)


class Provider:
    """One call of DesktopDetailWindow with a lambda."""

    def __init__(self, universe, rel, call_start, lambda_start, lambda_end, args, depth, parent):
        code = universe.code[rel]
        self.rel, self.depth, self.parent, self.args = rel, depth, parent, " ".join(args.split())
        self.line = code.count("\n", 0, call_start) + 1
        self.explicit = explicit_flags(args)
        self.calls = exclusive_invocations(code, lambda_start + 1, lambda_end)
        self.previous = universe.reach(self.calls)
        self.governs = tuple(sink for sink in PLAYER_SINKS if sink in self.previous)
        self.paths = {sink: Universe.path_to(self.previous, sink) for sink in self.governs}
        self.universe = universe

    def hosts(self, pattern=r"Desktop\w*(?:Host|Mount)$"):
        return [name for name in self.calls if re.match(pattern, name)]

    def takes_default(self, flag_index):
        return self.explicit[flag_index] is None

    def effective(self, defaults):
        return tuple(default if explicit is None else explicit for explicit, default in zip(self.explicit, defaults))

    def governs_overlay(self):
        return tuple(sink for sink in self.governs if sink in PLAYER_OVERLAYS)


def providers(universe=None):
    """Every DesktopDetailWindow call of desktop/src/main with a lambda, outer before nested, files in path order."""
    universe = universe or repo_universe()
    found = []
    for rel in sorted(universe.code):
        if not rel.startswith(DESKTOP_MAIN) or rel == DETAIL_WINDOW_REL:
            continue
        code = universe.code[rel]

        def walk(start, end, depth, parent):
            for call_start, lambda_start, lambda_end, args in provider_regions(code, start, end):
                provider = Provider(universe, rel, call_start, lambda_start, lambda_end, args, depth, parent)
                found.append(provider)
                walk(lambda_start + 1, lambda_end, depth + 1, len(found) - 1)

        walk(0, len(code), 0, None)
    return found


def decide_keyboard_default(provider_list):
    """The rule of spec 3.8 and decision D15. (default, blockers): hardwareKeyboardConnected may default to true only when no provider
    that takes the default hosts a vendored player overlay; the blockers are the providers that do."""
    blockers = [provider for provider in provider_list if provider.takes_default(1) and provider.governs_overlay()]
    return (not blockers), blockers


def decide_pointer_default(provider_list):
    """A desktop always has a precise pointer and no provider hosts anything that must not see one, so the default is true. The function
    fails loudly if a provider passes an explicit false, because that would be a site that opted out and needs a reason."""
    opted_out = [provider for provider in provider_list if provider.explicit[0] is False]
    assert not opted_out, [(provider.rel, provider.line) for provider in opted_out]
    return True


# ---------------------------------------------------------------------------------------------------------------------
# Question 2: the readers of the flags and of the policy outputs
# ---------------------------------------------------------------------------------------------------------------------

def files_with(universe, pattern, exclude=(), needle=None):
    """Sorted repo-relative paths of the scanned files whose masked code matches the pattern. A needle (a plain substring every match contains)
    only spares the regular expression the files that cannot match."""
    expression = re.compile(pattern)
    return sorted(rel for rel, code in universe.code.items()
                  if rel not in exclude and (needle is None or needle in code) and expression.search(code))


def readers(universe=None):
    """The reader inventory: raw flag files, policy callers, and per output the files that read it, plus the player function around each
    keyboard reader."""
    universe = universe or repo_universe()
    result = {
        "raw_flags": {flag: files_with(universe, r"\b" + flag + r"\b", needle=flag) for flag in RAW_FLAGS},
        "policy_callers": files_with(universe, r"\bresolveInputDevicePolicy\s*\(", exclude=(POLICY_REL,), needle="resolveInputDevicePolicy"),
        "outputs": {output: files_with(universe, r"\.\s*" + output + r"\b", needle=output) for output in POLICY_OUTPUTS},
        "providers_of_the_local": files_with(universe, r"\bLocalAppWindowAdaptiveInfo\s+provides\b", needle="LocalAppWindowAdaptiveInfo"),
    }
    result["keyboard_functions"] = {}
    for rel in result["outputs"]["enableKeyboardNavigation"]:
        code = universe.code[rel]
        offsets = [match.start() for match in re.finditer(r"\.\s*enableKeyboardNavigation\b", code)]
        result["keyboard_functions"][rel] = sorted({universe.enclosing_function(rel, offset) for offset in offsets})
    return result


_FLAG_TOKENS = re.compile(r"\b(?:" + "|".join(RAW_FLAGS + POLICY_OUTPUTS + ("resolveInputDevicePolicy",)) + r")\b")


def generator_mentions():
    """{generator file name: sorted flag and policy names it mentions} over desktop/tools/*.py (the generators sit at the top of that folder;
    the UX suites sit in sub folders and are not generators). A generator that injected a new reader would show up here."""
    found = {}
    for path in sorted((REPO / "desktop/tools").glob("*.py")):
        tokens = sorted(set(_FLAG_TOKENS.findall(source.read(path))))
        if tokens:
            found[path.name] = tokens
    return found


def slice_reader_names():
    """Sorted base names of the desktop/upstream-slices Kotlin files that read a policy output. Those are generator inputs (older copies of
    vendored files with a delta), so the names show whether any input other than the player section reads the keyboard or pointer output."""
    names = set()
    for path in (REPO / "desktop/upstream-slices").rglob("*.kt"):
        if re.search(r"\.\s*(?:" + "|".join(POLICY_OUTPUTS) + r")\b", source.mask_kotlin(source.read(path))):
            names.add(path.name)
    return sorted(names)


def manifest_modes(rels):
    """{path: (mode, features)} of the entries of desktop/upstream-sources.json for those vendored files (read only)."""
    import json
    wanted = set(rels)
    entries = json.loads(source.read(REPO / "desktop/upstream-sources.json"))["sources"]
    return {entry["path"]: (entry.get("mode"), tuple(entry.get("features", ()))) for entry in entries if entry.get("path") in wanted}


def hover_override(generator_text, vendored_text):
    """(generator replaces the hover default with true, the modifier file is one of its adapted files, the replaced text occurs exactly once in
    the vendored file). The home card generator makes adaptiveCardHoverEffect unconditional, so the pointer flag no longer switches it."""
    before = "enabled: Boolean = resolveInputDevicePolicy(LocalAppWindowAdaptiveInfo.current).enableHoverEffects"
    replaces = "('" + before + "','enabled: Boolean = true')" in generator_text
    adapted = re.search(r"(?m)^ADAPTED=\[.*APP\+'core/ui/adaptive/AdaptiveInputModifiers\.kt'", generator_text) is not None
    return replaces, adapted, source.mask_kotlin(vendored_text).count(before) == 1


# ---------------------------------------------------------------------------------------------------------------------
# Route facts: which leaf the shell gives a VideoDetail key
# ---------------------------------------------------------------------------------------------------------------------

def route_facts(shell_code, original_leaf_code, universe_codes):
    """The facts behind "the original video route is dormant", each a boolean. The vendored VideoPlayerSection is mounted only by
    DesktopOriginalVideoHolderRoute, which DesktopOriginalVideoPhysicalLeaf calls for a VideoDetail key; the shell hands a VideoDetail key
    to the desktop leaf and gives the original leaf only the audio routes. Dormant is a statement about this source, not a mount proof
    (slice V12a measures that)."""
    video = [m.start() for m in re.finditer(r"entryKey is BiliPaiNavKey\.VideoDetail\s*->", shell_code)]
    audio = [m.start() for m in re.finditer(r"entryKey is BiliPaiNavKey\.AudioMode \|\| entryKey is BiliPaiNavKey\.NativeMusic\s*->", shell_code)]
    desktop_leaf = [m.start() for m in re.finditer(r"\bDesktopWindowsVideoPhysicalLeaf\s*\(", shell_code)]
    original_leaf = [m.start() for m in re.finditer(r"\bDesktopOriginalVideoPhysicalLeaf\s*\(", shell_code)]
    holder_calls = sum(len(re.findall(r"\bDesktopOriginalVideoHolderRoute\s*\(", code)) for rel, code in universe_codes.items()
                       if rel.startswith(DESKTOP_MAIN) and not re.search(r"fun\s+DesktopOriginalVideoHolderRoute\b", code))
    gate = re.search(r"if \(key is BiliPaiNavKey\.VideoDetail\) \{.*?\bDesktopOriginalVideoHolderRoute\s*\(", original_leaf_code, re.S)
    facts = {
        "one VideoDetail branch": len(video) == 1,
        "one audio branch": len(audio) == 1,
        "one desktop leaf call": len(desktop_leaf) == 1,
        "one original leaf call": len(original_leaf) == 1,
        "holder route has one caller": holder_calls == 1,
        "holder route sits behind the VideoDetail key test": gate is not None,
    }
    if len(video) == len(audio) == len(desktop_leaf) == len(original_leaf) == 1:
        facts["VideoDetail branch calls the desktop leaf"] = video[0] < desktop_leaf[0] < audio[0]
        facts["audio branch calls the original leaf"] = audio[0] < original_leaf[0]
        facts["the original leaf is not called from the VideoDetail branch"] = not (video[0] < original_leaf[0] < audio[0])
    return facts


# ---------------------------------------------------------------------------------------------------------------------
# Presentation
# ---------------------------------------------------------------------------------------------------------------------

def rows(provider_list, defaults):
    """[(file name, line, depth, host, explicit, effective, governs)] for the table."""
    table = []
    for provider in provider_list:
        hosts = provider.hosts()
        table.append((Path(provider.rel).name, provider.line, provider.depth, hosts[0] if hosts else "-", provider.explicit,
                      provider.effective(defaults), provider.governs))
    return table


def render(provider_list, defaults, reader_inventory):
    lines = ["DesktopDetailWindow defaults: precisePointerConnected=%s hardwareKeyboardConnected=%s" % defaults, ""]
    lines.append("%-48s %5s %5s  %-44s %-12s %-12s %s" % ("file", "line", "depth", "first host", "explicit", "effective", "governs"))
    for name, line, depth, host, explicit, effective, governs in rows(provider_list, defaults):
        lines.append("%-48s %5d %5d  %-44s %-12s %-12s %s" % (name, line, depth, host, explicit, effective, ", ".join(governs) or "-"))
    lines += ["", "readers:"]
    for key, value in reader_inventory.items():
        lines.append("  %s: %s" % (key, value))
    keyboard, blockers = decide_keyboard_default(provider_list)
    lines += ["", "keyboard default may be true: %s" % keyboard,
              "providers that take the default and host a vendored player overlay: %s" % [(Path(p.rel).name, p.line) for p in blockers],
              "pointer default: %s" % decide_pointer_default(provider_list)]
    return "\n".join(lines)


def main(argv):
    universe = repo_universe()
    defaults = declared_defaults(source.read(REPO / DETAIL_WINDOW_REL))
    provider_list = providers(universe)
    print(render(provider_list, defaults, readers(universe)))
    if "--check" in argv:
        keyboard, _ = decide_keyboard_default(provider_list)
        pointer = decide_pointer_default(provider_list)
        if defaults != (pointer, keyboard):
            print("DesktopDetailWindow.kt defaults %s disagree with the decision %s" % (defaults, (pointer, keyboard)))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
