"""Slice S2 checks: the shell shortcut host wired to the Root key handler, F6 pane cycling, the mouse Back button, the collision test
of spec 10.1 over the real table, and hunk RH1 (part S2). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_shell_shortcut_host.py

What is proved.

  ContractTest        the contract section shell_host (panes, the smallest cycle, the repeat window, the routes of each page, the Back rules)
                      equals what the Kotlin host declares, and the route lists equal the sets the Root chrome and the Root stack already use
  PaneCyclingTest     the mirror of desktopShellNextPane agrees with an independent ring over every subset of panes, every current pane
                      and several directions; the golden cases of spec 6.1; with fewer than two panes nothing cycles
  HeldKeysTest        a claimed press owns its repeats and its release; the window, a different chord, a stale claim and a clock that runs
                      backwards
  PageTest            the page of a route, the Back guard at the first page, the text editor test over a focus-owner chain
  HostModelTest       the mirror of DesktopShellShortcutHost.handleKey and perform, run over the Kotlin table: every row is claimed on a shell
                      page and runs its one effect, the page rules, the text editor rule, the Back guard, repeats and releases, the mouse Back
                      button, and a sweep of every key under all modifier combinations (nothing is claimed that is not a table row)
  KeyNameTest         every key of the table has an AWT key code that reduces to its name, and the zoom decoder's codes reduce to names the
                      resolver refuses
  CollisionTest       spec 10.1 over the real Kotlin table: no duplicate chord per scope, no bare printable key on shell pages, no Ctrl+Alt,
                      no overlap with the zoom decoder set (read from the real decoder), none with a Windows, text-editing or focus
                      traversal chord, none with the vendored player map, and one owner for F6
  SourceShapeTest     the text of the host (the order of the checks, one effect per action kind, the guards), no global hook and no second
                      key-fallback slot, the frame and Root stack wiring, the hints constant
  Rh1HunkTest         the three RH1 hunks of this slice (the val, the one branch, the provider entry), reverse-applied to the Root-reserved
                      DesktopShell.kt, give the base blob byte for byte (sha256 and size pinned); the branch sits after F11 and Esc and
                      before the player branch; the Root stack hunk likewise
  FilesTest           line endings, the program scopes of the hygiene suite, the JVM test classes exist and use no window

What is not proved. That the Kotlin compiles or runs. DesktopShellShortcutHostTest and DesktopShellShortcutsTest are the proof of the
pure parts on the Linux compile later; the composables that bind the host and the focus groups (DesktopShellFrameBinding,
DesktopShellRootBinding, the pane modifier) and the delivery of the mouse Back button and of the keys through the real window are [W]
items for the user's PC.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2
says; Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.

Slice notes, kept here with the pins they explain.
  * Where the host is bound. The registration of the commands that need the route owner and the chrome sits in the free file
    DesktopOriginalRootStack.kt (one call before DesktopOriginalRootChrome), not in the Root-reserved DesktopOriginalRootChrome.kt: the
    Stack has routes, chrome bindings and the visible items in every rail mode and on every route, the chrome file is not named by hunk
    RH1. The frame (a free file) lends the rail toggle and the panes.
  * Back at the first page. requestBack at the root of the Home tab runs the back-at-root callback of the Root services, which closes the
    application (Esc already does that today). Alt+Left and the mouse Back button must not, so the host asks canGoBack first and does
    nothing at the first page. Esc is unchanged.
  * The text editor test. The Root has a flag for it (searchFocused) that nothing ever sets, and the leaf uses a Swing text component test
    that a Compose text field is not. The host asks the focus owner and its parents for getInputMethodRequests: the layer component of the
    Compose scene returns the request of the active text input session and null otherwise (checked in the pinned ui-desktop jar), and a
    Swing text component is tested by type. The Compose half is a [W] item until a real window says so.
"""
import hashlib
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_player_keys_model as pk  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402
import ux_vendored_player_keys as vk  # noqa: E402

CONTRACT = model.load()
HOST_CONTRACT = CONTRACT["shell_host"]
SHORTCUT_CONTRACT = CONTRACT["shortcuts"]

HOST_KT = shell.HOST_KT
SHELL_KT = source.K / "DesktopShell.kt"
STACK_KT = source.K / "ui/DesktopOriginalRootStack.kt"
FRAME_KT = source.K / "ui/DesktopWindowsShellFrame.kt"
CHROME_KT = shell.ROOT_CHROME
NAV_KEY_KT = source.REPO / "app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKey.kt"
HOST_TEST_KT = source.KT / "ui/DesktopShellShortcutHostTest.kt"
SHORTCUTS_TEST_KT = source.KT / "ui/DesktopShellShortcutsTest.kt"

HOST = source.read(HOST_KT)
SHELL = source.read(SHELL_KT)
STACK = source.read(STACK_KT)
FRAME = source.read(FRAME_KT)
CHROME = source.read(CHROME_KT)
HOST_CODE = source.mask_kotlin(HOST)
SHELL_CODE = source.mask_kotlin(SHELL)
STACK_CODE = source.mask_kotlin(STACK)
FRAME_CODE = source.mask_kotlin(FRAME)
HOST_FLAT = shell.squash(HOST_CODE)
STACK_FLAT = shell.squash(STACK_CODE)
FRAME_FLAT = shell.squash(FRAME_CODE)

SHORTCUTS_TEXT = shell.read(shell.SHORTCUTS_KT)
ROWS = shell.shortcut_rows(SHORTCUTS_TEXT)
TABLE = shell.kotlin_shell_table(ROWS)
RESERVED = set(shell.string_set(SHORTCUTS_TEXT, "DESKTOP_SHELL_RESERVED_CHORDS"))
ZOOM = set(shell.string_set(SHORTCUTS_TEXT, "DESKTOP_SHELL_ZOOM_KEYS"))
PANES = shell.host_panes(HOST)
WINDOW_MS = int(re.search(r"internal const val DESKTOP_SHELL_REPEAT_WINDOW_MS = (\d+)L", HOST).group(1))


def unique(names):
    seen = []
    for name in names:
        if name not in seen:
            seen.append(name)
    return seen


def function_block(code, name):
    """Masked source from the signature of the top-level function `name` to its closing brace at column 0 (or the end of an expression body)."""
    match = re.search(r"(?m)^(?:internal |private )?fun (?:<[^>]+> )?(?:(?:Modifier|BoxScope)\.)?%s\(" % re.escape(name), code)
    if match is None:
        raise AssertionError("no function " + name)
    end = code.find("\n}\n", match.end())
    if end < 0:
        raise AssertionError("no closing brace for " + name)
    return code[match.start():end + 2]


def class_block(code, name):
    match = re.search(r"(?m)^internal class %s\b" % re.escape(name), code)
    if match is None:
        raise AssertionError("no class " + name)
    end = code.find("\n}\n", match.end())
    return code[match.start():end + 2]


def rig(page="HOME", frame=True, window_ms=None):
    host = shell.HostKt(TABLE, RESERVED, ZOOM, PANES, WINDOW_MS if window_ms is None else window_ms)
    host.bind_root(page)
    if frame:
        host.bind_frame(["RAIL", "CONTENT"])
    return host


# --------------------------------------------------------------------------------------
# Contract
# --------------------------------------------------------------------------------------

