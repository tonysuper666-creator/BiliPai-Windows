"""Shell shortcut table and resolver of the desktop UX program (slice X1a, spec 6.1 and 10.1). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_shortcut_table.py

What is proved.
  * The collision parser of spec 10.1 (ux_shell_model.shortcut_violations) passes the contract table and fails each
    rule on a deliberately broken table: a duplicate chord in a scope, a bare printable key on shell pages, Ctrl+Alt
    (AltGr), an overlap with the zoom decoder set (read from the real desktopWindowsScaleKey), Ctrl digits outside 1 to 9,
    a reserved chord, Enter and Backspace, a chord with two meanings across scopes, and a malformed chord or scope.
  * The shell rows of the contract equal the spec 6.1 table written out again here, and the Kotlin table
    (ui/DesktopShellShortcuts.kt, parsed from its marked block) equals the contract rows in content and order, with the
    Ctrl+number rows generated over the fixed destinations. The reserved and zoom sets of the Kotlin equal the contract.
  * The resolver mirror, fed with the Kotlin table, agrees with an oracle written from the spec prose over the contract on
    every key of the vocabulary under all 16 modifier combinations, five pages, both text-editor states and key down and
    up. The transliterated Kotlin lines are pinned as source needles.
  * Strings: every action kind has a label.

What is not proved. That the Kotlin compiles or runs: DesktopShellShortcutsTest is the proof, on the Linux compile later.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in
desktop/tools/tests as spec 8.2 and 9.2 say; Root adds each suite to the validation plan as an explicit
`-s desktop/tools/ux -p <file>` line.
"""
import copy
import itertools
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
TABLE = CONTRACT["shortcuts"]
SHORTCUTS_TEXT = shell.read(shell.SHORTCUTS_KT)
STRINGS_TEXT = shell.read(shell.STRINGS_KT)
ROWS = shell.shortcut_rows(SHORTCUTS_TEXT)
KOTLIN_TABLE = shell.kotlin_shell_table(ROWS)
KOTLIN_RESERVED = set(shell.string_set(SHORTCUTS_TEXT, "DESKTOP_SHELL_RESERVED_CHORDS"))
KOTLIN_ZOOM = set(shell.string_set(SHORTCUTS_TEXT, "DESKTOP_SHELL_ZOOM_KEYS"))
ZOOM_FROM_SOURCE = shell.zoom_decoder_keys_from_source()
SHELL_ROWS = shell.contract_shell_rows(CONTRACT)

# Spec 6.1, verbatim: (chord, action, argument). Ctrl+comma is the chord Ctrl+Comma.
SPEC_SHELL_TABLE = (
    ("Ctrl+K", "OPEN_SEARCH", None), ("Ctrl+F", "OPEN_SEARCH", None), ("Alt+Left", "BACK", None), ("F5", "REFRESH", None),
    ("Ctrl+R", "REFRESH", None), ("Ctrl+1", "GO_DESTINATION", "HOME"), ("Ctrl+2", "GO_DESTINATION", "DYNAMIC"),
    ("Ctrl+3", "GO_DESTINATION", "LIVE"), ("Ctrl+4", "GO_DESTINATION", "LISTEN_VIDEO"), ("Ctrl+5", "GO_DESTINATION", "HISTORY"),
    ("Ctrl+6", "GO_DESTINATION", "FAVORITE"), ("Ctrl+7", "GO_DESTINATION", "WATCHLATER"),
    ("Ctrl+8", "GO_DESTINATION", "DOWNLOADS"), ("Ctrl+9", "GO_DESTINATION", "PROFILE"), ("Ctrl+B", "RAIL_TOGGLE", None),
    ("Ctrl+Comma", "OPEN_SETTINGS", None), ("Ctrl+J", "OPEN_DOWNLOADS", None), ("F6", "CYCLE_PANES", 1),
    ("Shift+F6", "CYCLE_PANES", -1), ("F1", "SHORTCUT_HELP", None), ("F11", "FULLSCREEN_TOGGLE", None),
    ("Alt+Enter", "FULLSCREEN_TOGGLE", None),
)


