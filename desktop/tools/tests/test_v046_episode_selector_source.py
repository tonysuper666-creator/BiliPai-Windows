"""Source checks for roadmap slice lst-episode-selector-collection. No Gradle, no JVM, no network.

Covers the six whole v0.4.6 files of desktop/upstream-slices/v046-episode-selector (pages-only part selector, part list,
shared list navigation, collection sheet with introCanExpand, Page.firstFrame and the original nested scroll test), their
selection by the four existing Windows producers, the five counted call-site adaptations (detail page, player overlay and
the three large-screen callers: tablet layout, cinema layout, audio mode player), the verify scripts that read the
producers, the registration and the hand-written JVM class. The Kotlin is not compiled here: the producers run for real
into short temporary directories and the generated text is checked.
"""
from contextlib import contextmanager
from pathlib import Path
import difflib
import hashlib
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
import v046_episode_selector as episode

PKG = 'com/android/purebilibili/'
COMPONENTS = PKG + 'feature/video/ui/components/'
PAGES_OUTPUT = COMPONENTS + 'PagesSelector.kt'
SHEET_OUTPUT = COMPONENTS + 'DesktopOriginalCollectionSheet.kt'
OVERLAY_OUTPUT = PKG + 'feature/video/ui/overlay/VideoPlayerOverlay.kt'
CONTENT_OUTPUT = PKG + 'feature/video/screen/DesktopOriginalVideoContentSection.kt'
DETAIL_OUTPUT = episode.DETAIL_RESPONSE_OUTPUT
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopEpisodeSelectorRepinTest.kt'
FEATURE = 'v046-episode-selector'
APP_OTHER = episode.APP + 'feature/video/screen/VideoContentSection.kt'
TASKS = ('extractOriginalPlayerFullControls', 'extractStableCollectionSheet', 'extractOriginalVideoDetailUnits',
         'extractOriginalVideoContentFull', 'extractOriginalVideoTabletFull', 'extractOriginalVideoAudioFull')
SCREEN = PKG + 'feature/video/screen/'
# The three large-screen callers: (script producer, producer-relative output, call-site key, the one line that is added).
LARGE_SCREEN_SITES = (
    ('tablet', 'TabletVideoLayout.kt', 'tablet', '                    coverUrl = info.pic,'),
    ('tablet', 'TabletCinemaLayout.kt', 'cinema', '                                coverUrl = success.info.pic,'),
    ('audio', 'AudioModeMusicPlayer.kt', 'audio', '                coverUrl = info.pic,'),
)
SCRIPT_PRODUCERS = {'tablet': 'extract-upstream-video-tablet-full.py', 'audio': 'extract-upstream-video-audio-full.py'}