class ContractTest(unittest.TestCase):
    def test_the_section_has_exactly_the_documented_keys(self):
        self.assertEqual(["about", "panes", "min_panes_to_cycle", "repeat_window_ms", "page_routes", "back", "editor_focus"], list(HOST_CONTRACT))

    def test_the_panes_are_the_kotlin_enum_in_cycle_order(self):
        self.assertEqual(["RAIL", "CONTENT", "SIDE_PANEL"], HOST_CONTRACT["panes"])
        self.assertEqual(HOST_CONTRACT["panes"], PANES)

    def test_the_smallest_cycle_is_two_panes_and_the_kotlin_says_so(self):
        self.assertEqual(2, HOST_CONTRACT["min_panes_to_cycle"])
        self.assertIn("if (order.size < 2) return null", HOST_FLAT)

    def test_the_repeat_window_is_the_contract_number(self):
        self.assertEqual(1500, HOST_CONTRACT["repeat_window_ms"])
        self.assertEqual(HOST_CONTRACT["repeat_window_ms"], WINDOW_MS)

    def test_the_routes_of_each_page_are_the_branches_of_the_kotlin_when(self):
        routes = shell.host_page_routes(HOST)
        self.assertEqual(set(HOST_CONTRACT["page_routes"]), set(routes) - {"OTHER"})
        for page, names in HOST_CONTRACT["page_routes"].items():
            self.assertEqual(names, unique(routes[page]), page)
        self.assertEqual([], routes["OTHER"])
        self.assertIn("else -> DesktopShellPage.OTHER", HOST_FLAT)

    def test_the_player_routes_are_exactly_the_routes_the_root_chrome_calls_video(self):
        chrome = shell.squash(source.mask_kotlin(CHROME))
        line = re.search(r"val video = (key is BiliPaiNavKey\.\w+(?: \|\| key is BiliPaiNavKey\.\w+)*)", chrome).group(1)
        self.assertEqual(HOST_CONTRACT["page_routes"]["PLAYER"], re.findall(r"BiliPaiNavKey\.(\w+)", line))

    def test_the_search_routes_are_the_search_branch_of_the_root_stack(self):
        self.assertIn("is BiliPaiNavKey.Search, BiliPaiNavKey.Search, BiliPaiNavKey.SearchTrending ->", STACK_FLAT)
        self.assertEqual(["Search", "SearchTrending"], HOST_CONTRACT["page_routes"]["SEARCH"])

    def test_every_route_name_is_a_real_nav_key(self):
        text = source.read(NAV_KEY_KT)
        for names in HOST_CONTRACT["page_routes"].values():
            for name in names:
                self.assertRegex(text, r"(?m)^    (?:data class|data object) %s\b" % name, name)

    def test_the_back_rules_and_the_editor_rule_are_stated(self):
        back = HOST_CONTRACT["back"]
        self.assertTrue(back["never_exits_the_app"])
        self.assertTrue(back["acts_once_per_press"])
        self.assertEqual("Back", back["mouse_button"])
        self.assertEqual("the Home tab at the root of the stack", back["idle_at"])
        self.assertIn("getInputMethodRequests", HOST_CONTRACT["editor_focus"])


# --------------------------------------------------------------------------------------
# F6
# --------------------------------------------------------------------------------------

class PaneCyclingTest(unittest.TestCase):
    def test_the_mirror_agrees_with_an_independent_ring_everywhere(self):
        checked = 0
        for mask in range(8):
            available = [pane for index, pane in enumerate(PANES) if mask & (1 << index)]
            for current in [None] + PANES:
                for direction in (-1, 0, 1, 7):
                    self.assertEqual(shell.oracle_next_pane(available, current, direction),
                                     shell.next_pane_kt(available, current, direction, PANES), (available, current, direction))
                    checked += 1
        self.assertEqual(8 * 4 * 4, checked)

    def test_golden_cases(self):
        both = ["RAIL", "CONTENT"]
        self.assertEqual("CONTENT", shell.next_pane_kt(both, "RAIL", 1, PANES))
        self.assertEqual("RAIL", shell.next_pane_kt(both, "CONTENT", 1, PANES))
        self.assertEqual("CONTENT", shell.next_pane_kt(both, "RAIL", -1, PANES))
        self.assertEqual("RAIL", shell.next_pane_kt(both, None, 1, PANES))
        self.assertEqual("CONTENT", shell.next_pane_kt(both, None, -1, PANES))
        self.assertEqual("SIDE_PANEL", shell.next_pane_kt(PANES, "CONTENT", 1, PANES))
        self.assertEqual("RAIL", shell.next_pane_kt(PANES, "SIDE_PANEL", 1, PANES))
        self.assertEqual("SIDE_PANEL", shell.next_pane_kt(PANES, "RAIL", -1, PANES))
        for available in ([], ["CONTENT"], ["SIDE_PANEL"]):
            for current in (None, "CONTENT"):
                self.assertIsNone(shell.next_pane_kt(available, current, 1, PANES))

    def test_the_kotlin_lines_are_the_transliteration(self):
        block = shell.squash(function_block(HOST_CODE, "desktopShellNextPane"))
        for needle in ("val order = DesktopShellPane.entries.filter { it in available }",
                       "if (order.size < 2) return null",
                       "val index = if (current == null) -1 else order.indexOf(current)",
                       "if (index < 0) return if (direction < 0) order.last() else order.first()",
                       "val step = if (direction < 0) -1 else 1",
                       "return order[(index + step + order.size) % order.size]"):
            self.assertEqual(1, block.count(needle), needle)

    def test_the_side_panel_has_no_registration_yet(self):
        # The video page layout of the side panel (V7) is not built, and the leaf is Root-reserved: nothing binds the third pane.
        self.assertEqual(1, len(re.findall(r"DesktopShellPane\.SIDE_PANEL -> false", HOST_CODE)))
        self.assertIsNone(re.search(r"DesktopShellPane\.SIDE_PANEL", FRAME_CODE))


# --------------------------------------------------------------------------------------
# Held keys
# --------------------------------------------------------------------------------------

class HeldKeysTest(unittest.TestCase):
    def test_a_claimed_press_owns_its_repeats_within_the_window_and_its_release(self):
        held = shell.HeldKeysKt(WINDOW_MS)
        self.assertFalse(held.is_repeat("K", "Ctrl+K", 0))
        held.claim("K", "Ctrl+K", 0)
        self.assertTrue(held.is_repeat("K", "Ctrl+K", 500))
        self.assertTrue(held.is_repeat("K", "Ctrl+K", 500 + WINDOW_MS))
        self.assertTrue(held.is_repeat("K", "Ctrl+K", 500 + WINDOW_MS + 30))
        self.assertTrue(held.release("K"))
        self.assertFalse(held.release("K"))
        self.assertFalse(held.is_repeat("K", "Ctrl+K", 5000))

    def test_the_window_is_inclusive_and_a_stale_claim_goes_away(self):
        held = shell.HeldKeysKt(WINDOW_MS)
        held.claim("K", "Ctrl+K", 1000)
        self.assertFalse(held.is_repeat("K", "Ctrl+K", 1000 + WINDOW_MS + 1))
        self.assertFalse(held.release("K"), "the stale claim was dropped by the look")
        held.claim("K", "Ctrl+K", 1000)
        self.assertTrue(held.is_repeat("K", "Ctrl+K", 1000 + WINDOW_MS))

    def test_another_chord_on_the_same_key_and_a_clock_that_ran_back_are_not_repeats(self):
        held = shell.HeldKeysKt(WINDOW_MS)
        held.claim("K", "Ctrl+K", 100)
        self.assertFalse(held.is_repeat("K", "K", 200))
        self.assertFalse(held.release("K"))
        held.claim("K", "Ctrl+K", 1000)
        self.assertFalse(held.is_repeat("K", "Ctrl+K", 500))

    def test_two_keys_are_held_independently(self):
        held = shell.HeldKeysKt(WINDOW_MS)
        held.claim("F5", "F5", 0)
        held.claim("K", "Ctrl+K", 10)
        self.assertTrue(held.is_repeat("F5", "F5", 600))
        self.assertTrue(held.release("K"))
        self.assertTrue(held.is_repeat("F5", "F5", 900))

    def test_the_kotlin_class_is_the_transliteration(self):
        text = shell.squash(class_block(HOST_CODE, "DesktopShellHeldKeys"))
        for needle in ("private class Held(val chord: String, var lastMs: Long)",
                       "val press = held[key] ?: return false",
                       "if (press.chord == chord && now - press.lastMs in 0L..DESKTOP_SHELL_REPEAT_WINDOW_MS) { press.lastMs = now return true }",
                       "held.remove(key) return false",
                       "held[key] = Held(chord, nowMs())",
                       "fun release(key: String): Boolean = held.remove(key) != null"):
            self.assertEqual(1, text.count(needle), needle)


