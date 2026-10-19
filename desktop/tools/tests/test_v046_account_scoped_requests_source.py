"""v0.4.6 AccountScopedRequests carried whole, plus the audit of how Windows scopes requests to an account today.

Roadmap slice acct-account-scoped-requests, first step: enumerate which owners read the Root session epoch for
favourites, history, watch-later, home, the Bangumi hub, the Listen collection and profile, and adopt the upstream
behaviour only where the audit shows a gap. The audit found none (every page owner is retired and rebuilt by Root
when the epoch or the account MID changes), so the only carried piece is the definer the carried archives import.
"""
from pathlib import Path
from unittest import mock
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

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v044_favorite_subscription as subscription
import v046_account_scoped_requests as source


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync_upstream_account_scoped_requests', 'sync-upstream.py')
producer = load('extract_upstream_home_viewmodel_account_scoped_requests', 'extract-upstream-home-viewmodel.py')

WIN = 'desktop/src/main/kotlin/com/bilipai/desktop/'
UI = WIN + 'ui/'
TASK = 'extractOriginalHomeViewModel'
PRODUCER = 'extract-upstream-home-viewmodel.py'
DECLARES_OWNER = re.compile(rb'\bclass AccountScopedRequests\b')
IMPORT_LINE = 'import com.android.purebilibili.core.store.AccountSessionIdentity\n'
HAND_WRITTEN = ('desktop/src/test/kotlin/com/android/purebilibili/core/coroutines/'
                'DesktopAccountScopedRequestsRootIdentityTest.kt')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def flat(text):
    return re.sub(r'\s+', ' ', text)


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def read(path):
    return (REPO / path).read_text(encoding='utf-8').replace('\r\n', '\n')


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def tool_sources():
    """Every generator and producer; tests are not generators."""
    return {p.name: p.read_text(encoding='utf-8').replace('\r\n', '\n') for p in sorted(TOOLS.glob('*.py'))}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / source.ARCHIVE).iterdir() if p.is_file()}


