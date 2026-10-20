"""Source checks for roadmap slice audio-listen-video-closure. No Gradle, no JVM, no network.

The archive v046-audio-listen-closure carries five whole v0.4.6 files. Every check here runs the REAL producers into a
short temporary root, once with the layer and once with the layer patched out, and reads the output; the Kotlin is
neither compiled nor run.
"""
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_audio_listen_closure as gen  # noqa: E402

PKG = 'com/android/purebilibili/'
LISTEN = gen.RELATIVE['listen']
MUSIC = gen.RELATIVE['music']
COVER = gen.RELATIVE['cover']
AUDIO = gen.RELATIVE['audio']
LYRICS = gen.RELATIVE['lyrics']
UI = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui'
UI_TESTS = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui'
PRODUCERS = {
    'listen': TOOLS / 'extract-upstream-listen-video-root.py',
    'music': TOOLS / 'extract-upstream-music-player-full.py',
    'audio': TOOLS / 'extract-upstream-video-audio-full.py',
    'lyrics': TOOLS / 'extract-upstream-text-lyrics.py',
}
# Android-only references that a Windows output must not keep (a package name such as com.android is not one).
ANDROID = re.compile(r'(?<![\w.])android\.[a-z]|\bBuild\.|\bSettings\.|LocalContext|Toast|AudioManager|asAndroidBitmap')


def text_of(path):
    return Path(path).read_bytes().decode('utf8').replace('\r\n', '\n')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_producer(script, arguments, disabled=None):
    """Run one real producer as a program. `disabled` maps this module's function names to replacement source."""
    program = ('import runpy, sys\nsys.path.insert(0, %r)\nimport v046_audio_listen_closure as layer\n' % str(TOOLS) +
               ''.join('layer.%s = %s\n' % (name, source) for name, source in (disabled or {}).items()) +
               'sys.argv = [%r] + %r\nrunpy.run_path(%r, run_name="__main__")\n' % (str(script), arguments, str(script)))
    done = subprocess.run([sys.executable, '-X', 'utf8', '-B', '-c', program], capture_output=True, text=True,
                          encoding='utf8', cwd=REPO, env={**__import__('os').environ, 'PYTHONUTF8': '1',
                                                          'PYTHONDONTWRITEBYTECODE': '1'})
    assert done.returncode == 0, (script.name, done.stderr[-3000:])


def run_all(root, with_layer):
    """The four real producers into `root`; without the layer every hook of this slice is replaced by an identity."""
    run_producer(PRODUCERS['listen'], ['--repo', str(REPO), '--output', str(root / 'listen'),
                                       '--test-output', str(root / 'listen-tests')],
                 None if with_layer else {'advance_listen_output': 'lambda repo, output, receipts: None'})
    run_producer(PRODUCERS['music'], ['--repo', str(REPO), '--output', str(root / 'music')],
                 None if with_layer else {'advance_music_outputs': 'lambda repo, output, changes: None'})
    run_producer(PRODUCERS['audio'], ['--repo', str(REPO), '--output', str(root / 'audio')],
                 None if with_layer else {'advance_audio_mode_outputs': 'lambda repo, output, changes: None'})
    run_producer(PRODUCERS['lyrics'], ['--repo', str(REPO), '--output', str(root / 'lyrics')],
                 None if with_layer else {'upgraded_lyrics_display_policy': 'lambda repo, raw, row: (raw, row)'})


def tree(root):
    """Every file of a producer output; the output directory itself is replaced by a placeholder, because the
    producers record absolute paths in their receipts and diffs."""
    root = Path(root)
    spellings = [str(root), root.as_posix(), str(root).replace('\\', '\\\\')]
    files = {}
    for p in sorted(root.rglob('*')):
        if p.is_file():
            raw = p.read_bytes()
            for spelling in spellings:
                raw = raw.replace(spelling.encode('utf8'), b'<ROOT>')
            files[p.relative_to(root).as_posix()] = raw
    return files


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-al-')
        root = cls.root = Path(cls.temp.name)
        run_all(root / 'with', True)
        run_all(root / 'without', False)
        cls.sources, cls.manifest = gen.fixed(REPO)
        cls.with_layer = root / 'with'
        cls.without = root / 'without'

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def out(self, group, relative):
        return text_of(self.with_layer / group / relative)

    def base(self, group, relative):
        return text_of(self.without / group / relative)

    def changed_files(self, group):
        a, b = tree(self.with_layer / group), tree(self.without / group)
        self.assertEqual(set(a), set(b) | {n for n in a if n.startswith('v046-')})
        return {name for name in b if a[name] != b[name]}