# --------------------------------------------------------------------------------------
# Pages, Back guard, text editor
# --------------------------------------------------------------------------------------

class PageTest(unittest.TestCase):
    ROUTES = HOST_CONTRACT["page_routes"]

    def test_every_route_of_the_contract_is_on_the_page_the_contract_names(self):
        for page, names in self.ROUTES.items():
            for name in names:
                self.assertEqual(None if page == "OFF" else page, shell.page_of_kt(self.ROUTES, name), name)

    def test_every_other_route_is_an_other_page(self):
        for name in ("MainHost", "Profile", "Settings", "TipsSettings", "DownloadList", "History", "Favorite", "WatchLater", "LiveList",
                     "ListenVideo", "Login", "Story", "Space", "ArticleDetail"):
            self.assertEqual("OTHER", shell.page_of_kt(self.ROUTES, name), name)

    def test_back_is_possible_everywhere_except_the_home_tab_at_the_root(self):
        self.assertFalse(shell.can_go_back_kt(1, True))
        self.assertFalse(shell.can_go_back_kt(0, True))
        self.assertTrue(shell.can_go_back_kt(1, False))
        self.assertTrue(shell.can_go_back_kt(2, True))
        self.assertTrue(shell.can_go_back_kt(2, False))
        self.assertIn("canGoBack = { !(routes.stack.size <= 1 && routes.loginReadDestination() == BiliPaiNavKey.Home) },", HOST_FLAT)

    def test_a_text_editor_is_a_text_component_or_a_focus_owner_chain_with_an_input_session(self):
        self.assertTrue(shell.text_editor_focused_kt([(True, None)]))
        self.assertTrue(shell.text_editor_focused_kt([(False, None), (False, "session")]))
        self.assertTrue(shell.text_editor_focused_kt([(False, "session")]))
        self.assertFalse(shell.text_editor_focused_kt([(False, None), (False, None)]))
        self.assertFalse(shell.text_editor_focused_kt([]))
        flat = shell.squash(function_block(HOST_CODE, "desktopShellTextEditorFocused"))
        self.assertIn("generateSequence(focusOwner) { it.parent }.any { it is JTextComponent || it.inputMethodRequests != null }", flat)

    def test_the_compose_scene_answers_with_a_request_only_while_a_text_field_is_in_session(self):
        # The proof of the premise of the text editor test, read from the pinned ui-desktop jar: the layer component returns the mediator's
        # currentInputMethodRequests, enableInput sets it and disableInput clears it. Skipped where the jars are not present.
        import os
        import subprocess
        import zipfile
        roots = [Path(item) for item in os.environ.get("BILIPAI_UX_JAR_DIRS", "").split(os.pathsep) if item]
        try:
            common = subprocess.run(["git", "-C", str(source.REPO), "rev-parse", "--git-common-dir"], capture_output=True, text=True,
                                    timeout=30).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            common = ""
        if common:
            roots.append(Path(common).resolve().parent / ".local/fsr/base-runtime")
        jars = [jar for root in roots if root.is_dir() for jar in sorted(root.glob("ui-desktop-1.12.1-*.jar"))]
        if not jars:
            self.skipTest("the pinned ui-desktop jar is not on this machine")
        with zipfile.ZipFile(jars[0]) as jar:
            for layer in ("androidx/compose/ui/scene/skia/SwingSkiaLayerComponent$hierarchyRoot$1.class",
                          "androidx/compose/ui/scene/skia/SurfaceSkiaLayerComponent$hierarchyRoot$1.class"):
                data = jar.read(layer)
                self.assertIn(b"getInputMethodRequests", data, layer)
                self.assertIn(b"getCurrentInputMethodRequests", data, layer)
            component = jar.read("androidx/compose/ui/scene/ComposeSceneMediator$DesktopPlatformComponent.class")
            self.assertIn(b"enableInput", component)
            self.assertIn(b"disableInput", component)
            self.assertIn(b"setCurrentInputMethodRequests", component)


# --------------------------------------------------------------------------------------
# The host
# --------------------------------------------------------------------------------------

EFFECT_OF = {"OPEN_SEARCH": "openSearch", "BACK": "requestBack", "REFRESH": "refresh", "RAIL_TOGGLE": "toggleRail",
             "SHORTCUT_HELP": "openHelp", "FULLSCREEN_TOGGLE": "toggleFullscreen"}


def press(host, chord, down=True):
    mods, key = model.parse_chord(chord)
    return host.handle_key(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods, key_down=down)


