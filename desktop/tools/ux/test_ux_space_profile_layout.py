"""TON-1060 patch checks: the space page grid and the profile page header of the desktop layout, on the merged root files. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 python3 -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_space_profile_layout.py

What is proved. The two patches (SpaceScreen.kt.patch, ProfileScreen.kt.patch under desktop/upstream-core/patches) are applied to the
merged root files with a port of applyUnifiedPatch (desktop/build.gradle.kts: hunks in order, every before-block exactly once):

  PatchTest     the whole patch applies; the TON-1060 hunks are the last three of each file and each matches exactly once in the text
                it receives; a missing or a doubled anchor fails closed; reverse-applying them gives back the received text byte for byte
  SpaceTest     the page box, the column default, the preference bounds, the pinch bounds and the card layout go through
                desktopSpaceFeedGrid, the vendored width cap, column function and card layout are not called any more, and the grid
                value is declared before its first use
  ProfileTest   a wide window in the desktop layout gets one full-width feed column with the header band as the leading content of its
                first item, the vendored split stays as the else branch, both calls of the feed column pass the same arguments apart
                from the layout ones, and the header band holds no scroll of its own
  HelperTest    the names the patches call are declared in DesktopSpaceProfileLayout.kt, the named arguments are its parameters, and
                the file is plain LF text without Chinese

What is not proved. That the patched files compile and what the grid draws: DesktopSpaceProfileLayoutTest (JVM) compares the numbers
with the Home feed, and the Gradle build applies the same patches to the producer output.
"""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
PATCHES = REPO / "desktop/upstream-core/patches"
SPACE = "app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt"
PROFILE = "app/src/main/java/com/android/purebilibili/feature/profile/ProfileScreen.kt"
HELPER = REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceProfileLayout.kt"
MARKERS = ("desktopSpaceFeedGrid", "desktopSpaceGrid", "desktopProfileHeaderOnTop", "leadingContent")
CJK = re.compile("[　-〿㐀-䶿一-鿿豈-﫿＀-￯]")


class PatchError(AssertionError):
    pass


def parse(patch):
    """The hunks of a unified diff as (header, before, after) line lists, as applyUnifiedPatch reads them."""
    lines = patch.replace("\r\n", "\n").split("\n")
    start = next(i for i, line in enumerate(lines) if line.startswith("--- "))
    body = lines[start + 2:]
    hunks, index = [], 0
    while index < len(body):
        header = body[index]
        index += 1
        if header == "" and index == len(body):
            break
        if not header.startswith("@@ "):
            raise PatchError("malformed hunk header: " + header)
        before, after = [], []
        while index < len(body) and not body[index].startswith("@@ "):
            line = body[index]
            index += 1
            if line == "" and index == len(body):
                break
            if line[:1] == " ":
                before.append(line[1:])
                after.append(line[1:])
            elif line[:1] == "-":
                before.append(line[1:])
            elif line[:1] == "+":
                after.append(line[1:])
            else:
                raise PatchError("unexpected patch line: " + line)
        hunks.append((header, before, after))
    return hunks


def matches(lines, block):
    return [s for s in range(len(lines) - len(block) + 1) if lines[s:s + len(block)] == block]


def apply(text, hunks, label="patch"):
    lines = text.replace("\r\n", "\n").split("\n")
    for header, before, after in hunks:
        found = matches(lines, before)
        if len(found) != 1:
            raise PatchError(f"{label}: hunk {header} matches {len(found)} times; regenerate the patch against the producer output")
        s = found[0]
        lines = lines[:s] + after + lines[s + len(before):]
    return "\n".join(lines)


def is_ticket(hunk):
    added = [line for line in hunk[2] if line not in hunk[1]]
    return any(marker in line for line in added for marker in MARKERS)


def load(relative):
    hunks = parse((PATCHES / (relative + ".patch")).read_text(encoding="utf-8"))
    return (REPO / relative).read_text(encoding="utf-8"), hunks


def call_args(text, start):
    """The top-level named arguments of the call whose opening bracket follows text[start:]."""
    open_at = text.index("(", start)
    depth, index, names, segment = 0, open_at, [], []
    while True:
        ch = text[index]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                break
        if depth == 1 and ch not in "(":
            segment.append(ch)
            if ch == ",":
                segment = []
        if depth == 1:
            m = re.match(r"\s*(\w+)\s*=(?!=)", "".join(segment))
            if m and (not names or names[-1] != m.group(1)):
                names.append(m.group(1))
        index += 1
    return names, text[open_at:index + 1]