def violations(rows, zoom_keys=None, reserved=None, not_taken=None, dual=None):
    return shell.shortcut_violations(
        rows, ZOOM_FROM_SOURCE if zoom_keys is None else zoom_keys, TABLE["reserved"] if reserved is None else reserved,
        TABLE["not_taken"] if not_taken is None else not_taken, TABLE["dual_meaning"] if dual is None else dual)


def codes(rows, **kwargs):
    return sorted({code for code, _row in violations(rows, **kwargs)})


def contract_rows():
    return copy.deepcopy(TABLE["rows"])


def with_row(scope, chord, action=None):
    """The contract rows plus one row. Without an action the row takes the action the other scope already gives the same
    chord (so it cannot cause a scope conflict by accident), or SHORTCUT_HELP when the chord is new."""
    if action is None:
        action = next((row["action"] for row in TABLE["rows"] if row["chord"] == chord and row["scope"] != scope), "SHORTCUT_HELP")
    return contract_rows() + [{"scope": scope, "chord": chord, "action": action}]


# --------------------------------------------------------------------------------------
# The collision parser
# --------------------------------------------------------------------------------------

class CollisionParserTest(unittest.TestCase):
    def test_the_contract_table_has_no_violation(self):
        self.assertEqual([], violations(TABLE["rows"]))

    def test_the_zoom_decoder_set_is_read_from_the_real_decoder_and_agrees_everywhere(self):
        self.assertEqual({"Plus", "Equals", "Add", "Minus", "Subtract", "0", "Numpad0"}, ZOOM_FROM_SOURCE)
        self.assertEqual(ZOOM_FROM_SOURCE, set(TABLE["zoom_decoder_keys"]))
        self.assertEqual(ZOOM_FROM_SOURCE, KOTLIN_ZOOM)

    def test_the_reserved_chords_are_the_contract_reserved_plus_the_keys_not_taken(self):
        self.assertEqual(set(TABLE["reserved"]) | set(TABLE["not_taken"]), KOTLIN_RESERVED)
        for chord in ("Ctrl+Plus", "Ctrl+Minus", "Ctrl+0", "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Alt+F4",
                      "Backspace", "Enter"):
            self.assertIn(chord, KOTLIN_RESERVED)

    def test_a_duplicate_chord_in_a_scope_is_found(self):
        self.assertEqual(["duplicate"], codes(with_row("shell", "Ctrl+K", "OPEN_SEARCH")))
        self.assertEqual(["duplicate"], codes(with_row("player", "Space", "PLAY_PAUSE")))

    def test_a_bare_printable_key_is_found_on_shell_pages_only(self):
        for chord in ("K", "1", "Comma", "Slash", "Space", "LeftBracket"):
            self.assertEqual(["bare-printable-shell"], codes(with_row("shell", chord)), chord)
        self.assertEqual([], codes(with_row("player", "Q", "SCREENSHOT")))  # a bare letter is fine in the player scope
        self.assertEqual([], codes(with_row("shell", "F8", "SHORTCUT_HELP")))  # a function key is not printable
        self.assertEqual([], codes(with_row("shell", "Shift+K", "OPEN_SEARCH")))

    def test_ctrl_alt_is_found(self):
        self.assertEqual(["ctrl-alt"], codes(with_row("shell", "Ctrl+Alt+K")))
        self.assertEqual(["ctrl-alt"], codes(with_row("shell", "Ctrl+Alt+Plus")))  # the decoder ignores Alt chords

    def test_an_overlap_with_the_zoom_decoder_set_is_found_with_or_without_shift(self):
        for chord in ("Ctrl+Plus", "Ctrl+Equals", "Ctrl+Add", "Ctrl+Minus", "Ctrl+Subtract", "Ctrl+Numpad0"):
            self.assertIn("zoom-overlap", codes(with_row("shell", chord)), chord)
        self.assertIn("zoom-overlap", codes(with_row("player", "Ctrl+Shift+Equals")))
        self.assertEqual(["reserved", "zoom-overlap"], sorted(set(codes(with_row("shell", "Ctrl+Plus")))))
        self.assertEqual(["zoom-overlap"], codes(with_row("shell", "Ctrl+Shift+Equals")))
        self.assertEqual([], codes(with_row("shell", "Alt+Plus", "OPEN_SEARCH")))  # no Ctrl, no zoom

    def test_ctrl_digits_outside_one_to_nine_are_found(self):
        self.assertIn("ctrl-digit-range", codes(with_row("shell", "Ctrl+0")))
        self.assertEqual([], codes(with_row("player", "Ctrl+Shift+5", "SEEK_PERCENT")))

    def test_reserved_chords_and_the_keys_not_taken_are_found(self):
        for chord in ("Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Alt+F4"):
            self.assertIn("reserved", codes(with_row("shell", chord)), chord)
        self.assertEqual(["not-taken"], codes(with_row("player", "Enter")))
        self.assertEqual(["not-taken", "reserved"], codes(with_row("player", "Backspace")))

    def test_a_chord_with_two_meanings_needs_the_dual_meaning_list(self):
        self.assertEqual(["scope-conflict"], codes(with_row("player", "Ctrl+K", "SEEK")))
        self.assertEqual([], codes(contract_rows()))
        self.assertEqual(["scope-conflict"], codes(contract_rows(), dual=[]))
        self.assertEqual(["Ctrl+B"], TABLE["dual_meaning"])
        self.assertEqual([], codes(with_row("player", "Ctrl+K", "OPEN_SEARCH")))  # the same meaning twice is not a conflict

    def test_malformed_chords_and_scopes_are_found(self):
        for chord in ("Shift+Ctrl+K", "Ctrl+", "", "Hyper+K", "Ctrl+Ctrl+K"):
            self.assertIn("bad-chord", codes(with_row("shell", chord)), chord)
        self.assertEqual(["bad-scope"], codes(with_row("sidebar", "F8")))

    def test_the_rules_of_the_contract_are_the_rules_the_parser_enforces(self):
        self.assertEqual(6, len(TABLE["rules"]))
        text = " ".join(TABLE["rules"])
        for phrase in ("duplicate chord", "bare printable", "Ctrl and Alt", "zoom decoder", "1 to 9", "dual_meaning"):
            self.assertIn(phrase, text)


