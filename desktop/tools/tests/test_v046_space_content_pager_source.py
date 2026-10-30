"""Source checks for roadmap slice space-profile-content-pager. No Gradle, no JVM, no network.

Covers the carried v0.4.6 ContentTabPager (with its PagerState rebuild guard), its one comment adaptation, the 25 counted
hunks that mount it on the generated Space screen, the banner rotation and the pinned card icon, the producer hooks, the
Gradle wiring and the inventory registration. The chain class runs the three retained producers that feed the Space screen in a
throw-away mirror (the same way the symbol gate does) and compares the real output with the output of the same producer
with this layer patched out.
"""
from pathlib import Path
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
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_space_content_pager as pager
from v025_source_paths import canonical_source

TASKS = ('extractOriginalVideoTabletFull', 'extractOriginalFavorites', 'extractOriginalSpacePages')
SPACE_OUTPUT = pager.SPACE
PAGER_PACKAGE = 'package com.android.purebilibili.core.ui.common\n'
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopContentTabPagerTest.kt'
GESTURE = 'app/src/main/java/com/android/purebilibili/core/ui/common/VerticalPriorityPagerGesture.kt'
SELECTION = 'app/src/main/java/com/android/purebilibili/navigation/PagerSelectionMotion.kt'
HEADER_NAMES = ('contentTabPagerGestures', 'rememberContentTabPager', 'stationaryPagerHeader')
DECLARATIONS = {
    'rememberContentTabPager': re.compile(r'(?m)^(?:internal |private |public )?fun rememberContentTabPager\('),
    'stationaryPagerHeader': re.compile(r'(?m)^(?:internal |private |public )?fun Modifier\.stationaryPagerHeader\('),
    'contentTabPagerGestures': re.compile(r'(?m)^(?:internal |private |public )?fun Modifier\.contentTabPagerGestures\('),
}
UNIVERSE_ROOTS = ('desktop/src', 'app', 'design-system', 'core-data', 'core-player', 'network-core', 'settings-core',
                  'danmaku-engine', 'plugin-sdk', 'plugins', 'miuix-navigation')


def load(name, label):
    spec = importlib.util.spec_from_file_location(label, TOOLS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync-upstream.py', 'scp_sync')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def text_of(path):
    return Path(path).read_bytes().decode('utf-8')


def git_bytes(*args):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), *args])


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def require_history(case, *revisions):
    missing = [revision for revision in revisions if not commit_present(revision)]
    if missing:
        raise AssertionError('upstream commits missing from this clone: ' + ', '.join(missing) +
                             '. Fetch them; a missing commit is a failure, not a skip.')


def upstream(commit, path):
    return git_bytes('show', commit + ':' + path).decode('utf-8')


def balanced(text):
    depth = {'(': 0, '[': 0, '{': 0}
    closers = {')': '(', ']': '[', '}': '{'}
    for word, _, _ in sync.kotlin_tokens(text):
        if word in depth:
            depth[word] += 1
        elif word in closers:
            depth[closers[word]] -= 1
            if depth[closers[word]] < 0:
                return False
    return all(value == 0 for value in depth.values())


def named_arguments(tokens, opening):
    """Names of the named arguments (or declared parameters) at depth one of the parenthesis at tokens[opening]."""
    depth, names, index = 0, [], opening
    while True:
        word = tokens[index][0]
        if word in ('(', '[', '{'):
            depth += 1
        elif word in (')', ']', '}'):
            depth -= 1
            if depth == 0:
                return names
        elif depth == 1 and tokens[index - 1][0] in ('(', ',') and index + 1 < len(tokens):
            follower = tokens[index + 1][0]
            if (follower == '=' and tokens[index + 2][0] != '=') or follower == ':':
                names.append(word)
        index += 1


def calls(text, name):
    """(arguments of each call, parameters of the declaration) of `name( ... )` in the text."""
    tokens = sync.kotlin_tokens(text)
    found, declared = [], []
    for index in range(1, len(tokens) - 1):
        if tokens[index][0] == name and tokens[index + 1][0] == '(':
            names = named_arguments(tokens, index + 1)
            is_declaration = tokens[index - 1][0] == 'fun' or (index > 2 and tokens[index - 1][0] == '.' and tokens[index - 3][0] == 'fun')
            (declared if is_declaration else found).append(names)
    return found, declared