class PatchTest(unittest.TestCase):
    def test_the_whole_patch_applies_and_the_ticket_hunks_come_last(self):
        for relative in (SPACE, PROFILE):
            with self.subTest(relative):
                text, hunks = load(relative)
                apply(text, hunks, relative)
                flags = [is_ticket(h) for h in hunks]
                self.assertEqual(3, sum(flags), "the TON-1060 hunks of " + relative)
                self.assertEqual([True] * 3, flags[-3:], "the TON-1060 hunks are appended after the earlier ones")

    def test_each_ticket_hunk_matches_once_in_the_text_it_receives(self):
        for relative in (SPACE, PROFILE):
            text, hunks = load(relative)
            lines = apply(text, hunks[:-3]).split("\n")
            for header, before, after in hunks[-3:]:
                with self.subTest(relative=relative, hunk=header):
                    self.assertEqual(1, len(matches(lines, before)))
                    lines = apply("\n".join(lines), [(header, before, after)]).split("\n")

    def test_a_missing_or_doubled_anchor_fails_closed(self):
        for relative in (SPACE, PROFILE):
            text, hunks = load(relative)
            received = apply(text, hunks[:-3])
            for n in range(3):
                hunk = hunks[-3 + n]
                lines = apply(received, hunks[-3:-3 + n] if n else []).split("\n")
                anchor = matches(lines, hunk[1])[0]
                key = next(i for i, line in enumerate(hunk[1]) if line.strip())
                missing = list(lines)
                missing[anchor + key] = missing[anchor + key] + " // moved upstream"
                doubled = lines[:anchor] + hunk[1] + lines[anchor:]
                for case, mutated, count in (("missing", missing, 0), ("doubled", doubled, 2)):
                    with self.subTest(relative=relative, hunk=n, case=case):
                        with self.assertRaisesRegex(PatchError, f"matches {count} times"):
                            apply("\n".join(mutated), [hunk], relative)

    def test_reverse_applying_the_ticket_hunks_gives_back_the_received_text(self):
        for relative in (SPACE, PROFILE):
            with self.subTest(relative):
                text, hunks = load(relative)
                received = apply(text, hunks[:-3])
                patched = apply(received, hunks[-3:])
                self.assertNotEqual(received, patched)
                reverse = [(h, after, before) for h, before, after in reversed(hunks[-3:])]
                self.assertEqual(received.encode("utf-8"), apply(patched, reverse).encode("utf-8"))


def patched(relative):
    text, hunks = load(relative)
    return apply(text, hunks, relative)


class SpaceTest(unittest.TestCase):
    def setUp(self):
        self.text = patched(SPACE)

    def test_the_grid_goes_through_the_desktop_function(self):
        t = self.text
        self.assertEqual(1, t.count("val desktopSpaceGrid = com.bilipai.desktop.ui.desktopSpaceFeedGrid("))
        self.assertEqual(1, t.count(".responsiveContentWidth(maxWidth = desktopSpaceGrid.maxWidth)"))
        self.assertEqual(1, t.count("val preferredGridColumns = desktopSpaceGrid.columns"))
        self.assertEqual(1, t.count("bounds = desktopSpaceGrid.bounds,"))
        self.assertEqual(1, t.count("val pinchColumnBounds = desktopSpaceGrid.bounds"))
        self.assertEqual(1, t.count("delegated = desktopSpaceGrid.delegated,"))
        self.assertEqual(1, t.count("desktopSpaceGrid.dynamicColumns(adaptiveLayoutSpec.dynamicColumns, spaceFeedCardLayout.outerPaddingDp)"))
        self.assertEqual(1, t.count("com.bilipai.desktop.ui.desktopResolveHomeFeedCardLayout("))

    def test_the_vendored_width_columns_and_card_layout_are_not_called(self):
        t = self.text
        self.assertNotIn("adaptiveLayoutSpec.contentMaxWidthDp.dp", t)
        self.assertNotRegex(t, r"(?<![\w.])resolveSpaceContentGridColumnCount\(")
        self.assertNotRegex(t, r"(?<![\w.])resolveHomeFeedPinchColumnBounds\(")
        self.assertNotRegex(t, r"(?<![\w.])resolveHomeFeedCardLayout\(")

    def test_the_grid_value_is_declared_before_its_first_use(self):
        t = self.text
        declared = t.index("val desktopSpaceGrid =")
        self.assertLess(declared, t.index("desktopSpaceGrid.", declared))
        self.assertEqual(declared, min(m.start() for m in re.finditer(r"desktopSpaceGrid\b", t)) - len("val "))
        # The card layout is resolved before the dynamic columns read its side padding.
        self.assertLess(t.index("val spaceFeedCardLayout ="), t.index("desktopSpaceGrid.dynamicColumns("))


