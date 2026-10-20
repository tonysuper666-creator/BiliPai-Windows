"""Slice V12a source checks: the Windows fixture that measures whether the dormant original VideoDetail branch of the shell mounts (spec 4.13, 9.2, 10.3).
No JVM, no network, no window. The fixture itself is Windows-only and has not run; this suite pins what can be proved from the sources.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_original_route_fixture.py

The slice adds three Kotlin files to the TEST source set only (no main file changes, no behaviour change):

  ui/WindowsOriginalVideoRouteMountFixture.kt    the Windows main class: one actual Main, one ordinary video, one measurement of the accessibility tree
  ui/WindowsOriginalVideoRouteMountVerdict.kt    its pure part: the two anchor lists, the matcher and the truth table (no AWT, no Compose)
  ui/WindowsOriginalVideoRouteMountVerdictTest.kt the JVM test of the pure part (runs on Linux)

and this suite plus ux_original_route_model.py in desktop/tools/ux (the UX program's Python lives there, following the desktop/tools/nvidia
precedent, not in desktop/tools/tests).

What is pinned, by class:

  PremiseTest         the shell's VideoDetail branch calls the desktop leaf; the vendored page (VideoDetailScreenStateHolder, VideoPlayerSection,
                      VideoPlayerOverlay) is reachable only through DesktopOriginalVideoHolderRoute, which only the original leaf calls and the
                      shell gives the audio routes. The two branches are exclusive, so the source already says the original page does not mount.
  AnchorTablesTest    the fixture decides by accessible names, so every name must be one page's alone. Leaf anchors are the DETAILS and MORE_ACTIONS
                      labels of the LEGACY control bar (composed without any condition, in no file the original route reaches). Original anchors
                      are spelled as a content description, text or label inside a composable that only the original route reaches, and are a
                      literal of no desktop file and no composable the desktop leaf reaches. Re-derived from the call graph on every run.
  VerdictTest         the Kotlin truth table equals the model, the JVM test's eight cases agree with the model, the expectations accept what they
                      should, and the model table is checked against the documented rules written a second way.
  FixtureTest         protocol order (fresh guest profile, first drawn Root that is not the agreement gate, health, the startup video, measure,
                      classify, receipt, screenshot, exit), arguments, receipt keys, exit codes, the helper members it calls, and read-only: no
                      route, preference, key, pointer or agreement input, one file written (the receipt). Also what the protocol stands on:
                      its start equals the start of the live WindowsVideoActualRootUiFixture, Windows starts in Home (isRequired() is false, no
                      other source asks for the gate, --video= opens the video, so there is no agreement to accept and the agreement helpers
                      of the onboarding fixture are never called), and the KDoc says it is for the Windows cloud runner only (no silent profile).
  ScopeTest           test source only: no main file mentions the new names, each is declared once, imports resolve, and the Gradle wiring is
                      Root's (the KDoc snippet equals the existing smoke task with this fixture's name; if the build file registers it, identically).
  ScannerTest         the string-literal scanner and the small parsers on synthetic text, including that a quoted word in a comment is not a literal.
  MutationTest        each check fails on an edited copy of the real sources (in memory, nothing is written).
  HygieneTest         LF only, no control characters, UTF-8 without BOM, final newline.

Not claimed: that the fixture compiles or runs; that an absent original anchor proves the original page is absent (the run has no positive control
on the original page; the exclusive branches carry that proof); that the original page picks a layout that shows any particular anchor.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402
import ux_detail_window_model as detail  # noqa: E402
import ux_original_route_model as model  # noqa: E402

SLICE_KOTLIN = (model.VERDICT_KT, model.FIXTURE_KT, model.JVM_TEST_KT)
SLICE_PYTHON = (Path(model.__file__), Path(__file__))
# Names that both pages emit (checked below against the sources): they would make a measurement ambiguous, so they are never anchors.
AMBIGUOUS_NAMES = ("返回", "全屏", "播放", "暂停", "更多", "分享", "投币", "点赞", "设置", "投屏", "弹幕设置")


def verdict_text():
    return source.read(model.VERDICT_KT)


def fixture_text():
    return source.read(model.FIXTURE_KT)


def jvm_text():
    return source.read(model.JVM_TEST_KT)


def replaced(text, old, new, count=1):
    """text with old replaced by new, after checking old occurs exactly count times (a mutation that does nothing would make a test vacuous)."""
    assert text.count(old) == count, (old, text.count(old))
    return text.replace(old, new)


# ---------------------------------------------------------------------------------------------------------------------
class PremiseTest(unittest.TestCase):

    def test_the_nine_route_facts_hold_so_the_two_branches_of_the_shell_are_exclusive(self):
        universe, _, _ = model.graph()
        facts = detail.route_facts(universe.code[detail.SHELL_REL], universe.code[detail.ORIGINAL_LEAF_REL], universe.code)
        self.assertEqual(9, len(facts))
        self.assertEqual([], [name for name, value in facts.items() if not value])

    def test_the_premise_has_no_problem(self):
        self.assertEqual([], model.premise_problems())

    def test_the_original_route_reaches_the_original_page_and_the_desktop_leaf_reaches_none_of_it(self):
        _, original_reach, leaf_reach = model.graph()
        for name in model.ORIGINAL_PAGE_FUNCTIONS:
            self.assertIn(name, original_reach, name)
            self.assertNotIn(name, leaf_reach, name)

    def test_the_desktop_leaf_reaches_its_legacy_control_bar(self):
        _, _, leaf_reach = model.graph()
        self.assertIn("DesktopWindowsVideoControlBar", leaf_reach)
        self.assertNotIn("DesktopWindowsVideoControlBar", model.graph()[1])

    def test_the_shell_gives_the_original_leaf_only_the_audio_routes(self):
        universe, _, _ = model.graph()
        shell = universe.code[detail.SHELL_REL]
        audio = re.search(r"entryKey is BiliPaiNavKey\.AudioMode \|\| entryKey is BiliPaiNavKey\.NativeMusic\s*->\s*DesktopOriginalVideoPhysicalLeaf\(", shell)
        self.assertIsNotNone(audio)
        video = re.search(r"entryKey is BiliPaiNavKey\.VideoDetail\s*->", shell)
        self.assertIsNotNone(video)
        self.assertLess(video.start(), shell.index("DesktopWindowsVideoPhysicalLeaf(", video.end()))
        self.assertLess(shell.index("DesktopWindowsVideoPhysicalLeaf(", video.end()), audio.start())

    def calls(self, name):
        """{desktop main file: number of calls of the composable} (its own declaration is not a call)."""
        universe, _, _ = model.graph()
        pattern = re.compile(r"(?<!fun )\b" + name + r"\s*\(")
        found = {rel: len(pattern.findall(code)) for rel, code in universe.code.items() if rel.startswith(detail.DESKTOP_MAIN) and name in code}
        return {rel: count for rel, count in found.items() if count}

    def test_only_the_shell_calls_the_original_leaf_and_only_the_original_leaf_calls_the_holder_route(self):
        self.assertEqual({detail.SHELL_REL: 1}, self.calls("DesktopOriginalVideoPhysicalLeaf"))
        self.assertEqual({detail.ORIGINAL_LEAF_REL: 1}, self.calls("DesktopOriginalVideoHolderRoute"))
        self.assertEqual({detail.SHELL_REL: 1}, self.calls("DesktopWindowsVideoPhysicalLeaf"))

    def test_the_original_leaf_reaches_the_holder_route_only_behind_the_video_detail_key(self):
        universe, _, _ = model.graph()
        code = universe.code[detail.ORIGINAL_LEAF_REL]
        self.assertIsNotNone(re.search(r"if \(key is BiliPaiNavKey\.VideoDetail\) \{.*?\bDesktopOriginalVideoHolderRoute\s*\(", code, re.S))
        self.assertEqual(1, len(re.findall(r"\bDesktopOriginalVideoHolderRoute\s*\(", code)))


# ---------------------------------------------------------------------------------------------------------------------
class AnchorTablesTest(unittest.TestCase):

    def tables(self):
        return model.anchor_tables()

    def test_the_tables_are_the_pinned_ones(self):
        leaf, original = self.tables()
        self.assertEqual(model.EXPECTED_LEAF_ANCHORS, leaf)
        self.assertEqual(model.EXPECTED_ORIGINAL_ANCHORS, original)

    def test_the_leaf_anchors_have_no_problem(self):
        leaf, original = self.tables()
        self.assertEqual([], model.leaf_anchor_problems(leaf, original))

    def test_the_original_anchors_have_no_problem(self):
        leaf, original = self.tables()
        self.assertEqual([], model.original_anchor_problems(original, leaf))

    def test_every_original_anchor_is_spelled_by_a_composable_that_only_the_original_route_reaches(self):
        _, original = self.tables()
        _, original_reach, leaf_reach = model.graph()
        witnesses = model.anchor_witnesses(original)
        self.assertEqual(sorted(original), sorted(witnesses))
        for anchor, names in witnesses.items():
            self.assertTrue(names, anchor)
            for name in names:
                self.assertIn(name, original_reach, (anchor, name))
                self.assertNotIn(name, leaf_reach, (anchor, name))
        self.assertEqual({"BottomControlBar", "NativeDanmakuToggleButton", "PortraitTopBar", "PortraitTopControlBar", "TopControlBar", "UpInfoSection",
                          "UpPreviewSheetHeader", "VideoPlayerOverlay"}, {name for names in witnesses.values() for name in names})

    def test_the_tables_are_disjoint_trimmed_and_free_of_duplicates(self):
        leaf, original = self.tables()
        for table in (leaf, original):
            self.assertEqual(len(table), len(set(table)))
            self.assertTrue(all(name and name == name.strip() and "\n" not in name for name in table))
        self.assertEqual(set(), set(leaf) & set(original))

    def test_names_that_both_pages_emit_are_never_anchors(self):
        leaf, original = self.tables()
        desktop = model.desktop_main_files()
        _, original_reach, _ = model.graph()
        original_files = model.files_defining(original_reach)
        for name in AMBIGUOUS_NAMES:
            self.assertNotIn(name, leaf)
            self.assertNotIn(name, original)
            self.assertTrue(any(model.spelled_in_file(rel, name) for rel in desktop), "%s is not spelled by any desktop file" % name)
            self.assertTrue(any(model.spelled_in_file(rel, name) for rel in original_files), "%s is not spelled by the original route" % name)

    def test_the_leaf_anchors_are_constants_of_the_strings_object_that_the_bar_uses_as_labels(self):
        leaf, _ = self.tables()
        values = model.constants(source.read(model.STRINGS_KT))
        bar = source.read(model.BAR_KT)
        for anchor in leaf:
            constant = model.LEAF_CONSTANTS[anchor]
            self.assertEqual(anchor, values[constant])
            self.assertEqual(1, bar.count("label = DesktopWindowsVideoControlBarStrings." + constant))

    def test_the_leaf_anchors_are_composed_without_a_condition_in_the_bar(self):
        bar = source.read(model.BAR_KT)
        for constant in model.LEAF_CONSTANTS.values():
            self.assertTrue(model.unconditional_in_bar(bar, constant), constant)

    def test_the_unconditional_check_tells_a_conditional_button_from_an_unconditional_one(self):
        bar = source.read(model.BAR_KT)
        for constant in ("MINI_PLAYER", "VOLUME_AND_MUTE", "SPEED", "QUALITY", "PREVIOUS", "NEXT"):
            self.assertFalse(model.unconditional_in_bar(bar, constant), constant)
        for constant in ("DETAILS", "MORE_ACTIONS"):
            self.assertTrue(model.unconditional_in_bar(bar, constant), constant)

    def test_the_leaf_mounts_the_legacy_bar_on_page_entry(self):
        leaf = source.read(model.LEAF_KT)
        self.assertEqual(1, leaf.count("if (chromeVisible && legacyChrome) legacyControlBar()"))
        self.assertEqual(1, leaf.count("val chromeVisible = !fullscreen || chrome.visible"))


# ---------------------------------------------------------------------------------------------------------------------
class VerdictTest(unittest.TestCase):

    def test_the_kotlin_truth_table_expectations_and_cases_equal_the_model(self):
        self.assertEqual([], model.verdict_problems(verdict_text(), jvm_text()))

    def test_the_jvm_test_cases_cover_every_verdict(self):
        cases = model.kotlin_cases(jvm_text())
        self.assertEqual(8, len(cases))
        self.assertEqual(set(model.VERDICTS), {verdict for _, _, verdict in cases})
        self.assertEqual(len(cases), len({(a, b) for a, b, _ in cases}))

    def test_the_model_table_follows_the_documented_rules_written_a_second_way(self):
        leaf, original = model.anchor_tables()
        rows = model.model_table(leaf, original)
        self.assertEqual(4 * (len(original) + 2), len(rows))
        for leaf_seen, original_seen, verdict in rows:
            leaf_complete = len(leaf_seen) == len(leaf)
            if leaf_complete and not original_seen:
                self.assertEqual("DESKTOP_LEAF_ONLY", verdict)
            elif original_seen and not leaf_seen:
                self.assertEqual("ORIGINAL_ONLY", verdict)
            elif original_seen:
                self.assertEqual("BOTH", verdict)
            else:
                self.assertEqual("INCONCLUSIVE", verdict)
        self.assertEqual(set(model.VERDICTS), {verdict for _, _, verdict in rows})

    def test_one_original_anchor_decides_and_a_partial_leaf_never_proves_the_leaf(self):
        leaf, original = model.anchor_tables()
        self.assertEqual("DESKTOP_LEAF_ONLY", model.classify(leaf, [], leaf))
        self.assertEqual("INCONCLUSIVE", model.classify(leaf[:1], [], leaf))
        self.assertEqual("INCONCLUSIVE", model.classify([], [], leaf))
        for anchor in original:
            self.assertEqual("ORIGINAL_ONLY", model.classify([], [anchor], leaf))
            self.assertEqual("BOTH", model.classify(leaf, [anchor], leaf))
            self.assertEqual("BOTH", model.classify(leaf[:1], [anchor], leaf))

    def test_the_expectations_accept_exactly_what_they_name(self):
        self.assertEqual({"desktop-leaf": {"DESKTOP_LEAF_ONLY"}, "original": {"ORIGINAL_ONLY"}, "any": {"DESKTOP_LEAF_ONLY", "ORIGINAL_ONLY", "BOTH"}},
                         model.EXPECTATION_ACCEPTS)
        for expectation in model.EXPECTATION_ACCEPTS:
            self.assertFalse(model.accepts(expectation, "INCONCLUSIVE"), expectation)
        self.assertFalse(model.accepts("desktop-leaf", "BOTH"))
        self.assertTrue(model.accepts("any", "BOTH"))

    def test_the_jvm_test_class_is_the_one_the_report_names(self):
        text = jvm_text()
        self.assertTrue(text.startswith("package com.bilipai.desktop.ui\n"))
        self.assertEqual(1, text.count("class WindowsOriginalVideoRouteMountVerdictTest {"))
        self.assertEqual("com.bilipai.desktop.ui.WindowsOriginalVideoRouteMountVerdictTest", model.JVM_TEST_CLASS)
        self.assertEqual(8, len(re.findall(r"(?m)^    @Test fun ", text)))
        for name in ("Test", "assertEquals", "assertFailsWith", "assertFalse", "assertTrue"):
            self.assertIn("import kotlin.test." + name + "\n", text)

    def test_the_verdict_file_holds_no_awt_compose_or_io(self):
        code = source.mask_kotlin(verdict_text())
        self.assertEqual([], re.findall(r"(?m)^import .*", code))
        for token in (r"java\.", r"javax\.", r"androidx\.", r"kotlinx\.", r"\bWindow\b", r"\bFiles\b", r"\bThread\b", r"\bSystem\b"):
            self.assertIsNone(re.search(token, code), token)


# ---------------------------------------------------------------------------------------------------------------------
class FixtureTest(unittest.TestCase):

    def test_structure(self):
        self.assertEqual([], model.structure_problems(fixture_text()))

    def test_protocol_order_and_exit_codes(self):
        self.assertEqual([], model.protocol_problems(fixture_text()))

    def test_arguments(self):
        self.assertEqual([], model.argument_problems(fixture_text()))

    def test_receipt(self):
        self.assertEqual([], model.receipt_problems(fixture_text()))

    def test_read_only(self):
        self.assertEqual([], model.read_only_problems(fixture_text()))

    def test_observation(self):
        self.assertEqual([], model.observation_problems(fixture_text()))

    def test_the_shared_helper_still_has_every_member_the_fixture_calls(self):
        self.assertEqual([], model.helper_problems(fixture_text(), source.read(model.ACTIONS_KT)))

    def test_the_fixture_follows_the_live_windows_video_fixtures_start(self):
        live = source.read(model.LIVE_FIXTURE_KT)
        fixture = fixture_text()
        self.assertEqual([], model.live_start_problems(fixture, live))
        # Fresh guest profile, observer, worker thread, first drawn Root, not the agreement gate, health marker, the startup --video= argument.
        for token in model.LIVE_START_TOKENS:
            self.assertIn(token, live, "the live fixture lacks " + token)
            self.assertIn(token, fixture, "the route mount fixture lacks " + token)
        self.assertIn(model.LIVE_FIRST_ROOT_WAIT, live)
        self.assertIn("Windows startup still mounted the mobile agreement gate", fixture)
        self.assertIn('arrayOf("--update-health-file", health.toString(), "--update-health-token", token, "--video=$bvid")', fixture)
        self.assertIn('arrayOf("--video=$video", "--update-health-file", health.toString(), "--update-health-token", args[2])', live)
        self.assertEqual("92", dict(re.findall(r"private const val (EXIT_\w+) = (\d+)", fixture))["EXIT_MEASUREMENT_FAILED"])

    def test_the_first_root_wait_polls_like_the_live_fixture(self):
        self.assertEqual([], model.first_root_problems(fixture_text()))
        live = source.read(model.LIVE_FIXTURE_KT)
        # The live fixture's own deadline for the first drawn Root is 45 s; the wait of this fixture is not shorter.
        self.assertIn("Duration.ofSeconds(45)", live[live.index("private fun await(description: String"):][:260])

    def test_the_video_is_awaited_for_the_first_roots_handle_from_serial_zero(self):
        body = model.worker_body(fixture_text())
        self.assertEqual(1, body.count("actions.awaitVideo("))
        self.assertIn("val opened = actions.awaitVideo(0L, first.handle, bvid)", body)
        self.assertIn("check(opened.handle === first.handle)", body)

    def test_windows_starts_in_home_so_there_is_no_agreement_to_accept(self):
        # The premise the protocol stands on: nothing asks for the gate, and the shell opens the --video= route once the destination is not the gate.
        self.assertEqual([], model.startup_policy_problems())
        host = source.read(source.REPO / model.POLICY_REL)
        self.assertEqual(1, host.count("fun isRequired(): Boolean = false"))
        self.assertIn("Windows starts directly in Home", host)

    def test_the_fixture_never_calls_the_agreement_protocol_of_the_onboarding_fixture(self):
        self.assertEqual([], model.agreement_problems(fixture_text()))
        code = source.mask_kotlin(fixture_text())
        for name in ("awaitFreshAgreement", "acknowledgeThroughOriginalUi", "acceptFreshAgreementToHome"):
            self.assertNotIn(name, code)
        self.assertIsNone(re.search(r"requireAcknowledged\(\s*true\s*\)", code))
        # Every acknowledgement check of the fixture is the "nothing was persisted" one.
        self.assertEqual(3, len(re.findall(r"actions\.requireAcknowledged\(false\)", code)))
        self.assertEqual(3, len(re.findall(r"requireAcknowledged\(", code)))

    def test_the_fixture_plays_the_video_unmuted_so_its_kdoc_restricts_it_to_the_cloud_runner(self):
        live = source.read(model.LIVE_FIXTURE_KT)
        self.assertEqual([], model.audio_problems(fixture_text()))
        self.assertIn(model.LIVE_SILENT_SEED, live)
        self.assertIsNone(re.search(r"PlayerPreferences", source.mask_kotlin(fixture_text())))
        self.assertIn("Windows cloud runner only; never run on the user's machine (it plays the video unmuted).", fixture_text())

    def test_the_docstrings_and_the_kdoc_do_not_describe_the_removed_agreement_step(self):
        self.assertEqual([], model.stale_phrase_problems({"the fixture": fixture_text(), "the model docstring": model.__doc__,
                                                          "the suite docstring": __doc__}))

    def test_the_receipt_is_written_before_the_screenshot_and_before_a_missed_expectation_exits(self):
        body = model.worker_body(fixture_text())
        write, capture = body.index("Files.writeString("), body.index("actions.capture(")
        missed = body.index("exitProcess(EXIT_EXPECTATION_MISSED)")
        self.assertLess(write, capture)
        self.assertLess(capture, missed)
        self.assertLess(body.index("expectation.accepts(verdict)"), missed)
        self.assertLess(missed, body.index("completed.set(true)"))

    def test_the_kdoc_names_the_wiring_that_belongs_to_root_and_the_limits(self):
        text = fixture_text()
        for token in ("Root hunks", "desktop/build.gradle.kts", "interim Windows cloud run", "Not claimed", "has not run",
                      "positive control", "V12b", "decision D7"):
            self.assertIn(token, text, token)

    def test_the_gradle_snippet_is_the_existing_smoke_task_with_this_fixtures_name_and_class(self):
        self.assertEqual([], model.snippet_problems(fixture_text(), source.read(model.BUILD_GRADLE)))
        snippet = model.gradle_snippet(fixture_text())
        self.assertIn('tasks.register<JavaExec>("windowsOriginalVideoRouteMountSmoke") {', snippet)
        self.assertIn('mainClass.set("%s")' % model.FIXTURE_CLASS, snippet)
        # The argument order of the task is the order of the usage line: report, health, token, video.
        args = re.search(r"args\(providers\.gradleProperty\(\"(\w+)\"\).*?providers\.gradleProperty\(\"(\w+)\"\).*?\n\s*providers\.gradleProperty\(\"(\w+)\"\)"
                         r".*?providers\.gradleProperty\(\"(\w+)\"\)", snippet, re.S)
        self.assertEqual(("rootValidationReport", "rootValidationHealth", "rootValidationToken", "rootValidationVideo"), args.groups())

    def test_the_build_file_wires_the_task_only_like_the_snippet_if_it_wires_it_at_all(self):
        build = source.read(model.BUILD_GRADLE)
        wired = model.build_task(build, "windowsOriginalVideoRouteMountSmoke")
        self.assertEqual(wired is not None, model.FIXTURE_CLASS in build)
        self.assertEqual([], model.snippet_problems(fixture_text(), build))

    def test_the_fixture_class_is_the_one_the_snippet_and_the_receipt_name(self):
        self.assertEqual("com.bilipai.desktop.ui", model.FIXTURE_CLASS.rsplit(".", 1)[0])
        self.assertIn("put(\"fixture\", WindowsOriginalVideoRouteMountFixture::class.java.name)", fixture_text())


# ---------------------------------------------------------------------------------------------------------------------
class ScopeTest(unittest.TestCase):

    def test_the_three_kotlin_files_are_test_sources_in_the_ui_package(self):
        for path in SLICE_KOTLIN:
            self.assertTrue(path.is_file(), path)
            self.assertEqual("desktop/src/test/kotlin/com/bilipai/desktop/ui/" + path.name, source.relative_posix(path))
            self.assertTrue(source.read(path).startswith("package com.bilipai.desktop.ui\n"), path)

    def test_no_main_source_mentions_a_new_name(self):
        for path in sorted((source.REPO / detail.DESKTOP_MAIN).rglob("*.kt")):
            text = source.read(path)
            for name in model.NEW_NAMES:
                self.assertNotIn(name, text, "%s mentions %s" % (source.relative_posix(path), name))

    def test_each_new_top_level_name_is_declared_once_in_the_desktop_sources(self):
        declarations = {name: [] for name in model.NEW_NAMES}
        pattern = re.compile(r"(?m)^(?:internal |private |public )?(?:enum class|class|object|interface|typealias) (\w+)")
        for path in sorted((source.REPO / "desktop/src").rglob("*.kt")):
            text = source.read(path)
            if any(name in text for name in model.NEW_NAMES):
                for name in pattern.findall(text):
                    if name in declarations:
                        declarations[name].append(path.name)
        for name, files in declarations.items():
            self.assertEqual(1, len(files), (name, files))

    def test_every_project_import_of_the_slice_files_resolves(self):
        roots = {"com.android.purebilibili": source.REPO / "app/src/main/java/com/android/purebilibili",
                 "com.bilipai.desktop": source.REPO / "desktop/src/main/kotlin/com/bilipai/desktop"}
        for path in SLICE_KOTLIN:
            for imported in re.findall(r"(?m)^import ((?:com\.bilipai|com\.android\.purebilibili)\.[\w.]+)$", source.read(path)):
                package, _, name = imported.rpartition(".")
                root = next(prefix for prefix in roots if package.startswith(prefix))
                directory = roots[root] / package[len(root):].lstrip(".").replace(".", "/")
                declared = re.compile(r"(?m)\b(?:class|object|interface|typealias)\s+" + re.escape(name) + r"\b")
                self.assertTrue(any(declared.search(source.read(file)) for file in directory.glob("*.kt")), "%s imports %s" % (path.name, imported))

    def test_the_fixture_imports_nothing_from_main_beyond_the_nav_key(self):
        imports = re.findall(r"(?m)^import ((?:com\.bilipai|com\.android\.purebilibili)\.[\w.]+)$", fixture_text())
        self.assertEqual(["com.android.purebilibili.navigation3.BiliPaiNavKey"], imports)

    def test_the_slice_adds_python_beside_the_other_ux_suites(self):
        for path in SLICE_PYTHON:
            self.assertEqual(Path(source.HERE), path.parent)


# ---------------------------------------------------------------------------------------------------------------------
class ScannerTest(unittest.TestCase):

    def contents(self, text):
        return [content for _, _, content in model.string_literals(text)]

    def test_a_word_in_a_comment_is_not_a_literal(self):
        text = 'val a = "real" // "line"\n/* "block" /* "nested" */ "still block" */ val b = "after"\n/** "doc" */\n'
        self.assertEqual(["real", "after"], self.contents(text))

    def test_escapes_templates_raw_strings_and_char_literals(self):
        text = 'val a = "q\\"uote" + "${x + "inner"}" + """raw "x" """ + \'"\' + "tail"\n'
        self.assertEqual(['q\\"uote', "${x + \"inner\"}", "tail"], self.contents(text))

    def test_a_url_in_a_string_is_not_a_comment(self):
        self.assertEqual(["http://example.com/a", "next"], self.contents('val u = "http://example.com/a"; val n = "next"\n'))

    def test_offsets_point_at_the_quotes(self):
        text = 'x = "ab"'
        (opening, closing, content), = model.string_literals(text)
        self.assertEqual(('"', '"', "ab"), (text[opening], text[closing], content))

    def test_the_string_list_parser_reads_a_list_of_strings_and_nothing_else(self):
        text = 'object A {\n    val L: List<String> = listOf("x", "y z",\n        "w",\n    )\n    val M: List<String> = listOf("m")\n}\n'
        self.assertEqual(["x", "y z", "w"], model.string_list(text, "L"))
        self.assertEqual(["m"], model.string_list(text, "M"))
        self.assertIsNone(model.string_list(text, "N"))
        self.assertIsNone(model.string_list('// val L: List<String> = listOf("x")\n', "L"))

    def test_the_constants_parser(self):
        self.assertEqual({"A": "a b", "C": "c"}, model.constants('const val A = "a b"\n    const val C = "c" // x\nval D = "d"\n'))

    def test_the_bar_condition_walk_on_synthetic_text(self):
        bar = "\n".join([
            "                Row(Modifier.fillMaxWidth().heightIn(min = DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP.dp),",
            "                    verticalAlignment = Alignment.CenterVertically) {",
            "                    DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.ALWAYS, onClick = a) {",
            "                    }",
            "                    if (expanded) {",
            "                        DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.SOMETIMES, onClick = b) {",
            "                        }",
            "                    }",
            "                    if (expanded) DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.INLINE, onClick = c) {",
            "                    }",
            "                    Box {",
            "                        DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.BOXED, onClick = d) {",
            "                        }",
            "                    }",
            "                }",
        ])
        self.assertTrue(model.unconditional_in_bar(bar, "ALWAYS"))
        self.assertFalse(model.unconditional_in_bar(bar, "SOMETIMES"))
        self.assertFalse(model.unconditional_in_bar(bar, "INLINE"))
        self.assertTrue(model.unconditional_in_bar(bar, "BOXED"))
        self.assertFalse(model.unconditional_in_bar(bar, "MISSING"))

    def test_the_gradle_snippet_and_task_extractors(self):
        kdoc = "/**\n * intro\n * task would read as the following, modelled on x:\n *\n *     tasks.register<JavaExec>(\"t\") {\n *         group = \"g\"\n *     }\n *\n * Not claimed.\n */\n"
        self.assertEqual('tasks.register<JavaExec>("t") {\n    group = "g"\n}', model.gradle_snippet(kdoc))
        self.assertIsNone(model.gradle_snippet("/** nothing */"))
        build = 'a\ntasks.register<JavaExec>("t") {\n    x { y }\n}\nb\n'
        self.assertEqual('tasks.register<JavaExec>("t") {\n    x { y }\n}', model.build_task(build, "t"))
        self.assertIsNone(model.build_task(build, "u"))


# ---------------------------------------------------------------------------------------------------------------------
class MutationTest(unittest.TestCase):
    """Each check must fail on an edited copy. The edits are in memory."""

    def assertFails(self, problems, expected=None):
        self.assertTrue(problems, "the check passed on an edited copy")
        if expected is not None:
            self.assertTrue(any(expected in problem for problem in problems), (expected, problems))

    # --- the fixture text
    def test_a_route_push_is_refused(self):
        text = replaced(fixture_text(), "val main = mainWindow()", "frame.routes.push(null)\n        val main = mainWindow()")
        self.assertFails(model.read_only_problems(text), "push")

    def test_a_second_input_helper_is_refused(self):
        text = replaced(fixture_text(), "val measurement = measure(opened)", "val measurement = measure(opened)\n                    actions.disagree(opened, false)")
        self.assertFails(model.read_only_problems(text), "actions members")
        self.assertFails(model.agreement_problems(text), "disagree")

    def test_reinserting_the_agreement_steps_is_refused(self):
        # The first draft started like the onboarding fixture, which waits 25 s for a gate that Windows never mounts.
        for call, name in (("actions.awaitFreshAgreement()", "awaitFreshAgreement"),
                           ("actions.acknowledgeThroughOriginalUi(first)", "acknowledgeThroughOriginalUi"),
                           ("actions.acceptFreshAgreementToHome(health)", "acceptFreshAgreementToHome"),
                           ("actions.clickClause(first, UserAgreementClause.entries.first())", "clickClause"),
                           ("actions.requireAcknowledged(true)", "requireAcknowledged")):
            text = replaced(fixture_text(), "val first = awaitFirstRoot(latest)", call + "\n                    val first = awaitFirstRoot(latest)")
            self.assertFails(model.agreement_problems(text), name)
        for call in ("actions.awaitFreshAgreement()", "actions.acknowledgeThroughOriginalUi(first)", "actions.acceptFreshAgreementToHome(health)"):
            text = replaced(fixture_text(), "val first = awaitFirstRoot(latest)", call + "\n                    val first = awaitFirstRoot(latest)")
            self.assertFails(model.read_only_problems(text), "actions members")

    def test_expecting_the_agreement_gate_instead_of_refusing_it_is_refused(self):
        text = replaced(fixture_text(), model.GATE_CHECK, 'check(first.key == BiliPaiNavKey.Onboarding) { "The agreement gate did not mount" }')
        self.assertFails(model.protocol_problems(text), "Windows startup still mounted")
        self.assertFails(model.agreement_problems(text), "exactly once")
        self.assertFails(model.live_start_problems(text, source.read(model.LIVE_FIXTURE_KT)), "the route mount fixture lacks")

    def test_an_acknowledgement_that_is_required_instead_of_refused_is_refused(self):
        text = replaced(fixture_text(), "val measurement = measure(opened)\n                    actions.requireAcknowledged(false)",
                        "val measurement = measure(opened)\n                    actions.requireAcknowledged(true)")
        self.assertFails(model.agreement_problems(text), "requireAcknowledged")
        self.assertFails(model.protocol_problems(text), "actions.requireAcknowledged(false)")

    def test_a_preference_or_pointer_input_is_refused(self):
        for token in ("changePreferences(x)", "java.awt.Robot()", "window.dispatchEvent(e)", "System.setProperty(\"a\", \"b\")", "frame.handle.navigation.requestBack()",
                      "PlayerPreferencesStore().save(PlayerPreferences(volume = 0.0, muted = true))"):
            text = replaced(fixture_text(), "val main = mainWindow()", token + "\n        val main = mainWindow()")
            self.assertFails(model.read_only_problems(text))

    def test_a_write_other_than_the_receipt_is_refused(self):
        text = replaced(fixture_text(), "report.resolve(RECEIPT_NAME)", 'report.resolve("other.json")')
        self.assertFails(model.read_only_problems(text), "writes something")
        text = replaced(fixture_text(), "Files.exists(health)", "Files.exists(health) && Files.deleteIfExists(health)")
        self.assertFails(model.read_only_problems(text))

    def test_moving_the_screenshot_before_the_receipt_breaks_the_protocol(self):
        text = fixture_text()
        text = replaced(text, 'actions.capture("200-v12a-video-route", last)', "")
        text = replaced(text, "val record = receipt(", 'actions.capture("200-v12a-video-route", requireNotNull(latest.get()))\n                    val record = receipt(')
        self.assertFails(model.protocol_problems(text), "actions.capture(")

    def test_dropping_a_step_or_an_exit_code_breaks_the_protocol(self):
        for old, new, expected in (
                ("val first = awaitFirstRoot(latest)", "val first = requireNotNull(latest.get())", "val first = awaitFirstRoot(latest)"),
                (model.GATE_CHECK, "", "Windows startup still mounted"),
                ("actions.awaitActualHealth(health)", "", "actions.awaitActualHealth(health)"),
                ("awaitVideo(0L, first.handle, bvid)", "awaitVideo(first.serial, first.handle, bvid)", "awaitVideo(0L, first.handle, bvid)"),
                ("check(opened.handle === first.handle)", "check(true)", "check(opened.handle === first.handle)"),
                ("val measurement = measure(opened)\n                    actions.requireAcknowledged(false)", "val measurement = measure(opened)",
                 "actions.requireAcknowledged(false)"),
                ("worker.start()", "", "worker.start()")):
            self.assertFails(model.protocol_problems(replaced(fixture_text(), old, new)), expected)
        self.assertFails(model.protocol_problems(replaced(fixture_text(), "EXIT_EXPECTATION_MISSED = 93", "EXIT_EXPECTATION_MISSED = 94")), "exit codes")
        self.assertFails(model.protocol_problems(replaced(fixture_text(), "exitProcess(EXIT_MEASUREMENT_FAILED)", "exitProcess(0)")), "EXIT_MEASUREMENT_FAILED")
        self.assertFails(model.protocol_problems(replaced(fixture_text(), '"--video=$bvid"', '"--video=BV0000000000"')), "--video")

    def test_a_changed_receipt_is_refused(self):
        self.assertFails(model.receipt_problems(replaced(fixture_text(), 'put("realAccountUsed", false)', 'put("realAccountUsed", true)')), "realAccountUsed")
        self.assertFails(model.receipt_problems(replaced(fixture_text(), 'put("samples",', 'put("sampleCount",')), "receipt keys")
        self.assertFails(model.receipt_problems(replaced(fixture_text(), 'put("slice", "V12a")', 'put("slice", "V12b")')), "slice")

    def test_a_changed_usage_or_argument_parse_is_refused(self):
        self.assertFails(model.argument_problems(replaced(fixture_text(), "[desktop-leaf|original|any]", "[any]")), "usage")
        self.assertFails(model.argument_problems(replaced(fixture_text(), "require(args.size in 4..5)", "require(args.size >= 4)")), "argument count")
        self.assertFails(model.argument_problems(replaced(fixture_text(), "?: OriginalVideoRouteExpectation.DESKTOP_LEAF.argument", "?: \"any\"")))

    def test_sampling_only_the_main_window_is_refused(self):
        text = replaced(fixture_text(), "Window.getWindows().filter { it.isShowing && ownedBy(main, it) }", "listOf<Window>(main)")
        self.assertFails(model.observation_problems(text), "getWindows")
        self.assertFails(model.observation_problems(replaced(fixture_text(), "check(opened.key is BiliPaiNavKey.VideoDetail)", "check(true)")))

    def test_a_second_main_or_a_missing_object_is_refused(self):
        self.assertFails(model.structure_problems(replaced(fixture_text(), "package com.bilipai.desktop.ui", "package com.bilipai.desktop")), "package")
        self.assertFails(model.structure_problems(replaced(fixture_text(), "com.bilipai.desktop.main(mainArgs)", "")), "Main")
        self.assertFails(model.structure_problems(replaced(fixture_text(), "object WindowsOriginalVideoRouteMountFixture {", "private object WindowsOriginalVideoRouteMountFixture {")))

    def test_a_drifting_kdoc_snippet_is_refused(self):
        text = replaced(fixture_text(), 'mainClass.set("%s")' % model.FIXTURE_CLASS, 'mainClass.set("com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture")')
        self.assertFails(model.snippet_problems(text, source.read(model.BUILD_GRADLE)), "differs")
        self.assertFails(model.snippet_problems("/** no snippet */", source.read(model.BUILD_GRADLE)), "no Gradle task snippet")

    def test_a_build_file_that_wires_the_task_differently_is_refused(self):
        build = source.read(model.BUILD_GRADLE)
        wired = build + '\ntasks.register<JavaExec>("windowsOriginalVideoRouteMountSmoke") {\n    group = "other"\n}\n'
        self.assertFails(model.snippet_problems(fixture_text(), wired), "registers the task differently")
        exact = build + "\n" + model.gradle_snippet(fixture_text()) + "\n"
        self.assertEqual([], model.snippet_problems(fixture_text(), exact))

    def test_a_helper_that_changed_is_refused(self):
        actions = source.read(model.ACTIONS_KT)
        self.assertFails(model.helper_problems(fixture_text(), replaced(actions, "    fun awaitVideo(", "    private fun awaitVideo(")), "awaitVideo")
        self.assertFails(model.helper_problems(fixture_text(), replaced(actions, "private val token: String,", "private val secret: String,")), "constructor")

    def test_a_first_root_wait_that_does_not_poll_or_fail_or_is_shorter_than_the_live_one_is_refused(self):
        text = fixture_text()
        self.assertFails(model.first_root_problems(replaced(text, "Thread.sleep(100)", "")), "Thread.sleep(100)")
        self.assertFails(model.first_root_problems(replaced(text, "FIRST_ROOT_SECONDS = 45L", "FIRST_ROOT_SECONDS = 25L")), "45 seconds")
        self.assertFails(model.first_root_problems(replaced(text, 'error("The actual Main drew no Root within $FIRST_ROOT_SECONDS seconds")',
                                                            "return requireNotNull(latest.get())")), "error(")
        self.assertFails(model.first_root_problems(replaced(text, "private fun awaitFirstRoot(", "private fun awaitFirstRoot2(")), "awaitFirstRoot is missing")
        self.assertFails(model.first_root_problems(replaced(text, "latest: AtomicReference<DesktopOriginalRootValidationTap.Frame?>", "latest: Any")), "signature")

    def test_a_fixture_that_does_not_say_it_is_for_the_cloud_runner_is_refused(self):
        self.assertFails(model.audio_problems(replaced(fixture_text(), model.AUDIO_WARNING, "")), "KDoc lacks")
        late = replaced(fixture_text(), model.AUDIO_WARNING, "").replace("object WindowsOriginalVideoRouteMountFixture {",
                                                                          "object WindowsOriginalVideoRouteMountFixture {\n    // " + model.AUDIO_WARNING, 1)
        self.assertFails(model.audio_problems(late), "above the object")

    def test_wording_of_the_removed_agreement_step_is_refused(self):
        for phrase in model.STALE_PHRASES:
            self.assertFails(model.stale_phrase_problems({"a doc": "it " + phrase + " here"}), phrase)
        self.assertEqual([], model.stale_phrase_problems({"a doc": "Windows starts directly in Home"}))

    def test_a_start_that_differs_from_the_live_fixtures_is_refused(self):
        live = source.read(model.LIVE_FIXTURE_KT)
        self.assertFails(model.live_start_problems(fixture_text(), replaced(live, model.GATE_CHECK, "")), "the live Windows video fixture lacks")
        self.assertFails(model.live_start_problems(fixture_text(), live.replace(model.LIVE_SILENT_SEED, "")), "silent profile")
        self.assertFails(model.live_start_problems(fixture_text(), replaced(live, model.LIVE_FIRST_ROOT_WAIT, "")), "first drawn Root")
        early = replaced(fixture_text(), "val first = awaitFirstRoot(latest)", "actions.awaitActualHealth(health)\n                    val first = awaitFirstRoot(latest)")
        self.assertFails(model.live_start_problems(early, live), "before it waits for the health marker")
        self.assertFails(model.live_start_problems(replaced(fixture_text(), '"--video=$bvid"', '"--v=$bvid"'), live), '"--video=$')

    # --- the verdict
    def test_a_changed_truth_table_is_refused(self):
        text, jvm = verdict_text(), jvm_text()
        swapped = replaced(text, "original -> OriginalVideoRouteVerdict.BOTH", "original -> OriginalVideoRouteVerdict.ORIGINAL_ONLY")
        self.assertFails(model.verdict_problems(swapped, jvm), "classify branches")
        weaker = replaced(text, "val leafComplete = leafSeen.containsAll(LEAF_ANCHORS)", "val leafComplete = leafSeen.isNotEmpty()")
        self.assertFails(model.verdict_problems(weaker, jvm), "classify declares")
        reordered = replaced(text, "            leafComplete && !original -> OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY\n            original && leafSeen.isEmpty() -> OriginalVideoRouteVerdict.ORIGINAL_ONLY\n",
                             "            original && leafSeen.isEmpty() -> OriginalVideoRouteVerdict.ORIGINAL_ONLY\n            leafComplete && !original -> OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY\n")
        self.assertFails(model.verdict_problems(reordered, jvm), "classify branches")

    def test_a_changed_expectation_is_refused(self):
        text, jvm = verdict_text(), jvm_text()
        self.assertFails(model.verdict_problems(replaced(text, "ANY -> verdict != OriginalVideoRouteVerdict.INCONCLUSIVE", "ANY -> true"), jvm), "accepts")
        self.assertFails(model.verdict_problems(replaced(text, 'ORIGINAL("original")', 'ORIGINAL("classic")'), jvm), "expectation arguments")
        self.assertFails(model.verdict_problems(replaced(text, "DESKTOP_LEAF -> verdict == OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY",
                                                         "DESKTOP_LEAF -> verdict != OriginalVideoRouteVerdict.INCONCLUSIVE"), jvm), "accepts")

    def test_a_jvm_case_that_disagrees_with_the_model_is_refused(self):
        jvm = replaced(jvm_text(), "Case(leafSeen = 1, originalSeen = 0, verdict = OriginalVideoRouteVerdict.INCONCLUSIVE)",
                       "Case(leafSeen = 1, originalSeen = 0, verdict = OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY)")
        self.assertFails(model.verdict_problems(verdict_text(), jvm), "case (1, 0)")
        self.assertFails(model.verdict_problems(verdict_text(), replaced(jvm_text(), "Case(leafSeen = 0, originalSeen = 0,", "Case(leafSeen = 0, originalSeen = 0, //")), "cases")

    # --- the anchors
    def test_an_original_anchor_that_the_desktop_also_spells_is_refused(self):
        leaf, original = model.anchor_tables()
        for ambiguous in ("返回", "分享", "弹幕设置", "关闭"):
            self.assertFails(model.original_anchor_problems(original + [ambiguous], leaf), ambiguous)

    def test_an_original_anchor_that_is_only_in_a_comment_or_never_a_name_is_refused(self):
        leaf, original = model.anchor_tables()
        self.assertFails(model.original_anchor_problems(original + ["UP投稿"], leaf), "UP投稿")
        self.assertFails(model.original_anchor_problems(original + ["UP主名称"], leaf), "never spelled")
        self.assertFails(model.original_anchor_problems(original + ["no such name"], leaf), "no such name")
        self.assertFails(model.original_anchor_problems(original + [original[0]], leaf), "duplicate")
        self.assertFails(model.original_anchor_problems(original + ["详情"], leaf + ["详情"]), "both a leaf and an original")

    def test_a_leaf_anchor_that_the_original_also_spells_or_that_the_bar_conditions_is_refused(self):
        leaf, original = model.anchor_tables()
        self.assertFails(model.leaf_anchor_problems(leaf + ["全屏"], original), "全屏")
        self.assertFails(model.leaf_anchor_problems([], original), "no leaf anchor")
        self.assertFails(model.leaf_anchor_problems(leaf + [original[0]], original), original[0])
        conditional = replaced(source.read(model.BAR_KT), "DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.DETAILS,",
                               "if (expanded) DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.DETAILS,")
        self.assertFails(model.leaf_anchor_problems(leaf, original, bar_text=conditional), "condition")
        retitled = replaced(source.read(model.STRINGS_KT), 'const val DETAILS = "详情"', 'const val DETAILS = "Details"')
        self.assertFails(model.leaf_anchor_problems(leaf, original, strings_text=retitled), "详情")
        hidden = replaced(source.read(model.LEAF_KT), "if (chromeVisible && legacyChrome) legacyControlBar()", "if (legacyChrome) legacyControlBar()")
        self.assertFails(model.leaf_anchor_problems(leaf, original, leaf_text=hidden), "LEGACY control bar")

    # --- the premise
    def test_a_shell_that_routes_a_video_to_the_original_leaf_breaks_the_premise(self):
        universe, _, _ = model.graph()
        shell = universe.texts[detail.SHELL_REL]
        mutated = replaced(shell, "DesktopWindowsVideoPhysicalLeaf(entryKey, ordinaryVideo,", "DesktopOriginalVideoPhysicalLeaf(entryKey, ordinaryVideo,")
        problems = model.premise_problems(universe.replace(detail.SHELL_REL, mutated))
        self.assertFails(problems, "route fact is false")

    def test_a_windows_startup_that_asks_for_the_agreement_gate_breaks_the_startup_premise(self):
        universe, _, _ = model.graph()
        codes = {rel: universe.code[rel] for rel in model.desktop_main_files()}
        self.assertEqual([], model.startup_policy_problems(codes))
        required = dict(codes, **{model.POLICY_REL: replaced(codes[model.POLICY_REL], "fun isRequired(): Boolean = false", "fun isRequired(): Boolean = true")})
        self.assertFails(model.startup_policy_problems(required), "isRequired()")
        gate_elsewhere = dict(codes, **{"desktop/src/main/kotlin/com/bilipai/desktop/Elsewhere.kt":
                                        "val s = resolveInitialBiliPaiBackStack(firstRoute = r, onboardingRequired = true, openPortraitFeedOnStartup = false)\n"})
        self.assertFails(model.startup_policy_problems(gate_elsewhere), "onboardingRequired")
        self.assertFails(model.startup_policy_problems({rel: code for rel, code in codes.items() if rel != model.POLICY_REL}), "is gone")

    def test_a_shell_or_main_that_no_longer_opens_the_startup_video_breaks_the_startup_premise(self):
        universe, _, _ = model.graph()
        codes = {rel: universe.code[rel] for rel in model.desktop_main_files()}
        shell = replaced(codes[detail.SHELL_REL], "if(destination != BiliPaiNavKey.Onboarding && !initialVideoConsumed", "if(!initialVideoConsumed")
        self.assertFails(model.startup_policy_problems(dict(codes, **{detail.SHELL_REL: shell})), "pushes the startup video")
        main_text = source.read(model.MAIN_KT)
        self.assertEqual([], model.startup_policy_problems(codes, main_text))
        self.assertFails(model.startup_policy_problems(codes, replaced(main_text, 'startsWith("--video=")', 'startsWith("--vid=")')), "--video=")


# ---------------------------------------------------------------------------------------------------------------------
class HygieneTest(unittest.TestCase):

    def test_every_file_of_the_slice_is_lf_only_without_control_characters(self):
        source.assert_lf_only(self, *SLICE_KOTLIN, *SLICE_PYTHON)

    def test_every_file_is_utf8_without_a_bom_and_ends_with_one_newline(self):
        for path in SLICE_KOTLIN + SLICE_PYTHON:
            data = path.read_bytes()
            data.decode("utf-8")
            self.assertFalse(data.startswith(b"\xef\xbb\xbf"), path.name)
            self.assertTrue(data.endswith(b"\n") and not data.endswith(b"\n\n"), path.name)

    def test_no_kotlin_comment_of_the_slice_opens_a_nested_block_by_accident(self):
        for path in SLICE_KOTLIN:
            text = source.read(path)
            self.assertEqual(text.count("/*"), text.count("*/"), path.name)

    def test_the_python_files_have_no_trailing_whitespace_and_no_tabs(self):
        for path in SLICE_PYTHON:
            for number, line in enumerate(source.read(path).split("\n"), 1):
                self.assertEqual(line.rstrip(), line, "%s:%d" % (path.name, number))
                self.assertNotIn("\t", line, "%s:%d" % (path.name, number))


if __name__ == "__main__":
    unittest.main()