# --------------------------------------------------------------------------------------
# The table
# --------------------------------------------------------------------------------------

class TableTest(unittest.TestCase):
    def test_the_contract_shell_rows_are_the_spec_table(self):
        rows = [(row["chord"], row["action"], row.get("arg")) for row in SHELL_ROWS]
        self.assertEqual(list(SPEC_SHELL_TABLE), rows)

    def test_ctrl_n_is_numbered_over_the_fixed_destinations_only(self):
        digits = [row for row in SHELL_ROWS if re.fullmatch(r"Ctrl\+[0-9]", row["chord"])]
        self.assertEqual(["Ctrl+%d" % n for n in range(1, 10)], [row["chord"] for row in digits])
        self.assertEqual(CONTRACT["rail"]["fixed_destinations"], [row["arg"] for row in digits])
        for row in digits:
            self.assertNotIn(row["arg"], ("ACCOUNT_SWITCH", "PLUGINS", "SETTINGS"))

    def test_the_status_marks_only_the_chord_the_root_already_handles(self):
        self.assertEqual(["F11"], [row["chord"] for row in SHELL_ROWS if row.get("status") == "existing"])

    def test_the_existing_root_keys_are_not_redefined_and_the_player_keys_are_not_taken(self):
        handler = shell.read(source.K / "DesktopShell.kt")
        self.assertIn("event.key == Key.F11", handler)
        self.assertIn("event.key == Key.Escape", handler)
        chords = {row["chord"] for row in SHELL_ROWS}
        for chord in ("Esc", "Escape", "Space", "Enter", "Backspace"):
            self.assertNotIn(chord, chords)
        self.assertEqual(1, len([row for row in SHELL_ROWS if row["chord"] == "F11"]))

    def test_ctrl_b_has_two_meanings_by_route_and_the_help_says_so(self):
        by_scope = {row["scope"]: row["action"] for row in TABLE["rows"] if row["chord"] == "Ctrl+B"}
        self.assertEqual({"shell": "RAIL_TOGGLE", "player": "SIDE_PANEL_OR_CINEMA"}, by_scope)
        self.assertIn('const val CTRL_B_NOTE = "Ctrl+B ', STRINGS_TEXT)

    def test_f6_is_the_shells_on_every_page_and_the_player_scope_has_no_f6_row(self):
        # Spec 6.1 (cycle panes) and 6.6 (the same keys on the video page): one chord, one owner, so no scope conflict.
        for chord, direction in (("F6", 1), ("Shift+F6", -1)):
            rows = [row for row in TABLE["rows"] if row["chord"] == chord]
            self.assertEqual([{"scope": "shell", "chord": chord, "action": "CYCLE_PANES", "arg": direction}], rows)
        self.assertNotIn("F6", [model.parse_chord(row["chord"])[1] for row in TABLE["rows"] if row["scope"] == "player"])
        self.assertNotIn("CYCLE_PANES", [row["action"] for row in TABLE["rows"] if row["scope"] == "player"])
        for page in shell.PAGES:
            self.assertEqual(("CYCLE_PANES", None, 1), mirror("F6", page=page), page)
            self.assertEqual(("CYCLE_PANES", None, -1), mirror("F6", shift=True, page=page), page)
            self.assertEqual(("CYCLE_PANES", None, 1), oracle("F6", page=page), page)
            self.assertEqual(("CYCLE_PANES", None, -1), oracle("F6", shift=True, page=page), page)
            self.assertIsNone(mirror("F6", ctrl=True, page=page), page)
            self.assertIsNone(mirror("F6", page=page, text_editor=True), page)

    def test_no_shell_chord_is_a_bare_letter_or_digit(self):
        for row in SHELL_ROWS:
            mods, key = model.parse_chord(row["chord"])
            self.assertFalse(not mods and (len(key) == 1 or key in shell.PRINTABLE_NAMES), row)