class ArchiveTests(unittest.TestCase):
    def copy(self, temp):
        repo = Path(temp)
        shutil.copytree(REPO / gen.ARCHIVE, repo / gen.ARCHIVE)
        return repo

    def test_archive_matches_the_upstream_objects_and_the_pins(self):
        sources, manifest = gen.fixed(REPO)
        self.assertEqual(gen.EXPECTED, set(sources))
        self.assertEqual(gen.COMMIT, manifest['commit'])
        self.assertEqual(gen.MANIFEST_SHA256, sha((REPO / gen.ARCHIVE / 'manifest.json').read_bytes()))
        self.assertEqual({'listen/ListenVideoScreen.kt', 'music/MusicPlayerContent.kt', 'music/Music3DCoverFlow.kt',
                          'audio/AudioModeMusicPlayer.kt', 'lyrics/LyricsDisplayPolicy.kt'},
                         set(manifest['beforeFinalOutputs']))
        # the leaves are the exact objects of the pinned upstream commit (checked where the upstream objects exist)
        if subprocess.run(['git', 'cat-file', '-e', gen.COMMIT + '^{commit}'], cwd=REPO, capture_output=True).returncode:
            return
        for row in manifest['files']:
            blob = subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', gen.COMMIT + ':' + row['path']],
                                  cwd=REPO, capture_output=True, check=True).stdout
            self.assertEqual(blob, (REPO / gen.ARCHIVE / row['filename']).read_bytes(), row['filename'])

    def test_every_leaf_the_manifest_the_licence_and_an_extra_leaf_are_pinned(self):
        for leaf in sorted(gen.EXPECTED | {'manifest.json', 'LICENSE'}):
            with tempfile.TemporaryDirectory(prefix='bp-al-a-') as temp:
                repo = self.copy(temp)
                target = repo / gen.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError, msg=leaf):
                    gen.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-al-a-') as temp:
            repo = self.copy(temp)
            (repo / gen.ARCHIVE / 'Extra.kt').write_text('package x\n')
            with self.assertRaises(ValueError):
                gen.fixed(repo)

    def test_output_overlapping_the_archive_is_rejected_before_any_write(self):
        child = REPO / gen.ARCHIVE / 'child'
        for call in (lambda: gen.validate_outputs(REPO, child), lambda: gen.validate_outputs(REPO, REPO / 'desktop'),
                     lambda: load('al_lyrics', 'extract-upstream-text-lyrics.py').generate(REPO, child)):
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                call()
        self.assertFalse(child.exists())
        for key, hook in {'listen': 'v046_audio_listen_closure.validate_outputs(repo, output, test_output)',
                          'music': 'v046_audio_listen_closure.validate_outputs(REPO,LANE)',
                          'audio': 'v046_audio_listen_closure.validate_outputs(REPO,LANE)',
                          'lyrics': 'validate_outputs(repo, output)'}.items():
            self.assertEqual(1, text_of(PRODUCERS[key]).count(hook), key)

    def test_each_layer_is_hooked_exactly_once_as_the_last_step_of_its_producer(self):
        hooks = {'listen': 'v046_audio_listen_closure.advance_listen_output(repo, output, receipts)',
                 'music': 'v046_audio_listen_closure.advance_music_outputs(REPO,LANE,CHANGES)',
                 'audio': 'v046_audio_listen_closure.advance_audio_mode_outputs(REPO,LANE,CHANGES)',
                 'lyrics': 'raw, effective_row = upgraded_lyrics_display_policy(repo, raw, effective_row)'}
        for key, hook in hooks.items():
            self.assertEqual(1, text_of(PRODUCERS[key]).count(hook), key)
        music = text_of(PRODUCERS['music'])
        self.assertLess(music.index("write(LANE/'music-outputs.json'"), music.index(hooks['music']))
        self.assertLess(music.index(hooks['music']), music.index("print(json.dumps({'outputs':len(OUTPUTS)"))

    def test_foreign_predecessors_and_changed_anchors_fail_closed(self):
        sources, manifest = gen.fixed(REPO)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            gen.advance_cover_flow(REPO, 'package x\n')
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            gen.upgraded_lyrics_display_policy(REPO, b'package x\n', {})
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            gen.advance_listen_screen(REPO, 'package x\n', [])
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            gen._once('package x\n', 'missing', 'x', 'missing')
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            gen._once('a a', 'a', 'b', 'twice')
        with self.assertRaisesRegex(ValueError, 'port anchor changed'):
            gen.replay('text', [dict(before='absent', after='x', label='absent', count=1)], 'x')
        with self.assertRaisesRegex(ValueError, 'port anchor changed'):
            gen.replay('a a', [dict(before='a', after='b', label='counted', count=1)], 'x')


