"""v0.4.6 liquid glass tuning values on the shared-liquid-tabs producer (source checks only; nothing is compiled)."""
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_chrome_glass_tuning as glass
from v025_source_paths import canonical_source


def load(filename, name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PRODUCER = load('extract-upstream-shared-liquid-tabs.py', 'glass_tuning_liquid_tabs_producer')
sync = load('sync-upstream.py', 'glass_tuning_sync_upstream')

PIN = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
UPSTREAM_CHANGE = '4e15f578b4eccd79d2762ebb7056f63c421e3635'
COMPONENTS = 'com/android/purebilibili/feature/home/components/'
STORE = 'com/android/purebilibili/core/store/'
HAND_WRITTEN_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopChromeGlassTuningTest.kt'
SHELL = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def git(*args):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', *args], cwd=str(REPO), capture_output=True, check=True).stdout


def blob_oid(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def body(raw):
    text = raw.decode('utf-8')
    return text[text.index('package '):]


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def declared(text, path):
    """Package and the simple names of the non-private top-level declarations (a private one is file-local)."""
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, {item.name for item in info.decls if item.visibility != 'private'}


def parameter_lists(source, function):
    """Parameter text of every `fun function(` overload, with balanced parentheses."""
    found = []
    for match in re.finditer(r'\bfun ' + re.escape(function) + r'\(', source):
        depth, index = 1, match.end()
        while depth:
            depth += (source[index] == '(') - (source[index] == ')')
            index += 1
        found.append(source[match.end():index - 1])
    return found


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


class V046ChromeGlassTuningSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-glass-')
        cls.root = Path(cls.temporary.name)
        PRODUCER.generate(REPO, cls.root / 'with')
        # The same producer with only this slice's layer removed: the exact v0.2.5 pinned glass values.
        stub = SimpleNamespace(selected_source=lambda repo, path, previous: previous,
                               identity_for=lambda repo, path: None,
                               validate_outputs=glass.validate_outputs, test_files=glass.test_files,
                               proof_text=glass.proof_text)
        with mock.patch.object(PRODUCER, 'glass_tuning', stub):
            PRODUCER.generate(REPO, cls.root / 'without')
        cls.with_tree = tree(cls.root / 'with')
        cls.without_tree = tree(cls.root / 'without')
        cls.archive = {p.name: p.read_bytes() for p in (REPO / glass.ARCHIVE).iterdir() if p.is_file()}
        cls.manifest = json.loads(cls.archive['manifest.json'])
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.proof = json.loads(cls.with_tree['v046-chrome-glass-tuning-proof.json'])

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def generated(self, name, tree_=None):
        return (tree_ or self.with_tree)['generated/' + COMPONENTS + name]

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        self.assertEqual(sorted(glass.EXPECTED | {'LICENSE', 'manifest.json'}), sorted(self.archive))
        self.assertEqual(glass.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        for leaf in sorted(self.archive):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-glass-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / glass.ARCHIVE, repo / glass.ARCHIVE)
                target = repo / glass.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    glass.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-glass-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / glass.ARCHIVE, repo / glass.ARCHIVE)
            (repo / glass.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                glass.fixed(repo)
            (repo / glass.ARCHIVE / 'Extra.kt').unlink()
            (repo / glass.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                glass.fixed(repo)

    def test_leaves_are_the_v046_blobs_and_commit_4e15f57_is_the_only_step_from_the_pin(self):
        for row in self.manifest['files']:
            with self.subTest(path=row['path']):
                leaf = self.archive[row['filename']]
                upstream = git('cat-file', 'blob', glass.COMMIT + ':' + row['path'])
                self.assertEqual(upstream, leaf)
                self.assertEqual(row['gitBlob'], blob_oid(leaf))
                self.assertEqual(row['gitBlob'], git('rev-parse', glass.COMMIT + ':' + row['path']).decode().strip())
                self.assertEqual(row['gitBlob'], git('rev-parse', UPSTREAM_CHANGE + ':' + row['path']).decode().strip())
                before = git('rev-parse', UPSTREAM_CHANGE + '^:' + row['path']).decode().strip()
                self.assertEqual(before, git('rev-parse', PIN + ':' + row['path']).decode().strip())
                self.assertNotEqual(before, row['gitBlob'])
        self.assertEqual(glass.COMMIT, self.manifest['commit'])
        self.assertEqual(glass.UPSTREAM_PATHS, {row['filename']: row['path'] for row in self.manifest['files']})

    def test_predecessors_are_the_pinned_v025_bodies_and_stale_or_advanced_inputs_fail_closed(self):
        for path in glass.MAIN_PATHS:
            with self.subTest(path=path):
                pinned = git('cat-file', 'blob', PIN + ':' + path).decode('utf-8').replace('\r\n', '\n')
                canonical = canonical_source(REPO, path).read_text(encoding='utf-8').replace('\r\n', '\n')
                self.assertEqual(pinned, canonical)
                self.assertEqual(glass.PREDECESSOR_SHA256[path], sha(canonical.encode('utf-8')))
                advanced, proof = glass.advance(REPO, path, canonical)
                self.assertEqual(self.archive[Path(path).name].decode('utf-8'), advanced)
                self.assertEqual(canonical, glass.restore(advanced, proof))
                self.assertEqual(canonical, glass.restore(advanced, proof))
                for foreign in (canonical + '\n', canonical.replace('package ', 'package  ', 1), advanced, ''):
                    with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                        glass.advance(REPO, path, foreign)
                with self.assertRaisesRegex(ValueError, 'output differs'):
                    glass.restore(canonical, proof)
        self.assertEqual('same', glass.selected_source(REPO, 'app/src/main/java/Other.kt', 'same'))
        self.assertIsNone(glass.identity_for(REPO, 'app/src/main/java/Other.kt'))
        with self.assertRaises(ValueError):
            glass.advance(REPO, 'app/src/main/java/Other.kt', 'same')

    def test_a_drifted_or_duplicated_anchor_fails_before_any_output(self):
        path = glass.TUNING
        canonical = canonical_source(REPO, path).read_text(encoding='utf-8').replace('\r\n', '\n')
        hunks = glass.HUNKS[path]
        for index in range(len(hunks)):
            drifted = list(hunks)
            drifted[index] = dict(hunks[index], before=hunks[index]['before'] + 'DRIFT')
            with self.subTest(hunk=hunks[index]['id']), mock.patch.dict(glass.HUNKS, {path: tuple(drifted)}):
                with self.assertRaisesRegex(ValueError, 'anchor changed: ' + hunks[index]['id']):
                    glass.advance(REPO, path, canonical)
            with self.assertRaisesRegex(ValueError, 'anchor changed: ' + hunks[index]['id']):
                glass.replay(canonical + canonical, [hunks[index]])
        with self.assertRaisesRegex(ValueError, 'receipt changed'):
            glass.unreplay(canonical, hunks)

    def test_only_the_two_glass_files_differ_and_each_emits_the_whole_v046_original(self):
        base = {p: raw for p, raw in self.without_tree.items() if p.startswith('generated/')}
        new = {p: raw for p, raw in self.with_tree.items() if p.startswith('generated/')}
        self.assertEqual(set(base), set(new))
        self.assertEqual({'generated/' + COMPONENTS + 'LiquidGlassTuning.kt',
                          'generated/' + COMPONENTS + 'BottomBarGlassMaterialPolicy.kt'},
                         {p for p in base if base[p] != new[p]})
        for path in glass.MAIN_PATHS:
            name = Path(path).name
            emitted = self.generated(name).decode('utf-8')
            before = self.generated(name, self.without_tree).decode('utf-8')
            self.assertIn('// OriginalSource: ' + path + '\n', emitted)
            self.assertIn('// OriginalSHA256: ' + sha(self.archive[name]) + '\n', emitted)
            self.assertIn('// OriginalCommit: ' + glass.COMMIT + '\n', emitted)
            self.assertNotIn('OriginalCommit', before)
            self.assertEqual(self.archive[name].decode('utf-8'), body(self.generated(name)))
            self.assertEqual(canonical_source(REPO, path).read_text(encoding='utf-8').replace('\r\n', '\n'), body(self.generated(name, self.without_tree)))
            self.assertEqual(body(self.generated(name, self.without_tree)),
                             glass.unreplay(body(self.generated(name)), glass.HUNKS[path]))

    def test_source_inventory_changes_only_the_two_hashes_and_their_source_identities(self):
        base = json.loads(self.without_tree['source-inventory.json'])
        new = json.loads(self.with_tree['source-inventory.json'])
        self.assertEqual(base['sourceCount'], new['sourceCount'])
        identities = []
        for old, current in zip(base['sources'], new['sources']):
            self.assertEqual(old['path'], current['path'])
            if old['path'] in glass.MAIN_PATHS:
                self.assertEqual({'sha256LF', 'sourceIdentity'}, {k for k in set(old) | set(current) if old.get(k) != current.get(k)})
                self.assertEqual(glass.identity_for(REPO, old['path']), current['sourceIdentity'])
                self.assertEqual('direct', current['mode'])
                self.assertEqual([], current['adaptations'])
                identities.append(current['sourceIdentity'])
            else:
                self.assertEqual(old, current, old['path'])
        self.assertEqual(2, len(identities))
        self.assertEqual(base['sourceOverrides'], [o for o in new['sourceOverrides'] if o not in identities])
        self.assertEqual(identities, [o for o in new['sourceOverrides'] if o in identities])

    def test_receipt_lists_every_visual_parameter_and_recovers_the_pinned_files(self):
        proof = self.proof
        self.assertEqual((glass.COMMIT, glass.MANIFEST_SHA256, glass.ARCHIVE), (proof['upstreamCommit'], proof['sourceManifestSHA256'], proof['archive']))
        self.assertEqual(UPSTREAM_CHANGE, proof['changedByUpstreamCommit'])
        self.assertFalse(proof['kotlinCompiled'])
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertEqual(['BottomBarGlassMaterialPolicyTest'], proof['notCarried'])
        self.assertEqual(list(glass.MAIN_PATHS), [row['path'] for row in proof['files']])
        covered = []
        for row in proof['files']:
            name = Path(row['path']).name
            self.assertTrue(row['wholeOriginal'])
            self.assertEqual(0, row['windowsAdaptations'])
            self.assertEqual(sha(self.archive[name]), row['originalBytesSHA256'])
            self.assertEqual(body(self.generated(name, self.without_tree)), glass.restore(body(self.generated(name)), row))
            self.assertEqual([h['id'] for h in glass.HUNKS[row['path']]], [h['id'] for h in row['hunks']])
            covered += [h['id'] for h in row['hunks']]
        changes = proof['visualParameterChanges']
        self.assertEqual(['readability-protection-curve', 'readability-scrim-alpha', 'indicator-chromatic-boost', 'bottom-bar-container-alpha'],
                         [c['id'] for c in changes])
        listed = [h for c in changes for h in c['hunks']]
        self.assertEqual(sorted(covered), sorted(listed))
        self.assertEqual(len(listed), len(set(listed)))
        for change in changes:
            for key in ('file', 'parameter', 'before', 'after'):
                self.assertTrue(change[key].isascii() and change[key], (change['id'], key))
            self.assertIn(change['file'], {'LiquidGlassTuning.kt', 'BottomBarGlassMaterialPolicy.kt'})
        self.assertEqual([c['id'] for c in glass.VISUAL_PARAMETER_CHANGES], [c['id'] for c in changes])

    def test_emitted_kotlin_holds_the_new_values_and_no_trace_of_the_old_curve(self):
        tuning = body(self.generated('LiquidGlassTuning.kt'))
        old_tuning = body(self.generated('LiquidGlassTuning.kt', self.without_tree))
        for token in ('    val readabilityProtection = configuredReadability\n',
                      '    val contentReadabilityScrimAlpha = contentReadabilityBoost * 0.34f\n',
                      '        indicatorChromaticBoost = 1f,\n'):
            self.assertEqual(1, tuning.count(token), token)
            self.assertEqual(0, old_tuning.count(token), token)
        for token in ('frostWeight', 'configuredReadability * configuredReadability', 'A smooth fourth-power response',
                      '0.12f + readabilityProtection', '0.22f', 'midpointLerp(1.20f'):
            self.assertEqual(0, tuning.count(token), token)
            self.assertGreaterEqual(old_tuning.count(token), 1, token)
        material = body(self.generated('BottomBarGlassMaterialPolicy.kt'))
        for token in ('luminance', 'alphaScale', 'isDarkSurface', '0.75f', '0.95f'):
            self.assertEqual(0, material.count(token), token)
            self.assertGreaterEqual(body(self.generated('BottomBarGlassMaterialPolicy.kt', self.without_tree)).count(token), 1, token)
        self.assertEqual(1, material.count('    return surfaceColor.copy(alpha = liquidGlassTuning.surfaceAlpha.coerceIn(0f, 1f))\n'))
        # Everything else the shell consumes keeps its signature: the field names are still read by the pinned chrome.
        for field in ('contentReadabilityBoost', 'contentReadabilityScrimAlpha', 'indicatorChromaticBoost'):
            self.assertEqual(1, len(re.findall(r'(?m)^    val ' + field + r': Float,$', tuning)), field)
        for text in (tuning, material):
            balanced(self, text)

    def test_default_numbers_in_the_upstream_test_follow_from_the_emitted_constants(self):
        tuning = body(self.generated('LiquidGlassTuning.kt'))
        store = self.with_tree['generated/' + STORE + 'SettingsManager.kt'].decode('utf-8')
        settings = re.search(r'data class LiquidGlassAdvancedSettings\((.*?)\n\)', store, re.S).group(1)
        default = float(re.search(r'val contentReadability: Float = ([0-9.]+)f', settings).group(1))
        weight = float(re.search(r'val readabilityWeight = midpointLerp\(1f, ([0-9.]+)f, [0-9.]+f, normalizedProgress\)', tuning).group(1))
        factor = float(re.search(r'val contentReadabilityScrimAlpha = contentReadabilityBoost \* ([0-9.]+)f', tuning).group(1))
        self.assertEqual((0.62, 0.6, 0.34), (default, weight, factor))
        self.assertAlmostEqual(0.12648, weight * default * factor, places=9)
        original_test = self.archive['LiquidGlassTuningTest.kt'].decode('utf-8')
        self.assertIn('assertEquals(0.12648f, tuning.contentReadabilityScrimAlpha, 0.0001f)', original_test)
        self.assertIn('assertEquals(1f, tuning.indicatorChromaticBoost, 0.0001f)', original_test)

    def test_desktop_shell_resolves_the_emitted_tuning_through_the_original_signature(self):
        shell = SHELL.read_text(encoding='utf-8')
        call = re.findall(r'com\.android\.purebilibili\.feature\.home\.components\.resolveLiquidGlassTuning\(\s*'
                          r'progress = it\.liquidGlassProgress, advancedSettings = it\.liquidGlassAdvancedSettings,\s*'
                          r'readabilityMode = it\.liquidGlassReadabilityMode\), preset = it\.bottomBarLiquidGlassPreset\)', shell)
        self.assertEqual(1, len(call))
        self.assertEqual(1, shell.count('resolveLiquidGlassTuning('))
        tuning = body(self.generated('LiquidGlassTuning.kt'))
        signature = ('internal fun resolveLiquidGlassTuning(\n    progress: Float,\n'
                     '    advancedSettings: LiquidGlassAdvancedSettings = LiquidGlassAdvancedSettings(),\n'
                     '    readabilityMode: LiquidGlassReadabilityMode = LiquidGlassReadabilityMode.STABLE,\n'
                     '): LiquidGlassTuning {\n')
        self.assertEqual(1, tuning.count(signature))
        # No desktop-owned Kotlin re-derives the curve, the scrim or the container alpha.
        owned = REPO / 'desktop/src/main/kotlin'
        for path in sorted(owned.rglob('*.kt')):
            text = path.read_text(encoding='utf-8')
            for token in ('fun resolveLiquidGlassTuning', 'fun resolveBottomBarGlassMaterialContainerColor',
                          'readabilityProtection', 'contentReadabilityScrimAlpha =', 'indicatorChromaticBoost ='):
                self.assertNotIn(token, text, path.name)
        dock = (owned / 'com/bilipai/desktop/ui/DesktopOriginalLinkedDockRoot.kt').read_text(encoding='utf-8')
        self.assertIn('liquidGlassTuning: LiquidGlassTuning,', dock)

    def test_the_archive_is_the_only_compiled_definer_and_the_pinned_copies_are_never_copied(self):
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
        for path in glass.MAIN_PATHS:
            rows = [row for row in sources if row['path'] == path]
            self.assertEqual(1, len(rows), path)
            # Only direct-mode rows are copied by prepareUpstreamSources; extracted rows are producer-owned.
            self.assertEqual('extracted', rows[0]['mode'], path)
            self.assertEqual(['stable-shared-liquid-tabs'], rows[0]['features'], path)
            self.assertEqual(0, self.build.count('exclude(canonicalOriginalIdentity("' + path + '"))'), path)
        names = {}
        for name in ('LiquidGlassTuning.kt', 'BottomBarGlassMaterialPolicy.kt'):
            package, found = declared(self.archive[name].decode('utf-8'), name)
            self.assertEqual('com.android.purebilibili.feature.home.components', package)
            self.assertTrue(found)
            names[name] = found
        self.assertFalse(names['LiquidGlassTuning.kt'] & names['BottomBarGlassMaterialPolicy.kt'])
        all_names = set().union(*names.values())
        self.assertTrue({'LiquidGlassTuning', 'resolveLiquidGlassTuning', 'resolveBottomBarGlassMaterialContainerColor'} <= all_names)
        needles = [n.encode('utf-8') for n in all_names]
        mine = {REPO / glass.ARCHIVE / name for name in names}
        hits = []
        for base in (REPO / 'desktop/src/main', REPO / 'desktop/upstream-slices'):
            for path in base.rglob('*.kt'):
                if path in mine:
                    continue
                raw = path.read_bytes()
                if not any(needle in raw for needle in needles):
                    continue
                package, found = declared(raw.decode('utf-8'), path.relative_to(REPO).as_posix())
                if package == 'com.android.purebilibili.feature.home.components' and found & all_names:
                    hits.append((path.relative_to(REPO).as_posix(), sorted(found & all_names)))
        self.assertEqual([], hits)
        # The whole producer output declares each name in exactly one file: the matching emitted original.
        counts = {name: set() for name in all_names}
        for path, raw in self.with_tree.items():
            if not path.startswith('generated/') or not path.endswith('.kt'):
                continue
            if not any(needle in raw for needle in needles):
                continue
            package, found = declared(raw.decode('utf-8'), path)
            for name in all_names & found:
                if package == 'com.android.purebilibili.feature.home.components':
                    counts[name].add(path)
        for leaf, found in names.items():
            for name in found:
                self.assertEqual({'generated/' + COMPONENTS + leaf}, counts[name], name)

    def test_original_tuning_test_is_emitted_to_the_test_root_with_only_three_imports_mapped(self):
        relative = 'test/' + COMPONENTS + 'LiquidGlassTuningTest.kt'
        self.assertEqual([relative], [p for p in self.with_tree if p.startswith('test/')])
        emitted = self.with_tree[relative].decode('utf-8')
        original = self.archive['LiquidGlassTuningTest.kt'].decode('utf-8')
        self.assertEqual(glass.adapt_test(original), emitted)
        self.assertEqual({relative: emitted}, {'test/' + k: v for k, v in glass.test_files(REPO).items()})
        self.assertEqual(glass.TEST_CASES, original.count('\n    @Test\n'))
        self.assertEqual(glass.TEST_CASES, emitted.count('@Test'))
        restored = emitted
        for old, new in glass.TEST_IMPORTS:
            self.assertEqual(1, restored.count(new))
            restored = restored.replace(new, old, 1)
        self.assertEqual(original, restored)
        self.assertNotIn('org.junit', emitted)
        self.assertEqual(['import kotlin.test.Test', 'import kotlin.test.assertEquals', 'import kotlin.test.assertTrue'],
                         sorted(line for line in emitted.splitlines() if line.startswith('import kotlin.test')))
        self.assertEqual(3, len(set(original.splitlines()) - set(emitted.splitlines())))
        self.assertEqual(1, original.count('\nclass LiquidGlassTuningTest {\n'))
        balanced(self, emitted)
        self.assertEqual([], [p for p in self.with_tree if p.startswith('generated/') and p.endswith('Test.kt')])
        with self.assertRaises(ValueError):
            glass.adapt_test(original.replace('@Test', '@Check', 1))
        # BottomBarGlassMaterialPolicyTest needs BottomBar.kt declarations that the Windows producer does not select.
        policy_test = git('cat-file', 'blob', glass.COMMIT + ':app/src/test/java/' + COMPONENTS + 'BottomBarGlassMaterialPolicyTest.kt').decode('utf-8')
        generated = '\n'.join(raw.decode('utf-8') for path, raw in self.with_tree.items() if path.startswith('generated/') and path.endswith('.kt'))
        missing = [n for n in ('shouldUseBottomBarCombinedIndicatorBackdrop', 'shouldUseBottomBarIndicatorLens',
                               'resolveBottomBarEffectiveBackdropPresetProgress', 'resolveBottomBarPresetPanelOffsets')
                   if n in policy_test and 'fun ' + n + '(' not in generated]
        self.assertEqual(['shouldUseBottomBarCombinedIndicatorBackdrop', 'shouldUseBottomBarIndicatorLens',
                          'resolveBottomBarEffectiveBackdropPresetProgress', 'resolveBottomBarPresetPanelOffsets'], missing,
                         'BottomBar.kt now provides what BottomBarGlassMaterialPolicyTest calls: carry that original test too')

    def test_gradle_and_inventory_wire_the_archive_once(self):
        build = self.build
        block = task_block(build, 'extractSharedLiquidTabs')
        for literal in ('inputs.file("tools/v046_chrome_glass_tuning.py")',
                        'inputs.dir("upstream-slices/v046-chrome-glass-tuning")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        test_root = 'kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/shared-liquid-tabs/test")) }'
        self.assertEqual(1, build.count(test_root))
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/shared-liquid-tabs/generated"))'))
        self.assertEqual(1, build.count('generated/shared-liquid-tabs/test'))
        self.assertLess(build.index('val extractSharedLiquidTabs by tasks.registering'), build.index(test_root))
        self.assertIn(glass.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(glass.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(glass.ARCHIVE + '/')]
        self.assertEqual(3, len(rows))
        self.assertEqual(sorted(glass.UPSTREAM_PATHS.values()), sorted(row['path'] for row in rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-chrome-glass-tuning'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(glass.COMMIT, row['upstreamCommit'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == glass.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({glass.ARCHIVE + '/' + name for name in glass.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        self.assertEqual(rows, [row for row in sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(glass.ARCHIVE + '/')])
        raw = (TOOLS / 'compatibility-source-inputs.json').read_bytes()
        self.assertEqual(sync.COMPATIBILITY_CATALOG_SHA256, sha(raw.replace(b'\r\n', b'\n')))

    def test_producer_rejects_an_overlapping_output_and_every_rerun_is_identical(self):
        before = {p.name: p.read_bytes() for p in (REPO / glass.ARCHIVE).iterdir() if p.is_file()}
        nested = REPO / glass.ARCHIVE / 'child'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                PRODUCER.generate(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                glass.validate_outputs(REPO, REPO)
            self.assertFalse(nested.exists())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / glass.ARCHIVE).iterdir() if p.is_file()})
        PRODUCER.generate(REPO, self.root / 'again')
        self.assertEqual(self.with_tree, tree(self.root / 'again'))
        self.assertEqual(glass.proof_text(REPO).encode('utf-8'), self.with_tree['v046-chrome-glass-tuning-proof.json'])

    def test_hand_written_jvm_regression_is_source_complete(self):
        text = HAND_WRITTEN_TEST.read_text(encoding='utf-8')
        self.assertTrue(text.startswith('package com.bilipai.desktop.ui\n'))
        self.assertEqual(5, text.count('    @Test\n'))
        self.assertEqual(5, len(re.findall(r'(?m)^    @Test\n    fun \w+\(\)', text)))
        self.assertNotIn('org.junit.Assert', text)
        balanced(self, text)
        generated = '\n'.join(raw.decode('utf-8') for path, raw in self.with_tree.items() if path.startswith('generated/') and path.endswith('.kt'))
        owned = '\n'.join(p.read_text(encoding='utf-8') for p in (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/settings').glob('*.kt'))
        imports = re.findall(r'(?m)^import (com\.android\.purebilibili\.[\w.]+|com\.bilipai\.desktop\.[\w.]+)$', text)
        self.assertEqual(10, len(imports))
        for imported in imports:
            name = imported.rsplit('.', 1)[1]
            pattern = r'(?m)^(?:internal |private |public )?(?:fun|class|data class|enum class|object|val) ' + name + r'\b'
            self.assertTrue(re.search(pattern, generated + '\n' + owned), imported)
        # Every named argument the test passes exists on the declaration it calls.
        signatures = {
            'resolveStoredLiquidGlassProgress': ('progress', 'legacyModeValue', 'legacyStrength', 'legacyStyleValue'),
            'resolveLiquidGlassAdvancedSettings': ('presetValue', 'contentReadability', 'chromaticAberration', 'contentDistortion'),
            'resolveLiquidGlassTuning': ('progress', 'advancedSettings', 'readabilityMode'),
            'resolveBottomBarGlassMaterialContainerColor': ('surfaceColor', 'preset', 'glassEnabled', 'fallbackAlpha', 'liquidGlassTuning'),
        }
        for function, params in signatures.items():
            overloads = parameter_lists(generated, function)
            self.assertTrue(overloads, function)
            # One overload must accept every named argument the test passes.
            self.assertTrue(any(all(re.search(r'(?:^|[(,\s])' + param + r'\s*:', lst) for param in params) for lst in overloads), function)
            for param in params:
                self.assertIn(param + ' = ', text, (function, param))
        constructor = re.search(r'internal data class DesktopLiquidTabHomePreferences\((.*?)\n\)', owned, re.S).group(1)
        for param in ('isBottomBarLiquidGlassEnabled', 'androidNativeLiquidGlassEnabled', 'liquidGlassProgress',
                      'liquidGlassAdvancedSettings', 'liquidGlassReadabilityMode', 'navigationIconCrossScaleEnabled'):
            self.assertIn('val ' + param + ':', constructor)
            self.assertIn(param + ' = ', text)
        self.assertIn('val bottomBarLiquidGlassPreset:BottomBarLiquidGlassPreset=BottomBarLiquidGlassPreset.BILIPAI_TUNED,', constructor)
        self.assertIn('private fun shellTuning(progress: Float, settings: LiquidGlassAdvancedSettings)', text)

    def test_every_new_or_edited_file_is_lf_only(self):
        paths = [REPO / glass.ARCHIVE / name for name in sorted(self.archive)]
        paths += [TOOLS / 'v046_chrome_glass_tuning.py', TOOLS / 'extract-upstream-shared-liquid-tabs.py',
                  TOOLS / 'tests/test_v046_chrome_glass_tuning_source.py', HAND_WRITTEN_TEST,
                  TOOLS / 'compatibility-source-inputs.json', TOOLS / 'sync-upstream.py',
                  TOOLS / 'tests/test_sync_upstream.py', REPO / 'desktop/build.gradle.kts']
        for path in paths:
            with self.subTest(path=path.relative_to(REPO).as_posix()):
                self.assertNotIn(b'\r', path.read_bytes())
        for relative, raw in self.with_tree.items():
            self.assertNotIn(b'\r', raw, relative)


if __name__ == '__main__':
    unittest.main()
