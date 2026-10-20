"""v0.4.6 portrait pager family (roadmap slice plr-portrait-story-pager-repin): the whole v0.4.6 PortraitFullscreenOverlay,
PortraitInteractionBar, PortraitBottomInputBar, PortraitProgressBar, PortraitVideoLoadPolicy and PortraitPagerSwitchPolicy
carried through the producers that already own them, the new PortraitControlsVisibilityPolicy emitted whole, and fourteen
counted hunks on the generated PortraitVideoPager.

Source checks only: nothing is compiled and no JVM test runs here. The hand-written Kotlin regression class is counted and
checked for balanced delimiters; the Linux compile and JVM run happen later.
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
import v046_follow_confirmation as follow
import v046_portrait_pager as portrait


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync_upstream_portrait_pager', 'sync-upstream.py')
GRADLE = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
CATALOG = json.loads((TOOLS / 'compatibility-source-inputs.json').read_bytes())
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopPortraitPagerRepinTest.kt'
PLATFORM = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitPlatform.kt'
CONSUMED_VIEWS = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoConsumedViews.kt'
PAGER = portrait.PAGER
OWNER_VIEW_MODEL = 'com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
PLAYBACK_UI_STATE = 'com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoPlaybackUiState.kt'
OVERLAY_OUTPUT = portrait.OUT + 'overlay/PortraitFullscreenOverlay.kt'
SWITCH_OUTPUT = portrait.OUT + 'pager/PortraitPagerSwitchPolicy.kt'
PROOFS = portrait.PROOF_DIRECTORY + '/'
# every emitted file that differs from the producer output without this slice
CHANGED = set(portrait.SPEC_LEAVES) | {PAGER, 'source-inventory.json'}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


def text_of(path):
    return Path(path).read_bytes().decode('utf-8')


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


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def git_blob(path):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                    portrait.COMMIT + ':' + path])


def call_text(source, name):
    """The balanced argument list of the single call `name(` in `source`, found with the Kotlin tokenizer."""
    start = source.index(name + '(')
    depth = 0
    for token, first, end in sync.kotlin_tokens(source[start:]):
        if token == '(':
            depth += 1
        elif token == ')':
            depth -= 1
            if depth == 0:
                return source[start:start + end]
    raise AssertionError('unbalanced call ' + name)


def lf(data):
    """The story topic helper writes with the platform newline; everything else here is LF."""
    return data.decode('utf-8').replace('\r\n', '\n')


def declared(source, name):
    return len(re.findall(r'(?m)^\s*(?:internal |private |public |inline |const |data |enum |sealed )*(?:fun|val|var|class|object)\s+'
                          r'(?:<[^>]*>\s+)?(?:[\w.<>?, ]+\.)?' + re.escape(name) + r'\b', source))


class PortraitPagerSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-pp-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.sources, cls.manifest = portrait.fixed(REPO)
        cls.pager = load('pp_pager', 'extract-upstream-video-fullscreen-pager.py')
        cls.pager.generate(REPO, cls.root / 'with')
        cls.story = load('pp_story', 'extract-upstream-story-topic.py')
        cls.story.generate(REPO, cls.root / 'story-with')
        cls.now, cls.story_now = tree(cls.root / 'with'), tree(cls.root / 'story-with')
        # the same producers with the layers of this slice patched out: the retained v0.2.5 behaviour
        baseline = load('pp_pager_baseline', 'extract-upstream-video-fullscreen-pager.py')
        with mock.patch.object(portrait, 'spec_source', lambda repo, relative, canonical, expected: (canonical, expected)), \
                mock.patch.object(portrait, 'advance_pager', lambda repo, relative, text, output: text), \
                mock.patch.object(portrait, 'record_spec', lambda repo, relative, final, output: None), \
                mock.patch.object(portrait, 'emit_controls_policy', lambda repo, output: dict(
                    path='unused', origin='unused', mode='unused', sha256LF='0', generated=False)):
            baseline.generate(REPO, cls.root / 'without')
        story_baseline = load('pp_story_baseline', 'extract-upstream-story-topic.py')
        with mock.patch.object(portrait, 'switch_policy', lambda repo, canonical, adapt, output: (canonical, adapt(canonical))):
            story_baseline.generate(REPO, cls.root / 'story-without')
        cls.before, cls.story_before = tree(cls.root / 'without'), tree(cls.root / 'story-without')
        # the original view model as the full owner producer emits it: the two dialog flags the pager hunk reads live here
        owner = load('pp_owner', 'extract-upstream-video-full-owner.py')
        owner.generate(REPO, cls.root / 'owner')
        cls.owner_view_model = text_of(cls.root / 'owner' / OWNER_VIEW_MODEL)
        # the playback UI state (Success and its two known flags) the hand-written JVM class constructs
        state = load('pp_state', 'extract-upstream-video-state-core.py')
        state.generate(REPO, cls.root / 'state')
        cls.playback_ui_state = text_of(cls.root / 'state' / PLAYBACK_UI_STATE)

    def emitted(self, relative):
        return self.now[relative].decode('utf-8')

    # ---- archive integrity -------------------------------------------------------------------------------------

    def test_any_changed_leaf_manifest_license_or_extra_leaf_is_rejected(self):
        for name in sorted(portrait.EXPECTED | {'manifest.json', 'LICENSE'}):
            with self.subTest(changed=name), tempfile.TemporaryDirectory(prefix='bp-pp-t-') as temp:
                copy = Path(temp) / portrait.ARCHIVE
                shutil.copytree(REPO / portrait.ARCHIVE, copy)
                with (copy / name).open('ab') as handle:
                    handle.write(b' ')
                with self.assertRaises(ValueError):
                    portrait.fixed(temp)
        with tempfile.TemporaryDirectory(prefix='bp-pp-t-') as temp:
            copy = Path(temp) / portrait.ARCHIVE
            shutil.copytree(REPO / portrait.ARCHIVE, copy)
            (copy / 'Extra.kt').write_text('package x\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                portrait.fixed(temp)
        with tempfile.TemporaryDirectory(prefix='bp-pp-t-') as temp:
            copy = Path(temp) / portrait.ARCHIVE
            shutil.copytree(REPO / portrait.ARCHIVE, copy)
            (copy / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                portrait.fixed(temp)
        self.assertEqual(portrait.EXPECTED | {'manifest.json', 'LICENSE'}, {p.name for p in (REPO / portrait.ARCHIVE).iterdir()})

    def test_archive_text_is_lf_only_utf8_with_the_right_package(self):
        for name, text in self.sources.items():
            self.assertNotIn('\r', text, name)
            self.assertIn('package ' + portrait.PACKAGES[name] + '\n', text[:200], name)
        self.assertEqual(7, len(self.sources))

    def test_manifest_pins_are_the_real_producer_predecessors(self):
        specs = {Path(spec['output']).name: spec for spec in self.pager.SPECS}
        for relative, leaf in portrait.SPEC_LEAVES.items():
            spec = specs[leaf]
            self.assertEqual(relative, spec['output'])
            self.assertEqual(portrait.CANONICAL_LF_SHA256[leaf], spec['originalSHA'], leaf)
            self.assertEqual(portrait.PREVIOUS_OUTPUT_SHA256[leaf], spec['outputSHA'], leaf)
            self.assertEqual('selected', spec['mode'], leaf)
            self.assertEqual('whole', spec['kind'], leaf)
        rows = {row['replacedBy']: row for row in self.manifest['producerPredecessors']}
        self.assertEqual(set(portrait.CANONICAL_LF_SHA256), set(rows))
        helpers = self.story.host(REPO)
        path = self.story.BASE + 'feature/video/ui/pager/PortraitPagerSwitchPolicy.kt'
        canonical = self.story.read(REPO, path)
        self.assertEqual(portrait.CANONICAL_LF_SHA256[portrait.SWITCH_LEAF], sha(canonical))
        self.assertEqual(portrait.PREVIOUS_OUTPUT_SHA256[portrait.SWITCH_LEAF], sha(self.story.adapt(path, canonical, helpers)))
        registry = {row['path']: row['sha256'] for row in json.loads((REPO / 'desktop/upstream-sources.json').read_bytes())['sources']}
        for leaf, upstream_path in portrait.UPSTREAM_PATHS.items():
            if leaf in portrait.CANONICAL_LF_SHA256:
                self.assertEqual(portrait.CANONICAL_LF_SHA256[leaf], registry[upstream_path], leaf)
            else:
                self.assertNotIn(upstream_path, registry, 'the new v0.4.0 policy has no vendored v0.2.5 copy')

    @unittest.skipUnless(commit_present(portrait.COMMIT), 'the v0.4.6 upstream commit is not in the local object store')
    def test_every_leaf_is_the_v046_blob_and_every_pager_hunk_text_is_upstream(self):
        for row in self.manifest['files']:
            self.assertEqual(git_blob(row['path']), (REPO / portrait.ARCHIVE / row['filename']).read_bytes(), row['filename'])
        self.assertEqual(portrait.PAGER_UPSTREAM_BLOB, subprocess.check_output(
            ['git', '-C', str(REPO), 'rev-parse', portrait.COMMIT + ':' + portrait.PAGER_UPSTREAM_PATH], text=True).strip())
        upstream = git_blob(portrait.PAGER_UPSTREAM_PATH).decode('utf-8')
        for hunk in portrait.PAGER_HUNKS:
            self.assertEqual(1, upstream.count(hunk['upstream']), hunk['label'])
            adaptation = hunk.get('adaptation')
            if adaptation is None:
                self.assertIn(hunk['upstream'], hunk['after'], hunk['label'])
            else:
                self.assertEqual(1, hunk['upstream'].count(adaptation['before']), hunk['label'])
                self.assertIn(hunk['upstream'].replace(adaptation['before'], adaptation['after'], 1), hunk['after'])
        # v0.4.6 has no pager pause icon: upstream deleted it together with the overlay's centre playback control.
        self.assertNotIn('shouldShowPortraitPauseIcon', upstream)
        self.assertNotIn('Icons.Filled.PlayArrow', upstream)
        # The cover hunk is the one deliberate departure from a whole v0.4.6 file: its `before` is the upstream text.
        policy = git_blob(portrait.UPSTREAM_PATHS[portrait.SWITCH_LEAF]).decode('utf-8')
        self.assertEqual(1, policy.count(portrait.COVER_HUNK['before']))

    def test_the_pager_hunk_table_is_complete_and_counted(self):
        self.assertEqual(14, len(portrait.PAGER_HUNKS))
        self.assertEqual(len(portrait.PAGER_HUNKS), len({hunk['label'] for hunk in portrait.PAGER_HUNKS}))
        self.assertEqual(len(portrait.PAGER_HUNKS), self.manifest['pagerHunks']['hunks'])
        for hunk in portrait.PAGER_HUNKS:
            self.assertNotEqual(hunk['before'], hunk['after'], hunk['label'])
            self.assertTrue(hunk['before'].endswith('\n') or hunk['label'].startswith('overlay call'), hunk['label'])
            self.assertNotIn('\r', hunk['after'], hunk['label'])
        # exactly one hunk only removes text (the pager pause icon); it is the last one and keeps the seek hint comment
        removing = [hunk for hunk in portrait.PAGER_HUNKS if hunk['after'] in hunk['before']]
        self.assertEqual([portrait.PAGER_HUNKS[-1]], removing)
        self.assertEqual(portrait._SEEK_HINT_COMMENT, removing[0]['after'])
        self.assertEqual(portrait._PAUSE_ICON_BLOCK + portrait._SEEK_HINT_COMMENT, removing[0]['before'])
        self.assertEqual(portrait._SEEK_HINT_COMMENT, removing[0]['upstream'])

    # ---- the producer layers ------------------------------------------------------------------------------------

    def test_exactly_the_intended_outputs_change_and_nothing_else(self):
        changed = {name for name in self.before if name in self.now and self.now[name] != self.before[name]}
        self.assertEqual(CHANGED, changed)
        added = set(self.now) - set(self.before)
        self.assertEqual({portrait.CONTROLS_OUTPUT} | {PROOFS + name + '.json' for name in
                                                       list(portrait.SPEC_LEAVES.values()) + [portrait.CONTROLS_LEAF,
                                                                                              'PortraitVideoPager.kt']}, added)
        self.assertEqual(set(), set(self.before) - set(self.now))
        inventory = {r['path']: r for r in json.loads(self.now['source-inventory.json'])['outputs']}
        base = {r['path']: r for r in json.loads(self.before['source-inventory.json'])['outputs']}
        row = inventory[portrait.CONTROLS_OUTPUT]
        self.assertEqual((portrait.UPSTREAM_PATHS[portrait.CONTROLS_LEAF], portrait.CONTROLS_MODE, True),
                         (row['origin'], row['mode'], row['generated']))
        self.assertEqual(sha(self.sources[portrait.CONTROLS_LEAF]), row['sha256LF'])
        for path, entry in base.items():
            if path in inventory:
                self.assertEqual(path in CHANGED, entry['sha256LF'] != inventory[path]['sha256LF'], path)
        story_changed = {name for name in self.story_before if name in self.story_now and self.story_now[name] != self.story_before[name]}
        self.assertEqual({SWITCH_OUTPUT}, story_changed)
        self.assertEqual({PROOFS + portrait.SWITCH_LEAF + '.json'}, set(self.story_now) - set(self.story_before))
        self.assertEqual(set(), set(self.story_before) - set(self.story_now))

    def test_each_whole_file_is_the_archive_bytes_plus_only_the_producers_own_counted_edits(self):
        specs = {spec['output']: spec for spec in self.pager.SPECS}
        for relative, leaf in portrait.SPEC_LEAVES.items():
            with self.subTest(leaf=leaf):
                expected = self.sources[leaf]
                for edit in specs[relative]['edits']:
                    count = edit.get('count', 1)
                    self.assertGreaterEqual(expected.count(edit['before']), count, edit.get('label'))
                    expected = expected.replace(edit['before'], edit['after'], count)
                self.assertEqual(portrait.CURRENT_OUTPUT_SHA256[leaf], sha(expected))
                # later layers of the producer (the captured media byte consumer delta of the load policy) sit on top
                expected = self.pager.portrait_hwnd_foreground_delta(
                    relative, self.pager.captured_media_byte_consumer_delta(relative, expected))
                self.assertEqual(expected, self.emitted(relative))
                self.assertNotEqual(portrait.PREVIOUS_OUTPUT_SHA256[leaf], portrait.CURRENT_OUTPUT_SHA256[leaf])
                # the vendored v0.2.5 row is read and pinned, then replaced; the retained row text is unchanged
                self.assertEqual(portrait.PREVIOUS_OUTPUT_SHA256[leaf], specs[relative]['outputSHA'])

    def test_the_original_is_recoverable_from_every_emitted_whole_file(self):
        for relative, leaf in portrait.SPEC_LEAVES.items():
            with self.subTest(leaf=leaf):
                proof = json.loads(self.now[PROOFS + leaf + '.json'])
                emitted = self.emitted(relative)
                self.assertEqual(portrait.COMMIT, proof['upstreamCommit'])
                self.assertEqual(portrait.MANIFEST_SHA256, proof['sourceManifestSHA256'])
                self.assertEqual(sha(emitted), proof['emittedSHA256'])
                self.assertEqual(self.sources[leaf], restore_hunks(emitted, proof['originalToWindows']))
                self.assertTrue(proof['originalToWindows']['wholeInverseExact'])
                self.assertFalse(proof['mainCompiled'])
                self.assertFalse(proof['applicationExecuted'])
        switch = json.loads(self.story_now[PROOFS + portrait.SWITCH_LEAF + '.json'])
        self.assertEqual(self.sources[portrait.SWITCH_LEAF],
                         restore_hunks(lf(self.story_now[SWITCH_OUTPUT]).split('\n', 2)[2], switch['originalToWindows']))
        self.assertEqual(1, switch['coverHunk']['count'])

    def test_the_new_controls_policy_is_emitted_whole_and_byte_for_byte(self):
        data = self.now[portrait.CONTROLS_OUTPUT]
        self.assertEqual(self.sources[portrait.CONTROLS_LEAF].encode('utf-8'), data)
        self.assertEqual(1, declared(data.decode('utf-8'), 'shouldAutoHidePortraitControls'))
        self.assertIn('internal const val PORTRAIT_CONTROLS_AUTO_HIDE_DELAY_MS = 4_000L', data.decode('utf-8'))
        proof = json.loads(self.now[PROOFS + portrait.CONTROLS_LEAF + '.json'])
        self.assertTrue(proof['wholeOriginalEmittedByteForByte'])

    def test_the_switch_policy_is_the_v046_file_with_the_two_platform_edits_and_the_one_cover_hunk(self):
        first, second, body = lf(self.story_now[SWITCH_OUTPUT]).split('\n', 2)
        self.assertEqual('// GENERATED from ' + portrait.UPSTREAM_PATHS[portrait.SWITCH_LEAF] + '; do not edit.', first)
        self.assertEqual('// LF-normalized SHA-256: ' + sha(self.sources[portrait.SWITCH_LEAF]), second)
        upstream = self.sources[portrait.SWITCH_LEAF]
        # the cid-aware media id match of v0.4.6 (v035-portrait-pager-preload-identity)
        self.assertIn('return (targetCid > 0L && targetCid == currentPlayingCid) ||', body)
        self.assertNotIn('if (normalizedMediaId == targetBvid && targetCid <= 0L && currentPlayingCid <= 0L) {', body)
        # the v0.2.5 cover rule stays, through exactly one counted hunk
        self.assertEqual(1, upstream.count(portrait.COVER_HUNK['before']))
        self.assertNotIn(portrait.COVER_HUNK['before'], body)
        self.assertEqual(1, body.count(portrait.COVER_HUNK['after']))
        # the producer's own isVerticalVideo substitutions still apply
        self.assertNotIn('VideoRepository', body)
        self.assertIn('isVerticalVideo: suspend (String, Long) -> Boolean,', body)
        self.assertIn('isVerticalVideo(candidate.bvid, candidate.aid)', body)
        independent = upstream_with_edits(self.story, upstream)
        self.assertEqual(portrait.CURRENT_OUTPUT_SHA256[portrait.SWITCH_LEAF], sha(independent))
        recorded = json.loads(self.story_now[PROOFS + portrait.SWITCH_LEAF + '.json'])
        self.assertEqual(portrait.CURRENT_OUTPUT_SHA256[portrait.SWITCH_LEAF], recorded['emittedSHA256'])
        self.assertEqual(independent.strip() + '\n', body)

    def test_the_pager_layer_is_fourteen_counted_hunks_that_peel_off_exactly(self):
        proof = json.loads(self.now[PROOFS + 'PortraitVideoPager.kt.json'])
        final = self.emitted(PAGER)
        self.assertEqual(sha(final), proof['outputSHA256'])
        self.assertEqual(portrait.PAGER_EMITTED_SHA256, proof['outputSHA256'])
        self.assertEqual(portrait.PAGER_PREDECESSOR_SHA256, proof['inputSHA256'])
        self.assertEqual(14, len(proof['hunks']))
        self.assertEqual([1] * 14, [hunk['count'] for hunk in proof['hunks']])
        self.assertTrue(proof['fullInputInverseRecovered'])
        peeled = portrait.peel_pager(final, proof)
        self.assertEqual(self.before[PAGER].decode('utf-8'), peeled)
        self.assertEqual(portrait.PAGER_PREDECESSOR_SHA256, sha(peeled))
        for hunk in portrait.PAGER_HUNKS:
            self.assertEqual(1, peeled.count(hunk['before']), hunk['label'])
            # a hunk that only removes text leaves its `after` standing in the predecessor, as the tail of its `before`
            removes_text = hunk['after'] in hunk['before']
            self.assertEqual(1 if removes_text else 0,
                             peeled.count(hunk['after']) if hunk['before'] not in hunk['after'] else 0, hunk['label'])
        # thirteen hunks add 78 lines; the fourteenth removes the 20 lines of the pager pause icon block
        removed_lines = (portrait._PAUSE_ICON_BLOCK).count('\n')
        self.assertEqual(20, removed_lines)
        self.assertEqual(78 - removed_lines, len(final.split('\n')) - len(peeled.split('\n')))
        self.assertEqual(58, len(final.split('\n')) - len(peeled.split('\n')))
        with self.assertRaises(ValueError):
            portrait.peel_pager(final + ' ', proof)

    def test_a_paused_page_has_one_centre_play_pause_control_the_overlays_and_not_the_pagers_own_icon(self):
        pager, overlay = self.emitted(PAGER), self.emitted(OVERLAY_OUTPUT)
        # the predecessor drew its own 60dp play icon at the centre of a paused current page; that block is gone
        peeled = portrait.peel_pager(pager, json.loads(self.now[PROOFS + 'PortraitVideoPager.kt.json']))
        self.assertEqual(1, peeled.count('shouldShowPortraitPauseIcon('))
        self.assertEqual(1, peeled.count('Icons.Filled.PlayArrow,'))
        self.assertEqual(0, pager.count('shouldShowPortraitPauseIcon('))
        self.assertEqual(0, pager.count('showPauseIcon'))
        self.assertEqual(0, pager.count('Icons.Filled.PlayArrow'))
        self.assertEqual(0, pager.count('// 暂停图标'))
        # the seek hint that followed the icon is untouched and directly follows the blank line after the media layer
        self.assertEqual(1, pager.count(portrait._SEEK_HINT_COMMENT))
        self.assertIn('\n        }\n\n' + portrait._SEEK_HINT_COMMENT + '        if (isSeekGesture && progressState.duration > 0) {\n', pager)
        # the overlay the pager calls owns the one centre control, gated on the visible controls and ready playback
        self.assertEqual(1, overlay.count('CenterPlaybackControl('))
        self.assertIn('visible = showControls && playbackReady && !isPlaybackRecovering &&', overlay)
        self.assertIn('onPlayPause = onPlayPause,\n            diameter = 72.dp,', overlay)
        self.assertEqual(1, call_text(pager, 'PortraitFullscreenOverlay').count('onPlayPause = {'))
        # the policy that decided the old icon is a carried helper that nothing in the pager calls any more; it stays defined
        self.assertEqual(1, declared(self.story_now[SWITCH_OUTPUT].decode('utf-8'), 'shouldShowPortraitPauseIcon'))
        self.assertEqual(0, declared(self.emitted(portrait.OUT + 'pager/PortraitVideoLoadPolicy.kt'), 'shouldShowPortraitPauseIcon'))

    def test_the_pager_passes_every_new_required_overlay_parameter(self):
        pager, overlay = self.emitted(PAGER), self.emitted(OVERLAY_OUTPUT)
        # v0.4.6 declares both known flags without a default: a caller that forgets one does not compile
        self.assertRegex(overlay, r'\n    likeStatusKnown: Boolean,\n')
        self.assertRegex(overlay, r'\n    followStatusKnown: Boolean,\n')
        self.assertNotRegex(overlay, r'likeStatusKnown: Boolean =')
        self.assertNotRegex(overlay, r'followStatusKnown: Boolean =')
        self.assertEqual(1, len(re.findall(r'\n    PortraitFullscreenOverlay\(\n', pager)))
        call = call_text(pager, 'PortraitFullscreenOverlay')
        for argument in ('playbackReady = isCurrentPage && !isLoading && isPlayerReadyForThisVideo,',
                         'declarations = videoDeclarations,',
                         'likeStatusKnown = resolvedInteractionState.likeStatusKnown,',
                         'followStatusKnown = followStatusKnown,'):
            self.assertEqual(1, call.count(argument), argument)
        self.assertIn('onLikeLongClick = {\n                if (canHandlePortraitInteraction && resolvedInteractionState.likeStatusKnown) {', call)
        self.assertIn('if (canHandlePortraitInteraction && authorMid > 0L) onToggleFollow(authorMid, isFollowing)', call)
        # the interaction bar is only called by the overlay, with the same flag
        self.assertEqual(1, overlay.count('PortraitInteractionBar('))
        self.assertIn('likeStatusKnown = likeStatusKnown,', overlay)

    def test_the_declarations_come_from_the_metadata_settings_store_not_an_android_settings_object(self):
        pager = self.emitted(PAGER)
        self.assertEqual(1, pager.count('val showVideoDeclarations by remember(context) {'))
        self.assertIn('com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings'
                      '.getVideoArgueMsgShown(context.pluginContext)', pager)
        self.assertNotIn('core.store.SettingsManager.getVideoArgueMsgShown', pager)
        self.assertIn('portraitDetailInfo?.argueInfo?.argueMsg?.trim()', pager)
        self.assertIn('add("未经作者授权，请勿转载")', pager)
        # the metadata settings store is emitted by the stable video metadata producer, with this exact getter
        self.assertIn("'getVideoArgueMsgShown'", text_of(TOOLS / 'extract-stable-video-metadata.py'))
        self.assertIn('internal object DesktopOriginalVideoMetadataSettings', text_of(TOOLS / 'extract-stable-video-metadata.py'))
        # the ViewInfo model that the emitted detail units carry holds the two fields the declarations read
        model = text_of(REPO / 'desktop/upstream-slices/v046-episode-selector/VideoDetailResponse.kt')
        self.assertIn('val argueInfo: VideoArgueInfo? = null,', model)
        self.assertIn('val rights: VideoDetailRights = VideoDetailRights(),', model)
        self.assertIn('val noReprint: Int = 0', model)

    def test_the_auto_hide_block_reads_only_names_that_exist_before_it_in_the_page_item(self):
        pager = self.emitted(PAGER)
        start = pager.index('    var controlsInteractionGeneration by remember(bvid)')
        page_item = pager.index('private fun VideoPageItem(')
        self.assertLess(page_item, start)
        before = pager[page_item:start]
        for name in ('showCommentSheet', 'subReplyState', 'showDetailSheet', 'showUpPreview', 'commentSheetVisibilityProgress',
                     'upPreviewVisibilityProgress', 'showSpeedMenu', 'showAudioQualityMenu', 'showQualityMenu',
                     'showRatioMenu', 'showSubtitlePanel', 'isLongPressing', 'seekSession', 'isPlayerReadyForThisVideo',
                     'isPlaying', 'lifecycleOwner'):
            self.assertRegex(before, r'(?:var|val)\s+' + name + r'\b', name)
        for parameter in ('isPortraitPlaybackAllowed', 'isLoading', 'portraitOverlayVisible', 'onPortraitOverlayVisibleChange',
                          'viewModel', 'isCurrentPage'):
            self.assertRegex(before, r'\n    ' + parameter + r':', parameter)
        self.assertEqual(1, pager.count('PointerEventPass.Initial)\n                    controlsPointerPressed = true'))
        self.assertEqual(1, pager.count('import androidx.compose.ui.input.pointer.PointerEventPass\n'))
        self.assertEqual(2, pager.count('PointerEventPass.Initial'))
        # the observer sits in the same modifier chain, in front of the page gesture
        observer = pager.index('.pointerInput(isCurrentPage, bvid) {')
        gestures = pager.index('.pointerInput(isCurrentPage, bvid, playerGesturesEnabled) {')
        self.assertLess(observer, gestures)
        self.assertLess(gestures - observer, 1200)

    def test_the_two_dialog_flags_are_read_views_of_the_one_original_view_model(self):
        platform, views = text_of(PLATFORM), text_of(CONSUMED_VIEWS)
        self.assertEqual(1, platform.count('    val showDanmakuInputDialog: StateFlow<Boolean>\n'))
        self.assertEqual(1, platform.count('    val favoriteFolderDialogVisible: StateFlow<Boolean>\n'))
        self.assertEqual(1, views.count('    override val showDanmakuInputDialog get() = original.showDanmakuInputDialog\n'))
        self.assertEqual(1, views.count('    override val favoriteFolderDialogVisible get() = original.favoriteFolderDialogVisible\n'))
        interface = platform[platform.index('internal interface DesktopOriginalPortraitPlaybackOwner'):]
        interface = interface[:interface.index('\n}\n')]
        implementers = [p for p in (REPO / 'desktop/src').rglob('*.kt') if ') : DesktopOriginalPortraitPlaybackOwner'
                        in text_of(p) or ': DesktopOriginalPortraitPlaybackOwner {' in text_of(p)]
        self.assertEqual([CONSUMED_VIEWS], implementers, 'the consumed views are the only implementer')
        for source in (platform, views):
            balanced(self, source)
        # the generated original view model owns both flags as real StateFlows: the overrides above read these two members
        self.assertEqual(1, self.owner_view_model.count('    val showDanmakuInputDialog = _showDanmakuInputDialog.asStateFlow()\n'))
        self.assertEqual(1, self.owner_view_model.count('    val favoriteFolderDialogVisible = _favoriteFolderDialogVisible.asStateFlow()\n'))
        self.assertEqual(1, self.owner_view_model.count('    private val _showDanmakuInputDialog = MutableStateFlow(false)\n'))
        self.assertEqual(1, self.owner_view_model.count('    private val _favoriteFolderDialogVisible = MutableStateFlow(false)\n'))
        # The danmaku flag is upstream's as carried: the portrait composer opens through showDanmakuSendDialog (which sets
        # showDanmakuDialog), so an open composer is covered by its playback pause. The KDoc of the platform interface says so.
        pager = self.emitted(PAGER)
        self.assertEqual(1, pager.count('viewModel.showDanmakuSendDialog()'))
        self.assertEqual(0, pager.count('viewModel.showDanmakuInputDialog()'))
        self.assertEqual(1, pager.count('val danmakuInputVisible by viewModel.showDanmakuInputDialog.collectAsStateWithLifecycle()'))

    def test_the_overlay_follow_buttons_are_upstream_text_and_the_unknown_follow_state_consumer_is_in(self):
        overlay = self.emitted(OVERLAY_OUTPUT)
        self.assertEqual(1, overlay.count(follow.IMPORT_LINE))
        self.assertEqual(2, overlay.count(follow.FUNCTION + '('))
        self.assertIn('onClickLabel = if (followStatusKnown) null else "重新确认状态并关注",', overlay)
        self.assertIn('val isFollowed = followStatusKnown && isFollowing', overlay)
        self.assertIn('val buttonText = if (!followStatusKnown) "待确认" else if (isFollowed) "已关注" else "关注"', overlay)
        self.assertIn('targetKey = ownerMid,\n                                userName = authorName,', overlay)
        self.assertIn('targetKey = member.mid,\n                                userName = member.name,', overlay)
        # the creator team facade replaces the Android repository at its three call sites; nothing else is edited
        self.assertNotIn('data.repository.ActionRepository', overlay)
        # the val and the property it reads, plus the three replaced repository uses
        self.assertEqual(5, len(re.findall(r'\bcreatorTeam\b', overlay)))
        self.assertEqual(1, overlay.count('val creatorTeam = com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform.current.creatorTeam\n'))
        self.assertIn('import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration\n', overlay)
        self.assertNotIn('import androidx.compose.ui.platform.LocalConfiguration', overlay)
        # the unfollow alert file and its owned-window adapter are still emitted by the same producer
        self.assertIn(follow.OUTPUT_RELATIVE, self.now)
        self.assertEqual(follow.EMITTED_SHA256, sha(self.now[follow.OUTPUT_RELATIVE]))
        self.assertNotIn(follow.PROOF_DIRECTORY + '/PortraitFullscreenOverlay.kt.json', self.now)
        self.assertIn(follow.PROOF_DIRECTORY + '/UpPreviewSheet.kt.json', self.now)

    def test_the_interaction_bar_draws_plain_white_icons_and_gates_the_triple_press_on_the_known_state(self):
        bar = self.emitted(portrait.OUT + 'overlay/PortraitInteractionBar.kt')
        self.assertNotIn('Color.Black.copy(alpha = layoutPolicy.iconBackingAlpha)', bar)
        self.assertNotIn('.background(\n                    color =', bar)
        self.assertIn('if (likeStatusKnown) triplePress.start()', bar)
        self.assertIn('countText = if (!likeStatusKnown) "待确认"', bar)
        self.assertIn('tint = if (!enabled) Color.White.copy(alpha = 0.45f) else if (isActive) activeColor else Color.White,', bar)

    def test_the_glass_chrome_degrades_without_a_page_backdrop_and_uses_theme_roles(self):
        overlay = self.emitted(OVERLAY_OUTPUT)
        for token in ('PlayerControlsBackdropHost(', 'PlayerControlGlassDock(', 'PortraitGlassIconButton(',
                      'CenterPlaybackControl(', 'AppContentColors.accent()', 'MediaContrastPalette.ControlSurface',
                      'playbackReady: Boolean = true,'):
            self.assertIn(token, overlay, token)
        glass = text_of(REPO / 'desktop/upstream-slices/v046-player-overlay/PlayerControlGlass.kt')
        self.assertIn('LocalPlayerPageBackdrop = compositionLocalOf<Backdrop?> { null }', glass)
        self.assertIn('if (glass && providedBackdrop == null) {\n                Modifier.background(Color.Black.copy(alpha = 0.32f), shape)', glass)
        self.assertNotIn('LocalPlayerPageBackdrop provides', self.emitted(PAGER), 'the page backdrop needs the whole pager')

    def test_the_cid_aware_preload_policies_are_carried_but_the_retained_pager_still_keys_prefetches_by_bvid(self):
        policy = self.emitted(portrait.OUT + 'pager/PortraitVideoLoadPolicy.kt')
        self.assertIn('if (targets.none { it.bvid == identity.bvid && it.cid == identity.cid }) {', policy)
        self.assertEqual(1, declared(policy, 'resolvePortraitPrefetchWindowTargets'))
        self.assertIn('}.distinctBy { resolvePortraitMediaId(it.bvid, it.cid) }', policy)
        # the Windows ports of the load policy are the ones that already existed
        self.assertIn('context: DesktopOriginalPortraitPlatform,', policy)
        self.assertNotIn('MediaUtils', policy)
        self.assertNotIn('OkHttpDataSource', policy)
        # v035-portrait-pager-preload-identity is only partly delivered (manifest partiallyDelivered): the retained pager
        # calls the carried target resolver but keys its prefetch set by bvid alone and never calls the window helper.
        # A follow-up that closes this must change these assertions and the manifest note together.
        pager = self.emitted(PAGER)
        self.assertEqual(1, pager.count('resolvePortraitPlayUrlPreloadTargets('))
        self.assertEqual(1, pager.count('portraitPrefetchedPlayUrlBvids.add(target.bvid)'))
        self.assertEqual(1, pager.count('portraitPrefetchedPlayUrlBvids.add(identity.bvid)'))
        self.assertEqual(1, pager.count('portraitPrefetchedPlayUrlBvids.remove(target.bvid)'))
        self.assertEqual(2, pager.count('portraitPrefetchedPlayUrlBvids.remove(identity.bvid)'))
        self.assertEqual(0, pager.count('portraitPrefetchedPlayUrlBvids.add(resolvePortraitMediaId('))
        self.assertEqual(0, pager.count('resolvePortraitPrefetchWindowTargets'))
        # The concrete coverage loss recorded in the manifest: the carried resolver spends one preload slot per PART of a
        # bvid, and this retained loop then drops every part after the first because its set is keyed by bvid.
        loop = pager[pager.index('preloadTargets.mapNotNull { target ->'):]
        loop = loop[:loop.index('}.awaitAll()')]
        self.assertEqual(1, loop.count('if (!portraitPrefetchedPlayUrlBvids.add(target.bvid)) return@mapNotNull null'))
        self.assertIn('if (targets.none { it.bvid == identity.bvid && it.cid == identity.cid }) {', policy)
        canonical = text_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitVideoLoadPolicy.kt')
        self.assertIn('if (targets.none { it.bvid == identity.bvid }) {', canonical, 'the v0.2.5 resolver deduplicated by bvid')
        self.assertNotIn('if (targets.none { it.bvid == identity.bvid }) {', policy)

    def test_the_manifest_records_the_partial_delivery_the_known_deviations_and_the_follow_ups(self):
        manifest = self.manifest
        self.assertEqual(2, len(manifest['partiallyDelivered']))
        preload, glass = manifest['partiallyDelivered']
        for token in ('v035-portrait-pager-preload-identity is only partly delivered', 'portraitPrefetchedPlayUrlBvids.add(identity.bvid)',
                      '.add(target.bvid) in the forward preload loop', 'resolvePortraitPrefetchWindowTargets has no production caller',
                      'not cid-aware end to end'):
            self.assertIn(token, preload)
        # the concrete regression of the partial delivery, so that the follow-up has to close it
        for token in ('Concrete coverage loss', 'spend the preloadCount slots', 'return@mapNotNull null',
                      '[BV2 part 1, BV2 part 2, BV2 part 3, BV4] with preloadCount 3 now warm BV2 part 1 only and never BV4',
                      'the v0.2.5 bvid-keyed targets [BV2, BV4] warmed both',
                      'resolvePortraitMediaId(target.bvid, target.cid) at the swipe-candidate add and both of its removes'):
            self.assertIn(token, preload)
        # the glass half of upstream commit 07362853: the overlay part is carried, the pager part is not
        for token in ('v037-portrait-glass-theme-colors-unported is only partly delivered', 'AppContentColors theme roles',
                      'glass docks of the overlay, the interaction bar and the input bar', '95 lines of PortraitVideoPager.kt',
                      'rememberChromeBackdropSource', 'supportsPlayerPageGlass', 'LocalPlayerPageBackdrop provider',
                      'flat Color.Black 0.32 fill instead of glass'):
            self.assertIn(token, glass)
        deviations = ' '.join(manifest['knownDeviations'])
        for token in ('Lifecycle.State.RESUMED', 'STARTED', 'no desktop mouse-move reveal', 'showDanmakuInputDialog', 'showDanmakuSendDialog'):
            self.assertIn(token, deviations)
        not_carried = ' '.join(manifest['notCarried'])
        for token in ('PortraitVideoPager.kt whole', 'PortraitWifiPreloadQueue.kt', 'v029-portrait-background-continue',
                      'PortraitDetailSheet.kt', 'LocalPlayerPageBackdrop', 'PortraitControlsVisibilityPolicyTest.kt'):
            self.assertIn(token, not_carried)
        self.assertIn('counted hunk 14 deletes the shouldShowPortraitPauseIcon block', ' '.join(manifest['newInV046']))
        self.assertIn('fourteen counted hunks', manifest['purpose'])
        # the generator docstring carries the same statements, so the code reader meets them first
        docstring = portrait.__doc__
        for token in ('Partly delivered', 'NOT cid-aware end to end', 'Concrete coverage loss', 'v037-portrait-glass-theme-colors-unported',
                      'Known deviations', 'Size and cut', 'PortraitWifiPreloadQueue', 'fourteen counted hunks', 'CenterPlaybackControl'):
            self.assertIn(token, docstring)
        # the catalog rows that describe this archive count the same hunks
        for row in sync.adapted_compatibility_inputs(REPO):
            if row['localPath'].startswith(portrait.ARCHIVE + '/'):
                self.assertIn('fourteen counted hunks', row['usedScope'])
                self.assertNotIn('thirteen', row['usedScope'])

    def test_every_new_top_level_name_has_exactly_one_owner_among_these_producers_the_vendored_roots_and_desktop_main(self):
        """Partial universe only: the outputs of the two producers run here, the vendored roots and desktop/src/main.
        The full compile universe (every other producer's output) is checked by the symbol gate, test_symbol_closure.py."""
        emitted ={name: data.decode('utf-8') for name, data in {**self.now, **self.story_now}.items() if name.endswith('.kt')}
        names = {portrait.CONTROLS_OUTPUT: ('PORTRAIT_CONTROLS_AUTO_HIDE_DELAY_MS', 'shouldAutoHidePortraitControls'),
                 portrait.OUT + 'pager/PortraitVideoLoadPolicy.kt': ('resolvePortraitPrefetchWindowTargets',)}
        for relative, symbols in names.items():
            for symbol in symbols:
                owners = [name for name, source in emitted.items() if declared(source, symbol)]
                self.assertEqual([relative], owners, symbol)
                for root in ('app', 'design-system', 'core-data', 'core-player', 'network-core', 'settings-core'):
                    for path in (REPO / root).rglob('*.kt'):
                        self.assertEqual(0, declared(text_of(path), symbol), str(path))
                for path in (REPO / 'desktop/src/main').rglob('*.kt'):
                    self.assertEqual(0, declared(text_of(path), symbol), str(path))
        # a file name that is emitted twice would be a duplicate JVM file facade
        leaves = [Path(name).name for name in emitted if name.endswith(tuple(portrait.ARCHIVE_FILES))]
        self.assertEqual(len(leaves), len(set(leaves)))

    def test_the_emitted_kotlin_is_balanced(self):
        for relative in sorted(CHANGED - {'source-inventory.json'}) + [portrait.CONTROLS_OUTPUT]:
            with self.subTest(file=relative):
                balanced(self, self.emitted(relative))
        balanced(self, self.story_now[SWITCH_OUTPUT].decode('utf-8'))

    # ---- fail closed -----------------------------------------------------------------------------------------

    def test_a_drifted_predecessor_or_anchor_fails_before_any_output(self):
        specs = {spec['output']: spec for spec in self.pager.SPECS}
        for relative, leaf in portrait.SPEC_LEAVES.items():
            with self.subTest(leaf=leaf):
                canonical = text_of(self.canonical_path(specs[relative]['origin'])).replace('\r\n', '\n')
                self.assertEqual((self.sources[leaf], portrait.CURRENT_OUTPUT_SHA256[leaf]),
                                 portrait.spec_source(REPO, relative, canonical, specs[relative]['outputSHA']))
                with self.assertRaises(ValueError):
                    portrait.spec_source(REPO, relative, canonical + ' ', specs[relative]['outputSHA'])
                with self.assertRaises(ValueError):
                    portrait.spec_source(REPO, relative, self.sources[leaf], specs[relative]['outputSHA'])
                with self.assertRaises(ValueError):
                    portrait.spec_source(REPO, relative, canonical, portrait.CURRENT_OUTPUT_SHA256[leaf])
        self.assertEqual(('x', 'y'), portrait.spec_source(REPO, portrait.OUT + 'pager/Other.kt', 'x', 'y'))
        peeled = self.before[PAGER].decode('utf-8')
        out = self.root / 'rejected'
        with self.assertRaises(ValueError):
            portrait.advance_pager(REPO, PAGER, peeled + ' ', out)
        with self.assertRaises(ValueError):
            portrait.advance_pager(REPO, PAGER, self.emitted(PAGER), out)
        self.assertEqual(peeled, portrait.advance_pager(REPO, portrait.OUT + 'pager/Other.kt', peeled, out))
        for hunk in portrait.PAGER_HUNKS:
            with self.subTest(hunk=hunk['label']), mock.patch.object(
                    portrait, 'PAGER_PREDECESSOR_SHA256', sha(peeled.replace(hunk['before'], hunk['before'] + hunk['before'], 1))):
                with self.assertRaises(ValueError):
                    portrait.advance_pager(REPO, PAGER, peeled.replace(hunk['before'], hunk['before'] + hunk['before'], 1), out)
            with self.subTest(moved=hunk['label']):
                moved = peeled.replace(hunk['before'], hunk['before'][:-3] + 'Z' + hunk['before'][-2:], 1)
                with mock.patch.object(portrait, 'PAGER_PREDECESSOR_SHA256', sha(moved)):
                    with self.assertRaises(ValueError):
                        portrait.advance_pager(REPO, PAGER, moved, out)
        self.assertFalse(out.exists())
        canonical = self.story.read(REPO, self.story.BASE + 'feature/video/ui/pager/PortraitPagerSwitchPolicy.kt')
        identity = lambda text: text
        with self.assertRaises(ValueError):
            portrait.switch_policy(REPO, canonical + ' ', identity, out)
        with self.assertRaises(ValueError):
            portrait.switch_policy(REPO, canonical, lambda text: text.replace(portrait.COVER_HUNK['before'], 'x'), out)
        with self.assertRaises(ValueError):
            portrait.switch_policy(REPO, canonical, identity, out)
        self.assertFalse(out.exists())

    def canonical_path(self, origin):
        from v025_source_paths import canonical_source
        return canonical_source(REPO, origin)

    def test_an_output_inside_the_archive_is_rejected_and_a_second_run_is_byte_identical(self):
        before = {p.name: p.read_bytes() for p in (REPO / portrait.ARCHIVE).iterdir()}
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.pager.generate(REPO, REPO / portrait.ARCHIVE / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.story.generate(REPO, REPO / portrait.ARCHIVE / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            portrait.validate_outputs(REPO, REPO / 'desktop/upstream-slices')
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / portrait.ARCHIVE).iterdir()})
        snapshot = tree(self.root / 'with')
        self.pager.generate(REPO, self.root / 'with')
        self.assertEqual(snapshot, tree(self.root / 'with'))
        story_snapshot = tree(self.root / 'story-with')
        self.story.generate(REPO, self.root / 'story-with')
        self.assertEqual(story_snapshot, tree(self.root / 'story-with'))

    # ---- registration and the hand-written JVM class ------------------------------------------------------------

    def test_gradle_lists_the_generator_and_the_archive_for_both_producers_and_excludes_nothing(self):
        self.assertEqual(1, GRADLE.count('inputs.dir("upstream-slices/v046-portrait-pager")'))
        self.assertEqual(1, GRADLE.count('"tools/v046_portrait_pager.py"'))
        start = GRADLE.index('"tools/v046_portrait_pager.py"')
        block_start = GRADLE.rindex('tasks.matching', 0, start)
        self.assertIn('setOf("extractOriginalVideoFullscreenPager", "extractUpstreamStoryTopic")', GRADLE[block_start:start])
        self.assertEqual([], [row for row in GRADLE.splitlines() if 'srcDir' in row and 'v046-portrait-pager' in row])
        # none of the carried files is a direct-mode v0.2.5 copy, so no vendored copy is excluded
        for leaf in portrait.ARCHIVE_FILES:
            self.assertNotIn('exclude(canonicalOriginalIdentity("' + portrait.UPSTREAM_PATHS[leaf] + '"))', GRADLE, leaf)
        registry = {row['path']: row.get('mode', 'direct') for row in
                    json.loads((REPO / 'desktop/upstream-sources.json').read_bytes())['sources']}
        for leaf, path in portrait.UPSTREAM_PATHS.items():
            if path in registry:
                self.assertNotEqual('direct', registry[path], leaf)

    def test_the_compatibility_catalog_has_the_archive_and_its_seven_source_rows(self):
        self.assertIn(portrait.ARCHIVE, CATALOG['buildInputRoots'])
        archive = next(row for row in CATALOG['archives'] if row['directory'] == portrait.ARCHIVE)
        self.assertEqual(sorted(portrait.EXPECTED | {'manifest.json', 'LICENSE'}),
                         sorted(Path(row['path']).name for row in archive['files']))
        rows = sync.adapted_compatibility_inputs(REPO)
        mine = [row for row in rows if row['localPath'].startswith(portrait.ARCHIVE + '/')]
        self.assertEqual(7, len(mine))
        self.assertEqual({'v046-portrait-pager'}, {feature for row in mine for feature in row['features']})
        for row in mine:
            self.assertFalse(row['referenceOnly'])
            manifest_row = next(r for r in self.manifest['files'] if r['filename'] == Path(row['localPath']).name)
            self.assertEqual((manifest_row['path'], manifest_row['gitBlob'], manifest_row['sha256']),
                             (row['path'], row['blob'], row['sha256']))
            self.assertEqual(portrait.COMMIT, row['upstreamCommit'])

    def test_the_hand_written_jvm_class_has_every_case_and_balanced_delimiters(self):
        text = text_of(JVM_TEST)
        self.assertNotIn('\r', text)
        self.assertEqual(12, text.count('@Test'))
        balanced(self, text)
        # the first three cases mirror the upstream unit test of the carried policy: the class says where they come from
        kdoc = text[text.index('/**'):text.index('class DesktopPortraitPagerRepinTest')]
        for token in ('PortraitControlsVisibilityPolicyTest', 'app/src/test/java/com/android/purebilibili/feature/video/ui/pager/',
                      portrait.COMMIT, 'GPL-3.0', 'not carried'):
            self.assertIn(token, kdoc)
        if commit_present(portrait.COMMIT):
            self.assertIn(b'class PortraitControlsVisibilityPolicyTest', git_blob(
                'app/src/test/java/com/android/purebilibili/feature/video/ui/pager/PortraitControlsVisibilityPolicyTest.kt'))
        # the two cases that close the gaps of the first review: the swipe target that is also a forward page, and the
        # shared state that forwards its known flag in both directions
        self.assertIn('fun aSwipeCandidateThatIsAlsoAForwardPageIsWarmedOnlyOnce()', text)
        self.assertIn('fun aMatchingSharedStateForwardsItsKnownLikeFlagInBothDirections()', text)
        # the Success state those cases build is the one the state core producer emits, with the required playUrl and
        # the two flags the pager hunk reads from it
        success = self.playback_ui_state[self.playback_ui_state.index('data class Success('):]
        success = success[:success.index('\n    ) : VideoPlaybackUiState() {')]
        for member in ('        val info: ViewInfo,\n', '        val playUrl: String,\n', '        val isLiked: Boolean = false,\n',
                       '        val likeStatusKnown: Boolean = false,\n'):
            self.assertEqual(1, success.count(member), member)
        self.assertIn('import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState\n', text)
        self.assertIn('import com.android.purebilibili.data.model.response.ViewInfo\n', text)
        for symbol in re.findall(r'^import com\.android\.purebilibili\.feature\.video\.ui\.pager\.(\w+)$', text, re.M):
            owners = [name for name, source in {**{k: v.decode('utf-8') for k, v in self.now.items() if k.endswith('.kt')},
                                                **{k: v.decode('utf-8') for k, v in self.story_now.items() if k.endswith('.kt')}}.items()
                      if declared(source, symbol)]
            self.assertEqual(1, len(owners), symbol)
        for forbidden in ('java.awt', 'javax.swing', 'androidx.compose.ui.window', 'Thread.sleep', 'java.io.File', 'java.net', 'HttpClient'):
            self.assertFalse(forbidden in text, 'the class needs no window, file or network: ' + forbidden)

    def test_every_new_or_touched_text_file_is_lf_only(self):
        for path in (TOOLS / 'v046_portrait_pager.py', TOOLS / 'tests/test_v046_portrait_pager_source.py', JVM_TEST, PLATFORM,
                     CONSUMED_VIEWS, TOOLS / 'extract-upstream-video-fullscreen-pager.py', TOOLS / 'extract-upstream-story-topic.py',
                     TOOLS / 'v046_follow_confirmation.py', REPO / 'desktop/build.gradle.kts'):
            self.assertNotIn(b'\r', path.read_bytes(), str(path))
        for leaf in list((REPO / portrait.ARCHIVE).iterdir()):
            self.assertNotIn(b'\r', leaf.read_bytes(), leaf.name)


def upstream_with_edits(story, text):
    """The story producer's own two platform substitutions plus the one cover hunk, applied independently of the layer."""
    helpers = story.host(REPO)
    path = story.BASE + 'feature/video/ui/pager/PortraitPagerSwitchPolicy.kt'
    adapted = story.adapt(path, text, helpers)
    assert adapted.count(portrait.COVER_HUNK['before']) == 1
    return adapted.replace(portrait.COVER_HUNK['before'], portrait.COVER_HUNK['after'], 1)


if __name__ == '__main__':
    unittest.main()