class LyricsTests(Fixture):
    def test_the_policy_is_the_whole_v046_file_over_the_v034_one(self):
        emitted = (self.with_layer / 'lyrics' / LYRICS).read_bytes()
        self.assertEqual((REPO / gen.ARCHIVE / 'LyricsDisplayPolicy.kt').read_bytes(), emitted)
        previous = (self.without / 'lyrics' / LYRICS).read_bytes()
        self.assertEqual((REPO / 'desktop/upstream-slices/v034-text-lyrics/LyricsDisplayPolicy.kt').read_bytes(), previous)
        self.assertEqual(gen.LYRICS_PREDECESSOR_SHA256, sha(previous))
        self.assertIn('internal fun resolveLyricsDisplayDocument(', text_of(self.with_layer / 'lyrics' / LYRICS))
        self.assertNotIn('resolveLyricsDisplayDocument', text_of(self.without / 'lyrics' / LYRICS))
        self.assertEqual({LYRICS}, self.changed_files('lyrics') - {'original-text-lyrics-provenance.json'})

    def test_the_provenance_row_names_the_advance_and_recovers_the_previous_policy(self):
        provenance = json.loads(text_of(self.with_layer / 'lyrics/original-text-lyrics-provenance.json'))
        row = next(r for r in provenance['sources'] if r['file'] == 'LyricsDisplayPolicy.kt')
        self.assertEqual(gen.COMMIT, row['upstreamCommit'])
        self.assertTrue(row['completeOriginalFile'])
        self.assertEqual(gen.MANIFEST_SHA256, row['v046AudioListenManifestSha256'])
        self.assertIn(gen.COMMIT, provenance['upstreamCommits'])
        proof = row['v046AudioListenAdvance']['retainedToCurrent']
        self.assertEqual(self.base('lyrics', LYRICS), gen.restore(self.out('lyrics', LYRICS), proof))
        self.assertEqual(sha(self.out('lyrics', LYRICS)), proof['afterSHA256'])
        self.assertEqual(self.sources['LyricsDisplayPolicy.kt'], self.out('lyrics', LYRICS))


class ProofMixin:
    """Both receipts of a whole-file advance recover the exact stages."""
    def check_proof(self, proof, body, before, original, leaf):
        self.assertEqual(gen.COMMIT, proof['upstreamCommit'])
        self.assertEqual(gen.MANIFEST_SHA256, proof['sourceManifestSHA256'])
        self.assertTrue(proof['wholeOriginal'])
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertEqual(sha(body), proof['adaptedSHA256'])
        self.assertEqual(sha(original), proof['originalSHA256'])
        self.assertEqual(sha(before), proof['predecessorSHA256'])
        self.assertEqual(self.manifest['beforeFinalOutputs'][leaf]['sha256'], sha(before))
        self.assertEqual(original, gen.restore(body, proof['originalToWindows']))
        self.assertEqual(before, gen.restore(body, proof['retainedToCurrent']))


class ListenScreenTests(Fixture, ProofMixin):
    def proof(self):
        return json.loads(text_of(self.with_layer / 'listen' / gen.PROOFS['listen']))

    def test_the_screen_is_the_whole_v046_original_and_both_receipts_recover_the_exact_stages(self):
        body, before = self.out('listen', LISTEN), self.base('listen', LISTEN)
        self.check_proof(self.proof(), body, before, self.sources['ListenVideoScreen.kt'],
                         'listen/ListenVideoScreen.kt')
        receipts = json.loads(text_of(self.with_layer / 'listen/listen-original-source-receipt.json'))
        receipt = next(r for r in receipts if r.get('output') == LISTEN)
        self.assertEqual(self.proof()['originalToWindows'], receipt['v046AudioListenAdvance']['originalToWindows'])
        # the predecessor receipt is untouched: the v038 recipe still rebuilds the v0.3.8 whole original
        import v038_listen_screen
        stage = body
        stage = gen.restore(stage, receipt['v046AudioListenAdvance']['retainedToCurrent'])
        for edit in reversed(receipt['edits']):
            at = edit['offset']
            self.assertEqual(edit['after'], stage[at:at + len(edit['after'])])
            stage = stage[:at] + edit['before'] + stage[at + len(edit['after']):]
        self.assertEqual(v038_listen_screen.fixed_sources(REPO)['ListenVideoScreen.kt'], stage)

    def test_only_the_screen_its_receipts_and_the_new_proof_differ_from_the_unadvanced_listen_output(self):
        self.assertEqual({LISTEN, 'listen-original-source-receipt.json'}, self.changed_files('listen'))
        self.assertTrue((self.with_layer / 'listen' / gen.PROOFS['listen']).is_file())
        self.assertFalse((self.without / 'listen' / gen.PROOFS['listen']).exists())
        # the v038 proof and the tests output are exactly the predecessor's
        self.assertEqual(tree(self.with_layer / 'listen-tests'), tree(self.without / 'listen-tests'))

    def test_recent_plays_reach_the_screen_through_the_retained_page_only(self):
        body = self.out('listen', LISTEN)
        for token in ('onPlayFromHistory: (bvid: String, cid: Long) -> Unit,', 'var showPlayHistory by rememberSaveable',
                      'onOpenHistory = { showPlayHistory = true },', 'Icons.Filled.History', 'centerGlassContent = true,',
                      'LocalDesktopOriginalMusicUiPlatform provides page.historyPlatform,',
                      'onPlay = { entry -> page.runUi {', 'onPlayFromHistory(entry.bvid, entry.cid)',
                      'val page = LocalDesktopOriginalListenVideoPage.current'):
            self.assertEqual(1, body.count(token), token)
        for gone in ('PlaylistManager', 'viewModel()', 'LocalContext', 'SettingsManager', 'androidx.lifecycle.viewmodel'):
            self.assertNotIn(gone, body)
        # the page is read before its first use, and every callback of the original is still admitted by it
        self.assertLess(body.index('val page = LocalDesktopOriginalListenVideoPage.current'),
                        body.index('val state by viewModel.uiState'))
        # one more admitted callback than the v0.3.8 screen: the history play callback
        self.assertEqual(self.base('listen', LISTEN).count('page.runUi {') + 1, body.count('page.runUi {'))
        self.assertEqual(1, body.count('page.playlist.uiState'))

    def test_the_proof_names_every_replayed_port_and_the_two_counted_ports(self):
        proof = self.proof()
        receipts = json.loads(text_of(self.with_layer / 'listen/listen-original-source-receipt.json'))
        recorded = next(r for r in receipts if r.get('output') == LISTEN)['edits']
        labels = [port['label'] for port in proof['replayedPorts']]
        # the one re-anchored v038 port is replaced by two, every other recorded port is replayed as it was
        self.assertEqual(len(recorded) + 1, len(labels))
        self.assertIn('The retained Root page is read before the first original state', labels)
        self.assertIn('The original queue state flows come from the Root playlist binding', labels)
        self.assertNotIn(gen.LISTEN_READJUSTED_LABEL, labels)
        self.assertEqual([e['label'] for e in recorded if e['label'] != gen.LISTEN_READJUSTED_LABEL],
                         [l for l in labels if not l.startswith(('The retained Root page', 'The original queue state'))])
        self.assertEqual(2, len(proof['windowsEdits']))