class HostModelTest(unittest.TestCase):
    def test_every_row_is_claimed_on_a_shell_page_and_runs_its_one_effect(self):
        for chord, kind, destination, direction, _existing in ROWS:
            host = rig("HOME")
            self.assertTrue(press(host, chord), chord)
            if kind == "GO_DESTINATION":
                expected = ("goTo", destination)
            elif kind == "OPEN_SETTINGS":
                expected = ("goTo", "SETTINGS")
            elif kind == "OPEN_DOWNLOADS":
                expected = ("goTo", "DOWNLOADS")
            elif kind == "CYCLE_PANES":
                expected = ("focusPane", "CONTENT" if direction < 0 else "RAIL")
            elif kind == "REFRESH":
                expected = ("refresh", "HOME")
            else:
                expected = (EFFECT_OF[kind],)
            self.assertEqual([expected], host.calls, chord)
            self.assertTrue(press(host, chord, down=False), chord + " release")

    def test_the_page_rules(self):
        for page in ("SEARCH", "OTHER", "PLAYER"):
            host = rig(page)
            self.assertFalse(press(host, "F5"), page)
            self.assertFalse(press(host, "Ctrl+R"), page)
        for page in ("SEARCH", "PLAYER"):
            self.assertFalse(press(rig(page), "Ctrl+K"), page)
            self.assertFalse(press(rig(page), "Ctrl+F"), page)
        self.assertTrue(press(rig("OTHER"), "Ctrl+F"))
        for page in ("HOME", "DYNAMIC"):
            host = rig(page)
            self.assertTrue(press(host, "F5"))
            self.assertEqual([("refresh", page)], host.calls)

    def test_a_player_page_keeps_back_fullscreen_help_and_the_pane_cycle(self):
        kept = {"BACK", "FULLSCREEN_TOGGLE", "SHORTCUT_HELP", "CYCLE_PANES"}
        for chord, kind, _destination, _direction, _existing in ROWS:
            host = shell.HostKt(TABLE, RESERVED, ZOOM, PANES, WINDOW_MS)
            host.bind_root("PLAYER")
            host.bind_frame(PANES)
            self.assertEqual(kind in kept, press(host, chord), chord)

    def test_no_page_without_shortcuts_and_no_unbound_root_claims_anything(self):
        off = rig(None)
        for chord, *_rest in ROWS:
            self.assertFalse(press(off, chord), chord)
        self.assertEqual([], off.calls)
        unbound = shell.HostKt(TABLE, RESERVED, ZOOM, PANES, WINDOW_MS)
        self.assertFalse(press(unbound, "Ctrl+K"))
        self.assertFalse(press(unbound, "Alt+Enter"))

    def test_a_text_editor_owning_the_focus_stops_every_key(self):
        host = rig()
        host.text_editor = True
        for chord, *_rest in ROWS:
            self.assertFalse(press(host, chord), chord)
        self.assertEqual([], host.calls)
        host.text_editor = False
        self.assertTrue(press(host, "Ctrl+K"))

    def test_back_does_nothing_at_the_first_page_and_follows_the_owner_elsewhere(self):
        host = rig()
        host.root["can_go_back"] = False
        self.assertTrue(press(host, "Alt+Left"))
        self.assertEqual([], host.calls)
        host = rig()
        self.assertTrue(press(host, "Alt+Left"))
        self.assertEqual([("requestBack",)], host.calls)
        host = rig()
        host.request_back_result = False
        self.assertFalse(press(host, "Alt+Left"))
        self.assertEqual([("requestBack",)], host.calls)

    def test_a_held_chord_acts_once_and_the_next_press_acts_again(self):
        host = rig()
        self.assertTrue(press(host, "Alt+Enter"))
        host.now = 600
        self.assertTrue(press(host, "Alt+Enter"))
        host.now = 630
        self.assertTrue(press(host, "Alt+Enter"))
        self.assertEqual([("toggleFullscreen",)], host.calls)
        self.assertTrue(press(host, "Alt+Enter", down=False))
        host.now = 700
        self.assertTrue(press(host, "Alt+Enter"))
        self.assertEqual([("toggleFullscreen",), ("toggleFullscreen",)], host.calls)

    def test_a_press_that_nothing_could_act_on_is_not_claimed_and_not_held(self):
        host = rig(frame=False)
        self.assertFalse(press(host, "Ctrl+B"))
        host.now = 40
        self.assertFalse(press(host, "Ctrl+B"))
        host.bind_frame(["RAIL", "CONTENT"])
        host.now = 80
        self.assertTrue(press(host, "Ctrl+B"))
        self.assertEqual([("toggleRail",)], host.calls)

    def test_ctrl_b_needs_a_toggle_and_a_shell_page(self):
        classic = rig(frame=False)
        classic.bind_frame(["RAIL", "CONTENT"], toggleable=False)
        self.assertFalse(press(classic, "Ctrl+B"))
        self.assertFalse(press(rig("PLAYER"), "Ctrl+B"))
        hidden = rig(frame=False)
        hidden.bind_frame(["CONTENT"], rail_visible=False)
        self.assertFalse(press(hidden, "Ctrl+B"))

    def test_f6_follows_the_focused_pane(self):
        host = rig()
        self.assertTrue(press(host, "F6"))
        press(host, "F6", down=False)
        host.pane_focus_changed("RAIL", True)
        self.assertTrue(press(host, "F6"))
        press(host, "F6", down=False)
        host.pane_focus_changed("CONTENT", True)
        host.pane_focus_changed("RAIL", False)
        self.assertTrue(press(host, "Shift+F6"))
        press(host, "Shift+F6", down=False)
        host.pane_focus_changed("CONTENT", False)
        self.assertTrue(press(host, "F6"))
        self.assertEqual([("focusPane", "RAIL"), ("focusPane", "CONTENT"), ("focusPane", "RAIL"), ("focusPane", "RAIL")], host.calls)

    def test_f6_with_one_pane_or_no_frame_is_not_claimed(self):
        self.assertFalse(press(rig(frame=False), "F6"))
        single = rig(frame=False)
        single.bind_frame(["CONTENT"])
        self.assertFalse(press(single, "F6"))
        broken = rig(frame=False)
        broken.bind_frame(["RAIL", "CONTENT"], accept=False)
        self.assertFalse(press(broken, "F6"))
        self.assertEqual([("focusPane", "RAIL")], broken.calls)

    def test_an_effect_that_could_not_act_is_not_claimed(self):
        host = rig()
        host.root["accept"] = False
        for chord in ("Ctrl+K", "F5", "Ctrl+3", "Ctrl+Comma", "Ctrl+J", "F1"):
            self.assertFalse(press(host, chord), chord)
            self.assertFalse(press(host, chord, down=False), chord)

    def test_the_mouse_back_button_acts_once_per_physical_press(self):
        host = rig()
        self.assertTrue(host.back_press(True))
        self.assertFalse(host.back_press(True))
        self.assertFalse(host.back_press(False))
        host.back_release(False)
        self.assertTrue(host.back_press(True))
        host.back_release(True)
        self.assertFalse(host.back_press(True))
        host.back_release(False)
        self.assertEqual([("requestBack",), ("requestBack",)], host.calls)
        host.root["page"] = None
        self.assertFalse(host.back_press(True))
        host.back_release(False)
        self.assertFalse(shell.HostKt(TABLE, RESERVED, ZOOM, PANES, WINDOW_MS).back_press(True))
        first = rig()
        first.root["can_go_back"] = False
        self.assertTrue(first.back_press(True))
        self.assertEqual([], first.calls)

    def test_nothing_is_claimed_that_is_not_a_table_row_over_every_key_and_modifier(self):
        names = set(pk.AWT_KEYS[code][1] for code in pk.AWT_KEYS)
        keys = sorted(names | {"Tab", "Delete", "PageUp", "PageDown", "Insert"})
        claimed = 0
        for key in keys:
            for bits in range(16):
                ctrl, alt, shift, meta = bool(bits & 1), bool(bits & 2), bool(bits & 4), bool(bits & 8)
                for page in shell.PAGES:
                    host = rig(page)
                    host.bind_frame(PANES)
                    if host.handle_key(key, ctrl, alt, shift, meta):
                        claimed += 1
                        self.assertIn(shell.chord_kt(key, ctrl, alt, shift, meta), TABLE)
                        self.assertFalse(meta or (ctrl and alt))
                        self.assertFalse(ctrl and key in ZOOM)
        self.assertGreaterEqual(claimed, len(ROWS))


# --------------------------------------------------------------------------------------
# Key names
# --------------------------------------------------------------------------------------