# The Windows rewrites of the part selector (display metrics instead of the Android configuration), in producer order.
PAGES_REMOVED = [
    'import android.content.res.Configuration',
    'import androidx.compose.ui.platform.LocalConfiguration',
    '    val isLandscape = remember(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {',
    '        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||',
]
PAGES_ADDED = [
    'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration',
    '    val isLandscape = remember((if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1), '
    'configuration.screenWidthDp, configuration.screenHeightDp) {',
    '        (if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1) == 2 ||',
]
# The seven Windows hunks of the collection sheet producer (unchanged since the v0.2.5 file), as removed and added lines.
SHEET_REMOVED = [
    'import androidx.compose.ui.platform.LocalContext',
    'import com.android.purebilibili.core.ui.AppModalBottomSheet',
    'import com.android.purebilibili.core.store.SettingsManager',
    '    val context = LocalContext.current',
    '            SettingsManager.setCollectionSortMode(context, collectionSubscriptionId, nextMode)',
    '                        com.android.purebilibili.core.util.ShareUtils.shareCollection(',
    '                        model = ImageRequest.Builder(LocalContext.current)',
]
SHEET_ADDED = [
    'import coil3.compose.LocalPlatformContext',
    'import com.bilipai.desktop.ui.LocalDesktopCollectionBindings',
    'import com.bilipai.desktop.ui.DesktopWindowsCollectionModalSheet as AppModalBottomSheet',
    'import com.android.purebilibili.core.store.DesktopOriginalCollectionSettings as SettingsManager',
    '    val platform = LocalDesktopCollectionBindings.current',
    '    val context = platform.context',
    '            platform.withOwnedCollectionPreferences { SettingsManager.setCollectionSortMode(context, collectionSubscriptionId, nextMode) }',
    '                        platform.shareCollection(',
    '                        model = ImageRequest.Builder(LocalPlatformContext.current)',
]
# Top-level names this slice brings into (or moves inside) the compile universe: each must have exactly one definer.
NEW_NAMES = (
    r'internal object PagePreviewNestedScrollConnection\b', r'^fun PagesSelector\(', r'internal fun PageCoverList\(',
    r'internal fun RowScope\.EpisodeListNavigationActions\(', r'^fun CollectionSheet\(', r'internal fun CollectionEpisodeRow\(',
    r'internal fun resolveCollectionEpisodeAbsoluteTimeText\(',
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def load(name, label):
    spec = importlib.util.spec_from_file_location(label, TOOLS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def text_of(path):
    return Path(path).read_bytes().decode('utf-8')


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def run_script_producer(label, output):
    """Run a script-style producer (argument parsing at module level, no generate()) in process, as __main__."""
    sink = io.TextIOWrapper(io.BytesIO(), encoding='utf-8')
    with patch.object(sys, 'argv', [SCRIPT_PRODUCERS[label], '--repo', str(REPO), '--output', str(output)]), \
            patch('sys.stdout', sink):
        runpy.run_path(str(TOOLS / SCRIPT_PRODUCERS[label]), run_name='__main__')


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def upstream_blob(path):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob', episode.COMMIT + ':' + path],
                          capture_output=True, check=True).stdout


def changed_lines(before, after):
    """(removed lines, added lines) of a line diff; the order of the lists follows the files."""
    removed, added = [], []
    matcher = difflib.SequenceMatcher(None, before.split('\n'), after.split('\n'), autojunk=False)
    for tag, a1, a2, b1, b2 in matcher.get_opcodes():
        if tag != 'equal':
            removed.extend(before.split('\n')[a1:a2])
            added.extend(after.split('\n')[b1:b2])
    return removed, added


def gradle_brace_depth(script):
    """Open minus closed braces and parentheses of a Gradle Kotlin script, ignoring strings and comments."""
    backslash = chr(92)
    depth, i, n = 0, 0, len(script)
    while i < n:
        c = script[i]
        if script.startswith('//', i):
            j = script.find(chr(10), i)
            i = n if j < 0 else j
        elif script.startswith('/*', i):
            j = script.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif script.startswith('"' * 3, i):
            i = script.index('"' * 3, i + 3) + 3
        elif c == '"':
            i += 1
            while script[i] != '"':
                if script[i] == backslash:
                    i += 1
                elif script.startswith('${', i):
                    d, i = 1, i + 2
                    while d:
                        d += (script[i] == '{') - (script[i] == '}')
                        i += 1
                    continue
                i += 1
            i += 1
        elif c == "'":
            i = script.index("'", i + 1 + (1 if script[i + 1] == backslash else 0)) + 1
        else:
            depth += (c in '{(') - (c in '})')
            i += 1
    return depth


def balanced(testcase, sync, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            testcase.assertTrue(stack, first)
            testcase.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    testcase.assertEqual([], stack)


class ArchiveAndWiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sync = load('sync-upstream.py', 'ves_sync')
        cls.sources, cls.raws, cls.manifest = episode.fixed(REPO)
        cls.build = text_of(REPO / 'desktop/build.gradle.kts')
        cls.catalog = json.loads(text_of(REPO / 'desktop/tools/compatibility-source-inputs.json'))
        cls.registry = {row['path']: row for row in json.loads(text_of(REPO / 'desktop/upstream-sources.json'))['sources']}

    def copy_archive(self, temp):
        target = Path(temp) / episode.ARCHIVE
        shutil.copytree(REPO / episode.ARCHIVE, target)
        return target

    def test_every_leaf_the_manifest_the_license_and_an_extra_leaf_reject_tampering(self):
        for name in sorted(episode.EXPECTED) + ['manifest.json', 'LICENSE']:
            with self.subTest(changed=name), tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
                archive = self.copy_archive(temp)
                with open(archive / name, 'ab') as handle:
                    handle.write(b' ')
                with self.assertRaises(ValueError):
                    episode.fixed(Path(temp))
        for name in sorted(episode.EXPECTED) + ['LICENSE']:
            with self.subTest(removed=name), tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
                archive = self.copy_archive(temp)
                (archive / name).unlink()
                with self.assertRaises(ValueError):
                    episode.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
            archive = self.copy_archive(temp)
            (archive / 'Extra.kt').write_bytes(b'package x\n')
            with self.assertRaises(ValueError):
                episode.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
            archive = self.copy_archive(temp)
            (archive / 'PagesSelector.kt').write_bytes(self.raws['PagesSelector.kt'].replace(b'\n', b'\r\n'))
            with self.assertRaises(ValueError):
                episode.fixed(Path(temp))
        with self.assertRaises(ValueError):
            episode.fixed(Path(tempfile.gettempdir()) / 'bp-ves-missing-repo')

    def test_declaration_pins_fail_closed_when_a_pinned_declaration_moves(self):
        for name, pins in episode.DECLARATIONS.items():
            for token, count in pins:
                with self.subTest(leaf=name, token=token), patch.dict(episode.DECLARATIONS, {name: ((token, count + 1),)}):
                    with self.assertRaises(ValueError):
                        episode.fixed(REPO)

    def test_the_reverted_collection_form_of_the_part_selector_is_refused(self):
        # 0.3.8 (e0c7c4ee) listed collection episodes inside the part selector; 0.3.9 (b491ed2) removed that and v0.4.6 keeps the
        # revert. A file that mentions any of these words is the 0.3.8 form and must never be accepted as the selector.
        for word in episode.COLLECTION_IN_PAGES_SELECTOR:
            with self.subTest(word=word), tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
                text = self.sources['PagesSelector.kt'] + '// ' + word + '\n'
                raw = text.encode('utf8')
                archive = self.copy_archive(temp)
                (archive / 'PagesSelector.kt').write_bytes(raw)
                manifest = json.loads((archive / 'manifest.json').read_bytes())
                row = next(item for item in manifest['files'] if item['filename'] == 'PagesSelector.kt')
                row.update(bytes=len(raw), sha256=sha(raw), gitBlob=hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
                changed = (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf8')
                (archive / 'manifest.json').write_bytes(changed)
                with patch.object(episode, 'MANIFEST_SHA256', sha(changed)):
                    with self.assertRaisesRegex(ValueError, 'reverted 0.3.8 form'):
                        episode.fixed(Path(temp))
        for word in episode.COLLECTION_IN_PAGES_SELECTOR:
            self.assertNotIn(word, self.sources['PagesSelector.kt'])
        # The part list and the part selector are about parts only.
        self.assertNotIn('ugc', self.sources['PageCoverList.kt'].lower())

    def test_the_archive_is_the_exact_upstream_blob_set(self):
        if not commit_present(episode.COMMIT):
            self.fail('the upstream commit is missing from this clone: ' + episode.COMMIT)
        self.assertEqual(episode.COMMIT, self.manifest['commit'])
        self.assertEqual(sorted(episode.UPSTREAM_PATHS.values()), sorted(row['path'] for row in self.manifest['files']))
        for row in self.manifest['files']:
            with self.subTest(leaf=row['filename']):
                blob = upstream_blob(row['path'])
                self.assertEqual(blob, self.raws[row['filename']])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
        self.assertEqual(episode.MANIFEST_SHA256, sha((REPO / episode.ARCHIVE / 'manifest.json').read_bytes()))
        self.assertEqual(upstream_blob('LICENSE'), (REPO / episode.ARCHIVE / 'LICENSE').read_bytes())
        # The blob ids the roadmap names.
        by_name = {row['filename']: row['gitBlob'] for row in self.manifest['files']}
        self.assertTrue(by_name['PagesSelector.kt'].startswith('d1ce2370'))
        self.assertTrue(by_name['CollectionSheet.kt'].startswith('1901854f'))
        self.assertTrue(by_name['VideoDetailResponse.kt'].startswith('687ce34e'))

    def test_the_manifest_predecessor_pins_are_the_real_predecessors(self):
        import v031_repost_coin as coin
        import v037_player_closure as closure
        from v025_source_paths import canonical_source
        pages_path = episode.UPSTREAM_PATHS['PagesSelector.kt']
        sheet_path = episode.UPSTREAM_PATHS['CollectionSheet.kt']
        v037_pages = closure.fixed_sources()[pages_path]
        self.assertEqual(episode.PREDECESSOR_SHA256['PagesSelector.kt'], sha(v037_pages))
        self.assertTrue(v037_pages.encode() != self.raws['PagesSelector.kt'])
        vendored = canonical_source(REPO, sheet_path).read_bytes().replace(b'\r\n', b'\n')
        self.assertEqual(episode.PREDECESSOR_SHA256['CollectionSheet.kt'], sha(vendored))
        self.assertEqual(self.registry[sheet_path]['sha256'], sha(vendored))
        v031 = coin.sources()[1]['VideoDetailResponse.kt']
        self.assertEqual(episode.PREDECESSOR_SHA256['VideoDetailResponse.kt'], sha(v031))
        for name, key in episode.PREDECESSOR_KEYS.items():
            self.assertEqual(episode.PREDECESSOR_SHA256[name], self.manifest['beforeFinalOutputs'][key]['sha256'])
        self.assertEqual(3, len(self.manifest['beforeFinalOutputs']))

    def test_the_selection_hands_the_two_whole_files_over_and_fails_closed_on_predecessor_drift(self):
        import v037_player_closure as closure
        pages_path = episode.UPSTREAM_PATHS['PagesSelector.kt']
        predecessor = closure.fixed_sources()[pages_path]
        self.assertEqual(self.sources['PagesSelector.kt'], episode.selected_source(pages_path, predecessor, REPO))
        self.assertEqual(self.sources['PagesSelector.kt'], episode.selected_source(pages_path, predecessor.replace('\n', '\r\n'), REPO))
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            episode.selected_source(pages_path, predecessor + ' ', REPO)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            episode.selected_source(pages_path, self.sources['PagesSelector.kt'], REPO)
        sheet_path = episode.UPSTREAM_PATHS['CollectionSheet.kt']
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            episode.selected_source(sheet_path, self.sources['CollectionSheet.kt'], REPO)
        # Every other path passes through untouched, so no other producer read changes.
        other = episode.UPSTREAM_PATHS['PageCoverList.kt']
        self.assertEqual('anything', episode.selected_source(other, 'anything', REPO))
        self.assertEqual('anything', episode.selected_source(APP_OTHER, 'anything', REPO))
        # VideoDetailResponse: the v0.3.1 body advances, anything else (the v0.4.6 body itself, a changed body) does not.
        import v031_repost_coin as coin
        v031 = coin.sources()[1]['VideoDetailResponse.kt']
        self.assertEqual(self.sources['VideoDetailResponse.kt'], episode.advance_video_detail_response(REPO, v031))
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            episode.advance_video_detail_response(REPO, self.sources['VideoDetailResponse.kt'])
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            episode.advance_video_detail_response(REPO, v031 + '\n')

    def test_the_v046_text_carries_every_feature_of_the_three_covered_entries(self):
        pages = self.sources['PagesSelector.kt']
        # v038-video-episode-selector (final form): horizontal cards, beat indicator, cover list in the sheet, auto-locate.
        self.assertIn('private fun PagesHorizontalPreviewRow(', pages)
        self.assertIn('private fun PlayingEqualizerIcon(', pages)
        self.assertEqual(2, pages.count('PageCoverList('))
        self.assertIn('isPlaying: Boolean = false', pages)
        self.assertIn('coverUrl: String = ""', pages)
        self.assertIn('listState.animateScrollToItem(activeIndex)', pages)
        self.assertIn('internal object PagePreviewNestedScrollConnection', pages)
        # v039-portrait-comment-viewport-pages: the pages-only final form (the revert of 0.3.8 collection episodes).
        self.assertIn('视频选集只展示当前视频的分 P，合集由独立入口处理。', pages)
        cover = self.sources['PageCoverList.kt']
        self.assertIn('AppSingleChoiceRow(', cover)
        self.assertIn('minLines = 2,', cover)
        self.assertIn('page.firstFrame.ifBlank { coverUrl }', cover)
        self.assertIn('initialFirstVisibleItemIndex = initialPosition', cover)
        self.assertIn('listState.scrollToItem(currentPosition)', cover)
        sheet = self.sources['CollectionSheet.kt']
        # v035-collection-intro-expand-fix: the intro only offers expansion when it overflows.
        self.assertIn('var introCanExpand by remember(ugcSeason.id, collectionIntro) { mutableStateOf(false) }', sheet)
        self.assertIn('.clickable(enabled = introCanExpand || introExpanded) { introExpanded = !introExpanded }', sheet)
        self.assertIn('if (!introExpanded) introCanExpand = result.hasVisualOverflow', sheet)
        self.assertIn('if (introCanExpand || introExpanded) AppIconButton(', sheet)
        self.assertEqual(1, sheet.count('EpisodeListNavigationActions('))
        self.assertEqual(3, self.sources['EpisodeListNavigationActions.kt'].count('AppIconButton('))
        self.assertIn('@SerialName("first_frame") val firstFrame: String = "",', self.sources['VideoDetailResponse.kt'])

    def test_registration_catalog_gradle_and_digest_agree(self):
        archive_rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(episode.ARCHIVE + '/')]
        self.assertEqual(sorted(episode.UPSTREAM_PATHS.values()), sorted(row['path'] for row in archive_rows))
        by_leaf = {row['filename']: row for row in self.manifest['files']}
        for row in archive_rows:
            leaf = Path(row['localPath']).name
            with self.subTest(leaf=leaf):
                self.assertFalse(row['referenceOnly'])
                self.assertEqual([FEATURE], row['features'])
                self.assertEqual('raw', row['hashNormalization'])
                self.assertEqual('adaptedSource', row['kind'])
                self.assertEqual(episode.COMMIT, row['upstreamCommit'])
                self.assertEqual(by_leaf[leaf]['sha256'], row['sha256'])
                self.assertEqual(by_leaf[leaf]['gitBlob'], row['blob'])
                self.assertEqual(leaf in ('PagesSelector.kt', 'CollectionSheet.kt'), row['partialReuse'])
                self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(episode.ARCHIVE))
        archives = [row for row in self.catalog['archives'] if row['directory'] == episode.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({episode.ARCHIVE + '/' + name for name in episode.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = self.sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(archive_rows, [row for row in loaded if row['localPath'].startswith(episode.ARCHIVE + '/')])
        # The archive directory appears in the build script only through the four producer tasks of one trailing block.
        self.assertEqual(1, self.build.count('inputs.dir("upstream-slices/v046-episode-selector")'))
        self.assertEqual(1, self.build.count('inputs.file("tools/v046_episode_selector.py")'))
        start = self.build.index('inputs.file("tools/v046_episode_selector.py")')
        block_start = self.build.rindex('tasks.matching', 0, start)
        header = self.build[block_start:start]
        for task in TASKS:
            self.assertIn('"' + task + '"', header)
            self.assertIn('val ' + task + ' by tasks.registering(Exec::class) {', self.build)
        self.assertEqual([], [row for row in self.build.splitlines() if 'srcDir' in row and 'v046-episode-selector' in row])
        # The digest and the counts that test_sync_upstream.py pins are the ones of the committed catalog.
        digest = sha(text_of(REPO / 'desktop/tools/compatibility-source-inputs.json').replace('\r\n', '\n'))
        self.assertEqual(digest, load('sync-upstream.py', 'ves_digest').COMPATIBILITY_CATALOG_SHA256)

    def test_the_vendored_direct_copy_exclude_already_exists_once_and_nothing_else_is_excluded(self):
        detail_path = episode.UPSTREAM_PATHS['VideoDetailResponse.kt']
        line = 'exclude(canonicalOriginalIdentity("' + detail_path + '"))'
        self.assertEqual(1, self.build.count(line))
        prepared = self.build[self.build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {'):
                              self.build.index('\nval extractUpstreamApi ')]
        self.assertEqual(1, prepared.count(line))
        self.assertEqual('direct', self.registry[detail_path].get('mode', 'direct'))
        # The part selector and the sheet are producer-owned (not direct), the other three leaves are new files.
        self.assertEqual('policy-extract', self.registry[episode.UPSTREAM_PATHS['PagesSelector.kt']]['mode'])
        self.assertEqual('extracted', self.registry[episode.UPSTREAM_PATHS['CollectionSheet.kt']]['mode'])
        for name in ('PagesSelector.kt', 'CollectionSheet.kt'):
            self.assertNotIn('exclude(canonicalOriginalIdentity("' + episode.UPSTREAM_PATHS[name] + '"))', self.build)
        for name in ('PageCoverList.kt', 'EpisodeListNavigationActions.kt'):
            self.assertNotIn(episode.UPSTREAM_PATHS[name], self.registry)
            self.assertFalse((REPO / episode.UPSTREAM_PATHS[name]).exists(), 'no vendored copy may define it a second time')
        model = load('check_slice_symbol_closure.py', 'ves_gate').parse_gradle(self.build)
        self.assertEqual(1, model.sync_excludes.count(detail_path))

    def test_the_same_package_helpers_and_the_layout_policy_are_the_v046_files(self):
        # The part selector and the sheet call these without importing them; they are vendored direct copies.
        for relative in ('feature/video/ui/components/PagesSelectorLayoutPolicy.kt',
                         'feature/video/ui/components/CollectionEpisodePolicy.kt', 'feature/video/ui/components/ModalChildScroll.kt'):
            path = episode.APP + relative
            with self.subTest(file=relative):
                self.assertEqual('direct', self.registry[path].get('mode', 'direct'))
                self.assertEqual(upstream_blob(path).replace(b'\r\n', b'\n'),
                                 (REPO / path).read_bytes().replace(b'\r\n', b'\n'))

    def test_no_other_generator_names_the_new_files_and_the_moved_declarations(self):
        for name in ('PageCoverList', 'EpisodeListNavigationActions', 'PagePreviewNestedScrollTest'):
            owners = sorted(p.name for p in TOOLS.glob('*.py') if name in p.read_text(encoding='utf-8'))
            self.assertEqual(['v046_episode_selector.py'], owners, name)
        sheet_owners = sorted(p.name for p in TOOLS.glob('*.py') if 'DesktopOriginalCollectionSheet' in p.read_text(encoding='utf-8'))
        self.assertEqual(['extract-stable-collection-sheet.py'], sheet_owners)

    def test_the_gradle_script_has_balanced_braces(self):
        self.assertEqual(0, gradle_brace_depth(self.build))
        self.assertEqual(0, gradle_brace_depth('val a = { "x}" } // {\n/* { */ val b = """ { """'))
        self.assertEqual(1, gradle_brace_depth('val a = {'))

    def test_the_hand_written_jvm_class_has_every_case_and_balanced_delimiters(self):
        text = text_of(JVM_TEST)
        self.assertNotIn('\r', text)
        self.assertEqual(11, text.count('@Test'))
        self.assertEqual(11, len(re.findall(r'(?m)^    fun \w+\(', text)))
        self.assertEqual(1, text.count('package com.bilipai.desktop.ui\n'))
        self.assertEqual(1, text.count('\nclass DesktopEpisodeSelectorRepinTest {\n'))
        for forbidden in ('java.awt', 'ImageComposeScene', 'java.net.http', 'HttpURLConnection', 'NetworkModule', 'File(',
                          'runComposeUiTest', 'org.junit'):
            self.assertNotIn(forbidden, text)
        balanced(self, self.sync, text)
        # Every declaration it calls is one the producers emit or a pinned direct file, imported exactly once.
        for token in ('Page', 'UgcEpisode', 'UgcEpisodeArc', 'PagesSelectorPresentation', 'filterPageIndicesForSelector',
                      'resolveCollectionEpisodeAbsoluteTimeText', 'resolvePageChapterGroupKey', 'resolvePageSelectorGroups',
                      'resolvePagesSelectorBottomContentPaddingDp', 'resolvePagesSelectorLayoutPolicy', 'shouldShowPagesExpandAction'):
            self.assertEqual(1, len(re.findall(r'(?m)^import .*\.' + token + r'$', text)), token)



class GeneratedOutputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-ves-')
        cls.root = Path(cls.temp.name)
        cls.sync = load('sync-upstream.py', 'ves_tokens')
        cls.sources, cls.raws, cls.manifest = episode.fixed(REPO)
        cls.controls = load('extract-upstream-video-player-full-controls.py', 'ves_controls')
        cls.sheet = load('extract-stable-collection-sheet.py', 'ves_sheet')
        cls.units = load('extract-upstream-video-detail-full-units.py', 'ves_units')
        cls.content = load('extract-upstream-video-content-full.py', 'ves_content')
        cls.run_all(cls.root / 'real', with_tests=True)
        with cls.baseline():
            cls.run_all(cls.root / 'base', with_tests=False)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @classmethod
    @contextmanager
    def baseline(cls):
        """The producers with this slice's selections, advances, leaves and call-site edits patched out."""
        with patch.object(episode, 'selected_source', lambda path, predecessor, repo=None: predecessor), \
                patch.object(episode, 'advance_video_detail_response', lambda repo, predecessor: predecessor), \
                patch.object(episode, 'emit_collection_leaves', lambda repo, output, records: records), \
                patch.object(episode, 'emit_original_tests', lambda repo, test_output: []), \
                patch.object(episode, 'CONTENT_PAGES_AFTER', episode.CONTENT_PAGES_BEFORE), \
                patch.object(episode, 'OVERLAY_PAGES_AFTER', episode.OVERLAY_PAGES_BEFORE), \
                patch.object(episode, 'TABLET_PAGES_AFTER', episode.TABLET_PAGES_BEFORE), \
                patch.object(episode, 'CINEMA_PAGES_AFTER', episode.CINEMA_PAGES_BEFORE), \
                patch.object(episode, 'AUDIO_PAGES_AFTER', episode.AUDIO_PAGES_BEFORE):
            yield

    @classmethod
    def run_all(cls, base, with_tests):
        with patch('sys.stdout', new_callable=io.StringIO):
            cls.controls.generate(REPO, base / 'controls', False, (base / 'controls-tests') if with_tests else None)
            cls.sheet.generate(REPO, base / 'sheet')
            cls.units.generate(REPO, base / 'units')
            cls.content.generate(REPO, base / 'content')
        run_script_producer('tablet', base / 'tablet')
        run_script_producer('audio', base / 'audio')

    def real(self, *parts):
        return self.root.joinpath('real', *parts)

    def base(self, *parts):
        return self.root.joinpath('base', *parts)

    def test_the_part_selector_is_the_whole_v046_file_with_only_the_windows_display_metrics_rewrites(self):
        generated = text_of(self.real('controls', PAGES_OUTPUT))
        removed, added = changed_lines(self.sources['PagesSelector.kt'], generated)
        self.assertEqual(PAGES_REMOVED, removed)
        self.assertEqual(PAGES_ADDED, added)
        # Every line of the file that is not a rewrite is the upstream line, in order.
        self.assertEqual(self.sources['PagesSelector.kt'].count('\n') - 1, generated.count('\n'), 'four lines rewritten by three')
        self.assertNotIn('import android.', generated)
        self.assertNotIn('Configuration.ORIENTATION', generated)
        self.assertEqual(2, generated.count('PageCoverList('))
        for word in episode.COLLECTION_IN_PAGES_SELECTOR:
            self.assertNotIn(word, generated)
        for token in ('fun PagesSelector(', 'coverUrl: String = ""', 'isPlaying: Boolean = false',
                      'internal object PagePreviewNestedScrollConnection', 'private fun PagesHorizontalPreviewRow(',
                      'private fun PlayingEqualizerIcon(', 'private fun PagesSelectorFilterBar('):
            self.assertEqual(1, generated.count(token), token)

    def test_the_controls_producer_differs_from_the_baseline_only_in_the_selector_and_the_overlay_call(self):
        real, base = tree(self.real('controls')), tree(self.base('controls'))
        self.assertEqual(set(base), set(real), 'no output is added or removed in the controls producer')
        differing = sorted(name for name in real if real[name] != base[name])
        # The overlay edit is the LAST layer of that output (after the NVIDIA and audio overlay layers and after the follow
        # confirmation), so the follow confirmation proof is byte for byte the proof of the run without this slice and still
        # describes the text it received. Only the sources, their readback and the selector proof of the page row move.
        # The player overlay re-pin (v046_player_overlay) applies its own proof AFTER this slice's overlay edit, so its proof of
        # that file records the edited predecessor and differs as well.
        self.assertEqual(sorted([PAGES_OUTPUT, OVERLAY_OUTPUT, 'v037-complete-controls-source-readback.json',
                                 'v046-player-overlay-proofs/VideoPlayerOverlay.kt.json']), differing)
        proof_name = 'v046-follow-confirmation-proofs/VideoPlayerOverlay.kt.json'
        self.assertEqual(real[proof_name], base[proof_name])
        proof = json.loads(real[proof_name].decode('utf8'))
        overlay = text_of(self.real('controls', OVERLAY_OUTPUT))
        self.assertNotEqual(sha(overlay), proof['outputSHA256'], 'the proof describes the text before the cover edit')
        removed, added = changed_lines(text_of(self.base('controls', OVERLAY_OUTPUT)), overlay)
        self.assertEqual([], removed)
        self.assertEqual(['                            coverUrl = coverUrl,'], added)
        # Peeling the one added line gives back exactly the text the proof recorded as its output. The control-center
        # hunks (plr-control-center-motion) are applied after this edit, so they are peeled first.
        import v046_control_center_motion as control_center
        peeled = overlay
        for hunk in reversed(control_center.OVERLAY_HUNKS):
            self.assertEqual(1, peeled.count(hunk['after']), hunk['label'])
            peeled = peeled.replace(hunk['after'], hunk['before'], 1)
        self.assertEqual(proof['outputSHA256'], sha(peeled.replace(episode.OVERLAY_PAGES_AFTER, episode.OVERLAY_PAGES_BEFORE)))
        readback = json.loads(text_of(self.real('controls', 'v037-complete-controls-source-readback.json')))
        row = next(item for item in readback['outputs'] if item['path'] == PAGES_OUTPUT)
        self.assertEqual('complete-original-v046-pages-selector-global-context-adapt', row['mode'])
        self.assertEqual('complete-original-menu-sheet-global-context-adapt',
                         next(item for item in readback['outputs'] if item['path'] == COMPONENTS + 'QualityMenu.kt')['mode'])
        labels = [item['label'] for item in readback['adaptations']]
        self.assertEqual(1, labels.count(episode.CALL_SITE_LABELS['overlay']))

    def test_the_overlay_call_is_the_v046_call_and_its_cover_is_in_scope(self):
        overlay = text_of(self.real('controls', OVERLAY_OUTPUT))
        self.assertEqual(1, overlay.count(episode.OVERLAY_PAGES_AFTER))
        self.assertEqual(0, overlay.count(episode.OVERLAY_PAGES_BEFORE))
        upstream = upstream_blob(episode.APP + 'feature/video/ui/overlay/VideoPlayerOverlay.kt').decode('utf8')
        self.assertEqual(1, upstream.count(episode.OVERLAY_PAGES_AFTER), 'the same lines exist at v0.4.6')
        # `coverUrl` is the overlay state value declared before the part page sheet in the same function.
        self.assertEqual(1, overlay.count('    val coverUrl = state.coverUrl\n'))
        self.assertLess(overlay.index('val coverUrl = state.coverUrl'), overlay.index(episode.OVERLAY_PAGES_AFTER))

    def test_the_large_screen_callers_pass_the_video_cover_exactly_as_v046_does(self):
        # The TabletVideoLayout call sits in ScrollableVideoInfoSection, which TabletVideoInfoPane renders for
        # LargeScreenVideoLayout on a wide, horizontally adapted window (the 4K and 5K path). TabletCinemaLayout (no caller in
        # the pinned tree yet) and AudioModeMusicPlayer are compiled too: all three are main source roots of the Windows build
        # (generated/video-tablet-full and generated/video-audio-full), so each must make the v0.4.6 call.
        build = text_of(REPO / 'desktop/build.gradle.kts')
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/video-tablet-full/com"))'))
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/video-audio-full/com"))'))
        for producer in ('tablet', 'audio'):
            real, base = tree(self.real(producer)), tree(self.base(producer))
            self.assertEqual(set(base), set(real), 'no output is added or removed in the ' + producer + ' producer')
            sources = sorted(name for name in real if name.startswith('com/') and name.endswith('.kt'))
            expected = sorted(SCREEN + name for prod, name, _key, _line in LARGE_SCREEN_SITES if prod == producer)
            if producer == 'audio':
                # The listen closure slice (v046_audio_listen_closure) replaces the audio mode player by the WHOLE v0.4.6
                # original, which already makes the cover call: with or without this slice the Kotlin is the same.
                expected = []
            differing = sorted(name for name in sources if real[name] != base[name])
            self.assertEqual(expected, differing, producer + ': only the call-site files differ in their Kotlin')
            # Every proof of the layers below this slice is byte for byte the proof of the run without it (last layer).
            # The listen closure proofs of the audio producer record their predecessor, which does differ.
            proofs = [name for name in real if name.endswith('proof.json')]
            if producer != 'audio':
                self.assertEqual([], [name for name in proofs if real[name] != base[name]])
        for producer, name, key, line in LARGE_SCREEN_SITES:
            with self.subTest(file=name):
                before_anchor, after_anchor = getattr(episode, key.upper() + '_PAGES_BEFORE'), getattr(episode, key.upper() + '_PAGES_AFTER')
                generated = text_of(self.real(producer, SCREEN + name))
                baseline = text_of(self.base(producer, SCREEN + name))
                removed, added = changed_lines(baseline, generated)
                self.assertEqual([], removed)
                if producer == 'audio':
                    # whole v0.4.6 original in both runs (see above): the call is there without this slice's own edit
                    self.assertEqual([], added)
                    self.assertEqual(1, baseline.count(after_anchor))
                else:
                    self.assertEqual([line], added)
                    self.assertEqual(1, baseline.count(before_anchor))
                self.assertEqual(1, generated.count(after_anchor))
                self.assertEqual(0, generated.count(before_anchor))
                self.assertEqual(1, generated.count('PagesSelector('), 'exactly one part selector call')
                # The very same lines exist at v0.4.6: the part list falls back to this cover for a part without first_frame.
                upstream = upstream_blob(episode.APP + 'feature/video/screen/' + name).decode('utf8')
                self.assertEqual(1, upstream.count(after_anchor), 'the same lines exist at v0.4.6')
                self.assertEqual(0, upstream.count(before_anchor))
                self.assertIn(line.strip(), [row.strip() for row in upstream.split('\n')])
                balanced(self, self.sync, generated)
        # The cover value is in scope at each call: the tablet column takes the video info, the cinema panel the success state.
        tablet = text_of(self.real('tablet', SCREEN + 'TabletVideoLayout.kt'))
        function_start = tablet.rindex('private fun ScrollableVideoInfoSection(', 0, tablet.index(episode.TABLET_PAGES_AFTER))
        self.assertIn('info: com.android.purebilibili.data.model.response.ViewInfo,', tablet[function_start:tablet.index(') {', function_start)])
        cinema = text_of(self.real('tablet', SCREEN + 'TabletCinemaLayout.kt'))
        function_start = cinema.rindex('private fun CinemaMetaPanel(', 0, cinema.index(episode.CINEMA_PAGES_AFTER))
        self.assertIn('    success: VideoPlaybackUiState.Success,\n', cinema[function_start:cinema.index(') {', function_start)])
        audio = text_of(self.real('audio', SCREEN + 'AudioModeMusicPlayer.kt'))
        self.assertEqual(1, audio.count('    val info = successState.info\n'))
        self.assertLess(audio.index('val info = successState.info'), audio.index(episode.AUDIO_PAGES_AFTER))
        # Recorded as counted adaptations with their labels, one each, and the audio whole-body inverse still passes.
        tablet_rows = json.loads(text_of(self.real('tablet', 'tablet-adaptations.json')))
        audio_rows = json.loads(text_of(self.real('audio', 'audio-adaptations.json')))
        for rows, name, key in ((tablet_rows, 'TabletVideoLayout.kt', 'tablet'), (tablet_rows, 'TabletCinemaLayout.kt', 'cinema'),
                                (audio_rows, 'AudioModeMusicPlayer.kt', 'audio')):
            entries = [item for item in rows['feature/video/screen/' + name] if item['label'] == episode.CALL_SITE_LABELS[key]]
            self.assertEqual(1, len(entries), name)
            self.assertEqual(getattr(episode, key.upper() + '_PAGES_BEFORE'), entries[0]['before'])
            self.assertEqual(getattr(episode, key.upper() + '_PAGES_AFTER'), entries[0]['after'])
        base_rows = json.loads(text_of(self.base('tablet', 'tablet-adaptations.json')))
        self.assertEqual(sorted(episode.CALL_SITE_LABELS[key] for key in ('tablet', 'cinema')),
                         sorted(item['label'] for rows in tablet_rows.values() for item in rows
                                if item['label'] in episode.CALL_SITE_LABELS.values()))
        # The run without the slice patches the after text to the before text, so its rows are identities (nothing changed).
        self.assertEqual([], [item for rows in base_rows.values() for item in rows
                              if item['label'] in episode.CALL_SITE_LABELS.values() and item['before'] != item['after']])

    def test_the_large_screen_edits_fail_closed_when_an_anchor_is_missing_or_not_unique(self):
        for producer, name, key, _line in LARGE_SCREEN_SITES:
            attribute = key.upper() + '_PAGES_BEFORE'
            for label, anchor in (('missing', '    // an anchor that is not in the file\n'), ('duplicated', '    ')):
                with self.subTest(file=name, anchor=label), tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
                    with patch.object(episode, attribute, anchor), patch.object(episode, key.upper() + '_PAGES_AFTER', anchor + '// x\n'):
                        with self.assertRaises(AssertionError):
                            run_script_producer(producer, Path(temp) / 'o')

    def test_the_original_nested_scroll_test_is_written_only_under_the_test_root_and_unchanged(self):
        target = self.real('controls-tests', episode.RELATIVE['PagePreviewNestedScrollTest.kt'])
        self.assertEqual(self.raws['PagePreviewNestedScrollTest.kt'], target.read_bytes())
        self.assertEqual(3, text_of(target).count('@Test'))
        self.assertEqual([], [p for p in self.real('controls').rglob('PagePreviewNestedScrollTest.kt')])
        self.assertFalse(self.base('controls-tests').exists(), 'the baseline run has no test root')
        # The verify script regenerates without a test root: it must still agree with the real output directory.
        verifier = load('verify-upstream-video-player-full-controls.py', 'ves_verify_controls')
        report = self.root / 'controls-report.json'
        with patch('sys.stdout', new_callable=io.StringIO):
            verifier.verify(REPO, self.real('controls'), report)
        self.assertTrue(json.loads(text_of(report))['passed'])

    def test_the_collection_sheet_is_the_whole_v046_file_with_only_the_seven_windows_hunks(self):
        generated = text_of(self.real('sheet', SHEET_OUTPUT))
        header, body = generated.split('\n', 2)[:2], generated.split('\n', 2)[2]
        # The header names the file the body really is (the archive leaf) and the vendored path it replaces.
        self.assertEqual(['// GENERATED from ' + episode.ARCHIVE + '/CollectionSheet.kt (replaces ' +
                          episode.UPSTREAM_PATHS['CollectionSheet.kt'] + '); do not edit.',
                          '// LF-normalized SHA-256: ' + sha(self.raws['CollectionSheet.kt'])], header)
        removed, added = changed_lines(self.sources['CollectionSheet.kt'], body)
        self.assertEqual(SHEET_REMOVED, removed)
        self.assertEqual(SHEET_ADDED, added)
        self.assertIn('var introCanExpand by remember(ugcSeason.id, collectionIntro) { mutableStateOf(false) }', body)
        self.assertNotIn('LocalContext', body)
        self.assertNotIn('import android.', body)
        self.assertEqual(1, body.count('EpisodeListNavigationActions('))
        # v0.4.6 declares the tab role itself, so the explicit role projection finds nothing to change and writes no proof.
        self.assertEqual(1, body.count('role = com.android.purebilibili.core.ui.components.AppTabRowRole.SECONDARY,'))
        self.assertFalse((self.real('sheet') / 'v037-explicit-tab-role-proofs').exists())
        import v037_tab_roles
        self.assertEqual(self.sources['CollectionSheet.kt'], v037_tab_roles.apply(episode.UPSTREAM_PATHS['CollectionSheet.kt'], self.sources['CollectionSheet.kt']))

    def test_the_part_list_and_the_list_navigation_are_written_unchanged_and_recorded(self):
        for name in episode.VERBATIM_COLLECTION_LEAVES:
            with self.subTest(leaf=name):
                self.assertEqual(self.raws[name], self.real('sheet', episode.RELATIVE[name]).read_bytes())
        records = json.loads(text_of(self.real('sheet', 'source-bindings.json')))
        by_output = {row['output'].replace(chr(92), '/'): row for row in records}
        self.assertEqual(len(records), len(by_output))
        sheet = by_output[SHEET_OUTPUT]
        self.assertEqual(sha(self.raws['CollectionSheet.kt']), sheet['originalSha256Lf'])
        self.assertEqual(self.manifest['files'][[r['filename'] for r in self.manifest['files']].index('CollectionSheet.kt')]['gitBlob'],
                         sheet['upstreamGitBlob'])
        self.assertEqual(episode.PREDECESSOR_SHA256['CollectionSheet.kt'], sheet['vendoredSha256Lf'])
        self.assertTrue(sheet['wholeOriginal'])
        self.assertEqual(sha(self.real('sheet', SHEET_OUTPUT).read_bytes()), sheet['generatedSha256'])
        for name in episode.VERBATIM_COLLECTION_LEAVES:
            row = by_output[episode.RELATIVE[name]]
            self.assertEqual(episode.UPSTREAM_PATHS[name], row['original'])
            self.assertEqual(sha(self.raws[name]), row['generatedSha256'])
            self.assertEqual(episode.MANIFEST_SHA256, row['sourceManifestSha256'])
            self.assertTrue(row['wholeOriginal'])
        # Nothing else of the sheet producer moved.
        real, base = tree(self.real('sheet')), tree(self.base('sheet'))
        self.assertEqual({episode.RELATIVE[name] for name in episode.VERBATIM_COLLECTION_LEAVES}, set(real) - set(base))
        # The only file that disappears is the tab role proof of the old v0.2.5 text: v0.4.6 needs no projection.
        self.assertEqual({'v037-explicit-tab-role-proofs/DesktopOriginalCollectionSheet.kt.json'}, set(base) - set(real))
        differing = sorted(name for name in set(base) & set(real) if real[name] != base[name])
        self.assertEqual(sorted([SHEET_OUTPUT, 'source-bindings.json']), differing)

    def test_the_detail_response_is_the_v046_file_and_only_the_first_frame_changed_from_the_v031_body(self):
        generated = self.real('units', DETAIL_OUTPUT).read_bytes()
        self.assertEqual(self.raws['VideoDetailResponse.kt'], generated)
        previous = self.base('units', DETAIL_OUTPUT).read_bytes().decode('utf8')
        self.assertEqual(episode.PREDECESSOR_SHA256['VideoDetailResponse.kt'], sha(previous))
        removed, added = changed_lines(previous, generated.decode('utf8'))
        self.assertEqual(['    val duration: Long = 0'], removed)
        self.assertEqual(['    val duration: Long = 0,', '    @SerialName("first_frame") val firstFrame: String = "",'], added)
        rows = json.loads(text_of(self.real('units', 'source-bindings.json')))['outputs']
        row = next(item for item in rows if item['path'] == DETAIL_OUTPUT)
        self.assertEqual('complete-original-v046-episode-selector', row['mode'])
        self.assertEqual(sha(generated), row['sha256LF'])
        self.assertEqual(1, [item['path'] for item in rows].count(DETAIL_OUTPUT))
        base_row = next(item for item in json.loads(text_of(self.base('units', 'source-bindings.json')))['outputs']
                        if item['path'] == DETAIL_OUTPUT)
        self.assertEqual(sha(previous), base_row['sha256LF'], 'the baseline row describes the v0.3.1 body')
        # Nothing else of the detail units producer moved.
        real, base = tree(self.real('units')), tree(self.base('units'))
        self.assertEqual(set(base), set(real))
        differing = sorted(name for name in real if real[name] != base[name])
        self.assertIn(DETAIL_OUTPUT, differing)
        self.assertEqual(set(), set(differing) - {DETAIL_OUTPUT, 'source-bindings.json', 'v037-detail-complete-output-readback.json'})

    def test_the_detail_page_passes_the_cover_and_the_playing_state_exactly_as_v046_does(self):
        generated = text_of(self.real('content', CONTENT_OUTPUT))
        base = text_of(self.base('content', CONTENT_OUTPUT))
        removed, added = changed_lines(base, generated)
        self.assertEqual([], removed)
        self.assertEqual(['                coverUrl = info.pic,', '                isPlaying = isVideoPlaying,'], added)
        self.assertEqual(1, generated.count(episode.CONTENT_PAGES_AFTER))
        carried = text_of(REPO / 'desktop/upstream-slices/v046-comment-list/VideoContentSection.kt')
        self.assertEqual(1, carried.count(episode.CONTENT_PAGES_AFTER), 'the carried v0.4.6 file makes the same call')
        self.assertEqual(carried, upstream_blob(episode.APP + 'feature/video/screen/VideoContentSection.kt').decode('utf8'))
        # `info` and `isVideoPlaying` are parameters of the function that makes the call (the collection row above it reads both).
        function_start = generated.rindex('private fun VideoHeaderContent(', 0, generated.index(episode.CONTENT_PAGES_AFTER))
        header = generated[function_start:generated.index(') {', function_start)]
        self.assertIn('info: ViewInfo', header)
        self.assertIn('isVideoPlaying: Boolean', header)
        self.assertLess(generated.index('isPlaying = isVideoPlaying', function_start), generated.index(episode.CONTENT_PAGES_AFTER))
        # Only that one file of the content producer moved, and its verify script still agrees.
        real_tree, base_tree = tree(self.real('content')), tree(self.base('content'))
        self.assertEqual(set(base_tree), set(real_tree))
        differing = sorted(name for name in real_tree if real_tree[name] != base_tree[name])
        self.assertIn(CONTENT_OUTPUT, differing)
        self.assertEqual([], [name for name in differing if name.endswith('.kt') and name != CONTENT_OUTPUT])
        # The cover edit is the LAST layer of that output: the comment callers proof of v046_comment_list is byte for byte the
        # proof of the run without this slice, and its recorded output is the text with the one cover edit peeled off.
        proof_name = 'v046-content-comment-callers-proof.json'
        self.assertNotIn(proof_name, differing)
        proof = json.loads(real_tree[proof_name].decode('utf8'))
        self.assertEqual(sha(generated.replace(episode.CONTENT_PAGES_AFTER, episode.CONTENT_PAGES_BEFORE)),
                         proof['retainedToCurrent']['afterSHA256'])
        self.assertNotEqual(sha(generated), proof['retainedToCurrent']['afterSHA256'])
        verifier = load('verify-upstream-video-content-full.py', 'ves_verify_content')
        with patch('sys.stdout', new_callable=io.StringIO):
            verifier.verify(REPO, self.real('content'))

    def test_the_call_site_edits_fail_closed_when_an_anchor_of_the_producer_text_moves(self):
        controls = load('extract-upstream-video-player-full-controls.py', 'ves_controls_anchor')
        controls.ADAPT = []
        with self.assertRaises(AssertionError):
            controls.adapt('no call here', episode.OVERLAY_PAGES_BEFORE, episode.OVERLAY_PAGES_AFTER, 'x')
        with self.assertRaises(AssertionError):
            controls.adapt(episode.OVERLAY_PAGES_BEFORE * 2, episode.OVERLAY_PAGES_BEFORE, episode.OVERLAY_PAGES_AFTER, 'x')
        content = load('extract-upstream-video-content-full.py', 'ves_content_anchor')
        content.ADAPT = []
        with self.assertRaises(AssertionError):
            content.adapt(episode.CONTENT_PAGES_AFTER, episode.CONTENT_PAGES_BEFORE, episode.CONTENT_PAGES_AFTER, 'x')
        self.assertEqual(episode.CONTENT_PAGES_AFTER,
                         content.adapt(episode.CONTENT_PAGES_BEFORE, episode.CONTENT_PAGES_BEFORE, episode.CONTENT_PAGES_AFTER, 'x'))

    def test_every_new_top_level_name_has_one_definer_among_the_generated_outputs_and_none_in_the_owned_sources(self):
        generated = {}
        for producer in ('controls', 'sheet', 'units', 'content'):
            for path in sorted((self.real(producer)).rglob('*.kt')):
                generated[producer + '/' + path.relative_to(self.real(producer)).as_posix()] = text_of(path)
        owned = {p.name: text_of(p) for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt')}
        for pattern in NEW_NAMES:
            declaration = re.compile(r'(?m)^[ \t]*(?:(?:internal|private|public|inline)\s+)*' + pattern.lstrip('^'))
            with self.subTest(name=pattern):
                definers = [path for path, text in generated.items() if declaration.search(text)]
                self.assertEqual(1, len(definers), definers)
                self.assertEqual([], [path for path, text in owned.items() if declaration.search(text)])
        self.assertEqual(1, len([p for p, text in generated.items() if re.search(r'(?m)^data class Page\(', text)]))
        tests = [p for p in self.real('controls-tests').rglob('*.kt') if 'class PagePreviewNestedScrollTest' in text_of(p)]
        self.assertEqual(1, len(tests))

    def test_no_import_of_the_original_files_is_lost_except_the_android_ones_the_rewrites_replace(self):
        allowed = {
            'PagesSelector.kt': {'import android.content.res.Configuration', 'import androidx.compose.ui.platform.LocalConfiguration'},
            'CollectionSheet.kt': {'import androidx.compose.ui.platform.LocalContext', 'import com.android.purebilibili.core.ui.AppModalBottomSheet',
                                   'import com.android.purebilibili.core.store.SettingsManager'},
        }
        generated = {'PagesSelector.kt': text_of(self.real('controls', PAGES_OUTPUT)), 'CollectionSheet.kt': text_of(self.real('sheet', SHEET_OUTPUT))}
        for name, text in generated.items():
            original = {line for line in self.sources[name].split('\n') if line.startswith('import ')}
            kept = {line for line in text.split('\n') if line.startswith('import ')}
            self.assertEqual(allowed[name], original - kept, name)
        for name in episode.VERBATIM_COLLECTION_LEAVES:
            self.assertEqual(self.sources[name], text_of(self.real('sheet', episode.RELATIVE[name])))

    def test_the_generated_files_have_balanced_delimiters(self):
        for path in (self.real('controls', PAGES_OUTPUT), self.real('controls', OVERLAY_OUTPUT), self.real('sheet', SHEET_OUTPUT),
                     self.real('units', DETAIL_OUTPUT), self.real('content', CONTENT_OUTPUT),
                     self.real('controls-tests', episode.RELATIVE['PagePreviewNestedScrollTest.kt'])):
            with self.subTest(file=path.name):
                balanced(self, self.sync, text_of(path))
        for name in episode.VERBATIM_COLLECTION_LEAVES:
            balanced(self, self.sync, text_of(self.real('sheet', episode.RELATIVE[name])))

    def test_a_second_run_is_byte_identical_and_overlap_with_the_archive_is_rejected(self):
        archive = REPO / episode.ARCHIVE
        before = {p.name: p.read_bytes() for p in archive.iterdir()}
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.sheet.generate(REPO, archive / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.units.generate(REPO, archive / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.content.generate(REPO, archive / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.controls.generate(REPO, self.root / 'elsewhere', False, archive / 'tests')
        for producer in ('tablet', 'audio'):
            with self.subTest(producer=producer), self.assertRaisesRegex(ValueError, 'overlaps'):
                run_script_producer(producer, archive / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            episode.validate_outputs(REPO, archive)
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            episode.validate_outputs(REPO, REPO / 'desktop/upstream-slices')
        self.assertEqual(before, {p.name: p.read_bytes() for p in archive.iterdir()})
        self.assertFalse((self.root / 'elsewhere').exists(), 'the controls producer validates before it writes anything')
        # A second run of EVERY producer that carries a layer of this slice reproduces its first output byte for byte.
        snapshot = {key: tree(self.real(key)) for key in ('controls', 'sheet', 'units', 'content', 'controls-tests', 'tablet', 'audio')}
        with patch('sys.stdout', new_callable=io.StringIO):
            self.controls.generate(REPO, self.real('controls'), False, self.real('controls-tests'))
            self.sheet.generate(REPO, self.real('sheet'))
            self.units.generate(REPO, self.real('units'))
            self.content.generate(REPO, self.real('content'))
        run_script_producer('tablet', self.real('tablet'))
        run_script_producer('audio', self.real('audio'))
        for key in snapshot:
            with self.subTest(producer=key):
                self.assertEqual(snapshot[key], tree(self.real(key)))

    def test_a_leaf_that_is_already_claimed_or_not_a_regular_file_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
            out = Path(temp) / 'o'
            claimed = [dict(output=episode.RELATIVE['PageCoverList.kt'])]
            with self.assertRaisesRegex(ValueError, 'already emitted'):
                episode.emit_collection_leaves(REPO, out, claimed)
            self.assertEqual([dict(output=episode.RELATIVE['PageCoverList.kt'])], claimed)
            self.assertFalse(out.exists(), 'nothing is written when any leaf is already claimed')
            blocker = out / episode.RELATIVE['EpisodeListNavigationActions.kt']
            blocker.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                episode.emit_collection_leaves(REPO, out, [])
            self.assertEqual([], [p for p in out.rglob('*') if p.is_file()])
            tests = Path(temp) / 't'
            (tests / episode.RELATIVE['PagePreviewNestedScrollTest.kt']).mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                episode.emit_original_tests(REPO, tests)
        with tempfile.TemporaryDirectory(prefix='bp-ves-') as temp:
            rows = episode.emit_collection_leaves(REPO, Path(temp), [])
            self.assertEqual([episode.RELATIVE[name] for name in episode.VERBATIM_COLLECTION_LEAVES], [row['output'] for row in rows])
            self.assertEqual([(Path(temp) / episode.RELATIVE['PagePreviewNestedScrollTest.kt']).resolve()],
                             [Path(target).resolve() for target in episode.emit_original_tests(REPO, temp)])


if __name__ == '__main__':
    unittest.main()