class MusicTests(Fixture, ProofMixin):
    def proof(self):
        return json.loads(text_of(self.with_layer / 'music' / gen.PROOFS['music']))

    def test_music_player_content_is_the_whole_v046_original_with_the_recorded_ports(self):
        proof = self.proof()['musicPlayerContent']
        body, before = self.out('music', MUSIC), self.base('music', MUSIC)
        self.check_proof(proof, body, before, self.sources['MusicPlayerContent.kt'], 'music/MusicPlayerContent.kt')
        self.assertEqual(sha(body), proof['outputFileSHA256'])
        # the recorded ports rebuild the predecessor from the whole 79e8 original, and only the four atmosphere ports are new
        self.assertEqual(gen.BASELINE_SHA256['MusicPlayerContent.kt'], sha(
            gen._baseline(REPO, 'feature/audio/screen/MusicPlayerContent.kt', 'MusicPlayerContent.kt')))
        self.assertEqual(4, len(proof['windowsEdits']))
        self.assertEqual(25 + 1, len(proof['replayedPorts']))

    def test_the_cover_flow_is_the_whole_v046_file_over_the_unchanged_79e8_original(self):
        proof = self.proof()['music3DCoverFlow']
        emitted = (self.with_layer / 'music' / COVER).read_bytes()
        self.assertEqual((REPO / gen.ARCHIVE / 'Music3DCoverFlow.kt').read_bytes(), emitted)
        self.assertEqual(self.base('music', COVER), gen.restore(self.out('music', COVER), proof['retainedToCurrent']))
        self.assertEqual(gen.BASELINE_SHA256['Music3DCoverFlow.kt'], proof['predecessorSHA256'])
        self.assertEqual([], proof['windowsEdits'])

    def test_only_the_two_files_and_the_new_proof_differ_from_the_unadvanced_music_output(self):
        self.assertEqual({MUSIC, COVER}, self.changed_files('music'))
        self.assertTrue((self.with_layer / 'music' / gen.PROOFS['music']).is_file())

    def test_the_audio_waveform_atmosphere_does_not_exist_on_windows(self):
        body = self.out('music', MUSIC)
        for token in ('MusicAtmosphereHost', 'audioEnergy', 'AudioSpectrum', 'AudioWaveform', 'android.widget',
                      'Toast', '音乐氛围：开启', '音乐氛围：关闭', '已开启音乐氛围', '已关闭音乐氛围'):
            self.assertNotIn(token, body, token)
        self.assertIn('    val audioSpectrumEnabled = false\n', body)
        self.assertEqual(1, body.count('    run {\n        if (coverBitmap != null) {'))
        # the cover flow background keeps the v0.3.4 signature the Windows output carries
        self.assertNotIn('audioEnergyProvider', body)

    def test_no_android_only_reference_survives_in_any_advanced_output(self):
        for group, relative in (('music', MUSIC), ('music', COVER), ('audio', AUDIO), ('listen', LISTEN)):
            lines = [line.strip() for line in self.out(group, relative).split('\n') if ANDROID.search(line)]
            before = [line.strip() for line in self.base(group, relative).split('\n') if ANDROID.search(line)]
            self.assertEqual(before, lines, relative)
        self.assertEqual([], [line for line in self.out('music', MUSIC).split('\n') if ANDROID.search(line)])

    def test_the_v046_behaviours_are_in_the_music_player(self):
        body = self.out('music', MUSIC)
        for token in ('resolveLyricsDisplayDocument(', 'val displayState = state.copy(lyrics = displayLyrics)',
                      'isLikeStatusKnown: Boolean = true,', 'videoSubtitlesEnabled: Boolean = true,',
                      'onVideoSubtitlesEnabledChange: ((Boolean) -> Unit)? = null,', 'centerGlassContent = true,',
                      'showQueue = false\n                        onQueueItemSelected(index)',
                      'private fun MusicLyricsViewportScrims(', 'AppContentColors.primaryText()',
                      'compositingStrategy = CompositingStrategy.ModulateAlpha',
                      'LocalAppButtonSlotColorCorrectionEnabled provides false',
                      'import com.android.purebilibili.core.ui.components.AppHorizontalPager as HorizontalPager',
                      'import com.bilipai.desktop.ui.asImageBitmap\n'):
            self.assertIn(token, body, token)
        # the scrims between the Android bitmap helper and PlayerPage survived the recorded block removal
        self.assertEqual(1, body.count('private fun MusicLyricsViewportScrims('))
        self.assertEqual(2, body.count('MusicLyricsViewportScrims('))
        self.assertNotIn('toAndroidBitmapOrNull', body)
        # the dropped top-control drag transform is no longer used by the player
        self.assertNotIn('resolveMusicTopControlTransform', body)

    def test_the_cover_flow_carries_the_unknown_like_placeholder(self):
        body = self.out('music', COVER)
        self.assertIn('isLikeStatusKnown: Boolean = true,', body)
        self.assertIn('if (onLikeClick != null && isLikeStatusKnown) {', body)

    def test_every_new_import_of_a_project_symbol_has_a_definer(self):
        pattern = re.compile(r'(?m)^import ((?:com\.android\.purebilibili|com\.bilipai\.desktop)\.[\w.]+?)(?: as \w+)?$')
        roots = ['desktop/src', 'desktop/upstream-slices', 'app', 'design-system', 'core-data', 'core-player',
                 'network-core', 'settings-core', 'danmaku-engine', 'plugin-sdk']
        # symbols the same producers emit are definers too (they are not in the tree)
        emitted = ''.join(text_of(p) for root in self.with_layer.iterdir() if root.is_dir()
                          for p in root.rglob('*.kt'))
        checked = 0
        for group, relative in (('music', MUSIC), ('music', COVER), ('audio', AUDIO), ('listen', LISTEN)):
            new = set(pattern.findall(self.out(group, relative))) - set(pattern.findall(self.base(group, relative)))
            for imported in sorted(new):
                name = imported.rsplit('.', 1)[1]
                declaration = r'(fun|val|var|class|object|interface|typealias)[[:space:]]+(<[^>]*>[[:space:]]+)?([A-Za-z0-9_.<>?, ]+\.)?' + name + r'\b'
                found = subprocess.run(['git', '-c', 'core.autocrlf=false', 'grep', '--untracked', '-q', '-E',
                                        declaration, '--', *roots], cwd=REPO).returncode == 0
                if not found:
                    found = re.search(declaration.replace('[[:space:]]', r'\s'), emitted) is not None
                self.assertTrue(found, 'No definer for ' + imported + ' (' + relative + ')')
                checked += 1
        self.assertGreaterEqual(checked, 8)