class KeyNameTest(unittest.TestCase):
    RANGES, NAMED = pk.key_code_tables(source.read(pk.EFFECTS_KT))

    def name(self, code):
        return pk.key_name_kt(code, self.RANGES, self.NAMED)

    def test_every_key_of_the_table_has_an_awt_code_that_reduces_to_its_name(self):
        reduced = {self.name(code) for code in range(2048)} - {None}
        for chord, *_rest in ROWS:
            key = chord.split("+")[-1]
            self.assertIn(key, reduced, chord)
            codes = [code for code in range(2048) if self.name(code) == key]
            self.assertEqual(1, len([c for c in codes if pk.oracle_key_name(c) == key]), chord)

    def test_the_awt_oracle_agrees_with_the_kotlin_table_on_every_code(self):
        for code in range(2048):
            self.assertEqual(pk.oracle_key_name(code), self.name(code), code)

    def test_the_zoom_decoder_codes_reduce_to_names_the_resolver_refuses(self):
        decoder = shell.zoom_decoder_keys_from_source()
        self.assertEqual(decoder, ZOOM)
        reduced = {self.name(code) for code in (0x209, 0x3D, 0x6B, 0x2D, 0x6D, 0x30, 0x60)}
        self.assertEqual(ZOOM, reduced)
        for key in ZOOM:
            for page in shell.PAGES:
                self.assertIsNone(shell.resolve_shell_key_kt(TABLE, RESERVED, ZOOM, key, ctrl=True, page=page), (key, page))

    def test_the_host_reduces_an_event_with_the_player_key_table_and_the_four_modifiers(self):
        flat = shell.squash(function_block(HOST_CODE, "desktopShellKeyDataOf"))
        for needle in ("val keyDown = event.type == KeyEventType.KeyDown",
                       "if (!keyDown && event.type != KeyEventType.KeyUp) return null",
                       "val name = desktopPlayerKeyName(event.key.nativeKeyCode) ?: return null",
                       "key = name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed, "
                       "meta = event.isMetaPressed, keyDown = keyDown,"):
            self.assertEqual(1, flat.count(needle), needle)


# --------------------------------------------------------------------------------------
# Collisions (spec 10.1)
# --------------------------------------------------------------------------------------

OWNED_ELSEWHERE = (
    "Alt+F4", "Alt+Tab", "Alt+Space", "Alt+Esc", "Ctrl+Esc", "Ctrl+Alt+Delete", "Meta+D", "Meta+L", "F10", "Shift+F10", "Menu",
    "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Ctrl+Backspace", "Ctrl+Delete", "Ctrl+Left", "Ctrl+Right", "Ctrl+Home",
    "Ctrl+End", "Shift+Left", "Shift+Right", "Shift+Up", "Shift+Down", "Shift+Home", "Shift+End", "Home", "End", "Left", "Right", "Up",
    "Down", "PageUp", "PageDown", "Tab", "Shift+Tab", "Ctrl+Tab", "Space", "Enter", "Backspace", "Delete", "Esc", "Ctrl+Plus",
    "Ctrl+Minus", "Ctrl+0", "Ctrl+Equals",
)


class CollisionTest(unittest.TestCase):
    def real_rows(self):
        rows = [{"scope": "shell", "chord": chord, "action": kind} for chord, kind, _d, _dir, _e in ROWS]
        return rows + [row for row in SHORTCUT_CONTRACT["rows"] if row["scope"] == "player"]

    def violations(self, rows):
        return shell.shortcut_violations(rows, shell.zoom_decoder_keys_from_source(), SHORTCUT_CONTRACT["reserved"],
                                         SHORTCUT_CONTRACT["not_taken"], SHORTCUT_CONTRACT["dual_meaning"])

    def test_the_real_kotlin_table_with_the_player_table_has_no_violation_of_the_ten_one_rules(self):
        self.assertEqual([], self.violations(self.real_rows()))

    def test_the_collision_parser_still_finds_each_rule_on_a_broken_copy_of_the_real_table(self):
        rows = self.real_rows()
        broken = {
            "duplicate": rows + [{"scope": "shell", "chord": "Ctrl+K", "action": "OPEN_SEARCH"}],
            "bare-printable-shell": rows + [{"scope": "shell", "chord": "Comma", "action": "SHORTCUT_HELP"}],
            "ctrl-alt": rows + [{"scope": "shell", "chord": "Ctrl+Alt+K", "action": "SHORTCUT_HELP"}],
            "zoom-overlap": rows + [{"scope": "shell", "chord": "Ctrl+Shift+Equals", "action": "SHORTCUT_HELP"}],
            "ctrl-digit-range": rows + [{"scope": "shell", "chord": "Ctrl+0", "action": "SHORTCUT_HELP"}],
            "scope-conflict": rows + [{"scope": "player", "chord": "Ctrl+K", "action": "SEEK"}],
        }
        for code, table in broken.items():
            self.assertIn(code, {found for found, _row in self.violations(table)}, code)

    def test_no_shell_chord_is_a_windows_text_editing_or_focus_traversal_chord(self):
        self.assertEqual([], sorted(set(OWNED_ELSEWHERE) & set(TABLE)))
        for chord in OWNED_ELSEWHERE:
            mods, key = model.parse_chord(chord)
            for page in shell.PAGES:
                self.assertIsNone(shell.resolve_shell_key_kt(TABLE, RESERVED, ZOOM, key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods,
                                                             "Meta" in mods, True, page, False), (chord, page))

    def test_a_chord_in_two_scopes_has_one_action_or_is_the_dual_meaning_chord(self):
        by_chord = {}
        for row in SHORTCUT_CONTRACT["rows"]:
            by_chord.setdefault(row["chord"], {})[row["scope"]] = row["action"]
        for chord, scopes in by_chord.items():
            if len(scopes) > 1 and chord not in SHORTCUT_CONTRACT["dual_meaning"]:
                self.assertEqual(1, len(set(scopes.values())), chord)
        self.assertEqual({"shell": "RAIL_TOGGLE", "player": "SIDE_PANEL_OR_CINEMA"}, by_chord["Ctrl+B"])
        self.assertEqual({"shell": "FULLSCREEN_TOGGLE", "player": "FULLSCREEN_TOGGLE"}, by_chord["Alt+Enter"])

    def test_the_vendored_player_map_ignores_every_ctrl_alt_and_meta_chord_and_binds_no_other_shell_chord(self):
        text = source.read(vk.VENDORED_KEY_POLICY)
        self.assertEqual(1, len(re.findall(r"if \(event\.isCtrlPressed \|\| event\.isAltPressed \|\| event\.isMetaPressed\) \{\s*return null\s*\}", text)))
        bound = {vk.Router().chord_of(row["chord"]) for row in CONTRACT["player_keys"]["vendored"]["bindings"]}
        for chord in TABLE:
            mods, _key = model.parse_chord(chord)
            if "Ctrl" in mods or "Alt" in mods or "Meta" in mods:
                continue
            self.assertNotIn(chord, bound, chord)

    def test_f6_has_one_owner_the_shell(self):
        for chord in ("F6", "Shift+F6"):
            self.assertEqual(1, len([row for row in SHORTCUT_CONTRACT["rows"] if row["chord"] == chord]))
        self.assertEqual(0, len([row for row in SHORTCUT_CONTRACT["rows"] if row["scope"] == "player" and row["action"] == "CYCLE_PANES"]))

    def test_the_rail_roving_keys_are_unmodified_keys_that_no_shell_chord_uses(self):
        rail = source.read(source.K / "ui/DesktopWindowsNavigationRail.kt")
        flat = shell.squash(source.mask_kotlin(rail))
        self.assertIn("if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || "
                      "event.isShiftPressed) { return null }", flat)
        for chord in TABLE:
            self.assertNotIn(chord, ("Up", "Down", "Home", "End"), chord)

    def test_the_zoom_input_and_the_window_key_fallback_are_untouched(self):
        # The pins of the existing key paths: this slice adds one branch to the Root handler and reads the same events; the zoom input
        # (Ctrl+wheel, Ctrl+plus and minus, Ctrl+0) and the two-slot window key fallback are byte for byte what they were.
        pins = {"appearance/DesktopWindowsDisplayScaleInput.kt": "0c8940e5fada5292c8d8be6672db79b95c5437dcf3d1ba81d9d9b0a99ee8c956",
                "ui/DesktopWindowKeyFallback.kt": "f6dc3fe8fd83ac58483ed7c742511c73e621964ee8714397bc2bddec10870bb0"}
        for relative, digest in pins.items():
            self.assertEqual(digest, hashlib.sha256(source.read(source.K / relative).encode("utf-8")).hexdigest(), relative)
        self.assertIsNone(re.search(r"\.register(?:Leaf)?\(", HOST_CODE), "the host adds no slot to the window key fallback")
        self.assertEqual(1, SHELL_CODE.count("windowKeyFallback?.register { event ->"), "the Root keeps its one registration")

    def test_the_existing_f11_and_esc_handling_is_untouched_and_f11_is_marked_existing(self):
        self.assertEqual(1, SHELL_CODE.count("if (event.type == KeyEventType.KeyDown && event.key == Key.F11) { toggleOriginalFullscreen(); true }"))
        self.assertEqual(1, shell.squash(SHELL_CODE).count(
            "else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !searchFocused) { "
            "if (isFullscreen()) setOriginalFullscreen(false) else homeRootRef.get()?.navigation?.requestBack() true }"))
        self.assertEqual(["F11"], [chord for chord, _k, _d, _dir, existing in ROWS if existing])


