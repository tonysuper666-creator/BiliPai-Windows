"""v0.4.6 design-system leaf files, part B: AppPreferenceComponents, AppSelectionPreferenceComponents, AppCard,
AppDesktopInteraction, AppTonalPillTabPolicy and animation/DampedDragAnimation advanced to the whole v0.4.6 file inside
the four producers that already own them.

Source checks only: nothing is compiled and no JVM test runs here. The Kotlin regression class is counted and checked
for balanced delimiters and resolvable imports; the Linux compile and JVM run happen later.
"""
from pathlib import Path
import difflib
import functools
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import design_system_consumer_table as table_tool
import v037_shared_tabs_progress as tabs
import v046_design_system_leaves_a as leaves_a
import v046_design_system_leaves_b as leaves


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync_upstream_design_system_leaves_b', 'sync-upstream.py')
components = load('extract_upstream_components_design_system_leaves_b', 'extract-upstream-components.py')
preferences = load('extract_upstream_preferences_design_system_leaves_b', 'extract-upstream-preferences.py')
shared = load('extract_upstream_shared_liquid_tabs_design_system_leaves_b', 'extract-upstream-shared-liquid-tabs.py')

BASE = leaves.BASE
CANONICAL = leaves.UPSTREAM_PATHS
HAND_WRITTEN_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDesignSystemLeavesBTest.kt'
PRODUCERS = {
    'components': 'tools/extract-upstream-components.py',
    'preferences': 'tools/extract-upstream-preferences.py',
    'shared': 'tools/extract-upstream-shared-liquid-tabs.py',
    'controls': 'tools/extract-upstream-video-player-full-controls.py',
}
TASKS = ('extractUpstreamComponents', 'extractUpstreamPreferences', 'extractSharedLiquidTabs',
         'extractOriginalPlayerFullControls')
TOP_LEVEL = {
    'AppPreferenceComponents.kt': {
        ('public', 'fun', 'AppPreferenceSectionTitle'), ('public', 'class', 'AppPreferenceGroupPresentation'),
        ('public', 'fun', 'AppPreferenceGroup'), ('public', 'fun', 'AppSwitchPreference'),
        ('public', 'fun', 'AppSliderPreference'), ('public', 'fun', 'AppPreference'),
        ('public', 'fun', 'AppPreferenceDivider'), ('public', 'fun', 'AppPreferenceGridItem'),
        ('public', 'class', 'AppSearchFieldPresentation'), ('public', 'fun', 'AppSearchField'),
        ('public', 'fun', 'AppTextField')},
    'AppSelectionPreferenceComponents.kt': {
        ('public', 'class', 'AppSingleChoicePresentation'), ('public', 'val', 'LocalAppSingleChoicePresentation'),
        ('public', 'class', 'AppChoiceOption'), ('public', 'fun', 'AppSingleChoicePreference'),
        ('public', 'fun', 'AppSingleChoiceDialog'), ('public', 'fun', 'AppSliderDialogPreference'),
        ('public', 'class', 'AppSliderDialogLayoutPolicy'), ('public', 'fun', 'resolveAppSliderDialogLayoutPolicy'),
        ('public', 'fun', 'AppSliderDialog'), ('public', 'fun', 'shouldDispatchAppChoiceSelection'),
        ('public', 'fun', 'resolveAppSliderDialogValue')},
    'AppCard.kt': {
        ('public', 'interface', 'AppCardShape'), ('public', 'class', 'AppCardColors'),
        ('public', 'class', 'AppCardVariant'), ('public', 'object', 'AppCardDefaults'), ('public', 'fun', 'AppCard')},
    'AppDesktopInteraction.kt': {
        ('public', 'fun', 'appDesktopInteractionVisuals'), ('public', 'fun', 'appDesktopFocusableItemVisuals')},
    'AppTonalPillTabPolicy.kt': {
        ('public', 'fun', 'resolveTonalPillTabDefaultLabelFontSize'), ('public', 'fun', 'resolveTonalPillTabCornerRadius'),
        ('public', 'fun', 'resolveTonalPillTabItemHorizontalPadding'), ('public', 'fun', 'resolveTonalPillTabDefaultHeight')},
    'DampedDragAnimation.kt': {
        ('internal', 'fun', 'resolveDampedDragVelocityItemsPerSecond'), ('internal', 'val', 'HORIZONTAL_DRAG_DOMINANCE_RATIO'),
        ('internal', 'val', 'HORIZONTAL_DRAG_MIN_DISTANCE_PX'), ('public', 'class', 'DampedDragTrackingMode'),
        ('public', 'fun', 'shouldEngageHorizontalDrag'), ('internal', 'fun', 'resolveDampedDragReleaseTargetIndex'),
        ('public', 'class', 'DampedDragAnimationState'), ('private', 'val', 'BILIPAI_PRESSED_SCALE'),
        ('public', 'fun', 'rememberDampedDragAnimationState'), ('public', 'fun', 'horizontalDragGesture')},
}
SELECTION_BINDING = ('import androidx.compose.ui.platform.LocalConfiguration',
                     'import com.bilipai.desktop.appearance.DesktopWindowConfiguration as LocalConfiguration')
DAMPED_BINDINGS = (('import android.os.SystemClock\n', ''),
                   ('import kotlinx.coroutines.android.awaitFrame', 'import androidx.compose.runtime.withFrameNanos'),
                   ('awaitFrame()', 'withFrameNanos { it }'),
                   ('SystemClock.uptimeMillis()', 'System.nanoTime() / 1_000_000L'))
ADAPTER_REFERENCES = {
    'AdaptivePreferenceComponents.kt': BASE + 'components/AdaptivePreferenceComponents.kt',
    'AdaptiveDialogComponents.kt': BASE + 'AdaptiveDialogComponents.kt',
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def lf(raw):
    return raw.replace(b'\r\n', b'\n')


def git_blob(path, commit=leaves.COMMIT):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', commit + ':' + path],
                          cwd=str(REPO), capture_output=True, check=True).stdout


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / leaves.ARCHIVE).iterdir() if p.is_file()}


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def prepare_block(build):
    start = build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {')
    return build[start:build.index('\nval extractUpstreamApi ', start)]


def run_cli(name, root, seed):
    """The producer exactly the way its Gradle Exec task runs it (working directory desktop/)."""
    output, tests = root / (name + '-out'), root / (name + '-test')
    if name == 'controls':
        arguments = [str(REPO), str(output), '--test-output', str(tests)]
    elif name == 'components':
        arguments = ['--repo', str(REPO), '--output', str(output), '--test-output', str(tests)]
    else:
        arguments = ['--repo', str(REPO), '--output', str(output)]
    environment = dict(os.environ, PYTHONHASHSEED=str(seed), PYTHONIOENCODING='utf-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1')
    done = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(REPO / 'desktop' / PRODUCERS[name])] + arguments,
                          cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(name + ': ' + done.stderr.decode('utf-8', 'replace')[-4000:])
    return output, tests


def declarations(text, path='x.kt'):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, {(item.visibility, item.kind, item.name) for item in info.decls}


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


def kotlin_tokens(text):
    return gate.tokenize(gate.mask_kotlin(text))[0]