# --------------------------------------------------------------------------------------
# The Kotlin table
# --------------------------------------------------------------------------------------

class KotlinTableParityTest(unittest.TestCase):
    def test_the_kotlin_table_equals_the_contract_rows_in_content_and_order(self):
        expected = []
        for row in SHELL_ROWS:
            kind = row["action"]
            destination = row["arg"] if kind == "GO_DESTINATION" else None
            direction = row["arg"] if kind == "CYCLE_PANES" else 0
            expected.append((row["chord"], kind, destination, direction, row.get("status") == "existing"))
        self.assertEqual(expected, ROWS)

    def test_the_ctrl_number_rows_are_generated_over_the_fixed_destination_list(self):
        self.assertEqual(1, shell.squash(SHORTCUTS_TEXT).count(shell.GENERATED_DESTINATIONS))
        self.assertEqual(CONTRACT["rail"]["fixed_destinations"], shell.fixed_destinations(shell.read(shell.RAIL_KT)))

    def test_the_table_has_no_duplicate_chord_and_no_player_row(self):
        self.assertEqual(len(ROWS), len(KOTLIN_TABLE))
        player_only = {row["chord"] for row in TABLE["rows"] if row["scope"] == "player"} - {row["chord"] for row in SHELL_ROWS}
        self.assertEqual(set(), player_only & set(KOTLIN_TABLE))

    def test_every_action_kind_of_the_contract_is_an_enum_entry_and_back(self):
        enum = re.search(r"internal enum class DesktopShellActionKind \{(.*?)\}", SHORTCUTS_TEXT, re.S)
        kinds = re.findall(r"[A-Z_]+", enum.group(1))
        self.assertEqual(sorted({row["action"] for row in SHELL_ROWS}), sorted(kinds))
        self.assertEqual(len(kinds), len(set(kinds)))

    def test_the_pages_are_the_five_the_mirror_knows(self):
        enum = re.search(r"internal enum class DesktopShellPage \{ ([A-Z, ]+) \}", SHORTCUTS_TEXT)
        self.assertEqual(list(shell.PAGES), [name.strip() for name in enum.group(1).split(",")])

    def test_the_player_page_kinds_are_the_four_the_spec_leaves_with_the_shell(self):
        # Back, fullscreen and the shortcut list (spec 6.1) and F6 pane cycling (spec 6.6: the video page cannot take Tab
        # focus into its dock, so F6 between the content and the side panel is part of its keyboard story).
        found = re.search(r"desktopShellPlayerPageKinds: Set<DesktopShellActionKind> = setOf\((.*?)\)\n", SHORTCUTS_TEXT, re.S)
        kotlin = sorted(re.findall(r"DesktopShellActionKind\.([A-Z_]+)", found.group(1)))
        self.assertEqual(sorted(shell.PLAYER_PAGE_KINDS), kotlin)
        self.assertEqual(["BACK", "CYCLE_PANES", "FULLSCREEN_TOGGLE", "SHORTCUT_HELP"], kotlin)
        self.assertEqual(len(kotlin), len(set(kotlin)))

    def test_the_three_lists_of_the_table_are_joined_with_plus(self):
        # The parser splits the block at the generated Ctrl+number rows; the plus signs on both sides are what keeps those
        # rows in the table (a minus would drop them without changing anything the parser reads).
        text = shell.squash(SHORTCUTS_TEXT)
        self.assertEqual(1, text.count(
            'DesktopShellShortcut("Ctrl+R", DesktopShellAction(DesktopShellActionKind.REFRESH)),) + '
            + shell.GENERATED_DESTINATIONS
            + ' + listOf(DesktopShellShortcut("Ctrl+B", DesktopShellAction(DesktopShellActionKind.RAIL_TOGGLE)),'))
        self.assertEqual(1, text.count("internal val DESKTOP_SHELL_SHORTCUTS: List<DesktopShellShortcut> = listOf("))

    def test_the_data_class_defaults_are_pinned(self):
        # The defaults decide what an omitted argument means (a key press, no modifier, no editor focus, a row the root
        # does not handle yet, no direction): a flipped one changes every caller that leaves it out.
        text = shell.squash(SHORTCUTS_TEXT)
        for declaration in (
            "internal data class DesktopShellAction(val kind: DesktopShellActionKind, val destination: DesktopRailItem? = null, "
            "val direction: Int = 0,)",
            "internal data class DesktopShellShortcut(val chord: String, val action: DesktopShellAction, val existing: Boolean = false)",
            "internal data class DesktopShellKeyData(val key: String, val ctrl: Boolean = false, val alt: Boolean = false, "
            "val shift: Boolean = false, val meta: Boolean = false, val keyDown: Boolean = true,)",
            "internal data class DesktopShellKeyContext(val page: DesktopShellPage, val textEditorFocused: Boolean = false)",
        ):
            self.assertEqual(1, text.count(declaration), declaration)

    def test_the_key_vocabulary_of_the_header_covers_every_key_of_the_table(self):
        header = SHORTCUTS_TEXT[:SHORTCUTS_TEXT.index("internal enum class")]
        for name in ("Left", "Comma", "LeftBracket", "Esc", "Enter", "Backspace", "Menu", "Numpad0", "Subtract"):
            self.assertIn(name, header)