class ProfileTest(unittest.TestCase):
    def setUp(self):
        self.text = patched(PROFILE)
        head = "        if (com.bilipai.desktop.ui.desktopProfileHeaderOnTop(splitLayout = isTablet)) {\n"
        self.assertEqual(1, self.text.count(head))
        start = self.text.index(head)
        end = self.text.index("\n        } else if (isTablet) {\n", start)
        self.branch = self.text[start:end]
        self.vendored = self.text[end:]

    def test_the_desktop_branch_is_one_full_width_feed_with_the_header_on_top(self):
        b = self.branch
        self.assertEqual(1, b.count("ProfileSpaceFeedColumn("))
        self.assertEqual(1, b.count("ProfileSpaceHeader("))
        self.assertEqual(1, b.count("ProfileQuickAccessDashboard("))
        self.assertEqual(1, b.count("leadingContent = { page ->"))
        self.assertIn(".fillMaxSize()", b)
        self.assertIn(".stationaryPagerHeader(pagerState, page)", b)
        self.assertNotIn("verticalScroll(", b)
        self.assertNotIn("Modifier.weight(1f),\n                contentPadding", b)
        # The header comes before the shortcuts in the band.
        self.assertLess(b.index("ProfileSpaceHeader("), b.index("ProfileQuickAccessDashboard("))

    def test_the_vendored_split_stays_the_else_branch(self):
        v = self.vendored
        self.assertTrue(v.startswith("\n        } else if (isTablet) {\n"))
        self.assertIn(".verticalScroll(tabletRailScrollState)", v[:3000])

    def test_both_feed_calls_pass_the_same_arguments_apart_from_the_layout(self):
        desktop_args, _ = call_args(self.branch, self.branch.index("ProfileSpaceFeedColumn("))
        vendored_args, _ = call_args(self.vendored, self.vendored.index("ProfileSpaceFeedColumn("))
        layout = {"modifier", "contentPadding", "leadingContent"}
        self.assertEqual([a for a in vendored_args if a not in layout], [a for a in desktop_args if a not in layout])
        self.assertIn("leadingContent", desktop_args)
        self.assertNotIn("leadingContent", vendored_args)
        for state in ("listState", "pageListStates", "pagerState"):
            self.assertIn(state, desktop_args)

    def test_the_feed_column_draws_the_leading_content_in_its_first_item(self):
        t = self.text
        self.assertEqual(1, t.count("    leadingContent: (@Composable (Int) -> Unit)? = null,\n) {"))
        self.assertEqual(1, t.count("leadingContent?.invoke(page)"))
        item = t.index("leadingContent?.invoke(page)")
        self.assertLess(item, t.index("            tabSlot(\n                page,", item))
        self.assertGreater(item, t.rindex("        item {", 0, item))


class HelperTest(unittest.TestCase):
    def setUp(self):
        self.source = HELPER.read_text(encoding="utf-8")

    def test_the_called_names_are_declared(self):
        s = self.source
        self.assertEqual(2, len(re.findall(r"^internal fun desktopSpaceFeedGrid\(", s, re.M)))
        self.assertEqual(2, len(re.findall(r"^internal fun desktopProfileHeaderOnTop\(", s, re.M)))
        for member in ("val maxWidth", "val columns", "val bounds", "val delegated", "fun dynamicColumns(", "fun cardLayoutColumns("):
            self.assertIn(member, s)

    def test_the_named_arguments_of_the_patch_are_parameters_of_the_composable_overload(self):
        t = patched(SPACE)
        used, _ = call_args(t, t.index("com.bilipai.desktop.ui.desktopSpaceFeedGrid("))
        overload = self.source[self.source.index("@Composable\ninternal fun desktopSpaceFeedGrid("):]
        params = re.findall(r"^    (\w+): ", overload[:overload.index("): DesktopSpaceFeedGrid")], re.M)
        self.assertEqual(params, used)

    def test_the_helper_is_plain_lf_text_without_chinese(self):
        raw = HELPER.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertIsNone(CJK.search(self.source))


if __name__ == "__main__":
    unittest.main()
