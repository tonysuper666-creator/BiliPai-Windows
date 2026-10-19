"""The XP API probe of the desktop UX program (slice XP, spec 9.2 and 10.2). No JVM, no network.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_api_probe_source.py

What is proved. desktop/src/test/kotlin/com/bilipai/desktop/ux/UxApiProbeTest.kt is a compile-only unit that
references every uncertain API symbol of the UX program. This suite checks the probe against the evidence that exists
without a compiler:

  * the probe file itself: ASCII, LF, a header that names it throwaway and Root as its only deleter, one trivial test,
    one `// probe: <key>` section per row of ux_api_probe.SYMBOLS and the Kotlin text each row requires;
  * the pinned jars (zipfile only): every referenced class exists, every member exists as a public method with the
    exact JVM descriptor (value-class mangling aware), the abstract members of the two interfaces the probe
    implements (v2.ScrollbarAdapter, PopupPositionProvider) are exactly the ones it overrides, the opt-in markers a
    symbol carries are exactly the ones the probe declares, and every import of the probe resolves;
  * the vendored Miuix sources (TooltipBox, rememberLayerBackdrop, ...): parameter names and defaults against the
    probe's calls;
  * the real generated text, produced by running the real generators into a temporary directory: PlayerControlGlassDock
    and its host are internal members of com.android.purebilibili.feature.video.ui.overlay with the probed
    parameters; the AppDropdownMenu and AppDropdownMenuItem call sites of the generated VideoCard use exactly the
    argument names, and the trailing ColumnScope lambda, that the desktop-owned stand-ins accept;
  * the JDK API the probe uses, from lib/ct.sym.

What is not proved. That the probe compiles. The first Linux compile is the proof; this suite only removes the errors
that can be found without one. Jars are found by ux_api_probe.candidate_roots (BILIPAI_UX_JAR_DIRS first). When none
are reachable the jar suites error out; setting BILIPAI_UX_JARS_OPTIONAL=1 turns that into a visible skip for
environments that never have them, and the source-only suites still run.

Throwaway. This suite, ux_api_probe.py and the probe die together when Root deletes the probe.
"""
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_api_probe as probe  # noqa: E402
import ux_source as source  # noqa: E402

REPO = probe.REPO
JARS_OPTIONAL = os.environ.get("BILIPAI_UX_JARS_OPTIONAL") == "1"
PROBE_TEXT = probe.read_text(probe.PROBE)
PROBE_CODE = probe.mask_kotlin(PROBE_TEXT)
GENERATED = {}


def squash(text):
    """Whitespace-insensitive form for needle matching."""
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\( ", "(", text)
    text = re.sub(r" \)", ")", text)
    return re.sub(r" \.", ".", text)


def run_generator(args):
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1")
    done = subprocess.run([sys.executable, "-X", "utf8", "-B"] + args, cwd=str(REPO / "desktop"), env=environment,
                          capture_output=True, text=True, timeout=900)
    if done.returncode != 0:
        raise AssertionError("generator failed (%d): %s\n%s" % (done.returncode, args[0], (done.stdout + done.stderr)[-2000:]))


def status():
    return subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(REPO), "status", "--porcelain", "--untracked-files=all"],
                          capture_output=True, text=True, timeout=120).stdout


def setUpModule():
    """Run the real generators once, into a temporary directory (never into the checkout)."""
    GENERATED["status"] = status()
    root = Path(tempfile.mkdtemp(prefix="xp"))
    GENERATED["root"] = root
    tools = REPO / "desktop/tools"
    run_generator([str(tools / "extract-upstream-video-player-full-controls.py"), str(REPO), str(root / "player"),
                   "--test-output", str(root / "player-tests")])
    run_generator([str(tools / "extract-upstream-home-full-card.py"), "--repo", str(REPO), "--output", str(root / "home-full-card")])
    run_generator([str(tools / "extract-upstream-home-core-refresh.py"), "--repo", str(REPO), "--output", str(root / "home-page"),
                   "--test-output", str(root / "home-page-tests")])
    run_generator([str(tools / "extract-upstream-components.py"), "--repo", str(REPO), "--output", str(root / "components"),
                   "--test-output", str(root / "components-tests")])
    GENERATED["player"] = root / "player"
    GENERATED["video_card"] = root / "home-full-card/generated/com/android/purebilibili/feature/home/components/cards/VideoCard.kt"
    GENERATED["roots"] = [root / "player", root / "home-full-card/generated", root / "home-page", root / "components"]