# Which owner retires each page when Root's session epoch (or the account MID) changes. Each row is
# (page, [(file, exact source token, whitespace-insensitive)]). A token that disappears means the owner no
# longer reads the epoch there: re-audit that page before touching this table.
OWNERS = (
    ('favourites', (
        (UI + 'DesktopFavoritesRootEntry.kt',
         'fun isOwned(): Boolean = alive.get() && job.isActive && currentEpoch() == epoch && rootAlive()'),
        (UI + 'DesktopFavoritesRootEntry.kt',
         'identity = { com.android.purebilibili.core.store.AccountSessionIdentity( readMid()?.takeIf { it > 0L }, epoch) }'),
        (UI + 'DesktopOriginalFavoritesHost.kt', 'if (!environment.isOwned()) return'),
        (UI + 'DesktopOriginalFavoritesHost.kt', 'remember(environment,detail) {'),
        (WIN + 'DesktopShell.kt',
         'var favoritesEntry by remember(repository, sessionEpoch) { mutableStateOf<DesktopFavoritesRootEntry?>(null) }'),
        (WIN + 'DesktopShell.kt', 'val entry = DesktopFavoritesRootEntry(capturedEpoch, { repository.sessionEpoch }, scope,'),
    )),
    ('history', (
        (UI + 'DesktopPersonalListsRoot.kt', 'fun owns(): Boolean = !closed.get() && job.isActive && gate.owns()'),
        (UI + 'DesktopPersonalListsRoot.kt',
         'repository.ownedHomeService(type, "https://api.bilibili.com/", gate.epoch, entry::owns)'),
        (UI + 'DesktopPersonalListsRoot.kt', 'repository.captureHomeNavRequest(gate.epoch, gate.mid)'),
        (UI + 'DesktopPersonalListsRoot.kt', 'entry.install(env, HistoryViewModel(env), FavoriteCategoryViewModel(env))'),
        (UI + 'DesktopPersonalListsRoot.kt', 'fun owns(): Boolean = !closed.get() && job.isActive && root.owns()'),
    )),
    ('watch-later', (
        (UI + 'DesktopPersonalListsRoot.kt', 'repository.homeWbiKeys(gate.epoch,entry::owns,favorites.api,force)'),
        (UI + 'DesktopPersonalListsRoot.kt', 'com.android.purebilibili.feature.watchlater.WatchLaterViewModel(env)'),
        (UI + 'DesktopOriginalWatchLaterHost.kt', 'fun owns() = !closed.get() && job.isActive && root.owns()'),
    )),
    ('home', (
        (UI + 'DesktopHomeRetainedEntry.kt',
         'fun owns(): Boolean = !closed && job.isActive && rootAlive() && currentEpoch() == epoch &&'),
        (UI + 'DesktopHomeRetainedEntry.kt', 'throw CancellationException("Home epoch changed before construction")'),
        (UI + 'DesktopHomeRetainedEntry.kt', 'val vm = DesktopOriginalHomeViewModel(environment)'),
        (UI + 'DesktopHomeRootRequestBinding.kt', 'repository.sessionEpoch==capturedEpoch'),
        (UI + 'DesktopHomeRootRetainer.kt', 'it.capturedEpoch == currentEpoch()'),
        (UI + 'DesktopReadyOriginalRootMount.kt',
         'LaunchedEffect(handle, epoch, account?.mid, factoryBinding, retry, loginInstalled) {'),
        (UI + 'DesktopReadyOriginalRootMount.kt',
         'val installedRoot = handle.retainer.install(epoch, account?.mid, factory.factory)'),
        (UI + 'DesktopReadyOriginalRootMount.kt', 'physicalStack.clear(); physicalStack.addAll(nextInitialStack)'),
    )),
    ('bangumi-hub', (
        (UI + 'DesktopOriginalBangumiPagesRoot.kt', 'androidx.compose.runtime.key(entryKey, aggregate, gate, appResources) {'),
        (UI + 'DesktopOriginalBangumiPagesRoot.kt', 'repository.sessionEpoch == gate.epoch'),
        (UI + 'DesktopOriginalBangumiPagesRoot.kt', 'val hub = BangumiHubViewModel(hubEnvironment)'),
        (UI + 'DesktopOriginalHomeEmbeddedAggregate.kt', 'val bangumi = BangumiHubViewModel(bangumiEnvironment)'),
        (UI + 'DesktopOriginalHomeEmbeddedAggregate.kt', '}, lifetime::owns, lifetime::commit)'),
    )),
    ('listen-collection', (
        (UI + 'DesktopOriginalListenVideoCollectionEnvironment.kt',
         'fun owns(): Boolean = job.isActive && root.owns() && listen.owns() && destinationRetained()'),
        (UI + 'DesktopOriginalListenVideoEntry.kt', 'fun owns() = !closed.get() && job.isActive && root.owns()'),
        (UI + 'DesktopOriginalListenVideoEntry.kt', 'viewModel = ListenVideoViewModel(environment)'),
        (UI + 'DesktopPersonalListsRoot.kt',
         'created.install(environment(created.scope, created::owns, created::assertOwned, created::commit))'),
        (WIN + 'audio/ListenAudioSession.kt', 'private val sessionEpoch = repository.sessionEpoch'),
        (WIN + 'audio/ListenAudioSession.kt', 'repository.sessionEpochFlow.first { it != sessionEpoch }'),
    )),
    ('profile', (
        (UI + 'DesktopReadyHomeFactoryBinding.kt', 'repository.ownedHomeService(type, base, gate.epoch, gate::owns)'),
        (UI + 'DesktopReadyHomeFactoryBinding.kt',
         'return createDesktopOriginalWindowsProfileBinding(context, runtime.store.root, gate.scope, gate::owns, gate::commit,'),
        (UI + 'DesktopProfileBindings.kt',
         'fun ensureOwned() { if (scope.coroutineContext[Job]?.isActive != true || !owns()) '
         'throw CancellationException("Profile entry retired") }'),
        (UI + 'DesktopProfileBindings.kt', 'val viewModel = ProfileViewModel(environment)'),
    )),
    ('root-epoch-authority', (
        (WIN + 'data/DesktopRepository.kt', 'internal val sessionEpoch: Long get() = sessions.generation'),
        (WIN + 'data/DesktopRepository.kt', 'internal val sessionEpochFlow: StateFlow<Long> = sessions.generationState'),
        (UI + 'DesktopHomeRetainedEntry.kt', '{ repository.account.value?.mid }, rootAlive, parentScope)'),
    )),
    ('dynamic-pagination', (
        (UI + 'CommunityDynamicScreens.kt',
         'val timelines=remember(memory,mountedRoot,mid,capturedEpoch){mutableMapOf<String,DesktopDynamicTimelineState>()}'),
        (UI + 'DesktopDynamicTimeline.kt', 'private val original=DesktopOriginalDynamicTimelineRepository(fetchPage,stillOwned)'),
        (UI + 'DesktopHomeRetainedEntry.kt', 'val originalFollow = DesktopOriginalDynamicTimelineRepository('),
    )),
)