def split_names(toks, first, last):
    """Names of the parameter group toks[first+1:last]: the identifier before each top-level colon."""
    names, head, depth, angle, typed = [], [], 0, 0, False
    for token in toks[first + 1:last]:
        if token in ('(', '[', '{'):
            depth += 1
        elif token in (')', ']', '}'):
            depth -= 1
        if depth == 0 and angle == 0 and token == ',':
            head, typed = [], False
            continue
        if depth != 0:
            continue
        if typed:
            if token == '<':
                angle += 1
            elif token == '>':
                angle = max(0, angle - 1)
        elif token == ':':
            typed = True
            if head:
                names.append(head[-1])
        else:
            head.append(token)
    return names


def parameter_names(text, name):
    """Parameter names of every `fun ... name(` declaration (overloads give several lists)."""
    toks = kotlin_tokens(text)
    found = []
    for index, token in enumerate(toks):
        if token != 'fun':
            continue
        cursor = index + 1
        if cursor < len(toks) and toks[cursor] == '<':
            cursor = gate._skip_angle(toks, cursor)
        while cursor < len(toks) and toks[cursor] not in ('(', '{', '}', '=', ';'):
            cursor += 1
        if cursor < len(toks) and toks[cursor] == '(' and toks[cursor - 1] == name:
            found.append(split_names(toks, cursor, gate._match(toks, cursor, '(', ')')))
    return found


def named_arguments(text, name):
    """Named arguments of every call of `name(...)` (declarations and member calls are skipped)."""
    toks = kotlin_tokens(text)
    found = []
    for index, token in enumerate(toks[:-1]):
        if token != name or toks[index + 1] != '(' or (index and toks[index - 1] in ('fun', '.')):
            continue
        close = gate._match(toks, index + 1, '(', ')')
        depth, names = 0, []
        for cursor in range(index + 2, close):
            current = toks[cursor]
            if current in ('(', '[', '{'):
                depth += 1
            elif current in (')', ']', '}'):
                depth -= 1
            elif (depth == 0 and gate.is_ident(current) and toks[cursor + 1] == '=' and toks[cursor + 2] != '='
                  and toks[cursor - 1] in ('(', ',')):
                names.append(current)
        found.append(names)
    return found


def changed(old, new):
    rows = [line for line in difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm='', n=0)
            if line[:1] in '+-' and line[:3] not in ('+++', '---')]
    return [line for line in rows if line[:1] == '-'], [line for line in rows if line[:1] == '+']


def vendored(name):
    return (REPO / CANONICAL[name]).read_bytes().decode('utf-8').replace('\r\n', '\n')


