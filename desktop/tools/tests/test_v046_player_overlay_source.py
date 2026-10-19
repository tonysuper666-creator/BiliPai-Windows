"""v0.4.6 player overlay chrome (roadmap slice plr-overlay-and-controlbar-repin): BottomControlBar, TopControlBar,
PlayerControlGlass, PersistentProgressBar, VideoProgressBarLayoutPolicy, VideoPlayerOverlayContracts,
LandscapeDanmakuComposer and the new PlainLandscapeDanmakuComposer carried whole through the producers that already
own them, plus nine counted hunks on the v0.3.7 VideoPlayerOverlay.

Source checks only: nothing is compiled and no JVM test runs here. The hand-written Kotlin regression class is counted
and checked for balanced delimiters; the Linux compile and JVM run happen later.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
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
import v037_player_chrome as chrome
import v037_player_closure as closure
import v037_player_render as render
import v042_player_readability as readability
import v046_player_overlay as overlay


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync_upstream_player_overlay', 'sync-upstream.py')
OUT_ROOT = 'com/android/purebilibili/'
HAND_WRITTEN = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopPlayerOverlayRepinTest.kt'
DESKTOP_PROGRESS_TEST = REPO / overlay.DESKTOP_TEST_PATH
DIALOG = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsDanmakuDialog.kt'
GRADLE = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
CATALOG = json.loads((TOOLS / 'compatibility-source-inputs.json').read_bytes())
# emitted .kt files that differ from the producer output without this slice, and the ones that did not exist
CHANGED = {'BottomControlBar.kt', 'TopControlBar.kt', 'PlayerControlGlass.kt', 'VideoPlayerOverlay.kt',
           'VideoPlayerOverlayContracts.kt', 'LandscapeDanmakuComposer.kt'}
NEW = {'PersistentProgressBar.kt', 'VideoProgressBarLayoutPolicy.kt'}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


def restore_hunks(value, proof):
    """Applies an inverse_proof backwards."""
    assert sha(value) == proof['afterSHA256']
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        assert ''.join(lines[first:last]) == hunk['after']
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    assert sha(result) == proof['beforeSHA256']
    return result


def restore_whole(value, proof):
    """Applies a v029_comment_search.whole_proof backwards."""
    for edit in reversed(proof['indexedEdits']):
        at = edit['afterOffset']
        assert value[at:at + len(edit['after'])] == edit['after']
        value = value[:at] + edit['before'] + value[at + len(edit['after']):]
    return value


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def git_blob(path):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                    overlay.COMMIT + ':' + path])


class PlayerOverlaySourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-po-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.sources, cls.raws, cls.manifest = overlay.fixed(REPO)
        controls = load('po_controls', 'extract-upstream-video-player-full-controls.py')
        controls.generate(REPO, cls.root / 'with', False, cls.root / 'with-tests')
        cls.controls = controls
        cls.adapt = list(controls.ADAPT)
        cls.now = tree(cls.root / 'with')
        cls.tests_now = tree(cls.root / 'with-tests')
        # the same producer with the layers of this slice patched out: the retained v0.3.7 behaviour. The NVIDIA edits of
        # the bar are anchored on the v0.4.6 text, so the v0.3.7 bar runs without them; every other file is untouched.
        baseline = load('po_controls_baseline', 'extract-upstream-video-player-full-controls.py')
        with mock.patch.object(overlay, 'chain_source', lambda repo, path, previous: previous), \
                mock.patch.object(overlay, 'render_source', lambda repo, path, previous, row, commit: (previous, row, commit)), \
                mock.patch.object(overlay, 'write_proofs', lambda repo, output: []), \
                mock.patch.object(baseline, 'windows_nvidia_control_bar_ui', lambda source, record=False: source):
            baseline.generate(REPO, cls.root / 'without', False, cls.root / 'without-tests')
        cls.before = tree(cls.root / 'without')

    def emitted(self, name):
        path = overlay.OVERLAY_RELATIVE if name == 'VideoPlayerOverlay.kt' else overlay.RELATIVE[name]
        return self.now[path].decode('utf-8')

    # ---- archive integrity -------------------------------------------------------------------------------------

    def test_any_changed_leaf_manifest_or_extra_leaf_is_rejected(self):
        for name in sorted(overlay.EXPECTED | {'manifest.json', 'LICENSE'}):
            with self.subTest(changed=name), tempfile.TemporaryDirectory(prefix='bp-po-t-') as temp:
                copy = Path(temp) / overlay.ARCHIVE
                shutil.copytree(REPO / overlay.ARCHIVE, copy)
                with (copy / name).open('ab') as handle:
                    handle.write(b' ')
                with self.assertRaises(ValueError):
                    overlay.fixed(temp)
        with tempfile.TemporaryDirectory(prefix='bp-po-t-') as temp:
            copy = Path(temp) / overlay.ARCHIVE
            shutil.copytree(REPO / overlay.ARCHIVE, copy)
            (copy / 'Extra.kt').write_text('package x\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                overlay.fixed(temp)

    def test_manifest_pins_are_the_real_producer_predecessors(self):
        chrome_sources, closure_sources, render_sources = chrome.fixed_sources(), closure.fixed_sources(), render.fixed_sources()
        by_name = {Path(path).name: text for path, text in {**chrome_sources, **closure_sources, **render_sources}.items()}
        for name in ('BottomControlBar.kt', 'TopControlBar.kt', 'VideoPlayerOverlayContracts.kt',
                     'LandscapeDanmakuComposer.kt', 'PlayerControlGlass.kt'):
            self.assertEqual(overlay.PREDECESSOR_SHA256[name], sha(by_name[name]), name)
        self.assertEqual(overlay.OVERLAY_PREDECESSOR_SHA256, sha(closure_sources[overlay.OVERLAY_PATH]))
        registry = {row['path']: row['sha256'] for row in json.loads((REPO / 'desktop/upstream-sources.json').read_bytes())['sources']}
        for name in overlay.EXCLUDED_NOW:
            self.assertEqual(overlay.VENDORED_LF_SHA256[name], registry[overlay.UPSTREAM_PATHS[name]], name)
        self.assertEqual(overlay.DESKTOP_TEST_PREVIOUS_SHA256, self.manifest['replacedDesktopTest']['previousSha256'])

    @unittest.skipUnless(commit_present(overlay.COMMIT), 'the v0.4.6 upstream commit is not in the local object store')
    def test_every_leaf_is_the_v046_blob_and_every_hunk_after_text_is_upstream(self):
        for row in self.manifest['files']:
            self.assertEqual(git_blob(row['path']), self.raws[row['filename']], row['filename'])
        upstream = git_blob(overlay.OVERLAY_PATH).decode('utf-8')
        self.assertEqual(overlay.OVERLAY_UPSTREAM_BLOB, subprocess.check_output(
            ['git', '-C', str(REPO), 'rev-parse', overlay.COMMIT + ':' + overlay.OVERLAY_PATH], text=True).strip())
        for hunk in overlay.OVERLAY_HUNKS:
            self.assertEqual(1, upstream.count(hunk['after']), hunk['label'])

    # ---- the producer layers ------------------------------------------------------------------------------------

    def test_chain_swaps_each_whole_file_and_fails_closed(self):
        chrome_sources, closure_sources = chrome.fixed_sources(), closure.fixed_sources()
        previous = {**chrome_sources, **closure_sources}
        for name in overlay.CHAIN_FILES:
            path = overlay.UPSTREAM_PATHS[name]
            with self.subTest(name=name):
                if name in overlay.EXCLUDED_NOW:
                    predecessor = self.controls_canonical(path)
                else:
                    predecessor = previous[path]
                self.assertEqual(self.sources[name], overlay.chain_source(REPO, path, predecessor))
                self.assertEqual(self.sources[name], overlay.chain_source(REPO, path, predecessor.replace('\n', '\r\n')))
                with self.assertRaises(ValueError):
                    overlay.chain_source(REPO, path, predecessor + ' ')
                with self.assertRaises(ValueError):
                    overlay.chain_source(REPO, path, self.sources[name])
        self.assertEqual('anything', overlay.chain_source(REPO, overlay.OVERLAY + 'Other.kt', 'anything'))

    def controls_canonical(self, path):
        from v025_source_paths import canonical_source
        return canonical_source(REPO, path).read_bytes().replace(b'\r\n', b'\n').decode('utf-8')

    def test_render_layer_replaces_only_player_control_glass(self):
        row = {'path': overlay.UPSTREAM_PATHS['PlayerControlGlass.kt'], 'filename': 'PlayerControlGlass.kt'}
        previous = render.fixed_sources()[row['path']]
        text, effective, commit = overlay.render_source(REPO, row['path'], previous, row, 'old')
        self.assertEqual(self.sources['PlayerControlGlass.kt'], text)
        self.assertEqual(overlay.COMMIT, commit)
        archived = next(r for r in self.manifest['files'] if r['filename'] == 'PlayerControlGlass.kt')
        self.assertEqual((archived['bytes'], archived['sha256'], archived['gitBlob']),
                         (effective['bytes'], effective['sha256Bytes'], effective['gitBlob']))
        other = overlay.OVERLAY + 'Other.kt'
        self.assertEqual(('x', row, 'old'), overlay.render_source(REPO, other, 'x', row, 'old'))
        with self.assertRaises(ValueError):
            overlay.render_source(REPO, row['path'], previous + ' ', row, 'old')

    def test_overlay_hunks_are_counted_unique_and_invert(self):
        previous = closure.fixed_sources()[overlay.OVERLAY_PATH]
        text, rows = overlay.advance_overlay(previous)
        self.assertEqual(9, len(rows))
        self.assertEqual(9, len(overlay.OVERLAY_HUNKS))
        self.assertEqual(len({h['label'] for h in overlay.OVERLAY_HUNKS}), 9)
        for hunk in overlay.OVERLAY_HUNKS:
            self.assertEqual(1, previous.count(hunk['before']), hunk['label'])
            self.assertEqual(1, text.count(hunk['after']), hunk['label'])
        restored = text
        for hunk in reversed(overlay.OVERLAY_HUNKS):
            restored = restored.replace(hunk['after'], hunk['before'], 1)
        self.assertEqual(previous, restored)
        with self.assertRaises(ValueError):
            overlay.advance_overlay(previous + ' ')
        with self.assertRaises(ValueError):
            overlay.advance_overlay(text)
        for hunk in overlay.OVERLAY_HUNKS:
            drifted = previous.replace(hunk['before'], hunk['before'] + hunk['before'], 1)
            with mock.patch.object(overlay, 'OVERLAY_PREDECESSOR_SHA256', sha(drifted)):
                with self.assertRaises(ValueError, msg=hunk['label']):
                    overlay.advance_overlay(drifted)

    def test_plain_composer_has_no_android_handle_and_uses_the_owned_dialog(self):
        original = self.sources['PlainLandscapeDanmakuComposer.kt']
        text, rows = overlay.plain_composer(REPO)
        self.assertEqual(6, len(rows))
        for token in overlay.PLAIN_ABSENT:
            self.assertIn(token, original)
            self.assertNotIn(token, text)
        self.assertEqual(1, text.count('import com.bilipai.desktop.ui.DesktopWindowsDanmakuComposerDialog as Dialog\n'))
        replayed = original
        for row in rows:
            self.assertEqual(1, replayed.count(row['before']), row['label'])
            replayed = replayed.replace(row['before'], row['after'], 1)
        self.assertEqual(text, replayed)
        for row in rows:
            drifted = original.replace(row['before'], '// changed\n', 1)
            with mock.patch.object(overlay, 'fixed', lambda repo, d=drifted: ({overlay.PLAIN_FILE: d}, {}, {})):
                with self.assertRaises(ValueError, msg=row['label']):
                    overlay.plain_composer(REPO)
        composer =DIALOG.read_text(encoding='utf-8')
        self.assertEqual(1, composer.count('internal fun DesktopWindowsDanmakuComposerDialog('))
        signature = composer[composer.index('internal fun DesktopWindowsDanmakuComposerDialog('):]
        signature = signature[:signature.index(') {')]
        for parameter in ('onDismissRequest: () -> Unit', 'properties: DialogProperties', 'content: @Composable () -> Unit'):
            self.assertIn(parameter, signature)
        self.assertEqual(1, composer.count('internal const val DESKTOP_WINDOWS_DANMAKU_COMPOSER_DIALOG_HEIGHT_DP = 420'))

    # ---- generated outputs ---------------------------------------------------------------------------------------

    def test_emitted_files_carry_the_v046_semantics_and_no_android_ports(self):
        bar = self.emitted('BottomControlBar.kt')
        for token in ('fullscreenDanmakuActionsInDedicatedDocks: Boolean = false,', 'private fun PlainLandscapeDockActionRow(',
                      'AppContentColors.selectionAccent(', 'windowsEnhancementContent: (@Composable ((Boolean) -> Unit) -> Unit)? = null,',
                      'DesktopHomeCardWindowMetrics as LocalConfiguration', 'DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset'):
            self.assertIn(token, bar)
        self.assertNotIn('danmakuSendInTopBar', bar)
        self.assertEqual(1, bar.count('.widthIn(min = 140.dp, max = 220.dp)\n                        .verticalScroll(rememberScrollState())'))
        top = self.emitted('TopControlBar.kt')
        for token in ('onDanmakuSettingsClick: (() -> Unit)? = null,', 'private fun AdaptivePlayerTitleInfoDock(',
                      'DesktopOriginalPlayerControlsPlatform', 'return context.readBatteryPercent()'):
            self.assertIn(token, top)
        self.assertNotIn('onDanmakuInputClick', top)
        self.assertEqual(1, top.count('private fun PlayerTitleInfoDock('))
        overlay_text = self.emitted('VideoPlayerOverlay.kt')
        for hunk in overlay.OVERLAY_HUNKS:
            self.assertEqual(1, overlay_text.count(hunk['after']), hunk['label'])
        self.assertIn('windowsEnhancementContent = windowsEnhancementContent', overlay_text)
        self.assertEqual(1, overlay_text.count('rememberFollowActionWithConfirmation('))
        self.assertNotIn('danmakuSendInTopBar', overlay_text)
        self.assertNotIn('onDanmakuInputClick = onDanmakuInputClick,\n                        onCastClick', overlay_text)
        contracts = self.emitted('VideoPlayerOverlayContracts.kt')
        for token in ('val hasRenderedFirstFrame: Boolean = true,', 'val suppressTransientOverlaysForTransition: Boolean = false,',
                      'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player'):
            self.assertIn(token, contracts)
        self.assertEqual(self.sources['LandscapeDanmakuComposer.kt'], self.emitted('LandscapeDanmakuComposer.kt'))
        self.assertEqual(self.sources['PersistentProgressBar.kt'], self.emitted('PersistentProgressBar.kt'))
        self.assertEqual(self.sources['VideoProgressBarLayoutPolicy.kt'], self.emitted('VideoProgressBarLayoutPolicy.kt'))
        glass = self.emitted('PlayerControlGlass.kt')
        self.assertIn('LocalAppIconButtonGlassSurfaceProvided provides true,', glass)
        self.assertNotIn('import android.', glass)
        self.assertEqual(self.sources['PlayerControlGlass.kt'], glass)
        for name in overlay.RELATIVE:
            text = self.emitted(name)
            self.assertNotIn('\r', text)
            self.assertEqual([], re.findall(r'(?m)^import (?:android\.|androidx\.media3|androidx\.core)', text), name)
            balanced(self, text)

    def test_only_the_intended_sources_change_and_the_rest_is_byte_identical(self):
        kotlin = {p for p in self.now if p.endswith('.kt')}
        changed = {Path(p).name for p in kotlin if self.now[p] != self.before.get(p)}
        self.assertEqual(CHANGED | NEW, changed)
        for path in kotlin - {p for p in kotlin if Path(p).name in CHANGED | NEW}:
            self.assertEqual(self.before[path], self.now[path], path)
        for name in NEW:
            self.assertFalse(any(Path(p).name == name for p in self.before if p.endswith('.kt')), name)
        # the plain composer is emitted in both runs (it needs no earlier layer), the proof directory only now
        self.assertIn(OUT_ROOT + 'feature/video/ui/components/PlainLandscapeDanmakuComposer.kt', self.before)
        self.assertTrue(any(p.startswith(overlay.PROOF_DIRECTORY + '/') for p in self.now))
        self.assertFalse(any(p.startswith(overlay.PROOF_DIRECTORY + '/') for p in self.before))

    def test_proofs_recover_the_v046_originals(self):
        for name in overlay.PROOF_FILES:
            proof = json.loads(self.now[overlay.PROOF_DIRECTORY + '/' + name + '.json'])
            self.assertEqual(overlay.COMMIT, proof['upstreamCommit'])
            self.assertEqual(overlay.MANIFEST_SHA256, proof['sourceManifestSHA256'])
            self.assertEqual(sha(self.sources[name]), proof['originalSHA256'])
            self.assertEqual(self.sources[name] == self.emitted(name), proof['wholeOriginalEmitted'])
            self.assertEqual(self.sources[name], restore_hunks(self.emitted(name), proof['originalToWindows']), name)
            self.assertFalse(proof['sourceQualified'])
        for name in ('BottomControlBar.kt', 'TopControlBar.kt'):
            proof = json.loads(self.now['v037-source-proofs/' + name + '.json'])
            self.assertEqual(overlay.COMMIT, proof['upstreamCommit'])
            self.assertEqual(self.sources[name], restore_whole(self.emitted(name), proof), name)
        glass = json.loads(self.now['v037-render-source-proofs/PlayerControlGlass.kt.json'])
        self.assertEqual(overlay.COMMIT, glass['upstreamCommit'])
        self.assertEqual(self.sources['PlayerControlGlass.kt'], restore_whole(self.emitted('PlayerControlGlass.kt'), glass))
        hunks = json.loads(self.now[overlay.PROOF_DIRECTORY + '/VideoPlayerOverlay.kt.json'])
        self.assertEqual(9, len(hunks['hunks']))
        self.assertEqual(overlay.OVERLAY_PREDECESSOR_SHA256, hunks['predecessorSHA256'])
        self.assertEqual(sha(self.emitted('VideoPlayerOverlay.kt')), hunks['emittedSHA256'])
        self.assertFalse(hunks['wholeOriginalSelected'])
        title = json.loads(self.now['v042-player-readability-proofs/TopControlBar.kt.json'])
        self.assertTrue(title['fullInputInverseRecovered'])

    def test_original_test_is_emitted_and_the_desktop_copy_holds_the_upstream_bytes(self):
        key = 'com/android/purebilibili/feature/video/ui/overlay/PersistentProgressBarPolicyTest.kt'
        self.assertEqual(self.raws['PersistentProgressBarPolicyTest.kt'], self.tests_now[key])
        self.assertEqual(1, self.tests_now[key].decode('utf-8').count('@Test'))
        self.assertEqual(self.raws['VideoProgressBarLayoutPolicyTest.kt'], DESKTOP_PROGRESS_TEST.read_bytes())
        self.assertEqual(2, DESKTOP_PROGRESS_TEST.read_text(encoding='utf-8').count('@Test'))
        # the producer's own policy tests keep being emitted next to it
        for name in ('BottomControlBarLayoutPolicyTest', 'BottomControlBarPolicyTest', 'TopControlBarLayoutPolicyTest',
                     'TopControlBarPolicyTest'):
            self.assertIn('com/android/purebilibili/feature/video/ui/overlay/' + name + '.kt', self.tests_now)
        self.assertNotIn('assertEqual(20, policy.baseHeightWithoutChapterDp)', DESKTOP_PROGRESS_TEST.read_text(encoding='utf-8'))
        compact = self.emitted('VideoProgressBarLayoutPolicy.kt')
        self.assertIn('baseHeightWithoutChapterDp = 12,', compact)
        self.assertNotIn('baseHeightWithoutChapterDp = 32,', compact)

    def test_generation_is_deterministic(self):
        controls = load('po_controls_again', 'extract-upstream-video-player-full-controls.py')
        controls.generate(REPO, self.root / 'again', False, self.root / 'again-tests')
        self.assertEqual(self.now, tree(self.root / 'again'))
        self.assertEqual(self.tests_now, tree(self.root / 'again-tests'))

    def test_output_inside_the_archive_is_rejected_before_anything_is_written(self):
        before = tree(REPO / overlay.ARCHIVE)
        controls = load('po_controls_overlap', 'extract-upstream-video-player-full-controls.py')
        with self.assertRaises(ValueError):
            controls.generate(REPO, REPO / overlay.ARCHIVE / 'child')
        with self.assertRaises(ValueError):
            overlay.validate_outputs(REPO, REPO / overlay.ARCHIVE.rsplit('/', 1)[0])
        self.assertEqual(before, tree(REPO / overlay.ARCHIVE))

    def test_public_names_of_the_emitted_files_are_declared_once_in_the_controls_output(self):
        pattern = re.compile(r'(?m)^(?:@\w+(?:\([^)]*\))?\s+)*((?:internal |public |inline |data |sealed |enum |const |suspend )*)'
                             r'(fun|val|var|class|object|interface)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)')
        declared = {}
        for path, raw in self.now.items():
            if not path.endswith('.kt'):
                continue
            for modifiers, kind, name in pattern.findall(raw.decode('utf-8')):
                if 'private' not in modifiers:
                    declared.setdefault((kind, name), []).append(path)
        for name in sorted(set(overlay.EXCLUDED_NOW) | {'PlainLandscapeDanmakuComposer.kt', 'PlayerControlGlass.kt',
                                                         'LandscapeDanmakuComposer.kt'}):
            path = overlay.RELATIVE[name]
            mine = {key for key, paths in declared.items() if path in paths}
            self.assertTrue(mine, name)
            for key in mine:
                self.assertEqual([path], declared[key], (name, key))

    # ---- wiring and registration ---------------------------------------------------------------------------------

    def test_gradle_excludes_the_two_vendored_copies_and_lists_the_inputs_once(self):
        for name in overlay.EXCLUDED_NOW:
            line = 'exclude(canonicalOriginalIdentity("%s"))' % overlay.UPSTREAM_PATHS[name]
            self.assertEqual(1, GRADLE.count(line), name)
        for path in (overlay.UPSTREAM_PATHS['BottomControlBar.kt'], overlay.UPSTREAM_PATHS['TopControlBar.kt'],
                     overlay.UPSTREAM_PATHS['VideoPlayerOverlayContracts.kt'], overlay.UPSTREAM_PATHS['PlayerControlGlass.kt'],
                     overlay.OVERLAY_PATH):
            self.assertEqual(0, GRADLE.count('exclude(canonicalOriginalIdentity("%s"))' % path), path)
        block = GRADLE.split('val extractOriginalPlayerFullControls by tasks.registering', 1)[1].split('val verifyOriginalPlayerFullControls', 1)[0]
        for line in ('inputs.file("tools/v046_player_overlay.py")', 'inputs.file("tools/v044_favorite_detail.py")',
                     'inputs.dir("upstream-slices/v046-player-overlay")'):
            self.assertEqual(1, block.count(line), line)
        self.assertEqual(1, GRADLE.count('upstream-slices/v046-player-overlay'))
        producer = (TOOLS / 'extract-upstream-video-player-full-controls.py').read_text(encoding='utf-8')
        self.assertNotIn("apply_player_readability(REPO,rel,t,OUTPUT)\n emit(rel,t,rel,'complete-original-bottom", producer)
        for line in ("from v046_player_overlay import chain_source as overlay_source\n",
                     " from v046_player_overlay import validate_outputs as validate_player_overlay\n",
                     " from v046_player_overlay import write_proofs as write_player_overlay_proofs\n",
                     "  from v046_player_overlay import emit_original_tests as emit_player_overlay_tests\n"):
            self.assertEqual(1, producer.count(line), line)
        self.assertEqual(1, (TOOLS / 'v037_player_render.py').read_text(encoding='utf-8').count('from v046_player_overlay import render_source as v046_render_source'))
        self.assertEqual(1, (TOOLS / 'v037_player_chrome.py').read_text(encoding='utf-8').count('from v046_player_overlay import effective_original'))

    def test_inventory_and_catalog_register_the_archive(self):
        self.assertEqual(1, CATALOG['buildInputRoots'].count(overlay.ARCHIVE))
        rows = [row for row in CATALOG['inputs'] if row['localPath'].startswith(overlay.ARCHIVE + '/')]
        self.assertEqual(sorted(row['path'] for row in self.manifest['files']), sorted(row['path'] for row in rows))
        self.assertEqual(10, len(rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-player-overlay'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(overlay.COMMIT, row['upstreamCommit'])
            self.assertEqual('adaptedSource', row['kind'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
            self.assertEqual(sha(self.raws[Path(row['localPath']).name]), row['sha256'])
        archives = [row for row in CATALOG['archives'] if row['directory'] == overlay.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({overlay.ARCHIVE + '/' + name for name in overlay.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(overlay.ARCHIVE + '/')])
        self.assertEqual(hashlib.sha256((TOOLS / 'compatibility-source-inputs.json').read_bytes().replace(b'\r\n', b'\n')).hexdigest(),
                         sync.COMPATIBILITY_CATALOG_SHA256)
        # The catalog TOTALS change with every slice and are pinned once, by test_sync_upstream.py.

    def test_manifest_states_what_is_carried_and_what_is_not(self):
        self.assertEqual(7, len(self.manifest['producerPredecessors']))
        self.assertEqual(2, len(self.manifest['replacedVendoredCopies']))
        self.assertEqual(9, self.manifest['overlayHunks']['hunks'])
        self.assertGreaterEqual(len(self.manifest['behaviourChanges']), 5)
        text = ' '.join(self.manifest['notCarried'])
        for needle in ('VideoPlayerOverlay.kt whole file', 'plr-control-center-motion', 'lst-episode-selector-collection',
                       'PiliPlusDanmakuSendIcon', 'appIconGlyphTextStyle', 'hasRenderedFirstFrame', 'native mpv canvas'):
            self.assertIn(needle, text)
        self.assertFalse(readability.TITLE.endswith('BottomControlBar.kt'))

    def test_hand_written_regression_class_is_counted_and_balanced(self):
        text = HAND_WRITTEN.read_text(encoding='utf-8')
        self.assertNotIn('\r', text)
        balanced(self, text)
        self.assertEqual(5, len(re.findall(r'@Test\s+fun ', text)))
        self.assertIn('package com.bilipai.desktop.ui\n', text)
        self.assertIn('class DesktopPlayerOverlayRepinTest {', text)
        for forbidden in ('java.io.File', 'Window(', 'application {', 'HttpClient', 'java.net', 'System.getenv', 'ImageComposeScene'):
            self.assertNotIn(forbidden, text)
        for symbol in ('resolveVideoProgressBarLayoutPolicy', 'shouldShowBufferingIndicator'):
            self.assertIn('import com.android.purebilibili.feature.video.ui.overlay.' + symbol + '\n', text)
            self.assertEqual(1, len(re.findall(r'(?m)^internal fun %s\(|^fun %s\(' % (symbol, symbol),
                                               self.emitted('VideoPlayerOverlay.kt') + self.emitted('VideoProgressBarLayoutPolicy.kt'))), symbol)


if __name__ == '__main__':
    unittest.main()