# --------------------------------------------------------------------------------------
# Source shape
# --------------------------------------------------------------------------------------

class SourceShapeTest(unittest.TestCase):
    def test_the_checks_of_handle_key_come_in_this_order(self):
        body = shell.squash(HOST_CODE[HOST_CODE.index("internal fun handleKey("):HOST_CODE.index("private fun perform(")])
        order = ("if (!data.keyDown) return held.release(data.key)", "val commands = root ?: return false",
                 "val page = commands.page() ?: return false", "val chord = desktopShellChord(data)",
                 "if (held.isRepeat(data.key, chord)) return true",
                 "val action = resolveDesktopShellKey(data, DesktopShellKeyContext(page, textEditorFocused())) ?: return false",
                 "if (!perform(action, page, commands, toggleFullscreen)) return false", "held.claim(data.key, chord)", "return true")
        positions = [body.index(needle) for needle in order[:-1]] + [body.rindex(order[-1])]
        self.assertEqual(sorted(positions), positions)
        for needle in order[:-1]:
            self.assertEqual(1, body.count(needle), needle)
        self.assertEqual(2, body.count("return true"), "the repeat and the claim are the only two ways to answer true")

    def test_perform_has_one_branch_per_action_kind_and_no_else(self):
        block = shell.squash(HOST_CODE[HOST_CODE.index("private fun perform("):HOST_CODE.index("private fun back(")])
        kinds = re.findall(r"DesktopShellActionKind\.([A-Z_]+) ->", block)
        enum = re.search(r"internal enum class DesktopShellActionKind \{(.*?)\}", SHORTCUTS_TEXT, re.S)
        self.assertEqual(sorted(re.findall(r"[A-Z_]+", enum.group(1))), sorted(kinds))
        self.assertEqual(len(kinds), len(set(kinds)))
        self.assertNotIn("else ->", block)
        for needle in ("DesktopShellActionKind.OPEN_SEARCH -> commands.openSearch()", "DesktopShellActionKind.BACK -> back(commands)",
                       "DesktopShellActionKind.REFRESH -> commands.refresh(page)",
                       "DesktopShellActionKind.GO_DESTINATION -> action.destination?.let { commands.goTo(it) } ?: false",
                       "DesktopShellActionKind.RAIL_TOGGLE -> frame?.toggleRail?.invoke() ?: false",
                       "DesktopShellActionKind.OPEN_SETTINGS -> commands.goTo(DesktopRailItem.SETTINGS)",
                       "DesktopShellActionKind.OPEN_DOWNLOADS -> commands.goTo(DesktopRailItem.DOWNLOADS)",
                       "DesktopShellActionKind.CYCLE_PANES -> cyclePanes(action.direction)",
                       "DesktopShellActionKind.SHORTCUT_HELP -> commands.openHelp()",
                       "DesktopShellActionKind.FULLSCREEN_TOGGLE -> { toggleFullscreen() true }"):
            self.assertEqual(1, block.count(needle), needle)

    def test_back_never_leaves_the_app_and_the_cycle_asks_the_pure_rule(self):
        for needle in ("private fun back(commands: DesktopShellRootCommands): Boolean = if (commands.canGoBack()) requestBack() else true",
                       "val target = desktopShellNextPane(commands.availablePanes(), focusedPane, direction) ?: return false return commands.focusPane(target)",
                       "val fire = backPressed && !backHeld backHeld = backPressed if (!fire) return false",
                       "if (commands.page() == null) return false return back(commands)"):
            self.assertEqual(1, HOST_FLAT.count(needle), needle)
        self.assertEqual(1, len(re.findall(r"\brequestBack\(\)", HOST_CODE)))

    def test_the_bindings_clear_only_what_they_set(self):
        for needle in ("if (root?.owner === owner) root = null",
                       "if (frame?.owner === owner) { frame = null focusedPane = null }",
                       "SideEffect { host.bindFrame(commands) } DisposableEffect(host, rail) { onDispose { host.unbindFrame(rail) } }",
                       "SideEffect { host.bindRoot(commands) } DisposableEffect(host, routes) { onDispose { host.unbindRoot(routes) } }",
                       "val host = LocalDesktopShellShortcutHost.current ?: return"):
            self.assertIn(needle, HOST_FLAT, needle)
        self.assertEqual(2, HOST_FLAT.count("val host = LocalDesktopShellShortcutHost.current ?: return"))

    def test_the_root_commands_keep_the_owner_guard_and_the_admitted_entry(self):
        for needle in ("page = { if (routes.owns()) desktopShellPageOf(routes.loginReadDestination()) else null },",
                       "if (routes.owns()) { chrome.actions.searchClick() true } else { false }",
                       "DesktopShellPage.HOME -> { chrome.actions.homeDoubleTap() true }",
                       "DesktopShellPage.DYNAMIC -> { chrome.actions.dynamicDoubleTap() true }",
                       "if (routes.currentKey != BiliPaiNavKey.DownloadList) routes.push(BiliPaiNavKey.DownloadList)",
                       "val bottom = item.bottomNavName?.let { name -> visibleItems.firstOrNull { it.name == name } } ?: return false",
                       "val target = bottomPagerNavKeyForItem(bottom) if (routes.loginReadDestination() != target) routes.push(target)",
                       "if (routes.currentKey != BiliPaiNavKey.TipsSettings) routes.push(BiliPaiNavKey.TipsSettings)"):
            self.assertEqual(1, HOST_FLAT.count(needle), needle)
        self.assertEqual(3, len(re.findall(r"\broutes\.push\(", HOST_CODE)))
        self.assertIsNone(re.search(r"\b(?:routes\.stack\.(?:add|remove|clear)|\.replaceStack|returnToMainHostAdmitted)", HOST_CODE))
        self.assertEqual(3, len(re.findall(r"if \(!routes\.owns\(\)\) return", HOST_CODE)))

    def test_the_pane_modifier_is_a_focus_group_with_a_requester_and_a_focus_report(self):
        flat = HOST_FLAT
        self.assertEqual(1, flat.count("this.focusRequester(requester).onFocusChanged { host?.paneFocusChanged(pane, it.hasFocus) }.focusGroup()"))
        self.assertEqual(1, flat.count(".onPointerEvent(PointerEventType.Press) { event -> host.onBackButtonPress(event.buttons.isBackPressed) }"))
        self.assertEqual(1, flat.count(".onPointerEvent(PointerEventType.Release) { event -> host.onBackButtonRelease(event.buttons.isBackPressed) }"))
        self.assertIn("if (host == null) { this } else {", flat)
        self.assertIn("} catch (_: IllegalStateException) { false }", flat)
        self.assertIn("@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)", HOST)

    def test_no_global_hook_no_key_fallback_no_chinese_no_banned_import(self):
        self.assertEqual([], source.hook_uses(HOST))
        self.assertEqual([], source.key_fallback_uses(HOST))
        self.assertEqual([], source.cjk_positions(HOST))
        self.assertEqual([], source.banned_imports(HOST))
        self.assertTrue(HOST.isascii())
        for banned in ("LocalDesktopWindowKeyFallback", "Toolkit.", "addKeyListener", "MpvPlayer", "SwingPanel", "desktopCommandHitRegion"):
            self.assertNotIn(banned, HOST_CODE, banned)

    def test_the_host_file_is_in_the_strict_text_scope_of_the_hygiene_suite(self):
        names = {source.rel(path) for path in source.kotlin_files(source.STRICT_TEXT_GLOBS)}
        self.assertIn("ui/DesktopShellShortcutHost.kt", names)
        self.assertFalse(source.is_strings_file(HOST_KT))

    def test_the_root_stack_lends_the_commands_in_every_mode_before_the_chrome_and_inside_the_provider(self):
        self.assertEqual(1, len(re.findall(r"\bDesktopShellRootBinding\(", STACK_CODE)))
        self.assertEqual(1, STACK_FLAT.count("DesktopShellRootBinding(routes, chrome, visibleItems) DesktopOriginalRootChrome(routes, pages, chrome, currentItem, visibleItems,"))
        provider = STACK_CODE.rindex("CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides")
        self.assertLess(provider, STACK_CODE.index("DesktopShellRootBinding("))
        self.assertEqual(1, len(re.findall(r"\bDesktopOriginalRootChrome\(", STACK_CODE)))

    def test_the_frame_lends_the_toggle_and_the_panes_and_keeps_its_one_content_call(self):
        self.assertEqual(1, FRAME_FLAT.count("DesktopShellFrameBinding(railVisible, presentation != DesktopRailPresentation.CLASSIC, toggle, railPane, contentPane)"))
        self.assertLess(FRAME_FLAT.index("DesktopShellFrameBinding("), FRAME_FLAT.index("CompositionLocalProvider("))
        self.assertEqual(1, len(re.findall(r"\bcontent\(\)", FRAME_CODE)))
        self.assertEqual(2, len(re.findall(r"\.desktopShellPane\(", FRAME_CODE)))
        self.assertEqual(1, len(re.findall(r"\.desktopShellBackButton\(", FRAME_CODE)))
        self.assertEqual(1, FRAME_FLAT.count("val shortcuts = LocalDesktopShellShortcutHost.current"))
        self.assertEqual(2, len(re.findall(r"\bremember \{ FocusRequester\(\) \}", FRAME_FLAT)))
        self.assertIn("import androidx.compose.ui.focus.FocusRequester\n", FRAME)

    def test_the_rail_names_its_keys_now_and_the_hint_constant_is_the_one_switch(self):
        self.assertIn("internal const val DESKTOP_RAIL_SHORTCUT_HINTS = true\n", source.read(shell.RAIL_KT))
        self.assertEqual(1, len(re.findall(r"\bDESKTOP_RAIL_SHORTCUT_HINTS = ", source.read(shell.RAIL_KT))))

    def test_the_tips_page_has_no_general_note_any_more(self):
        strings = source.read(source.K / "ui/DesktopWindowsTipsStrings.kt")
        self.assertNotRegex(strings, r"const val NOTE\b")
        self.assertIn("const val PLAYER_NOTE", strings)
        shortcuts = source.mask_kotlin(source.read(source.K / "ui/DesktopWindowsTipsShortcuts.kt"))
        self.assertNotIn("DesktopWindowsTipsStrings.NOTE", shortcuts)

    def test_every_chord_of_the_shell_table_is_listed_on_the_tips_page(self):
        text = shell.squash(source.mask_kotlin(source.read(source.K / "ui/DesktopWindowsShortcutList.kt")))
        self.assertIn("table.map { it.chord to DesktopShellStrings.actionLabel(it.action) }", text)
        self.assertIn("desktopShellShortcutListRows().forEach { DesktopTipsShortcutRow(it) }",
                      shell.squash(source.mask_kotlin(source.read(source.K / "ui/DesktopWindowsTipsShortcuts.kt"))))