class AudioModeTests(Fixture, ProofMixin):
    def test_audio_mode_player_is_the_whole_v046_original_with_the_recorded_ports(self):
        proof = json.loads(text_of(self.with_layer / 'audio' / gen.PROOFS['audio']))
        body, before = self.out('audio', AUDIO), self.base('audio', AUDIO)
        self.check_proof(proof, body, before, self.sources['AudioModeMusicPlayer.kt'], 'audio/AudioModeMusicPlayer.kt')
        self.assertEqual(sha(body), proof['outputFileSHA256'])
        # no counted port is left: the part selector the Windows tree compiles is the whole v0.4.6 one (episode selector slice)
        self.assertEqual(0, len(proof['windowsEdits']))
        self.assertEqual({AUDIO}, self.changed_files('audio'))

    def test_the_v046_behaviours_are_in_the_audio_mode_player(self):
        body = self.out('audio', AUDIO)
        for token in ('var videoSubtitlesEnabled by rememberSaveable { mutableStateOf(true) }',
                      'videoSubtitlesEnabled = videoSubtitlesEnabled,',
                      'onVideoSubtitlesEnabledChange = { videoSubtitlesEnabled = it },',
                      'isLikeStatusKnown = engagementState.likeStatusKnown &&',
                      'platform.playlist.playAt(index)?.let { item ->',
                      'if (player != null && !player.isPlaying) player.handleAudioModePlayPause()'):
            self.assertEqual(1, body.count(token), token)
        for gone in ('PlaylistManager', 'viewModel()', 'LocalContext', 'ExoPlayer'):
            self.assertNotIn(gone, body)
        # the selector argument is carried exactly as v0.4.6 writes it: its callee is the whole v0.4.6 PagesSelector
        self.assertEqual(1, body.count('coverUrl = info.pic,'))
        self.assertEqual(1, self.sources['AudioModeMusicPlayer.kt'].count('coverUrl = info.pic'))

    def test_the_selector_the_windows_tree_compiles_has_the_cover_parameter(self):
        # the v0.3.7 selector of the older archive has none, which is why this slice once dropped the argument
        old = text_of(REPO / 'desktop/upstream-slices/v037-player-closure/PagesSelector.kt')
        old_signature = old[old.index('fun PagesSelector('):old.index(') {', old.index('fun PagesSelector('))]
        self.assertNotIn('coverUrl', old_signature)
        selector = text_of(REPO / 'desktop/upstream-slices/v046-episode-selector/PagesSelector.kt')
        signature = selector[selector.index('fun PagesSelector('):selector.index(') {', selector.index('fun PagesSelector('))]
        self.assertIn('coverUrl', signature)