# --------------------------------------------------------------------------------------
# The resolver
# --------------------------------------------------------------------------------------

KEYS = sorted({model.parse_chord(row["chord"])[1] for row in TABLE["rows"]}
              | set("ABCDEFGHIJKLMNOPQRSTUVWXYZ") | set("0123456789") | {"F%d" % n for n in range(1, 13)}
              | {"Plus", "Equals", "Add", "Minus", "Subtract", "Numpad0", "Numpad1", "Enter", "Backspace", "Tab", "Delete",
                 "PageUp", "PageDown", "Insert", "Menu", "Period", "Slash", "Comma", "Space", "Esc"})


def mirror(key, ctrl=False, alt=False, shift=False, meta=False, key_down=True, page="HOME", text_editor=False):
    return shell.resolve_shell_key_kt(KOTLIN_TABLE, KOTLIN_RESERVED, KOTLIN_ZOOM, key, ctrl, alt, shift, meta, key_down, page, text_editor)


def oracle(key, ctrl=False, alt=False, shift=False, meta=False, key_down=True, page="HOME", text_editor=False):
    return shell.oracle_shell_resolve(CONTRACT, key, ctrl, alt, shift, meta, key_down, page, text_editor)


class ResolverTest(unittest.TestCase):
    def test_the_mirror_equals_the_oracle_on_every_key_modifier_page_and_state(self):
        checked = claimed = 0
        for key in KEYS:
            for ctrl, alt, shift, meta in itertools.product((False, True), repeat=4):
                for page in shell.PAGES:
                    for text_editor in (False, True):
                        for key_down in (True, False):
                            args = (key, ctrl, alt, shift, meta, key_down, page, text_editor)
                            got = mirror(*args)
                            self.assertEqual(oracle(*args), got, args)
                            checked += 1
                            claimed += got is not None
        self.assertGreater(checked, 20000)
        self.assertGreaterEqual(claimed, len(ROWS))

    def test_nothing_is_claimed_that_is_not_a_table_row(self):
        for key in KEYS:
            for ctrl, alt, shift, meta in itertools.product((False, True), repeat=4):
                got = mirror(key, ctrl, alt, shift, meta)
                if got is not None:
                    self.assertIn(shell.chord_kt(key, ctrl, alt, shift, meta), KOTLIN_TABLE)
                    self.assertFalse(meta or (ctrl and alt))

    def test_every_table_row_resolves_to_its_action_on_a_page_that_allows_it(self):
        for chord, kind, destination, direction, _existing in ROWS:
            mods, key = model.parse_chord(chord)
            args = dict(key=key, ctrl="Ctrl" in mods, alt="Alt" in mods, shift="Shift" in mods)
            self.assertEqual((kind, destination, direction), mirror(page="HOME", **args), chord)
            self.assertEqual((kind, destination, direction), mirror(page="DYNAMIC", **args), chord)

    def test_a_text_editor_owning_focus_stops_every_chord(self):
        for chord, _kind, _destination, _direction, _existing in ROWS:
            mods, key = model.parse_chord(chord)
            for page in shell.PAGES:
                self.assertIsNone(mirror(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods, page=page, text_editor=True), (chord, page))

    def test_altgr_and_the_windows_key_are_never_claimed(self):
        for chord, *_rest in ROWS:
            mods, key = model.parse_chord(chord)
            self.assertIsNone(mirror(key, True, True, "Shift" in mods), chord + " with Ctrl+Alt")
            self.assertIsNone(mirror(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods, meta=True), chord + " with Meta")

    def test_reserved_chords_and_zoom_keys_are_never_claimed_even_with_shift(self):
        for key in ("Plus", "Equals", "Add", "Minus", "Subtract", "0", "Numpad0"):
            for shift in (False, True):
                for page in shell.PAGES:
                    self.assertIsNone(mirror(key, True, shift=shift, page=page), (key, shift))
        for chord in ("Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Alt+F4", "Backspace", "Enter"):
            mods, key = model.parse_chord(chord)
            self.assertIsNone(mirror(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods), chord)
        self.assertIsNotNone(mirror("Enter", alt=True))  # Alt+Enter is a different chord from the Enter that is not taken

    def test_bare_letters_and_digits_are_not_claimed_on_any_shell_page(self):
        for key in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789":
            for page in shell.PAGES:
                self.assertIsNone(mirror(key, page=page), (key, page))
                self.assertIsNone(mirror(key, shift=True, page=page), (key, page))

    def test_a_key_release_is_never_claimed(self):
        for chord, *_rest in ROWS:
            mods, key = model.parse_chord(chord)
            self.assertIsNone(mirror(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods, key_down=False), chord)

    def test_page_rules(self):
        self.assertEqual(("OPEN_SEARCH", None, 0), mirror("K", ctrl=True, page="HOME"))
        self.assertIsNone(mirror("K", ctrl=True, page="SEARCH"))
        self.assertIsNone(mirror("F", ctrl=True, page="SEARCH"))
        self.assertEqual(("OPEN_SEARCH", None, 0), mirror("F", ctrl=True, page="OTHER"))
        for page in ("HOME", "DYNAMIC"):
            self.assertEqual(("REFRESH", None, 0), mirror("F5", page=page))
            self.assertEqual(("REFRESH", None, 0), mirror("R", ctrl=True, page=page))
        for page in ("SEARCH", "OTHER", "PLAYER"):
            self.assertIsNone(mirror("F5", page=page))
            self.assertIsNone(mirror("R", ctrl=True, page=page))
        for page in ("HOME", "DYNAMIC", "SEARCH", "OTHER"):
            self.assertEqual(("RAIL_TOGGLE", None, 0), mirror("B", ctrl=True, page=page))
        self.assertIsNone(mirror("B", ctrl=True, page="PLAYER"))  # the side panel of the video page, not the rail
        self.assertEqual(("BACK", None, 0), mirror("Left", alt=True, page="PLAYER"))
        self.assertEqual(("FULLSCREEN_TOGGLE", None, 0), mirror("F11", page="PLAYER"))
        self.assertEqual(("FULLSCREEN_TOGGLE", None, 0), mirror("Enter", alt=True, page="PLAYER"))
        self.assertEqual(("SHORTCUT_HELP", None, 0), mirror("F1", page="PLAYER"))
        # F6 stays with the shell on the video page: the content and the side panel are the panes it cycles there.
        self.assertEqual(("CYCLE_PANES", None, 1), mirror("F6", page="PLAYER"))
        self.assertEqual(("CYCLE_PANES", None, -1), mirror("F6", shift=True, page="PLAYER"))
        for key, kwargs in (("K", dict(ctrl=True)), ("1", dict(ctrl=True)), ("Comma", dict(ctrl=True)), ("J", dict(ctrl=True))):
            self.assertIsNone(mirror(key, page="PLAYER", **kwargs), (key, kwargs))

    def test_destinations_and_pane_cycling(self):
        names = CONTRACT["rail"]["fixed_destinations"]
        for number in range(1, 10):
            self.assertEqual(("GO_DESTINATION", names[number - 1], 0), mirror(str(number), ctrl=True), number)
            self.assertIsNone(mirror(str(number), ctrl=True, shift=True), number)
        self.assertEqual(("CYCLE_PANES", None, 1), mirror("F6"))
        self.assertEqual(("CYCLE_PANES", None, -1), mirror("F6", shift=True))
        self.assertEqual(("OPEN_SETTINGS", None, 0), mirror("Comma", ctrl=True))
        self.assertEqual(("OPEN_DOWNLOADS", None, 0), mirror("J", ctrl=True))
        self.assertEqual(("BACK", None, 0), mirror("Left", alt=True))
        self.assertIsNone(mirror("Left", ctrl=True))
        self.assertIsNone(mirror("Left"))

    def test_the_resolver_lines_are_pinned(self):
        text = shell.squash(SHORTCUTS_TEXT)
        for needle in (
            "if (!event.keyDown || context.textEditorFocused) return null",
            "if (event.meta || (event.ctrl && event.alt)) return null",
            "if (event.ctrl && event.key in DESKTOP_SHELL_ZOOM_KEYS) return null",
            "val chord = desktopShellChord(event)",
            "if (chord in DESKTOP_SHELL_RESERVED_CHORDS) return null",
            "val action = desktopShellActionByChord[chord] ?: return null",
            "return if (desktopShellPageAllows(action.kind, context.page)) action else null",
            'if (event.ctrl) append("Ctrl+") if (event.alt) append("Alt+") if (event.shift) append("Shift+") '
            'if (event.meta) append("Meta+") append(event.key)',
            "DESKTOP_SHELL_SHORTCUTS.associate { it.chord to it.action }",
            "DesktopShellPage.PLAYER -> kind in desktopShellPlayerPageKinds "
            "DesktopShellPage.SEARCH -> kind != DesktopShellActionKind.OPEN_SEARCH && kind != DesktopShellActionKind.REFRESH "
            "DesktopShellPage.HOME, DesktopShellPage.DYNAMIC -> true "
            "DesktopShellPage.OTHER -> kind != DesktopShellActionKind.REFRESH",
        ):
            self.assertIn(needle, text, needle)

    def test_the_file_is_pure_plain_ascii_without_a_throwing_construct(self):
        self.assertTrue(SHORTCUTS_TEXT.isascii())
        self.assertEqual([], source.cjk_positions(SHORTCUTS_TEXT))
        code = source.mask_kotlin(SHORTCUTS_TEXT)
        for banned in ("androidx", "java.awt", "KeyEvent", "KeyEventType", "Key.", "javax.swing", "KeyFallback"):
            self.assertFalse(banned in code, banned)
        self.assertEqual([], re.findall(r"(?m)^import .*$", SHORTCUTS_TEXT))


