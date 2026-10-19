"""Source checks for roadmap slice dmk-command-merge-hot-banners. No Gradle, no JVM, no network.

The archive v046-danmaku-hot-command carries whole v0.4.6 files (hot banner policy, bar, count text and policy test; the
command overlay and its policy test; the video action feedback policy). Every check here runs the REAL producers into
a short temporary root and reads their output; the Kotlin is neither compiled nor run.
"""
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_danmaku_hot_command as gen  # noqa: E402
import v029_command_vote as vote  # noqa: E402
import v037_command_overlay as v037  # noqa: E402

PKG = 'com/android/purebilibili/'
BAR = PKG + 'feature/video/ui/overlay/HotDanmakuBar.kt'
POLICY = PKG + 'feature/video/danmaku/HotDanmakuPolicy.kt'
COUNT = PKG + 'core/ui/components/AnimatedCountText.kt'
CONFIRMATION = PKG + 'feature/video/ui/components/DanmakuSameSendConfirmation.kt'
SECTION = PKG + 'feature/video/ui/section/VideoPlayerSection.kt'
DESKTOP_UI = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui'
DESKTOP_TESTS = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop'


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def text_of(path):
    return Path(path).read_bytes().decode('utf8').replace('\r\n', '\n')


def sha(value):
    return hashlib.sha256(value.encode('utf8')).hexdigest()


def sync_module():
    return load('dm_sync', 'sync-upstream.py')


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-dm-')
        root = cls.root = Path(cls.temp.name)
        cls.hot = load('dm_hot', 'extract-upstream-hot-danmaku.py')
        cls.votes = load('dm_votes', 'extract-stable-video-votes.py')
        cls.section = load('dm_section', 'extract-upstream-video-player-section-full.py')
        cls.units = load('dm_units', 'extract-upstream-video-detail-full-units.py')
        cls.hot.generate(REPO, root / 'hot', root / 'hot-tests')
        cls.votes.generate(REPO, root / 'votes')
        cls.section.generate(REPO, root / 'section', False, root / 'section-tests')
        cls.units.generate(REPO, root / 'units')
        cls.sources, cls.manifest = gen.fixed(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def hot_text(self, relative):
        return text_of(self.root / 'hot' / relative)


class ArchiveTests(unittest.TestCase):
    def copy(self, temp):
        repo = Path(temp)
        shutil.copytree(REPO / gen.ARCHIVE, repo / gen.ARCHIVE)
        return repo

    def test_archive_matches_the_upstream_objects_and_the_pins(self):
        sources, manifest = gen.fixed(REPO)
        self.assertEqual(gen.EXPECTED, set(sources))
        self.assertEqual(gen.COMMIT, manifest['commit'])
        self.assertEqual(gen.MANIFEST_SHA256, hashlib.sha256((REPO / gen.ARCHIVE / 'manifest.json').read_bytes()).hexdigest())
        self.assertEqual({'hot/' + leaf for leaf in gen.HOT_LEAVES} |
                         {'votes/DesktopOriginalCommandDanmakuOverlay.kt', 'units/VideoEngagementViewModel.kt'},
                         set(manifest['beforeFinalOutputs']))

    def test_every_leaf_the_manifest_the_licence_and_an_extra_leaf_are_pinned(self):
        for leaf in sorted(gen.EXPECTED | {'manifest.json', 'LICENSE'}):
            with tempfile.TemporaryDirectory(prefix='bp-dm-a-') as temp:
                repo = self.copy(temp)
                target = repo / gen.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError, msg=leaf):
                    gen.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-dm-a-') as temp:
            repo = self.copy(temp)
            (repo / gen.ARCHIVE / 'Extra.kt').write_text('package x\n')
            with self.assertRaises(ValueError):
                gen.fixed(repo)

    def test_output_overlapping_the_archive_is_rejected_before_any_write(self):
        child = REPO / gen.ARCHIVE / 'child'
        for call in (lambda: gen.validate_outputs(REPO, child), lambda: gen.validate_outputs(REPO, REPO / 'desktop'),
                     lambda: load('dm_hot_o', 'extract-upstream-hot-danmaku.py').generate(REPO, child)):
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                call()
        self.assertFalse(child.exists())
        for producer, hook in {
            'extract-upstream-hot-danmaku.py': 'validate_outputs(repo, output, tests_output)',
            'extract-stable-video-votes.py': 'validate_outputs(repo,output)',
            'extract-upstream-video-player-section-full.py': 'validate_outputs(REPO,OUTPUT,test_output)',
            'extract-upstream-video-detail-full-units.py': 'validate_outputs(REPO,OUTPUT)'}.items():
            self.assertEqual(1, text_of(TOOLS / producer).count(hook), producer)


