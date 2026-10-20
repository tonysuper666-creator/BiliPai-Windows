"""Source checks for roadmap slice plr-holder-collapse-family (#55). No Gradle, no JVM, no network.

The slice is reproduce-or-skip over eight holder, collapse and gesture entries. It carries three parts with a Windows
reproduction (fullscreen exit scroll recovery, the tablet secondary pane tab synchronisation, the compact comment titles)
and records what it did NOT carry and why. The Kotlin is not compiled here: the three producers run for real into short
temporary directories, once with the layer and once with the layer replaced by the identity, and the generated text is
checked. The decision premises (entries that are already carried, not needed or deferred) are pinned against the generated
tree: a later slice that legitimately changes one of them must revisit the matching decision in the manifest.
"""
from contextlib import contextmanager
from pathlib import Path
import difflib
import importlib.util
import io
import json
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_detail_holder_collapse as collapse

PKG = 'com/android/purebilibili/'
SCREEN = PKG + 'feature/video/screen/'
HOLDER_OUTPUT = SCREEN + 'VideoDetailScreenStateHolder.kt'
TABLET_OUTPUT = SCREEN + 'TabletVideoLayout.kt'
CINEMA_OUTPUT = SCREEN + 'TabletCinemaLayout.kt'
CONTENT_OUTPUT = SCREEN + 'DesktopOriginalVideoContentSection.kt'
RECOVERY_OUTPUT = SCREEN + 'VideoDetailInlineScrollRecovery.kt'
JVM_TEST = REPO / collapse.RECOVERY_TEST_COPY
FEATURE = 'v046-detail-holder-collapse'
TASKS = ('extractOriginalVideoDetailHolderFull', 'extractOriginalVideoTabletFull', 'extractOriginalVideoContentFull')
ENTRY_IDS = (
    'v030-detail-chrome-hidden-paused-collapsed', 'v030-detail-glass-gated-chrome-blur', 'v042-detail-cover-169-skeleton',
    'v042-fullscreen-exit-scroll-recovery', 'v042-compact-comment-autopause', 'v042-glass-pager-vertical-priority',
    'v046-collapse-height-shared', 'post046-shared-collapse-detail-layouts',
)
KINDS = {'carried', 'partial', 'already-carried', 'not-needed', 'deferred'}


def load(name, label):
    spec = importlib.util.spec_from_file_location(label, TOOLS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def text_of(path):
    return Path(path).read_bytes().decode('utf-8')


def lf_of(path):
    """Vendored canonical files may be checked out with CRLF; the generators read them LF-normalised."""
    return text_of(path).replace('\r\n', '\n')


def run_tablet(output):
    """The tablet producer is a script (argument parsing at module level): run it in process, as __main__."""
    sink = io.TextIOWrapper(io.BytesIO(), encoding='utf-8')
    with patch.object(sys, 'argv', ['extract-upstream-video-tablet-full.py', '--repo', str(REPO), '--output', str(output)]), \
            patch('sys.stdout', sink):
        runpy.run_path(str(TOOLS / 'extract-upstream-video-tablet-full.py'), run_name='__main__')


def git_blob(path):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob', collapse.COMMIT + ':' + path],
                          capture_output=True, check=True).stdout


def changed(before, after):
    """(removed, added) text of a line diff between two texts."""
    a, b = before.splitlines(keepends=True), after.splitlines(keepends=True)
    removed, added = [], []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if tag != 'equal':
            removed.append(''.join(a[i1:i2]))
            added.append(''.join(b[j1:j2]))
    return removed, added


def balanced(case, text, name, syntax):
    stack = []
    for token, _, _ in syntax.kotlin_tokens(text):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, name)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), name)
    case.assertEqual([], stack, name)


@contextmanager
def identity_layers():
    """The three advance functions (and the file emission) replaced by the identity: what the producers made before."""
    with patch.object(collapse, 'advance_holder', side_effect=lambda repo, text, output: text), \
            patch.object(collapse, 'emit_recovery', side_effect=lambda repo, output: Path(output) / 'not-emitted'), \
            patch.object(collapse, 'advance_layout', side_effect=lambda repo, text, output, label: text), \
            patch.object(collapse, 'advance_content', side_effect=lambda repo, text, output: text):
        yield


class DetailHolderCollapseSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-hc-')
        root = Path(cls.temp.name)
        cls.syntax = load('sync-upstream.py', 'hc_syntax')
        cls.sources, cls.manifest = collapse.fixed(REPO)
        cls.comment_sources = collapse.comment_list_sources(REPO)
        cls.holder = load('extract-upstream-video-detail-holder.py', 'hc_holder')
        cls.content = load('extract-upstream-video-content-full.py', 'hc_content')
        for label in ('actual', 'before'):
            directory = root / label
            if label == 'actual':
                cls.holder.generate(REPO, directory / 'holder')
                cls.content.generate(REPO, directory / 'content')
                run_tablet(directory / 'tablet')
            else:
                with identity_layers():
                    cls.holder.generate(REPO, directory / 'holder')
                    cls.content.generate(REPO, directory / 'content')
                    run_tablet(directory / 'tablet')
        cls.root = root

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def out(self, label, owner, relative):
        return text_of(self.root / label / owner / relative)

    def proof(self, owner, name):
        return json.loads((self.root / 'actual' / owner / collapse.PROOF_DIRECTORY / (name + '.json')).read_bytes())

    # --- archive and manifest -------------------------------------------------------------------------------------------
    def test_archive_leaves_are_pinned_and_every_tamper_is_rejected(self):
        self.assertEqual(collapse.EXPECTED, set(self.sources))
        self.assertEqual(['LICENSE', 'VideoDetailInlineScrollRecovery.kt', 'VideoDetailInlineScrollRecoveryTest.kt',
                          'manifest.json'], sorted(p.name for p in (REPO / collapse.ARCHIVE).iterdir()))
        for name in sorted(collapse.EXPECTED | {'manifest.json', 'LICENSE'}):
            with self.subTest(tampered=name), tempfile.TemporaryDirectory(prefix='bp-hc-t-') as directory:
                shutil.copytree(REPO / collapse.ARCHIVE, Path(directory) / collapse.ARCHIVE)
                leaf = Path(directory) / collapse.ARCHIVE / name
                leaf.write_bytes(leaf.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    collapse.fixed(directory)
        with tempfile.TemporaryDirectory(prefix='bp-hc-t-') as directory:
            shutil.copytree(REPO / collapse.ARCHIVE, Path(directory) / collapse.ARCHIVE)
            (Path(directory) / collapse.ARCHIVE / 'Extra.kt').write_text('package x\n', encoding='utf-8', newline='\n')
            with self.assertRaises(ValueError):
                collapse.fixed(directory)

    def test_manifest_records_identity_pins_hunk_counts_and_the_eight_entry_decisions(self):
        manifest = self.manifest
        self.assertEqual((collapse.COMMIT, 'v0.4.6', 'raw'), (manifest['commit'], manifest['tag'], manifest['hashNormalization']))
        self.assertFalse(manifest['fullStableParityQualified'])
        self.assertEqual(dict(path=collapse.HOLDER_PATH, blob=collapse.HOLDER_UPSTREAM_BLOB, sha256=collapse.HOLDER_BLOCK_SHA256,
                              lines=collapse.HOLDER_BLOCK.count('\n')), manifest['holderBlock'])
        self.assertEqual(dict(holder=1, tablet=8, cinema=1, content=3, tabletAndCinemaAndContentSource=collapse.COMMENT_LIST_ARCHIVE),
                         manifest['countedHunks'])
        self.assertEqual((8, 1, 3), (len(collapse.TABLET_HUNKS), len(collapse.CINEMA_HUNKS), len(collapse.CONTENT_HUNKS)))
        decisions = manifest['entryDecisions']
        self.assertEqual(list(ENTRY_IDS), [d['entry'] for d in decisions])
        self.assertTrue(all(d['kind'] in KINDS and len(d['reason']) > 40 for d in decisions))
        kinds = {d['entry']: d['kind'] for d in decisions}
        self.assertEqual('carried', kinds['v042-fullscreen-exit-scroll-recovery'])
        self.assertEqual({'partial'}, {kinds['v046-collapse-height-shared'], kinds['post046-shared-collapse-detail-layouts']})
        self.assertEqual(['deferred', 'deferred', 'deferred'], [kinds[e] for e in (
            'v030-detail-chrome-hidden-paused-collapsed', 'v042-compact-comment-autopause', 'v042-glass-pager-vertical-priority')])
        self.assertEqual('already-carried', kinds['v030-detail-glass-gated-chrome-blur'])
        self.assertEqual('not-needed', kinds['v042-detail-cover-169-skeleton'])
        self.assertEqual(3, len(manifest['followUps']))
        self.assertEqual([dict(d) for d in collapse.DECISIONS], decisions)

    def test_leaves_are_the_v046_blobs_and_the_jvm_test_is_a_verbatim_copy(self):
        subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', collapse.COMMIT + '^{commit}'], check=True)
        for row in self.manifest['files']:
            self.assertEqual(git_blob(row['path']), (REPO / collapse.ARCHIVE / row['filename']).read_bytes(), row['filename'])
            self.assertEqual(row['gitBlob'], subprocess.run(
                ['git', '-C', str(REPO), 'rev-parse', collapse.COMMIT + ':' + row['path']], capture_output=True, text=True,
                check=True).stdout.strip())
        # the archive leaf is byte exact (-text); the desktop-owned copy may be checked out with CRLF by a local autocrlf
        self.assertEqual((REPO / collapse.ARCHIVE / collapse.RECOVERY_TEST_FILE).read_bytes(),
                         JVM_TEST.read_bytes().replace(b'\r\n', b'\n'))
        jvm = lf_of(JVM_TEST)
        self.assertEqual(collapse.RECOVERY_TEST_CASES, len(re.findall(r'(?m)^\s*@Test\b', jvm)))
        balanced(self, jvm, JVM_TEST.name, self.syntax)
        balanced(self, self.sources[collapse.RECOVERY_FILE], collapse.RECOVERY_FILE, self.syntax)

    def test_holder_block_is_one_slice_of_the_v046_holder_blob(self):
        upstream = git_blob(collapse.HOLDER_PATH).decode('utf-8')
        self.assertEqual(collapse.HOLDER_UPSTREAM_BLOB, subprocess.run(
            ['git', '-C', str(REPO), 'rev-parse', collapse.COMMIT + ':' + collapse.HOLDER_PATH], capture_output=True, text=True,
            check=True).stdout.strip())
        self.assertEqual(1, upstream.count(collapse.HOLDER_BLOCK))
        self.assertEqual(collapse.HOLDER_BLOCK_SHA256, collapse.sha(collapse.HOLDER_BLOCK))
        self.assertEqual(1, upstream.count(collapse.HOLDER_ANCHOR))
        # in the upstream holder the block sits directly in front of the same anchor
        self.assertEqual(1, upstream.count(collapse.HOLDER_BLOCK + collapse.HOLDER_ANCHOR))

    # --- the holder ------------------------------------------------------------------------------------------------------
    def test_holder_differs_only_by_the_block_and_it_is_restorable(self):
        before = self.out('before', 'holder', HOLDER_OUTPUT)
        after = self.out('actual', 'holder', HOLDER_OUTPUT)
        removed, added = changed(before, after)
        self.assertEqual([''], removed)
        self.assertEqual([collapse.HOLDER_BLOCK], added)
        self.assertEqual(before.replace(collapse.HOLDER_ANCHOR, collapse.HOLDER_BLOCK + collapse.HOLDER_ANCHOR, 1), after)
        proof = self.proof('holder', 'holder-proof')
        self.assertEqual(before, collapse.restore(after, proof['retainedToCurrent']))
        self.assertEqual((collapse.COMMIT, collapse.MANIFEST_SHA256, collapse.HOLDER_UPSTREAM_BLOB, True, True),
                         (proof['upstreamCommit'], proof['sourceManifestSHA256'], proof['upstreamBlob'],
                          proof['wholeBlockIsV046'], proof['noPredecessorInPinnedTree']))
        self.assertEqual(collapse.HOLDER_BLOCK.count('\n'), proof['blockLines'])
        # the block lands between the fullscreen toggle and the local back target, as in v0.4.6
        index = after.index(collapse.HOLDER_BLOCK)
        self.assertLess(after.index('val toggleFullscreen = {'), index)
        self.assertEqual(index + len(collapse.HOLDER_BLOCK), after.index(collapse.HOLDER_ANCHOR))
        for name in ('introListState', 'commentListState', 'videoContentPagerState', 'continuousPlayerPhase'):
            self.assertLess(after.index('val ' + name) if ('val ' + name) in after else after.index('var ' + name), index, name)

    def test_holder_layer_fails_closed_and_cannot_be_applied_twice(self):
        before = self.out('before', 'holder', HOLDER_OUTPUT)
        after = self.out('actual', 'holder', HOLDER_OUTPUT)
        with tempfile.TemporaryDirectory(prefix='bp-hc-t-') as directory:
            with self.assertRaises(ValueError):
                collapse.advance_holder(REPO, after, directory)
            with self.assertRaises(ValueError):
                collapse.advance_holder(REPO, before.replace(collapse.HOLDER_ANCHOR, 'val somethingElse = 1\n', 1), directory)
            with self.assertRaises(ValueError):
                collapse.advance_holder(REPO, before + collapse.HOLDER_ANCHOR, directory)
            self.assertEqual(after, collapse.advance_holder(REPO, before, directory))

    def test_recovery_file_is_emitted_once_equal_to_the_blob_and_declared_once(self):
        emitted = text_of(self.root / 'actual/holder' / RECOVERY_OUTPUT)
        self.assertEqual(self.sources[collapse.RECOVERY_FILE], emitted)
        self.assertEqual(1, text_of(self.root / 'actual/holder' / HOLDER_OUTPUT).count('stopVideoDetailInlineScrolls('))
        self.assertEqual(1, emitted.count('suspend fun stopVideoDetailInlineScrolls('))
        for owner in ('before/holder', 'before/tablet', 'before/content', 'actual/tablet', 'actual/content'):
            tree = self.root / owner
            self.assertEqual([], [p for p in tree.rglob('*.kt') if 'stopVideoDetailInlineScrolls(' in text_of(p)], owner)
        self.assertFalse((self.root / 'before/holder' / RECOVERY_OUTPUT).exists())
        proof = self.proof('holder', 'recovery-proof')
        self.assertEqual(collapse.sha(emitted), proof['originalSHA256'])
        # the imports the helper names are the same ones the pinned Compose version provides (stopScroll is public)
        for needed in ('import androidx.compose.foundation.MutatePriority', 'import androidx.compose.foundation.gestures.stopScroll',
                       'import kotlinx.coroutines.coroutineScope', 'import kotlinx.coroutines.launch'):
            self.assertIn(needed + '\n', emitted)

    # --- tablet layouts ----------------------------------------------------------------------------------------------
    def test_tablet_secondary_pane_takes_exactly_the_seven_sync_hunks_and_the_compact_title(self):
        before = self.out('before', 'tablet', TABLET_OUTPUT)
        after = self.out('actual', 'tablet', TABLET_OUTPUT)
        expected = before
        for hunk in collapse.TABLET_HUNKS:
            self.assertEqual(1, before.count(hunk['before']), hunk['label'])
            self.assertEqual(0, before.count(hunk['after']), hunk['label'])
            expected = expected.replace(hunk['before'], hunk['after'], 1)
        self.assertEqual(expected, after)
        proof = self.proof('tablet', 'TabletVideoLayout-proof')
        self.assertEqual(before, collapse.restore(after, proof['retainedToCurrent']))
        self.assertEqual([h['label'] for h in collapse.TABLET_HUNKS], [r['label'] for r in proof['hunks_applied']])
        # the saved tab is only written by the settled page effect; every request goes through the pager
        self.assertEqual(1, after.count('selectedTab = settledPage'))
        self.assertEqual(0, len(re.findall(r'(?m)^\s*selectedTab = (?!settledPage)', after)))
        self.assertEqual(0, after.count('LaunchedEffect(pagerState.currentPage)'))
        self.assertEqual(0, after.count('LaunchedEffect(selectedTab)'))
        self.assertEqual(1, after.count('fun selectSecondaryTab(index: Int)'))
        self.assertEqual(1, after.count('onSelected = ::selectSecondaryTab,'))
        self.assertLess(after.index('fun selectSecondaryTab(index: Int)'), after.index('selectSecondaryTab(index)\n                    onPaneModeChange'))
        self.assertLess(after.index('val scope = rememberCoroutineScope()'), after.index('fun selectSecondaryTab(index: Int)'))
        self.assertLess(after.index('val pagerState = key('), after.index('fun selectSecondaryTab(index: Int)'))
        self.assertEqual(1, after.count('val pagerState = key(success.info.bvid, fixedTab, relatedTabFirst) {'))
        self.assertIn('import androidx.compose.runtime.*\n', after)  # key and snapshotFlow
        # the Flow collect extension import already exists in the desktop code base (coroutines 1.10.2)
        self.assertEqual(1, after.count('import kotlinx.coroutines.flow.collect\n'))

    def test_every_tablet_cinema_and_content_after_text_is_v046_text(self):
        leaves = {'TabletVideoLayout.kt': collapse.TABLET_HUNKS, 'TabletCinemaLayout.kt': collapse.CINEMA_HUNKS,
                  'VideoContentSection.kt': collapse.CONTENT_HUNKS}
        for leaf, hunks in leaves.items():
            for hunk in hunks:
                if hunk.get('windows_adaptation'):
                    continue
                part = hunk.get('verbatim_part', hunk['after'])
                self.assertGreaterEqual(self.comment_sources[leaf].count(part), 1, hunk['label'])
        adapted = [h['label'] for h in collapse.CONTENT_HUNKS if h.get('windows_adaptation')]
        self.assertEqual(['content inline floating comment title uses the compact typography'], adapted)
        # the one adapted hunk forwards what VideoCommentFloatingHeader forwards in v0.4.6
        floating = self.comment_sources['VideoCommentFloatingHeader.kt']
        self.assertEqual(1, floating.count(collapse.FLOATING_FORWARD))
        self.assertEqual(1, self.comment_sources['VideoContentSection.kt'].count(collapse.FLOATING_CALL_ARGUMENT))
        # the Windows pre-images are not in the v0.4.6 file any more: upstream replaced them
        for hunk in collapse.TABLET_HUNKS[1:7]:
            self.assertEqual(0, self.comment_sources['TabletVideoLayout.kt'].count(hunk['before']), hunk['label'])

    def test_cinema_layout_takes_only_the_compact_title(self):
        before = self.out('before', 'tablet', CINEMA_OUTPUT)
        after = self.out('actual', 'tablet', CINEMA_OUTPUT)
        hunk = collapse.CINEMA_HUNKS[0]
        self.assertEqual(before.replace(hunk['before'], hunk['after'], 1), after)
        self.assertEqual(1, after.count('compactTypography = true,'))
        self.assertEqual(before, collapse.restore(after, self.proof('tablet', 'TabletCinemaLayout-proof')['retainedToCurrent']))

    def test_content_section_takes_the_three_compact_title_arguments(self):
        before = self.out('before', 'content', CONTENT_OUTPUT)
        after = self.out('actual', 'content', CONTENT_OUTPUT)
        expected = before
        for hunk in collapse.CONTENT_HUNKS:
            self.assertEqual(1, before.count(hunk['before']), hunk['label'])
            expected = expected.replace(hunk['before'], hunk['after'], 1)
        self.assertEqual(expected, after)
        self.assertEqual(2, after.count('compactSortHeader = true,'))
        self.assertEqual(1, after.count('compactTypography = true,'))
        self.assertEqual(2, len(re.findall(r'VideoCommentTab\(', after)))
        proof = self.proof('content', 'content-proof')
        self.assertEqual(before, collapse.restore(after, proof['retainedToCurrent']))
        self.assertEqual(['content inline floating comment title uses the compact typography'], proof['windowsAdaptedHunks'])

    def test_layers_fail_closed_on_drift_and_second_application(self):
        tablet = self.out('actual', 'tablet', TABLET_OUTPUT)
        content = self.out('actual', 'content', CONTENT_OUTPUT)
        cinema = self.out('actual', 'tablet', CINEMA_OUTPUT)
        with tempfile.TemporaryDirectory(prefix='bp-hc-t-') as directory:
            for label, text in (('TabletVideoLayout', tablet), ('TabletCinemaLayout', cinema)):
                with self.assertRaises(ValueError):
                    collapse.advance_layout(REPO, text, directory, label)
            with self.assertRaises(ValueError):
                collapse.advance_content(REPO, content, directory)
            with self.assertRaises(ValueError):
                collapse.advance_layout(REPO, 'package x\n', directory, 'TabletVideoLayout')
            with self.assertRaises(ValueError):
                collapse.advance_layout(REPO, tablet, directory, 'Unknown')
            before = self.out('before', 'tablet', TABLET_OUTPUT)
            drifted = before.replace(collapse.TABLET_HUNKS[1]['before'], 'val pagerState = somethingElse()\n', 1)
            with self.assertRaises(ValueError):
                collapse.advance_layout(REPO, drifted, directory, 'TabletVideoLayout')
            duplicated = before + collapse.TABLET_HUNKS[3]['before']
            with self.assertRaises(ValueError):
                collapse.advance_layout(REPO, duplicated, directory, 'TabletVideoLayout')

    # --- boundaries --------------------------------------------------------------------------------------------------------
    def test_outputs_inside_or_around_the_archive_are_rejected_and_the_archive_is_unchanged(self):
        before = {p.name: p.read_bytes() for p in (REPO / collapse.ARCHIVE).iterdir()}
        with tempfile.TemporaryDirectory(prefix='bp-hc-t-') as directory:
            for candidate in (REPO / collapse.ARCHIVE / 'child', REPO / 'desktop/upstream-slices', REPO / collapse.ARCHIVE):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    collapse.validate_outputs(REPO, candidate)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                self.holder.generate(REPO, REPO / collapse.ARCHIVE / 'child')
            collapse.validate_outputs(REPO, directory, None)
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / collapse.ARCHIVE).iterdir()})

    def test_the_layers_are_deterministic(self):
        with tempfile.TemporaryDirectory(prefix='bp-hc-d-') as directory:
            second = Path(directory)
            self.holder.generate(REPO, second / 'holder')
            self.content.generate(REPO, second / 'content')
            run_tablet(second / 'tablet')
            for owner, files in (('holder', (HOLDER_OUTPUT, RECOVERY_OUTPUT)), ('content', (CONTENT_OUTPUT,)),
                                 ('tablet', (TABLET_OUTPUT, CINEMA_OUTPUT))):
                for relative in files:
                    self.assertEqual((self.root / 'actual' / owner / relative).read_bytes(), (second / owner / relative).read_bytes(),
                                     relative)
            for owner in ('holder', 'content', 'tablet'):
                for proof in (self.root / 'actual' / owner / collapse.PROOF_DIRECTORY).glob('*.json'):
                    self.assertEqual(proof.read_bytes(), (second / owner / collapse.PROOF_DIRECTORY / proof.name).read_bytes())

    def test_changed_generated_files_are_balanced_and_use_lf_only(self):
        for owner, relative in (('holder', HOLDER_OUTPUT), ('holder', RECOVERY_OUTPUT), ('tablet', TABLET_OUTPUT),
                                ('tablet', CINEMA_OUTPUT), ('content', CONTENT_OUTPUT)):
            data = (self.root / 'actual' / owner / relative).read_bytes()
            self.assertNotIn(b'\r', data, relative)
            balanced(self, data.decode('utf-8'), relative, self.syntax)
        balanced(self, collapse.HOLDER_BLOCK, 'holder block', self.syntax)

    def test_gradle_inputs_and_inventory_registration(self):
        build = text_of(REPO / 'desktop/build.gradle.kts')
        for task in TASKS:
            block = build.split('val ' + task + ' by tasks.registering', 1)[1].split('\nval ', 1)[0]
            self.assertEqual(1, block.count('inputs.file("tools/v046_detail_holder_collapse.py")'), task)
            self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-detail-holder-collapse")'), task)
        self.assertEqual(3, build.count('upstream-slices/v046-detail-holder-collapse'))
        sync = load('sync-upstream.py', 'hc_sync')
        rows = [r for r in sync.adapted_compatibility_inputs(REPO) if r['localPath'].startswith(collapse.ARCHIVE + '/')]
        self.assertEqual(2, len(rows))
        self.assertTrue(all(not r['referenceOnly'] and r['features'] == [FEATURE] and r['upstreamCommit'] == collapse.COMMIT
                            for r in rows))
        self.assertEqual({collapse.UPSTREAM_PATHS[name] for name in collapse.EXPECTED}, {r['path'] for r in rows})
        catalog = json.loads(text_of(REPO / 'desktop/tools/compatibility-source-inputs.json'))
        self.assertEqual(1, catalog['buildInputRoots'].count(collapse.ARCHIVE))
        archive = [a for a in catalog['archives'] if a['directory'] == collapse.ARCHIVE]
        self.assertEqual(1, len(archive))
        self.assertEqual(sorted(['LICENSE', 'manifest.json'] + sorted(collapse.EXPECTED)),
                         sorted(Path(f['path']).name for f in archive[0]['files']))

    def test_the_hooks_name_this_module_in_exactly_the_three_producers_and_no_other_generator_does(self):
        owners = sorted(p.name for p in TOOLS.glob('*.py') if 'v046_detail_holder_collapse' in text_of(p))
        self.assertEqual(['extract-upstream-video-content-full.py', 'extract-upstream-video-detail-holder.py',
                          'extract-upstream-video-tablet-full.py'], owners)
        named = sorted(p.name for p in TOOLS.glob('*.py') if 'VideoDetailInlineScrollRecovery' in text_of(p))
        self.assertEqual(['v046_detail_holder_collapse.py'], named)

    # --- the decision premises ----------------------------------------------------------------------------------------------
    def test_decision_premises_hold_in_the_generated_tree(self):
        holder = self.out('actual', 'holder', HOLDER_OUTPUT)
        content = self.out('actual', 'content', CONTENT_OUTPUT)
        policy = lf_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/screen/PortraitDetailPresentationPolicy.kt')
        autopause = lf_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/screen/PlayerCollapseAutoPausePolicy.kt')
        scroll = lf_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/policy/VideoDetailScrollCoordinator.kt')
        # v030-detail-glass-gated-chrome-blur: already carried by the retained VideoContentSection
        self.assertIn('val chromeBlurActive = !isPlayerCollapsed || liquidGlassEnabled', content)
        self.assertIn('(!isPlayerCollapsed || liquidGlassEnabledForTabBar)', content)
        # v042-detail-cover-169-skeleton: 16:9 geometry and an always animated skeleton
        self.assertEqual(1, holder.count('preferLayoutWidth16x9Inline = '))
        self.assertEqual(0, holder.count('preferLayoutWidth16x10Inline'))
        self.assertEqual(1, len(re.findall(r'VideoDetailSkeleton\(\s*animated = true,\s*\)', holder)))
        # v030-detail-chrome-hidden-paused-collapsed and v042-compact-comment-autopause: the pause-hide model is absent
        self.assertEqual(0, holder.count('hidePausedPlayerForComments'))
        self.assertEqual(0, policy.count('shouldHidePausedInlinePlayerForComments'))
        self.assertEqual(0, autopause.count('resolvePlayerCollapsedForAutoPause'))
        self.assertEqual(0, autopause.count('resolveIsPlaybackPausedForInlineLayout'))
        self.assertEqual(1, len(re.findall(r'isCompactFoldableCoverWindow &&\s+useOfficialInlinePortraitDetailExperience &&', policy)))
        self.assertEqual(1, len(re.findall(r'\): Boolean = compactForCommentTab\s', scroll)))
        self.assertEqual(1, len(re.findall(r'val playerCollapsedForAutoPause =\s+isPlayerCollapsed \|\| skipGesturePlayerCollapse', holder)))
        # v042-glass-pager-vertical-priority: the pager gesture still lacks the v0.4.6 Miuix branch and its parameter
        pager = lf_of(REPO / 'app/src/main/java/com/android/purebilibili/core/ui/common/VerticalPriorityPagerGesture.kt')
        self.assertEqual(0, pager.count('forceVerticalPriority'))
        # v046-collapse-height-shared: the phone collapse state is still the pinned v0.2.5 file
        collapse_state = lf_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailInlineCollapseState.kt')
        self.assertEqual(0, collapse_state.count('reconcileLayout'))
        self.assertEqual(1, holder.count('introScrollPastCollapseThreshold by rememberSaveable'))


if __name__ == '__main__':
    unittest.main()