# Every place Windows constructs one of the audited view models. Upstream resets these in place with
# AccountScopedRequests; Windows destroys the owner that holds them, so each site must sit under an OWNERS row.
VIEW_MODEL_SITES = {
    'FavoriteViewModel': {'DesktopFavoritesRootEntry.kt', 'DesktopOriginalFavoritesHost.kt'},
    'FavoriteCategoryViewModel': {'DesktopFavoritesRootEntry.kt', 'DesktopPersonalListsRoot.kt'},
    'SeasonSeriesDetailViewModel': {'DesktopFavoritesRootEntry.kt', 'DesktopOriginalFavoritesHost.kt'},
    'HistoryViewModel': {'DesktopPersonalListsRoot.kt'},
    'LikedVideosViewModel': {'DesktopPersonalListsRoot.kt'},
    'WatchLaterViewModel': {'DesktopPersonalListsRoot.kt'},
    'DesktopOriginalHomeViewModel': {'DesktopHomeRetainedEntry.kt'},
    'BangumiHubViewModel': {'DesktopOriginalBangumiPagesRoot.kt', 'DesktopOriginalHomeEmbeddedAggregate.kt'},
    'ListenVideoViewModel': {'DesktopOriginalListenVideoEntry.kt'},
    'ProfileViewModel': {'DesktopProfileBindings.kt'},
}


