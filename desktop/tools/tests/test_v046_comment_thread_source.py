"""Verify the actual producer keeps the complete original nested-page lifecycle."""
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
sys.path.insert(0, str(REPO/'desktop/tools'))
import v046_comment_list as comments
import v046_comment_thread as thread
from v046_favorite_editor import restore
from desktop_refresh_adaptation import unbind_refresh_lists


def body(path):
    value = path.read_text(encoding='utf8')
    return value.split('\n', 2)[2] if value.startswith(('// Original source ', '// GENERATED ')) else value


class OriginalNestedPageSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temp.name)
        cls.parser = comments.load(REPO, 'thread259_tests_syntax', 'sync-upstream.py')
        cls.owner = comments.load(REPO, 'thread259_tests_owner', 'extract-upstream-dynamic-reply.py')
        cls.owner.generate(REPO, cls.output/'actual')
        with patch.object(thread, 'advance', side_effect=lambda repo, before, output: before), \
             patch('v046_comment_polish.advance_inline_entrance', side_effect=lambda repo, before, output: before), \
             patch.object(thread, 'advance_split_item_states', side_effect=lambda repo, before, output: before):
            cls.owner.generate(REPO, cls.output/'previous')
        relative = comments.PACKAGE+'DesktopOriginalSubReplyDetailComponents.kt'
        # The v046 comment polish inline entrance stage follows the nested page stage; recover this stage's own output.
        entrance = json.loads((cls.output/'actual/v046-inline-subreply-entrance-proof.json').read_bytes())
        # The comment-detail split item states follow the entrance; peel that receipt first.
        cls.split = json.loads((cls.output/'actual/v046-comment-detail-split-item-states-proof.json').read_bytes())
        cls.final = unbind_refresh_lists(body(cls.output/'actual'/relative))
        # 591685d9e binds the shared refresh lists after every stage receipt; peel that exact binding first.
        cls.actual = restore(restore(cls.final, cls.split['retainedToCurrent']), entrance['retainedToCurrent'])
        cls.previous = unbind_refresh_lists(body(cls.output/'previous'/relative))
        cls.original = comments.fixed(REPO)[0]['SubReplyDetailComponents.kt']
        cls.proof = json.loads((cls.output/'actual/v046-comment-thread-host-proof.json').read_bytes())
        cls.host = comments.declaration(cls.parser, cls.actual, 'SubReplyDetailContent')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_whole_original_host_and_private_page_type_are_recoverable(self):
        self.assertEqual(comments.declaration(self.parser, self.original, 'SubReplyDetailContent'),
                         restore(self.host, self.proof['originalToWindows']))
        self.assertEqual(thread.page_type(self.original), thread.page_type(self.actual))
        self.assertNotIn('@Composable\nprivate data class SubReplyDetailPage(', self.actual)
        self.assertEqual(1, self.actual.count('private data class SubReplyDetailPage('))
        self.assertTrue(self.proof['completeOriginalHostDeclaration'])

    def test_actual_file_inverse_preserves_all_existing_actions_and_renderers(self):
        self.assertEqual(self.previous, restore(self.actual, self.proof['retainedToCurrent']))
        for name in ['SubReplyDetailItem', 'SubReplyAuxiliaryBadge', 'SubReplyDetailStaggeredReveal']:
            self.assertEqual(comments.declaration(self.parser, self.previous, name),
                             comments.declaration(self.parser, self.actual, name))
        self.assertTrue(self.proof['sameExistingVideoAndDynamicStateOwners'])

    def test_real_inline_state_forwards_conversation_identity_with_same_callbacks(self):
        before = comments.declaration(self.parser, self.previous, 'VideoInlineSubReplyDetailContent')
        actual = comments.declaration(self.parser, self.actual, 'VideoInlineSubReplyDetailContent')
        addition = '        conversationAnchorId = state.conversationAnchor?.rpid ?: 0L,\n'
        self.assertEqual(before, actual.replace(addition, '', 1))
        self.assertEqual(1, actual.count(addition))
        self.assertIn('onConversationClick = onConversationClick', actual)
        self.assertIn('onConversationBack = onConversationBack', actual)
        self.assertIn('key(rootReply.rpid, activeConversationId)', self.host)
        self.assertIn('key(rootReply.rpid) { rememberLazyListState() }', self.host)
        self.assertIn('if (effectiveConversationMode) conversationListState else allRepliesListState', self.host)
        self.assertTrue(self.proof['broaderInlineEntranceMotionNotClaimed'])

    def test_original_entry_jump_is_consumed_before_cancellable_animation(self):
        for name in ['resolveSubReplyTargetListIndex', 'resolveSubReplyDetailListScrollResetKey',
                     'resolveSubReplyDetailScrollRestoreAction', 'shouldSaveSubReplyDetailScrollBeforeConversationEnter']:
            self.assertEqual(comments.declaration(self.parser, self.original, name),
                             comments.declaration(self.parser, self.actual, name))
        self.assertIn('if (targetReplyHandled) return@LaunchedEffect', self.host)
        self.assertLess(self.host.index('targetReplyHandled = true'), self.host.index('listState.animateScrollToItem(targetIndex)'))
        self.assertIn('LaunchedEffect(highlightedTargetId)', self.host)
        self.assertIn('index = allRepliesListState.firstVisibleItemIndex', self.host)

    def test_outgoing_page_keeps_its_own_list_and_actual_lazy_avatar_keys(self):
        self.assertIn('val listState = page.listState', self.host)
        self.assertIn('key = Triple(rootReply.rpid, effectiveConversationMode, activeConversationId)', self.host)
        self.assertIn('key = { _, item -> item.rpid }', self.host)
        calls = comments.call_spans(self.parser, self.host, 'SubReplyDetailItem')
        self.assertEqual(2, len(calls))
        for first, last in calls:
            call = self.host[first:last]
            self.assertIn('avatarListState = listState.takeIf { avatarPushEnabled }', call)
            self.assertIn('avatarItemKey = '+('"root_reply"' if 'item = rootReply,' in call else 'item.rpid'), call)

    def test_viewport_skeleton_slots_do_not_restore_the_old_nested_resize_animation(self):
        self.assertIn('DesktopWindowConfiguration.current.screenHeightDp', self.host)
        self.assertIn('videoCommentSkeletonItems(', self.host)
        self.assertIn('slotCount = commentSkeletonSlotCount', self.host)
        self.assertNotIn('SubReplyDetailStaggeredReveal(', self.host)
        self.assertIn('com.android.purebilibili.core.ui.components.AppLazyColumn(', self.host)

    def test_same_owned_settings_and_feedback_ports_without_new_android_services(self):
        self.assertIn('platform.subReplyLoadedCountEnabled', self.host)
        self.assertIn('if (platform.isOwned()) platform.showFeedback(error)', self.host)
        for forbidden in ['Toast.makeText', 'LocalConfiguration.', 'SettingsManager.', 'collectAsStateWithLifecycle',
                          'VideoCommentViewModel(', 'DesktopOriginalDynamicReplySession(', 'Intent(']:
            self.assertNotIn(forbidden, self.host)
        dynamic = body(self.output/'actual/com/bilipai/desktop/ui/DesktopOriginalDynamicThreadContent.kt')
        self.assertIn('SubReplyDetailContent(', dynamic)
        self.assertIn('if (!state.visible || !session.isOwned() || !platform.isOwned()) return', dynamic)

    def test_unknown_predecessor_duplicate_page_or_tampered_receipt_fails_closed(self):
        with self.assertRaises(AssertionError):
            thread.advance(REPO, self.previous.replace('val listState = rememberLazyListState()', 'val listState = unrelatedOwner()', 1), self.output/'bad')
        with self.assertRaises(AssertionError):
            thread.advance(REPO, self.previous+'\n'+thread.page_type(self.original), self.output/'bad')
        with self.assertRaises((AssertionError, ValueError)):
            restore(self.actual+'\n// unexpected', self.proof['retainedToCurrent'])

    def test_wide_comment_detail_moves_only_the_original_comment_beside_the_same_list(self):
        self.assertEqual(1, self.host.count('com.bilipai.desktop.ui.DesktopCommentDetailSplitPanes('))
        self.assertEqual(1, self.host.count('com.bilipai.desktop.ui.DesktopCommentDetailRootSlot(desktopSplit, listState) {'))
        self.assertEqual(1, self.host.count('if (!desktopSplitActive) desktopRootReply(listState)\n'))
        self.assertEqual(1, self.host.count('context = { desktopRootReply(null) },'))
        self.assertEqual(1, self.host.count('item = rootReply,'))
        self.assertEqual(1, self.host.count(thread.SPLIT_TARGET_LINE))
        self.assertIn('LocalDesktopCommentDetailSplitPanes.current', self.host)
        self.assertTrue(self.proof['desktopCommentDetailSplitOptIn'])

    def test_split_item_states_stage_replaces_each_original_state_once_and_inverts(self):
        stage = restore(self.final, self.split['retainedToCurrent'])
        self.assertEqual(self.final, thread.desktop_split_item_states(stage))
        item = comments.declaration(self.parser, self.final, 'SubReplyDetailItem')
        for line, name, _, _ in thread.SPLIT_ITEM_STATES:
            with self.subTest(name=name):
                self.assertEqual(1, stage.count(line))
                self.assertNotIn(line, self.final)
                self.assertEqual(1, item.count('("'+name+'", '))
        self.assertEqual(len(thread.SPLIT_ITEM_STATES), item.count('com.bilipai.desktop.ui.rememberDesktopCommentDetailItemState'))
        # Its coroutine dies with the composition, so the in-flight flag stays local.
        self.assertIn('var isTranslating by remember(item.rpid) { mutableStateOf(false) }', item)
        self.assertEqual([name for _, name, _, _ in thread.SPLIT_ITEM_STATES], self.split['itemStates'])

    def test_missing_or_duplicate_split_anchor_raises_instead_of_skipping(self):
        stage = restore(self.final, self.split['retainedToCurrent'])
        for line, name, _, _ in thread.SPLIT_ITEM_STATES:
            for broken in (stage.replace(line, '', 1), stage.replace(line, line+line, 1)):
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Comment detail split anchor changed'):
                    thread.desktop_split_item_states(broken)
        for anchor in (thread.SPLIT_STATE_ANCHOR, thread.SPLIT_REFRESH, thread.SPLIT_ROOT_ITEM):
            with self.subTest(anchor=anchor[:40]), self.assertRaisesRegex(ValueError, 'Comment detail split anchor changed'):
                thread.desktop_split_panes(self.host.replace(anchor, '', 1))

    def test_missing_split_anchor_exits_non_zero_even_with_optimized_asserts(self):
        script = ('import sys; sys.path.insert(0, sys.argv[1]); import v046_comment_thread as t; '
                  't.desktop_split_item_states(sys.stdin.read())')
        stage = restore(self.final, self.split['retainedToCurrent'])
        broken = stage.replace(thread.SPLIT_ITEM_STATES[0][0], '', 1)
        run = subprocess.run([sys.executable, '-O', '-B', '-c', script, str(REPO/'desktop/tools')],
                             input=broken, capture_output=True, text=True)
        self.assertNotEqual(0, run.returncode)
        self.assertIn('Comment detail split anchor changed', run.stderr)
        ok = subprocess.run([sys.executable, '-O', '-B', '-c', script, str(REPO/'desktop/tools')],
                            input=stage, capture_output=True, text=True)
        self.assertEqual(0, ok.returncode, ok.stderr)

    def test_root_text_hook_reaches_only_the_rich_comment_text_and_inverts(self):
        receipt = json.loads((self.output/'actual/v046-comment-detail-root-text-proof.json').read_bytes())
        final = unbind_refresh_lists(body(self.output/'actual'/comments.PACKAGE/'DesktopOriginalReplyComponents.kt'))
        stage = restore(final, receipt['retainedToCurrent'])
        self.assertEqual(final, thread.desktop_root_text_layout(stage))
        hook = 'val desktopRootText = com.bilipai.desktop.ui.rememberDesktopCommentDetailRootText(text)\n'
        self.assertEqual(0, stage.count(hook))
        self.assertEqual(1, final.count(hook))
        rich = final.index('fun RichCommentText(')
        self.assertLess(rich, final.index(hook))
        self.assertLess(final.index(hook), final.index('\n@Composable', rich))
        for broken in (stage.replace(thread.ROOT_TEXT_BEFORE, '', 1),
                       stage.replace(thread.ROOT_TEXT_BEFORE, thread.ROOT_TEXT_BEFORE*2, 1)):
            with self.assertRaisesRegex(ValueError, 'Comment detail split anchor changed'):
                thread.desktop_root_text_layout(broken)

    def test_all32_original_cases_use_the_actual_generated_production_source(self):
        original = thread.fixed_tests(REPO)
        actual = (self.output/'actual/test'/comments.PACKAGE/'V046SubReplyDetailPresentationOriginalTest.kt').read_text(encoding='utf8')
        proof = json.loads((self.output/'actual/v046-comment-thread-original-tests-proof.json').read_bytes())
        self.assertEqual(original, restore(actual, proof['originalToWindows']))
        self.assertEqual(32, actual.count('@Test'))
        self.assertEqual(4, actual.count(thread.ACTUAL_TEST_SOURCE))
        self.assertNotIn(thread.ORIGINAL_TEST_SOURCE, actual)
        self.assertTrue(proof['completeOriginalClass'])
        self.assertFalse(proof['kotlinExecuted'])

    def test_original_test_archive_corruption_and_extra_leaves_fail_closed(self):
        for name in ['manifest.json', 'SubReplyDetailPresentationPolicyTest.kt', 'LICENSE', 'extra.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                repo = Path(directory)
                shutil.copytree(REPO/thread.TEST_ARCHIVE, repo/thread.TEST_ARCHIVE)
                p = repo/thread.TEST_ARCHIVE/name
                p.write_bytes((p.read_bytes() if p.exists() else b'')+b'\n')
                with self.assertRaises(AssertionError):
                    thread.fixed_tests(repo)


if __name__ == '__main__':
    unittest.main()