def tearDownModule():
    shutil.rmtree(GENERATED.get("root", ""), ignore_errors=True)


def declares(text, name):
    return re.search(r"\b(?:fun|val|var|class|interface|object|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?%s\b" % re.escape(name),
                     probe.mask_kotlin(text)) is not None


def project_declaration(import_name):
    """The file that declares a com.android.purebilibili.* or com.bilipai.desktop.* import, or None."""
    package, _, name = import_name.rpartition(".")
    roots = list(GENERATED["roots"]) + [REPO / "desktop/src/main/kotlin", REPO / "desktop/src/test/kotlin"]
    for root in roots:
        directory = Path(root) / package.replace(".", "/")
        if directory.is_dir():
            for path in sorted(directory.glob("*.kt")):
                if declares(probe.read_text(path), name):
                    return path
    return None


class JarCase(unittest.TestCase):
    """Base of the suites that read the pinned jars."""
    jars = None

    @classmethod
    def setUpClass(cls):
        found, missing = probe.find_jars()
        if missing:
            message = "pinned jars not found: %s (searched %s)" % (missing, [str(r) for r in probe.candidate_roots()])
            if JARS_OPTIONAL:
                raise unittest.SkipTest(message)
            raise AssertionError(message)
        cls.found = found
        cls.jars = probe.JarSet([jar for family in found.values() for jar in family])


class ProbeFileTest(unittest.TestCase):
    def test_the_probe_is_ascii_lf_and_in_the_ux_test_package(self):
        source.assert_lf_only(self, probe.PROBE, Path(probe.__file__), Path(__file__))
        self.assertTrue(PROBE_TEXT.isascii())
        self.assertTrue(probe.read_text(__file__).isascii())
        self.assertTrue(probe.read_text(probe.__file__).isascii())
        self.assertEqual("desktop/src/test/kotlin/com/bilipai/desktop/ux/UxApiProbeTest.kt", probe.PROBE.relative_to(REPO).as_posix())
        self.assertEqual("package com.bilipai.desktop.ux", PROBE_TEXT.splitlines()[0])

    def test_the_header_says_throwaway_and_that_only_root_deletes_it(self):
        header = PROBE_TEXT[PROBE_TEXT.index("/*"):PROBE_TEXT.index("*/")]
        for phrase in ("THROWAWAY", "Only Root may delete it", "test_ux_api_probe_source.py", "ux_api_probe.py",
                       "first Linux compile", "Nothing in this file runs"):
            self.assertIn(phrase, header)

    def test_the_only_test_is_trivially_true(self):
        self.assertEqual(1, len(re.findall(r"@Test\b", PROBE_CODE)))
        body = re.search(r"class UxApiProbeTest \{(.*?)\n\}\n", PROBE_CODE, re.S).group(1)
        self.assertEqual("assertTrue(true)", squash(body).split("{", 1)[1].rsplit("}", 1)[0].strip())
        self.assertIn("import org.junit.jupiter.api.Test", PROBE_CODE)
        self.assertIn("import kotlin.test.assertTrue", PROBE_CODE)

    def test_no_other_source_of_the_ux_test_package_depends_on_the_probe(self):
        marker = re.compile(r"UxApiProbe|DesktopCardContextMenu|Probe[A-Z]\w*")
        for path in sorted((probe.PROBE.parent).glob("*.kt")):
            if path != probe.PROBE:
                self.assertIsNone(marker.search(probe.read_text(path)), path.name)

    def test_every_symbol_has_exactly_one_probe_section_and_its_kotlin_text(self):
        tags = probe.probe_tags(PROBE_TEXT)
        self.assertEqual(sorted(tags), sorted(set(tags)), "a probe key is used twice")
        self.assertEqual({symbol["key"] for symbol in probe.SYMBOLS}, set(tags))
        code = squash(PROBE_CODE)
        for symbol in probe.SYMBOLS:
            for needle in symbol["needles"]:
                self.assertIn(squash(needle), code, "%s: %s" % (symbol["key"], needle))

    def test_opt_in_markers_are_declared_only_for_markers_a_symbol_carries(self):
        declared = set(re.findall(r"@OptIn\(([^)]*)\)", PROBE_CODE))
        names = {item.strip().split("::")[0] for group in declared for item in group.split(",")}
        expected = {probe.OPT_IN_SIMPLE_NAMES[marker] for symbol in probe.SYMBOLS for marker in symbol["opt_in"]}
        self.assertEqual(expected, names)

    def test_imports_are_unique_and_the_only_aliases_are_the_known_ones(self):
        imports = probe.probe_imports(PROBE_CODE)
        names = [name for name, _alias in imports]
        self.assertEqual(sorted(names), sorted(set(names)))
        aliases = {alias for _name, alias in imports if alias}
        self.assertEqual({"MiuixPlainTooltip", "MiuixTooltipBox", "MiuixTooltipDefaults", "rememberMiuixTooltipState",
                          "AppDropdownMenu", "AppDropdownMenuItem"}, aliases)
        self.assertNotIn("import top.yukonga.miuix.kmp.basic.PopupPositionProvider", PROBE_CODE)
        self.assertNotIn("import androidx.compose.ui.window.Window", PROBE_CODE)

    def test_the_generators_left_the_checkout_untouched(self):
        self.assertEqual(GENERATED["status"], status())


