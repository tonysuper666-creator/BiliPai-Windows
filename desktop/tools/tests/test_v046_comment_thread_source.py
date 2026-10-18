"""Verify the actual producer keeps the complete original nested-page lifecycle."""
from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v046_comment_list as comments
import v046_comment_thread as thread
from v046_favorite_editor import restore


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
        with patch.object(thread, 'advance', side_effect=lambda repo, before, output: before):
            cls.owner.generate(REPO, cls.output/'previous')
        relative = comments.PACKAGE+'DesktopOriginalSubReplyDetailComponents.kt'
        cls.actual = body(cls.output/'actual'/relative)
        cls.previous = body(cls.output/'previous'/relative)
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
