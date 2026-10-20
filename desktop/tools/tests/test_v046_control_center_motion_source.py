"""v0.4.6 control-center drawer motion (roadmap slice plr-control-center-motion): the whole LandscapeSidePanel, the new
ControlCenterMotionSpec, VideoControlCenterMotion, DanmakuSettingsPanelHost and DanmakuSettingsPanelAppearance carried
through the fullscreen-controls producer, thirteen counted end-drawer hunks on the vendored overlay and three counted
hunks plus one counted item rule on the settings panel.

Source checks only: nothing is compiled and no JVM test runs here. The hand-written Kotlin regression class is counted
and checked for balanced delimiters; the Linux compile and JVM run happen later.
"""
from pathlib import Path
import difflib
import hashlib
import importlib.util
import io
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v037_player_closure as closure
import v046_control_center_motion as cm


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync_upstream_control_center', 'sync-upstream.py')
OUT_ROOT = 'com/android/purebilibili/'
HAND_WRITTEN = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopControlCenterMotionRegressionTest.kt'
GRADLE = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
CATALOG = json.loads((TOOLS / 'compatibility-source-inputs.json').read_bytes())
GAPS = (TOOLS / 'symbol-closure-expected-gaps.json').read_text(encoding='utf-8')
PRODUCER = (TOOLS / 'extract-upstream-video-player-full-controls.py').read_text(encoding='utf-8')
# emitted .kt files this slice changes, and the ones it adds
CHANGED = {'LandscapeSidePanel.kt', 'VideoPlayerOverlay.kt', 'VideoSettingsPanel.kt'}
NEW = {'ControlCenterMotionSpec.kt', 'VideoControlCenterMotion.kt', 'DanmakuSettingsPanelHost.kt',
       'DanmakuSettingsPanelAppearance.kt'}
NEW_NAMES = ('ControlCenterMotionSpec', 'VideoControlCenterMotion', 'shouldCloseVideoControlCenter',
             'rememberVideoControlCenterMotion', 'VideoControlCenterHandle', 'VideoControlCenterGroups',
             'useDanmakuControlCenterMotion', 'DanmakuSettingsPanelHost', 'DanmakuPanelDismissAction',
             'rememberDanmakuPanelColors', 'DanmakuSyncButton', 'landscapeControlCenterGroup',
             'landscapeEndDrawerPadding', 'LandscapeControlCenterGroups', 'LocalLandscapeControlCenterGroups')
SLICE_WORDS = re.compile(r'endDrawer|landscapeControlCenterGroup|closeProgress|enableControlCenterMotion|'
                         r'LandscapeEndDrawer|landscapeEndDrawerPadding|ControlCenter', re.I)
SETTINGS_WORDS = re.compile(r'groups|controlCenter|ControlCenter|sheetState|dragHandle|DragHandle', re.I)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def f32(value):
    return struct.unpack('f', struct.pack('f', value))[0]


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


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def git_blob(path):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                    cm.COMMIT + ':' + path])


def keyword_opcodes(emitted, upstream, words):
    """Line opcodes between the emitted file and the upstream blob whose lines mention the slice vocabulary."""
    a, b = emitted.split('\n'), upstream.split('\n')
    matcher = difflib.SequenceMatcher(None, a, b, autojunk=False)
    return [(tag, '\n'.join(a[i1:i2]), '\n'.join(b[j1:j2])) for tag, i1, i2, j1, j2 in matcher.get_opcodes()
            if tag != 'equal' and words.search('\n'.join(a[i1:i2] + b[j1:j2]))]


class ControlCenterMotionSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-cc-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.sources, cls.raws, cls.manifest = cm.fixed(REPO)
        controls = load('cc_controls', 'extract-upstream-video-player-full-controls.py')
        controls.generate(REPO, cls.root / 'with', False, cls.root / 'with-tests')
        cls.controls = controls
        cls.now = tree(cls.root / 'with')
        cls.tests_now = tree(cls.root / 'with-tests')
        # the same producer with the layers of this slice patched out: the retained v0.3.7 behaviour
        baseline = load('cc_controls_baseline', 'extract-upstream-video-player-full-controls.py')
        with mock.patch.object(cm, 'chain_source', lambda repo, path, previous: previous), \
                mock.patch.object(cm, 'definer_files', lambda repo: []), \
                mock.patch.object(cm, 'advance_emitted', lambda rel, text: (text, [])), \
                mock.patch.object(cm, 'write_proofs', lambda repo, output: []), \
                mock.patch.object(cm, 'emit_original_tests', lambda repo, test_output: []):
            baseline.generate(REPO, cls.root / 'without', False, cls.root / 'without-tests')
        cls.before = tree(cls.root / 'without')
        cls.tests_before = tree(cls.root / 'without-tests')

    def emitted(self, name):
        if name == 'VideoPlayerOverlay.kt':
            path = cm.OVERLAY_RELATIVE
        elif name == 'VideoSettingsPanel.kt':
            path = cm.SETTINGS_RELATIVE
        else:
            path = cm.RELATIVE[name]
        return self.now[path].decode('utf-8')

    def baseline(self, name):
        path = cm.OVERLAY_RELATIVE if name == 'VideoPlayerOverlay.kt' else cm.SETTINGS_RELATIVE
        return self.before[path].decode('utf-8')

    # ---- archive integrity -------------------------------------------------------------------------------------

    def test_any_changed_leaf_manifest_or_extra_leaf_is_rejected(self):
        for name in sorted(cm.EXPECTED | {'manifest.json', 'LICENSE'}):
            with self.subTest(changed=name), tempfile.TemporaryDirectory(prefix='bp-cc-t-') as temp:
                copy = Path(temp) / cm.ARCHIVE
                shutil.copytree(REPO / cm.ARCHIVE, copy)
                with (copy / name).open('ab') as handle:
                    handle.write(b' ')
                with self.assertRaises(ValueError):
                    cm.fixed(temp)
        with tempfile.TemporaryDirectory(prefix='bp-cc-t-') as temp:
            copy = Path(temp) / cm.ARCHIVE
            shutil.copytree(REPO / cm.ARCHIVE, copy)
            (copy / 'Extra.kt').write_text('package x\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                cm.fixed(temp)
        with tempfile.TemporaryDirectory(prefix='bp-cc-t-') as temp:
            copy = Path(temp) / cm.ARCHIVE
            shutil.copytree(REPO / cm.ARCHIVE, copy)
            (copy / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                cm.fixed(temp)

    def test_manifest_pins_are_the_real_producer_predecessor_blobs_and_adaptations(self):
        panel_path = cm.UPSTREAM_PATHS[cm.PANEL_FILE]
        self.assertEqual(cm.PANEL_PREDECESSOR_SHA256, sha(closure.fixed_sources()[panel_path]))
        self.assertEqual(cm.MANIFEST_SHA256, sha((REPO / cm.ARCHIVE / 'manifest.json').read_bytes()))
        self.assertEqual('v046-control-center-motion', Path(cm.ARCHIVE).name)
        self.assertEqual([dict(file=cm.HOST_FILE, label=label, before=before, after=after)
                          for before, after, label in cm.HOST_EDITS], self.manifest['windowsAdaptations'])
        self.assertEqual([dict(file=cm.MOTION_TEST_FILE, label=label, before=before, after=after)
                          for before, after, label in cm.ORIGINAL_TEST_EDITS], self.manifest['originalTestEdits'])
        self.assertEqual(13, len(cm.OVERLAY_HUNKS))
        self.assertEqual(3, len(cm.SETTINGS_HUNKS))
        self.assertEqual(13, len({h['label'] for h in cm.OVERLAY_HUNKS}))
        self.assertEqual(3, len({h['label'] for h in cm.SETTINGS_HUNKS}))
        self.assertFalse(self.manifest['fullStableParityQualified'])
        text = ' '.join(self.manifest['notCarried'])
        for needle in ('DanmakuSettingsPanel.kt', 'VideoPlayerSection.kt', 'PortraitFullscreenOverlay.kt', 'TopControlBar.kt'):
            self.assertIn(needle, text)

    @unittest.skipUnless(commit_present(cm.COMMIT), 'the v0.4.6 upstream commit is not in the local object store')
    def test_every_leaf_is_the_v046_blob_and_every_hunk_after_text_is_upstream(self):
        for row in self.manifest['files']:
            self.assertEqual(git_blob(row['path']), self.raws[row['filename']], row['filename'])
        for path, blob, hunks in ((cm.OVERLAY_PATH, cm.OVERLAY_UPSTREAM_BLOB, cm.OVERLAY_HUNKS),
                                  (cm.SETTINGS_PATH, cm.SETTINGS_UPSTREAM_BLOB, cm.SETTINGS_HUNKS)):
            upstream = git_blob(path).decode('utf-8')
            self.assertEqual(blob, subprocess.check_output(
                ['git', '-C', str(REPO), 'rev-parse', cm.COMMIT + ':' + path], text=True).strip())
            for hunk in hunks:
                self.assertEqual(1, upstream.count(hunk['after']), hunk['label'])
        settings = git_blob(cm.SETTINGS_PATH).decode('utf-8')
        self.assertEqual(cm.SETTINGS_UPSTREAM_GROUP_ITEMS, len(re.findall(r'(?m)^ *groups\.item \{', settings)))
        self.assertEqual([], cm.ITEM_PATTERN.findall(settings))
        # the upstream v0.3.7 file had exactly as many plain items as v0.4.6 has groups
        v037 = closure.fixed_sources()[cm.SETTINGS_PATH]
        self.assertEqual(cm.SETTINGS_UPSTREAM_GROUP_ITEMS, len(cm.ITEM_PATTERN.findall(v037)))

    @unittest.skipUnless(commit_present(cm.COMMIT), 'the v0.4.6 upstream commit is not in the local object store')
    def test_upstream_motion_cases_that_contradict_the_carried_clock_are_recorded_with_their_calculation(self):
        original = self.sources[cm.MOTION_TEST_FILE]
        motion = self.sources[cm.MOTION_FILE]
        self.assertIn('        progress = (progress + delta).coerceIn(0f, 1.5f)\n', motion)
        self.assertEqual(1, original.count('assertEquals(1.14f, motion.progress, absoluteTolerance = 0.0001f)'))
        self.assertEqual(1, original.count('motion.dragBy(20f)\n        assertEquals(1.25f, motion.progress)'))
        # dragBy: progress = clamp(progress + delta, 0, 1.5), evaluated in float32
        progress = f32(0 + 1.0)
        self.assertEqual(1.0, progress)
        progress = min(max(f32(progress + f32(0.4)), 0.0), 1.5)
        self.assertAlmostEqual(1.4, progress, places=5)
        progress = min(max(f32(0.0 + 20.0), 0.0), 1.5)
        self.assertEqual(1.5, progress)
        progress = min(max(f32(progress + f32(-0.25)), 0.0), 1.5)
        self.assertEqual(1.25, progress)
        progress = min(max(f32(progress + f32(-20.0)), 0.0), 1.5)
        self.assertEqual(0.0, progress)
        # the upstream literals are what the spec's dragProgress gives (0.35 resistance, 0.25 cap)
        self.assertAlmostEqual(1.14, 1 + 0.4 * 0.35, places=6)
        self.assertEqual(1.25, 1 + 0.25)

    # ---- the producer layers ------------------------------------------------------------------------------------

    def test_chain_swaps_the_whole_side_panel_and_fails_closed(self):
        path = cm.UPSTREAM_PATHS[cm.PANEL_FILE]
        predecessor = closure.fixed_sources()[path]
        self.assertEqual(self.sources[cm.PANEL_FILE], cm.chain_source(REPO, path, predecessor))
        self.assertEqual(self.sources[cm.PANEL_FILE], cm.chain_source(REPO, path, predecessor.replace('\n', '\r\n')))
        with self.assertRaises(ValueError):
            cm.chain_source(REPO, path, predecessor + ' ')
        with self.assertRaises(ValueError):
            cm.chain_source(REPO, path, self.sources[cm.PANEL_FILE])
        self.assertEqual('anything', cm.chain_source(REPO, cm.COMPONENTS + 'Other.kt', 'anything'))
        self.assertEqual('anything', cm.chain_source(REPO, cm.OVERLAY_PATH, 'anything'))

    def test_host_adaptation_is_one_counted_edit_and_inverts(self):
        original = self.sources[cm.HOST_FILE]
        text, rows = cm.host_source(REPO)
        self.assertEqual(1, len(rows))
        self.assertEqual(1, original.count('import androidx.compose.ui.window.Dialog\n'))
        self.assertEqual(1, text.count('import com.bilipai.desktop.ui.DesktopWindowsDanmakuDialog as Dialog\n'))
        self.assertNotIn('import androidx.compose.ui.window.Dialog\n', text)
        self.assertEqual(original, text.replace(rows[0]['after'], rows[0]['before'], 1))
        drifted = original.replace(rows[0]['before'], '// changed\n', 1)
        with mock.patch.object(cm, 'fixed', lambda repo: ({cm.HOST_FILE: drifted}, {}, {})):
            with self.assertRaises(ValueError):
                cm.host_source(REPO)
        # the alias target takes the call shape the host uses
        dialog = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsDanmakuDialog.kt').read_text(encoding='utf-8')
        signature = dialog[dialog.index('internal fun DesktopWindowsDanmakuDialog('):]
        signature = signature[:signature.index(') {')]
        for parameter in ('onDismissRequest: () -> Unit', 'properties: DialogProperties', 'content: @Composable () -> Unit'):
            self.assertIn(parameter, signature)
        self.assertIn('Dialog(\n        onDismissRequest = requestDismiss,\n        properties = DialogProperties(', text)

    def test_overlay_hunks_are_counted_unique_and_invert(self):
        previous = self.baseline('VideoPlayerOverlay.kt')
        text, rows = cm.advance_overlay(previous)
        self.assertEqual(13, len(rows))
        for hunk in cm.OVERLAY_HUNKS:
            self.assertEqual(1, previous.count(hunk['before']), hunk['label'])
            self.assertEqual(1, text.count(hunk['after']), hunk['label'])
        restored = text
        for hunk in reversed(cm.OVERLAY_HUNKS):
            restored = restored.replace(hunk['after'], hunk['before'], 1)
        self.assertEqual(previous, restored)
        with self.assertRaises(ValueError):
            cm.advance_overlay(text)
        for hunk in cm.OVERLAY_HUNKS:
            with self.subTest(hunk=hunk['label']):
                with self.assertRaises(ValueError):
                    cm.advance_overlay(previous.replace(hunk['before'], '// moved\n', 1))
                with self.assertRaises(ValueError):
                    cm.advance_overlay(previous + hunk['before'])
        self.assertEqual((previous, []), cm.advance_emitted('feature/video/ui/overlay/Other.kt', previous))

    @unittest.skipUnless(commit_present(cm.COMMIT), 'the v0.4.6 upstream commit is not in the local object store')
    def test_overlay_hunks_close_every_end_drawer_difference_to_v046(self):
        upstream = git_blob(cm.OVERLAY_PATH).decode('utf-8')
        self.assertNotEqual([], keyword_opcodes(self.baseline('VideoPlayerOverlay.kt'), upstream, SLICE_WORDS))
        self.assertEqual([], keyword_opcodes(self.emitted('VideoPlayerOverlay.kt'), upstream, SLICE_WORDS))

    def test_settings_panel_hunks_and_item_rule_are_counted_and_fail_closed(self):
        previous = self.baseline('VideoSettingsPanel.kt')
        self.assertEqual(cm.SETTINGS_UPSTREAM_GROUP_ITEMS + cm.SETTINGS_WINDOWS_GROUP_ITEMS, len(cm.ITEM_PATTERN.findall(previous)))
        text, rows = cm.advance_settings_panel(previous)
        self.assertEqual(4, len(rows))
        self.assertEqual(22, rows[-1]['count'])
        self.assertEqual([], cm.ITEM_PATTERN.findall(text))
        self.assertEqual(22, len(re.findall(r'(?m)^ *groups\.item \{', text)))
        self.assertEqual(1, text.count('val groups = VideoControlCenterGroups(this, controlCenterMotion)\n'))
        self.assertEqual(1, text.count('skipPartiallyExpanded = controlCenterMotion != null'))
        self.assertEqual(1, text.count('VideoControlCenterHandle('))
        for hunk in cm.SETTINGS_HUNKS:
            self.assertEqual(1, previous.count(hunk['before']), hunk['label'])
            self.assertEqual(1, text.count(hunk['after']), hunk['label'])
        with self.assertRaises(ValueError):
            cm.advance_settings_panel(text)
        for hunk in cm.SETTINGS_HUNKS:
            with self.subTest(hunk=hunk['label']):
                with self.assertRaises(ValueError):
                    cm.advance_settings_panel(previous.replace(hunk['before'], '// moved\n', 1))
        # one more or one fewer plain item than the pinned 21 + 1 is a different panel
        with self.assertRaises(ValueError):
            cm.advance_settings_panel(previous + '            item { }\n')
        first = cm.ITEM_PATTERN.search(previous)
        with self.assertRaises(ValueError):
            cm.advance_settings_panel(previous[:first.start()] + previous[first.start():].replace('item {', 'itemX {', 1))
        # an item before the group declaration would escape the rule
        anchor = cm.SETTINGS_HUNKS[2]['before']
        with self.assertRaises(ValueError):
            cm.advance_settings_panel(previous.replace(anchor, '            item { }\n' + anchor, 1))

    @unittest.skipUnless(commit_present(cm.COMMIT), 'the v0.4.6 upstream commit is not in the local object store')
    def test_settings_panel_differs_from_v046_only_by_the_windows_native_audio_slot(self):
        upstream = git_blob(cm.SETTINGS_PATH).decode('utf-8')
        self.assertNotEqual([], keyword_opcodes(self.baseline('VideoSettingsPanel.kt'), upstream, SETTINGS_WORDS))
        left = keyword_opcodes(self.emitted('VideoSettingsPanel.kt'), upstream, SETTINGS_WORDS)
        self.assertEqual(1, len(left))
        tag, ours, theirs = left[0]
        self.assertEqual('delete', tag)
        self.assertIn('nativeAudioTrackContent', ours)
        self.assertEqual('', theirs.strip())

    # ---- generated outputs ---------------------------------------------------------------------------------------

    def test_emitted_files_are_the_archive_bytes_apart_from_the_one_host_alias(self):
        for name in (cm.SPEC_FILE, cm.MOTION_FILE, cm.APPEARANCE_FILE, cm.PANEL_FILE):
            with self.subTest(name=name):
                self.assertEqual(self.raws[name], self.now[cm.RELATIVE[name]], name)
        host = self.emitted(cm.HOST_FILE)
        self.assertEqual(cm.host_source(REPO)[0], host)
        self.assertEqual(1, host.count('import com.bilipai.desktop.ui.DesktopWindowsDanmakuDialog as Dialog\n'))
        for name in NEW | {cm.PANEL_FILE}:
            text = self.emitted(name)
            self.assertNotIn('\r', text)
            self.assertEqual([], re.findall(r'(?m)^import (?:android\.|androidx\.media3|androidx\.core)', text), name)
            balanced(self, text)

    def test_overlay_and_settings_panel_carry_the_hunks_and_import_their_modifiers(self):
        overlay_text = self.emitted('VideoPlayerOverlay.kt')
        for hunk in cm.OVERLAY_HUNKS:
            self.assertEqual(1, overlay_text.count(hunk['after']), hunk['label'])
            if hunk['before'] not in hunk['after']:
                self.assertEqual(0, overlay_text.count(hunk['before']), hunk['label'])
        for name in ('landscapeControlCenterGroup', 'landscapeEndDrawerPadding'):
            self.assertEqual(1, overlay_text.count('import com.android.purebilibili.feature.video.ui.components.' + name + '\n'))
        self.assertEqual(0, len(re.findall(r'\.padding\(end = endDrawerReservedWidth\)', overlay_text)))
        self.assertEqual(3, overlay_text.count('.landscapeEndDrawerPadding(endDrawerReservedWidthProvider)'))
        self.assertEqual(4, overlay_text.count('landscapeControlCenterGroup(index = '))
        self.assertEqual(1, overlay_text.count('enableControlCenterMotion = true,'))
        # the earlier layers still hold: the nine v0.4.6 overlay hunks, the follow confirmation and the page cover
        self.assertEqual(1, overlay_text.count('rememberFollowActionWithConfirmation('))
        self.assertIn('hasRenderedFirstFrame = hasRenderedFirstFrame,', overlay_text)
        self.assertIn('coverUrl = coverUrl,', overlay_text)
        balanced(self, overlay_text)
        settings = self.emitted('VideoSettingsPanel.kt')
        self.assertEqual(22, settings.count('groups.item {'))
        self.assertIn('item { nativeAudioTrackContent() }'.replace('item', 'groups.item'), settings)
        self.assertIn('DesktopWindowsVideoEnhancementSettingsContent(showProcessingQuality = false)', settings)
        self.assertIn('import androidx.compose.material3.*\n', settings)
        balanced(self, settings)
        panel = self.emitted(cm.PANEL_FILE)
        self.assertEqual(1, panel.count('internal fun Modifier.landscapeEndDrawerPadding('))
        self.assertEqual(1, panel.count('enableControlCenterMotion: Boolean = false,'))

    def test_only_the_intended_sources_change_and_the_rest_is_byte_identical(self):
        kotlin = {p for p in self.now if p.endswith('.kt')}
        changed = {Path(p).name for p in kotlin if self.now[p] != self.before.get(p)}
        self.assertEqual(CHANGED | NEW, changed)
        self.assertEqual(NEW, {Path(p).name for p in kotlin if p not in self.before})
        for path in kotlin - {p for p in kotlin if Path(p).name in CHANGED | NEW}:
            self.assertEqual(self.before[path], self.now[path], path)
        self.assertEqual({cm.PROOF_DIRECTORY + '/' + name + '.json' for name in NEW | CHANGED},
                         {p for p in self.now if p.startswith(cm.PROOF_DIRECTORY + '/')})
        self.assertEqual([], [p for p in self.before if p.startswith(cm.PROOF_DIRECTORY)])
        # tests: exactly the two original files are new, the rest of the test root is untouched
        new_tests = {Path(p).name for p in self.tests_now if p not in self.tests_before}
        self.assertEqual({cm.DANMAKU_TEST_FILE, cm.MOTION_TEST_FILE}, new_tests)
        for path in self.tests_before:
            self.assertEqual(self.tests_before[path], self.tests_now[path], path)

    def test_original_tests_are_written_only_under_the_test_root_with_three_recorded_literals(self):
        self.assertEqual([], [p for p in self.now if p.endswith('Test.kt')])
        danmaku = self.tests_now[cm.TEST_RELATIVE[cm.DANMAKU_TEST_FILE]]
        self.assertEqual(self.raws[cm.DANMAKU_TEST_FILE], danmaku)
        self.assertEqual(3, danmaku.decode('utf-8').count('@Test'))
        written = self.tests_now[cm.TEST_RELATIVE[cm.MOTION_TEST_FILE]].decode('utf-8')
        original = self.sources[cm.MOTION_TEST_FILE]
        self.assertEqual(6, written.count('@Test'))
        self.assertEqual(6, original.count('@Test'))
        self.assertEqual(written, cm.original_tests(REPO)[cm.MOTION_TEST_FILE])
        replayed = original
        for before, after, label in cm.ORIGINAL_TEST_EDITS:
            self.assertEqual(1, replayed.count(before), label)
            replayed = replayed.replace(before, after, 1)
        self.assertEqual(written, replayed)
        self.assertEqual(3, len(cm.ORIGINAL_TEST_EDITS))
        # only these assertion lines changed; everything else is upstream text
        removed = sorted(line[2:] for line in difflib.ndiff(original.splitlines(), written.splitlines())
                         if line.startswith('- '))
        self.assertEqual(sorted(['        assertEquals(1.14f, motion.progress, absoluteTolerance = 0.0001f)',
                                 '        assertEquals(1.25f, motion.progress)',
                                 '        assertEquals(1f, motion.progress)']), removed)
        for token in ('assertEquals(1.4f, motion.progress, absoluteTolerance = 0.0001f)',
                      'assertEquals(1.5f, motion.progress)', 'assertEquals(1.25f, motion.progress)'):
            self.assertEqual(1, written.count(token), token)
        for before, after, label in cm.ORIGINAL_TEST_EDITS:
            drifted = original.replace(before, '// changed\n', 1)
            with mock.patch.object(cm, 'fixed', lambda repo, d=drifted: ({cm.DANMAKU_TEST_FILE: '', cm.MOTION_TEST_FILE: d}, {}, {})):
                with self.assertRaises(ValueError, msg=label):
                    cm.original_tests(REPO)
        for text in (written, danmaku.decode('utf-8')):
            balanced(self, text)
            self.assertNotIn('org.junit', text)

    def test_proofs_describe_the_emitted_files(self):
        proofs = {Path(p).name[:-5]: json.loads(self.now[p].decode('utf-8')) for p in self.now
                  if p.startswith(cm.PROOF_DIRECTORY + '/')}
        self.assertEqual(7, len(proofs))
        for name in (cm.SPEC_FILE, cm.MOTION_FILE, cm.APPEARANCE_FILE, cm.PANEL_FILE):
            proof = proofs[name]
            self.assertTrue(proof['wholeOriginalEmitted'], name)
            self.assertEqual(sha(self.sources[name]), proof['originalSHA256'])
            self.assertEqual(sha(self.emitted(name)), proof['emittedSHA256'])
            self.assertNotIn('originalToWindows', proof)
        host = proofs[cm.HOST_FILE]
        self.assertFalse(host['wholeOriginalEmitted'])
        self.assertEqual(sha(self.emitted(cm.HOST_FILE)), host['emittedSHA256'])
        self.assertEqual(sha(self.sources[cm.HOST_FILE]), host['originalToWindows']['beforeSHA256'])
        self.assertTrue(host['originalToWindows']['wholeInverseExact'])
        self.assertEqual(1, len(host['originalToWindows']['hunks']))
        self.assertEqual(cm.PANEL_PREDECESSOR_SHA256, proofs[cm.PANEL_FILE]['predecessorSHA256'])
        overlay_proof = proofs['VideoPlayerOverlay.kt']
        self.assertEqual(13, len(overlay_proof['countedHunks']))
        self.assertTrue(overlay_proof['hunksFoundInEmitted'])
        self.assertNotIn('emittedSHA256', overlay_proof)
        self.assertEqual([sha(h['after']) for h in cm.OVERLAY_HUNKS], [h['afterSHA256'] for h in overlay_proof['countedHunks']])
        self.assertEqual(cm.OVERLAY_UPSTREAM_BLOB, overlay_proof['upstreamBlob'])
        settings_proof = proofs['VideoSettingsPanel.kt']
        self.assertNotIn('emittedSHA256', settings_proof)
        self.assertEqual(3, len(settings_proof['countedHunks']))
        self.assertEqual(22, settings_proof['groupItems'])
        self.assertEqual(cm.SETTINGS_UPSTREAM_BLOB, settings_proof['upstreamBlob'])
        for proof in proofs.values():
            self.assertFalse(proof['sourceQualified'])
            self.assertFalse(proof['mainCompiled'])
            self.assertFalse(proof['jvmTestsExecuted'])

    def test_generation_is_deterministic_and_refuses_to_write_into_the_archive(self):
        again = load('cc_controls_again', 'extract-upstream-video-player-full-controls.py')
        again.generate(REPO, self.root / 'again', False, self.root / 'again-tests')
        self.assertEqual(self.now, tree(self.root / 'again'))
        self.assertEqual(self.tests_now, tree(self.root / 'again-tests'))
        archive_before = tree(REPO / cm.ARCHIVE)
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.controls.generate(REPO, REPO / cm.ARCHIVE / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.controls.generate(REPO, self.root / 'ok', False, REPO / cm.ARCHIVE)
        self.assertEqual(archive_before, tree(REPO / cm.ARCHIVE))

    def test_the_verifier_agrees_with_the_real_output_and_names_the_four_new_files(self):
        verifier = load('cc_verify_controls', 'verify-upstream-video-player-full-controls.py')
        report = self.root / 'controls-report.json'
        with mock.patch('sys.stdout', new_callable=io.StringIO):
            verifier.verify(REPO, self.root / 'with', report)
        self.assertTrue(json.loads(report.read_text(encoding='utf-8'))['passed'])
        for name in NEW:
            path = OUT_ROOT + 'feature/video/ui/components/' + name
            self.assertIn(path, verifier.EXPECTED_GENERATED_OUTPUTS)
            self.assertIn(path, verifier.EXPECTED_ALL_OUTPUTS)

    # ---- names, wiring and registration ------------------------------------------------------------------------

    def test_every_new_top_level_name_has_one_definer_among_the_outputs_and_none_in_the_owned_sources(self):
        generated = {p: self.now[p].decode('utf-8') for p in self.now if p.endswith('.kt')}
        owned = {p.name: p.read_text(encoding='utf-8') for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt')}
        for name in NEW_NAMES:
            declaration = re.compile(r'(?m)^[ \t]*(?:(?:internal|private|public|inline)\s+)*'
                                     r'(?:(?:fun|class|object|val|var)\s+(?:[\w.<>?, ]+\.)?' + re.escape(name) + r'\b)')
            with self.subTest(name=name):
                definers = [path for path, text in generated.items() if declaration.search(text)]
                self.assertEqual(1, len(definers), definers)
                self.assertEqual([], [path for path, text in owned.items() if declaration.search(text)])

    def test_the_danmaku_host_is_carried_but_not_called_by_any_windows_body(self):
        for path, raw in self.now.items():
            if path.endswith('.kt') and not path.endswith('DanmakuSettingsPanelHost.kt'):
                self.assertNotIn('DanmakuSettingsPanelHost(', raw.decode('utf-8'), path)
        for path in (REPO / 'desktop/src/main/kotlin').rglob('*.kt'):
            self.assertNotIn('DanmakuSettingsPanelHost(', path.read_text(encoding='utf-8'), path.name)
        # no producer rewrites a Windows body to call it either
        for path in TOOLS.glob('*.py'):
            if path.name != 'v046_control_center_motion.py':
                self.assertNotIn('DanmakuSettingsPanelHost(', path.read_text(encoding='utf-8'), path.name)
        for name in ('rememberDanmakuPanelColors(', 'DanmakuSyncButton('):
            callers = [p for p, raw in self.now.items() if p.endswith('.kt') and name in raw.decode('utf-8')
                       and not p.endswith('DanmakuSettingsPanelAppearance.kt')]
            self.assertEqual([], callers, name)

    def test_gradle_and_producer_wiring(self):
        block = GRADLE.split('val extractOriginalPlayerFullControls by tasks.registering', 1)[1].split('val verifyOriginalPlayerFullControls', 1)[0]
        for line in ('inputs.file("tools/v046_control_center_motion.py")', 'inputs.file("tools/v044_favorite_detail.py")',
                     'inputs.dir("upstream-slices/v046-control-center-motion")'):
            self.assertEqual(1, block.count(line), line)
        self.assertEqual(1, GRADLE.count('upstream-slices/v046-control-center-motion'))
        self.assertEqual(1, GRADLE.count('v046_control_center_motion'))
        # the v0.3.7 copy stays excluded exactly once; no new canonical file is excluded and no new srcDir is needed
        self.assertEqual(1, GRADLE.count('exclude(canonicalOriginalIdentity("%s"))' % cm.UPSTREAM_PATHS[cm.PANEL_FILE]))
        for name in NEW:
            self.assertEqual(0, GRADLE.count(name), name)
        for line in (" from v046_control_center_motion import chain_source as control_center_source\n",
                     " from v046_control_center_motion import advance_emitted as advance_control_center\n",
                     " from v046_control_center_motion import definer_files as control_center_definers\n",
                     " from v046_control_center_motion import validate_outputs as validate_control_center\n",
                     " from v046_control_center_motion import write_proofs as write_control_center_proofs\n",
                     "  from v046_control_center_motion import emit_original_tests as emit_control_center_tests\n"):
            self.assertEqual(1, PRODUCER.count(line), line)
        self.assertEqual(1, PRODUCER.count('control_center_source(REPO,path,overlay_source(REPO,path,episode_selected_source('))
        # the hook is the last layer of the overlay: after the page cover hunk and before the canonical comparison
        emit = PRODUCER.split('def emit(rel,t,origin,mode):', 1)[1].split('def adapt(', 1)[0]
        self.assertLess(emit.index('CALL_SITE_LABELS[\'overlay\']'), emit.index('advance_control_center(rel,t)'))
        self.assertLess(emit.index('advance_control_center(rel,t)'), emit.index('canonical=SOURCES.get'))

    def test_inventory_and_catalog_register_the_archive(self):
        self.assertEqual(1, CATALOG['buildInputRoots'].count(cm.ARCHIVE))
        rows = [row for row in CATALOG['inputs'] if row['localPath'].startswith(cm.ARCHIVE + '/')]
        self.assertEqual(sorted(row['path'] for row in self.manifest['files']), sorted(row['path'] for row in rows))
        self.assertEqual(7, len(rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-control-center-motion'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(cm.COMMIT, row['upstreamCommit'])
            self.assertEqual('adaptedSource', row['kind'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
            self.assertEqual(sha(self.raws[Path(row['localPath']).name]), row['sha256'])
        archives = [row for row in CATALOG['archives'] if row['directory'] == cm.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({cm.ARCHIVE + '/' + name for name in cm.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(cm.ARCHIVE + '/')])
        self.assertEqual(hashlib.sha256((TOOLS / 'compatibility-source-inputs.json').read_bytes().replace(b'\r\n', b'\n')).hexdigest(),
                         sync.COMPATIBILITY_CATALOG_SHA256)
        # The catalog TOTALS change with every slice and are pinned once, by test_sync_upstream.py.

    def test_the_closed_end_drawer_gap_is_gone_from_the_expected_gaps(self):
        self.assertNotIn('feature.video.ui.components.landscapeEndDrawerPadding', GAPS)
        self.assertEqual(1, self.emitted(cm.PANEL_FILE).count('internal fun Modifier.landscapeEndDrawerPadding('))

    # ---- the hand-written regression class ----------------------------------------------------------------------

    def test_hand_written_regression_class_is_counted_and_balanced(self):
        text = HAND_WRITTEN.read_text(encoding='utf-8')
        self.assertNotIn('\r', text)
        balanced(self, text)
        self.assertEqual(9, len(re.findall(r'@Test\s+fun ', text)))
        self.assertIn('package com.bilipai.desktop.ui\n', text)
        self.assertIn('class DesktopControlCenterMotionRegressionTest {', text)
        for forbidden in ('java.io.File', 'Window(', 'application {', 'HttpClient', 'java.net', 'System.getenv', 'ImageComposeScene',
                          'runBlocking', 'org.junit'):
            self.assertNotIn(forbidden, text)
        for symbol in ('ControlCenterMotionSpec', 'DanmakuSettingsPanelAnchor', 'VideoControlCenterMotion', 'useDanmakuControlCenterMotion'):
            self.assertIn('import com.android.purebilibili.feature.video.ui.components.' + symbol + '\n', text)


if __name__ == '__main__':
    unittest.main()