class ParserSelfTest(unittest.TestCase):
    """The readers find what they must on small samples (a synthetic class file and Kotlin snippets)."""

    @staticmethod
    def build_class():
        pool, index = [], {}

        def add(entry, key):
            if key not in index:
                pool.append(entry)
                index[key] = len(pool)
            return index[key]

        def utf8(text):
            data = text.encode("utf-8")
            return add(b"\x01" + struct.pack(">H", len(data)) + data, ("u", text))

        def klass(name):
            return add(b"\x07" + struct.pack(">H", utf8(name)), ("c", name))

        number = add(b"\x03" + struct.pack(">i", 7), ("i", 7))
        add(b"\x05" + struct.pack(">q", 9), ("l", 9))
        pool.append(b"")  # the second slot a long or double occupies; written as nothing
        this, parent, iface = klass("a/B"), klass("java/lang/Object"), klass("a/I")

        def annotation(type_name, with_values=False):
            body = struct.pack(">HH", utf8(type_name), 1 if with_values else 0)
            if with_values:
                # one pair: name -> array of two ints, then nothing else
                body += struct.pack(">H", utf8("v")) + b"[" + struct.pack(">H", 2) + b"I" + struct.pack(">H", number) + b"I" + struct.pack(">H", number)
            return body

        def attribute(name, blob):
            return struct.pack(">HI", utf8(name), len(blob)) + blob

        def method(access, name, desc, annotations=()):
            attrs = b""
            if annotations:
                blob = struct.pack(">H", len(annotations)) + b"".join(annotation(a, True) for a in annotations)
                attrs = attribute("RuntimeInvisibleAnnotations", blob)
            return struct.pack(">HHHH", access, utf8(name), utf8(desc), 1 if attrs else 0) + attrs

        methods = [method(0x0401, "scrollTo", "(D)V"), method(0x0019, "isBackPressed-aHzCx-E", "(I)Z", ["La/Experimental;"]),
                   method(0x1001, "bridge", "()V")]
        class_attrs = attribute("RuntimeVisibleAnnotations", struct.pack(">H", 1) + annotation("La/Marker;"))
        body = (struct.pack(">HHH", 0x0601, this, parent) + struct.pack(">HH", 1, iface) + struct.pack(">H", 0)
                + struct.pack(">H", len(methods)) + b"".join(methods) + struct.pack(">H", 1) + class_attrs)
        pool_bytes = b"".join(pool)
        return b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 65) + struct.pack(">H", len(pool) + 1) + pool_bytes + body

    def test_class_file_reader_reads_header_members_and_annotations(self):
        cls = probe.ClassFile(self.build_class())
        self.assertEqual("a/B", cls.name)
        self.assertEqual("java/lang/Object", cls.super_name)
        self.assertEqual(("a/I",), cls.interfaces)
        self.assertTrue(cls.is_interface)
        self.assertEqual(("La/Marker;",), cls.annotations)
        self.assertEqual({"scrollTo(D)V"}, cls.abstract_methods())
        found = cls.find("isBackPressed-", "(I)Z")
        self.assertEqual(["isBackPressed-aHzCx-E"], [m.name for m in found])
        self.assertEqual(("La/Experimental;",), found[0].annotations)
        self.assertEqual([], cls.find("isBackPressed-", "(Z)Z"))
        self.assertEqual([], cls.find("isBackPressed", "(I)Z"))
        self.assertIn("isBackPressed-aHzCx-E", cls.utf8)

    def test_function_signatures_read_names_defaults_and_lambda_types(self):
        text = ("@Composable\nfun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this\n"
                "fun TooltipBox(positionProvider: PopupPositionProvider, tooltip: @Composable TooltipScope.() -> Unit,\n"
                "    modifier: Modifier = Modifier, focusable: Boolean = false, content: @Composable () -> Unit) {}\n"
                "// fun TooltipBox(ignored: Int)\nval s = \"fun TooltipBox(ignored: Int)\"\n")
        self.assertEqual([[("backdrop", False)]], probe.function_signatures(text, "layerBackdrop"))
        self.assertEqual([[("positionProvider", False), ("tooltip", False), ("modifier", True), ("focusable", True),
                           ("content", False)]], probe.function_signatures(text, "TooltipBox"))

    def test_calls_read_named_arguments_positional_count_and_trailing_lambda(self):
        text = ("fun f() {\n  Box(a = 1, b = { c = 2 }, 3) { x }\n  Box(\n    a = if (p == q) 1 else 2,\n  )\n"
                "  fun Box(z: Int) {}\n  // Box(q = 1)\n}\n")
        self.assertEqual([(("a", "b"), 1, True), (("a",), 0, False)], probe.calls(text, "Box"))

    def test_split_top_level_keeps_nested_generics_arrows_and_calls_together(self):
        self.assertEqual(["a: Map<String, Int>", "b: (Int, Int) -> Unit", "c: Int = f(1, 2)"],
                         probe.split_top_level("a: Map<String, Int>, b: (Int, Int) -> Unit, c: Int = f(1, 2)"))