# --------------------------------------------------------------------------------------
# Hunk RH1, part S2
# --------------------------------------------------------------------------------------

# The three anchor-based hunks of slice S2 in file order: (before, after). Each "after" block occurs exactly once in DesktopShell.kt.
RH1_S2_HUNKS = (
    (
        "    val rootKeyHandler: (KeyEvent) -> Boolean = { event ->\n",
        "    // UX S2 (hunk RH1, part S2): one shell shortcut host. Back goes through the window navigation owner, as Esc does.\n"
        "    val shellShortcutHost = remember(homeRootRef) { com.bilipai.desktop.ui.DesktopShellShortcutHost({ homeRootRef.get()?.navigation?.requestBack() == true }) }\n"
        "    val rootKeyHandler: (KeyEvent) -> Boolean = { event ->\n",
    ),
    (
        "                true\n"
        "            }\n"
        "            else if ((showVideo || section == DesktopSection.STORY) && playing.details != null && playerFocused && !searchFocused && player != null) {\n",
        "                true\n"
        "            }\n"
        "            // UX S2 (hunk RH1, part S2): the shell shortcuts of spec 6.1 (Ctrl+K, Alt+Left, F5, Ctrl+1 to 9, F6, F1, Alt+Enter ...).\n"
        "            else if (shellShortcutHost.onKey(event) { toggleOriginalFullscreen() }) true\n"
        "            else if ((showVideo || section == DesktopSection.STORY) && playing.details != null && playerFocused && !searchFocused && player != null) {\n",
    ),
    (
        "        com.bilipai.desktop.appearance.LocalDesktopWindowsUx provides windowsUx,\n",
        "        com.bilipai.desktop.appearance.LocalDesktopWindowsUx provides windowsUx,\n"
        "        com.bilipai.desktop.ui.LocalDesktopShellShortcutHost provides shellShortcutHost,\n",
    ),
)
SHELL_BASE_SHA256 = "30b5b5ed9a7a7c9de8968a695a02108b8a2ed14437e1e4848f3ebf010c95e4b6"
SHELL_BASE_BYTES = 248607

# The one hunk of the Root stack (a free file): a comment and one call before the chrome.
STACK_HUNK = (
    "        LocalDesktopHomeMediaPorts provides root.media, LocalDesktopHomeEnvironment provides root.environment) {\n"
    "    DesktopOriginalRootChrome(routes, pages, chrome, currentItem, visibleItems,",
    "        LocalDesktopHomeMediaPorts provides root.media, LocalDesktopHomeEnvironment provides root.environment) {\n"
    "    // UX S2: the shell shortcut host acts through these same routes and chrome actions (Ctrl+K, F5, Ctrl+1 to 9, F1 ...).\n"
    "    DesktopShellRootBinding(routes, chrome, visibleItems)\n"
    "    DesktopOriginalRootChrome(routes, pages, chrome, currentItem, visibleItems,",
)
STACK_BASE_SHA256 = "6d4a54330c7fe9a9a087f1c4a8dfe568cc00229c21c2f7e1d5f36633cbcbe219"
STACK_BASE_BYTES = 17270


