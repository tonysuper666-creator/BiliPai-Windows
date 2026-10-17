from collections import Counter
from pathlib import Path
from unittest import mock
import ast
import difflib
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
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v041_video_status_labels as source
import v043_search_up_author as search_up_followup
import v043_home_native_cards as home_native_followup
from v025_source_paths import canonical_source

MODEL_DIR = 'com/android/purebilibili/data/model/response/'
BADGES_PATH = 'com/android/purebilibili/core/ui/components/VideoStatusBadges.kt'
LABELS_PATH = MODEL_DIR + 'VideoStatusLabels.kt'
SEARCH_PATH = MODEL_DIR + 'SearchModels.kt'
FAVORITE_PATH = MODEL_DIR + 'FavoriteModels.kt'
SUPPORT_PATH = 'com/android/purebilibili/data/repository/UserContentVideoItem.kt'
PRODUCER = 'tools/extract-v041-video-status-labels.py'

# Reviewed result of the actual producer at the accepted baseline. A hook, hunk or archive change must update these
# deliberately; they are not derived from the module under test.
GOLDEN_MAIN = {
    BADGES_PATH: '83cf025c274e340e8877986784ffb1d9bbb88a168ef6b81c830344a601cf854b',
    LABELS_PATH: 'e5cba87c8ee8440d02b87c777ca545ab5d43408ce3f1d2f0d309b5cb13ef60d0',
    MODEL_DIR + 'ListModels.kt': '5cb726df0e15c1ffcd79f3722cf9ffae12ffe30a8f0fb784ad223419bb578d1a',
    MODEL_DIR + 'RecommendationReason.kt': '62af39b81966634667b2af5ca926dd1e1cbf9942425f4a87a8b869cc00b4b7b5',
    MODEL_DIR + 'SpaceModels.kt': '3fdd9795a060157922f5bbfa28c705d193fd401247e5d975b0f60eefdedcaf4b',
    SEARCH_PATH: '941c24c45ab415de3ae291209e1b945f7c714053386ebd838c6b02e181d8fb36',
    FAVORITE_PATH: '3b5a648f0b9625019f173afbd4981d81313919c64dbea8140602a8437d595534',
    'v041-video-status-labels-proof.json': 'ecafa00c675583d048a7a5a5b147cab74263e2ae0feb7a5676eb55341ac64962',
}
GOLDEN_TESTS = {
    MODEL_DIR + 'RecommendationNativeCardTest.kt': 'f80b74b728572ab721f18080d562fd2af64d4c69e36f7647b1b89b92a0e930c5',
    MODEL_DIR + 'RecommendationReasonTest.kt': 'dc417702213875a054ba74297903b763e0a27b3dad9823cc7a59bca5bc4c7427',
    'com/android/purebilibili/navigation/HomeVideoNavigationPolicyTest.kt': '545f1a3fa95b577e4de61e79509b8178ba15047d482d349c3b87e3259be005bd',
    MODEL_DIR + 'SearchUpItemTest.kt': '1b5ba1f311cfe57f899cf2c7e6efa69c17d9c3d7b419cf8317c67f0c48c378a4',
    MODEL_DIR + 'VideoStatusLabelsTest.kt': '5802d3ae505a73ab3eb79f31fcbd4ce961a8778f1ff0a39923e9ebad02765b92',
    MODEL_DIR + 'VideoCooperationMetadataTest.kt': '7ad0dffa6f6ab350b14bd18eb294d3ec8d15cd55e4d3a99bdcc31cf3b6e2337f',
    SUPPORT_PATH: '053873ee6ce6df0dd201c4c911724f61fc068f8987db7a80690eb6f49192840b',
}
HOOK_IDS = ['SM1', 'SM2', 'FM1', 'HC1', 'HC2', 'HC3', 'HC4', 'SS0', 'SS1', 'SS2', 'SS3', 'SS4', 'SS5', 'SS6', 'SS7',
            'SO1', 'WL1', 'WL2', 'WL3', 'WL4', 'WL5']