class VendoredSourceTest(unittest.TestCase):
    """Miuix API shapes, from the vendored Miuix sources the pinned fork jar is built from."""
    tooltip = probe.read_text(probe.MIUIX / "upstream/miuix-ui/src/commonMain/kotlin/basic/Tooltip.kt")
    backdrop = probe.read_text(probe.MIUIX / "upstream/miuix-blur/src/commonMain/kotlin/blur/LayerBackdrop.kt")
    modifier = probe.read_text(probe.MIUIX / "upstream/miuix-blur/src/commonMain/kotlin/blur/LayerBackdropModifier.kt")
    drawing = probe.read_text(REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoSectionPlatform.kt")
    bar = probe.read_text(REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsVideoControlBar.kt")

    def check_call(self, signature, call):
        """Every named argument is a parameter; every parameter without a default is supplied by name, by position or as
        the trailing lambda (the last parameter)."""
        names = [name for name, _default in signature]
        named, positional, trailing = call
        self.assertTrue(set(named) <= set(names), (named, names))
        provided = set(named) | set(names[:positional]) | ({names[-1]} if trailing else set())
        required = {name for name, default in signature if not default}
        self.assertTrue(required <= provided, ("required", required, "provided", provided))

    def test_tooltip_box_call_matches_the_miuix_signature_and_the_production_call(self):
        signatures = [s for s in probe.function_signatures(self.tooltip, "TooltipBox") if s[0][0] == "positionProvider"]
        self.assertEqual(1, len(signatures))
        calls = probe.calls(PROBE_TEXT, "MiuixTooltipBox")
        self.assertEqual(1, len(calls))
        self.check_call(signatures[0], calls[0])
        production = probe.calls(self.bar, "MiuixTooltipBox")
        self.assertEqual(1, len(production))
        # The probe adds only `modifier`, and passes the content by name instead of as a trailing lambda.
        self.assertTrue(production[0][2])
        self.assertEqual(set(production[0][0]) | {"modifier", "content"}, set(calls[0][0]))

    def test_tooltip_state_position_provider_and_plain_tooltip(self):
        state = probe.function_signatures(self.tooltip, "rememberTooltipState")
        self.assertEqual([[("initialIsVisible", True), ("isPersistent", True), ("mutatorMutex", True)]], state)
        self.assertEqual([(("isPersistent",), 0, False)], probe.calls(PROBE_TEXT, "rememberMiuixTooltipState"))
        provider = probe.function_signatures(self.tooltip, "rememberTooltipPositionProvider")
        self.assertEqual(1, len(provider))
        self.assertEqual([("positioning", True), ("spacingBetweenTooltipAndAnchor", True)], provider[0])
        self.assertIn("positioning: TooltipAnchorPosition = TooltipAnchorPosition.Below, spacingBetweenTooltipAndAnchor: Dp = "
                      "SpacingBetweenTooltipAndAnchor,): PopupPositionProvider", squash(self.tooltip))
        self.assertIn("import androidx.compose.ui.window.PopupPositionProvider\n", self.tooltip)
        self.assertEqual([((), 2, False)], probe.calls(PROBE_TEXT, "rememberTooltipPositionProvider"))
        plain = [s for s in probe.function_signatures(self.tooltip, "PlainTooltip")]
        self.assertEqual(1, len(plain))
        self.assertEqual(("content", False), plain[0][-1])
        # PlainTooltip is called with its trailing lambda only: every other parameter has a default.
        self.assertEqual(1, len(re.findall(r"\bMiuixPlainTooltip\s*\{", PROBE_CODE)))
        self.assertTrue(all(default for _name, default in plain[0][:-1]))

    def test_layer_backdrop_calls(self):
        remember = probe.function_signatures(self.backdrop, "rememberLayerBackdrop")
        self.assertEqual([[("graphicsLayer", True), ("onDraw", True)]], remember)
        self.assertIn("onDraw: ContentDrawScope.() -> Unit", squash(self.backdrop))
        self.assertEqual([(("onDraw",), 0, False)], probe.calls(PROBE_TEXT, "rememberLayerBackdrop"))
        self.assertEqual([[("backdrop", False)]], probe.function_signatures(self.modifier, "layerBackdrop"))
        self.assertIn("fun Modifier.layerBackdrop(backdrop: LayerBackdrop)", squash(self.modifier))
        self.assertIn("class LayerBackdrop internal constructor(", self.backdrop)
        # The node draws its content, then records onDraw with the node's ContentDrawScope as receiver.
        self.assertIn("recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }", self.modifier)
        self.assertIn("fun DrawScope.drawVideoBackdrop()", self.drawing)
        self.assertIn("internal interface DesktopOriginalVideoSectionPlatform", self.drawing)

    def test_popup_position_provider_override_matches_the_miuix_implementation(self):
        miuix = probe.function_signatures(self.tooltip, "calculatePosition")
        ours = probe.function_signatures(PROBE_TEXT, "calculatePosition")
        self.assertEqual(1, len(miuix))
        self.assertEqual(miuix, ours)
        self.assertIn("import androidx.compose.ui.window.PopupPositionProvider", self.tooltip)
        types = re.search(r"override fun calculatePosition\((.*?)\): IntOffset", squash(self.tooltip)).group(1)
        ours_types = re.search(r"override fun calculatePosition\((.*?)\): IntOffset", squash(PROBE_CODE)).group(1)
        self.assertEqual(types.rstrip(","), ours_types.rstrip(","))


class GeneratedTextTest(unittest.TestCase):
    """The real generated text, from the generators run in setUpModule."""

    @staticmethod
    def glass():
        return probe.read_text(GENERATED["player"] / "com/android/purebilibili/feature/video/ui/overlay/PlayerControlGlass.kt")

    def test_the_dock_its_host_and_the_page_backdrop_local_are_internal_in_the_overlay_package(self):
        text = self.glass()
        self.assertIn("package com.android.purebilibili.feature.video.ui.overlay\n", text)
        self.assertIsNotNone(re.search(r"@Composable\ninternal fun PlayerControlGlassDock\(", text))
        self.assertIsNotNone(re.search(r"@Composable\ninternal fun PlayerControlsBackdropHost\(", text))
        self.assertIn("internal val LocalPlayerPageBackdrop = compositionLocalOf<Backdrop?> { null }", text)
        self.assertIn("import top.yukonga.miuix.kmp.blur.Backdrop\n", text)
        # The dock's own local is private: the backdrop reaches the dock only through the page local and the host.
        self.assertIn("private val LocalPlayerControlBackdrop = compositionLocalOf<Backdrop?> { null }", text)
        self.assertIn("val backdrop = LocalPlayerPageBackdrop.current", text)
        self.assertIn("LocalPlayerControlBackdrop provides backdrop.takeIf { glass }", text)

    def test_the_probe_calls_match_the_generated_signatures(self):
        text = self.glass()
        dock = probe.function_signatures(text, "PlayerControlGlassDock")
        self.assertEqual([[("enabled", False), ("modifier", True), ("shape", True), ("content", False)]], dock)
        host = probe.function_signatures(text, "PlayerControlsBackdropHost")
        self.assertEqual([[("enabled", False), ("modifier", True), ("content", False)]], host)
        self.assertEqual([(("enabled", "modifier", "shape"), 0, True)], probe.calls(PROBE_TEXT, "PlayerControlGlassDock"))
        self.assertEqual([(("enabled",), 0, True)], probe.calls(PROBE_TEXT, "PlayerControlsBackdropHost"))
        self.assertIn("val glass = enabled && LocalAppThemeConfig.current.liquidGlassEnabled", text)

    def test_every_probe_import_of_project_code_is_declared(self):
        for name, _alias in probe.probe_imports(PROBE_CODE):
            if name.startswith(("com.android.purebilibili.", "com.bilipai.desktop.")):
                self.assertIsNotNone(project_declaration(name), name)

    def test_the_dock_and_the_section_platform_are_visible_to_the_test_source_set(self):
        # internal is visible to the test source set of the same Gradle module; private would not be.
        platform = probe.read_text(REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoSectionPlatform.kt")
        self.assertIn("\ninternal interface DesktopOriginalVideoSectionPlatform {", platform)
        for name in ("PlayerControlGlassDock", "PlayerControlsBackdropHost", "LocalPlayerPageBackdrop"):
            self.assertIsNone(re.search(r"\bprivate\s+(?:fun|val)\s+%s\b" % name, self.glass()))

    def test_the_generated_video_card_menu_uses_exactly_the_probed_call_shapes(self):
        text = probe.read_text(GENERATED["video_card"])
        code = probe.mask_kotlin(text)
        menu_import = "import com.android.purebilibili.core.ui.components.AppDropdownMenu\n"
        item_import = "import com.android.purebilibili.core.ui.components.AppDropdownMenuItem\n"
        self.assertEqual(1, text.count(menu_import))
        self.assertEqual(1, text.count(item_import))
        # Every other token use is a call: no reference, no named import of anything else from the family.
        self.assertEqual(2, len(re.findall(r"\bAppDropdownMenu\b", code)))
        self.assertEqual(4, len(re.findall(r"\bAppDropdownMenuItem\b", code)))
        self.assertEqual(0, len(re.findall(r"AppDropdownMenu\w+", code.replace("AppDropdownMenuItem", ""))))
        menus, items = probe.calls(text, "AppDropdownMenu"), probe.calls(text, "AppDropdownMenuItem")
        self.assertEqual([(("expanded", "onDismissRequest", "offset"), 0, True)], menus)
        self.assertEqual([(("text", "onClick"), 0, False)] * 3, items)
        # The probe reproduces those shapes through the aliases, in the same count.
        self.assertEqual(menus, probe.calls(PROBE_TEXT, "AppDropdownMenu"))
        self.assertEqual(items, probe.calls(PROBE_TEXT, "AppDropdownMenuItem"))
        self.assertIn("AppDropdownMenu(\n                expanded = showDismissMenu,", text)

    def test_the_standins_accept_every_argument_of_the_original_call_sites_and_nothing_else_is_required(self):
        original = probe.read_text(GENERATED["roots"][3] / "com/android/purebilibili/core/ui/components/AppPrimitiveComponents.kt")
        menu, = probe.function_signatures(original, "AppDropdownMenu")
        item, = probe.function_signatures(original, "AppDropdownMenuItem")
        used_menu, used_item = {"expanded", "onDismissRequest", "offset"}, {"text", "onClick"}
        self.assertTrue(used_menu <= {n for n, _ in menu})
        self.assertTrue(used_item <= {n for n, _ in item})
        self.assertEqual(("content", False), menu[-1])
        self.assertIn("content: @Composable ColumnScope.() -> Unit", squash(original))
        for original_signature, stand_in_name, used in ((menu, "DesktopCardContextMenu", used_menu),
                                                        (item, "DesktopCardContextMenuItem", used_item)):
            stand_ins = probe.function_signatures(PROBE_TEXT, stand_in_name)
            self.assertEqual(1, len(stand_ins), stand_in_name)
            stand_in = stand_ins[0]
            original_names = [n for n, _ in original_signature]
            names = [n for n, _ in stand_in]
            self.assertTrue(used <= set(names), (stand_in_name, used, names))
            self.assertTrue(set(names) <= set(original_names), (stand_in_name, names, original_names))
            required = {n for n, default in stand_in if not default}
            self.assertTrue(required <= used | {"content"}, (stand_in_name, required))
        self.assertIn("content: @Composable ColumnScope.() -> Unit", squash(PROBE_CODE))

    def test_story_video_card_is_the_only_other_home_card_that_uses_the_family(self):
        users = sorted(path.name for root in GENERATED["roots"][:3] for path in Path(root).rglob("*.kt")
                       if re.search(r"\bAppDropdownMenu(?:Item)?\(", probe.mask_kotlin(probe.read_text(path)))
                       and "components/cards" in path.as_posix())
        self.assertEqual(["StoryVideoCard.kt", "VideoCard.kt"], users)


class PinnedJarApiTest(JarCase):
    def test_the_jars_are_the_pinned_versions(self):
        compose, commit = probe.pins()
        self.assertEqual("1.12.1", compose)
        for family, jars in self.found.items():
            for jar in jars:
                self.assertIn(commit if family == "miuix" else compose, jar.name)
        self.assertEqual(set(probe.jar_patterns()), set(self.found))

    def test_every_symbol_exists_with_the_exact_descriptor_and_the_declared_opt_in_markers(self):
        for symbol in probe.SYMBOLS:
            with self.subTest(symbol["key"]):
                problems, markers = probe.symbol_findings(self.jars, symbol)
                self.assertEqual([], problems)
                self.assertEqual(set(symbol["opt_in"]), markers)

    def test_the_two_interfaces_the_probe_implements_have_exactly_the_probed_abstract_members(self):
        def kotlin_names(jvm_members):
            names = set()
            for member in jvm_members:
                name = re.match(r"[\w$]+", member).group()
                names.add(re.sub(r"^get([A-Z])", lambda m: m.group(1).lower(), name))
            return names

        body = re.search(r"class ProbeStaggeredGridScrollbarAdapter : ScrollbarAdapter \{(.*?)\n\}\n", PROBE_CODE, re.S).group(1)
        overridden = set(re.findall(r"override (?:suspend )?(?:val|fun) (\w+)", body))
        adapter = self.jars.get("androidx/compose/foundation/v2/ScrollbarAdapter")
        self.assertEqual(kotlin_names(adapter.abstract_methods()), overridden)
        provider = self.jars.get("androidx/compose/ui/window/PopupPositionProvider")
        self.assertEqual({"calculatePosition"}, {re.match(r"[A-Za-z]+", n).group() for n in provider.abstract_methods()})
        self.assertEqual(1, len(provider.abstract_methods()))
        body = re.search(r"class ProbePopupPositionProvider\(.*?\) : PopupPositionProvider \{(.*?)\n\}\n", PROBE_CODE, re.S).group(1)
        self.assertEqual(["calculatePosition"], re.findall(r"override (?:suspend )?(?:val|fun) (\w+)", body))

    def test_the_v1_scrollbar_overload_is_deprecated_and_the_v2_one_is_not(self):
        facade = self.jars.get("androidx/compose/foundation/Scrollbar_desktopKt")
        overloads = [m for m in facade.methods if m.name == "VerticalScrollbar"]
        flags = {("v2/ScrollbarAdapter" in m.desc): ("Lkotlin/Deprecated;" in m.annotations) for m in overloads}
        self.assertEqual({True: False, False: True}, flags)

    def test_the_compose_dialog_owner_and_modality_constructor_is_not_experimental_but_the_analytics_one_is(self):
        dialog = self.jars.get("androidx/compose/ui/awt/ComposeDialog")
        plain = dialog.find("<init>", "(Ljava/awt/Window;Ljava/awt/Dialog$ModalityType;Ljava/awt/GraphicsConfiguration;)V")
        self.assertEqual(1, len(plain))
        self.assertEqual((), plain[0].annotations)
        analytics = [m for m in dialog.methods if m.name == "<init>" and "SkiaLayerAnalytics" in m.desc and not m.synthetic]
        self.assertTrue(analytics and all("Landroidx/compose/ui/ExperimentalComposeUiApi;" in m.annotations for m in analytics))

    def test_every_non_project_import_of_the_probe_resolves(self):
        skipped = ("java.", "javax.", "kotlin.", "org.junit.", "com.android.purebilibili.", "com.bilipai.desktop.")
        unresolved = []
        for name, _alias in probe.probe_imports(PROBE_CODE):
            if name.startswith(skipped):
                continue
            package, _, leaf = name.rpartition(".")
            if self.jars.get(name.replace(".", "/")) is None and not self.jars.has_top_level(package, leaf):
                unresolved.append(name)
        self.assertEqual([], unresolved)

    def test_named_arguments_of_the_compose_calls_occur_in_the_defining_facades(self):
        # A weak check by design: parameter names live in Kotlin metadata strings of the facade's constant pool.
        for cls, names in (("androidx/compose/ui/input/pointer/PointerIconKt", ("overrideDescendants",)),
                           ("androidx/compose/foundation/Scrollbar_desktopKt", ("adapter", "modifier")),
                           ("androidx/compose/ui/window/Popup_skikoKt", ("popupPositionProvider", "onDismissRequest", "properties")),
                           ("androidx/compose/ui/input/pointer/PointerEvent_skikoKt", ("isBackPressed",))):
            for name in names:
                self.assertIn(name, self.jars.get(cls).utf8, (cls, name))
        self.assertEqual([(("isBackPressed",), 0, False)], probe.calls(PROBE_TEXT, "PointerButtons"))
        self.assertEqual([(("overrideDescendants",), 1, False)], probe.calls(PROBE_TEXT, "pointerHoverIcon")[:1])
        self.assertEqual([(("adapter", "modifier"), 0, False)], probe.calls(PROBE_TEXT, "VerticalScrollbar"))
        popup = probe.calls(PROBE_TEXT, "Popup")
        self.assertEqual([(("popupPositionProvider", "onDismissRequest", "properties"), 0, True)], popup)


class JdkApiTest(unittest.TestCase):
    def test_the_jdk_api_of_the_probe_exists(self):
        stubs = probe.find_jdk_stubs()
        if stubs is None:
            if JARS_OPTIONAL:
                self.skipTest("no JDK with lib/ct.sym found (BILIPAI_UX_JDK, JAVA_HOME, work/toolchain/jdk)")
            self.fail("no JDK with lib/ct.sym found (BILIPAI_UX_JDK, JAVA_HOME, work/toolchain/jdk)")
        for cls, member, desc in probe.JDK_SYMBOLS:
            with self.subTest("%s.%s" % (cls, member)):
                stub = stubs.get(cls)
                self.assertIsNotNone(stub, cls)
                if desc is None:
                    self.assertTrue(stub.has_field(member))
                else:
                    self.assertTrue([m for m in stub.methods if m.name == member and m.desc == desc and m.public], desc)
        imported = {name for name, _alias in probe.probe_imports(PROBE_CODE) if name.startswith("java.")}
        self.assertEqual({"java.awt.Cursor", "java.awt.Dialog", "java.awt.Point", "java.awt.Toolkit", "java.awt.Window",
                          "java.awt.image.BufferedImage"}, imported)
        for name in imported:
            self.assertIsNotNone(stubs.get(name.replace(".", "/")), name)


if __name__ == "__main__":
    unittest.main()