class HotBannerTests(Fixture):
    def test_every_hot_file_is_the_whole_v046_original_and_both_receipts_recover_the_exact_stages(self):
        proof = json.loads(text_of(self.root / 'hot/source-proof.json'))
        rows = {Path(row['source']).name: row for row in proof['files']}
        self.assertEqual(5, len(rows))
        for leaf in gen.HOT_LEAVES:
            row = rows[leaf]
            advance = row['v046Advance']
            self.assertEqual(gen.COMMIT, advance['upstreamCommit'])
            self.assertEqual(gen.MANIFEST_SHA256, advance['sourceManifestSHA256'])
            root = self.root / ('hot-tests' if row['testSource'] else 'hot')
            raw = text_of(root / row['generatedPath'])
            self.assertTrue(raw.startswith(gen.hot_prefix(row['source'])))
            body = raw[len(row['generatedPrefix']):]
            self.assertEqual(advance['adaptedSHA256'], sha(body))
            self.assertEqual(sha(raw), row['generatedSha256Bytes'])
            # whole original recoverable from the output
            self.assertEqual(self.sources[leaf], gen.restore(body, advance['originalToWindows']))
            self.assertEqual(sha(self.sources[leaf]), advance['originalSHA256'])
            # the v027 stage below it is recovered exactly and equals the pinned predecessor
            predecessor = gen.restore(body, advance['retainedToCurrent'])
            self.assertEqual(self.manifest['beforeFinalOutputs']['hot/' + leaf]['sha256'], sha(predecessor))
            self.assertEqual(sha(predecessor), row['adaptedFullFileSha256LF'])
            # every Windows edit is one exact counted replacement
            for edit in advance['windowsEdits']:
                self.assertEqual(1, body.count(edit['after']), edit['label'])
        untouched = rows['DanmakuSameSendConfirmation.kt']
        self.assertNotIn('v046Advance', untouched)
        self.assertTrue(untouched['generatedPrefix'].startswith('// Fixed original ' + self.hot.COMMIT))

    def test_policy_and_policy_test_are_the_untouched_v046_files(self):
        self.assertEqual(self.sources['HotDanmakuPolicy.kt'], self.hot_text('com/android/purebilibili/feature/video/danmaku/HotDanmakuPolicy.kt').split('\n', 2)[2])
        test = text_of(self.root / 'hot-tests/com/android/purebilibili/feature/video/danmaku/HotDanmakuPolicyTest.kt')
        self.assertEqual(self.sources['HotDanmakuPolicyTest.kt'], test.split('\n', 2)[2])
        self.assertEqual(8, self.sources['HotDanmakuPolicyTest.kt'].count('@Test'))
        self.assertIn('minimumGapPx', self.sources['HotDanmakuPolicy.kt'])
        self.assertNotIn('spacingPx', self.sources['HotDanmakuPolicy.kt'])
        self.assertEqual([], gen.HOT_PORTS['HotDanmakuPolicy.kt'] + gen.HOT_PORTS['HotDanmakuPolicyTest.kt'])

    def test_bar_has_one_mode_measures_itself_and_takes_mouse_input_only_on_its_entries(self):
        bar = self.hot_text(BAR)
        for retired in ('expandedMode', 'SettingsManager', 'LocalContext', 'collectAsStateWithLifecycle', 'horizontalScroll',
                        'onVisibilityChange', 'HOT_DANMAKU_BAR_HEIGHT_DP', 'import android.', 'androidx.media3'):
            self.assertNotIn(retired, bar, retired)
        self.assertIn('onPresentationChange: (List<DanmakuItem>, Int) -> Unit', bar)
        self.assertIn('import com.bilipai.desktop.ui.DesktopHotDanmakuPlayer as Player', bar)
        self.assertEqual(1, bar.count('.desktopCommandHitRegion("hot-danmaku-item-" + item.danmakuId)'))
        self.assertEqual(1, bar.count('import com.bilipai.desktop.ui.desktopCommandHitRegion\n'))
        self.assertIn('Arrangement.SpaceEvenly', bar)
        self.assertEqual(1, bar.count('blurPadding = 1.dp,'))

    def test_count_text_keeps_the_v027_blur_port_and_gains_the_blur_padding_parameter(self):
        count = self.hot_text(COUNT)
        self.assertIn('blurPadding: Dp = 4.dp,', count)
        self.assertIn('desktopDetailRenderEffectsSupported()', count)
        self.assertIn('edgeTreatment = TileMode.Decal', count)
        for retired in ('Build.VERSION', 'createBlurEffect', 'import android.'):
            self.assertNotIn(retired, count)

    def test_the_selected_entries_hide_through_the_windows_selection_and_the_overlay_keeps_its_schedule(self):
        overlay = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt')
        self.assertEqual(1, overlay.count('internal fun updateHotBarPresentation('))
        self.assertNotIn('updateHotBarReservation', overlay)
        self.assertEqual(1, overlay.count('}.filter {hidden.isVisible(it.comment)}'))
        self.assertEqual(1, overlay.count('DesktopHotDanmakuSelection.of(items)'))
        # the filter runs on the frame the scheduler already built, so hidden items keep their slot and lifetime
        scheduler = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuScheduler.kt')
        self.assertNotIn('DesktopHotDanmakuSelection', scheduler)
        selection = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotDanmakuSelection.kt')
        self.assertIn('comment.originalRenderItem?.mergedOriginalText ?: comment.text', selection)
        self.assertEqual(1, selection.count('danmakuId !in ids && (text == null || text !in texts)'))

    def test_both_hot_mounts_use_the_presentation_callback_and_no_expanded_mode_flow_remains(self):
        host = text_of(DESKTOP_UI / 'DesktopWindowsHotDanmakuHost.kt')
        self.assertEqual(1, host.count('onPresentationChange={items,heightPx->relay.report(items,heightPx.toFloat())},'))
        self.assertEqual(1, host.count('latestOverlay.updateHotBarPresentation(lease.sourceVersion,reservation,items,heightPx)}}'))
        self.assertEqual(1, host.count('DesktopHotDanmakuBindings(lease,::owned)'))
        self.assertNotIn('onVisibilityChange', host)
        self.assertNotIn('import androidx.compose.ui.Modifier', host)
        self.assertNotIn('Modifier', host)
        platform = text_of(DESKTOP_UI / 'DesktopOriginalVideoSectionWindowsPlatform.kt')
        self.assertEqual(1, platform.count('DesktopHotDanmakuBindings(lease,::current)'))
        self.assertEqual(1, platform.count('override fun updateHotDanmakuPresentation(items:List<DanmakuItem>,heightPx:Int)'))
        self.assertNotIn('reserveHotDanmakuBarHeight', platform)
        self.assertEqual(1, platform.count('resources.overlay.updateHotBarPresentation(lease.sourceVersion,token,items,heightPx)}}'))
        self.assertEqual(1, platform.count('hotRelay?.report(items,heightPx.toFloat())'))

    def test_a_rejected_presentation_report_is_offered_again_in_both_mounts(self):
        # HotDanmakuBar deduplicates identical reports and DanmakuOverlay.updateHotBarPresentation answers false when the
        # document or reservation is not current, so each mount keeps the newest report until the overlay took it.
        relay = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotPresentationRelay.kt')
        for token in ('fun report(items: List<DanmakuItem>, heightPx: Float): Boolean', 'fun retry(): Boolean', 'fun resend(): Boolean',
                      'suspend fun redeliverWhileWaiting(intervalMs: Long = RETRY_INTERVAL_MS)'):
            self.assertEqual(1, relay.count(token), token)
        # a throwing delivery is not stored: the value is stored only after deliver returned
        report = relay[relay.index('fun report('):relay.index('/** Offers the newest report again')]
        self.assertLess(report.index('val accepted = deliver(items, heightPx)'), report.index('this.items = items'))
        host = text_of(DESKTOP_UI / 'DesktopWindowsHotDanmakuHost.kt')
        platform = text_of(DESKTOP_UI / 'DesktopOriginalVideoSectionWindowsPlatform.kt')
        for name, body in (('host', host), ('platform', platform)):
            self.assertEqual(1, body.count('DesktopHotPresentationRelay {items,heightPx->'), name)
            self.assertEqual(1, body.count('relay.resend()'), name)
            self.assertEqual(1, body.count('LaunchedEffect(relay) {relay.redeliverWhileWaiting()}'), name)
        # the new reservation (reserveHotBar) comes first, then the resend; a rejected report is retried while owned
        for body, reserve in ((host, 'overlay.reserveHotBar(lease.sourceVersion,reservation,0f)'),
                              (platform, 'resources.overlay.reserveHotBar(lease.sourceVersion,token,0f)')):
            self.assertLess(body.index(reserve), body.index('relay.resend()'))
        self.assertEqual(1, platform.count('hotRelay=relay'))
        self.assertIn('{hotReservation=null;hotRelay=null}', platform)
        port = text_of(DESKTOP_UI / 'DesktopOriginalVideoSectionPlatform.kt')
        self.assertEqual(1, port.count('fun updateHotDanmakuPresentation(items: List<DanmakuItem>, heightPx: Int)'))
        self.assertNotIn('reserveHotDanmakuBarHeight', port)
        for name in ('DesktopHotDanmakuPlatform.kt', 'DesktopOriginalDanmakuSettingsHost.kt'):
            self.assertNotIn('xpandedMode', text_of(DESKTOP_UI / name), name)
        for path in (REPO / 'desktop/src/main').rglob('*.kt'):
            self.assertNotIn('HotDanmakuExpandedMode', path.read_bytes().decode('utf8'), str(path))

    def test_generated_preferences_no_longer_carry_the_expanded_key(self):
        settings = load('dm_settings', 'extract-upstream-danmaku-settings.py')
        settings.generate(REPO, self.root / 'settings')
        text = text_of(self.root / 'settings/com/bilipai/desktop/settings/DesktopOriginalDanmakuPreferences.kt')
        for retired in ('hot_danmaku_expanded_mode', 'HotDanmakuExpandedMode', 'KEY_HOT_DANMAKU_EXPANDED_MODE'):
            self.assertNotIn(retired, text)
        self.assertIn('getDanmakuHotBarEnabled', text)
        self.assertIn('setDanmakuHotBarEnabled', text)

    def test_share_catalog_imports_the_full_merge_window_and_not_the_retired_key(self):
        catalog = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsShareWindowsCatalog.kt')
        self.assertEqual(1, catalog.count('?.intOrNull?.coerceIn(1_000, 15_000)?.let(::JsonPrimitive)'))
        self.assertNotIn('intRange(danmaku, "danmaku_duplicate_merge_window_ms"', catalog)
        self.assertNotIn('hot_danmaku_expanded_mode"', catalog.replace('hot_danmaku_expanded_mode left the Android', ''))
        default = text_of(REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt')
        self.assertIn('duplicateMergeWindowMs: Int = 15_000', default)
        self.assertIn('duplicateMergeWindowMs.coerceIn(1_000, 15_000)', default)
        # the merger itself is already the v0.4.6 blob (anchored window, summed likes)
        carried = (REPO / 'desktop/upstream-slices/v038-danmaku-merge/DanmakuMerger.kt').read_bytes()
        self.assertEqual('08193bae66d713359c226c43d328262a7dabffad',
                         hashlib.sha1(b'blob ' + str(len(carried)).encode() + b'\0' + carried).hexdigest())
        self.assertIn('mergedText.likeCount = batch.sumOf { it.likeCount }', carried.decode('utf8'))
        self.assertIn('val firstItem = currentBatch.first()', carried.decode('utf8'))

    def test_drifted_predecessors_and_anchors_fail_closed(self):
        with self.assertRaises(ValueError):
            gen.advance_hot(REPO, 'HotDanmakuBar.kt', 'package foreign\n')
        with self.assertRaises(ValueError):
            gen.advance_command_overlay(REPO, 'package foreign\n')
        original = self.sources['HotDanmakuBar.kt']
        for old, new, label in gen.HOT_PORTS['HotDanmakuBar.kt']:
            with self.assertRaises(ValueError):
                gen._edits(original.replace(old, 'changed', 1), gen.HOT_PORTS['HotDanmakuBar.kt'])
            with self.assertRaises(ValueError):
                gen._edits(original + old, gen.HOT_PORTS['HotDanmakuBar.kt'])


class CommandCardTests(Fixture):
    def overlay(self):
        return text_of(self.root / 'votes' / gen.OVERLAY_RELATIVE)

    def test_the_overlay_is_the_whole_v046_file_with_the_same_hash_pinned_vote_ports(self):
        proof = json.loads(text_of(self.root / 'votes' / gen.OVERLAY_PROOF))
        raw = self.overlay()
        self.assertTrue(raw.startswith(proof['header']))
        body = raw[len(proof['header']):]
        self.assertEqual(proof['adaptedSHA256'], sha(body))
        self.assertEqual(self.sources['CommandDanmakuOverlay.kt'], gen.restore(body, proof['originalToWindows']))
        v037_original, _ = v037.fixed_original(REPO)
        predecessor = gen.restore(body, proof['retainedToCurrent'])
        self.assertEqual(vote.adapt_overlay(v037_original, []), predecessor)
        self.assertEqual(self.manifest['beforeFinalOutputs']['votes/DesktopOriginalCommandDanmakuOverlay.kt']['sha256'], sha(predecessor))
        # the header names the v0.4.6 identity and keeps the carried v0.3.7 layer as its predecessor
        row = next(r for r in self.manifest['files'] if r['filename'] == 'CommandDanmakuOverlay.kt')
        self.assertIn('Fixed upstream ' + gen.COMMIT + '; raw SHA-256: ' + row['sha256'] + '; Git blob: ' + row['gitBlob'], proof['header'])
        self.assertIn(v037.COMMIT, proof['header'])
        self.assertEqual(1, body.count('.desktopCommandHitRegion(item.id)'))
        self.assertEqual(1, body.count('platform.releasePending(pending) { isLoading = false }'))
        self.assertEqual(1, len(re.findall(r'(?m)^internal fun CommandDanmakuOverlay\(', body)))

    def test_the_cards_read_the_real_state_and_gate_both_actions_on_the_two_guards(self):
        body = self.overlay()
        for token in ('isLiked: Boolean,', 'isCoined: Boolean,', 'isFavorited: Boolean,', 'isFollowSubmitting: Boolean,',
                      'isTripleSubmitting: Boolean,', 'canSubmitVideoFollowAction(followStatusKnown, isFollowing, isFollowSubmitting)',
                      'if (action.shouldFollow && followEnabled) onFollowClick()',
                      'if (action.shouldTriple && tripleEnabled) onTripleClick()',
                      'resolveVideoActionTint(statusKnown && isLiked, activeColor, inactiveColor)', '"三连提交中"', '"处理中"'):
            self.assertIn(token, body, token)
        for retired in ('followStatusKnown) onFollowClick()', 'likeStatusKnown) onTripleClick()'):
            self.assertNotIn(retired, body)

    def test_the_triple_guard_has_exactly_one_definer_next_to_the_follow_guard(self):
        policy = text_of(self.root / 'votes' / gen.TRIPLE_POLICY_RELATIVE)
        declaration = gen.triple_policy(REPO)
        self.assertTrue(policy.endswith('package com.android.purebilibili.feature.video.ui.feedback\n\n' + declaration + '\n'))
        self.assertEqual('internal fun canSubmitVideoTripleAction(\n    statusKnown: Boolean,\n    isLiked: Boolean,\n    isCoined: Boolean,\n'
                         '    isFavorited: Boolean,\n    isSubmitting: Boolean,\n): Boolean = statusKnown && !isSubmitting && !(isLiked && isCoined && isFavorited)',
                         declaration)
        definers = []
        for base in (self.root / 'hot', self.root / 'votes', self.root / 'units', self.root / 'section', REPO / 'desktop/src/main'):
            for path in base.rglob('*.kt'):
                if 'fun canSubmitVideoTripleAction(' in path.read_bytes().decode('utf8'):
                    definers.append(path.name)
        self.assertEqual(['DesktopOriginalTripleActionPolicy.kt'], definers)
        follow = text_of(self.root / 'units/com/android/purebilibili/feature/video/ui/feedback/DesktopOriginalFollowActionPolicy.kt')
        self.assertNotIn('canSubmitVideoTripleAction', follow)

    def test_the_two_hand_ported_policy_cases_equal_the_upstream_bodies(self):
        upstream = self.sources['CommandDanmakuOverlayPolicyTest.kt']
        ported = text_of(DESKTOP_TESTS / 'ui/DesktopV046CommandCardPolicyTest.kt')
        for name in ('attention follow is disabled while unknown submitting or already followed',
                     'partial triple permits retry but pending and complete states block submission'):
            start = upstream.index('    @Test\n    fun `' + name + '`()')
            block = upstream[start:upstream.index('\n    }\n', start) + len('\n    }\n')]
            self.assertIn(block, ported, name)

    def test_native_popup_and_section_forward_the_state_and_the_ui_test_call_is_updated(self):
        votes = text_of(DESKTOP_UI / 'DesktopVideoVotes.kt')
        for token in ('isLiked = attentionOwned && attentionState?.isLiked == true,',
                      'isCoined = attentionOwned && (attentionState?.coinCount ?: 0) > 0,',
                      'isFavorited = attentionOwned && attentionState?.isFavorited == true,',
                      'isFollowSubmitting = attentionOwned && attentionState?.isFollowSubmitting == true,',
                      'isTripleSubmitting = attentionOwned && attentionState?.isTripleSubmitting == true,'):
            self.assertEqual(1, votes.count(token), token)
        wrapper = text_of(DESKTOP_UI / 'DesktopWindowsCommandVotePlatform.kt')
        self.assertEqual(1, wrapper.count('    isLiked: Boolean,\n    isCoined: Boolean,\n    isFavorited: Boolean,\n'))
        self.assertEqual(1, wrapper.count('isFollowSubmitting = isFollowSubmitting, isTripleSubmitting = isTripleSubmitting, modifier = modifier,'))
        binding = text_of(DESKTOP_UI / 'DesktopWindowsCommandAttentionBinding.kt')
        self.assertEqual(1, binding.count('canSubmitVideoTripleAction(captured.likeStatusKnown, captured.isLiked, captured.coinCount > 0,'))
        ui = text_of(DESKTOP_TESTS / 'ui/DesktopWindowsCommandLinkUiTest.kt')
        self.assertEqual(1, ui.count('isFollowSubmitting = false, isTripleSubmitting = false,'))

    def test_section_hunks_are_counted_reversible_and_leave_every_other_byte_alone(self):
        proof = json.loads(text_of(self.root / 'section' / gen.SECTION_PROOF))
        actual = text_of(self.root / 'section' / SECTION)
        self.assertTrue(proof['fullInputInverseRecovered'])
        with tempfile.TemporaryDirectory(prefix='bp-dm-s-') as temp:
            with patch.object(gen, 'advance_section', side_effect=lambda repo, text, output: text):
                self.section.generate(REPO, Path(temp) / 'before')
            before = text_of(Path(temp) / 'before' / SECTION)
            # later producer layers rewrite other parts of the file, so the receipt is a layer receipt and the
            # exact replay of the hunks on the layer-less output is the whole-file check
            self.assertEqual(actual, gen.advance_section(REPO, before, Path(temp)))
        for edit in gen.SECTION_HUNKS:
            self.assertEqual(1, actual.count(edit[1]), edit[2])
            self.assertEqual(0, actual.count(edit[0]) if edit[0] != edit[1] else 0)
        self.assertEqual(1, actual.count('onPresentationChange = danmakuManager::updateHotDanmakuPresentation,'))
        self.assertEqual(1, actual.count('com.bilipai.desktop.ui.DesktopWindowsSectionCommandOverlay('))
        self.assertEqual(['isFollowSubmitting', 'isTripleSubmitting'], proof['notForwardedToTheSection'])
        self.assertNotIn('HotDanmakuBar(\n                            getDanmakuList = { danmakuManager.getHotDanmakuList() },\n                            onVisibilityChange', actual)

    def test_the_view_model_gets_the_flag_both_guards_and_a_flag_release_for_every_exit(self):
        relative = gen.VM_RELATIVE
        actual = text_of(self.root / 'units' / relative)
        proof = json.loads(text_of(self.root / 'units' / gen.VM_PROOF_DIRECTORY / 'VideoEngagementViewModel.kt.json'))
        guard = json.loads(text_of(self.root / 'units/v046-like-write-guard-proofs/VideoEngagementViewModel.kt.json'))
        # the like write guard is the last stage; its own receipt peels it, then this layer's input is exact
        peeled = actual[:guard['offset']] + guard['before'] + actual[guard['offset'] + len(guard['after']):]
        self.assertEqual(proof['outputSHA256'], sha(peeled))
        self.assertEqual(self.manifest['beforeFinalOutputs']['units/VideoEngagementViewModel.kt']['sha256'], proof['inputSHA256'])
        self.assertTrue(proof['fullInputInverseRecovered'])
        self.assertEqual(2, actual.count('canSubmitVideoTripleAction('))
        self.assertEqual(1, actual.count('isSubmitting = state.isTripleSubmitting,'))
        self.assertEqual(1, actual.count('isSubmitting = false,\n                )\n            ) return@withLock'))
        self.assertEqual(1, actual.count('updateOwned { it.copy(isTripleSubmitting = true) }'))
        self.assertEqual(1, actual.count('environment.completeTripleSubmission(_uiState, state.subject)'))
        self.assertEqual(4, actual.count('completeTripleSubmission()'))  # declaration, finally, catch, invokeOnCompletion
        self.assertEqual(1, actual.count('tripleJob.invokeOnCompletion { completeTripleSubmission() }'))
        self.assertEqual(1, actual.count('val tripleJob = try { viewModelScope.launch('))
        # the admitted start happens before the launch, the release for a job cancelled before it starts is registered
        self.assertLess(actual.index('if (!tripleAdmitted) return'), actual.index('val tripleJob = try {'))
        self.assertLess(actual.index('} finally {\n                completeTripleSubmission()'), actual.index('tripleJob.invokeOnCompletion'))
        # fail closed
        self.assertEqual('same', gen.apply_triple_submission(REPO, 'other.kt', 'same', self.root / 'rejected'))
        with self.assertRaises(ValueError):
            gen.apply_triple_submission(REPO, relative, actual, self.root / 'rejected')
        with self.assertRaises(ValueError):
            gen.apply_triple_submission(REPO, relative, peeled.replace('"三连失败"', '"x"', 1), self.root / 'rejected')
        self.assertFalse((self.root / 'rejected').exists())
        environment = text_of(DESKTOP_UI / 'DesktopOriginalVideoEngagementBindings.kt')
        self.assertEqual(1, environment.count('internal fun completeTripleSubmission('))
        self.assertEqual(1, environment.count('current.copy(isTripleSubmitting = false)'))

    def test_producers_are_deterministic(self):
        with tempfile.TemporaryDirectory(prefix='bp-dm-d-') as temp:
            second = Path(temp)
            self.hot.generate(REPO, second / 'hot', second / 'hot-tests')
            self.votes.generate(REPO, second / 'votes')
            for relative in (BAR, COUNT, POLICY, CONFIRMATION):
                self.assertEqual((self.root / 'hot' / relative).read_bytes(), (second / 'hot' / relative).read_bytes())
            for relative in (gen.OVERLAY_RELATIVE, gen.TRIPLE_POLICY_RELATIVE, gen.OVERLAY_PROOF, gen.POLICY_PROOF):
                self.assertEqual((self.root / 'votes' / relative).read_bytes(), (second / 'votes' / relative).read_bytes())


class WiringTests(unittest.TestCase):
    def test_gradle_wires_the_four_producers_once_and_the_archive_is_registered(self):
        build = text_of(REPO / 'desktop/build.gradle.kts')
        block = ('tasks.matching { it.name in setOf(\n    "extractOriginalHotDanmaku", "extractStableVideoVotes",\n'
                 '    "extractOriginalVideoPlayerSectionFull", "extractOriginalVideoDetailUnits",\n) }.configureEach {\n'
                 '    inputs.files("tools/v046_danmaku_hot_command.py", "tools/v044_favorite_detail.py")\n'
                 '    inputs.dir("upstream-slices/v046-danmaku-hot-command")\n}\n')
        self.assertEqual(1, build.count(block))
        self.assertEqual(1, build.count('upstream-slices/v046-danmaku-hot-command'))
        for task in ('extractOriginalHotDanmaku', 'extractStableVideoVotes', 'extractOriginalVideoPlayerSectionFull',
                     'extractOriginalVideoDetailUnits'):
            self.assertEqual(1, build.count('val ' + task + ' by tasks.registering'), task)
        # No canonical direct file was replaced: no new exclude line belongs to this slice.
        for name in ('CommandDanmakuOverlay.kt', 'HotDanmakuBar.kt', 'HotDanmakuPolicy.kt', 'AnimatedCountText.kt'):
            self.assertEqual(0, len(re.findall(r'exclude\(canonicalOriginalIdentity\("[^"]*/' + re.escape(name) + r'"\)\)', build)), name)
        catalog = json.loads(text_of(REPO / 'desktop/tools/compatibility-source-inputs.json'))
        self.assertIn('desktop/' + 'upstream-slices/v046-danmaku-hot-command', catalog['buildInputRoots'])
        archive = next(row for row in catalog['archives'] if row['directory'].endswith('/v046-danmaku-hot-command'))
        self.assertEqual(len(gen.EXPECTED) + 2, len(archive['files']))
        rows = [r for r in sync_module().adapted_compatibility_inputs(REPO)
                if r['localPath'].startswith('desktop/upstream-slices/v046-danmaku-hot-command/')]
        self.assertEqual(len(gen.EXPECTED), len(rows))
        self.assertTrue(all(r['features'] == ['v046-danmaku-hot-command'] for r in rows))
        # CommandDanmakuOverlayPolicyTest is never emitted or compiled: the committed v029 copy stays byte-pinned and the two
        # new v0.4.6 cases are ported by hand into DesktopV046CommandCardPolicyTest, so exactly that row is reference only.
        self.assertEqual({'CommandDanmakuOverlayPolicyTest.kt'}, {Path(r['localPath']).name for r in rows if r['referenceOnly']})
        reference = next(r for r in rows if r['referenceOnly'])
        self.assertFalse(reference['partialReuse'])
        self.assertTrue(reference['usedScope'].startswith('Reference only.'))
        self.assertIn('DesktopV046CommandCardPolicyTest', reference['usedScope'])
        self.assertEqual({'VideoActionFeedbackPolicy.kt'}, {Path(r['localPath']).name for r in rows if r['partialReuse']})
        self.assertEqual(hashlib.sha256((REPO / 'desktop/tools/compatibility-source-inputs.json').read_bytes().replace(b'\r\n', b'\n')).hexdigest(),
                         re.search(r'COMPATIBILITY_CATALOG_SHA256 = "([0-9a-f]{64})"', text_of(TOOLS / 'sync-upstream.py')).group(1))

    def test_balanced_delimiters_in_every_new_or_changed_hand_written_kotlin_file(self):
        syntax = sync_module()
        files = [
            REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotDanmakuSelection.kt',
            REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotPresentationRelay.kt',
            REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt',
            DESKTOP_UI / 'DesktopWindowsHotDanmakuHost.kt', DESKTOP_UI / 'DesktopHotDanmakuPlatform.kt',
            DESKTOP_UI / 'DesktopOriginalVideoSectionPlatform.kt', DESKTOP_UI / 'DesktopOriginalVideoSectionWindowsPlatform.kt',
            DESKTOP_UI / 'DesktopOriginalDanmakuSettingsHost.kt', DESKTOP_UI / 'DesktopOriginalVideoEngagementBindings.kt',
            DESKTOP_UI / 'DesktopVideoVotes.kt', DESKTOP_UI / 'DesktopWindowsCommandVotePlatform.kt',
            DESKTOP_UI / 'DesktopWindowsCommandAttentionBinding.kt',
            REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsShareWindowsCatalog.kt',
            DESKTOP_TESTS / 'danmaku/DesktopHotDanmakuSelectionTest.kt', DESKTOP_TESTS / 'danmaku/DesktopV038DanmakuMergeBridgeTest.kt',
            DESKTOP_TESTS / 'danmaku/DesktopHotPresentationRelayTest.kt',
            DESKTOP_TESTS / 'settings/DesktopSettingsShareMergeWindowTest.kt', DESKTOP_TESTS / 'settings/DesktopDanmakuHotSettingsTest.kt',
            DESKTOP_TESTS / 'ui/DesktopV046CommandCardPolicyTest.kt', DESKTOP_TESTS / 'ui/DesktopWindowsCommandAttentionBindingTest.kt',
            DESKTOP_TESTS / 'ui/DesktopWindowsVideoFeedbackOriginTest.kt', DESKTOP_TESTS / 'ui/DesktopWindowsCommandLinkUiTest.kt',
        ]
        for path in files:
            depth = {'(': 0, '{': 0, '[': 0}
            closing = {')': '(', '}': '{', ']': '['}
            for word, _, _ in syntax.kotlin_tokens(text_of(path)):
                if word in depth:
                    depth[word] += 1
                elif word in closing:
                    depth[closing[word]] -= 1
                    self.assertGreaterEqual(depth[closing[word]], 0, str(path))
            self.assertEqual({'(': 0, '{': 0, '[': 0}, depth, str(path))

    def test_the_gradle_script_has_balanced_delimiters_and_the_comment_polish_block_is_closed(self):
        # A text-only wiring check cannot see an unclosed block; the Kotlin DSL script would not parse (found in review of
        # this slice: a block lost its closing brace three commits earlier and swallowed the next blocks).
        syntax = sync_module()
        build = text_of(REPO / 'desktop/build.gradle.kts')
        depth = {'(': 0, '{': 0, '[': 0}
        closing = {')': '(', '}': '{', ']': '['}
        for word, _, _ in syntax.kotlin_tokens(build):
            if word in depth:
                depth[word] += 1
            elif word in closing:
                depth[closing[word]] -= 1
                self.assertGreaterEqual(depth[closing[word]], 0, 'build.gradle.kts closes a delimiter that was never opened')
        self.assertEqual({'(': 0, '{': 0, '[': 0}, depth, 'build.gradle.kts is not balanced')
        # the exact block that lost its brace: its closing line is present, then a blank line, then the next block comment
        self.assertEqual(1, build.count('    inputs.dir("upstream-slices/v046-comment-polish")\n}\n\n// The v0.4.6 design-system leaf files (part B)'))

    def test_jvm_regression_classes_have_the_declared_number_of_cases(self):
        counts = {
            'danmaku/DesktopHotDanmakuSelectionTest.kt': 4, 'danmaku/DesktopHotPresentationRelayTest.kt': 5,
            'settings/DesktopSettingsShareMergeWindowTest.kt': 4,
            'ui/DesktopV046CommandCardPolicyTest.kt': 3, 'settings/DesktopDanmakuHotSettingsTest.kt': 4,
            'danmaku/DesktopV038DanmakuMergeBridgeTest.kt': 7, 'ui/DesktopWindowsCommandAttentionBindingTest.kt': 16,
        }
        for relative, expected in counts.items():
            body = text_of(DESKTOP_TESTS / relative)
            self.assertEqual(expected, len(re.findall(r'(?m)^\s*@Test\b', body)), relative)
        for relative in counts:
            self.assertNotIn('\r', (DESKTOP_TESTS / relative).read_bytes().decode('utf8'), relative)

    def test_touched_files_have_no_bare_carriage_return_except_the_legacy_crlf_catalog(self):
        paths = [TOOLS / 'v046_danmaku_hot_command.py', TOOLS / 'tests/test_v046_danmaku_hot_command_source.py',
                 REPO / gen.ARCHIVE / 'manifest.json']
        paths += [DESKTOP_UI / name for name in ('DesktopWindowsHotDanmakuHost.kt', 'DesktopHotDanmakuPlatform.kt',
                                                 'DesktopVideoVotes.kt', 'DesktopWindowsCommandVotePlatform.kt')]
        paths += [REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotPresentationRelay.kt',
                  REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotDanmakuSelection.kt',
                  REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/danmaku/DesktopHotPresentationRelayTest.kt',
                  REPO / 'desktop/build.gradle.kts']
        for path in paths:
            self.assertNotIn(b'\r', path.read_bytes(), str(path))
        # DesktopSettingsShareWindowsCatalog.kt is committed with CRLF; it is edited in place and stays uniformly CRLF.
        legacy = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsShareWindowsCatalog.kt').read_bytes()
        self.assertEqual(legacy.count(b'\n'), legacy.count(b'\r\n'))


if __name__ == '__main__':
    unittest.main()