class V046AccountScopedRequestsSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-acct-req-')
        cls.root = Path(cls.temporary.name)
        cls.result = producer.generate(REPO, cls.root / 'main', test_output=cls.root / 'tests')
        with mock.patch.object(source, 'emit', lambda repo, output, test_output=None: {'files': []}):
            producer.generate(REPO, cls.root / 'without-main', test_output=cls.root / 'without-tests')
        cls.main = tree(cls.root / 'main')
        cls.tests = tree(cls.root / 'tests')
        cls.without_main = tree(cls.root / 'without-main')
        cls.without_tests = tree(cls.root / 'without-tests')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def balanced(self, text):
        stack = []
        for token, first, _end in sync.kotlin_tokens(text):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', ']', '}'):
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)

    def temporary_repo(self):
        temporary = tempfile.TemporaryDirectory(prefix='bp-acct-req-in-')
        self.addCleanup(temporary.cleanup)
        repo = Path(temporary.name)
        shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
        return repo

    # ------------------------------------------------------------------ archive and generator

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        self.assertEqual(sorted(source.EXPECTED | {'LICENSE', 'manifest.json'}), sorted(self.archive))
        self.assertEqual('a66da43dc59750d25af177a495e18f4959b961f5cd6ec1e8523bf3766b3d2460', source.MANIFEST_SHA256)
        self.assertEqual(source.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        for leaf in sorted(self.archive):
            with self.subTest(leaf=leaf):
                repo = self.temporary_repo()
                target = repo / source.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    source.fixed(repo)
        repo = self.temporary_repo()
        (repo / source.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
        with self.assertRaisesRegex(ValueError, 'leaves changed'):
            source.fixed(repo)
        repo = self.temporary_repo()
        (repo / source.ARCHIVE / 'LICENSE').unlink()
        with self.assertRaisesRegex(ValueError, 'leaves changed'):
            source.fixed(repo)
        self.assertEqual(self.archive, archive_files())

    def test_leaves_are_the_exact_upstream_blobs_at_the_pinned_commit(self):
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual(['v0.4.6', source.COMMIT], [manifest['tag'], manifest['commit']])
        for row in manifest['files']:
            with self.subTest(path=row['path']):
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', source.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, self.archive[row['filename']])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
        licence = subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', source.COMMIT + ':LICENSE'],
                                 cwd=str(REPO), capture_output=True, check=True).stdout
        self.assertEqual(licence, self.archive['LICENSE'])

    def test_adaptation_is_exactly_the_two_token_manager_hunks_and_is_invertible(self):
        adapted, proof = source.adapt(REPO)
        original = self.archive[source.MAIN].decode('utf-8')
        self.assertEqual(2, len(source.HUNKS))
        self.assertEqual(original, source.restore(adapted))
        self.assertEqual(proof['originalSHA256'], sha(original.encode('utf-8')))
        self.assertEqual(proof['adaptedSHA256'], sha(adapted.encode('utf-8')))
        self.assertTrue(proof['wholeOriginal'] and proof['completeOriginalInverse'])
        self.assertFalse(proof['jvmTestsExecuted'] or proof['applicationExecuted'])
        self.assertIn('import com.android.purebilibili.core.store.TokenManager\n', original)
        self.assertIn('= TokenManager.accountIdentity,\n', original)
        for text in ('TokenManager', 'accountIdentity'):
            self.assertNotIn(text, adapted)
        self.assertIn('    private val identities: StateFlow<AccountSessionIdentity>,\n', adapted)
        # Everything but the two hunks is byte for byte the upstream file: peel them from the original and compare.
        stripped = original
        for before, after in source.HUNKS:
            stripped = stripped.replace(before, after, 1)
        self.assertEqual(adapted, stripped)
        # One import line is dropped; the parameter line is rewritten in place.
        self.assertEqual(len(original.splitlines()) - 1, len(adapted.splitlines()))

    def test_adaptation_fails_closed_on_anchor_drift(self):
        with mock.patch.object(source, 'HUNKS', (('this anchor is not in the original\n', 'x\n'),)):
            with self.assertRaisesRegex(ValueError, 'not unique'):
                source.adapt(REPO)
        # A drifted hunk list that leaves the Android default behind is refused as well.
        with mock.patch.object(source, 'HUNKS', (source.HUNKS[0],)):
            with self.assertRaisesRegex(ValueError, 'TokenManager reference remains'):
                source.adapt(REPO)
        # An adapted body whose anchor occurs twice cannot be peeled back unambiguously.
        adapted, _proof = source.adapt(REPO)
        with self.assertRaisesRegex(ValueError, 'not unique'):
            source.restore(adapted + IMPORT_LINE)
        with self.assertRaisesRegex(ValueError, 'not unique'):
            source.restore(adapted.replace(source.PARAMETER_AFTER, '', 1))

    def test_original_test_is_the_verbatim_three_case_upstream_class(self):
        raw = source.original_test(REPO)
        self.assertEqual(self.archive[source.TEST], raw)
        self.assertEqual(3, raw.count(b'    @Test\n'))
        for name in ('switch clears old data after pending request rollback finishes',
                     'queued old request cannot start after identity changes',
                     'rapid switching skips superseded reset and never revives earlier account requests'):
            self.assertIn(('fun `' + name + '`').encode('utf-8'), raw)
        # It passes Root-style identities explicitly, so the required constructor parameter does not touch it.
        self.assertEqual(3, raw.count(b'AccountScopedRequests(backgroundScope, identities)'))
        self.assertIn(b'import kotlin.test.Test\n', raw)
        self.assertNotIn(b'org.junit', raw)
        repo = self.temporary_repo()
        target = repo / source.ARCHIVE / source.TEST
        target.write_bytes(target.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            source.original_test(repo)

    def test_the_real_producer_emits_owner_proof_and_test_and_changes_nothing_else(self):
        adapted, proof = source.adapt(REPO)
        main_path = source.RELATIVE[source.MAIN]
        self.assertEqual(adapted.encode('utf-8'), self.main[main_path])
        self.assertEqual(proof, json.loads(self.main[source.PROOF]))
        self.assertEqual((json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode('utf-8'), self.main[source.PROOF])
        self.assertEqual(self.archive[source.TEST], self.tests[source.RELATIVE[source.TEST]])
        # Patching the layer out (the producer without this slice) leaves exactly these three outputs missing.
        self.assertEqual({main_path, source.PROOF}, set(self.main) - set(self.without_main))
        self.assertEqual({source.RELATIVE[source.TEST]}, set(self.tests) - set(self.without_tests))
        self.assertEqual(set(self.without_main), set(self.main) - {main_path, source.PROOF})
        for path in self.without_main:
            self.assertEqual(self.without_main[path], self.main[path], path)
        for path in self.without_tests:
            self.assertEqual(self.without_tests[path], self.tests[path], path)
        for raw in list(self.main.values()) + list(self.tests.values()):
            self.assertNotIn(b'\r', raw)

    def test_the_main_output_is_a_source_root_that_holds_no_stray_test_class(self):
        for path in self.main:
            self.assertFalse(path.endswith('Test.kt'), path)
        self.assertIn('com/android/purebilibili/feature/home/DesktopOriginalHomeViewModel.kt', self.main)
        self.assertEqual({'com/android/purebilibili/core/coroutines/AccountScopedRequestsTest.kt',
                          'com/android/purebilibili/feature/home/HomeLoadSequencePolicyTest.kt'}, set(self.tests))

    def test_emission_is_deterministic_and_works_without_a_test_root(self):
        second = self.root / 'second'
        producer.generate(REPO, second / 'main', test_output=second / 'tests')
        self.assertEqual(self.main, tree(second / 'main'))
        self.assertEqual(self.tests, tree(second / 'tests'))
        solo = self.root / 'solo'
        receipt = source.emit(REPO, solo / 'main')
        self.assertEqual([source.RELATIVE[source.MAIN], source.PROOF], [row['path'] for row in receipt['files']])
        self.assertEqual([], [p for p in solo.rglob('*Test.kt')])
        again = source.emit(REPO, solo / 'main', solo / 'tests')
        self.assertEqual(3, len(again['files']))
        self.assertEqual(self.archive[source.TEST], (solo / 'tests' / source.RELATIVE[source.TEST]).read_bytes())

    def test_producer_refuses_output_inside_the_archive_before_writing(self):
        repo = self.temporary_repo()
        before = tree(repo / source.ARCHIVE)
        for output, tests in ((repo / source.ARCHIVE / 'generated', None),
                              (repo / 'elsewhere', repo / source.ARCHIVE / 'tests'),
                              (repo / 'desktop', None)):
            with self.subTest(output=output.name):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    producer.generate(repo, output, test_output=tests)
        self.assertFalse((repo / source.ARCHIVE / 'generated').exists())
        self.assertFalse((repo / source.ARCHIVE / 'tests').exists())
        self.assertEqual(before, tree(repo / source.ARCHIVE))

    def test_emit_refuses_to_write_through_anything_but_a_regular_file(self):
        out = self.root / 'irregular'
        (out / source.RELATIVE[source.MAIN]).mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'not a regular file'):
            source.emit(REPO, out)
        self.assertFalse((out / source.PROOF).exists())
        # A pre-existing regular file is overwritten (re-runs), a symlink is never followed.
        again = self.root / 'rerun'
        source.emit(REPO, again)
        (again / source.PROOF).write_bytes(b'stale')
        source.emit(REPO, again)
        self.assertEqual(json.loads((again / source.PROOF).read_bytes())['wholeOriginal'], True)
        linked = self.root / 'linked'
        target = linked / source.RELATIVE[source.MAIN]
        target.parent.mkdir(parents=True)
        victim = self.root / 'victim.kt'
        victim.write_bytes(b'untouched')
        try:
            os.symlink(victim, target)
        except (OSError, NotImplementedError):
            return
        with self.assertRaisesRegex(ValueError, 'not a regular file'):
            source.emit(REPO, linked)
        self.assertEqual(b'untouched', victim.read_bytes())

    def test_producer_hook_validates_first_and_emits_last(self):
        text = (TOOLS / PRODUCER).read_text(encoding='utf-8')
        start = text.index('def generate(')
        validate_import = 'from v046_account_scoped_requests import validate_outputs as validate_account_scoped_outputs'
        validate_call = 'validate_account_scoped_outputs(repo,output,test_output)'
        emit_import = 'from v046_account_scoped_requests import emit as emit_account_scoped_requests'
        emit_call = 'emit_account_scoped_requests(repo,output,test_output)'
        for needle in (validate_import, validate_call, emit_import, emit_call):
            self.assertEqual(1, text.count(needle), needle)
        pins = text.index('for path,expected in SOURCE_PINS.items()', start)
        standalone = text.index(' if standalone:', start)
        returned = text.index(" return {'pinnedCommit'", start)
        self.assertLess(start, text.index(validate_import, start))
        self.assertLess(text.index(validate_call, start), pins)
        self.assertLess(standalone, text.index(emit_import, start))
        self.assertLess(text.index(emit_import, start), text.index(emit_call, start))
        self.assertLess(text.index(emit_call, start), returned)
        self.assertFalse(any(line.startswith(('import v046_account_scoped_requests', 'from v046_account_scoped_requests'))
                             for line in text.splitlines()))

    # ------------------------------------------------------------------ registration

    def test_gradle_declares_both_inputs_on_the_producer_task_and_no_canonical_copy_exists(self):
        block = task_block(self.build, TASK)
        self.assertIn('"tools/' + PRODUCER + '"', block)
        self.assertEqual(1, block.count('inputs.file("tools/v046_account_scoped_requests.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-account-scoped-requests")'))
        self.assertIn('"--test-output", layout.buildDirectory.dir("generated/home-load-tests")', block)
        self.assertEqual(1, self.build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/home-viewmodel"))'))
        self.assertEqual(1, self.build.count(
            'kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-load-tests")) }'))
        self.assertEqual(1, self.build.count('upstream-slices/v046-account-scoped-requests"'))
        # A new file: nothing to exclude, because no vendored v0.2.5 copy of it exists.
        self.assertNotIn('AccountScopedRequests', self.build.replace('v046-account-scoped-requests', ''))
        canonical = (TOOLS / 'v025-canonical-sources.json').read_text(encoding='utf-8')
        sources = (REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8')
        self.assertNotIn('AccountScopedRequests', canonical)
        self.assertNotIn('AccountScopedRequests', sources)

    def test_catalog_inventories_both_leaves_and_every_archive_file(self):
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(source.ARCHIVE + '/')]
        self.assertEqual(sorted(source.EXPECTED), sorted(Path(row['localPath']).name for row in rows))
        manifest = json.loads(self.archive['manifest.json'])
        by_name = {row['filename']: row for row in manifest['files']}
        for row in rows:
            name = Path(row['localPath']).name
            with self.subTest(name=name):
                self.assertEqual(by_name[name]['path'], row['path'])
                self.assertEqual((source.COMMIT, by_name[name]['gitBlob'], by_name[name]['sha256'], 'raw', 'adaptedSource'),
                                 (row['upstreamCommit'], row['blob'], row['sha256'], row['hashNormalization'], row['kind']))
                self.assertEqual(['v046-account-scoped-requests'], row['features'])
                self.assertFalse(row['referenceOnly'])
                self.assertEqual(sha(self.archive[name]), row['sha256'])
                self.assertTrue(row['usedScope'].isascii())
        self.assertEqual(1, self.catalog['buildInputRoots'].count(source.ARCHIVE))
        archives = [row for row in self.catalog['archives'] if row['directory'] == source.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual(sorted(archives[0]['files'], key=lambda r: r['path']),
                         sorted([{'path': source.ARCHIVE + '/' + name, 'sha256': sha(raw), 'hashNormalization': 'raw',
                                  'blob': hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()}
                                 for name, raw in self.archive.items()], key=lambda r: r['path']))
        catalog_bytes = (TOOLS / 'compatibility-source-inputs.json').read_bytes().replace(b'\r\n', b'\n')
        pinned = re.search(r'(?m)^COMPATIBILITY_CATALOG_SHA256 = "([0-9a-f]{64})"$',
                           (TOOLS / 'sync-upstream.py').read_text(encoding='utf-8')).group(1)
        self.assertEqual(pinned, sha(catalog_bytes))

    def test_symbol_gate_retires_the_seed_instead_of_expecting_the_gap(self):
        expected = json.loads((TOOLS / 'symbol-closure-expected-gaps.json').read_text(encoding='utf-8'))
        full = source.PACKAGE + '.AccountScopedRequests'
        for group in expected['groups']:
            self.assertNotEqual('acct-account-scoped-requests', group['closedBy'])
            for gap in group['gaps']:
                self.assertNotIn('AccountScopedRequests', gap.get('symbol', ''), full)
        seeds = [seed for seed in expected['retiredSeeds'] if seed['symbol'] == 'AccountScopedRequests']
        self.assertEqual(1, len(seeds))
        seed = seeds[0]
        self.assertEqual(('declaration', source.PACKAGE, 'acct-account-scoped-requests'),
                         (seed['kind'], seed['package'], seed['closedBy']))
        self.assertIn(source.ARCHIVE, seed['retiredBy'])
        self.assertIn('v046_account_scoped_requests.py', seed['retiredBy'])
        self.assertIn('test_v046_account_scoped_requests_source.py', seed['guardedBy'])

    # ------------------------------------------------------------------ definer, imports and Kotlin shape

    def test_the_owner_is_declared_once_and_every_name_it_uses_resolves(self):
        adapted = self.main[source.RELATIVE[source.MAIN]].decode('utf-8')
        info = gate.parse_kotlin(adapted, source.MAIN, 'generated', 'main')
        self.assertEqual(source.PACKAGE, info.package)
        self.assertEqual([('internal', 'class', 'AccountScopedRequests')],
                         [(d.visibility, d.kind, d.name) for d in info.decls])
        imported = {i.path for i in info.imports}
        self.assertEqual({
            'com.android.purebilibili.core.store.AccountSessionIdentity',
            'kotlin.coroutines.CoroutineContext', 'kotlin.coroutines.EmptyCoroutineContext',
            'kotlinx.coroutines.CoroutineScope', 'kotlinx.coroutines.Job', 'kotlinx.coroutines.SupervisorJob',
            'kotlinx.coroutines.cancel', 'kotlinx.coroutines.flow.StateFlow', 'kotlinx.coroutines.launch',
            'kotlinx.coroutines.yield'}, imported)
        self.assertFalse(any(i.star for i in info.imports))
        # No other source in the Windows tree or the archives declares it.
        declaring = []
        for base in ('desktop/src', 'desktop/upstream-slices'):
            for path in sorted((REPO / base).rglob('*.kt')):
                if DECLARES_OWNER.search(path.read_bytes()):
                    declaring.append(path.relative_to(REPO).as_posix())
        self.assertEqual([source.ARCHIVE + '/' + source.MAIN], declaring)
        # The one type it needs is emitted, in the same package as its import, by the favorites subscription layer.
        identity = [(relative, body) for relative, body, _row in subscription.additional_sources(REPO)
                    if relative == 'core/store/DesktopFavoriteAccountIdentity']
        self.assertEqual(1, len(identity))
        self.assertIn('package com.android.purebilibili.core.store\n', identity[0][1])
        self.assertIn('data class AccountSessionIdentity(val mid: Long? = null, val generation: Long = 0L)', identity[0][1])
        for path in sorted((REPO / 'desktop/src').rglob('*.kt')):
            self.assertNotIn(b'class AccountSessionIdentity', path.read_bytes(), path.name)

    def test_emitted_and_hand_written_kotlin_keep_balanced_delimiters(self):
        for text in (self.main[source.RELATIVE[source.MAIN]].decode('utf-8'),
                     self.tests[source.RELATIVE[source.TEST]].decode('utf-8'), read(HAND_WRITTEN)):
            self.balanced(text)

    def test_the_hand_written_jvm_class_needs_no_window_gpu_or_network(self):
        text = read(HAND_WRITTEN)
        self.assertTrue(text.startswith('package com.android.purebilibili.core.coroutines\n'))
        self.assertEqual(3, text.count('    @Test\n'))
        self.assertEqual(1, text.count('class DesktopAccountScopedRequestsRootIdentityTest {'))
        for forbidden in ('java.awt', 'javax.swing', 'okhttp3', 'java.net', 'androidx.compose', 'DesktopRepository',
                          'DesktopShell', 'org.junit', 'Dispatchers.Main', 'runBlocking'):
            self.assertNotIn(forbidden, text)
        imports = re.findall(r'(?m)^import (\S+)$', text)
        self.assertEqual(len(imports), len(set(imports)))
        for name in ('kotlinx.coroutines.test.runTest', 'kotlinx.coroutines.test.runCurrent', 'kotlin.test.Test'):
            self.assertIn(name, imports)
        # The identity formula it exercises is the one Root's favorites entry builds.
        formula = '?.takeIf { it > 0L }, epoch)'
        self.assertIn(formula, flat(read(UI + 'DesktopFavoritesRootEntry.kt')))
        self.assertIn('AccountSessionIdentity(mid?.takeIf { it > 0L }, epoch)', text)
        fqcn = 'class DesktopAccountScopedRequestsRootIdentityTest'
        hits = [p for p in (REPO / 'desktop/src/test').rglob('*.kt') if fqcn.encode() in p.read_bytes()]
        self.assertEqual([REPO / HAND_WRITTEN], hits)

    # ------------------------------------------------------------------ the audit

    def test_every_audited_view_model_has_a_known_construction_site(self):
        pattern = re.compile(r'\b(' + '|'.join(sorted(VIEW_MODEL_SITES)) + r')\(')
        found = {}
        for path in sorted((REPO / 'desktop/src/main').rglob('*.kt')):
            for match in pattern.finditer(path.read_text(encoding='utf-8')):
                found.setdefault(match.group(1), set()).add(path.name)
        self.assertEqual(VIEW_MODEL_SITES, found,
                         'A new construction site of an audited view model needs a row in OWNERS: say which Root '
                         'epoch owner retires it, or adopt AccountScopedRequests there on purpose.')

    def test_each_page_owner_is_retired_by_the_root_session_epoch(self):
        pages = [page for page, _tokens in OWNERS]
        for required in ('favourites', 'history', 'watch-later', 'home', 'bangumi-hub', 'listen-collection', 'profile'):
            self.assertIn(required, pages)
        for page, tokens in OWNERS:
            for path, token in tokens:
                with self.subTest(page=page, path=Path(path).name, token=token[:60]):
                    self.assertEqual(1, flat(read(path)).count(flat(token)))

    def test_the_epoch_effect_rebuilds_the_whole_original_route_stack(self):
        text = flat(read(UI + 'DesktopReadyOriginalRootMount.kt'))
        effect = text.index('LaunchedEffect(handle, epoch, account?.mid, factoryBinding, retry, loginInstalled) {')
        stack = text.index('physicalStack.clear(); physicalStack.addAll(nextInitialStack)', effect)
        install = text.index('handle.retainer.install(epoch, account?.mid, factory.factory)', effect)
        closes = [text.index(token, effect) for token in (
            'handle.route.getAndSet(null)?.close()', 'handle.messagePages.getAndSet(null)?.closeAndJoin()',
            'handle.spacePages.getAndSet(null)?.closeAndJoin()')]
        self.assertTrue(all(close < stack < install for close in closes))
        self.assertLess(install, text.index('catch (cancelled: CancellationException)', effect))
        # The retainer refuses to hand out a root of an older epoch.
        retainer = flat(read(UI + 'DesktopHomeRootRetainer.kt'))
        self.assertIn('if (!isActive() || epoch != currentEpoch()) throw CancellationException("Home Root retired before install")',
                      retainer)

    def test_no_windows_code_constructs_the_owner_or_reads_an_android_identity_source(self):
        for path in sorted((REPO / 'desktop/src/main').rglob('*.kt')):
            text = path.read_text(encoding='utf-8')
            for needle in ('AccountScopedRequests', 'accountRequests', 'TokenManager.accountIdentity',
                           'object TokenManager', 'val accountIdentity'):
                self.assertNotIn(needle, text, path.name)
        generators = tool_sources()
        mentions = lambda needle: sorted(name for name, text in generators.items() if needle in text)
        self.assertEqual(['v044_favorite_detail.py', 'v044_favorite_subscription.py'], mentions('accountRequests'))
        self.assertEqual(['extract-upstream-video-detail-full-units.py', 'v044_favorite_search.py',
                          'v044_favorite_subscription.py', 'v046_account_scoped_requests.py',
                          'v046_favorite_editor.py'], mentions('accountIdentity'))
        self.assertEqual(['v046_account_scoped_requests.py'], mentions('AccountScopedRequests'))

    def test_carried_consumers_are_rebound_to_root_identity_or_not_compiled(self):
        generators = tool_sources()
        detail = generators
        self.assertIn("replace('accountRequests.launch', 'viewModelScope.launch', 4)", detail['v044_favorite_detail.py'])
        self.assertIn('The existing Root replaces the whole entry on credential/epoch changes.',
                      detail['v044_favorite_detail.py'])
        bind = generators['v044_favorite_subscription.py']
        for token in ("body.replace('accountRequests.launch', 'viewModelScope.launch')",
                      "body.replace('accountRequests.identity', 'environment.favoriteAccountIdentity()')",
                      "body.replace('accountRequests.isCurrent(', 'environment.isFavoriteAccountCurrent(')"):
            self.assertIn(token, bind)
        self.assertIn("'private fun interactionSession(): Any = environment.interactionSession()'",
                      generators['extract-upstream-video-detail-full-units.py'])
        reference = {row['localPath']: row['referenceOnly'] for row in self.catalog['inputs']}
        self.assertTrue(reference['desktop/upstream-slices/v043-home-native-cards/HomeViewModel.kt'])
        self.assertTrue(reference['desktop/upstream-slices/v044-favorite-detail/ListViewModel.kt'])
        # The only compiled Home view model is the pinned one with Windows edits: no account scoped owner in it.
        vm = self.main['com/android/purebilibili/feature/home/DesktopOriginalHomeViewModel.kt'].decode('utf-8')
        for needle in ('AccountScopedRequests', 'accountRequests', 'accountIdentity'):
            self.assertNotIn(needle, vm)
        self.assertIn('Home entry retired', vm)

    def test_dynamic_pagination_is_per_owner_and_detail_seeds_have_no_reader(self):
        generators = tool_sources()
        timeline = generators['extract-upstream-dynamic-settings.py']
        header = timeline.index('internal class DesktopOriginalDynamicTimelineRepository(')
        registry = timeline.index('private val feedPagination=DynamicFeedPaginationRegistry()', header)
        self.assertLess(header, registry)
        self.assertNotIn('object DesktopOriginalDynamicTimelineRepository', timeline)
        for path in sorted((REPO / 'desktop/src/main').rglob('*.kt')):
            text = path.read_text(encoding='utf-8')
            self.assertNotIn('peekDynamicDetailSeed', text, path.name)
            self.assertNotIn('DynamicFeedPaginationRegistry()', text, path.name)
        # The process-global seed cache is write-only on Windows; a future reader must make it account scoped.
        readers = sorted(name for name, text in generators.items() if 'peekDynamicDetailSeed' in text)
        self.assertEqual(['extract-upstream-dynamic-card.py', 'extract-upstream-dynamic-detail-protocol.py'], readers)
        self.assertIn("main.replace('val seed = peekDynamicDetailSeed(cleanedId)', 'val seed = seedItem')",
                      generators['extract-upstream-dynamic-detail-protocol.py'])


if __name__ == '__main__':
    unittest.main()