REPLACED_MODELS = [source.LIST_MODELS, source.SPACE_MODELS, source.SEARCH_MODELS, source.FAVORITE_MODELS]
HOOKED_PATHS = [source.SEARCH_MODELS, source.FAVORITE_MODELS, source.VIDEO_CARD, source.SINGLE_COLUMN,
                source.SPACE_SCREEN, source.SPACE_LOAD_POLICY, source.WATCH_LATER_REPOSITORY, source.WATCH_LATER_SCREEN]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def run_producer(output, tests_output, cwd, script, seed):
    environment = dict(os.environ, PYTHONHASHSEED=str(seed), PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1')
    environment.pop('PYTHONPATH', None)
    done = subprocess.run([sys.executable, str(script), '--repo', str(REPO), '--output', str(output),
                           '--tests-output', str(tests_output)], cwd=str(cwd), env=environment,
                          capture_output=True, check=True)
    return json.loads(done.stdout.decode('utf-8').strip().splitlines()[-1])


def copy_archive(repo):
    shutil.copytree(REPO / source.ARCHIVE, Path(repo) / source.ARCHIVE)


def changed_lines(before, after):
    matcher = difflib.SequenceMatcher(None, before.split('\n'), after.split('\n'), autojunk=False)
    return [op for op in matcher.get_opcodes() if op[0] != 'equal']


class V041VideoStatusLabelsSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-status-labels-')
        cls.root = Path(cls.temporary.name)
        cls.summary = run_producer(cls.root / 'main', cls.root / 'tests', REPO / 'desktop',
                                   REPO / 'desktop' / PRODUCER, 0)
        cls.main = tree(cls.root / 'main')
        cls.tests = tree(cls.root / 'tests')
        cls.latest = source.verified_sources(REPO)
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def emitted(self, path):
        return self.main[path].decode('utf-8')

    # ------------------------------------------------------------------ real producer
    def test_actual_producer_cli_emits_only_the_pinned_files_and_is_deterministic(self):
        self.assertEqual(dict(commit=source.COMMIT, hooks=21, mainSources=7, testSources=7,
                              proofSha256=GOLDEN_MAIN['v041-video-status-labels-proof.json']), self.summary)
        self.assertEqual(set(GOLDEN_MAIN), set(self.main))
        self.assertEqual(set(GOLDEN_TESTS), set(self.tests))
        for name, digest in {**GOLDEN_MAIN, **GOLDEN_TESTS}.items():
            with self.subTest(name=name):
                self.assertEqual(digest, sha({**self.main, **self.tests}[name]))
        with tempfile.TemporaryDirectory(prefix='bp-status-labels-repeat-') as other:
            elsewhere = Path(other)
            summary = run_producer(elsewhere / 'main', elsewhere / 'tests', elsewhere, REPO / 'desktop' / PRODUCER, 7)
            self.assertEqual(self.summary, summary)
            self.assertEqual(self.main, tree(elsewhere / 'main'))
            self.assertEqual(self.tests, tree(elsewhere / 'tests'))

    def test_six_whole_sources_are_byte_identical_to_their_pinned_archives(self):
        for name in ('VideoStatusLabels.kt', 'VideoStatusBadges.kt', 'ListModels.kt', 'SpaceModels.kt', 'SearchModels.kt', 'FavoriteModels.kt'):
            with self.subTest(name=name):
                archive = (search_up_followup.ARCHIVE if name == 'SearchModels.kt' else
                           home_native_followup.ARCHIVE if name == 'ListModels.kt' else source.ARCHIVE)
                archived = (REPO / archive / name).read_bytes()
                emitted = [raw for path, raw in self.main.items() if path.endswith('/' + name)]
                self.assertEqual([archived], emitted)

    def test_replaced_models_cover_exactly_the_four_synced_files_and_nothing_else(self):
        names = {Path(path).name for path in self.main if path.endswith('.kt')}
        self.assertEqual({'VideoStatusLabels.kt', 'VideoStatusBadges.kt', 'ListModels.kt', 'SpaceModels.kt',
                          'SearchModels.kt', 'FavoriteModels.kt', 'RecommendationReason.kt'}, names)
        # The unmounted charge-chip cluster and the premium pill policy are neither emitted nor hooked.
        for forbidden in ('SpaceChargeBadgePolicy', 'VideoPremiumBadgePolicy', 'resolveVideoPremiumBadgeLabel'):
            self.assertEqual([], [path for path, raw in self.main.items()
                                  if path.endswith('.kt') and forbidden in raw.decode('utf-8')], forbidden)
        self.assertEqual([], [hook.id for hook in source.HOOKS
                              if hook.path.endswith(('SpaceChargeBadgePolicy.kt', 'VideoPremiumBadgePolicy.kt'))])
        charge = canonical_source(REPO, source.APP + 'feature/space/SpaceChargeBadgePolicy.kt').read_text(encoding='utf-8')
        self.assertIn('internal fun resolveSpaceVideoChargeBadgeLabel(video: SpaceVideoItem): String? {', charge)

    # ------------------------------------------------------------------ shared policy and badge row
    def test_policy_keeps_upstream_priority_vocabulary_and_defaults_for_missing_fields(self):
        policy = self.emitted(LABELS_PATH)
        marks = source.label_order(policy)
        self.assertEqual(5, len(marks))
        # charging (or paid) < course < cooperation < interactive < server labels.
        self.assertEqual(sorted(policy.index(mark) for mark in marks), [policy.index(mark) for mark in marks])
        self.assertEqual(('互动', '付费', '充电', '充电专属', '合作', '课堂', '课程'),
                         source.label_vocabulary(policy, self.emitted(BADGES_PATH)))
        signature = policy[policy.index('fun resolveVideoStatusLabels('):policy.index('): List<String>')]
        self.assertEqual(['cooperation: Boolean = false', 'interactive: Boolean = false', 'course: Boolean = false',
                          'charging: Boolean = false', 'paid: Boolean = false',
                          'serverLabels: List<String> = emptyList()'],
                         [line.strip().rstrip(',') for line in signature.split('\n')[1:] if line.strip()])
        self.assertIn('if (charging) add("充电专属") else if (paid) add("付费")', policy)
        self.assertIn('.map(String::trim).filter(String::isNotBlank)', policy)
        self.assertEqual(1, policy.count('}.distinct()'))
        self.assertNotIn('订阅', policy)
        self.assertNotIn('置顶', policy)
        badges = self.emitted(BADGES_PATH)
        self.assertIn('if (labels.isEmpty()) return', badges)
        self.assertEqual(1, badges.count('emphasized = label == "充电专属" || label == "付费"'))
        self.assertNotIn('置顶', badges)

    def test_changed_priority_or_vocabulary_is_rejected(self):
        policy = self.emitted(LABELS_PATH)
        lines = policy.split('\n')
        course = next(i for i, line in enumerate(lines) if line.strip().startswith('if (course) add('))
        cooperation = next(i for i, line in enumerate(lines) if line.strip().startswith('if (cooperation) add('))
        swapped = list(lines)
        swapped[course], swapped[cooperation] = swapped[cooperation], swapped[course]
        with self.assertRaisesRegex(ValueError, 'priority order changed'):
            source.label_order('\n'.join(swapped))
        with self.assertRaisesRegex(ValueError, 'vocabulary changed'):
            source.label_vocabulary(policy.replace('"合作"', '"订阅"', 1), self.emitted(BADGES_PATH))
        with self.assertRaisesRegex(ValueError, 'vocabulary changed'):
            source.label_vocabulary(policy, self.emitted(BADGES_PATH).replace('"付费"', '"置顶"', 1))

    def test_upstream_has_no_subscribe_label_and_pin_is_only_a_separate_space_chip(self):
        for path, text in self.latest.items():
            with self.subTest(path=path):
                self.assertNotIn('订阅', text)
        screen = self.latest[source.SPACE_SCREEN]
        hooked = source.apply(source.SPACE_SCREEN, source.baseline(REPO, source.SPACE_SCREEN))
        self.assertEqual(1, screen.count('badgeLabel = "置顶"'))
        self.assertEqual(1, hooked.count('badgeLabel = "置顶"'))
        for path in (source.LABELS, source.BADGES):
            self.assertNotIn('置顶', self.latest[path])
        # Paid is produced only from the video rights pay/arc_pay inputs, never from search, space or watch-later.
        lists = self.emitted(MODEL_DIR + 'ListModels.kt')
        self.assertEqual(1, lists.count('paid = (rights?.pay ?: 0) > 0 || (rights?.arcPay ?: 0) > 0,'))
        self.assertEqual(1, len(re.findall(r'\bpaid\s*=', lists)))
        for text in (self.emitted(MODEL_DIR + 'SpaceModels.kt'), self.emitted(SEARCH_PATH),
                     source.apply(source.WATCH_LATER_REPOSITORY, source.baseline(REPO, source.WATCH_LATER_REPOSITORY))):
            self.assertNotRegex(text, r'\bpaid\s*=')
        proof = json.loads(self.main['v041-video-status-labels-proof.json'])
        self.assertEqual(dict(subscribeLabelExists=False, pinnedLabelInSharedPolicy=False,
                              pinnedChip='SpaceTopVideoCard badgeLabel only', paidLabelFromSearchSpaceOrWatchLater=False,
                              fullV041ParityClaimed=False), proof['upstreamScope'])
        self.assertEqual((False, False, False), (proof['mainCompiled'], proof['jvmTestsExecuted'], proof['runtimeExecuted']))

    def test_charging_pay_and_typed_badges_keep_upstream_decode_parity(self):
        # Any non-null level, including 0, is charging (same predicate as the upstream tests); documented Root decision.
        for path, name, needles in (
            (source.LIST_MODELS, 'ListModels.kt', ('chargingPay?.level != null', 'List<VideoContentBadge>')),
            (source.SPACE_MODELS, 'SpaceModels.kt', ('List<SpaceAggregateBadge>', 'data class SpaceAggregateBadge(')),
            (source.SEARCH_MODELS, 'SearchModels.kt', ('List<VideoContentBadge>',)),
        ):
            for needle in needles:
                with self.subTest(needle=needle, path=name):
                    emitted = self.emitted(MODEL_DIR + name).count(needle)
                    self.assertGreaterEqual(emitted, 1)
                    self.assertEqual(self.latest[path].count(needle), emitted)
        # A typed badge is a JSON object: a non-object element is not covered by ignoreUnknownKeys (upstream behaviour).
        self.assertEqual(1, self.emitted(LABELS_PATH).count('data class VideoContentBadge(val text: String = "")'))
        proof = json.loads(self.main['v041-video-status-labels-proof.json'])
        self.assertIn('a non-object element fails the whole page', proof['decodeParity']['typedBadges'])
        self.assertIn('including 0', proof['decodeParity']['chargingPay'])

    # ------------------------------------------------------------------ hook-edited models
    def test_search_models_keep_v041_schema_and_the_whole_v043_author_followup(self):
        emitted = self.emitted(SEARCH_PATH)
        self.assertEqual(search_up_followup.verified_raw(REPO)['SearchModels.kt'].decode('utf-8'), emitted)
        receipt = json.loads(self.main[source.PROOF_NAME])['mainSources'][SEARCH_PATH]
        self.assertEqual('whole-v043-search-up-author-replacement', receipt['mode'])
        self.assertTrue(receipt['identicalToArchive'])
        self.assertEqual([], receipt['deferredUpstreamHunks'])
        self.assertEqual(9, len(receipt['appliedPostHookUpstreamHunks']))
        enum = emitted[emitted.index('enum class SearchType'):emitted.index('companion object')]
        self.assertEqual(['ALL', 'VIDEO', 'UP', 'BANGUMI', 'MEDIA_FT', 'LIVE', 'LIVE_USER', 'ARTICLE', 'TOPIC', 'PHOTO'],
                         re.findall(r'^    ([A-Z_]+)\(', enum, re.M))
        for needle in ('SearchAllResponse', 'SearchAllData', 'SearchAllResultCategory',
                       'val res: List<SearchVideoItem>', 'area_name',
                       'val isUnionVideo: Int = 0', 'val isSteins: Boolean = false', 'val isLessonVideo: Boolean = false',
                       '@SerialName("charging_pay") val chargingPay: WatchLaterChargingPay? = null',
                       '@SerialName("pgc_label") val pgcLabel: String? = null',
                       'val badges: List<VideoContentBadge> = emptyList()',
                       'isUnionVideo = isUnionVideo,', 'isSteins = isSteins,', 'isLessonVideo = isLessonVideo,',
                       'chargingPay = chargingPay,', 'pgcLabel = pgcLabel,', 'badges = badges,'):
            with self.subTest(needle=needle):
                self.assertGreater(emitted.count(needle), 0)
                self.assertEqual(self.latest[source.SEARCH_MODELS].count(needle), emitted.count(needle))

    def edits(self, path, text):
        edits = []
        source.apply(path, text, edits=edits)
        return edits

    def test_favorite_model_is_whole_v041_with_page_size_and_named_existing_callers(self):
        emitted = self.emitted(FAVORITE_PATH)
        self.assertEqual(self.latest[source.FAVORITE_MODELS], emitted)
        self.assertEqual('3b5a648f0b9625019f173afbd4981d81313919c64dbea8140602a8437d595534', sha(emitted.encode()))
        baseline = source.baseline(REPO, source.FAVORITE_MODELS)
        self.assertEqual([('insert', 1), ('insert', 1)],
                         [(op[0], op[4] - op[3]) for op in changed_lines(baseline, emitted)])
        declaration = emitted[emitted.index('data class WatchLaterData('):]
        declaration = declaration[:declaration.index('\n)\n')]
        self.assertEqual(['val count: Int = 0', 'val ps: Int = 0', 'val list: List<WatchLaterItem>? = null'],
                         [line.strip().rstrip(',') for line in declaration.split('\n')[1:]])
        caller = (REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopPersonalContentTest.kt').read_text(encoding='utf-8')
        self.assertIn('WatchLaterData(count = 60, list = listOf(', caller)
        self.assertNotIn('WatchLaterData(60, listOf(', caller)
        receipt = json.loads(self.main[source.PROOF_NAME])['mainSources'][FAVORITE_PATH]
        self.assertEqual('whole-v041-replacement', receipt['mode'])
        self.assertTrue(receipt['identicalToArchive'])
        self.assertEqual([], receipt['deferredUpstreamHunks'])
        self.assertEqual(1, len(receipt['appliedPostHookUpstreamHunks']))

    def test_hooked_search_and_favorite_text_replays_inverse_and_matches_independent_replacement(self):
        for path, count in ((source.SEARCH_MODELS, 9), (source.FAVORITE_MODELS, 1)):
            with self.subTest(path=path):
                baseline = source.baseline(REPO, path)
                edits = self.edits(path, baseline)
                text = source.apply(path, baseline)
                emitted = self.emitted(MODEL_DIR + Path(path).name)
                if path == source.SEARCH_MODELS:
                    # Prove the full v043 -> v041 -> v025 inverse chain, retaining
                    # the same nine reviewed v041 hunks and their original hooks.
                    self.assertEqual(search_up_followup.verified_raw(REPO)['SearchModels.kt'].decode('utf-8'), emitted)
                    emitted = emitted.encode('utf-8').replace(search_up_followup.AFTER, search_up_followup.BEFORE, 1).decode('utf-8')
                    self.assertEqual(search_up_followup.BASE_SHA256, sha(emitted.encode('utf-8')))
                self.assertEqual(self.latest[path], emitted)
                remainder = source.reviewed_delta(path, source.REVIEWED_POST_HOOK_DELTAS, text, emitted)
                self.assertEqual(count, len(remainder))
                self.assertEqual(baseline, source.revert(text, edits))

    # ------------------------------------------------------------------ pins, anchors and inverse replay
    def test_every_hook_anchor_is_exact_once_in_canonical_text_and_inverse_restores_each_file(self):
        self.assertEqual(HOOK_IDS, [hook.id for hook in source.HOOKS])
        self.assertEqual(len(HOOK_IDS), len({hook.id for hook in source.HOOKS}))
        self.assertEqual(set(HOOKED_PATHS), {hook.path for hook in source.HOOKS})
        receipts = source.verify_hooks(REPO)
        rows = [row for row in receipts if row['id'] != 'result']
        self.assertEqual(HOOK_IDS, [row['id'] for row in rows])
        for row in rows:
            with self.subTest(hook=row['id']):
                self.assertEqual(1, row['anchorCountCanonical'])
                self.assertEqual(1, row['anchorCountAfterEarlierHooks'])
        self.assertEqual(8, len([row for row in receipts if row['id'] == 'result']))
        self.assertTrue(all(row['inverseRestoresBaseline'] for row in receipts if row['id'] == 'result'))
        for path in HOOKED_PATHS:
            with self.subTest(path=path):
                baseline = source.baseline(REPO, path)
                for hook in source.hooks_for(path):
                    self.assertEqual(1, baseline.count(hook.before), hook.id)
                edits = []
                hooked = source.apply(path, baseline, edits=edits)
                self.assertNotEqual(baseline, hooked)
                self.assertEqual(len(source.hooks_for(path)), len(edits))
                self.assertEqual(baseline, source.revert(hooked, edits))
                with self.assertRaises(ValueError):
                    source.apply(path, hooked)
                first = source.hooks_for(path)[0]
                with self.assertRaisesRegex(ValueError, 'not unique'):
                    source.apply(path, baseline + first.before)
                with self.assertRaisesRegex(ValueError, 'not unique'):
                    source.apply(path, baseline.replace(first.before, '', 1))
                with self.assertRaisesRegex(ValueError, 'Inverse seam'):
                    source.revert(hooked + '// drift\n', edits)
        self.assertEqual('text', source.apply('app/src/main/java/other/Unhooked.kt', 'text'))

    def test_duplicate_space_screen_badge_line_is_only_hooked_with_unique_context(self):
        baseline = source.baseline(REPO, source.SPACE_SCREEN)
        naive = ' ' * 44 + 'badgeLabel = resolveSpaceVideoChargeBadgeLabel(video),\n'
        self.assertEqual(2, baseline.count(naive))
        hooks = {hook.id: hook for hook in source.hooks_for(source.SPACE_SCREEN)}
        for name in ('SS1', 'SS2'):
            with self.subTest(hook=name):
                self.assertIn(naive, hooks[name].before)
                self.assertEqual(1, baseline.count(hooks[name].before))
        hooked = source.apply(source.SPACE_SCREEN, baseline)
        latest = self.latest[source.SPACE_SCREEN]
        for needle in ('resolveSpaceVideoChargeBadgeLabel', 'badgeLabel = "置顶"', 'VideoStatusBadges(', 'statusLabels = '):
            with self.subTest(needle=needle):
                self.assertEqual(latest.count(needle), hooked.count(needle))
        self.assertEqual(0, hooked.count('resolveSpaceVideoChargeBadgeLabel'))
        self.assertEqual(3, hooked.count('VideoStatusBadges('))

    def test_hook_consumers_are_the_declared_generators(self):
        consumers = {hook.id: hook.consumer for hook in source.HOOKS}
        self.assertEqual({'SM1', 'SM2', 'FM1'}, {name for name, value in consumers.items() if value == source.PRODUCER})
        self.assertEqual({'HC1', 'HC2', 'HC3', 'HC4'},
                         {name for name, value in consumers.items() if value == source.HOME_CARD_GENERATOR})
        self.assertEqual({'SO1'}, {name for name, value in consumers.items() if value == source.SPACE_OVERVIEW_GENERATOR})
        self.assertEqual({'SS%d' % i for i in range(8)},
                         {name for name, value in consumers.items() if value == source.SPACE_PAGES_GENERATOR})
        self.assertEqual({'WL1', 'WL2', 'WL3', 'WL4', 'WL5'},
                         {name for name, value in consumers.items() if value == source.WATCH_LATER_GENERATOR})
        for consumer in set(consumers.values()):
            self.assertTrue((REPO / consumer).is_file(), consumer)

    def test_non_held_consumer_generators_import_the_hook_module_once(self):
        # The home full-card generator is held by another task; its hooks HC1-HC4 are a separate patch.
        for consumer in (source.SPACE_OVERVIEW_GENERATOR, source.SPACE_PAGES_GENERATOR, source.WATCH_LATER_GENERATOR):
            with self.subTest(consumer=consumer):
                text = (REPO / consumer).read_text(encoding='utf-8')
                imports = re.findall(r'^\s*(?:from v041_video_status_labels import|import v041_video_status_labels)\b', text, re.M)
                self.assertEqual(1, len(imports))

    def test_pinned_hunk_tables_replay_forward_and_inverse_and_reject_any_drift(self):
        for path, count in ((source.LIST_MODELS, 10), (source.SPACE_MODELS, 2)):
            with self.subTest(path=path):
                receipts = source.reviewed_delta(path, source.WHOLE_MODEL_DELTAS, source.baseline(REPO, path), self.latest[path])
                self.assertEqual(count, len(receipts))
                self.assertTrue(all(row['meaning'] for row in receipts))
                self.drift(path, source.WHOLE_MODEL_DELTAS, source.baseline(REPO, path), self.latest[path])
        for path, count in ((source.SEARCH_MODELS, 9), (source.FAVORITE_MODELS, 1)):
            with self.subTest(path=path):
                emitted = source.apply(path, source.baseline(REPO, path))
                receipts = source.reviewed_delta(path, source.REVIEWED_POST_HOOK_DELTAS, emitted, self.latest[path])
                self.assertEqual(count, len(receipts))
                self.drift(path, source.REVIEWED_POST_HOOK_DELTAS, emitted, self.latest[path])

    def drift(self, path, table, old, new):
        rows = list(table[path])
        for index, row in enumerate(rows):
            for position in (4, 5):
                changed = list(row)
                changed[position] = '0' * 64
                with self.assertRaisesRegex(ValueError, 'hunks changed'):
                    source.reviewed_delta(path, {path: rows[:index] + [tuple(changed)] + rows[index + 1:]}, old, new)
            with self.assertRaisesRegex(ValueError, 'hunks changed|order changed'):
                source.reviewed_delta(path, {path: rows[:index] + rows[index + 1:]}, old, new)
        if len(rows) > 1:
            with self.assertRaisesRegex(ValueError, 'hunks changed|order changed'):
                source.reviewed_delta(path, {path: rows[::-1]}, old, new)

    def test_manifest_every_archive_leaf_and_any_extra_leaf_reject_tampering_before_any_output(self):
        leaves = ['manifest.json'] + [row['archiveFile'] for row in source.PINS]
        self.assertEqual(16, len(leaves))
        for leaf in leaves:
            for mutation in ('changed', 'missing', 'crlf'):
                with self.subTest(leaf=leaf, mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                    repo = Path(tmp)
                    copy_archive(repo)
                    target = repo / source.ARCHIVE / leaf
                    if mutation == 'changed':
                        target.write_bytes(target.read_bytes() + b'\n')
                    elif mutation == 'crlf':
                        target.write_bytes(target.read_bytes().replace(b'\n', b'\r\n'))
                    else:
                        target.unlink()
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        source.verified_raw(repo)
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        source.generate(repo, repo / 'out-main', repo / 'out-tests')
                    self.assertFalse((repo / 'out-main').exists() or (repo / 'out-tests').exists())
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            copy_archive(repo)
            (repo / source.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaisesRegex(ValueError, 'archive leaves changed'):
                source.verified_raw(repo)

    def test_archive_manifest_pins_and_identity_match_the_independent_values(self):
        manifest_raw = (REPO / source.ARCHIVE / 'manifest.json').read_bytes()
        self.assertEqual('e13914083ff47f1ba60181bda99cd71f6b019a71488b8bf2ff323c9c54f3b9d3', sha(manifest_raw))
        self.assertEqual(source.MANIFEST_SHA256, sha(manifest_raw))
        manifest = json.loads(manifest_raw)
        self.assertEqual((1, 'jay3-yy/BiliPai', 'v0.4.1', 'd18fb20e31adb11bbec9fae6dfce7d705d556217'),
                         (manifest['schemaVersion'], manifest['repository'], manifest['tag'], manifest['commit']))
        self.assertEqual(15, len(manifest['files']))
        self.assertEqual({'VideoStatusLabels.kt', 'VideoStatusBadges.kt', 'ListModels.kt', 'SpaceModels.kt', 'SearchModels.kt', 'FavoriteModels.kt',
                          'VideoStatusLabelsTest.kt', 'VideoCooperationMetadataTest.kt'},
                         {row['archiveFile'] for row in manifest['files'] if not row['referenceOnly']})
        self.assertEqual({row['archiveFile'] for row in manifest['files']} | {'manifest.json'},
                         {p.name for p in (REPO / source.ARCHIVE).iterdir()})
        for row in manifest['files']:
            raw = (REPO / source.ARCHIVE / row['archiveFile']).read_bytes()
            self.assertEqual((row['bytes'], row['sha256']), (len(raw), sha(raw)), row['archiveFile'])
            self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
            self.assertNotIn(b'\r', raw)
            self.assertFalse(raw.startswith(b'\xef\xbb\xbf'))
        # The two label sources and the two upstream tests are the pinned upstream blobs (VideoStatusLabelsTest 1bf5b2e4).
        by_name = {row['archiveFile']: row['gitBlob'][:8] for row in manifest['files']}
        self.assertEqual('8115954f', by_name['VideoStatusLabels.kt'])
        self.assertEqual('68e9bc71', by_name['VideoStatusBadges.kt'])
        self.assertEqual('1bf5b2e4', by_name['VideoStatusLabelsTest.kt'])
        self.assertEqual('4ee8b2b4', by_name['VideoCooperationMetadataTest.kt'])
        self.assertEqual('8301c8b6', by_name['UserContentRepository.kt'])

    def test_any_changed_canonical_v025_source_fails_closed_and_checkout_crlf_is_accepted(self):
        for path in source.BASELINE_SHA256_LF:
            original = canonical_source(REPO, path)
            relative = original.relative_to(REPO)
            with self.subTest(path=path, mutation='changed'), tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / relative
                target.parent.mkdir(parents=True)
                target.write_bytes(original.read_bytes() + b'\n// changed canonical source\n')
                with self.assertRaisesRegex(ValueError, 'Canonical original source digest mismatch'):
                    source.baseline(Path(tmp), path)
            with self.subTest(path=path, mutation='crlf'), tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / relative
                target.parent.mkdir(parents=True)
                target.write_bytes(original.read_bytes().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n'))
                self.assertEqual(source.baseline(REPO, path), source.baseline(Path(tmp), path))
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            copy_archive(repo)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                source.generate(repo, repo / source.ARCHIVE / 'out', repo / 'tests')
            with self.assertRaisesRegex(ValueError, 'overlap'):
                source.generate(repo, repo / 'out', repo / 'out' / 'tests')

    # ------------------------------------------------------------------ original tests and test support
    def test_original_tests_are_import_only_rewrites_with_the_original_case_counts(self):
        for name, cases in (('VideoStatusLabelsTest.kt', 6), ('VideoCooperationMetadataTest.kt', 5)):
            with self.subTest(name=name):
                archived = (REPO / source.ARCHIVE / name).read_text(encoding='utf-8')
                emitted = self.tests[MODEL_DIR + name].decode('utf-8')
                self.assertEqual(cases, archived.count('    @Test\n'))
                self.assertEqual(cases, emitted.count('    @Test\n'))
                self.assertNotIn('org.junit', emitted)
                self.assertEqual(1, emitted.count('package com.android.purebilibili.data.model.response\n'))
                before, after = archived.split('\n'), emitted.split('\n')
                changed = changed_lines(archived, emitted)
                self.assertTrue(changed)
                for tag, i1, i2, j1, j2 in changed:
                    self.assertEqual('replace', tag)
                    self.assertEqual(i2 - i1, j2 - j1)
                    for old, new in zip(before[i1:i2], after[j1:j2]):
                        self.assertTrue(old.startswith('import org.junit.'), old)
                        self.assertTrue(new.startswith('import kotlin.test.'), new)
                        self.assertEqual(old.rsplit('.', 1)[-1], new.rsplit('.', 1)[-1])
                # The original tests call the real production conversions and the verbatim test-only support.
                self.assertIn('toVideoItem', emitted)
                self.assertIn('import com.android.purebilibili.data.repository.asVideoItem\n', emitted)

    def test_original_test_support_is_the_verbatim_pinned_tail_in_test_sources_only(self):
        support = self.tests[SUPPORT_PATH].decode('utf-8')
        repository = (REPO / source.ARCHIVE / 'UserContentRepository.kt').read_text(encoding='utf-8')
        tail = repository[repository.index('fun durationSeconds(text: String): Int ='):]
        self.assertTrue(support.endswith(tail))
        self.assertEqual(1, support.count('fun durationSeconds(text: String): Int ='))
        self.assertEqual(1, support.count('fun SpaceVideoItem.asVideoItem(mid: Long): VideoItem ='))
        self.assertIn('package com.android.purebilibili.data.repository\n', support)
        self.assertIn('import com.android.purebilibili.data.model.response.*\n', support)
        self.assertNotIn(SUPPORT_PATH, self.main)
        self.assertEqual([], [path for path in self.main if path.endswith('Test.kt')])
        declaration = re.compile(r'\bfun\s+(?:SpaceVideoItem\.asVideoItem|durationSeconds)\(')
        collisions = [path.relative_to(REPO).as_posix() for path in (REPO / 'desktop/src').rglob('*.kt')
                      if declaration.search(path.read_text(encoding='utf-8'))]
        self.assertEqual([], collisions)

    # ------------------------------------------------------------------ Gradle, manifest and inventory wiring
    def test_build_declares_sync_excludes_producer_inputs_and_generated_source_sets(self):
        build = self.build
        for path in REPLACED_MODELS:
            with self.subTest(path=path):
                self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("' + path + '"))'))
                self.assertIn(path, {row['path'] for row in json.loads(
                    (REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']})
        for path in (source.VIDEO_CARD, source.SINGLE_COLUMN, source.SPACE_SCREEN, source.SPACE_LOAD_POLICY,
                     source.WATCH_LATER_REPOSITORY, source.WATCH_LATER_SCREEN):
            self.assertEqual(0, build.count('exclude(canonicalOriginalIdentity("' + path + '"))'), path)
        block = task_block(build, 'extractOriginalVideoStatusLabels')
        self.assertIn('dependsOn(prepareUpstreamSources)', block)
        self.assertIn('"python", "' + PRODUCER + '",', block)
        for option in ('"--repo", repositoryRoot.absolutePath', '"--output", layout.buildDirectory.dir("generated/v041-video-status-labels")',
                       '"--tests-output", layout.buildDirectory.dir("generated/v041-video-status-label-tests")'):
            self.assertEqual(1, block.count(option), option)
        # The producer is the command and an input; the other three are inputs only.
        self.assertEqual(2, block.count('"' + PRODUCER + '"'))
        for tool in ('tools/v041_video_status_labels.py', 'tools/v025_source_paths.py', 'tools/v025-canonical-sources.json'):
            self.assertEqual(1, block.count('"' + tool + '"'), tool)
        for tool in (PRODUCER, 'tools/v041_video_status_labels.py', 'tools/v025_source_paths.py', 'tools/v025-canonical-sources.json'):
            self.assertTrue((REPO / 'desktop' / tool).is_file(), tool)
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v041-video-status-labels")'))
        closing = '\n    ).map { canonicalOriginalSource(it) })'
        listing = block.split('inputs.files(listOf(', 1)[1].split(closing, 1)[0]
        # Exactly the ten canonical sources the producer reads, each once, with no other expression around them.
        self.assertEqual(sorted(source.BASELINE_SHA256_LF), sorted(re.findall(r'"([^"]+)"', listing)))
        self.assertEqual('', re.sub(r'\s*"[^"]+"\s*,?', '', listing).strip())
        self.assertEqual(1, block.count(closing))
        self.assertEqual(1, block.count('outputs.dir(layout.buildDirectory.dir("generated/v041-video-status-labels"))'))
        self.assertEqual(1, block.count('outputs.dir(layout.buildDirectory.dir("generated/v041-video-status-label-tests"))'))
        for line in (
            'kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/v041-video-status-labels")) }',
            'kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/v041-video-status-label-tests")) }',
            'tasks.named("compileKotlin") { dependsOn(extractOriginalVideoStatusLabels) }',
            'tasks.named("compileTestKotlin") { dependsOn(extractOriginalVideoStatusLabels) }',
        ):
            self.assertEqual(1, build.count(line), line)
        for name in ('extractUpstreamSpaceOverview', 'extractOriginalSpacePages', 'extractOriginalWatchLater'):
            with self.subTest(consumer=name):
                consumer = task_block(build, name)
                self.assertEqual(1, consumer.count('inputs.file("tools/v041_video_status_labels.py")'))
                self.assertEqual(1, consumer.count('inputs.dir("upstream-slices/v041-video-status-labels")'))

    def test_no_other_generator_names_the_four_replaced_model_files(self):
        # prepareUpstreamSources excludes these four canonical files for everyone, so a second producer (or a hook of
        # another module) for the same file would emit a duplicate declaration or silently drop this slice's hunks.
        # The follow-up helper returns verified bytes to this same producer; it never writes output.
        own = {'v041_video_status_labels.py', 'extract-v041-video-status-labels.py', 'v043_search_up_author.py', 'v043_home_native_cards.py'}
        names = [Path(path).name for path in REPLACED_MODELS]
        self.assertEqual(['ListModels.kt', 'SpaceModels.kt', 'SearchModels.kt', 'FavoriteModels.kt'], names)
        for tool in sorted((REPO / 'desktop/tools').glob('*.py')):
            if tool.name not in own:
                with self.subTest(tool=tool.name):
                    text = tool.read_text(encoding='utf-8')
                    self.assertEqual([], [name for name in names if name in text])

    def test_hook_module_imports_only_the_declared_helper_and_standard_modules(self):
        tree_ = ast.parse((REPO / 'desktop/tools/v041_video_status_labels.py').read_text(encoding='utf-8'))
        roots = set()
        for node in ast.walk(tree_):
            if isinstance(node, ast.Import):
                roots |= {alias.name.split('.')[0] for alias in node.names}
            elif isinstance(node, ast.ImportFrom):
                roots.add(node.module.split('.')[0])
        self.assertEqual({'collections', 'pathlib', 'hashlib', 'json', 'os', 're', 'v025_source_paths', 'v043_search_up_author', 'v043_home_native_cards'}, roots)
        cli = ast.parse((REPO / 'desktop/tools/extract-v041-video-status-labels.py').read_text(encoding='utf-8'))
        cli_roots = set()
        for node in ast.walk(cli):
            if isinstance(node, ast.Import):
                cli_roots |= {alias.name.split('.')[0] for alias in node.names}
            elif isinstance(node, ast.ImportFrom):
                cli_roots.add(node.module.split('.')[0])
        self.assertEqual({'argparse', 'json', 'pathlib', 'v041_video_status_labels'}, cli_roots)

    def test_manifest_record_and_compatibility_inventory_pin_the_same_archive(self):
        record = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['v041VideoStatusLabelsSlice']
        self.assertEqual(source.MANIFEST_SHA256, record['manifestSHA256'])
        self.assertEqual((source.COMMIT, source.ARCHIVE, 'v0.4.1'),
                         (record['upstreamCommit'], record['archive'], record['upstreamTag']))
        self.assertEqual((11, 0, 15), (record['originalTestCases'], record['deferredUpstreamModelHunks'],
                                        record['archivedFiles']))
        # All 21 anchored hooks are wired: three model hooks and eighteen original consumer hooks.
        by_consumer = {consumer: sum(1 for hook in source.HOOKS if hook.consumer == consumer)
                       for consumer in {hook.consumer for hook in source.HOOKS}}
        self.assertEqual((21, 3, 18, 0), (record['anchoredHooks'], record['modelHooks'],
                                          record['wiredConsumerHooks'], record['heldConsumerHooks']))
        self.assertEqual((3, 4, 14), (by_consumer[source.PRODUCER], by_consumer[source.HOME_CARD_GENERATOR],
                                      sum(by_consumer.values()) - by_consumer[source.PRODUCER]
                                      - by_consumer[source.HOME_CARD_GENERATOR]))
        self.assertEqual((['home', 'popular', 'search', 'space', 'watchLater'], []),
                         (record['entriesAlignedByWiredHooks'], record['entriesPendingHeldHomeCardHooks']))
        self.assertEqual((False, False, False, False, False, False),
                         (record['subscribeLabelInV041'], record['pinnedChipInSharedPolicy'],
                          record['paidLabelFromSearchSpaceOrWatchLater'], record['fullV041ParityClaimed'],
                          record['mainCompiled'], record['jvmTestsExecuted']))
        self.assertTrue(record['chargingPayAnyNonNullLevelIsChargingAsUpstream'])
        inventory = json.loads((REPO / 'desktop/tools/compatibility-source-inputs.json').read_text(encoding='utf-8'))
        self.assertIn(source.ARCHIVE, inventory['buildInputRoots'])
        archive = [row for row in inventory['archives'] if row['directory'] == source.ARCHIVE]
        self.assertEqual(1, len(archive))
        manifest = json.loads((REPO / source.ARCHIVE / 'manifest.json').read_text(encoding='utf-8'))
        self.assertEqual({source.ARCHIVE + '/manifest.json', *[source.ARCHIVE + '/' + r['archiveFile'] for r in manifest['files']]},
                         {leaf['path'] for leaf in archive[0]['files']})
        rows = {row['localPath']: row for row in inventory['inputs'] if row['localPath'].startswith(source.ARCHIVE + '/')}
        self.assertEqual(15, len(rows))
        for entry in manifest['files']:
            row = rows[source.ARCHIVE + '/' + entry['archiveFile']]
            self.assertEqual((entry['path'], entry['gitBlob'], entry['sha256'], entry['referenceOnly']),
                             (row['path'], row['blob'], row['sha256'], row['referenceOnly']))
            self.assertEqual((source.COMMIT, 'raw'), (row['upstreamCommit'], row['hashNormalization']))


def load_generator(consumer):
    spec = importlib.util.spec_from_file_location('status_labels_' + Path(consumer).stem.replace('-', '_'), REPO / consumer)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def line_delta(before, after):
    """Signed per-line difference between two Kotlin texts, as a Counter of the non-blank lines that changed."""
    delta = Counter(line for line in after.splitlines() if line.strip())
    delta.subtract(Counter(line for line in before.splitlines() if line.strip()))
    return Counter({line: count for line, count in delta.items() if count})


class V041GeneratorWiringTest(unittest.TestCase):
    """The anchored hooks only exist if a generator calls them. These tests run the real non-held generators that
    need no generated prerequisites (watch later, space overview) once hooked and once with the hook module's apply()
    replaced by the identity, and check that the output differs by exactly the hooks' own lines. Space pages needs
    generated inputs and is checked on its text; the held home full-card generator is deliberately not asserted.
    They need the upstream git objects the generators read (CI checks out the full history)."""

    def generate(self, consumer, hooked):
        recorded = []
        real_apply = source.apply

        def recording(path, text, output=None, output_name=None, edits=None):
            mine = []
            result = real_apply(path, text, output, output_name, mine)
            if edits is not None:
                edits.extend(mine)
            recorded.append((path, [edit['id'] for edit in mine]))
            return result

        def identity(path, text, output=None, output_name=None, edits=None):
            return text

        module = load_generator(consumer)
        with tempfile.TemporaryDirectory(prefix='bp-status-labels-generated-') as output:
            with mock.patch.object(source, 'apply', recording if hooked else identity):
                if consumer == source.WATCH_LATER_GENERATOR:
                    module.generate(REPO, Path(output))
                else:
                    module.generate(REPO, Path(output), True)
            texts = {path.relative_to(output).as_posix(): path.read_text(encoding='utf-8')
                     for path in sorted(Path(output).rglob('*.kt'))}
        return [call for call in recorded if call[1]], texts

    def assert_generator_applies_exactly_its_hooks(self, consumer, expected_calls):
        calls, hooked = self.generate(consumer, True)
        self.assertEqual(expected_calls, calls)
        _, baseline = self.generate(consumer, False)
        self.assertEqual(set(baseline), set(hooked))
        own = [hook for hook in source.HOOKS if hook.consumer == consumer]
        self.assertEqual([hook.id for hook in own], [hook_id for _, ids in calls for hook_id in ids])
        expected = Counter()
        for hook in own:
            expected.update(line_delta(hook.before, hook.after))
        actual = Counter()
        for name in hooked:
            actual.update(line_delta(baseline[name], hooked[name]))
        self.assertTrue(expected)
        self.assertEqual({line: count for line, count in expected.items() if count},
                         {line: count for line, count in actual.items() if count})

    def test_watch_later_generator_applies_wl1_to_wl5_to_exactly_the_repository_and_the_screen(self):
        self.assert_generator_applies_exactly_its_hooks(source.WATCH_LATER_GENERATOR, [
            (source.WATCH_LATER_REPOSITORY, ['WL1', 'WL2', 'WL3']), (source.WATCH_LATER_SCREEN, ['WL4', 'WL5'])])

    def test_space_overview_generator_applies_so1_to_the_aggregate_mapping_only(self):
        self.assert_generator_applies_exactly_its_hooks(source.SPACE_OVERVIEW_GENERATOR, [
            (source.SPACE_LOAD_POLICY, ['SO1'])])

    def test_space_pages_generator_applies_ss0_to_ss7_once_and_first_inside_screen(self):
        text = (REPO / source.SPACE_PAGES_GENERATOR).read_text(encoding='utf-8')
        self.assertEqual(1, len(re.findall(r'v041_video_status_labels import', text)))
        self.assertEqual(1, text.count("apply_status_labels(BASE+'feature/space/SpaceScreen.kt',s,edit)"))
        lines = text.splitlines()
        start = lines.index('        def screen(s,edit,logs):')
        self.assertEqual('from v041_video_status_labels import apply_with as apply_status_labels', lines[start + 1].strip())
        self.assertEqual("s=apply_status_labels(BASE+'feature/space/SpaceScreen.kt',s,edit)", lines[start + 2].strip())
        # SO1 belongs to the overview generator only, so SpaceLoadPolicy is never hooked twice.
        self.assertNotIn('SpaceLoadPolicy', ''.join(line for line in lines if 'apply_status_labels' in line))
        self.assertEqual({'SS%d' % i for i in range(8)},
                         {hook.id for hook in source.HOOKS if hook.consumer == source.SPACE_PAGES_GENERATOR})


if __name__ == '__main__':
    unittest.main()