# --------------------------------------------------------------------------------------
# The strings
# --------------------------------------------------------------------------------------

class ShortcutStringsTest(unittest.TestCase):
    def test_every_action_kind_has_exactly_one_label_branch(self):
        enum = re.search(r"internal enum class DesktopShellActionKind \{(.*?)\}", SHORTCUTS_TEXT, re.S)
        kinds = re.findall(r"[A-Z_]+", enum.group(1))
        self.assertEqual(sorted(kinds), sorted(shell.action_label_kinds(STRINGS_TEXT)))

    def test_the_labels_name_the_actions_in_plain_chinese(self):
        branch = lambda kind: re.search(r"DesktopShellActionKind\.%s -> (.+)" % kind, STRINGS_TEXT).group(1)
        self.assertIn('"返回"', branch("BACK"))
        self.assertIn('"刷新"', branch("REFRESH"))
        self.assertIn('"全屏"', branch("FULLSCREEN_TOGGLE"))
        self.assertIn("SEARCH", branch("OPEN_SEARCH"))
        self.assertIn('"转到"', branch("GO_DESTINATION"))
        self.assertIn("railLabel", branch("GO_DESTINATION"))
        self.assertIn('"快捷键列表"', branch("SHORTCUT_HELP"))

    def test_every_label_branch_is_pinned_to_its_exact_text(self):
        # Every branch of actionLabel, whole: a changed word, an exchanged pair or a swapped direction fails here.
        branch = lambda kind: re.search(r"DesktopShellActionKind\.%s -> (.+)" % kind, STRINGS_TEXT).group(1)
        self.assertEqual({
            "OPEN_SEARCH": "SEARCH",
            "BACK": '"返回"',
            "REFRESH": '"刷新"',
            "GO_DESTINATION": '"转到" + (action.destination?.let { railLabel(it) } ?: "")',
            "RAIL_TOGGLE": '"折叠或展开导航栏"',
            "OPEN_SETTINGS": '"设置"',
            "OPEN_DOWNLOADS": '"下载与离线"',
            "CYCLE_PANES": 'if (action.direction < 0) "切换到上一个区域" else "切换到下一个区域"',
            "SHORTCUT_HELP": '"快捷键列表"',
            "FULLSCREEN_TOGGLE": '"全屏"',
        }, {kind: branch(kind) for kind in shell.action_label_kinds(STRINGS_TEXT)})
        self.assertEqual(10, len(shell.action_label_kinds(STRINGS_TEXT)))

    def test_the_settings_and_downloads_labels_are_the_rail_labels_of_those_rows(self):
        labels = shell.rail_labels(STRINGS_TEXT)
        self.assertEqual('"%s"' % labels["SETTINGS"], re.search(r"OPEN_SETTINGS -> (.+)", STRINGS_TEXT).group(1))
        self.assertEqual('"%s"' % labels["DOWNLOADS"], re.search(r"OPEN_DOWNLOADS -> (.+)", STRINGS_TEXT).group(1))

    def test_the_ctrl_b_note_says_what_the_key_does_on_the_video_page(self):
        self.assertEqual(1, STRINGS_TEXT.count('const val CTRL_B_NOTE = "Ctrl+B 在视频页打开或关闭详情面板"'))

    def test_the_shortcut_hint_of_the_search_row_is_the_ctrl_k_chord(self):
        self.assertIn('const val SEARCH_HINT = "Ctrl K"', STRINGS_TEXT)
        self.assertEqual("OPEN_SEARCH", next(row["action"] for row in SHELL_ROWS if row["chord"] == "Ctrl+K"))


if __name__ == "__main__":
    unittest.main()