class DeterminismAndHygieneTests(Fixture):
    def test_a_second_run_is_byte_identical(self):
        again = self.root / 'again'
        run_all(again, True)
        for group in ('listen', 'music', 'audio', 'lyrics'):
            self.assertEqual(tree(self.with_layer / group), tree(again / group), group)

    def test_every_advanced_output_has_balanced_delimiters_and_lf_endings(self):
        sync = load('al_sync', 'sync-upstream.py')
        for group, relative in (('listen', LISTEN), ('music', MUSIC), ('music', COVER), ('audio', AUDIO),
                                ('lyrics', LYRICS)):
            raw = (self.with_layer / group / relative).read_bytes()
            self.assertNotIn(b'\r', raw)
            depth = {'{': 0, '(': 0, '[': 0}
            closers = {'}': '{', ')': '(', ']': '['}
            for word, _a, _b in sync.kotlin_tokens(raw.decode('utf8')):
                if word in depth:
                    depth[word] += 1
                elif word in closers:
                    depth[closers[word]] -= 1
                    self.assertGreaterEqual(depth[closers[word]], 0, relative)
            self.assertEqual({'{': 0, '(': 0, '[': 0}, depth, relative)

    def test_every_non_private_top_level_name_of_an_advanced_file_has_one_owner(self):
        sync = load('al_sync2', 'sync-upstream.py')
        modifiers = {'internal', 'public', 'inline', 'suspend', 'operator', 'tailrec', 'infix', 'data', 'sealed',
                     'enum', 'const', 'open', 'abstract', 'annotation', 'fun', 'value', 'expect', 'actual', 'external'}

        def top_level(text):
            tokens = sync.kotlin_tokens(text)
            depth, parens, names = 0, 0, set()
            for index, (word, _a, _b) in enumerate(tokens[:-1]):
                parens += (word in '([') - (word in ')]') if len(word) == 1 else 0
                if depth == 0 and parens == 0 and word in {'fun', 'class', 'object', 'interface', 'typealias', 'val', 'var'}:
                    back, private = index - 1, False
                    while back >= 0 and tokens[back][0] in modifiers | {'private'}:
                        private = private or tokens[back][0] == 'private'
                        back -= 1
                    following = tokens[index + 1][0]
                    if not private and following != '<':
                        names.add(following)
                depth += (word == '{') - (word == '}')
            return names

        owners = {}
        for group in ('listen', 'music', 'audio', 'lyrics'):
            for path in (self.with_layer / group).rglob('*.kt'):
                relative = group + '/' + path.relative_to(self.with_layer / group).as_posix()
                for name in top_level(text_of(path)):
                    owners.setdefault(name, set()).add(relative)
        advanced = {LISTEN: 'listen', MUSIC: 'music', COVER: 'music', AUDIO: 'audio', LYRICS: 'lyrics'}
        checked = 0
        for relative, group in advanced.items():
            for name in top_level(self.out(group, relative)):
                self.assertEqual({group + '/' + relative}, owners[name], name)
                checked += 1
        self.assertGreater(checked, 10)
        desktop = ''.join(text_of(p) for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt'))
        for name in ('ListenVideoRoute', 'MusicPlayerContent', 'AudioModeMusicPlayer', 'resolveLyricsDisplayDocument'):
            self.assertEqual(0, len(re.findall(r'(?m)^\s*(?:internal |private |public )?fun ' + name + r'\b', desktop)), name)

    def test_the_slice_never_touches_the_audio_output_paths(self):
        words = re.compile(r'(?i)wasapi|exclusive|toslink|AudioOutput|DesktopWindowsAudio|mpv_|MpvPlayer')
        for path in (UI / 'DesktopOriginalListenHistoryPlatform.kt', UI / 'DesktopOriginalListenVideoHost.kt',
                     UI_TESTS / 'DesktopOriginalListenHistoryPlatformTest.kt'):
            self.assertIsNone(words.search(text_of(path)), path.name)
        # the generator names the output path only in its docstring, to say it is not touched
        source = text_of(TOOLS / 'v046_audio_listen_closure.py')
        self.assertIn(gen.__doc__, source)
        self.assertIsNone(words.search(source.replace(gen.__doc__, '')))
        for group, relative in (('music', MUSIC), ('audio', AUDIO), ('listen', LISTEN)):
            self.assertEqual([], [m.group(0) for m in words.finditer(self.out(group, relative)) if m.group(0) not in
                                  {m2.group(0) for m2 in words.finditer(self.base(group, relative))}], relative)


class HostTests(unittest.TestCase):
    def test_the_host_pushes_audio_mode_with_the_history_entry_and_supplies_the_history_platform(self):
        host = text_of(UI / 'DesktopOriginalListenVideoHost.kt')
        self.assertEqual(1, host.count(
            'onPlayFromHistory = { bvid, cid -> page.runUi {\n'
            '                routes.push(BiliPaiNavKey.AudioMode(sourceBvid = bvid, sourceCid = cid))\n'
            '            } },\n'))
        self.assertEqual(1, host.count('DesktopOriginalListenHistoryPlatform(settings, '
                                       'DesktopOriginalMusicStorageBinding(settings).history)'))
        self.assertEqual(1, host.count('val historyPlatform: DesktopOriginalMusicUiPlatform = run {'))
        self.assertEqual(1, host.count('{ action -> isCurrentUi() && entry.commit(action) })'))
        self.assertEqual(1, host.count('val pluginContext = LocalDesktopHomeEnvironment.current.pluginContext'))
        self.assertEqual(1, host.count('    pluginContext: DesktopPluginContext,\n'))
        self.assertEqual(1, host.count('remember(entry, routes, pageScope, pagerHosted, pluginContext) {'))
        self.assertEqual(1, host.count('{ latestActive }, pagerHosted, pluginContext)'))
        # every callback of the route is admitted by the retained page, the history one included
        route = host[host.index('ListenVideoRoute('):]
        for callback in ('onNowPlayingClick', 'onPlayTracks', 'onPlayFromHistory', 'onPlayCollection'):
            self.assertRegex(route, callback + r' = \{[^\n]*page\.runUi \{', callback)
        self.assertEqual(1, route.count('onLogin = { page.runUi { routes.push(BiliPaiNavKey.Login) } },'))

    def test_the_platform_overrides_every_member_of_the_interface_and_only_history_and_context_are_real(self):
        interface = text_of(UI / 'DesktopOriginalMusicUiPlatform.kt')
        body = interface[interface.index('internal interface DesktopOriginalMusicUiPlatform {'):]
        body = body[:body.index('\n}\n')]
        members = re.findall(r'(?m)^    (?:suspend )?(?:val|fun) (\w+)', body)
        self.assertEqual(['context', 'homeSettings', 'systemReduceMotion', 'isInBackground', 'blurEffectsAvailable',
                          'volume', 'maximumVolumeStep', 'setVolumeFraction', 'history', 'externalPlaylist',
                          'savePlaylist', 'loadOwnedArtwork', 'commitUi'], members)
        platform = text_of(UI / 'DesktopOriginalListenHistoryPlatform.kt')
        overridden = re.findall(r'override (?:suspend )?(?:val|fun) (\w+)', platform)
        # context and history are constructor properties, so they come first in the file
        self.assertEqual(sorted(members), sorted(overridden))
        real = {'context', 'history'}
        for name in members:
            line = next(l for l in platform.split('\n') if re.search(r'override (?:suspend )?(?:val|fun) ' + name + r'\b', l))
            if name in real:
                self.assertNotIn('notUsed', line)
            else:
                self.assertIn('notUsed("' + name + '")', line)

    def test_the_jvm_regression_test_has_five_cases_and_balanced_delimiters(self):
        path = UI_TESTS / 'DesktopOriginalListenHistoryPlatformTest.kt'
        text = text_of(path)
        self.assertEqual(5, len(re.findall(r'@Test fun ', text)))
        sync = load('al_sync3', 'sync-upstream.py')
        for source in (text, text_of(UI / 'DesktopOriginalListenHistoryPlatform.kt'),
                       text_of(UI / 'DesktopOriginalListenVideoHost.kt')):
            depth = {'{': 0, '(': 0, '[': 0}
            closers = {'}': '{', ')': '(', ']': '['}
            for word, _a, _b in sync.kotlin_tokens(source):
                if word in depth:
                    depth[word] += 1
                elif word in closers:
                    depth[closers[word]] -= 1
            self.assertEqual({'{': 0, '(': 0, '[': 0}, depth)
        self.assertIn('DesktopOriginalListenHistoryPlatform', text)
        self.assertEqual(0, len(re.findall(r'Thread\.sleep|Dispatchers\.Main|java\.awt|MpvPlayer|HttpClient|URL\(', text)))


class LyricsJvmTestTests(unittest.TestCase):
    def test_the_display_projection_regression_test_pins_the_original_function_without_a_platform(self):
        text = text_of(UI_TESTS / 'DesktopListenLyricsDisplayDocumentTest.kt')
        self.assertEqual(4, len(re.findall(r'@Test fun ', text)))
        self.assertIn('import com.android.purebilibili.feature.audio.lyrics.resolveLyricsDisplayDocument\n', text)
        self.assertEqual(0, len(re.findall(r'Thread\.sleep|Dispatchers|java\.awt|MpvPlayer|HttpClient|URL\(|Files\.', text)))
        sync = load('al_sync4', 'sync-upstream.py')
        depth = {'{': 0, '(': 0, '[': 0}
        closers = {'}': '{', ')': '(', ']': '['}
        for word, _a, _b in sync.kotlin_tokens(text):
            if word in depth:
                depth[word] += 1
            elif word in closers:
                depth[closers[word]] -= 1
        self.assertEqual({'{': 0, '(': 0, '[': 0}, depth)
        # the function under test is the whole v0.4.6 one that the archive carries
        policy = text_of(REPO / gen.ARCHIVE / 'LyricsDisplayPolicy.kt')
        self.assertIn('it.copy(translations = emptyList(), romanization = null)', policy)


class WiringTests(unittest.TestCase):
    def test_gradle_hooks_the_four_producers_once_without_an_exclude(self):
        build = text_of(REPO / 'desktop/build.gradle.kts')
        block = build[build.index('// v0.4.6 listen-to-video closure'):]
        block = block[:block.index('}\n', block.index('configureEach'))]
        for task in ('extractOriginalListenVideoPages', 'extractOriginalMusicPlayerFull',
                     'extractOriginalVideoAudioFull', 'extractOriginalTextLyrics'):
            self.assertEqual(1, block.count('"' + task + '"'), task)
            self.assertIn('val ' + task + ' by tasks.registering(Exec::class)', build)
        self.assertEqual(1, block.count('inputs.files("tools/v046_audio_listen_closure.py", "tools/v044_favorite_detail.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-audio-listen-closure")'))
        self.assertEqual(1, build.count('upstream-slices/v046-audio-listen-closure'))
        excludes = [line for line in build.split('\n') if 'exclude(canonicalOriginalIdentity(' in line]
        for leaf in ('ListenVideoScreen.kt', 'MusicPlayerContent.kt', 'Music3DCoverFlow.kt', 'AudioModeMusicPlayer.kt'):
            self.assertEqual([], [line for line in excludes if leaf in line], leaf)
        # LyricsDisplayPolicy keeps the one exclude it already had; this slice adds none
        self.assertEqual(1, len([line for line in excludes if 'LyricsDisplayPolicy.kt' in line]))

    def test_the_catalog_has_the_archive_its_leaves_and_five_inventory_rows(self):
        catalog = json.loads(text_of(REPO / 'desktop/tools/compatibility-source-inputs.json'))
        self.assertIn(gen.ARCHIVE, catalog['buildInputRoots'])
        archive = next(a for a in catalog['archives'] if a['directory'] == gen.ARCHIVE)
        self.assertEqual(sorted(gen.EXPECTED | {'manifest.json', 'LICENSE'}),
                         sorted(Path(f['path']).name for f in archive['files']))
        rows = [r for r in catalog['inputs'] if r['localPath'].startswith(gen.ARCHIVE + '/')]
        self.assertEqual(sorted(gen.EXPECTED), sorted(Path(r['localPath']).name for r in rows))
        _, manifest = gen.fixed(REPO)
        pins = {r['filename']: r for r in manifest['files']}
        for row in rows:
            pin = pins[Path(row['localPath']).name]
            self.assertEqual((pin['path'], pin['gitBlob'], pin['sha256'], gen.COMMIT),
                             (row['path'], row['blob'], row['sha256'], row['upstreamCommit']))
            self.assertFalse(row['referenceOnly'])
            self.assertEqual(['v046-audio-listen-closure'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])


if __name__ == '__main__':
    unittest.main()
