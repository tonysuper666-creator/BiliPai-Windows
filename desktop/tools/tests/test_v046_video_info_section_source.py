"""Source checks for roadmap slice vid-info-section-repin. No Gradle, no JVM, no network.

Covers the seven whole v0.4.6 files of desktop/upstream-slices/v046-video-info-section, their selection by the detail units
producer (information section, owner statistics row, topic pattern, selectable title, action row, count text, triple press),
the stable comment refresh box, the first-batch playback plan, the retired Windows hunks and the registration. The Kotlin
is not compiled here: the producers run for real into short temporary directories and the generated text is checked.
"""
from pathlib import Path
import hashlib
import importlib.util
import io
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
import v046_video_info_section as info
import v037_player_interaction as interaction
import v036_video_comment_viewport as viewport

PKG = 'com/android/purebilibili/'
SECTION = PKG + 'feature/video/ui/section/'
INFO_OUTPUT = SECTION + 'DesktopOriginalVideoInfoSection.kt'
POLICY_OUTPUT = SECTION + 'DesktopOriginalVideoInfoDisplayPolicy.kt'
ACTION_OUTPUT = SECTION + 'DesktopOriginalVideoActionSection.kt'
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopVideoInfoRepinTest.kt'
FEATURE = 'v046-video-info-section'
POST_LOAD_PATH = info.UPSTREAM_PATHS['PlaybackPostLoadPlan.kt']

# The Windows rewrites of an information function, in producer order. Every other line of the v0.4.6 function is kept.
INFO_REWRITES = (
    ('(String, android.os.Bundle?) -> Unit', '(String, Long) -> Unit'),
    ('com.android.purebilibili.core.store.SettingsManager', 'SettingsManager'),
    ('val context = LocalContext.current', 'val context = LocalDesktopOriginalVideoInfoBindings.current.context'),
    ('getPlayerControlVisibilitySettings(LocalContext.current)',
     'getPlayerControlVisibilitySettings(LocalDesktopOriginalVideoInfoBindings.current.context)'),
    ('ImageRequest.Builder(LocalContext.current)', 'ImageRequest.Builder(LocalPlatformContext.current)'),
    ('VideoRepository.getCreatorCardStats(info.owner.mid)',
     'LocalDesktopOriginalVideoInfoBindings.current.getCreatorCardStats(info.owner.mid)'),
    ('InlineBgmSection(', 'DesktopOriginalInlineBgmSection('),
)
SELECTED_FUNCTIONS = ('VideoTitleSection', 'VideoDetailSponsorLabelChip', 'VideoTitleWithDesc', 'VideoDescriptionAndTags',
                      'UpInfoSection', 'DescriptionSection', 'OwnerStatsRow')
POLICY_OWNED_ELSEWHERE = ('resolveVideoHonorChipText', 'resolveVideoHonorJumpUrl', 'shouldShowCreatorTeamSection',
                          'resolveVideoDetailBadges', 'resolveCompactPublishTimeRowText')
# Top-level names that this slice brings into the compile universe: each must have exactly one definer.
NEW_NAMES = (
    r'fun VideoDescriptionAndTags\(', r'fun OwnerStatsRow\(', r'fun resolveVideoDetailOwnerStatsLabels\(',
    r'class VideoActionCountRequests\b', r'fun shouldAnimateVideoActionCountChange\(', r'fun VideoActionCountText\(',
    r'class VideoTriplePressState\b', r'fun rememberVideoTriplePressState\(', r'fun resolveVideoTripleHoldProgress\(',
    r'const val VIDEO_TRIPLE_HOLD_MILLIS\b', r'fun buildPlaybackPostLoadPlan\(', r'data class PlaybackPostLoadTaskSpec\b',
    r'enum class PlaybackPostLoadTask\b',
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


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def upstream_blob(path):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob', info.COMMIT + ':' + path],
                          capture_output=True, check=True).stdout


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


class ArchiveAndWiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sync = load('sync-upstream.py', 'vis_sync')
        cls.sources, cls.raws, cls.manifest = info.fixed(REPO)
        cls.build = text_of(REPO / 'desktop/build.gradle.kts')
        cls.catalog = json.loads(text_of(REPO / 'desktop/tools/compatibility-source-inputs.json'))

    def copy_archive(self, temp):
        target = Path(temp) / info.ARCHIVE
        shutil.copytree(REPO / info.ARCHIVE, target)
        return target

    def test_every_leaf_the_manifest_the_license_and_an_extra_leaf_reject_tampering(self):
        for name in sorted(info.EXPECTED) + ['manifest.json', 'LICENSE']:
            with self.subTest(changed=name), tempfile.TemporaryDirectory(prefix='bp-vis-') as temp:
                archive = self.copy_archive(temp)
                with open(archive / name, 'ab') as handle:
                    handle.write(b' ')
                with self.assertRaises(ValueError):
                    info.fixed(Path(temp))
        for name in sorted(info.EXPECTED) + ['LICENSE']:
            with self.subTest(removed=name), tempfile.TemporaryDirectory(prefix='bp-vis-') as temp:
                archive = self.copy_archive(temp)
                (archive / name).unlink()
                with self.assertRaises(ValueError):
                    info.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-vis-') as temp:
            archive = self.copy_archive(temp)
            (archive / 'Extra.kt').write_bytes(b'package x\n')
            with self.assertRaises(ValueError):
                info.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-vis-') as temp:
            archive = self.copy_archive(temp)
            (archive / 'VideoInfoSection.kt').write_bytes(self.raws['VideoInfoSection.kt'].replace(b'\n', b'\r\n'))
            with self.assertRaises(ValueError):
                info.fixed(Path(temp))
        with self.assertRaises(ValueError):
            info.fixed(Path(tempfile.gettempdir()) / 'bp-vis-missing-repo')

    def test_declaration_pins_fail_closed_when_a_pinned_declaration_moves(self):
        for name, pins in info.DECLARATIONS.items():
            for token, count in pins:
                with self.subTest(leaf=name, token=token), patch.dict(info.DECLARATIONS, {name: ((token, count + 1),)}):
                    with self.assertRaises(ValueError):
                        info.fixed(REPO)

    def test_the_archive_is_the_exact_upstream_blob_set(self):
        if not commit_present(info.COMMIT):
            self.fail('the upstream commit is missing from this clone: ' + info.COMMIT)
        self.assertEqual(info.COMMIT, self.manifest['commit'])
        self.assertEqual(sorted(info.UPSTREAM_PATHS.values()), sorted(row['path'] for row in self.manifest['files']))
        for row in self.manifest['files']:
            with self.subTest(leaf=row['filename']):
                blob = upstream_blob(row['path'])
                self.assertEqual(blob, self.raws[row['filename']])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
        self.assertEqual(info.MANIFEST_SHA256, sha((REPO / info.ARCHIVE / 'manifest.json').read_bytes()))
        self.assertEqual(upstream_blob('LICENSE'), (REPO / info.ARCHIVE / 'LICENSE').read_bytes())

    def test_the_selection_hands_three_whole_files_to_the_producers_and_keeps_the_view_model_on_the_seed(self):
        for name in info.SELECTED_LEAVES:
            path = info.UPSTREAM_PATHS[name]
            with self.subTest(leaf=name):
                self.assertIn(path, interaction.SELECTED_SOURCE_FILES)
                identity = interaction.selected_source_identity(path)
                self.assertEqual(self.sources[name], identity['text'])
                self.assertEqual(sha(self.sources[name]), identity['sha256LF'])
                self.assertEqual(info.COMMIT, identity['upstreamCommit'])
                self.assertEqual(info.MANIFEST_SHA256, identity['sourceManifestSha256'])
                self.assertEqual(info.ARCHIVE + '/' + name, identity['localPath'])
                row = next(r for r in self.manifest['files'] if r['filename'] == name)
                self.assertEqual(row['gitBlob'], identity['gitBlob'])
        # The three files are never read from the v0.3.7 seed again, whose copies stay byte for byte in the old archive.
        seed = interaction.fixed_sources()
        for name in ('VideoInfoSection.kt', 'VideoActionSection.kt'):
            self.assertNotEqual(seed[name], self.sources[name])
        vm = 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'
        vm_identity = interaction.selected_source_identity(vm)
        self.assertEqual(seed['VideoEngagementViewModel.kt'], vm_identity['text'])
        self.assertEqual(interaction.COMMIT, vm_identity['upstreamCommit'])
        self.assertIsNone(info.selected_source_identity(vm))
        self.assertIsNone(interaction.selected_source_identity(info.UPSTREAM_PATHS['PlaybackPostLoadPlan.kt']))

    def test_the_v046_text_carries_every_feature_of_the_seven_covered_entries(self):
        info_text = self.sources['VideoInfoSection.kt']
        # v042-description-topic-tags: the pattern ends at punctuation and the topic is not underlined.
        self.assertIn('Regex("""#[^#\\s，。,.!?！？；;:：、]+#?""")', info_text)
        self.assertIn('val underline: Boolean = true', info_text)
        self.assertIn('underline = false', info_text)
        # v039/v040: selectable title (no tap-to-copy), a separate expand arrow, tap-expand on the whole title row.
        self.assertEqual(2, info_text.count('tapToCopyEnabled = false,'))
        self.assertIn('SelectionContainer(modifier = Modifier.weight(1f))', info_text)
        self.assertIn('onClickLabel = if (expanded) "收起简介" else "展开简介"', info_text)
        # v042-video-owner-stats: counts first, rendered by OwnerStatsRow.
        self.assertIn('resolveVideoDetailOwnerStatsLabels(', info_text)
        self.assertEqual(2, info_text.count('OwnerStatsRow(labels = upStatsLabels)'))
        # v038: intent-first expand and the follow button that crossfades instead of an empty placeholder.
        self.assertIn('var localExpanded by remember(info.bvid) { mutableStateOf<Boolean?>(null) }', info_text)
        self.assertIn('label = "videoDetailFollowLabel"', info_text)
        policy = self.sources['VideoInfoDisplayPolicy.kt']
        self.assertIn('followerCount?.takeIf { it >= 0 }', policy)
        action = self.sources['VideoActionSection.kt']
        # v038 (buttons from the first frame) and v043 (count motion, triple press state machine).
        self.assertNotIn('showIcon', action)
        self.assertIn('rememberVideoTriplePressState(info.bvid, likeStatusKnown)', action)
        self.assertEqual(3, action.count('countAnimationRequest = countRequests.'), 'the like, coin and favorite buttons')
        self.assertIn('Crossfade(', action)
        plan = self.sources['PlaybackPostLoadPlan.kt']
        self.assertEqual(2, plan.count('delayMs = 0L'))
        self.assertEqual(2, plan.count('delayMs = 450L'))
        self.assertIn('ComfortablePullToRefreshBox(', self.sources['VideoCommentRefreshContainer.kt'])

    def test_registration_catalog_gradle_and_digest_agree(self):
        archive_rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(info.ARCHIVE + '/')]
        self.assertEqual(sorted(info.UPSTREAM_PATHS.values()), sorted(row['path'] for row in archive_rows))
        by_leaf = {row['filename']: row for row in self.manifest['files']}
        for row in archive_rows:
            leaf = Path(row['localPath']).name
            with self.subTest(leaf=leaf):
                self.assertFalse(row['referenceOnly'])
                self.assertEqual([FEATURE], row['features'])
                self.assertEqual('raw', row['hashNormalization'])
                self.assertEqual('adaptedSource', row['kind'])
                self.assertEqual(info.COMMIT, row['upstreamCommit'])
                self.assertEqual(by_leaf[leaf]['sha256'], row['sha256'])
                self.assertEqual(by_leaf[leaf]['gitBlob'], row['blob'])
                self.assertEqual(leaf not in ('VideoActionCountText.kt', 'VideoTriplePressState.kt', 'VideoCommentRefreshContainer.kt',
                                              'PlaybackPostLoadPlan.kt'), row['partialReuse'])
                self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(info.ARCHIVE))
        archives = [row for row in self.catalog['archives'] if row['directory'] == info.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({info.ARCHIVE + '/' + name for name in info.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = self.sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(archive_rows, [row for row in loaded if row['localPath'].startswith(info.ARCHIVE + '/')])
        # The archive directory appears in the build script only through the two producer tasks of one trailing block.
        self.assertEqual(1, self.build.count('inputs.dir("upstream-slices/v046-video-info-section")'))
        self.assertEqual(1, self.build.count('inputs.file("tools/v046_video_info_section.py")'))
        start = self.build.index('inputs.file("tools/v046_video_info_section.py")')
        block_start = self.build.rindex('tasks.matching', 0, start)
        self.assertIn('setOf("extractOriginalVideoDetailUnits", "extractVideoCommentUi")', self.build[block_start:start])
        self.assertEqual([], [row for row in self.build.splitlines() if 'srcDir' in row and 'v046-video-info-section' in row])

    def test_the_vendored_post_load_plan_is_excluded_once_and_nothing_else_emits_the_carried_files(self):
        line = 'exclude(canonicalOriginalIdentity("' + POST_LOAD_PATH + '"))'
        self.assertEqual(1, self.build.count(line))
        prepared = self.build[self.build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {'):
                              self.build.index('\nval extractUpstreamApi ')]
        self.assertEqual(1, prepared.count(line))
        model = load('check_slice_symbol_closure.py', 'vis_gate').parse_gradle(self.build)
        self.assertEqual(1, model.sync_excludes.count(POST_LOAD_PATH))
        registry = json.loads(text_of(REPO / 'desktop/upstream-sources.json'))['sources']
        modes = {row['path']: row.get('mode', 'direct') for row in registry}
        self.assertEqual('direct', modes[POST_LOAD_PATH], 'it is a direct copy that the exclude replaces')
        # Without the exclude the vendored copy and the emitted file would both define the plan.
        vendored = text_of(REPO / POST_LOAD_PATH).replace('\r\n', '\n')
        self.assertEqual(1, vendored.count('internal fun buildPlaybackPostLoadPlan('))
        self.assertEqual(4, vendored.count('delayMs = 450L'))
        self.assertEqual(1, self.sources['PlaybackPostLoadPlan.kt'].count('internal fun buildPlaybackPostLoadPlan('))
        for name in ('VideoActionCountText', 'VideoTriplePressState'):
            owners = [p.name for p in TOOLS.glob('*.py') if name in p.read_text(encoding='utf-8')]
            self.assertEqual(['v046_video_info_section.py'], owners, name)
        # The full owner producer only lists the vendored copy as a skipped direct recipe row (its production run never
        # writes a direct file), so after the exclude the plan has this slice's generator as its only writer.
        owners = sorted(p.name for p in TOOLS.glob('*.py') if 'PlaybackPostLoadPlan' in p.read_text(encoding='utf-8'))
        self.assertEqual(['extract-upstream-video-full-owner.py', 'v046_video_info_section.py'], owners)
        recipes = load('extract-upstream-video-full-owner.py', 'vis_full_owner').RECIPES
        row = next(item for item in recipes if item['originalPath'] == POST_LOAD_PATH)
        self.assertEqual('direct', row['mode'])
        self.assertEqual(row['originalSha256LF'], row['outputSha256LF'])
        # The old v0.3.6 refresh container stays pinned in its archive, but only the v0.4.6 file is written.
        owners = sorted(p.name for p in TOOLS.glob('*.py') if 'VideoCommentRefreshContainer' in p.read_text(encoding='utf-8'))
        self.assertEqual(['v036_video_comment_viewport.py', 'v046_video_info_section.py'], owners)

    def test_the_gradle_script_has_balanced_braces(self):
        # The part B leaf commit once dropped the closing brace of the comment polish block, which would have been a
        # Kotlin script parse error for the whole build. This slice repaired it; keep it from coming back.
        self.assertEqual(0, gradle_brace_depth(self.build))
        self.assertEqual(0, gradle_brace_depth('val a = { "x}" } // {\n/* { */ val b = """ { """'))
        self.assertEqual(1, gradle_brace_depth('val a = {'))

    def test_the_two_retired_windows_hunks_are_gone_with_their_receipts(self):
        follow = load('v046_follow_confirmation.py', 'vis_follow')
        self.assertNotIn(INFO_OUTPUT, follow.SITES)
        self.assertFalse(hasattr(follow, 'INFO'))
        # plr-portrait-story-pager-repin retired the two portrait overlay buttons as well: the whole v0.4.6 overlay is upstream text.
        self.assertEqual({follow.UP_PREVIEW, follow.METADATA, follow.OVERLAY, follow.SPACE}, set(follow.SITES))
        palette = load('v046_identity_badge_palette.py', 'vis_palette')
        self.assertEqual({'DynamicCard.kt', 'SpaceScreen.kt'}, set(palette.UP_NAME_MIGRATIONS))
        producer = text_of(TOOLS / 'extract-upstream-video-detail-full-units.py')
        self.assertNotIn('up_name_vip_pink', producer)
        self.assertNotIn('v046_identity_badge_palette', producer)
        self.assertEqual(1, producer.count('from v046_follow_confirmation import apply_and_record as apply_follow_confirmation'))

    def test_the_hand_written_jvm_class_has_every_case_and_balanced_delimiters(self):
        text = text_of(JVM_TEST)
        self.assertNotIn('\r', text)
        self.assertEqual(12, text.count('@Test'))
        self.assertEqual(12, len(re.findall(r'(?m)^    fun \w+\(', text)))
        self.assertEqual(1, text.count('package com.bilipai.desktop.ui\n'))
        self.assertEqual(1, text.count('\nclass DesktopVideoInfoRepinTest {\n'))
        for forbidden in ('java.awt', 'ImageComposeScene', 'java.net.http', 'HttpURLConnection', 'NetworkModule', 'File(',
                          'runComposeUiTest', 'org.junit'):
            self.assertNotIn(forbidden, text)
        self.balanced(text)
        # Every function it calls is one of the declarations the producers emit.
        for token in ('resolveVideoDetailOwnerStatsLabels', 'buildVideoDescriptionAnnotatedString', 'resolveVideoTripleHoldProgress',
                      'shouldAnimateVideoActionCountChange', 'VideoActionCountRequests', 'VideoTriplePressState',
                      'buildPlaybackPostLoadPlan'):
            self.assertEqual(1, len(re.findall(r'(?m)^import .*\.' + token + r'$', text)), token)

    def balanced(self, source):
        stack = []
        for token, first, _end in self.sync.kotlin_tokens(source):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', ']', '}'):
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)


class GeneratedOutputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-vis-')
        cls.root = Path(cls.temp.name)
        cls.units = load('extract-upstream-video-detail-full-units.py', 'vis_units')
        with patch('sys.stdout', new_callable=io.StringIO):
            cls.units.generate(REPO, cls.root / 'units')
        cls.sources, cls.raws, cls.manifest = info.fixed(REPO)
        cls.sync = load('sync-upstream.py', 'vis_tokens')
        cls.emitted = {name: text_of(cls.root / 'units' / name) for name in (INFO_OUTPUT, POLICY_OUTPUT, ACTION_OUTPUT)}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def function(self, text, name):
        return self.units.func(text, name)

    def rewrite(self, name, body):
        for before, after in INFO_REWRITES:
            body = body.replace(before, after)
        if name == 'VideoTitleWithDesc':
            before = 'SettingsManager\n        .getVideoArgueMsgShown(context)'
            self.assertEqual(1, body.count(before))
            body = body.replace(before, 'com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings\n'
                                        '        .getVideoArgueMsgShown(context)')
        if name == 'UpInfoSection':
            before = '    val playerControlVisibility by'
            self.assertEqual(1, body.count(before))
            body = body.replace(before, '    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current\n' + before)
            body = body.replace('LocalDesktopOriginalVideoInfoBindings.current.getCreatorCardStats(info.owner.mid)',
                                'VideoRepository.getCreatorCardStats(info.owner.mid)')
        return body

    def test_the_three_whole_leaves_are_written_unchanged_and_recorded_once(self):
        readback = json.loads(text_of(self.root / 'units/v037-detail-complete-output-readback.json'))
        rows = {row['path']: row for row in readback['outputs']}
        self.assertEqual(len(readback['outputs']), len(rows))
        for name in info.VERBATIM_LEAVES:
            relative = info.RELATIVE[name]
            with self.subTest(leaf=name):
                self.assertEqual(self.raws[name], (self.root / 'units' / relative).read_bytes())
                row = rows[relative]
                self.assertEqual((info.UPSTREAM_PATHS[name], 'complete-original-v046-video-info-section', info.COMMIT, True),
                                 (row['origin'], row['mode'], row['fixedCommit'], row['generated']))
                self.assertEqual(sha(self.raws[name]), row['sha256LF'])
        # The three selected files are never written whole, and the refresh container belongs to the comment producer.
        written = {p.relative_to(self.root / 'units').as_posix() for p in (self.root / 'units').rglob('*.kt')}
        for name in ('VideoInfoSection.kt', 'VideoInfoDisplayPolicy.kt', 'VideoActionSection.kt', 'VideoCommentRefreshContainer.kt'):
            self.assertNotIn(info.RELATIVE[name], written)

    def test_the_source_bindings_name_the_v046_identities_of_the_selected_files(self):
        bindings = json.loads(text_of(self.root / 'units/source-bindings.json'))
        rows = {row['path']: row for row in bindings['sourceIdentities']}
        for name in info.SELECTED_LEAVES:
            row = rows[info.UPSTREAM_PATHS[name]]
            with self.subTest(leaf=name):
                self.assertEqual(sha(self.sources[name]), row['sha256LF'])
                self.assertEqual(info.COMMIT, row['upstreamCommit'])
                self.assertEqual(info.ARCHIVE + '/' + name, row['localPath'])
        labels = [edit['label'] for edit in bindings['adaptations']]
        self.assertEqual(0, len([label for label in labels if 'VIP colour' in label]))
        for label in ('Windows introduction is always expanded: no statistics-block collapse toggle',
                      'Collapsed inline declarations never show while expanded; the metadata section owns declarations'):
            self.assertEqual(1, labels.count(label), label)

    def test_every_information_function_is_the_v046_function_with_only_the_windows_rewrites(self):
        original = self.sources['VideoInfoSection.kt']
        emitted = self.emitted[INFO_OUTPUT]
        for name in SELECTED_FUNCTIONS:
            with self.subTest(function=name):
                expected = self.rewrite(name, self.function(original, name))
                self.assertEqual(1, emitted.count(expected), 'the emitted file holds the whole rewritten v0.4.6 function once')
        # The head of the file is the v0.4.6 import block and pure helpers with the Android imports swapped for ports.
        head = original[:original.index('private val useMiuixSpring')]
        for line in ('import com.android.purebilibili.data.repository.VideoRepository\n',
                     'import com.android.purebilibili.data.repository.ViewGrpcRepository\n',
                     'import com.android.purebilibili.feature.video.screen.buildVideoNavigationOptions\n',
                     'import com.android.purebilibili.core.store.SettingsManager\n',
                     'import androidx.compose.ui.platform.LocalContext\n'):
            self.assertIn(line, head)
            head = head.replace(line, '')
        head += ('import coil3.compose.LocalPlatformContext\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings\n'
                 'import com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager\n')
        pure = original[original.index('private val useMiuixSpring'):original.index('private const val BGM_DISCOVERY_LOAD_DELAY_MS')]
        self.assertTrue(emitted.startswith(head + pure))
        for needle in ('LocalContext', 'android.os', 'android.content', 'Toast', 'ViewGrpcRepository', 'buildVideoNavigationOptions'):
            self.assertNotIn(needle, emitted)
        self.assertEqual(1, emitted.count('import com.android.purebilibili.core.ui.components.rememberFollowActionWithConfirmation\n'))
        self.assertEqual(1, emitted.count('onClick = rememberFollowActionWithConfirmation('))
        self.assertEqual(1, emitted.count('val secondaryColor = com.android.purebilibili.core.ui.components.userVipPink()\n'))
        self.assertEqual(0, emitted.count('val secondaryColor = MaterialTheme.colorScheme.secondary\n'))
        self.assertEqual(0, emitted.count('resolveUpStatsText'))

    def test_the_windows_introduction_statistics_row_is_derived_from_the_v046_stats_block(self):
        original = self.sources['VideoInfoSection.kt']
        body = self.function(original, 'VideoTitleWithDesc')
        time_a = '    val publishTimeRowText = remember(info.pubdate, info.tname, info.title) {'
        time_b = '    val videoBadges = remember(info.isUpowerExclusive, info.isUpowerPreview, info.isCooperation) {'
        stats_a, stats_b = '        // Stats row\n', '        if (videoBadges.isNotEmpty()) {'
        for anchor in (time_a, time_b, stats_a, stats_b):
            self.assertEqual(1, body.count(anchor), anchor)
        time = body[body.index(time_a):body.index(time_b)]
        stats = body[body.index(stats_a):body.index(stats_b)]
        for target in ('viewsModifier', 'danmakuModifier'):
            start = stats.index('                if (metadataSharedEnabled) {')
            end = stats.index('                Row(verticalAlignment = Alignment.CenterVertically, modifier = ' + target + ') {', start)
            stats = stats[:start] + stats[end:]
        removals = (
            ('        trailingStatsContent?.invoke()\n', ''),
            ('.copyOnClick(info.bvid, "BV号")', '.clickable(onClick = onCopyBvid)'),
            ('            modifier = Modifier.weight(1f).clickable(\n                onClickLabel = if (expanded) "收起简介" else "展开简介",\n'
             '                onClick = toggleExpanded,\n            ),\n', '            modifier = Modifier.weight(1f),\n'),
            ('        if (inlineDeclarations) {\n            Spacer(Modifier.height(4.dp))\n'
             '            if (argueMsg.isNotBlank()) VideoArgueMsgRow(argueMsg = argueMsg)\n'
             '            if (argueMsg.isNotBlank() && noReprint) Spacer(Modifier.height(2.dp))\n'
             '            if (noReprint) VideoArgueMsgRow(argueMsg = "未经作者授权，请勿转载")\n        }\n', ''),
        )
        for before, after in removals:
            self.assertEqual(1, stats.count(before), before)
            stats = stats.replace(before, after)
        expected = ('@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)\n@Composable\n'
                    'internal fun DesktopOriginalVideoStatisticsRow(\n    info: ViewInfo,\n    onlineCount: String,\n'
                    '    showOnlineCount: Boolean,\n    onCopyBvid: () -> Unit,\n) {\n'
                    '    // The existing Windows introduction is already expanded. Keep the complete\n'
                    '    // original statistics/time/BV content without adding another collapse state.\n'
                    '    val expanded = true\n    val animateLayout = false\n' + time +
                    '    Column(modifier = Modifier.fillMaxWidth()) {\n' + stats + '    }\n}\n')
        emitted = self.emitted[INFO_OUTPUT]
        self.assertTrue(emitted.endswith(expected + '\n'), 'the statistics row is the last declaration of the file')
        row = expected
        for undeclared in ('toggleExpanded', 'inlineDeclarations', 'argueMsg', 'noReprint', 'trailingStatsContent', 'copyOnClick'):
            self.assertNotIn(undeclared, row)
        # The mount still reads exactly the four parameters the desktop-owned caller passes.
        caller = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsVideoMetadataSection.kt')
        for argument in ('info = info,', 'onlineCount = success.onlineCount,', 'showOnlineCount = showOnlineCount,', 'onCopyBvid = {'):
            self.assertEqual(1, caller.count(argument), argument)

    def test_the_statistics_row_fails_closed_when_an_anchor_of_the_original_moves(self):
        original = self.sources['VideoInfoSection.kt']
        self.assertTrue(self.units.video_statistics_function(original))
        for anchor in ('        // Stats row\n', '        if (videoBadges.isNotEmpty()) {',
                       '        trailingStatsContent?.invoke()\n', '.copyOnClick(info.bvid, "BV号")',
                       '                onClick = toggleExpanded,\n            ),\n', '        if (inlineDeclarations) {\n'):
            with self.subTest(anchor=anchor):
                self.assertEqual(1, original.count(anchor))
                changed = original.replace(anchor, anchor[:-1] + 'Z' + anchor[-1:], 1)
                with self.assertRaises(AssertionError):
                    self.units.video_statistics_function(changed)
                duplicated = original.replace(anchor, anchor + anchor, 1)
                # A duplicated block opener also unbalances the function the producer carves: still a hard stop.
                with self.assertRaises((AssertionError, IndexError)):
                    self.units.video_statistics_function(duplicated)

    def test_the_policy_keeps_every_declaration_that_no_other_producer_owns_and_gains_the_owner_stats_labels(self):
        policy = self.sources['VideoInfoDisplayPolicy.kt']
        emitted = self.emitted[POLICY_OUTPUT]
        for name in POLICY_OWNED_ELSEWHERE:
            self.assertEqual(1, len(re.findall(r'(?m)^[ \t]*(?:(?:internal|private)\s+)*fun\s+' + name + r'\s*\(', policy)))
            self.assertEqual(0, len(re.findall(r'(?m)^[ \t]*(?:(?:internal|private)\s+)*fun\s+' + name + r'\s*\(', emitted)), name)
        self.assertEqual(1, emitted.count('internal fun resolveVideoDetailOwnerStatsLabels('))
        owned = [name for name in re.findall(r'(?m)^internal fun (\w+)\(', policy) if name not in POLICY_OWNED_ELSEWHERE]
        self.assertIn('resolveVideoDetailOwnerStatsLabels', owned)
        self.assertEqual(owned, re.findall(r'(?m)^internal fun (\w+)\(', emitted))
        # v0.4.6 differs from the v0.2.5 policy the two other producers select their five functions from by exactly the
        # one added block, so those five (and every other declaration) are unchanged complete functions.
        legacy = text_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoDisplayPolicy.kt')
        legacy = legacy.replace('\r\n', '\n')
        block = policy[policy.index('/** 详情页统计数量在前'):policy.index('internal fun resolveVideoDetailOnlineCountText(')]
        self.assertEqual(1, policy.count(block))
        self.assertEqual(legacy, policy.replace(block, '', 1))

    def test_the_action_section_is_the_whole_v046_file_without_the_profile_owned_icon_and_with_the_windows_dark_theme_port(self):
        original = self.sources['VideoActionSection.kt']
        expected = original.replace('com.android.purebilibili.core.ui.isAppInDarkTheme()',
                                    'com.bilipai.desktop.appearance.isDesktopInDarkTheme()', 1)
        expected = self.units.remove_function(expected, 'TripleProgressIcon')
        self.assertEqual(expected, self.emitted[ACTION_OUTPUT])
        emitted = self.emitted[ACTION_OUTPUT]
        self.assertEqual(0, emitted.count('fun TripleProgressIcon('))
        self.assertEqual(1, original.count('fun TripleProgressIcon('))
        for needle in ('VideoActionCountText(', 'rememberVideoTriplePressState(', 'VideoActionCountRequests()'):
            self.assertEqual(1, emitted.count(needle), needle)
        for needle in ('import com.android.purebilibili.feature.video.ui.feedback.VideoActionCountRequests\n',
                       'import com.android.purebilibili.feature.video.ui.feedback.VideoActionCountText\n',
                       'import com.android.purebilibili.feature.video.ui.feedback.rememberVideoTriplePressState\n',
                       'import com.android.purebilibili.core.ui.AppContentColors\n'):
            self.assertEqual(1, emitted.count(needle), needle)
        # The icon the section still calls is the profile producer's copy, with the same parameters.
        profile = text_of(TOOLS / 'extract-upstream-profile-main.py')
        self.assertIn("'TripleProgressIcon'", profile)
        legacy = text_of(REPO / 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoActionSection.kt')
        legacy = legacy.replace('\r\n', '\n')
        header = 'fun TripleProgressIcon(\n'
        for text in (legacy, original):
            start = text.index(header)
            params = text[start:text.index(') {', start)]
            self.assertEqual(['icon', 'text', 'progress', 'progressColor', 'isActive', 'modifier'],
                             re.findall(r'(?m)^    (\w+):', params))

    def test_every_new_top_level_name_has_one_definer_in_the_detail_units_and_none_in_the_owned_sources(self):
        generated = {p.relative_to(self.root / 'units').as_posix(): text_of(p) for p in (self.root / 'units').rglob('*.kt')}
        owned = {p.name: text_of(p) for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt')}
        for pattern in NEW_NAMES:
            declaration = re.compile(r'(?m)^[ \t]*(?:(?:internal|private|public|inline)\s+)*' + pattern)
            with self.subTest(name=pattern):
                definers = [path for path, text in generated.items() if declaration.search(text)]
                self.assertEqual(1, len(definers), definers)
                self.assertEqual([], [path for path, text in owned.items() if declaration.search(text)])

    def test_the_generated_files_have_balanced_delimiters(self):
        for relative, text in self.emitted.items():
            with self.subTest(file=relative):
                self.balanced(text)
        for name in info.VERBATIM_LEAVES:
            self.balanced(text_of(self.root / 'units' / info.RELATIVE[name]))

    def balanced(self, source):
        stack = []
        for token, first, _end in self.sync.kotlin_tokens(source):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', '}', ']'):
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)

    def test_a_second_run_is_byte_identical_and_overlap_with_the_archive_is_rejected(self):
        before = {p.name: p.read_bytes() for p in (REPO / info.ARCHIVE).iterdir()}
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.units.generate(REPO, REPO / info.ARCHIVE / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            info.validate_outputs(REPO, REPO / info.ARCHIVE)
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            info.validate_outputs(REPO, REPO / 'desktop/upstream-slices')
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / info.ARCHIVE).iterdir()})
        snapshot = tree(self.root / 'units')
        with patch('sys.stdout', new_callable=io.StringIO):
            self.units.generate(REPO, self.root / 'units')
        self.assertEqual(snapshot, tree(self.root / 'units'))

    def test_a_leaf_that_is_already_claimed_or_not_a_regular_file_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix='bp-vis-') as temp:
            out = Path(temp) / 'o'
            claimed = [dict(path=info.RELATIVE['VideoTriplePressState.kt'])]
            with self.assertRaisesRegex(ValueError, 'already emitted'):
                info.emit_verbatim_leaves(REPO, out, claimed)
            self.assertEqual([dict(path=info.RELATIVE['VideoTriplePressState.kt'])], claimed)
            self.assertFalse(out.exists(), 'nothing is written when any leaf is already claimed')
            blocker = out / info.RELATIVE['VideoActionCountText.kt']
            blocker.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                info.emit_verbatim_leaves(REPO, out, [])
            self.assertEqual([], [p for p in out.rglob('*') if p.is_file()])

    def test_the_stable_refresh_box_is_the_v046_file_and_the_slots_file_is_unchanged(self):
        with tempfile.TemporaryDirectory(prefix='bp-vis-') as temp:
            out = Path(temp)
            written = viewport.generate_slots(out)
            by_name = {path.name: path for path in written}
            self.assertEqual({'VideoCommentSkeletonSlots.kt', 'VideoCommentRefreshContainer.kt'}, set(by_name))
            self.assertEqual(self.raws['VideoCommentRefreshContainer.kt'], by_name['VideoCommentRefreshContainer.kt'].read_bytes())
            self.assertEqual(info.refresh_container_bytes(REPO), by_name['VideoCommentRefreshContainer.kt'].read_bytes())
            self.assertEqual((REPO / 'desktop/upstream-slices/v036-video-comment-viewport/VideoCommentSkeletonSlots.kt').read_bytes(),
                             by_name['VideoCommentSkeletonSlots.kt'].read_bytes())
            self.assertEqual('com/android/purebilibili/feature/video/screen/VideoCommentRefreshContainer.kt',
                             by_name['VideoCommentRefreshContainer.kt'].relative_to(out).as_posix())
        # The older v0.3.6 file is still pinned and read, just no longer written.
        old = viewport.fixed_sources()['app/src/main/java/com/android/purebilibili/feature/video/screen/VideoCommentRefreshContainer.kt']
        self.assertNotEqual(old.encode(), self.raws['VideoCommentRefreshContainer.kt'])
        self.assertIn('AdaptivePullToRefreshBox(', old)
        # The producer validates the archive before it writes anything.
        producer = text_of(TOOLS / 'extract-upstream-video-comment-ui.py')
        self.assertLess(producer.index('validate_video_info_outputs(repo,output)'), producer.index('shared=mod('))
        self.assertEqual(1, producer.count('validate_video_info_outputs(repo,output)'))


if __name__ == '__main__':
    unittest.main()
