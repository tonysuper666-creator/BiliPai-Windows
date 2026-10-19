"""Real shared-reply generation and exact original/Windows ownership boundaries."""
from pathlib import Path
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_comment_list as comments
from v046_favorite_editor import restore


def module(filename):
    spec = importlib.util.spec_from_file_location('comment254_'+filename.replace('-', '_'), TOOLS/filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def body(path):
    text = path.read_text(encoding='utf8')
    return text.split('\n', 2)[2] if text.startswith(('// GENERATED ', '// Original source ')) else text


class OriginalCommentListSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temp.name)
        cls.owner = module('extract-upstream-dynamic-reply.py')
        cls.playback_owner = module('extract-upstream-whole-playback-settings.py')
        cls.syntax = module('sync-upstream.py')
        cls.sources, cls.manifest = comments.fixed(REPO)
        cls.owner.generate(REPO, cls.output/'actual')
        cls.playback_owner.main(REPO, cls.output/'playback')
        with patch.object(comments, 'advance_reply_components', side_effect=lambda repo, before, output: before), \
             patch.object(comments, 'advance_subreply_components', side_effect=lambda repo, before, output: before), \
             patch.object(comments, 'advance_dynamic_items', side_effect=lambda repo, before, output: before), \
             patch.object(comments, 'advance_settings', side_effect=lambda repo, before, output, owner: before), \
             patch.object(comments, 'emit_shared', return_value={}), \
             patch('v046_comment_thread.advance', side_effect=lambda repo, before, output: before), \
             patch('v046_comment_polish.advance_inline_entrance', side_effect=lambda repo, before, output: before):
            cls.owner.generate(REPO, cls.output/'before')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def proof(self, name, owner='actual'):
        return json.loads((self.output/owner/(name+'.json')).read_bytes())

    def test_actual_whole_renderer_inverse_preserves_every_other_current_declaration(self):
        path = comments.PACKAGE+'DesktopOriginalReplyComponents.kt'
        current = body(self.output/'actual'/path)
        previous = body(self.output/'before'/path)
        proof = self.proof('v046-reply-list-renderer-proof')
        self.assertEqual(previous, restore(current, proof['retainedToCurrent']))
        selected = comments.declaration(self.syntax, current, 'ReplyItemView')
        self.assertEqual(comments.declaration(self.syntax, self.sources['ReplyComponents.kt'], 'ReplyItemView'),
                         restore(selected, proof['originalToWindows']))
        old_member = comments.declaration(self.syntax, previous, 'ReplyMemberAvatar')
        self.assertEqual(old_member, comments.declaration(self.syntax, current, 'ReplyMemberAvatar'))
        self.assertEqual(old_member, comments.declaration(self.syntax, self.sources['ReplyComponents.kt'], 'ReplyMemberAvatar'))

    def test_real_windows_save_share_block_and_translation_ports_remain_identical(self):
        path = comments.PACKAGE+'DesktopOriginalReplyComponents.kt'
        current = comments.declaration(self.syntax, body(self.output/'actual'/path), 'ReplyItemView')
        previous = comments.declaration(self.syntax, body(self.output/'before'/path), 'ReplyItemView')
        for name in ['launchSaveReplyCommentImage', 'requestSaveReplyCommentImage', 'shareReplyComment', 'blockReplyUser']:
            self.assertEqual(comments.declaration(self.syntax, previous, name),
                             comments.declaration(self.syntax, current, name), name)
        self.assertEqual(comments.translation_job(self.syntax, previous), comments.translation_job(self.syntax, current))
        self.assertIn('platform.isOwned()', current)
        for token in ['Toast.makeText', 'Intent(', 'BlockedUpRepository.getInstance',
                      'CommentGrpcRepository.translateReply', 'rememberStoragePermissionState']:
            self.assertNotIn(token, current)

    def test_complete_avatar_and_motion_sources_keep_the_original_kernel_and_13_cases(self):
        for name in ['NagramChatAvatarPlacement.kt', 'CommentContentReveal.kt', 'CommentCascadingContent.kt']:
            self.assertEqual(self.sources[name], (self.output/'actual'/comments.PACKAGE/name).read_text(encoding='utf8'))
        original = self.sources['CommentAvatarPush.kt']
        actual = (self.output/'actual'/comments.PACKAGE/'DesktopOriginalCommentAvatarPush.kt').read_text(encoding='utf8')
        proof = self.proof('v046-comment-avatar-shared-proof')
        self.assertEqual(original, restore(actual, proof['originalToWindows']))
        self.assertIn('DesktopOriginalReplySettings.getCommentAvatarPushEnabled(platform.context)', actual)
        raw_test = self.sources['CommentAvatarPushPolicyTest.kt']
        emitted = (self.output/'actual/test'/comments.PACKAGE/'V046CommentAvatarPushOriginalPolicyTest.kt').read_text(encoding='utf8')
        self.assertEqual(13, emitted.count('@Test'))
        self.assertEqual(raw_test, emitted.replace('class V046CommentAvatarPushOriginalPolicyTest {',
                                                   'class CommentAvatarPushPolicyTest {', 1))
        self.assertIn('SPDX-License-Identifier: GPL-2.0-or-later', self.sources['NagramChatAvatarPlacement.kt'])

    def test_both_real_settings_objects_retain_the_same_complete_original_members(self):
        members = comments.settings_members(REPO)
        for owner, directory, filename in [('reply', 'actual', 'DesktopOriginalReplySettings.kt'),
                                           ('playback', 'playback', 'DesktopOriginalPlaybackSettingsPreferences.kt')]:
            folder = self.output/directory
            if owner == 'playback':
                folder = folder/'generated'
            text = body(folder/'com/android/purebilibili/core/store'/filename)
            self.assertEqual(1, text.count(members))
            self.assertEqual(1, text.count('fun getCommentAvatarPushEnabled('))
            self.assertEqual(1, text.count('fun setCommentAvatarPushEnabled('))
            self.assertIn('?: false', members)
            proof = self.proof('v046-comment-avatar-'+owner+'-settings-proof', directory)
            restored = restore(text, proof['retainedToCurrent'])
            self.assertNotIn('getCommentAvatarPushEnabled(', restored)

    def test_raw_inputs_predecessor_drift_and_overlapping_output_fail_closed(self):
        for name in ['ReplyComponents.kt', 'CommentAvatarPush.kt', 'manifest.json', 'LICENSE', 'extra.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                repo = Path(temp)
                shutil.copytree(REPO/comments.ARCHIVE, repo/comments.ARCHIVE)
                file = repo/comments.ARCHIVE/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(AssertionError):
                    comments.fixed(repo)
        with self.assertRaises(AssertionError):
            comments.advance_reply_components(REPO, 'unrelated owner', self.output/'bad')
        with self.assertRaises(AssertionError):
            comments.emit_shared(REPO, REPO/comments.ARCHIVE)

    def test_actual_generator_is_deterministic_and_does_not_duplicate_existing_rows(self):
        self.owner.generate(REPO, self.output/'again')
        for path in (self.output/'actual/com').rglob('*.kt'):
            self.assertEqual(path.read_bytes(), (self.output/'again'/path.relative_to(self.output/'actual')).read_bytes())
        definitions = ''.join(p.read_text(encoding='utf8') for p in (self.output/'actual/com').rglob('*.kt'))
        for name in ['ReplyItemView', 'ReplyMemberAvatar', 'SubReplyDetailItem', 'resolveNagramChatAvatarTop', 'rememberCommentAvatarPushEnabled']:
            self.assertEqual(1, definitions.count('fun '+name+'('), name)

    def test_nested_renderer_reconstructs_complete_original_and_retains_actual_windows_actions(self):
        path = comments.PACKAGE+'DesktopOriginalSubReplyDetailComponents.kt'
        actual = body(self.output/'actual'/path)
        before = body(self.output/'before'/path)
        proof = self.proof('v046-subreply-list-renderer-proof')
        thread_proof = self.proof('v046-comment-thread-host-proof')
        # The v046 comment polish inline entrance stage follows the nested page stage; peel it first.
        entrance_proof = self.proof('v046-inline-subreply-entrance-proof')
        previous_stage = restore(restore(actual, entrance_proof['retainedToCurrent']), thread_proof['retainedToCurrent'])
        self.assertEqual(before, restore(previous_stage, proof['retainedToCurrent']))
        item = comments.declaration(self.syntax, actual, 'SubReplyDetailItem')
        old_item = comments.declaration(self.syntax, before, 'SubReplyDetailItem')
        self.assertEqual(comments.declaration(self.syntax, self.sources['SubReplyDetailComponents.kt'], 'SubReplyDetailItem'),
                         restore(item, proof['originalToWindows']))
        for name in ['launchSaveReplyCommentImage', 'requestSaveReplyCommentImage', 'shareReplyComment', 'blockReplyUser']:
            self.assertEqual(comments.declaration(self.syntax, old_item, name), comments.declaration(self.syntax, item, name))
        self.assertEqual(comments.translation_job(self.syntax, old_item), comments.translation_job(self.syntax, item))
        self.assertIn('allowNeighborContact = !isRootItem', item)
        self.assertIn('commentAvatarPushOffset(', item)
        self.assertEqual(comments.declaration(self.syntax, self.sources['SubReplyDetailComponents.kt'], 'resolveSubReplyDetailLayoutPolicy'),
                         comments.declaration(self.syntax, actual, 'resolveSubReplyDetailLayoutPolicy'))
        self.assertTrue(proof['completeOriginalLayoutTypeAndFactory'])

    def test_nested_actual_lazy_owner_passes_root_and_child_keys_without_replacing_conversation_state(self):
        actual = body(self.output/'actual'/comments.PACKAGE/'DesktopOriginalSubReplyDetailComponents.kt')
        host = comments.declaration(self.syntax, actual, 'SubReplyDetailContent')
        calls = comments.call_spans(self.syntax, host, 'SubReplyDetailItem')
        self.assertEqual(2, len(calls))
        for first, last in calls:
            call = host[first:last]
            self.assertIn('avatarListState = listState.takeIf { avatarPushEnabled }', call)
            self.assertIn('avatarItemKey = '+('"root_reply"' if 'item = rootReply,' in call else 'item.rpid'), call)
        self.assertIn('item(key = "root_reply")', host)
        self.assertIn('key = { _, item -> item.rpid }', host)
        self.assertIn('val avatarPushEnabled = rememberCommentAvatarPushEnabled()', host)
        proof = self.proof('v046-subreply-list-renderer-proof')
        self.assertTrue(proof['sameExistingConversationAndListOwner'])
        self.assertTrue(proof['broaderV046ConversationHostNotClaimed'])

    def test_dynamic_slot_builder_is_complete_original_and_keeps_same_windows_callbacks(self):
        path = 'com/android/purebilibili/feature/dynamic/components/DesktopOriginalDynamicInlineReplyUi.kt'
        actual = body(self.output/'actual'/path)
        before = body(self.output/'before'/path)
        proof = self.proof('v046-dynamic-inline-comment-items-proof')
        self.assertEqual(before, restore(actual, proof['retainedToCurrent']))
        media = module('extract-upstream-media.py')
        expected = media.function(self.sources['DynamicCommentSheet.kt'], 'dynamicInlineCommentItems', self.syntax)
        current = media.function(actual, 'dynamicInlineCommentItems', self.syntax)
        self.assertEqual(expected, current)
        self.assertIn('key = { it.rpid }', current)
        self.assertIn('avatarListState = avatarListState', current)
        self.assertTrue(proof['noAdditionalSessionCacheOrTransport'])

    def test_copied_original_declarations_name_only_symbols_the_pinned_tree_provides(self):
        # Cloud compile 39083342426: these symbols were used by the copied v046 declarations but
        # neither imported by the retained Windows files nor present in the pinned design-system.
        reply = body(self.output/'actual'/comments.PACKAGE/'DesktopOriginalReplyComponents.kt')
        nested = body(self.output/'actual'/comments.PACKAGE/'DesktopOriginalSubReplyDetailComponents.kt')
        pink = 'com.android.purebilibili.core.ui.components.userVipPink()'
        for text in (reply, nested):
            self.assertIn('import com.android.purebilibili.core.ui.AppContentColors\n', text)
            self.assertNotIn('desktopUserVipPink()', text)
            self.assertIn(pink, text)
        self.assertEqual(2, reply.count(pink))
        self.assertEqual(1, nested.count(pink))
        self.assertIn('import androidx.compose.runtime.mutableIntStateOf\n', nested)
        # the compatibility extension is retired: v046-sheet-motion carries the real AppBottomSheetMotion.reducedMotion member
        self.assertFalse((self.output/'actual'/comments.PACKAGE/'DesktopOriginalCommentMotionCompat.kt').exists())
        sheet = (REPO/'desktop/upstream-slices/v046-sheet-motion/AppSheetComponents.kt').read_text(encoding='utf8')
        self.assertEqual(1, sheet.count('val reducedMotion: Boolean'))
        for name in ['CommentContentReveal.kt', 'CommentCascadingContent.kt']:
            self.assertEqual(self.sources[name], (self.output/'actual'/comments.PACKAGE/name).read_text(encoding='utf8'))
        badge = (REPO/'desktop/upstream-slices/v046-identity-badges/UserVipBadge.kt').read_text(encoding='utf8')
        self.assertEqual(1, badge.count('fun resolveUserVipPink('))
        self.assertEqual(1, badge.count('fun userVipPink('))
        self.assertIn('if (isDark) Color(0xFFD44E7D) else Color(0xFFFF6699)', badge)
        self.assertNotIn('desktopUserVipPink', badge)
        self.assertNotIn('AppSurfaceTokens', badge)


class ConnectedCommentPanelSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temp.name)
        cls.sources, _ = comments.fixed(REPO)
        cls.syntax = module('sync-upstream.py')
        commands = [
            ('comments', 'extract-upstream-video-comment-ui.py', True),
            ('content', 'extract-upstream-video-content-full.py', False),
            ('holder', 'extract-upstream-video-detail-holder.py', False),
            ('tablet', 'extract-upstream-video-tablet-full.py', True),
            ('bangumi', 'extract-upstream-bangumi-player-ui.py', True),
            ('dynamic', 'extract-upstream-dynamic-detail-container.py', True),
            ('fullscreen', 'extract-upstream-video-fullscreen-pager.py', True),
            ('bgm', 'extract-upstream-bgm-detail.py', True),
        ]
        for label, filename, named in commands:
            args = ['--repo', str(REPO), '--output', str(cls.output/label)] if named else [str(REPO), str(cls.output/label)]
            result = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(TOOLS/filename), *args],
                                    capture_output=True, encoding='utf8')
            if result.returncode:
                raise AssertionError(filename+'\n'+result.stdout+'\n'+result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def generated(self, owner, name):
        hits = list((self.output/owner/'com').rglob(name))
        self.assertEqual(1, len(hits), (owner, name))
        return body(hits[0])

    def proof(self, owner, name):
        return json.loads((self.output/owner/(name+'.json')).read_bytes())

    def test_shared_tab_is_complete_original_with_same_windows_density_and_delete_effect(self):
        actual = self.generated('comments', 'DesktopOriginalVideoCommentTab.kt')
        declaration = comments.declaration(self.syntax, actual, 'VideoCommentTab')
        proof = self.proof('comments', 'v046-shared-comment-tab-proof')
        self.assertEqual(comments.declaration(self.syntax, self.sources['VideoContentSection.kt'], 'VideoCommentTab'),
                         restore(declaration, proof['originalToWindows']))
        self.assertIn('avatarListState = listState', declaration)
        self.assertIn('isPinned = reply.rpid in pinnedReplyIds', declaration)
        self.assertIn('DesktopReplyDissolvableContainer(', declaration)

    def test_every_actual_ordinary_caller_passes_owner_duration_and_real_pins(self):
        generated = self.generated('content', 'DesktopOriginalVideoContentSection.kt')
        calls = comments.call_spans(self.syntax, generated, 'VideoCommentTab')
        self.assertEqual(2, len(calls))
        for first, last in calls:
            call = generated[first:last]
            self.assertIn('upMid = info.owner.mid', call)
            self.assertIn('maxTimestampMs = info.pages.firstOrNull { it.cid == info.cid }', call)
            self.assertIn('pinnedReplyIds = pinnedReplyIds', call)
            self.assertNotIn('videoTags =', call)
        holder = self.generated('holder', 'VideoDetailPhoneContent.kt')
        self.assertIn('pinnedReplyIds = commentState.pinnedReplyIds', holder)
        for filename, state, info in [('DesktopVideoCommentRoot.kt', 'commentState', 'info'), ('DesktopWindowsVideoCommentsSection.kt', 'state', 'success.info')]:
            text = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/filename).read_text(encoding='utf8')
            calls = comments.call_spans(self.syntax, text, 'VideoCommentTab')
            self.assertEqual(1, len(calls))
            call = text[calls[0][0]:calls[0][1]]
            self.assertIn('pinnedReplyIds = '+state+'.pinnedReplyIds', call)
            self.assertIn('maxTimestampMs =', call)
            self.assertIn('upMid = '+info+'.owner.mid', call)

    def test_bottom_bar_keeps_complete_original_file_and_all_known_state_projections(self):
        actual = self.generated('tablet', 'DesktopOriginalBottomInputBar.kt')
        proof = self.proof('tablet', 'v046-shared-bottom-input-bar-proof')
        self.assertEqual(self.sources['BottomInputBar.kt'], restore(actual, proof['originalToWindows']))
        self.assertIn('LocalDesktopOriginalHomeSettingsRootServices.current.home', actual)
        for owner, filename in [('holder', 'VideoDetailPhoneContent.kt'),
                                ('tablet', 'TabletVideoLayout.kt'),
                                ('tablet', 'TabletCinemaLayout.kt')]:
            caller = self.generated(owner, filename)
            first, last = comments.call_spans(self.syntax, caller, 'BottomInputBar')[0]
            self.assertIn('likeStatusKnown = engagementState.likeStatusKnown', caller[first:last])

    def test_tablet_and_cinema_forward_their_actual_list_and_exact_string_keys(self):
        for label, name, owner_name, prefix in [
            ('TabletVideoLayout', 'TabletVideoLayout.kt', 'TabletSecondaryContent', 'reply_'),
            ('TabletCinemaLayout', 'TabletCinemaLayout.kt', 'CinemaCommentsPane', 'curtain_reply_'),
        ]:
            actual = self.generated('tablet', name)
            proof = self.proof('tablet', 'v046-'+label+'-avatar-owner-proof')
            previous = restore(actual, proof['retainedToCurrent'])
            owner = comments.declaration(self.syntax, actual, owner_name)
            before_owner = comments.declaration(self.syntax, previous, owner_name)
            self.assertEqual(1, owner.count('val listState = rememberLazyListState()'))
            self.assertEqual(1, before_owner.count('val listState = rememberLazyListState()'))
            self.assertIn('rememberCommentAvatarPushEnabled()', owner)
            first, last = comments.call_spans(self.syntax, owner, 'ReplyItemView')[0]
            call = owner[first:last]
            self.assertIn('avatarListState = listState.takeIf { avatarPushEnabled }', call)
            self.assertIn('avatarItemKey = "'+prefix+'${reply.rpid}"', call)
            self.assertIn('key = { "'+prefix+'${it.rpid}" }', owner)
            self.assertEqual(prefix, proof['actualLazyKeyPrefix'])
            self.assertTrue(proof['originalAvatarArgumentsVerified'])

    def test_bangumi_panel_and_glass_header_reconstruct_the_whole_original_files(self):
        actual = self.generated('bangumi', 'DesktopOriginalBangumiCommentPanel.kt')
        proof = self.proof('bangumi', 'v046-bangumi-comment-panel-proof')
        self.assertEqual(self.sources['BangumiCommentPanel.kt'], restore(actual, proof['originalToWindows']))
        self.assertEqual(self.sources['VideoCommentFloatingHeader.kt'],
                         self.generated('bangumi', 'DesktopOriginalVideoCommentFloatingHeader.kt'))
        for target in ['VideoCommentTab', 'VideoCommentFloatingHeader', 'BottomInputBar', 'CommentSearchSheet']:
            self.assertEqual(1, len(comments.call_spans(self.syntax, actual, target)))
        self.assertIn('showActionButtons = false', actual)
        self.assertIn('DesktopOriginalReplySettings.setCommentDefaultSortMode', actual)
        self.assertNotIn('SettingsManager.', actual)

    def test_complete_search_file_keeps_all_original_filtering_motion_and_avatar_declarations(self):
        actual = self.generated('comments', 'CommentSearchSheet.kt')
        proof = self.proof('comments', 'v046-comment-search-sheet-proof')
        self.assertEqual(self.sources['CommentSearchSheet.kt'], restore(actual, proof['originalToWindows']))
        for name in ['CommentSearchSheet', 'CommentSearchSegmentedDock', 'CommentSearchResultRow', 'CommentSearchAvatar', 'highlightQuery']:
            self.assertEqual(1, actual.count('fun '+name+'('), name)
        self.assertIn('if (fullSearchState.isReady) fullReplies else replies', actual)
        self.assertIn('val glassActive = false', actual)
        self.assertIn('onLoadAllComments()', actual)
        self.assertIn('avatarListState = listState.takeIf { avatarPushEnabled }', actual)
        self.assertIn('key = { "${it.reply.rpid}_${it.isSubReply}" }', actual)
        self.assertIn('val item = entry.reply', actual)
        self.assertIn('itemKey = "${item.rpid}_${entry.isSubReply}"', actual)
        self.assertIn('allowNeighborContact = false', actual)

    def test_fullscreen_and_music_main_list_keep_whole_original_with_real_full_search_and_skeletons(self):
        actual = self.generated('fullscreen', 'VideoCommentSheetHost.kt')
        proof = self.proof('fullscreen', 'v046-main-comment-list-proof')
        # The v046 comment polish host stage follows the main list stage; recover its exact predecessor first.
        host_proof = self.proof('fullscreen', 'v046-comment-sheet-host-proof')
        actual = restore(actual, host_proof['retainedToCurrent'])
        previous = restore(actual, proof['retainedToCurrent'])
        selected = comments.declaration(self.syntax, actual, 'VideoCommentMainList')
        self.assertEqual(comments.declaration(self.syntax, self.sources['VideoCommentSheetHost.kt'], 'VideoCommentMainList'),
                         restore(selected, proof['originalToWindows']))
        self.assertEqual(comments.declaration(self.syntax, previous, 'VideoCommentMainList').split(') {', 1)[0],
                         selected.split(') {', 1)[0])
        self.assertIn('viewModel.fullSearchState.collectAsStateWithLifecycle()', selected)
        self.assertIn('viewModel.fullSearchReplies.collectAsStateWithLifecycle()', selected)
        self.assertIn('fullReplies = fullSearchReplies', selected)
        self.assertIn('onLoadAllComments = { viewModel.loadAllCommentsForSearch() }', selected)
        self.assertIn('avatarListState = listState.takeIf { avatarPushEnabled }', selected)
        self.assertIn('videoCommentSkeletonItems(', selected)
        self.assertIn('com.android.purebilibili.core.ui.components.AppLazyColumn(', selected)
        self.assertIn('DesktopOriginalVideoCommentSheetSettings.setCommentDefaultSortMode(context, mode.apiMode)', selected)
        for name in ['LoadingFooter', 'NoMoreFooter']:
            self.assertEqual(comments.declaration(self.syntax, self.sources['VideoCommentSheetHost.kt'], name),
                             comments.declaration(self.syntax, actual, name))

    def test_bgm_content_is_whole_original_with_same_windows_playing_bar_and_media_search_ports(self):
        actual = self.generated('bgm', 'BgmDetailScreen.kt')
        proof = self.proof('bgm', 'v046-bgm-comment-content-proof')
        before = restore(actual, proof['retainedToCurrent'])
        selected = comments.declaration(self.syntax, actual, 'BgmDetailContent')
        original = comments.declaration(self.syntax, self.sources['BgmDetailScreen.kt'], 'BgmDetailContent')
        self.assertEqual(original, restore(selected, proof['originalToWindows']))
        self.assertEqual(comments.declaration(self.syntax, before, 'BgmDetailContent').split(') {', 1)[0],
                         selected.split(') {', 1)[0])
        self.assertIn('nowPlayingBarOverlayVisible: Boolean', selected)
        self.assertIn('onMediaSearch: (String, String, String) -> Boolean', selected)
        self.assertEqual(2, selected.count('BgmInfoCard(detail, onVideosClick, onVideoClick, onUserClick, onMediaSearch,'))
        self.assertNotIn('AudioNowPlayingSession.barOverlayVisible', selected)
        self.assertNotIn('avatarListState', original)
        self.assertTrue(proof['originalBgmHasNoAvatarListWiring'])

    def test_search_preserves_existing_windows_clipboard_feedback_modal_and_signature(self):
        actual = self.generated('comments', 'CommentSearchSheet.kt')
        proof = self.proof('comments', 'v046-comment-search-sheet-proof')
        before = restore(actual, proof['retainedToCurrent'])
        for port in ['platform.copyText(entry.reply.content.message, "评论")',
                     'platform.showFeedback("评论已复制")',
                     'import com.bilipai.desktop.ui.DesktopWindowsCommentSearchModalSheet as AppModalBottomSheet',
                     'import coil3.compose.LocalPlatformContext as LocalContext']:
            self.assertIn(port, before)
            self.assertIn(port, actual)
        for text in [actual, before]:
            signature = comments.declaration(self.syntax, text, 'CommentSearchSheet').split(') {', 1)[0]
            if text == actual:
                current_signature = signature
            else:
                self.assertEqual(current_signature, signature)
        self.assertNotIn('Toast.makeText', actual)
        self.assertNotIn('clipboardManager.setText', actual)

    def test_episode_branch_uses_original_key_and_same_vm_without_changing_other_parent_logic(self):
        actual = self.generated('bangumi', 'DesktopOriginalBangumiPlayerContent.kt')
        proof = self.proof('bangumi', 'v046-DesktopOriginalBangumiPlayerContent-comment-caller-proof')
        previous = restore(actual, proof['retainedToCurrent'])
        self.assertIn('VideoCommentMainList(', previous)
        self.assertNotIn('VideoCommentMainList(', actual)
        original_key = comments.braced_statement(self.syntax, self.sources['BangumiPlayerContent.kt'],
            'key(currentEpisode.id) {\n                            BangumiCommentPanel(')
        self.assertIn(original_key, actual)
        self.assertIn('val commentListState = key(currentEpisode.id) { rememberLazyListState() }', actual)
        self.assertIn('getCommentMemberDecorationsEnabled(commentPlatform.context)', actual)
        self.assertIn('isVideoPlaying: Boolean,', actual)
        self.assertIn('onTimestampClick: ((Long) -> Unit)?,', actual)

    def test_screen_supplies_reactive_existing_player_state_and_actual_guarded_seek(self):
        actual = self.generated('bangumi', 'DesktopOriginalBangumiPlayerScreen.kt')
        proof = self.proof('bangumi', 'v046-DesktopOriginalBangumiPlayerScreen-comment-caller-proof')
        previous = restore(actual, proof['retainedToCurrent'])
        self.assertIn('isVideoPlaying = playbackDebugSnapshot.isPlaying', actual)
        self.assertIn('onTimestampClick = ::seekFromComment', actual)
        listener = 'override fun onIsPlayingChanged(isPlaying: Boolean)'
        self.assertEqual(previous.count(listener), actual.count(listener))
        self.assertIn('isPlaying = exoPlayer.isPlaying', previous)
        seek = comments.declaration(self.syntax, actual, 'seekFromComment')
        self.assertEqual(comments.declaration(self.syntax, self.sources['BangumiPlayerScreen.kt'], 'seekFromComment'),
                         restore(seek, proof['originalSeekToWindows']))
        self.assertIn('if (!platform.owns() || !exoPlayer.isOwned()) return', seek)

    def test_dynamic_detail_passes_the_original_actual_visible_list_without_new_state_or_session(self):
        actual = self.generated('dynamic', 'DesktopOriginalDynamicDetailLayout.kt')
        proof = self.proof('dynamic', 'v046-dynamic-detail-avatar-owner-proof')
        before = restore(actual, proof['retainedToCurrent'])
        self.assertIn(proof['originalWiring'], self.sources['DynamicDetailScreen.kt'])
        self.assertIn(proof['originalWiring'], actual)
        self.assertIn('val avatarPushEnabled = com.android.purebilibili.feature.video.ui.components.rememberCommentAvatarPushEnabled()', actual)
        self.assertEqual(2, actual.count('rememberLazyListState()'))
        self.assertEqual(before.count('rememberLazyListState()'), actual.count('rememberLazyListState()'))
        self.assertIn('key(dynamicId, session)', actual)
        self.assertIn('if (!platform.isOwned() || !session.isOwned()) return', actual)
        self.assertIn('session.commentUiState.collectAsState()', actual)
        self.assertFalse(proof['newListStateCreated'])

    def test_settings_search_and_backup_use_the_same_existing_owner_and_boolean(self):
        settings = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsPlaybackSettings.kt').read_text(encoding='utf8')
        self.assertIn('Preferences.getCommentAvatarPushEnabled(context)', settings)
        self.assertIn('writer.launch { Preferences.setCommentAvatarPushEnabled(context, enabled) }', settings)
        catalog = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsShareWindowsCatalog.kt').read_text(encoding='utf8')
        self.assertIn('"comment_avatar_push_enabled"', catalog)
        self.assertEqual(1, catalog.count('"comment_avatar_push_enabled"'))
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertIn('kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply/com"))', build)
        self.assertNotIn('kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply"))', build)
        self.assertIn('kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply/test"))', build)
        self.assertIn('inputs.files("tools/v046_comment_list.py", "tools/v044_favorite_detail.py")', build)
        self.assertIn('inputs.dir("upstream-slices/v046-comment-list")', build)
        search = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopWindowsSettingsCatalog.kt').read_text(encoding='utf8')
        self.assertIn('"头像跟随"', search)

    def test_generators_emit_no_duplicate_composables_and_guard_input_and_archive_overlap(self):
        all_text = '\n'.join(p.read_text(encoding='utf8') for owner in self.output.iterdir()
                             for p in (owner/'com').rglob('*.kt'))
        for name in ['BangumiCommentPanel', 'VideoCommentFloatingHeader', 'BottomInputBar', 'VideoCommentTab']:
            self.assertEqual(1, all_text.count('fun '+name+'('), name)
        with self.assertRaises(AssertionError):
            comments.emit_bangumi_panel(REPO, REPO/comments.ARCHIVE)
        with self.assertRaises(AssertionError):
            comments.advance_bangumi_ui(REPO, 'unrelated parent', self.output/'bad',
                'com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiPlayerContent.kt')

    def test_every_generated_owner_declares_each_bottom_bar_helper_once_and_imports_what_it_names(self):
        labels = ['comments', 'content', 'holder', 'tablet', 'bangumi', 'dynamic', 'fullscreen', 'bgm']
        for helper in ['shouldUseFloatingLiquidBottomInputBar', 'resolveBottomInputBarContentBottomPadding']:
            owners = [path.name for label in labels for path in (self.output/label).rglob('*.kt')
                      if 'fun '+helper+'(' in path.read_text(encoding='utf8')]
            self.assertEqual(['DesktopOriginalDetailInputPadding.kt'], owners, helper)
        bar = self.generated('tablet', 'DesktopOriginalBottomInputBar.kt')
        self.assertIn('fun shouldUseFrostedBottomInputBar(', bar)
        self.assertEqual(self.sources['BottomInputBar.kt'],
                         restore(bar, self.proof('tablet', 'v046-shared-bottom-input-bar-proof')['originalToWindows']))
        host = self.generated('fullscreen', 'VideoCommentSheetHost.kt')
        self.assertIn('import com.android.purebilibili.core.ui.AppContentColors\n', host)
        search = self.generated('comments', 'CommentSearchSheet.kt')
        pink = 'com.android.purebilibili.core.ui.components.userVipPink()'
        self.assertIn(pink, search)
        self.assertEqual(1, search.count(pink))
        self.assertNotIn('desktopUserVipPink()', search)
        badge = (REPO/'desktop/upstream-slices/v046-identity-badges/UserVipBadge.kt').read_text(encoding='utf8')
        self.assertEqual(1, badge.count('fun userVipPink('))
        self.assertIn('fun userVipPink(): Color =\n    resolveUserVipPink(MaterialTheme.colorScheme.surface.luminance() < 0.5f)', badge)

    def test_landscape_comment_panel_declares_and_receives_the_real_pinned_set(self):
        content = self.generated('content', 'DesktopOriginalVideoContentSection.kt')
        panel = comments.declaration(self.syntax, content, 'LandscapeCommentPanel')
        self.assertIn('    pinnedReplyIds: Set<Long> = emptySet(),\n    modifier: Modifier = Modifier,\n) {', panel)
        self.assertIn('pinnedReplyIds = pinnedReplyIds', panel)
        holder = self.generated('holder', 'VideoDetailScreenStateHolder.kt')
        calls = comments.call_spans(self.syntax, holder, 'LandscapeCommentPanel')
        self.assertEqual(2, len(calls))
        for first, last in calls:
            self.assertEqual(1, holder[first:last].count('pinnedReplyIds = commentState.pinnedReplyIds'))
        proof = self.proof('holder', 'v046-landscape-comment-pins-proof')
        self.assertEqual(2, proof['actualLandscapeCallers'])


if __name__ == '__main__':
    unittest.main()