def reverse_apply(text):
    """DesktopShell.kt with the three RH1 (part S2) hunks undone, last hunk first."""
    for before, after in reversed(RH1_S2_HUNKS):
        if text.count(after) != 1:
            raise AssertionError("an RH1 hunk is not in the Root shell exactly once: " + after[:80])
        text = text.replace(after, before)
    return text


class Rh1HunkTest(unittest.TestCase):
    def test_the_hunks_undo_to_the_base_blob_byte_for_byte(self):
        base = reverse_apply(SHELL).encode("utf-8")
        self.assertEqual(SHELL_BASE_BYTES, len(base))
        self.assertEqual(SHELL_BASE_SHA256, hashlib.sha256(base).hexdigest())

    def test_the_hunks_add_five_lines_and_remove_none(self):
        base = reverse_apply(SHELL).splitlines()
        now = SHELL.splitlines()
        self.assertEqual(5, len(now) - len(base))
        added = [line for line in now if line not in base]
        self.assertEqual(5, len(added), added)
        for line in added:
            self.assertTrue(line.strip().startswith(("// UX S2", "val shellShortcutHost", "else if (shellShortcutHost", "com.bilipai.desktop.ui.LocalDesktopShellShortcutHost")), line)

    def test_the_branch_sits_after_f11_and_esc_and_before_the_player_branch(self):
        flat = shell.squash(SHELL_CODE)
        f11 = flat.index("event.key == Key.F11")
        esc = flat.index("event.key == Key.Escape && !searchFocused")
        host = flat.index("shellShortcutHost.onKey(event)")
        player = flat.index("else if ((showVideo || section == DesktopSection.STORY) && playing.details != null && playerFocused")
        self.assertTrue(f11 < esc < host < player)
        self.assertEqual(1, flat.count("shellShortcutHost.onKey(event)"))
        self.assertEqual(1, flat.count("else if (shellShortcutHost.onKey(event) { toggleOriginalFullscreen() }) true"))

    def test_the_branch_is_inside_the_root_key_handler_and_the_val_is_outside_it(self):
        start = SHELL.index("val rootKeyHandler: (KeyEvent) -> Boolean = { event ->")
        end = SHELL.index("val latestRootKeyHandler by rememberUpdatedState(rootKeyHandler)")
        block = SHELL[start:end]
        self.assertEqual(1, block.count("shellShortcutHost.onKey(event)"))
        self.assertNotIn("val shellShortcutHost", block)
        self.assertLess(SHELL.index("val shellShortcutHost"), start)
        self.assertFalse("Ux" in block, "the root key handler mentions the flags")

    def test_the_provider_entry_follows_the_flags_entry_inside_the_root_provider(self):
        code = SHELL_CODE
        flags = code.index("LocalDesktopWindowsUx provides windowsUx,")
        entry = code.index("LocalDesktopShellShortcutHost provides shellShortcutHost,")
        self.assertLess(flags, entry)
        self.assertLess(code[flags:entry].count("\n"), 2)
        self.assertEqual(1, code.count("LocalDesktopShellShortcutHost provides shellShortcutHost,"))
        self.assertEqual(1, code.count("val shellShortcutHost = remember(homeRootRef) {"))

    def test_the_host_gets_the_navigation_owner_back_and_the_shells_fullscreen_switch_only(self):
        # The shell does not give the host the player, the routes or the stack: back is requestBack of the handle's owner (as Esc does)
        # and fullscreen is the lambda that F11 already runs.
        self.assertEqual(2, SHELL_CODE.count("homeRootRef.get()?.navigation?.requestBack()"))
        self.assertEqual(1, len(re.findall(r"\bshellShortcutHost\b", SHELL_CODE[SHELL_CODE.index("val rootKeyHandler"):SHELL_CODE.index("val latestRootKeyHandler")])))

    def test_the_shell_stays_clear_of_the_flags_and_the_key_fallback_is_untouched(self):
        for needle in ("WINDOWS_UX_NAMESPACE", "windows_ux", "DesktopWindowsUxCommand", "desktopWindowsUx"):
            self.assertNotIn(needle, SHELL, needle)
        self.assertEqual(1, SHELL_CODE.count("windowKeyFallback?.register { event ->"))
        self.assertEqual(SHELL_CODE.count("latestRootKeyHandler"), source.mask_kotlin(reverse_apply(SHELL)).count("latestRootKeyHandler"))

    def test_the_stack_hunk_undoes_to_the_base_blob_and_is_two_lines(self):
        before, after = STACK_HUNK
        self.assertEqual(1, STACK.count(after))
        base = STACK.replace(after, before).encode("utf-8")
        self.assertEqual(STACK_BASE_BYTES, len(base))
        self.assertEqual(STACK_BASE_SHA256, hashlib.sha256(base).hexdigest())
        self.assertEqual(2, len(STACK.splitlines()) - len(STACK.replace(after, before).splitlines()))


# --------------------------------------------------------------------------------------
# Files
# --------------------------------------------------------------------------------------

class FilesTest(unittest.TestCase):
    def test_line_endings_of_every_file_of_the_slice(self):
        source.assert_lf_only(
            self, HOST_KT, SHELL_KT, STACK_KT, FRAME_KT, shell.RAIL_KT, HOST_TEST_KT, SHORTCUTS_TEST_KT, Path(__file__).resolve(),
            source.K / "ui/DesktopWindowsTipsStrings.kt", source.K / "ui/DesktopWindowsTipsShortcuts.kt",
            model.CONTRACT_PATH, model.KOTLIN_COPY_PATH, shell.HERE / "ux_shell_model.py", shell.HERE / "test_ux_rail_source.py",
            shell.HERE / "test_ux_settings_signatures.py")

    def test_the_two_contract_copies_are_byte_identical_and_in_sync(self):
        self.assertEqual(model.CONTRACT_PATH.read_bytes(), model.KOTLIN_COPY_PATH.read_bytes())
        self.assertEqual([], model.problems())

    def test_the_jvm_tests_exist_are_pure_and_use_no_window(self):
        text = source.read(HOST_TEST_KT)
        self.assertIn("class DesktopShellShortcutHostTest {", text)
        for banned in ("ImageComposeScene", "Window(", "ComposeWindow", "Robot", "java.net", "Dispatchers.Main", "Thread.sleep"):
            self.assertNotIn(banned, text, banned)
        names = re.findall(r"@Test fun (\w+)\(", text)
        self.assertEqual(len(names), len(set(names)))
        self.assertGreaterEqual(len(names), 35)
        self.assertIn("class DesktopShellShortcutsTest {", source.read(SHORTCUTS_TEST_KT))
        added = re.findall(r"@Test fun (\w+)\(", source.read(SHORTCUTS_TEST_KT))
        for name in ("everyKeyNameOfTheRealTableHasAnAwtKeyCodeSoTheHostCanReadIt",
                     "theRealTableCollidesWithNoWindowsSystemTextEditingOrFocusTraversalChord",
                     "aChordTheShellAndThePlayerBothListHasTheSameActionOrIsTheDualMeaningChord"):
            self.assertIn(name, added)

    def test_the_jvm_test_reads_the_contract_section_and_only_pure_parts_of_the_host(self):
        text = source.read(HOST_TEST_KT)
        self.assertIn('DesktopUxContract.section("shell_host")', text)
        for name in ("DesktopShellRootBinding(", "DesktopShellFrameBinding(", "desktopShellPane(", "desktopShellBackButton("):
            self.assertNotIn(name, text, name)


if __name__ == "__main__":
    unittest.main()