class V046DesignSystemLeavesBSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-dsb-')
        cls.root = Path(cls.temporary.name)
        cls.out, cls.test_out = {}, {}
        for name in PRODUCERS:
            output, tests = run_cli(name, cls.root, 0)
            cls.out[name] = tree(output)
            cls.test_out[name] = tree(tests) if tests.exists() else {}
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.manifest = json.loads(cls.archive['manifest.json'])

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def emitted(self, producer, filename):
        paths = [path for path in self.out[producer] if path.endswith('/' + filename)]
        self.assertEqual(1, len(paths), (producer, filename, paths))
        return self.out[producer][paths[0]]

    # ---- archive -------------------------------------------------------------------------------------------

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        on_disk = sorted(self.archive)
        self.assertEqual(sorted(leaves.EXPECTED | {'LICENSE', 'manifest.json'}), on_disk)
        for leaf in on_disk:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / leaves.ARCHIVE, repo / leaves.ARCHIVE)
                target = repo / leaves.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    leaves.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / leaves.ARCHIVE, repo / leaves.ARCHIVE)
            (repo / leaves.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                leaves.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / leaves.ARCHIVE, repo / leaves.ARCHIVE)
            (repo / leaves.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                leaves.fixed(repo)
        self.assertEqual(self.archive, archive_files())

    def test_manifest_rows_match_the_upstream_git_blobs_and_the_pins(self):
        self.assertEqual(leaves.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        self.assertNotIn(b'\r', self.archive['manifest.json'])
        self.assertEqual(list(leaves.MAIN_FILES), [row['filename'] for row in self.manifest['files']])
        self.assertEqual(('jay3-yy/BiliPai', 'v0.4.6', leaves.COMMIT, 'raw'),
                         tuple(self.manifest[key] for key in ('repository', 'tag', 'commit', 'hashNormalization')))
        for row in self.manifest['files']:
            with self.subTest(name=row['filename']):
                blob = git_blob(row['path'])
                archived = self.archive[row['filename']]
                self.assertEqual(blob, archived)
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertEqual((row['bytes'], row['sha256']), (len(blob), sha(blob)))
                self.assertNotIn(b'\r', archived)
        self.assertEqual(git_blob('LICENSE'), self.archive['LICENSE'])
        self.assertEqual(sha(self.archive['LICENSE']), self.manifest['license']['sha256'])

    def test_the_slice_carries_exactly_the_six_named_files(self):
        self.assertEqual({'AppPreferenceComponents', 'AppSelectionPreferenceComponents', 'AppCard',
                          'AppDesktopInteraction', 'AppTonalPillTabPolicy', 'DampedDragAnimation'},
                         {Path(name).stem for name in leaves.MAIN_FILES})
        self.assertEqual(set(leaves.MAIN_FILES), set(TOP_LEVEL))
        self.assertEqual('design-system/src/main/java/com/android/purebilibili/core/ui/animation/DampedDragAnimation.kt',
                         CANONICAL['DampedDragAnimation.kt'])
        # the app-level miuix copy of the drag state is a different file and not part of this slice
        self.assertNotIn('app/src/main/java/com/android/purebilibili/feature/home/components/miuix/DampedDragAnimation.kt',
                         {row['path'] for row in self.manifest['files']})

    def test_declared_top_level_names_are_exactly_the_reviewed_set(self):
        for name, wanted in TOP_LEVEL.items():
            with self.subTest(name=name):
                package, decls = declarations(self.archive[name].decode('utf-8'), name)
                self.assertEqual(leaves.PACKAGES[name], package)
                self.assertEqual(wanted, decls)

    def test_behaviour_changes_against_the_vendored_predecessors_are_exactly_the_documented_ones(self):
        text = {name: self.archive[name].decode('utf-8') for name in leaves.MAIN_FILES}
        removed, added = changed(vendored('AppCard.kt'), text['AppCard.kt'])
        self.assertEqual(['-            colors = colors,', '-            colors = colors,'], removed)
        self.assertEqual(2, added.count('+            colors = resolvedColors,'))
        self.assertIn('+        containerColor = com.android.purebilibili.core.ui.AppSurfaceTokens.cardContainer(),', added)
        self.assertIn('+        contentColor = com.android.purebilibili.core.ui.AppContentColors.primaryText(),', added)
        self.assertEqual(6, len(added))
        removed, added = changed(vendored('AppDesktopInteraction.kt'), text['AppDesktopInteraction.kt'])
        self.assertEqual(2, removed.count('-    val focusColor = MaterialTheme.colorScheme.primary'))
        self.assertEqual(2, removed.count('-    val hoverColor = MaterialTheme.colorScheme.onSurface'))
        self.assertEqual(2, added.count('+    val focusColor = AppContentColors.accent()'))
        self.assertEqual(2, added.count('+    val hoverColor = AppContentColors.primaryText()'))
        self.assertEqual((4, 6), (len(removed), len(added)))
        removed, added = changed(vendored('AppTonalPillTabPolicy.kt'), text['AppTonalPillTabPolicy.kt'])
        self.assertIn('-fun resolveTonalPillTabVerticalInset(): Dp = 4.dp', removed)
        self.assertEqual(4, len(removed))
        self.assertEqual(2, len(added))
        for line in added + [row for row in removed if 'resolveTonalPillTabVerticalInset' not in row]:
            self.assertTrue(line[1:].strip() == '' or line[1:].lstrip().startswith('*'), line)      # KDoc lines only
        removed, added = changed(vendored('AppPreferenceComponents.kt'), text['AppPreferenceComponents.kt'])
        self.assertEqual((22, 30), (len(removed), len(added)))
        self.assertIn('+import com.android.purebilibili.core.ui.AppContentColors', added)
        self.assertIn('+import com.android.purebilibili.core.ui.ProvideAppEntryIconColors', added)
        self.assertEqual(1, text['AppPreferenceComponents.kt'].count('ProvideAppEntryIconColors {'))
        self.assertEqual(0, vendored('AppPreferenceComponents.kt').count('AppContentColors'))
        self.assertEqual(14, text['AppPreferenceComponents.kt'].count('AppContentColors.'))
        removed, added = changed(vendored('AppSelectionPreferenceComponents.kt'), text['AppSelectionPreferenceComponents.kt'])
        self.assertEqual((9, 38), (len(removed), len(added)))
        optional = 'optionLeading: (@Composable (T) -> Unit)? = null,'
        self.assertEqual(0, vendored('AppSelectionPreferenceComponents.kt').count('optionLeading'))
        self.assertEqual(2, text['AppSelectionPreferenceComponents.kt'].count(optional))
        self.assertEqual(2, text['AppSelectionPreferenceComponents.kt'].count('ModalWindowBlurBehindEffect()'))
        self.assertEqual(0, vendored('AppSelectionPreferenceComponents.kt').count('ModalWindowBlurBehindEffect'))
        old_damped, new_damped = vendored('DampedDragAnimation.kt'), text['DampedDragAnimation.kt']
        self.assertIn('private var velocityJob: Job? = null', old_damped)
        self.assertNotIn('velocityJob', new_damped)
        self.assertEqual(1, new_damped.count('private val velocityAnimation = LiquidIndicatorVelocity(scope)'))
        self.assertEqual(1, new_damped.count('import kotlinx.coroutines.withTimeoutOrNull'))
        member = re.compile(r'(?m)^    (?!private )((?:internal |override )?(?:fun|val|var) (?:<[^>]+> )?[\w.]+)')
        self.assertEqual(set(member.findall(old_damped)), set(member.findall(new_damped)))
        self.assertEqual(34, len(set(member.findall(new_damped))))

    def test_public_signatures_stay_source_compatible_with_the_vendored_callers(self):
        for name, functions in {
                'AppPreferenceComponents.kt': ('AppPreferenceGroup', 'AppSwitchPreference', 'AppSliderPreference',
                                               'AppPreference', 'AppPreferenceGridItem', 'AppSearchField', 'AppTextField'),
                'AppCard.kt': ('AppCard',),
                'AppDesktopInteraction.kt': ('appDesktopInteractionVisuals', 'appDesktopFocusableItemVisuals'),
                'DampedDragAnimation.kt': ('shouldEngageHorizontalDrag', 'rememberDampedDragAnimationState',
                                           'horizontalDragGesture', 'resolveDampedDragReleaseTargetIndex')}.items():
            for function in functions:
                with self.subTest(file=name, function=function):
                    old = parameter_names(vendored(name), function)
                    new = parameter_names(self.archive[name].decode('utf-8'), function)
                    self.assertTrue(new)
                    self.assertEqual(old, new)
        selection_old, selection_new = vendored('AppSelectionPreferenceComponents.kt'), self.archive['AppSelectionPreferenceComponents.kt'].decode('utf-8')
        for function in ('AppSingleChoicePreference', 'AppSingleChoiceDialog'):
            old = parameter_names(selection_old, function)[0]
            new = parameter_names(selection_new, function)[0]
            with self.subTest(function=function):
                self.assertEqual([name for name in new if name != 'optionLeading'], old)
                self.assertEqual(1, new.count('optionLeading'))
        for function in ('AppSliderDialogPreference', 'AppSliderDialog'):
            with self.subTest(function=function):
                self.assertEqual(parameter_names(selection_old, function), parameter_names(selection_new, function))
        # the one new parameter sits before presentation (choice preference) and is defaulted, so named callers compile
        new = parameter_names(selection_new, 'AppSingleChoicePreference')[0]
        self.assertEqual(new.index('optionLeading') + 1, new.index('presentation'))

    # ---- the adapter references that stay out --------------------------------------------------------------

    def test_adapter_references_stay_at_their_windows_adapters_and_the_notes_say_why(self):
        carried = {row['path'] for row in self.manifest['files']}
        sources = {row['path']: row for row in json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']}
        for name, path in ADAPTER_REFERENCES.items():
            self.assertNotIn(path, carried)
            self.assertNotIn(name, self.archive)
        self.assertEqual('platform-adapter-reference', sources[ADAPTER_REFERENCES['AdaptivePreferenceComponents.kt']]['mode'])
        self.assertEqual('policy-extract', sources[ADAPTER_REFERENCES['AdaptiveDialogComponents.kt']]['mode'])
        notes = self.manifest['notCarried']
        self.assertEqual(4, len(notes))
        self.assertTrue(notes[0].startswith(ADAPTER_REFERENCES['AdaptivePreferenceComponents.kt']))
        self.assertTrue(notes[1].startswith(ADAPTER_REFERENCES['AdaptiveDialogComponents.kt']))
        self.assertIn('DampedDragAnimationPolicyTest.kt', notes[2])
        self.assertTrue(notes[3].startswith(BASE + 'renderer/miuix/AppMiuixCard.kt'))

    def test_deferring_the_adapter_references_leaves_no_symbol_gap_for_the_carried_files(self):
        retained = {
            'AdaptivePreferenceComponents.kt': self.emitted('preferences', 'AdaptivePreferenceComponents.kt').decode('utf-8'),
            'AdaptiveDialogComponents.kt': self.emitted('components', 'AdaptiveDialogComponents.kt').decode('utf-8'),
        }

        def public_keys(text):
            info = gate.parse_kotlin(text, 'x.kt', 'carried', 'main')
            return {(item.kind, item.receiver, item.name, item.sig, item.visibility) for item in info.decls if item.visibility != 'private'}

        difference = {}
        for name, path in ADAPTER_REFERENCES.items():
            upstream = git_blob(path).decode('utf-8')
            new, old = public_keys(upstream), public_keys(retained[name])
            self.assertEqual({key[2] for key in new}, {key[2] for key in old}, name)   # same names, nothing new to depend on
            difference[name] = (sorted(new - old), sorted(old - new))
        # the dialog adapter has the identical public surface
        self.assertEqual(([], []), difference['AdaptiveDialogComponents.kt'])
        # the preference adapter differs in four signatures only: AppSearchEntry gains two parameters and the three
        # internal list-visual resolvers drop the liquid-glass flag
        added, dropped = difference['AdaptivePreferenceComponents.kt']
        self.assertEqual(['AppSearchEntry', 'resolveAdaptiveListComponentVisualSpec', 'resolveAdaptiveListRowVisualSpec',
                          'resolveAdaptiveListVisualCapabilities'], [key[2] for key in added])
        self.assertEqual([key[2] for key in added], [key[2] for key in dropped])
        # no carried archive and no desktop-owned file calls any of the four (their callers are upstream app files)
        names = ('AppSearchEntry', 'resolveAdaptiveListComponentVisualSpec', 'resolveAdaptiveListRowVisualSpec',
                 'resolveAdaptiveListVisualCapabilities')
        scanned = sorted((REPO / 'desktop/upstream-slices').glob('*/*.kt')) + sorted((REPO / 'desktop/src').rglob('*.kt'))
        for path in scanned:
            raw = path.read_bytes()
            if any(name.encode('utf-8') in raw for name in names) and path.name != 'AdaptivePreferenceComponents.kt':
                tokens = set(kotlin_tokens(raw.decode('utf-8')))
                self.assertEqual(set(), tokens & set(names), path.relative_to(REPO).as_posix())
        # the v0.4.6 AppPreferenceComponents and AppSelectionPreferenceComponents only call parameters the retained
        # adapter declares
        adapter = retained['AdaptivePreferenceComponents.kt']
        preference = self.archive['AppPreferenceComponents.kt'].decode('utf-8')
        toks = kotlin_tokens(preference)
        called = {toks[i] for i in range(len(toks) - 1) if toks[i + 1] == '(' and toks[i].startswith('Adaptive')
                  and (i == 0 or toks[i - 1] not in ('fun', '.'))}
        self.assertTrue({'AdaptivePreferenceGroupRenderer', 'AdaptiveSwitchPreferenceContent',
                         'AdaptiveSliderPreferenceRenderer', 'AdaptivePreferenceContent',
                         'AdaptivePreferenceGridItemRenderer'} <= called, called)
        for function in sorted(called):
            declared = [set(row) for row in parameter_names(adapter, function)]
            self.assertTrue(declared, function)
            for names_used in named_arguments(preference, function):
                with self.subTest(function=function):
                    self.assertTrue(any(set(names_used) <= row for row in declared), (function, names_used, declared))

    def test_card_renderers_accept_the_named_arguments_the_v046_card_passes(self):
        card = self.archive['AppCard.kt'].decode('utf-8')
        for function, path in (('AppMaterial3Card', 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3Card.kt'),
                               ('AppMiuixCard', 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixCard.kt')):
            source = (REPO / path).read_bytes().decode('utf-8').replace('\r\n', '\n')
            declared = [set(row) for row in parameter_names(source, function)]
            calls = named_arguments(card, function)
            self.assertEqual(1, len(calls), function)
            self.assertTrue(any(set(calls[0]) <= row for row in declared), (function, calls, declared))

    # ---- emission ------------------------------------------------------------------------------------------

    def test_direct_chain_files_are_the_whole_v046_file_behind_the_producer_header(self):
        for producer, name in (('components', 'AppCard.kt'), ('components', 'AppDesktopInteraction.kt'),
                               ('preferences', 'AppPreferenceComponents.kt')):
            with self.subTest(name=name):
                header, digest, body = lf(self.emitted(producer, name)).split(b'\n', 2)
                self.assertEqual(('// GENERATED from ' + CANONICAL[name] + '; do not edit.').encode(), header)
                self.assertEqual(('// LF-normalized SHA-256: ' + sha(self.archive[name])).encode(), digest)
                self.assertEqual(self.archive[name], body)

    def test_selection_file_is_the_whole_v046_file_with_only_the_existing_window_binding(self):
        header, digest, body = lf(self.emitted('preferences', 'AppSelectionPreferenceComponents.kt')).split(b'\n', 2)
        self.assertEqual(('// GENERATED from ' + CANONICAL['AppSelectionPreferenceComponents.kt'] + '; do not edit.').encode(), header)
        self.assertEqual(('// LF-normalized SHA-256: ' + sha(self.archive['AppSelectionPreferenceComponents.kt'])).encode(), digest)
        before, after = (value.encode('utf-8') for value in SELECTION_BINDING)
        self.assertEqual(0, body.count(before))
        self.assertEqual(1, body.count(after))
        self.assertEqual(self.archive['AppSelectionPreferenceComponents.kt'], body.replace(after, before, 1))
        self.assertNotRegex(body.decode('utf-8'), r'(?m)^import (?:android\.|androidx\.activity\.)')
        # the binding itself is the producer's, unchanged and independent of the file version
        original = self.archive['AppSelectionPreferenceComponents.kt'].decode('utf-8')
        self.assertEqual(original.replace(SELECTION_BINDING[0], SELECTION_BINDING[1]),
                         preferences.adapt(leaves.BASE + 'components/AppSelectionPreferenceComponents.kt', original,
                                           preferences.helper(REPO)))

    def test_tonal_policy_is_the_whole_v046_file_and_the_emitted_test_loses_only_the_removed_function_assertion(self):
        self.assertEqual(self.archive['AppTonalPillTabPolicy.kt'], self.emitted('controls', 'AppTonalPillTabPolicy.kt'))
        readback = json.loads(self.out['controls']['v037-complete-controls-source-readback.json'])
        rows = [row for row in readback['outputs'] if row['path'].endswith('/AppTonalPillTabPolicy.kt')]
        self.assertEqual(1, len(rows))
        self.assertEqual(leaves.COMMIT, rows[0]['fixedCommit'])
        self.assertEqual(leaves.MANIFEST_SHA256, rows[0]['sourceManifestSHA256'])
        self.assertEqual(sha(self.archive['AppTonalPillTabPolicy.kt']), rows[0]['source']['sha256Bytes'])
        self.assertEqual(sha(self.archive['AppTonalPillTabPolicy.kt']), rows[0]['sha256LF'])
        others = [row for row in readback['outputs'] if Path(row['path']).name in tabs.CONTROL_OUTPUTS
                  and not row['path'].endswith('/AppTonalPillTabPolicy.kt')]
        self.assertTrue(others)
        for row in others:
            self.assertNotEqual(leaves.COMMIT, row['fixedCommit'], row['path'])
        # run the control closure twice, the second time with both overrides switched off
        with tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
            base = Path(tmp)
            tabs.emit_control_foundations(base / 'a', [], base / 'at')
            with mock.patch.object(leaves, 'retained_original_override', lambda repo, path, raw, row, commit, manifest: (raw, row, commit, manifest)), \
                    mock.patch.object(leaves, 'tonal_test_source', lambda repo, path, text: text):
                tabs.emit_control_foundations(base / 'b', [], base / 'bt')
            current, previous = tree(base / 'a'), tree(base / 'b')
            self.assertEqual(sorted(current), sorted(previous))
            self.assertEqual(['com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicy.kt'],
                             [path for path in current if current[path] != previous[path]])
            self.assertEqual(self.archive['AppTonalPillTabPolicy.kt'],
                             current['com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicy.kt'])
            self.assertIn(b'resolveTonalPillTabVerticalInset', previous['com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicy.kt'])
            test_key = 'com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicyTest.kt'
            now, before = tree(base / 'at'), tree(base / 'bt')
            self.assertEqual(sorted(now), sorted(before))
            self.assertEqual([test_key], [path for path in now if now[path] != before[path]])
            window_after, window_before = (value.encode('utf-8') for value in
                                           (leaves.TONAL_TEST_WINDOW_AFTER, leaves.TONAL_TEST_WINDOW_BEFORE))
            self.assertEqual(before[test_key], now[test_key].replace(window_after, window_before, 1))
            self.assertEqual(before[test_key].replace(leaves.TONAL_TEST_STALE_LINE.encode('utf-8'), b'', 1), now[test_key])
            self.assertEqual(3, now[test_key].decode('utf-8').count('@Test'))
            self.assertNotIn(b'resolveTonalPillTabVerticalInset', now[test_key])
            for name in ('resolveTonalPillTabDefaultLabelFontSize', 'resolveTonalPillTabCornerRadius',
                         'resolveTonalPillTabItemHorizontalPadding', 'resolveTonalPillTabDefaultHeight'):
                self.assertIn(name.encode('utf-8'), now[test_key])
        # the emitted test is what the producer wrote, the same function the producer calls
        self.assertEqual(now[test_key], self.test_out['controls'][test_key])
        # nothing compiled calls the removed function any more
        for root in (REPO / 'desktop/src',):
            for path in sorted(root.rglob('*.kt')):
                self.assertNotIn('resolveTonalPillTabVerticalInset', path.read_text(encoding='utf-8'), path.name)
        for producer in self.out:
            for path, raw in self.out[producer].items():
                if path.endswith('.kt'):
                    self.assertNotIn(b'resolveTonalPillTabVerticalInset', raw, producer + ':' + path)
        for producer in self.test_out:
            for path, raw in self.test_out[producer].items():
                if path.endswith('.kt'):
                    self.assertNotIn(b'resolveTonalPillTabVerticalInset', raw, producer + ':' + path)

    def test_stale_test_override_fails_closed_on_drift(self):
        text = tabs.fixed_sources()[leaves.TONAL_TEST_PATH]
        self.assertEqual(leaves.TONAL_TEST_SHA256, sha(text.encode('utf-8')))
        self.assertEqual(text, leaves.tonal_test_source(REPO, 'some/other/Test.kt', text))
        adapted = leaves.tonal_test_source(REPO, leaves.TONAL_TEST_PATH, text)
        self.assertEqual(text.replace(leaves.TONAL_TEST_STALE_LINE, '', 1), adapted)
        for foreign in (text + '\n', text.replace('assertEquals(4.dp,', 'assertEquals(5.dp,'), adapted, ''):
            with self.subTest(foreign=sha(foreign.encode('utf-8'))[:8]):
                with self.assertRaisesRegex(ValueError, 'tonal test predecessor changed'):
                    leaves.tonal_test_source(REPO, leaves.TONAL_TEST_PATH, foreign)
        raw = tabs.fixed_sources()[leaves.TONAL_PATH].encode('utf-8')
        row = {'path': leaves.TONAL_PATH}
        self.assertEqual((raw, row, 'c', 'm'), leaves.retained_original_override(REPO, BASE + 'other.kt', raw, row, 'c', 'm'))
        replaced = leaves.retained_original_override(REPO, leaves.TONAL_PATH, raw, row, 'c', 'm')
        self.assertEqual(self.archive['AppTonalPillTabPolicy.kt'], replaced[0])
        self.assertEqual((leaves.COMMIT, leaves.MANIFEST_SHA256), replaced[2:])
        for foreign in (b'', raw + b'\n', self.archive['AppTonalPillTabPolicy.kt']):
            with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                leaves.retained_original_override(REPO, leaves.TONAL_PATH, foreign, row, 'c', 'm')

    def test_drag_state_is_the_whole_v046_file_with_only_the_four_existing_bindings(self):
        key = 'generated/com/android/purebilibili/core/ui/animation/DampedDragAnimation.kt'
        text = lf(self.out['shared'][key]).decode('utf-8')
        original = self.archive['DampedDragAnimation.kt'].decode('utf-8')
        expected = original
        for before, after in DAMPED_BINDINGS:
            self.assertEqual(1, expected.count(before), before)
            expected = expected.replace(before, after)
        header = ('// OriginalSource: ' + CANONICAL['DampedDragAnimation.kt'] + '\n// OriginalSHA256: '
                  + sha(original.encode('utf-8')) + '\n// OriginalCommit: ' + leaves.COMMIT + '\n')
        self.assertEqual(header + expected, text)
        self.assertNotIn('android.os.SystemClock', text)
        self.assertNotIn('kotlinx.coroutines.android', text)
        self.assertNotRegex(text, r'(?m)^import android\.')
        inventory = json.loads(self.out['shared']['source-inventory.json'])
        rows = [row for row in inventory['sources'] if row['path'] == CANONICAL['DampedDragAnimation.kt']]
        self.assertEqual(1, len(rows))
        row = rows[0]
        self.assertEqual('direct', row['mode'])
        self.assertEqual([dict(original=before, replacement=after, count=1) for before, after in DAMPED_BINDINGS], row['adaptations'])
        self.assertEqual(leaves.shared_identity(REPO, CANONICAL['DampedDragAnimation.kt']), row['sourceIdentity'])
        self.assertEqual(sha(self.archive['DampedDragAnimation.kt']), row['sha256LF'])
        proof = row['wholeOriginalToPlatform']
        self.assertEqual(sha(self.archive['DampedDragAnimation.kt']), proof['beforeSha256LF'])
        self.assertIn(row['sourceIdentity'], inventory['sourceOverrides'])

    def test_each_layer_changes_only_its_own_outputs(self):
        no_op = lambda repo, path, previous: previous
        # components
        with tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
            base = Path(tmp)
            components.generate(REPO, base / 'now', False, base / 'now-t')
            with mock.patch.object(components, '_leaf_b_source', no_op):
                components.generate(REPO, base / 'old', False, base / 'old-t')
            now, old = tree(base / 'now'), tree(base / 'old')
            self.assertEqual(sorted(now), sorted(old))
            self.assertEqual(sorted(CANONICAL[name].split('/main/java/')[1] for name in leaves.COMPONENT_FILES),
                             sorted(path for path in now if now[path] != old[path]))
            self.assertEqual(tree(base / 'now-t'), tree(base / 'old-t'))
            self.assertEqual(now, self.out['components'])
        # preferences
        with tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
            base = Path(tmp)
            preferences.generate(REPO, base / 'now', False)
            with mock.patch.object(preferences, '_leaf_b_source', no_op):
                preferences.generate(REPO, base / 'old', False)
            now, old = tree(base / 'now'), tree(base / 'old')
            self.assertEqual(sorted(now), sorted(old))
            self.assertEqual(sorted(CANONICAL[name].split('/main/java/')[1] for name in leaves.PREFERENCE_FILES),
                             sorted(path for path in now if now[path] != old[path]))
            self.assertEqual(now, self.out['preferences'])
        # shared liquid tabs: one generated file and the inventory record differ, the app-level drag state does not
        with tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
            base = Path(tmp)
            shared.generate(REPO, base / 'now')
            with mock.patch.object(shared, '_leaf_b_source', no_op), mock.patch.object(shared, '_leaf_b_identity', lambda repo, path: None):
                shared.generate(REPO, base / 'old')
            now, old = tree(base / 'now'), tree(base / 'old')
            self.assertEqual(sorted(now), sorted(old))
            drag = 'generated/com/android/purebilibili/core/ui/animation/DampedDragAnimation.kt'
            self.assertEqual(sorted([drag, 'source-inventory.json']), sorted(path for path in now if now[path] != old[path]))
            self.assertIn(b'LiquidIndicatorVelocity', now[drag])
            self.assertNotIn(b'LiquidIndicatorVelocity', old[drag])
            now_inventory, old_inventory = json.loads(now['source-inventory.json']), json.loads(old['source-inventory.json'])
            self.assertEqual([row['path'] for row in now_inventory['sources']], [row['path'] for row in old_inventory['sources']])
            differing = [new['path'] for new, prev in zip(now_inventory['sources'], old_inventory['sources']) if new != prev]
            self.assertEqual([CANONICAL['DampedDragAnimation.kt']], differing)
            self.assertEqual(now, self.out['shared'])

    def test_every_layer_asserts_its_predecessor_and_leaves_other_paths_alone(self):
        for name in leaves.CHAIN_FILES:
            path = CANONICAL[name]
            predecessor = vendored(name)
            with self.subTest(name=name):
                self.assertEqual(sha(predecessor.encode('utf-8')), leaves.PREDECESSOR_SHA256[name])
                self.assertEqual(self.archive[name].decode('utf-8'), leaves.chain_source(REPO, path, predecessor))
                self.assertEqual(self.archive[name].decode('utf-8'), leaves.chain_source(REPO, path, predecessor.replace('\n', '\r\n')))
                for foreign in (predecessor + '\n', '', self.archive[name].decode('utf-8'), predecessor + ' '):
                    with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                        leaves.chain_source(REPO, path, foreign)
        self.assertEqual('anything', leaves.chain_source(REPO, BASE + 'AppShapes.kt', 'anything'))
        self.assertEqual(sorted(leaves.CHAIN_PATHS), sorted(CANONICAL[name] for name in leaves.CHAIN_FILES))
        damped = CANONICAL['DampedDragAnimation.kt']
        self.assertEqual(self.archive['DampedDragAnimation.kt'].decode('utf-8'), leaves.shared_source(REPO, damped, vendored('DampedDragAnimation.kt')))
        for foreign in (vendored('DampedDragAnimation.kt') + '\n', self.archive['DampedDragAnimation.kt'].decode('utf-8')):
            with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                leaves.shared_source(REPO, damped, foreign)
        self.assertEqual('anything', leaves.shared_source(REPO, BASE + 'AppShapes.kt', 'anything'))
        self.assertIsNone(leaves.shared_identity(REPO, BASE + 'AppShapes.kt'))
        identity = leaves.shared_identity(REPO, damped)
        self.assertEqual(('pinnedCommit', 'originalPath', 'path', 'sha256Bytes', 'gitBlobOid', 'bytes'), tuple(identity))
        self.assertEqual(self.manifest['files'][5]['gitBlob'], identity['gitBlobOid'])

    def test_the_hooks_sit_after_every_earlier_layer_of_each_producer(self):
        text = {name: (REPO / 'desktop' / path).read_text(encoding='utf-8') for name, path in PRODUCERS.items()}
        producer = text['components']
        loop_at = producer.index('    for path in DIRECT:\n')
        loop = producer[loop_at:producer.index('    for path in ADAPTED:', loop_at)]
        part_a = ('original = _leaf_source(repo, path, _theme_chrome_source(repo, path, _optical_source(repo, path, '
                  '_chrome_source(repo, path, _liquid_source(repo, path, host.read(repo, path))))))\n')
        self.assertIn(part_a + '        original = _leaf_b_source(repo, path, original)\n', loop)
        self.assertIn('or path in _leaf_chain or path in _leaf_b_chain or path == _optical_surface) and path not in REUSED:', loop)
        self.assertEqual(1, producer.count('_leaf_b_source(repo'))
        generate = producer[producer.index('def generate('):]
        self.assertLess(generate.index('_validate_leaf_outputs(repo, output, test_output)'),
                        generate.index('_validate_leaf_b_outputs(repo, output, test_output)'))
        self.assertLess(generate.index('_validate_leaf_b_outputs(repo, output, test_output)'), generate.index('host = helper(repo)'))
        self.assertEqual(1, producer.count('from v046_design_system_leaves_b import '))
        producer = text['preferences']
        self.assertEqual(2, producer.count('_leaf_b_source(repo, path, host.read(repo, path))'))
        self.assertEqual(1, producer.count('if standalone or path in _leaf_b_chain: result.append(host.write(output, path, original, original))'))
        generate = producer[producer.index('def generate('):]
        self.assertLess(generate.index('_validate_leaf_b_outputs(repo, output)'), generate.index('host = helper(repo)'))
        self.assertEqual(1, producer.count('from v046_design_system_leaves_b import '))
        # the selection binding runs after the layer, on the v0.4.6 bytes
        adapted_loop = generate[generate.index('    for path in ADAPTED:'):generate.index('    for path in POLICIES:')]
        self.assertLess(adapted_loop.index('_leaf_b_source'), adapted_loop.index('adapt(path, original, host)'))
        producer = text['shared']
        # chrome glass tuning (a disjoint path set) wraps this layer as the last one; the leaf B hook stays inside it
        self.assertIn(' return glass_tuning.selected_source(repo,path,_leaf_b_source(repo,path,selected_shared_source(repo,path,_previous_native_source(repo,path,previous))))\n', producer)
        self.assertIn(' return glass_tuning.identity_for(repo,path) or _leaf_b_identity(repo,path) or shared_identity(repo,path) or _previous_native_identity(repo,path)\n', producer)
        self.assertEqual(1, producer.count('from v046_design_system_leaves_b import '))
        self.assertLess(producer.index('from v042_search_layout import selected_shared_source, shared_identity'),
                        producer.index('from v046_design_system_leaves_b import '))
        self.assertLess(producer.index(' REPO=Path(repo);HERE=Path(output)\n'), producer.index(' _validate_leaf_b_outputs(REPO,HERE)\n'))
        self.assertEqual(1, producer.count('if shared_identity(REPO,path) is not None or _leaf_b_identity(REPO,path) is not None:'))
        tabs_text = (TOOLS / 'v037_shared_tabs_progress.py').read_text(encoding='utf-8')
        self.assertLess(tabs_text.index('validate_leaf_b_outputs('), tabs_text.index(' originals=fixed_sources()\n', tabs_text.index('def emit_control_foundations')))
        self.assertLess(tabs_text.index('retained_original_override(Path(__file__)'), tabs_text.index('leaf_b_override(Path(__file__)'))
        self.assertLess(tabs_text.index('leaf_b_override(Path(__file__)'), tabs_text.index("text=effective_raw.decode('utf-8')"))
        self.assertLess(tabs_text.index('text=originals[row[\'path\']]\n    from v046_design_system_leaves_b import tonal_test_source'),
                        tabs_text.index("before='''        val normalized"))
        self.assertEqual(1, tabs_text.count('tonal_test_source('))
        self.assertEqual(1, text['controls'].count('emit_control_foundations(OUTPUT,OUTPUTS,test_output)'))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        nested = REPO / leaves.ARCHIVE / 'child'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                leaves.validate_outputs(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                leaves.validate_outputs(REPO, self.root / 'ok', nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                leaves.validate_outputs(REPO, REPO)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                components.generate(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                preferences.generate(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                shared.generate(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                tabs.emit_control_foundations(nested, [], None)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                tabs.emit_control_foundations(self.root / 'ok-2', [], nested)
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / leaves.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    def test_a_rerun_is_deterministic(self):
        for name in PRODUCERS:
            with self.subTest(producer=name), tempfile.TemporaryDirectory(prefix='bp-dsb-') as tmp:
                output, tests = run_cli(name, Path(tmp), 7)
                self.assertEqual(self.out[name], tree(output))
                if self.test_out[name]:
                    self.assertEqual(self.test_out[name], tree(tests))

    # ---- ownership and wiring ------------------------------------------------------------------------------

    def test_gradle_excludes_the_three_direct_copies_once_and_feeds_the_archive_to_the_four_owning_tasks(self):
        build = self.build
        prepared = prepare_block(build)
        model = gate.parse_gradle(build)
        for name in leaves.EXCLUDED_NOW:
            line = 'exclude(canonicalOriginalIdentity("' + CANONICAL[name] + '"))'
            with self.subTest(exclude=name):
                self.assertEqual(1, build.count(line), line)
                self.assertEqual(1, prepared.count(line), line)
                self.assertEqual(1, model.sync_excludes.count(CANONICAL[name]))
        # the tonal policy was already excluded by the v037 closure; the other two files are not direct-mode
        tonal = 'exclude(canonicalOriginalIdentity("' + CANONICAL['AppTonalPillTabPolicy.kt'] + '"))'
        self.assertEqual(1, build.count(tonal))
        for name in ('AppSelectionPreferenceComponents.kt', 'DampedDragAnimation.kt'):
            self.assertNotIn(CANONICAL[name], model.sync_excludes)
        block_at = build.index('tasks.matching { it.name in setOf("extractUpstreamComponents", "extractUpstreamPreferences", ')
        block = build[block_at:build.index('\n}\n', block_at)]
        for task in TASKS:
            self.assertIn('"' + task + '"', block)
            self.assertEqual(1, build.count('val ' + task + ' by tasks.registering(Exec::class) {'), task)
        for literal in ('inputs.file("tools/v046_design_system_leaves_b.py")',
                        'inputs.dir("upstream-slices/v046-design-system-leaves-b")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual(4, len(re.findall(r'"extract\w+"', block)))
        # the earlier part A wiring is untouched
        self.assertEqual(1, build.count('inputs.dir("upstream-slices/v046-design-system-leaves-a")'))
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'design-system-leaves' in row])
        self.assertNotIn('"--standalone"', build)

    def test_upstream_sources_json_is_untouched_and_the_vendored_pins_match_the_manifest(self):
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
        by_path = {row['path']: row for row in sources}
        modes = {'AppPreferenceComponents.kt': 'direct', 'AppCard.kt': 'direct', 'AppDesktopInteraction.kt': 'direct',
                 'AppTonalPillTabPolicy.kt': 'direct', 'AppSelectionPreferenceComponents.kt': 'platform-adapter-reference',
                 'DampedDragAnimation.kt': 'extracted'}
        for name, mode in modes.items():
            row = by_path[CANONICAL[name]]
            self.assertEqual(mode, row.get('mode', 'direct'), name)
            self.assertEqual(leaves.PREDECESSOR_SHA256[name], row['sha256'], name)
            self.assertEqual(leaves.PREDECESSOR_SHA256[name], sha(lf((REPO / CANONICAL[name]).read_bytes())), name)
            self.assertNotEqual(self.archive[name], (REPO / CANONICAL[name]).read_bytes().replace(b'\r\n', b'\n'), name)
        self.assertEqual({row['replacedBy'] for row in self.manifest['replacedVendoredCopies']}, set(leaves.EXCLUDED_NOW))
        for row in self.manifest['replacedVendoredCopies']:
            self.assertEqual(by_path[row['path']]['sha256'], row['vendoredLfSha256'])

    def test_no_compiled_direct_copy_remains_and_every_top_level_name_has_one_producer_output(self):
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
        excluded = set(gate.parse_gradle(self.build).sync_excludes)
        compiled_direct = {row['path'] for row in sources if row.get('mode', 'direct') == 'direct'} - excluded
        for name in leaves.MAIN_FILES:
            self.assertNotIn(CANONICAL[name], compiled_direct, name)
        scanned = {}
        for producer, files in self.out.items():
            for path, raw in files.items():
                if path.endswith('.kt'):
                    scanned[producer + ':' + path] = raw
        for path in sorted(compiled_direct):
            if path.endswith('.kt'):
                scanned['direct:' + path] = (REPO / path).read_bytes()
        for root in ('desktop/src/main', 'desktop/src/test'):
            for path in sorted((REPO / root).rglob('*.kt')):
                scanned['desktop:' + path.relative_to(REPO).as_posix()] = path.read_bytes()
        for name, wanted in TOP_LEVEL.items():
            package = leaves.PACKAGES[name]
            for visibility, kind, declared in sorted(wanted):
                if visibility == 'private':
                    continue
                owners = []
                for label, raw in scanned.items():
                    if declared.encode('utf-8') not in raw:
                        continue
                    pack, decls = declarations(raw.decode('utf-8'), label)
                    if pack == package and any(d[2] == declared and d[0] != 'private' for d in decls):
                        owners.append(label)
                with self.subTest(file=name, declared=declared):
                    self.assertEqual(1, len(owners), owners)
                    self.assertTrue(owners[0].endswith('/' + name), owners)

    def test_every_project_import_of_the_carried_files_has_a_definer_in_the_compile_inputs(self):
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
        excluded = set(gate.parse_gradle(self.build).sync_excludes)
        pool = [raw.decode('utf-8') for files in self.out.values() for path, raw in files.items() if path.endswith('.kt')]
        pool += [(REPO / row['path']).read_bytes().decode('utf-8') for row in sources
                 if row.get('mode', 'direct') == 'direct' and row['path'].endswith('.kt') and row['path'] not in excluded]
        pool += [path.read_bytes().decode('utf-8') for path in sorted((REPO / 'desktop/upstream-slices').glob('*/*.kt'))]
        pool += [path.read_bytes().decode('utf-8') for path in sorted((REPO / 'desktop/src/main').rglob('*.kt'))]
        index = {}
        for text in pool:
            package, decls = declarations(text)
            index.setdefault(package, set()).update(d[2] for d in decls if d[0] != 'private')
        for name in leaves.MAIN_FILES:
            info = gate.parse_kotlin(self.archive[name].decode('utf-8'), name, 'carried', 'main')
            for imp in info.imports:
                if not imp.path.startswith('com.android.purebilibili') or imp.star:
                    continue
                package, simple = imp.path.rsplit('.', 1)
                with self.subTest(file=name, imported=imp.path):
                    self.assertIn(simple, index.get(package, set()), imp.path)
        # the qualified names the files spell out (AppCard, AppSelectionPreferenceComponents, AppDesktopInteraction)
        for name, qualified in (('AppCard.kt', ('com.android.purebilibili.core.ui.AppSurfaceTokens',
                                                'com.android.purebilibili.core.ui.AppContentColors')),
                                ('AppSelectionPreferenceComponents.kt', ('com.android.purebilibili.core.ui.ModalWindowBlurBehindEffect',))):
            for symbol in qualified:
                package, simple = symbol.rsplit('.', 1)
                with self.subTest(file=name, qualified=symbol):
                    self.assertIn(symbol, self.archive[name].decode('utf-8'))
                    self.assertIn(simple, index.get(package, set()))
        # DampedDragAnimation waits on the motion constants and the velocity worker of the carried v0.4.6 kernels
        motion = [raw for path, raw in self.out['components'].items() if path.endswith('/animation/LiquidIndicatorMotion.kt')]
        self.assertEqual(1, len(motion))
        for member in (b'BloomWaitMillis', b'SettleWaitMillis', b'SettleTolerance', b'fun positionSpec(', b'fun pressSpec(',
                       b'fun scaleXSpec(', b'fun scaleYSpec('):
            self.assertIn(member, motion[0])
        velocity = (REPO / 'desktop/upstream-slices/v040-liquid-value-controls/LiquidIndicatorVelocity.kt').read_bytes()
        self.assertEqual(git_blob(BASE + 'animation/LiquidIndicatorVelocity.kt'), velocity)
        drag = self.archive['DampedDragAnimation.kt'].decode('utf-8')
        for member in ('updateTarget(', 'snapTo(', '.value'):
            self.assertIn(member, drag)
            self.assertIn(member.replace('(', '').replace('.', ''), velocity.decode('utf-8'))
        # v0.4.6 materially needs withTimeoutOrNull from kotlinx.coroutines (not a project symbol)
        self.assertIn('import kotlinx.coroutines.withTimeoutOrNull\n', drag)

    def test_inventory_and_catalog_register_the_archive(self):
        self.assertIn(leaves.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(leaves.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(leaves.ARCHIVE + '/')]
        self.assertEqual(sorted(row['path'] for row in self.manifest['files']), sorted(row['path'] for row in rows))
        self.assertEqual(6, len(rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-design-system-leaves-b'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(leaves.COMMIT, row['upstreamCommit'])
            self.assertEqual('adaptedSource', row['kind'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
            self.assertEqual(sha(self.archive[Path(row['localPath']).name]), row['sha256'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == leaves.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({leaves.ARCHIVE + '/' + name for name in leaves.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(leaves.ARCHIVE + '/')])
        self.assertEqual(hashlib.sha256(lf((TOOLS / 'compatibility-source-inputs.json').read_bytes())).hexdigest(),
                         sync.COMPATIBILITY_CATALOG_SHA256)
        # The catalog TOTALS change with every slice and are pinned once, by test_sync_upstream.py; this slice only pins
        # its own rows above (a total here made the test depend on merge order).
        self.assertIn(leaves.ARCHIVE, self.catalog['buildInputRoots'])

    # ---- source hygiene and the Kotlin regression class ----------------------------------------------------

    def test_emitted_sources_and_the_regression_class_keep_balanced_delimiters(self):
        for producer, name in (('components', 'AppCard.kt'), ('components', 'AppDesktopInteraction.kt'),
                               ('preferences', 'AppPreferenceComponents.kt'), ('preferences', 'AppSelectionPreferenceComponents.kt'),
                               ('controls', 'AppTonalPillTabPolicy.kt')):
            text = lf(self.emitted(producer, name)).decode('utf-8')
            self.assertNotIn('\r', text)
            balanced(self, text)
        drag = self.out['shared']['generated/com/android/purebilibili/core/ui/animation/DampedDragAnimation.kt'].decode('utf-8')
        self.assertNotIn('\r', drag)
        balanced(self, drag)
        tonal_test = self.test_out['controls']['com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicyTest.kt'].decode('utf-8')
        balanced(self, tonal_test)
        text = HAND_WRITTEN_TEST.read_bytes().decode('utf-8')
        self.assertNotIn('\r', text)
        balanced(self, text)
        self.assertEqual(6, len(re.findall(r'@Test\s+fun ', text)))
        self.assertIn('package com.bilipai.desktop.ui\n', text)
        self.assertIn('class DesktopDesignSystemLeavesBTest {', text)
        for forbidden in ('java.io.File', 'Window(', 'application {', 'HttpClient', 'java.net', 'System.getenv', 'ImageComposeScene'):
            self.assertNotIn(forbidden, text)
        # every project import of the regression class resolves to a name some emitted or carried file declares
        declared = {}
        for files in self.out.values():
            for path, raw in files.items():
                if path.endswith('.kt'):
                    package, decls = declarations(raw.decode('utf-8'))
                    declared.setdefault(package, set()).update(d[2] for d in decls if d[0] != 'private')
        imported = re.findall(r'^import (com\.android\.purebilibili\.[\w.]+)\.(\w+)$', text, re.M)
        self.assertGreaterEqual(len(imported), 15)
        for package, simple in imported:
            with self.subTest(imported=package + '.' + simple):
                self.assertIn(simple, declared.get(package, set()))

    def test_the_consumer_table_now_reports_the_six_files_at_the_v046_bytes(self):
        table = table_tool.build_table(REPO)
        files = {entry['path']: entry for entry in table['files']}
        for name in leaves.MAIN_FILES:
            with self.subTest(file=name):
                self.assertEqual('current', files[CANONICAL[name]]['status'])
                self.assertEqual([], files[CANONICAL[name]]['membersMissing'])
                copies = [copy for copy in files[CANONICAL[name]]['carriedCopies'] if copy['bytes'] == 'same-bytes']
                self.assertEqual([leaves.ARCHIVE + '/' + name], [copy['file'] for copy in copies])
                self.assertEqual({'whole'}, {copy['use'] for copy in copies})
        # consumers keep importing the same symbols; none of them is re-pinned by this slice (consumer rule)
        self.assertIn('AppSingleChoicePreference', files[CANONICAL['AppSelectionPreferenceComponents.kt']]['importedSymbols'])
        self.assertGreater(files[CANONICAL['AppPreferenceComponents.kt']]['consumerFiles'], 50)

    def test_the_tools_are_lf_only(self):
        for path in (TOOLS / 'v046_design_system_leaves_b.py', Path(__file__), TOOLS / 'extract-upstream-components.py',
                     TOOLS / 'extract-upstream-preferences.py', TOOLS / 'extract-upstream-shared-liquid-tabs.py',
                     TOOLS / 'v037_shared_tabs_progress.py', HAND_WRITTEN_TEST, REPO / 'desktop/build.gradle.kts',
                     TOOLS / 'compatibility-source-inputs.json', TOOLS / 'tests/test_extract_upstream_preferences.py'):
            self.assertNotIn(b'\r', path.read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