def tree(root):
    files = {}
    for path in Path(root).rglob('*'):
        if path.is_file():
            files[path.relative_to(root).as_posix()] = path.read_bytes()
    return files


class ArchiveAndEditTests(unittest.TestCase):
    def test_pins_match_the_manifest_the_bytes_and_the_registration(self):
        manifest = json.loads((REPO / pager.ARCHIVE / 'manifest.json').read_bytes())
        self.assertEqual(pager.MANIFEST_SHA256, sha((REPO / pager.ARCHIVE / 'manifest.json').read_bytes()))
        self.assertEqual(pager.COMMIT, manifest['commit'])
        body = pager.fixed(REPO)
        self.assertEqual((pager.LEAF_BYTES, pager.LEAF_SHA256), (len(body), sha(body)))
        self.assertEqual(pager.LEAF_BLOB, hashlib.sha1(b'blob %d\0' % len(body) + body).hexdigest())
        self.assertNotIn(b'\r', body)
        catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_bytes())
        rows = [row for row in catalog['inputs'] if row['localPath'].startswith(pager.ARCHIVE + '/')]
        self.assertEqual(1, len(rows))
        self.assertEqual((pager.LEAF_PATH, pager.COMMIT, pager.LEAF_BLOB, pager.LEAF_SHA256, False),
                         (rows[0]['path'], rows[0]['upstreamCommit'], rows[0]['blob'], rows[0]['sha256'], rows[0]['referenceOnly']))
        self.assertEqual(['v046-space-content-pager'], rows[0]['features'])
        self.assertIn(pager.ARCHIVE, catalog['buildInputRoots'])
        archive = next(a for a in catalog['archives'] if a['directory'] == pager.ARCHIVE)
        self.assertEqual({'ContentTabPager.kt', 'LICENSE', 'manifest.json'}, {Path(f['path']).name for f in archive['files']})
        for item in archive['files']:
            self.assertEqual(sha((REPO / item['path']).read_bytes()), item['sha256'])
        self.assertEqual(sha((TOOLS / 'compatibility-source-inputs.json').read_bytes().replace(b'\r\n', b'\n')),
                         sync.COMPATIBILITY_CATALOG_SHA256)
        self.assertEqual(1, len([r for r in sync.adapted_compatibility_inputs(REPO) if r['localPath'].startswith(pager.ARCHIVE + '/')]))
        self.assertTrue(all(not r['referenceOnly'] for r in rows))

    def test_tampered_extra_or_missing_leaves_are_rejected(self):
        for name in [pager.LEAF, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-scp-') as temp:
                root = Path(temp) / pager.ARCHIVE
                shutil.copytree(REPO / pager.ARCHIVE, root)
                target = root / name
                target.write_bytes((target.read_bytes() if target.exists() else b'') + b'\n')
                with self.assertRaises(ValueError):
                    pager.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-scp-') as temp:
            root = Path(temp) / pager.ARCHIVE
            shutil.copytree(REPO / pager.ARCHIVE, root)
            (root / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                pager.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-scp-') as temp:
            with self.assertRaises(ValueError):
                pager.fixed(Path(temp))

    def test_outputs_overlapping_the_archive_are_rejected(self):
        for bad in (REPO / pager.ARCHIVE, REPO / pager.ARCHIVE / 'child', REPO / 'desktop/upstream-slices'):
            with self.subTest(output=bad.name), self.assertRaises(ValueError):
                pager.validate_outputs(REPO, bad)
        pager.validate_outputs(REPO, None, Path(tempfile.gettempdir()) / 'bp-scp-elsewhere')

    def test_the_carried_file_is_the_upstream_blob_and_keeps_the_rebuild_guard(self):
        require_history(self, pager.COMMIT, pager.INTRODUCED_BY, pager.AMENDED_BY, pager.BANNER_COMMIT)
        body = pager.fixed(REPO)
        self.assertEqual(git_bytes('show', pager.COMMIT + ':' + pager.LEAF_PATH), body)
        self.assertEqual(git_bytes('show', pager.AMENDED_BY + ':' + pager.LEAF_PATH), body)
        text = body.decode('utf-8')
        self.assertTrue(text.startswith(PAGER_PACKAGE))
        self.assertEqual(1, text.count('val pager = key(pageCount) {\n        rememberPagerState(initialPage = selectedPage) { pageCount }\n    }'))
        self.assertEqual(1, text.count('import androidx.compose.runtime.key\n'))
        for name, pattern in DECLARATIONS.items():
            self.assertEqual(1, len(pattern.findall(text)), name)
        # The guard is the 0.4.5 amendment: the first upstream version replaced nothing on a count change.
        first = upstream(pager.INTRODUCED_BY, pager.LEAF_PATH)
        self.assertNotIn('key(pageCount)', first)
        self.assertIn('val pager = rememberPagerState(initialPage = selectedPage) { pageCount }', first)
        self.assertIn('val pager = rememberPagerState(initialPage = selectedPage) { pageCount }',
                      upstream(pager.AMENDED_BY + '^', pager.LEAF_PATH))
        self.assertTrue(balanced(text))
        # The banner rotation commit is a different change of the same screen.
        self.assertIn('SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS = 4_000L', upstream(pager.BANNER_COMMIT, pager.UPSTREAM_SPACE[0]))

    def test_the_one_adaptation_is_exact_recoverable_and_fails_closed(self):
        body = pager.fixed(REPO)
        data, edit = pager.adapt_pager(body)
        self.assertEqual((pager.EMITTED_BYTES, pager.EMITTED_SHA256), (len(data), sha(data)))
        text, adapted = body.decode('utf-8'), data.decode('utf-8')
        self.assertEqual(text, adapted[:edit['offset']] + pager.ADAPT_BEFORE + adapted[edit['offset'] + len(pager.ADAPT_AFTER):])
        self.assertEqual(1, text.count('forceVerticalPriority'))
        self.assertEqual(0, adapted.count('forceVerticalPriority =') + adapted.count('forceVerticalPriority:'))
        self.assertEqual(pager.ADAPT_AFTER.count('\n'), 2)
        self.assertEqual(len(text.splitlines()) + 1, len(adapted.splitlines()))
        self.assertNotIn(b'\r', data)
        self.assertTrue(balanced(adapted))
        for bad in (text.replace(pager.ADAPT_BEFORE, pager.ADAPT_BEFORE * 2),
                    text.replace(pager.ADAPT_BEFORE, ''),
                    text + '\n// forceVerticalPriority\n',
                    text.replace('verticalPriorityHorizontalPagerSwipe(', 'verticalPriorityHorizontalPagerSwipe2(')):
            with self.subTest(bad=bad[-40:]), self.assertRaises(ValueError):
                pager.adapt_pager(bad.encode('utf-8'))

    def test_the_adapted_call_matches_the_pinned_declarations_and_the_dropped_argument_is_the_forced_path(self):
        require_history(self, pager.COMMIT)
        adapted = pager.adapt_pager(pager.fixed(REPO))[0].decode('utf-8')
        pinned = canonical_source(REPO, GESTURE).read_text(encoding='utf-8').replace('\r\n', '\n')
        signature = 'internal fun Modifier.verticalPriorityHorizontalPagerSwipe('
        self.assertEqual(1, pinned.count(signature))
        start = pinned.index(signature)
        declaration = pinned[start:pinned.index('\n}\n', start) + 3]
        found, declared = calls(pinned, 'verticalPriorityHorizontalPagerSwipe')
        self.assertEqual([['state', 'enabled', 'reverseLayout', 'horizontalLockSlopMultiplier', 'shouldYield']], declared)
        self.assertEqual([], found)
        found, _ = calls(adapted, 'verticalPriorityHorizontalPagerSwipe')
        self.assertEqual([['state', 'enabled', 'shouldYield']], found)
        self.assertTrue(set(found[0]) <= set(declared[0]))
        # Upstream's forced call skips the Miuix override branch, so dropping the argument against the pinned declaration
        # (which has no such branch) is the same behaviour: v0.4.6 minus its two additions IS the pinned declaration.
        latest = upstream(pager.COMMIT, GESTURE)
        latest_start = latest.index(signature)
        latest_declaration = latest[latest_start:latest.index('\n}\n', latest_start) + 3]
        block = ('    // Glass comment lists need child-first arbitration even while the list is flinging.\n'
                 '    // Other callers retain Miuix\'s native cross-axis takeover behavior by default.\n'
                 '    if (!forceVerticalPriority && LocalAppUiStyle.current == AppUiStyle.MIUIX) {\n'
                 '        return@composed pagerGestureOverride(\n'
                 '            pagerState = state,\n'
                 '            flingBehavior = PagerDefaults.flingBehavior(state, snapAnimationSpec = PagerNavigationSpringSpec),\n'
                 '            enabled = !shouldYield(),\n'
                 '        )\n'
                 '    }\n')
        self.assertEqual(1, latest_declaration.count(block))
        self.assertEqual(1, latest_declaration.count('    forceVerticalPriority: Boolean = false,\n'))
        forced = latest_declaration.replace(block, '').replace('    forceVerticalPriority: Boolean = false,\n', '')
        self.assertEqual(declaration, forced)
        # The pinned declaration is compiled: the home page producer selects it, and nobody else declares it.
        producer = text_of(TOOLS / 'extract-upstream-home-page.py')
        self.assertEqual(1, producer.count("'verticalPriorityHorizontalPagerSwipe'"))
        # animatePagerSelection(pager, page) matches the direct v0.2.5 declaration.
        selection = canonical_source(REPO, SELECTION).read_text(encoding='utf-8')
        _, declared = calls(selection, 'animatePagerSelection')
        self.assertEqual([['pagerState', 'targetPage', 'animationSpec']], [declared[0]] if declared else [])
        self.assertEqual(2, adapted.count('animatePagerSelection(pager,'))

    def test_every_added_line_is_an_upstream_line_or_declared_and_the_table_is_well_formed(self):
        require_history(self, pager.COMMIT)
        latest = {line.strip() for line in upstream(pager.COMMIT, pager.UPSTREAM_SPACE[0]).splitlines()}
        self.assertEqual(pager.UPSTREAM_SPACE[1], git_bytes('rev-parse', pager.COMMIT + ':' + pager.UPSTREAM_SPACE[0]).decode().strip())
        declared = {line.strip() for _, line in pager.ADAPTATIONS}
        self.assertEqual(len(pager.ADAPTATIONS), len(declared))
        self.assertEqual(25, len(pager.EDITS))
        self.assertEqual(len(pager.EDITS), len({edit['label'] for edit in pager.EDITS}))
        self.assertEqual({'pager', 'banner'}, {edit['origin'] for edit in pager.EDITS})
        departures = []
        used = set()
        for edit in pager.EDITS:
            self.assertNotEqual(edit['before'], edit['after'], edit['label'])
            self.assertNotIn('\r', edit['before'] + edit['after'])
            self.assertTrue(edit['before'].endswith('\n') or edit['before'].endswith('@OptIn(') or '\n' not in edit['before'], edit['label'])
            before_lines = {line.strip() for line in edit['before'].splitlines()}
            for line in edit['after'].splitlines():
                stripped = line.strip()
                if not stripped or stripped in before_lines:
                    continue
                if stripped in declared:
                    used.add(stripped)
                elif stripped not in latest:
                    departures.append((edit['label'], line))
        self.assertEqual([], departures)
        self.assertEqual(declared, used, 'every declared adaptation line is used by an edit')

    def test_the_producer_hooks_are_in_the_pinned_places(self):
        producer = text_of(TOOLS / 'extract-upstream-space-pages.py')
        validate = ("def generate(repo, out, include_screen=True):\n"
                    "    from v046_space_content_pager import validate_outputs as validate_space_pager\n"
                    "    validate_space_pager(repo, out)\n"
                    "    parser = module('space_parser',repo/'desktop/tools/sync-upstream.py')\n")
        self.assertEqual(1, producer.count(validate))
        hook = ("            from v046_space_content_pager import site_edits as space_pager_edits\n"
                "            for pager_edit in space_pager_edits(pkg+'SpaceScreen.kt',s):\n"
                "                s=edit(s,pager_edit['before'],pager_edit['after'],1)\n")
        self.assertEqual(1, producer.count(hook))
        screen = producer.index('        def screen(s,edit,logs):')
        pink = producer.index("            s=edit(s,pink_before,pink_after,1)\n")
        follow = producer.index('            from v046_follow_confirmation import site_edits as follow_confirmation_edits\n')
        self.assertLess(screen, pink)
        self.assertLess(pink, producer.index(hook))
        self.assertLess(producer.index(hook), follow)
        emit = ("        produce(BASE+'feature/space/SpaceScreen.kt',pkg+'SpaceScreen.kt',screen)\n"
                "        # The whole v0.4.6 ContentTabPager.kt (new declarations: no other file declares them) that the hunks above call.\n"
                "        from v046_space_content_pager import emit_pager as emit_space_pager\n"
                "        emit_space_pager(repo,out)\n")
        self.assertEqual(1, producer.count(emit))
        self.assertEqual(1, producer.count('from v046_space_content_pager import validate_outputs as validate_space_pager\n'))
        self.assertFalse(any(line.startswith(('import v046_space_content_pager', 'from v046_space_content_pager'))
                             for line in producer.splitlines()))
        # No other generator or producer names the carried file or its declarations.
        for path in sorted(TOOLS.glob('*.py')):
            if path.name in ('v046_space_content_pager.py', 'extract-upstream-space-pages.py'):
                continue
            body = path.read_bytes().decode('utf-8')
            for name in HEADER_NAMES + ('ContentTabPager',):
                self.assertNotIn(name, body, (path.name, name))

    def test_gradle_wiring_and_the_absence_of_a_canonical_exclude(self):
        build = text_of(REPO / 'desktop/build.gradle.kts')
        block = ('tasks.named("extractOriginalSpacePages") {\n'
                 '    inputs.file("tools/v046_space_content_pager.py")\n'
                 '    inputs.dir("upstream-slices/v046-space-content-pager")\n'
                 '}\n')
        self.assertEqual(1, build.count(block))
        self.assertEqual(1, build.count('upstream-slices/v046-space-content-pager'))
        self.assertEqual(1, build.count('tools/v046_space_content_pager.py'))
        self.assertFalse(re.search(r'exclude\(canonicalOriginalIdentity\("[^"]*(ContentTabPager|VerticalPriorityPagerGesture)', build))
        task = build[build.index('val extractOriginalSpacePages by tasks.registering(Exec::class)'):]
        task = task[:task.index('\n}\n')]
        self.assertIn('"--out", layout.buildDirectory.dir("generated/original-space-pages")', task)
        self.assertEqual(1, build.count('kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-space-pages")) }'))

    def test_the_jvm_regression_class_exists_and_is_balanced(self):
        text = text_of(JVM_TEST)
        self.assertEqual(2, len(re.findall(r'(?m)^    @Test\n', text)))
        self.assertEqual(1, len(re.findall(r'(?m)^class DesktopContentTabPagerTest \{', text)))
        self.assertIn('package com.bilipai.desktop.ui\n', text)
        self.assertIn('rememberContentTabPager(', text)
        self.assertIn('key = { displayed[it] }', text)
        self.assertTrue(balanced(text))
        for forbidden in ('Robot', 'java.net', 'HttpServer', 'GraphicsEnvironment', 'application {', 'Window('):
            self.assertNotIn(forbidden, text)


class ChainTests(unittest.TestCase):
    """The real producers in a mirror, with and without this layer."""

    @classmethod
    def setUpClass(cls):
        scratch = tempfile.mkdtemp(prefix='bp-scp-')
        cls.addClassCleanup(shutil.rmtree, scratch, True)
        root, env = gate.make_scratch_repo(REPO, scratch)
        cls.root = root
        model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
        tasks, skipped = gate.select_producers(model, only=set(TASKS))
        if {task.name for task in tasks} != set(TASKS):
            raise AssertionError(([task.name for task in tasks], skipped))
        results = gate.run_producers(root, tasks, env)
        failed = [(row['task'], row['returncode'], row['stderrTail']) for row in results if row['returncode']]
        if failed:
            raise AssertionError(failed)
        cls.real = root / 'desktop/build/generated/original-space-pages'
        cls.real_tree = tree(cls.real)
        producer_path = root / 'desktop/tools/extract-upstream-space-pages.py'
        spec = importlib.util.spec_from_file_location('scp_space_pages', producer_path)
        cls.producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.producer)
        cls.before_dir = Path(tempfile.mkdtemp(prefix='bp-scb-'))
        cls.addClassCleanup(shutil.rmtree, str(cls.before_dir), True)
        with patch.object(pager, 'site_edits', lambda relative, text: []), \
                patch.object(pager, 'emit_pager', lambda repo, output: None):
            cls.producer.generate(root, cls.before_dir / 'o')
        cls.before_tree = tree(cls.before_dir / 'o')

    def space(self):
        return self.real_tree['com/android/purebilibili/feature/space/SpaceScreen.kt'].decode('utf-8')

    def test_the_real_output_is_the_unpatched_output_plus_exactly_the_pinned_edits(self):
        before = self.before_tree['com/android/purebilibili/feature/space/SpaceScreen.kt'].decode('utf-8')
        self.assertNotIn('rememberContentTabPager', before)
        expected = before
        for edit in pager.site_edits(SPACE_OUTPUT, before):
            self.assertEqual(1, expected.count(edit['before']), edit['label'])
            expected = expected.replace(edit['before'], edit['after'], 1)
        self.assertEqual(expected, self.space())
        changed = {name for name in set(self.before_tree) | set(self.real_tree)
                   if self.before_tree.get(name) != self.real_tree.get(name)}
        self.assertEqual({'com/android/purebilibili/feature/space/SpaceScreen.kt',
                          'com/android/purebilibili/core/ui/common/ContentTabPager.kt',
                          'v046-space-content-pager-proofs/ContentTabPager.kt.json',
                          'source-transform-replay.json',
                          'v037-explicit-tab-role-proofs/com/android/purebilibili/feature/space/SpaceScreen.kt.json'}, changed)
        self.assertNotIn('com/android/purebilibili/core/ui/common/ContentTabPager.kt', self.before_tree)

    def test_the_replay_receipt_records_every_edit_once_between_the_pink_and_the_follow_hunks(self):
        replay = json.loads(self.real_tree['source-transform-replay.json'].decode('utf-8'))
        screen = [row for row in replay['files'] if row['source'].endswith('/feature/space/SpaceScreen.kt')]
        self.assertEqual(1, len(screen))
        self.assertTrue(screen[0]['completeOriginalFileReconstructed'])
        pairs = [(step['original'], step['adapted']) for step in screen[0]['transforms']]
        mine = [(edit['before'], edit['after']) for edit in pager.EDITS]
        positions = [pairs.index(pair) for pair in mine]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(len(mine), len(set(positions)))
        self.assertEqual(list(range(positions[0], positions[0] + len(mine))), positions, 'one contiguous layer')
        import v046_follow_confirmation as follow
        import v046_identity_badge_palette as palette
        pink = palette.UP_NAME_MIGRATIONS['SpaceScreen.kt']
        pink_at = [i for i, (_, after) in enumerate(pairs) if after == pink['after']]
        follow_at = [i for i, (_, after) in enumerate(pairs) if follow.FUNCTION + '(' in after]
        self.assertEqual(1, len(pink_at))
        self.assertTrue(follow_at)
        self.assertLess(pink_at[0], positions[0])
        self.assertLess(positions[-1], min(follow_at))

    def test_the_emitted_pager_equals_the_adapted_archive_and_its_proof_is_written(self):
        emitted = self.real_tree['com/android/purebilibili/core/ui/common/ContentTabPager.kt']
        self.assertEqual(pager.adapt_pager(pager.fixed(REPO))[0], emitted)
        self.assertEqual((pager.EMITTED_BYTES, pager.EMITTED_SHA256), (len(emitted), sha(emitted)))
        proof = json.loads(self.real_tree['v046-space-content-pager-proofs/ContentTabPager.kt.json'].decode('utf-8'))
        self.assertEqual((pager.COMMIT, pager.INTRODUCED_BY, pager.AMENDED_BY, pager.MANIFEST_SHA256),
                         (proof['upstreamCommit'], proof['introducedBy'], proof['amendedBy'], proof['sourceManifestSHA256']))
        self.assertTrue(proof['fullInputInverseRecovered'])
        self.assertFalse(proof['mainCompiled'])
        self.assertEqual(sha(emitted), proof['emittedSHA256'])
        self.assertEqual(list(pager.TOP_LEVEL_NAMES), proof['topLevelNames'])
        # The receipts of the older producers are written with the platform newline; only this layer's files are checked.
        for name in ('com/android/purebilibili/core/ui/common/ContentTabPager.kt',
                     'com/android/purebilibili/feature/space/SpaceScreen.kt',
                     'v046-space-content-pager-proofs/ContentTabPager.kt.json'):
            self.assertNotIn(b'\r', self.real_tree[name], name)

    def test_a_second_run_is_byte_identical_and_an_overlapping_output_is_refused(self):
        again = Path(tempfile.mkdtemp(prefix='bp-scr-'))
        self.addCleanup(shutil.rmtree, str(again), True)
        self.producer.generate(self.root, again / 'o')
        second = tree(again / 'o')
        self.assertEqual(sorted(self.real_tree), sorted(second))
        # The replay receipt of the policy producer iterates a set, so its entry order depends on the process hash seed;
        # every emitted source is still byte identical and the Space screen entry of the receipt is identical.
        receipt = 'source-transform-replay.json'
        self.assertEqual([], [name for name in self.real_tree if name != receipt and self.real_tree[name] != second[name]])

        def screen_entry(raw):
            rows = [row for row in json.loads(raw.decode('utf-8'))['files']
                    if row['source'].endswith('/feature/space/SpaceScreen.kt')]
            self.assertEqual(1, len(rows))
            return rows[0]

        self.assertEqual(screen_entry(self.real_tree[receipt]), screen_entry(second[receipt]))
        archive = self.root / pager.ARCHIVE
        archive_before = tree(archive)
        with self.assertRaises(ValueError):
            self.producer.generate(self.root, archive / 'child')
        self.assertEqual(archive_before, tree(archive))
        self.assertFalse((archive / 'child').exists())

    def test_the_mount_is_complete_and_every_new_parameter_has_its_callers(self):
        text = self.space()
        self.assertTrue(balanced(text))
        self.assertEqual(1, text.count('val pagerState = rememberContentTabPager('))
        self.assertEqual(1, text.count('        AppHorizontalPager(\n'))
        self.assertEqual(1, text.count('contentTabPagerGestures(\n'))
        self.assertEqual(2, text.count('Modifier.stationaryPagerHeader(pagerState, page)'))
        self.assertEqual(1, text.count('        } // HorizontalPager\n'))
        for name in ('PagerState', 'collectIsDraggedAsState', 'positionInRoot', 'contentTabPagerGestures',
                     'rememberContentTabPager', 'stationaryPagerHeader', 'AppHorizontalPager', 'delay'):
            self.assertEqual(1, len(re.findall(r'(?m)^import [\w.]+\.' + name + r'$', text)), name)
        for name in ('Rect', 'IntOffset', 'offset', 'onSizeChanged', 'roundToInt', 'LocalAppThemeConfig'):
            self.assertEqual(1, len(re.findall(r'(?m)^import [\w.]+\.' + name + r'$', text)), name)
        self.assertIn('import androidx.compose.foundation.layout.*\n', text)
        self.assertIn('import androidx.compose.ui.layout.layout\n', text)
        # callers and declarations of the three private composables gained parameters (Source260 class: pinnedReplyIds)
        found, declared = calls(text, 'SpaceContent')
        self.assertEqual(1, len(found))
        self.assertIn('pageGridStates', found[0])
        self.assertIn('pageGridStates', declared[0])
        found, declared = calls(text, 'SpaceContentTabs')
        self.assertEqual(1, len(found))
        for name in ('pagerState', 'page', 'primaryTabHeightProvider'):
            self.assertIn(name, found[0])
            self.assertIn(name, declared[0])
        found, declared = calls(text, 'SpaceMainTabRow')
        self.assertEqual(2, len(found))
        for arguments in found:
            for name in ('indicatorPositionProvider', 'isScrollInProgressProvider'):
                self.assertIn(name, arguments)
        for name in ('modifier', 'indicatorPositionProvider', 'isScrollInProgressProvider'):
            self.assertIn(name, declared[0])
        tabs, _ = calls(text, 'AppThemeAdaptiveTabRow')
        self.assertEqual(1, len(tabs))
        for name in ('indicatorPositionProvider', 'isScrollInProgressProvider', 'role'):
            self.assertIn(name, tabs[0])
        # the page lambda has no non-local return (the content lambdas are not inline)
        start = text.index('        ) { page ->\n')
        end = text.index('        } // HorizontalPager\n')
        tokens = sync.kotlin_tokens(text[start:end])
        for index, (word, _, _) in enumerate(tokens):
            if word == 'return':
                self.assertEqual('@', tokens[index + 1][0], 'bare return inside the pager page lambda')
        for adaptation, line in pager.ADAPTATIONS:
            self.assertGreaterEqual(text.count(line), 1, adaptation)

    def test_banner_rotation_and_pinned_card_icon_are_complete(self):
        text = self.space()
        self.assertEqual(1, text.count('private const val SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS = 4_000L\n'))
        self.assertEqual(2, text.count('SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS'))
        self.assertIn('@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)\n@Composable\ninternal fun SpaceScreen(\n', text)
        banner = text[text.index('private fun SpaceHeaderBanner('):]
        banner = banner[:banner.index('\n}\n')]
        self.assertEqual(1, banner.count('collectIsDraggedAsState()'))
        self.assertEqual(1, banner.count('pagerState.animateScrollToPage(nextPage)'))
        self.assertEqual(1, banner.count('val nextPage = (pagerState.currentPage + 1) % topImages.size'))
        # only the branch with several header images rotates: the skin branch and the single image branch do not
        multi = banner[banner.index('} else if (topImages.size > 1) {'):banner.index('} else if (topImages.size == 1) {')]
        self.assertIn('delay(SPACE_BANNER_AUTO_ROTATE_INTERVAL_MS)', multi)
        self.assertNotIn('animateScrollToPage', banner[:banner.index('} else if (topImages.size > 1) {')])
        self.assertEqual(1, text.count('showTrailingMore = false,'))
        self.assertEqual(1, text.count('    showTrailingMore: Boolean = true,\n'))
        self.assertEqual(1, text.count('trailingContent = if (showTrailingMore) {'))
        found, declared = calls(text, 'SpaceArchiveListItemRow')
        self.assertEqual(3, len(found))
        self.assertIn('showTrailingMore', declared[0])
        self.assertEqual(1, sum('showTrailingMore' in names for names in found))

    def test_the_three_pager_names_have_one_definer_in_the_compile_universe(self):
        counts = {name: [] for name in DECLARATIONS}
        scanned = 0
        for relative in UNIVERSE_ROOTS:
            for path in (REPO / relative).rglob('*.kt'):
                text = path.read_bytes().decode('utf-8', 'replace')
                scanned += 1
                for name, pattern in DECLARATIONS.items():
                    if pattern.search(text):
                        counts[name].append(path.relative_to(REPO).as_posix())
        self.assertGreater(scanned, 1000)
        for name, places in counts.items():
            self.assertEqual([], places, name + ' is declared outside the carried archive')
        generated = {name: [] for name in DECLARATIONS}
        for path in (self.root / 'desktop/build/generated').rglob('*.kt'):
            text = path.read_bytes().decode('utf-8', 'replace')
            for name, pattern in DECLARATIONS.items():
                if pattern.search(text):
                    generated[name].append(path.relative_to(self.root).as_posix())
        for name, places in generated.items():
            self.assertEqual(['desktop/build/generated/original-space-pages/com/android/purebilibili/core/ui/common/ContentTabPager.kt'],
                             places, name)
        archive = text_of(REPO / pager.ARCHIVE / pager.LEAF)
        for name, pattern in DECLARATIONS.items():
            self.assertEqual(1, len(pattern.findall(archive)), name)


if __name__ == '__main__':
    unittest.main()
