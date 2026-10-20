"""Source checks for roadmap slice bgm-player-viewmodel-and-features (part 1). Nothing here compiles Kotlin or starts a JVM."""
from collections import Counter
from pathlib import Path
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v046_bangumi_player as bgm

TOOLS = REPO / 'desktop/tools'
UI = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui'
PLAYER_PRODUCER = 'extract-upstream-bangumi-player.py'
PAGES_PRODUCER = 'extract-upstream-bangumi-pages.py'
PORTS_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopBangumiPlayerPortsTest.kt'
PORTS_TEST_CASES = 7
VM = bgm.VM_OUTPUT
FEATURES = bgm.FEATURES_OUTPUT
POLICY = bgm.POLICY_OUTPUT

PLAYER_HOOKS = [
    "from v046_bangumi_player import apply_view_model as apply_bangumi_v046_view_model,emit_features as "
    "emit_bangumi_v046_features,validate_outputs as validate_bangumi_v046_outputs\n",
    "validate_bangumi_v046_outputs(repo,out)\n",
    " body=apply_bangumi_v046_view_model(repo,recipe['output'],body,out)\n",
    "emit_bangumi_v046_features(repo,out)\n",
]
PAGES_HOOK_START = '# v0.4.6 follow-session policy (targetSeasonId'
PAGES_HOOK_END = "emit_follow_preload_test(C,OUT.parent/(OUT.name+'-tests'))\n"
UI_PRODUCER = 'extract-upstream-bangumi-player-ui.py'
UI_HOOK = "  from v046_bangumi_player import apply_retry_ui\n  body=apply_retry_ui(a.repo,r['output'],body,a.output)\n"
UI_ROOT = 'com/android/purebilibili/feature/bangumi/'
UI_OUTPUTS = {
    bgm.SCREEN_OUTPUT: 'DesktopOriginalBangumiPlayerScreen.kt',
    bgm.COMPONENTS_OUTPUT: 'ui/player/DesktopOriginalBangumiPlayerComponents.kt',
    bgm.OVERLAY_HOST_OUTPUT: 'ui/player/DesktopOriginalBangumiPlayerOverlayHost.kt',
}


def load(name):
    spec = importlib.util.spec_from_file_location('bgm_player_' + name.replace('-', '_').replace('.', '_'), TOOLS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_producer(script, output):
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1', PYTHONPATH=str(TOOLS))
    result = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(script), '--repo', str(REPO), '--output', str(output)],
                            env=env, capture_output=True, text=True, timeout=240)
    if result.returncode != 0:
        raise AssertionError(result.stdout + result.stderr)


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


def balanced(testcase, name, text, syntax):
    stack = []
    for token, first, _ in syntax.kotlin_tokens(text):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            testcase.assertTrue(stack, (name, first))
            testcase.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (name, first))
    testcase.assertEqual([], stack, name)


def gradle_braces_balanced(text):
    depth = 0
    i, n = 0, len(text)
    backslash = chr(92)
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j == -1 else j
            continue
        if text.startswith('/*', i):
            i = text.find('*/', i) + 2
            continue
        if text.startswith('"""', i):
            i = text.find('"""', i + 3) + 3
            continue
        if text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == backslash else 1
            i = j + 1
            continue
        if text[i] == "'":
            i = text.find("'", i + 1) + 1
            continue
        depth += (text[i] == '{') - (text[i] == '}')
        if depth < 0:
            return False
        i += 1
    return depth == 0


class BangumiPlayerSliceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-bgm-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.sources, cls.manifest = bgm.fixed(REPO)
        cls.syntax = load('sync-upstream.py')
        run_producer(TOOLS / PLAYER_PRODUCER, cls.root / 'player')
        run_producer(TOOLS / PAGES_PRODUCER, cls.root / 'pages')
        run_producer(TOOLS / UI_PRODUCER, cls.root / 'ui')
        # The same producers with this slice's hook lines removed give the predecessor stage of every output.
        player = (TOOLS / PLAYER_PRODUCER).read_text(encoding='utf8')
        for hook in PLAYER_HOOKS:
            assert player.count(hook) == 1, hook
            player = player.replace(hook, '')
        pages = (TOOLS / PAGES_PRODUCER).read_text(encoding='utf8')
        start = pages.index(PAGES_HOOK_START)
        end = pages.index(PAGES_HOOK_END) + len(PAGES_HOOK_END)
        pages = pages[:start] + pages[end:]
        ui = (TOOLS / UI_PRODUCER).read_text(encoding='utf8')
        assert ui.count(UI_HOOK) == 1
        ui = ui.replace(UI_HOOK, '')
        for name, text, key in [('player-before.py', player, 'player'), ('pages-before.py', pages, 'pages'),
                                ('ui-before.py', ui, 'ui')]:
            (cls.root / name).write_text(text, encoding='utf8', newline='\n')
            run_producer(cls.root / name, cls.root / ('before-' + key))
        cls.player = cls.root / 'player'
        cls.pages = cls.root / 'pages'
        cls.vm = (cls.player / VM).read_text(encoding='utf8')
        cls.vm_proof = json.loads((cls.player / bgm.PROOF_DIRECTORY / 'view-model.json').read_text(encoding='utf8'))

    # ---- archive -------------------------------------------------------------------------------------------------

    def test_archive_rejects_a_changed_extra_or_missing_leaf_manifest_and_license(self):
        self.assertEqual(4, len(self.sources))
        self.assertEqual(bgm.COMMIT, self.manifest['commit'])
        for name in [*bgm.EXPECTED, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-bgm-a-') as temp:
                repo = Path(temp)
                shutil.copytree(REPO / bgm.ARCHIVE, repo / bgm.ARCHIVE)
                file = repo / bgm.ARCHIVE / name
                file.write_bytes((file.read_bytes() if file.exists() else b'') + b'\n')
                with self.assertRaises(ValueError):
                    bgm.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-bgm-a-') as temp:
            repo = Path(temp)
            shutil.copytree(REPO / bgm.ARCHIVE, repo / bgm.ARCHIVE)
            (repo / bgm.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                bgm.fixed(repo)

    def test_archive_leaves_are_the_upstream_blobs_and_lf_only(self):
        for row in self.manifest['files']:
            blob = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                   bgm.COMMIT + ':' + row['path']], capture_output=True, check=True).stdout
            self.assertEqual(blob, (REPO / bgm.ARCHIVE / row['filename']).read_bytes(), row['filename'])
            self.assertNotIn(b'\r', blob)

    def test_outputs_inside_the_archive_or_containing_it_are_rejected(self):
        for target in [REPO / bgm.ARCHIVE / 'child', REPO / 'desktop/upstream-slices']:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                bgm.validate_outputs(REPO, target)

    # ---- view model ----------------------------------------------------------------------------------------------

    def test_view_model_layer_is_the_exact_inverse_of_its_hunks_and_pins_both_ends(self):
        spec = self.manifest['viewModel']
        self.assertEqual(VM, spec['output'])
        self.assertEqual(bgm.VM_HUNK_COUNT, len(spec['hunks']))
        body = self.vm.split('\n', 1)[1]
        self.assertEqual(spec['afterSha256LF'], bgm.sha(body))
        predecessor = bgm.restore(body, self.vm_proof['retainedToCurrent'])
        self.assertEqual(spec['beforeSha256LF'], bgm.sha(predecessor))
        self.assertEqual(self.manifest['beforeFinalOutputs'][VM]['sha256'], bgm.sha(predecessor))
        before = (self.root / 'before-player' / VM).read_text(encoding='utf8').split('\n', 1)[1]
        self.assertEqual(before, predecessor)
        after, trace = bgm.apply_hunks(before, spec['hunks'])
        self.assertEqual(body, after)
        self.assertEqual(before, bgm.invert_trace(after, trace))
        self.assertEqual(len(spec['hunks']), len(trace))

    def test_every_line_the_layer_adds_is_upstream_text_or_a_declared_windows_binding(self):
        before = (self.root / 'before-player' / VM).read_text(encoding='utf8').split('\n', 1)[1]
        after = self.vm.split('\n', 1)[1]
        upstream = set(self.sources[bgm.VM_LEAF].splitlines())
        declared = self.manifest['viewModel']['windowsLines']
        added = Counter(after.splitlines()) - Counter(before.splitlines())
        undeclared = sorted(line for line in added if line not in upstream and line not in declared)
        self.assertEqual([], undeclared)
        self.assertEqual(bgm.VM_WINDOWS_LINE_COUNT, len(declared))
        self.assertEqual(declared, bgm.windows_lines(self.manifest['viewModel']['hunks'], self.sources[bgm.VM_LEAF]))
        self.assertEqual(declared, self.vm_proof['windowsLines'])
        for line in declared:
            self.assertIn(line, after.splitlines())

    def test_every_other_pgc_output_is_byte_identical_and_only_the_features_and_proofs_are_new(self):
        before, after = tree(self.root / 'before-player'), tree(self.player)
        self.assertEqual([], [k for k in before if k not in after])
        changed = sorted(k for k in before if before[k] != after[k])
        self.assertEqual([VM], changed)
        new = sorted(k for k in after if k not in before)
        self.assertEqual([FEATURES, bgm.PROOF_DIRECTORY + '/features.json', bgm.PROOF_DIRECTORY + '/view-model.json'], new)

    def test_view_model_carries_the_follow_retry_and_danmaku_members_and_drops_the_blocking_preload(self):
        unique = ['private val likeToggleMutex = Mutex()', 'val likeStatusKnown: Boolean = false',
                  'private fun ensureFollowCacheSession(): BangumiFollowSession',
                  'private fun acceptsFollowSession(session: BangumiFollowSession): Boolean',
                  'private fun verifyPlaybackFollowStatus(detail: BangumiDetail)',
                  'com.android.purebilibili.feature.video.viewmodel.queryVideoInteractionStatusWithRetry(',
                  'suspend fun sendEpisodeDanmaku(message: String, color: Int, mode: Int, fontSize: Int): Result<Unit>',
                  'fun saveCover(context: Context)', 'private var followVerificationJob: Job? = null',
                  'ActionRepository.cachedLikeStatus(']
        for token in unique:
            self.assertEqual(1, self.vm.count(token), token)
        self.assertGreaterEqual(self.vm.count('ActionRepository.queryLikeStatus('), 2)
        for token in ['viewModelScope', 'currentBangumiFollowSession', 'loadedFollowTypes', 'ensureFollowedSeasonsLoaded',
                      'TokenManager', 'DanmakuRepository', 'DownloadManager', 'android.content.Context', 'NetworkModule']:
            self.assertNotIn(token, self.vm, token)

    def test_view_model_uses_only_environment_members_the_platform_declares(self):
        platform = (UI / 'DesktopOriginalBangumiPlayerPlatform.kt').read_text(encoding='utf8')
        members = set(re.findall(r'environment\.(\w+)', self.vm))
        for member in ['followSession', 'danmaku', 'saveImageToGallery']:
            self.assertIn(member, members)
            self.assertIn('val ' + member, platform)
        for member in sorted(members):
            self.assertTrue(re.search(r'\bval ' + member + r'\b|\bfun ' + member + r'\b', platform), member)
        self.assertIn('fun hasPrimarySessionCookie(): Boolean', platform)
        actions = platform[platform.index('internal interface DesktopOriginalBangumiPlayerActions'):]
        actions = actions[:actions.index('\n}\n')]
        self.assertIn('suspend fun queryLikeStatus(aid: Long): Result<Boolean>', actions)
        self.assertIn('fun cachedLikeStatus(aid: Long): Boolean?', actions)
        owner = (UI / 'DesktopOriginalBangumiPlayerRootOwner.kt').read_text(encoding='utf8')
        for token in ['override suspend fun queryLikeStatus(aid: Long) = owner.environment.actions.queryLikeStatus(aid)',
                      'override fun cachedLikeStatus(aid: Long) = owner.environment.actions.cachedLikeStatus(aid)',
                      'danmaku = DesktopOriginalBangumiPlayerDanmakuView(', 'owner.environment.danmaku::isDanmakuServerDisabled',
                      'followSession = { desktopOriginalBangumiFollowSession(root.repository.sessionEpoch) }',
                      'owner.environment.download.saveImageToGallery(context, url, title)']:
            self.assertEqual(1, owner.count(token), token)

    def test_new_environment_ports_are_trailing_with_defaults_so_existing_constructors_still_bind(self):
        platform = (UI / 'DesktopOriginalBangumiPlayerPlatform.kt').read_text(encoding='utf8')
        tail = platform[platform.index('    val captureLoadCaller: suspend () -> Job,'):platform.index(') {\n    /** Read the existing Root quality mirror')]
        for name in ['danmaku', 'followSession', 'saveImageToGallery']:
            self.assertRegex(tail, 'val ' + name + '[^\n]*=')
        callers = 0
        for file in (REPO / 'desktop/src').rglob('*.kt'):
            text = file.read_text(encoding='utf8')
            callers += text.count('DesktopOriginalBangumiPlayerEnvironment(') - text.count('class DesktopOriginalBangumiPlayerEnvironment(')
        self.assertGreaterEqual(callers, 8)

    def test_fetch_play_url_callers_that_know_the_session_pass_it_and_the_rest_keep_the_default(self):
        signature = self.vm[self.vm.index('private suspend fun fetchPlayUrl('):]
        signature = signature[:signature.index(') {')]
        self.assertTrue(signature.rstrip().endswith('followSession: BangumiFollowSession = ensureFollowCacheSession()'))
        self.assertEqual(2, self.vm.count('followSession = session)'))
        self.assertEqual(1, self.vm.count('followSession = followSession,'))
        # The recovery lanes (recapture, retry, quality replacement) keep the default captured at call time.
        self.assertGreaterEqual(self.vm.count('fetchPlayUrl('), 6)

    def test_predecessor_drift_is_rejected_before_any_output(self):
        body = (self.root / 'before-player' / VM).read_text(encoding='utf8').split('\n', 1)[1]
        with tempfile.TemporaryDirectory(prefix='bp-bgm-o-') as out:
            for drift in [body + '\n', body.replace('    fun retry() {', '    fun retry2() {', 1), '']:
                with self.assertRaises(ValueError):
                    bgm.apply_view_model(REPO, VM, drift, out)
            self.assertEqual([], list(Path(out).iterdir()))
            self.assertEqual('same', bgm.apply_view_model(REPO, 'com/other/File.kt', 'same', out))
            self.assertEqual([], list(Path(out).iterdir()))

    # ---- like-status threading in the UI bodies ------------------------------------------------------------------------

    def test_ui_layer_threads_the_like_known_flag_with_five_counted_pairs_and_changes_nothing_else(self):
        before_tree, after_tree = tree(self.root / 'before-ui'), tree(self.root / 'ui')
        changed = sorted(k for k in before_tree if before_tree[k] != after_tree[k])
        self.assertEqual(sorted(UI_ROOT + rel for rel in UI_OUTPUTS.values()), changed)
        self.assertEqual([], [k for k in before_tree if k not in after_tree])
        new = sorted(k for k in after_tree if k not in before_tree)
        self.assertEqual(sorted(bgm.PROOF_DIRECTORY + '/ui-retry-' + Path(out).stem + '.json' for out in UI_OUTPUTS), new)
        self.assertEqual(5, sum(len(h) for h in bgm.UI_RETRY_HUNKS.values()))
        for output, relative in UI_OUTPUTS.items():
            before = (self.root / 'before-ui' / UI_ROOT / relative).read_text(encoding='utf8')
            after = (self.root / 'ui' / UI_ROOT / relative).read_text(encoding='utf8')
            self.assertEqual(self.manifest['beforeFinalOutputs'][output]['sha256'], bgm.sha(before))
            self.assertEqual(bgm.UI_RETRY_AFTER_SHA256[output], bgm.sha(after))
            proof = json.loads((self.root / 'ui' / bgm.PROOF_DIRECTORY / ('ui-retry-' + Path(output).stem + '.json')).read_text(encoding='utf8'))
            self.assertEqual(before, bgm.restore(after, proof['retainedToCurrent']))
            added = Counter(after.splitlines()) - Counter(before.splitlines())
            self.assertEqual(sum(len(h[2].splitlines()) - len(h[1].splitlines()) for h in bgm.UI_RETRY_HUNKS[output]),
                             sum(added.values()))
            for line in added:
                self.assertIn('likeStatusKnown', line)

    def test_ui_threading_lines_are_the_upstream_lines_pinned_in_the_manifest(self):
        provenance = self.manifest['uiRetry']['outputs']
        self.assertEqual(sorted(UI_OUTPUTS), sorted(provenance))
        for output, row in provenance.items():
            blob = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'rev-parse',
                                   bgm.COMMIT + ':' + row['upstreamPath']], capture_output=True, text=True, check=True).stdout.strip()
            self.assertEqual(blob, row['upstreamBlob'])
            upstream = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob', row['upstreamBlob']],
                                      capture_output=True, check=True).stdout.decode('utf8')
            stripped = [line.strip() for line in upstream.splitlines()]
            for line in row['upstreamLines']:
                self.assertIn(line, stripped, line)
            added = [new.replace(old, '', 1).strip() for _, old, new in bgm.UI_RETRY_HUNKS[output]]
            self.assertEqual(sorted(row['upstreamLines']), sorted(added))
        # Nothing consumes the flag except the carried overlay, which reads it from its state.
        overlay = (REPO / 'desktop/upstream-slices/v046-player-overlay/TopControlBar.kt').read_text(encoding='utf8')
        self.assertIn('isActive = likeStatusKnown && isLiked', overlay)

    def test_ui_bodies_declare_the_flag_before_use_and_the_overlay_host_has_one_caller(self):
        components = (self.root / 'ui' / UI_ROOT / UI_OUTPUTS[bgm.COMPONENTS_OUTPUT]).read_text(encoding='utf8')
        host = (self.root / 'ui' / UI_ROOT / UI_OUTPUTS[bgm.OVERLAY_HOST_OUTPUT]).read_text(encoding='utf8')
        screen = (self.root / 'ui' / UI_ROOT / UI_OUTPUTS[bgm.SCREEN_OUTPUT]).read_text(encoding='utf8')
        self.assertEqual(1, components.count('    likeStatusKnown: Boolean = false,\n'))
        self.assertEqual(1, host.count('    likeStatusKnown: Boolean,\n'))
        self.assertEqual(1, host.count('            likeStatusKnown = likeStatusKnown,\n'))
        self.assertEqual(1, screen.count('likeStatusKnown = successState?.likeStatusKnown == true,'))
        self.assertEqual(1, components.count('            likeStatusKnown = likeStatusKnown,\n'))
        calls = 0
        for other in list((self.root / 'ui').rglob('*.kt')) + list(self.player.rglob('*.kt')) + list((REPO / 'desktop/src').rglob('*.kt')):
            text = other.read_text(encoding='utf8')
            calls += text.count('BangumiPlayerOverlayHost(') - text.count('fun BangumiPlayerOverlayHost(')
        self.assertEqual(1, calls)

    # ---- follow policy ---------------------------------------------------------------------------------------------

    def test_policy_layer_restores_its_predecessor_and_keeps_every_older_declaration(self):
        proof = json.loads((self.pages / bgm.PROOF_DIRECTORY / 'follow-preload.json').read_text(encoding='utf8'))
        current = (self.pages / POLICY).read_text(encoding='utf8').replace('\r\n', '\n')
        self.assertEqual(bgm.POLICY_AFTER_SHA256, bgm.sha(current))
        before = bgm.restore(current, proof['retainedToCurrent'])
        self.assertEqual(self.manifest['beforeFinalOutputs'][POLICY]['sha256'], bgm.sha(before))
        old = (self.root / 'before-pages' / POLICY).read_text(encoding='utf-8').replace('\r\n', '\n')
        self.assertEqual(old, before)
        for name in ['FOLLOW_PRELOAD_PAGE_SIZE', 'FOLLOW_PRELOAD_MAX_PAGES', 'FollowPreloadResult',
                     'resolveFollowPreloadPageCount', 'preloadFollowedSeasonsForType']:
            self.assertIn(name, old)
            self.assertEqual(1, len(re.findall(r'(?:val|class|fun) ' + name + r'\b', current)), name)
        original = self.sources[bgm.POLICY_LEAF]
        selected, adapted = bgm.follow_preload_text(original)
        self.assertEqual(selected, bgm.restore(adapted, proof['originalToWindows']))
        for part in [original[:original.index(bgm.HEAD_END)],
                     original[original.index(bgm.PAGE_COUNT_START):original.index(bgm.PRELOAD_START)],
                     original[original.index(bgm.PRELOAD_START):]]:
            self.assertIn(part, original)
            self.assertIn(part, selected)
        self.assertEqual(4, len(bgm.POLICY_ADAPTATIONS))

    def test_policy_has_the_v046_session_contract_and_none_of_the_android_globals(self):
        text = (self.pages / POLICY).read_text(encoding='utf8').replace('\r\n', '\n')
        for token in ['internal data class BangumiFollowSession(val mid: Long?, val session: String?)',
                      'private val followVerificationPermit = Semaphore(1)', 'val targetFollowStatus: Int? = null',
                      '    targetSeasonId: Long? = null,\n', '    sessionProvider: () -> BangumiFollowSession = ::currentBangumiFollowSession,\n',
                      'internal fun currentBangumiFollowSession() = BangumiFollowSession(null, null)']:
            self.assertEqual(1, text.count(token), token)
        for token in ['TokenManager.', 'import com.android.purebilibili.core.store.TokenManager', 'BangumiRepository.',
                      'import com.android.purebilibili.data.repository.BangumiRepository', 'sessDataCache', 'midCache']:
            self.assertNotIn(token, text, token)
        last = text[text.index('    sessionProvider: '):]
        self.assertIn('    fetchPage: suspend (type: Int, page: Int, pageSize: Int) -> Result<MyFollowBangumiData>\n): FollowPreloadResult {', last)
        # The pages view model of the same producer calls the function with named arguments only. Since slice
        # bgm-hub-pages it carries the v0.4.6 target-season verification (a fresh found-set, the target season, the
        # page environment session and its request view); before that slice it kept the v0.2.5 full-scan call.
        hub = (self.pages / 'com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiViewModel.kt').read_text(encoding='utf-8')
        call = hub[hub.index('preloadFollowedSeasonsForType('):]
        call = call[:call.index('\n            )\n')]
        self.assertIn('type = type', call)
        self.assertIn('followedSeasonIds = foundIds', call)
        self.assertIn('targetSeasonId = seasonId', call)
        self.assertIn('sessionProvider = desktopEnvironment.followSession', call)
        self.assertIn('fetchPage = {', call)

    def test_follow_policy_upstream_suite_is_emitted_with_only_the_junit_import_binding(self):
        tests = self.root / 'pages-tests'
        emitted = (tests / bgm.TEST_OUTPUT).read_text(encoding='utf8')
        expected = self.sources[bgm.TEST_LEAF]
        for old, new in bgm.JUNIT_IMPORTS:
            expected = expected.replace(old, new)
        self.assertEqual(expected, emitted)
        self.assertEqual(bgm.TEST_COUNT, emitted.count('@Test'))
        self.assertEqual(bgm.TEST_AFTER_SHA256, bgm.sha(emitted))
        self.assertNotIn('org.junit', emitted)
        self.assertEqual([], [p for p in self.pages.rglob('*Test.kt')])
        proof = json.loads((tests / bgm.PROOF_DIRECTORY / 'follow-policy-test.json').read_text(encoding='utf8'))
        self.assertEqual(self.sources[bgm.TEST_LEAF], bgm.restore(emitted, proof['originalToWindows']))
        names = [p.relative_to(tests).as_posix() for p in tests.rglob('*.kt')]
        # The independent pages test root also holds the hub policy suite of slice bgm-hub-pages.
        self.assertEqual(sorted([bgm.TEST_OUTPUT, 'com/android/purebilibili/feature/bangumi/BangumiHubPolicyTest.kt']), sorted(names))

    # ---- features ----------------------------------------------------------------------------------------------------

    def test_features_file_declares_the_two_retired_seeds_exactly_once_in_the_producer_output(self):
        text = (self.player / FEATURES).read_text(encoding='utf8')
        self.assertEqual(1, text.count('\ndata class BangumiPlayerFeatures('))
        self.assertEqual(1, text.count('\ninternal fun rememberBangumiSubtitles('))
        self.assertEqual(1, text.count('\ndata class BangumiSubtitleState('))
        self.assertEqual(1, text.count('internal fun BoxScope.BangumiSubtitleOverlay('))
        self.assertIn('package com.android.purebilibili.feature.bangumi.ui.player\n', text)
        for other in list(self.pages.rglob('*.kt')) + list(self.player.rglob('*.kt')):
            if other != self.player / FEATURES:
                self.assertNotIn('data class BangumiPlayerFeatures(', other.read_text(encoding='utf-8'), other.name)
        for other in (REPO / 'desktop/src').rglob('*.kt'):
            body = other.read_text(encoding='utf-8')
            self.assertNotIn('data class BangumiPlayerFeatures(', body, other.name)
            self.assertNotIn('fun rememberBangumiSubtitles(', body, other.name)
        gaps = json.loads((TOOLS / 'symbol-closure-expected-gaps.json').read_text(encoding='utf8'))
        retired = {row['symbol']: row for row in gaps['retiredSeeds']}
        for symbol in ['BangumiPlayerFeatures', 'rememberBangumiSubtitles']:
            self.assertEqual('bgm-player-viewmodel-and-features', retired[symbol]['closedBy'])
            self.assertEqual('com.android.purebilibili.feature.bangumi.ui.player', retired[symbol]['package'])
        listed = [gap['symbol'] for group in gaps['groups'] for gap in group['gaps']]
        self.assertFalse([s for s in listed if s.endswith('BangumiPlayerFeatures') or s.endswith('rememberBangumiSubtitles')])

    def test_features_adaptations_are_counted_and_leave_no_android_or_global_access(self):
        self.assertEqual(12, len(bgm.FEATURES_ADAPTATIONS))
        original = self.sources[bgm.FEATURES_LEAF]
        adapted = bgm.features_text(original)
        text = (self.player / FEATURES).read_text(encoding='utf8')
        self.assertTrue(text.endswith(adapted))
        self.assertEqual(bgm.FEATURES_AFTER_SHA256, bgm.sha(text))
        for token in ['SettingsManager', 'VideoRepository', 'LocalContext', 'LocalConfiguration', 'media3', 'context',
                      'player.volume', 'onVolumeChanged']:
            self.assertNotIn(token, adapted, token)
        for label, old, new in bgm.FEATURES_ADAPTATIONS:
            self.assertEqual(1, original.count(old), label)
        # The two carried data classes are the untouched upstream declarations.
        start = original.index('/** Plain state and callbacks')
        end = original.index('@Composable\ninternal fun rememberBangumiSubtitles')
        self.assertIn(original[start:end], adapted)
        proof = json.loads((self.player / bgm.PROOF_DIRECTORY / 'features.json').read_text(encoding='utf8'))
        self.assertEqual(original, bgm.restore(adapted, proof['originalToWindows']))
        port = (UI / 'DesktopOriginalBangumiPlayerPorts.kt').read_text(encoding='utf8')
        used = sorted(set(re.findall(r'\bport\.(\w+)', adapted)))
        for member in used:
            self.assertRegex(port, '(?:val|fun) ' + member + r'\b', member)
        self.assertEqual(['autoPreference', 'getPlayerInfo', 'getSubtitleCues', 'isMuted', 'positionLocked', 'setPositionLocked',
                          'setVerticalOffsetFraction', 'verticalOffsetFraction'], used)

    # ---- determinism, wiring, hygiene -----------------------------------------------------------------------------------

    def test_producers_repeat_deterministically(self):
        again = self.root / 'again'
        run_producer(TOOLS / PLAYER_PRODUCER, again / 'player')
        run_producer(TOOLS / PAGES_PRODUCER, again / 'pages')
        run_producer(TOOLS / UI_PRODUCER, again / 'ui')
        self.assertEqual(tree(self.root / 'ui'), tree(again / 'ui'))
        self.assertEqual(tree(self.player), tree(again / 'player'))
        self.assertEqual(tree(self.pages), tree(again / 'pages'))

    def test_gradle_wiring_catalog_rows_and_balanced_build_script(self):
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertTrue(gradle_braces_balanced(build))
        block = build[build.index('tasks.matching { it.name in setOf("extractOriginalBangumiPlayer", "extractOriginalBangumiPages", "extractOriginalBangumiPlayerUi") }'):]
        block = block[:block.index('\n}\n')]
        self.assertEqual(1, block.count('inputs.file("tools/v046_bangumi_player.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-bangumi-player")'))
        self.assertEqual(1, build.count('outputs.dir(layout.buildDirectory.dir("generated/independent-bangumi-pages-tests"))'))
        self.assertEqual(1, build.count('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/independent-bangumi-pages-tests")) }'))
        # The main source root of the pages producer is unchanged: the test root is a sibling, never inside it.
        self.assertEqual(1, build.count('kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/independent-bangumi-pages")) }'))
        self.assertEqual(0, build.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/bangumi'))
        sync = load('sync-upstream.py')
        rows = [row for row in sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(bgm.ARCHIVE + '/')]
        self.assertEqual(4, len(rows))
        self.assertTrue(all(not row['referenceOnly'] for row in rows))
        catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf8'))
        self.assertIn(bgm.ARCHIVE, catalog['buildInputRoots'])
        self.assertEqual({'BangumiFollowStatusPolicy.kt', 'BangumiFollowStatusPolicyTest.kt', 'BangumiPlayerFeatures.kt',
                          'BangumiPlayerViewModel.kt', 'LICENSE', 'manifest.json'},
                         {Path(f['path']).name for a in catalog['archives'] if a['directory'] == bgm.ARCHIVE for f in a['files']})

    def test_changed_generated_and_desktop_owned_kotlin_keeps_balanced_delimiters(self):
        ui_texts = [(Path(out).name, (self.root / 'ui' / UI_ROOT / rel).read_text(encoding='utf8')) for out, rel in UI_OUTPUTS.items()]
        for name, text in ui_texts:
            balanced(self, name, text, self.syntax)
        for name, text in [('vm', self.vm), ('features', (self.player / FEATURES).read_text(encoding='utf8')),
                           ('policy', (self.pages / POLICY).read_text(encoding='utf-8')),
                           ('policy-test', (self.root / 'pages-tests' / bgm.TEST_OUTPUT).read_text(encoding='utf8')),
                           ('platform', (UI / 'DesktopOriginalBangumiPlayerPlatform.kt').read_text(encoding='utf8')),
                           ('ports', (UI / 'DesktopOriginalBangumiPlayerPorts.kt').read_text(encoding='utf8')),
                           ('root-owner', (UI / 'DesktopOriginalBangumiPlayerRootOwner.kt').read_text(encoding='utf8')),
                           ('ports-test', PORTS_TEST.read_text(encoding='utf8'))]:
            balanced(self, name, text, self.syntax)

    def test_new_jvm_test_has_the_counted_cases_and_needs_no_window_gpu_or_network(self):
        text = PORTS_TEST.read_text(encoding='utf8')
        self.assertEqual(PORTS_TEST_CASES, len(re.findall(r'@Test fun ', text)))
        for forbidden in ['MpvPlayer', 'java.net', 'okhttp', 'Retrofit', 'ProcessBuilder', 'System.load', 'javax.swing', 'java.awt']:
            self.assertNotIn(forbidden, text)

    def test_touched_text_files_are_lf_only(self):
        paths = [TOOLS / 'v046_bangumi_player.py', TOOLS / 'tests/test_v046_bangumi_player_source.py',
                 TOOLS / PLAYER_PRODUCER, TOOLS / PAGES_PRODUCER, TOOLS / UI_PRODUCER, TOOLS / 'symbol-closure-expected-gaps.json',
                 TOOLS / 'compatibility-source-inputs.json', TOOLS / 'sync-upstream.py', REPO / 'desktop/build.gradle.kts',
                 UI / 'DesktopOriginalBangumiPlayerPlatform.kt', UI / 'DesktopOriginalBangumiPlayerPorts.kt',
                 UI / 'DesktopOriginalBangumiPlayerRootOwner.kt', PORTS_TEST, *(REPO / bgm.ARCHIVE).iterdir()]
        for path in paths:
            self.assertNotIn(b'\r', path.read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
