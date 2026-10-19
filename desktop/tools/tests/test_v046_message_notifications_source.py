"""v0.4.6 re-pin of the message-notification slice: archive pins, complete originals, the App-token gate,
the recent-author red-dot snapshot, the Windows degradation of "clear on read" and the registration.

Source checks only: nothing here compiles Kotlin or runs a JVM test."""
from pathlib import Path
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v038_message_notifications as old
import v046_message_notifications as source

UI = REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'
LEAF_OF = {'policy': 'MessageNotificationPolicy.kt', 'checker': 'MessageNotificationChecker.kt',
           'state': 'MessageNotificationStateStore.kt', 'personal': 'DynamicPersonalFeedApi.kt',
           'authors': 'DynamicAuthorUpdatesApi.kt'}


def load(name):
    spec = importlib.util.spec_from_file_location('v046_notifications_'+re.sub(r'\W', '_', name), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def undo(body, proof):
    for row in reversed(proof['edits']):
        at = row['offset']
        assert row['after'] == body[at:at+len(row['after'])], row['before'][:60]
        body = body[:at]+row['before']+body[at+len(row['after']):]
    return body


def text(path):
    return Path(path).read_text(encoding='utf8')


class V046MessageNotificationSource(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources, cls.manifest = source.fixed(REPO)
        cls.main, cls.tests = source.build(REPO)
        cls.sync = load('sync-upstream.py')

    def body(self, key):
        return self.main[key][0]

    def test_archive_rejects_tampered_extra_or_missing_leaves(self):
        self.assertEqual(source.COMMIT, self.manifest['commit'])
        self.assertEqual(9, len(self.sources))
        for name in [*source.EXPECTED, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-n-') as temp:
                repo = Path(temp)
                shutil.copytree(REPO/source.ARCHIVE, repo/source.ARCHIVE)
                file = repo/source.ARCHIVE/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    source.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-n-') as temp:
            repo = Path(temp)
            shutil.copytree(REPO/source.ARCHIVE, repo/source.ARCHIVE)
            (repo/source.ARCHIVE/'LICENSE').unlink()
            with self.assertRaises(ValueError):
                source.fixed(repo)

    def test_manifest_declares_exactly_which_v038_blobs_it_replaces(self):
        previous = json.loads((REPO/old.ARCHIVE/'manifest.json').read_bytes())
        old_rows = {row['filename']: row for row in previous['files']}
        new_rows = {row['filename']: row for row in self.manifest['files']}
        replaces = self.manifest['replaces']
        self.assertEqual(old.MANIFEST_SHA256, replaces['manifestSha256'])
        self.assertEqual(old.COMMIT, replaces['commit'])
        self.assertEqual('d6bf8b1655ae910999860bf13e65c8fcea5b345b', source.COMMIT)
        for name in replaces['changedFiles']:
            self.assertNotEqual(old_rows[name]['gitBlob'], new_rows[name]['gitBlob'], name)
        for name in replaces['newFiles']:
            self.assertNotIn(name, old_rows)
        for name, blob in replaces['unchangedGitBlobs'].items():
            self.assertEqual(blob, old_rows[name]['gitBlob'], name)
            self.assertNotIn(name, new_rows)
        self.assertEqual(set(new_rows)-{'DynamicModels.kt'}, set(replaces['changedFiles'])|set(replaces['newFiles']))

    def test_complete_originals_are_recovered_after_each_platform_adaptation(self):
        for key, leaf in LEAF_OF.items():
            with self.subTest(file=leaf):
                body, proof = self.main[key]
                self.assertEqual(self.sources[leaf], undo(body, proof))
                self.assertTrue(proof['completeOriginalInverse'])
                self.assertFalse(proof['mainCompiled'])
        self.assertEqual([], self.main['policy'][1]['edits'])
        self.assertEqual(self.sources['MessageNotificationPolicy.kt'], self.body('policy'))

    def test_the_read_offset_watermark_is_gone_and_the_author_snapshot_clears_reads(self):
        checker, policy = self.body('checker'), self.body('policy')
        for token in ('source.dynamicAuthorUpdates()', 'authorUpdates[mid] == false', 'dynamicProbeBaseline',
                      'MAX_DYNAMIC_NOTIFICATION_CONCURRENCY', 'fun dynamicAuthorUpdates(): Map<Long, Boolean>',
                      'pendingPrivateSessionKeys', 'source.feedUnread()'):
            self.assertIn(token, checker, token)
        for token in ('readOffset', 'isDynamicNotificationRead', 'dynamicBaseline', 'NetworkModule', 'TokenManager',
                      'DynamicAuthorUpdatesApi', 'MessageRepository', 'import android.', 'MessageNotificationChecker('):
            self.assertNotIn(token, checker, token)
        self.assertIn('internal const val MAX_DYNAMIC_NOTIFICATION_CONCURRENCY = 16', policy)
        self.assertNotIn('isDynamicNotificationRead', policy)
        self.assertNotIn('readOffset', self.body('personal'))
        self.assertIn('ProtoWire.int32(4, 1)', self.body('personal'))
        self.assertIn('val dynamicProbeBaseline: String? = null', self.body('state'))
        self.assertNotIn('dynamicBaseline', self.body('state'))

    def test_app_token_gate_is_the_original_check_over_the_windows_token_source(self):
        personal = self.body('personal')
        reason = source.APP_AUTHORIZATION_REASON
        gate = 'check(!accessToken().isNullOrBlank()) { "%s" }' % reason
        self.assertIn('check(!TokenManager.accessTokenCache.isNullOrBlank()) { "%s" }' % reason,
                      self.sources['DynamicPersonalFeedApi.kt'])
        self.assertEqual(1, personal.count(gate))
        self.assertLess(personal.index(gate), personal.index('request(PATH, buildDynamicPersonalFeedRequest('))
        self.assertIn('private val accessToken: () -> String?', personal)
        self.assertIn('internal class DesktopDynamicPersonalFeedApi(private val request: suspend (String, ByteArray) -> ByteArray', personal)
        self.assertNotIn('TokenManager', personal)
        constant = re.search(r'DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON = "([^"]+)"', text(UI/'DesktopMessageNotificationAccuracy.kt'))
        self.assertEqual(reason, constant.group(1))
        consumer = text(UI/'DesktopMessageNotificationRootConsumer.kt')
        self.assertEqual(1, consumer.count('DesktopDynamicPersonalFeedApi(grpc::request, ::accessToken)'))
        self.assertEqual(1, consumer.count('ownedHomeAccessToken('))
        self.assertEqual(1, consumer.count('        ::accessToken,\n        ::owned,\n'))
        self.assertEqual(1, consumer.count('desktopDynamicPreloadBlockedReason(actual.appAccessToken())'))
        # The gate stops the dynamic category before its first request. That is only sound while the dynamic
        # category is the sole reader of the following list in the original Poller.
        poller = self.body('checker')
        self.assertEqual(1, poller.count('source.followings('))
        self.assertEqual(1, poller.count('dynamicFollowingUsers(selfMid)'))
        self.assertLess(poller.index('if (settings.notifyDynamicUpdates) category("dynamic")'),
                        poller.index('dynamicFollowingUsers(selfMid)'))
        guard = 'requireDesktopDynamicPreloadAuthorization(accessToken())'
        self.assertEqual(1, consumer.count(guard))
        self.assertLess(consumer.index('override suspend fun followings('), consumer.index(guard))
        self.assertLess(consumer.index(guard), consumer.index('api.getFollowings('))
        self.assertIn('"Dynamic update notifications are inactive for this account: $reason"', consumer)
        context = text(UI/'DesktopMessageNotificationContext.kt')
        self.assertEqual(1, context.count('internal fun appAccessToken(): String? {'))
        self.assertIn('admit { token = repository.ownedHomeAccessToken(epoch, ::retained) }', context)
        # A retired Root is not a web-only session: the admission result must not be dropped on the floor.
        reader = context[context.index('internal fun appAccessToken(): String? {'):]
        reader = reader[:reader.index('\n    }\n') + len('\n    }\n')]
        self.assertIn('if (!admit { token = repository.ownedHomeAccessToken(epoch, ::retained) })\n'
                      '            throw CancellationException("Message notification Root retired")\n        return token', reader)

    def test_recent_author_snapshot_is_verbatim_wired_and_only_reached_after_the_token_gated_scan(self):
        service, service_proof = self.main['service']
        selected = source.api_declarations(REPO)
        api = text(REPO/source.API_ARCHIVE/source.API_FILE)
        self.assertIn(selected, api)
        self.assertEqual(source.SERVICE_HEAD+selected+source.SERVICE_TAIL, service)
        self.assertTrue(service_proof['wholeSelectionVerbatim'])
        self.assertEqual(1, service.count('@GET("x/polymer/web-dynamic/v1/portal")'))
        self.assertEqual(1, service.count('@GET("x/polymer/web-dynamic/v1/uplist")'))
        authors = self.body('authors')
        self.assertIn('internal class DesktopDynamicAuthorUpdatesApi(private val service: DesktopDynamicAuthorUpdatesService)', authors)
        self.assertIn('loadPortal = { service.getDynamicAuthorUpdatesPortal() },', authors)
        self.assertIn('loadPage = { offset -> service.getDynamicAuthorUpdatesPage(offset) }', authors)
        for token in ('NetworkModule', 'accessToken', 'TokenManager', 'dynamicApi'):
            self.assertNotIn(token, authors)
        consumer = text(UI/'DesktopMessageNotificationRootConsumer.kt')
        self.assertEqual(1, consumer.count('override suspend fun dynamicAuthorUpdates() = read { DesktopDynamicAuthorUpdatesApi(authorUpdates).load() }'))
        self.assertEqual(1, consumer.count('ownedHomeService(DesktopDynamicAuthorUpdatesService::class.java,\n        "https://api.bilibili.com/", context.epoch, ::owned)'))
        # The endpoints need no token, but the original Poller reads the snapshot only inside the dynamic
        # category, after the following-list read that this Windows tree gates on the App token. An account
        # without that token therefore gets no dynamic notifications at all; no comment may claim otherwise.
        poller = self.body('checker')
        self.assertEqual(1, poller.count('source.dynamicAuthorUpdates()'))
        self.assertLess(poller.index('dynamicFollowingUsers(selfMid)'), poller.index('source.dynamicAuthorUpdates()'))
        self.assertIn('if (pending.isNotEmpty() || freshByUp.isNotEmpty()) category(null) {\n'
                      '                authorUpdates = fetch { source.dynamicAuthorUpdates() }', poller)
        accuracy = text(UI/'DesktopMessageNotificationAccuracy.kt')
        for label, body in (('service', service), ('accuracy', accuracy), ('consumer', consumer)):
            self.assertNotIn('no App token needed', body, label)
            self.assertNotIn('do not need an App access token', body, label)
        self.assertIn('no dynamic notifications at all', service)
        self.assertIn('no dynamic notifications at all', accuracy)
        self.assertIn('web-only accounts never do', consumer)

    def test_dto_block_is_exactly_the_v035_to_v046_delta_and_declared_once(self):
        block = source.dynamic_models_block(REPO)
        models, proof = self.main['models']
        self.assertEqual(source.MODELS_HEAD+block, models)
        self.assertTrue(proof['wholeSelectionVerbatim'])
        previous = text(REPO/'desktop/upstream-slices/v035-dynamic-link/DynamicModels.kt')
        self.assertEqual(previous, self.sources['DynamicModels.kt'].replace(block+'\n', '', 1))
        names = ['DynamicAuthorUpdatesPortalResponse', 'DynamicAuthorUpdatesPortalData', 'DynamicAuthorUpdatesPageResponse',
                 'DynamicAuthorUpdatesPage', 'DynamicAuthorUpdateItem']
        for name in names:
            self.assertEqual(1, models.count('data class %s(' % name), name)
        # No other carried DynamicModels.kt (they are the sole DTO owner chain) declares these names yet.
        for other in sorted((REPO/'desktop/upstream-slices').glob('*/DynamicModels.kt')):
            if other.parent.name != 'v046-message-notifications':
                self.assertNotIn('DynamicAuthorUpdate', text(other), other.parent.name)

    def test_original_tests_are_carried_with_only_documented_edits(self):
        self.assertEqual({'MessageNotificationPolicyTest.kt': 45, 'DynamicPersonalFeedApiTest.kt': 9,
                          'DynamicAuthorUpdatesApiTest.kt': 13}, {n: b.count('@Test') for n, (b, _) in self.tests.items()})
        for name, (body, proof) in self.tests.items():
            with self.subTest(test=name):
                self.assertEqual(self.sources[name], undo(body, proof))
                self.assertNotIn('org.junit', body)
                for edit in proof['edits']:
                    self.assertTrue(edit['before'].startswith('import org.junit.') or edit['before'] == source.ASSERT_TRUE_BEFORE)
        self.assertEqual([], self.tests['MessageNotificationPolicyTest.kt'][1]['edits'])
        reorder = [e for e in self.tests['DynamicAuthorUpdatesApiTest.kt'][1]['edits'] if not e['before'].startswith('import ')]
        self.assertEqual([(source.ASSERT_TRUE_BEFORE, source.ASSERT_TRUE_AFTER)], [(e['before'], e['after']) for e in reorder])
        # JUnit 4 message-first assertions: this is the only one in the two classes that still use JUnit 4
        # (the policy class already uses kotlin.test, where the message comes last).
        for name in ('DynamicPersonalFeedApiTest.kt', 'DynamicAuthorUpdatesApiTest.kt'):
            original = self.sources[name]
            self.assertEqual(int(name == 'DynamicAuthorUpdatesApiTest.kt'), original.count('assertTrue("'), name)
            self.assertEqual(0, original.count('assertFalse("') + original.count('assertNull("'), name)
        self.assertIn('import kotlin.test.assertTrue\n', self.tests['DynamicAuthorUpdatesApiTest.kt'][0])
        self.assertIn('MessageNotificationPoller(', self.tests['MessageNotificationPolicyTest.kt'][0])

    def test_anchor_drift_and_tampered_external_declarations_fail_closed(self):
        with self.assertRaises(ValueError):
            source.adapt('package x\n', [('absent anchor', '')])
        with self.assertRaises(ValueError):
            source.select('a a', 'a', 'a', 'duplicate')
        with tempfile.TemporaryDirectory(prefix='bp-n-') as temp:
            repo = Path(temp)
            for directory in (source.ARCHIVE, source.API_ARCHIVE):
                shutil.copytree(REPO/directory, repo/directory)
            self.assertEqual(source.api_declarations(REPO), source.api_declarations(repo))
            api = repo/source.API_ARCHIVE/source.API_FILE
            api.write_bytes(api.read_bytes()+b'\n')
            with self.assertRaisesRegex(ValueError, 'ApiClient.kt changed'):
                source.api_declarations(repo)
            output = repo/'out'
            with self.assertRaises(ValueError):
                source.generate(repo, output)
            self.assertEqual([], list(output.rglob('*')) if output.exists() else [])

    def test_existing_producer_is_the_sole_owner_and_emits_each_name_once_deterministically(self):
        producer = load('extract-upstream-message-notifications.py')
        before = {p.name: p.read_bytes() for p in (REPO/source.ARCHIVE).iterdir()}
        for directory in (source.ARCHIVE, source.API_ARCHIVE):
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                producer.generate(REPO, REPO/directory/'child')
        self.assertFalse((REPO/source.ARCHIVE/'child').exists())
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO/source.ARCHIVE).iterdir()})
        with tempfile.TemporaryDirectory(prefix='bp-n-') as temp:
            output = Path(temp)
            files = producer.generate(REPO, output)
            self.assertEqual(8, len(files))
            self.assertEqual(8, len(set(files)))
            for relative in files:
                self.assertTrue(re.search(r'^package (\S+)', text(output/relative), re.M).group(1)
                                .replace('.', '/') == Path(relative).parent.as_posix(), relative)
            snapshot = {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*') if p.is_file()}
            self.assertEqual(67, sum(text(p).count('@Test') for p in (output/'policy-tests').rglob('*.kt')))
            declarations = {}
            for relative in files:
                for kind, name in re.findall(r'(?m)^(?:internal |private |public )?(?:data |enum |suspend |inline )*'
                                              r'(class|interface|object|fun)\s+(\w+)', text(output/relative)):
                    declarations.setdefault((kind, name), []).append(relative)
            for name in ['DesktopDynamicAuthorUpdatesApi', 'DesktopDynamicAuthorUpdatesService', 'DesktopDynamicPersonalFeedApi',
                         'DynamicAuthorUpdatesPortalResponse', 'DynamicAuthorUpdateItem', 'MessageNotificationPoller',
                         'MessageNotificationSource', 'AccountNotificationState', 'collectDynamicAuthorUpdates']:
                owners = [v for (kind, n), v in declarations.items() if n == name]
                self.assertEqual(1, sum(len(v) for v in owners), name)
            hand_written = '\n'.join(text(p) for p in (REPO/'desktop/src/main').rglob('*.kt'))
            for name in ['DesktopDynamicAuthorUpdatesService', 'collectDynamicAuthorUpdates', 'DynamicAuthorUpdateItem']:
                self.assertEqual(0, len(re.findall(r'(?m)^(?:internal |private )?(?:data |suspend )*(?:class|interface|object|fun)\s+%s\b' % name, hand_written)), name)
            receipt = json.loads((output/'v046-message-notification-source-proof.json').read_bytes())
            self.assertEqual(source.MANIFEST_SHA256, receipt['sourceManifestSha256'])
            self.assertEqual(source.COMMIT, receipt['upstreamCommit'])
            self.assertFalse(receipt['readOffsetWatermarkReintroduced'])
            self.assertFalse(receipt['jvmTestsExecuted'])
            self.assertEqual(10, len(receipt['files']))
            # Every row says which commit its bytes come from: the shared v038 adapt() would stamp v0.3.8.
            self.assertNotEqual(old.COMMIT, source.COMMIT)
            self.assertEqual({source.COMMIT}, {row['upstreamCommit'] for row in receipt['files']})
            self.assertEqual({source.COMMIT}, {proof['upstreamCommit'] for _, proof in self.main.values()})
            self.assertEqual({source.COMMIT}, {proof['upstreamCommit'] for _, proof in self.tests.values()})
            producer.generate(REPO, output)
            self.assertEqual(snapshot, {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*') if p.is_file()})

    def test_gradle_inventory_and_catalog_pin_the_new_archive(self):
        build = text(REPO/'desktop/build.gradle.kts')
        at = build.index('val extractOriginalMessageNotifications by tasks.registering(')
        block = build[at:build.index('\n}', at)]
        for line in ('inputs.file("tools/v046_message_notifications.py")', 'inputs.dir("upstream-slices/v046-message-notifications")',
                     'inputs.dir("upstream-slices/v046-favorite-editor")', 'inputs.file("tools/v038_message_notifications.py")',
                     'inputs.dir("upstream-slices/v038-message-notifications")'):
            self.assertEqual(1, block.count(line), line)
        self.assertEqual(1, build.count('val extractOriginalMessageNotifications by tasks.registering('))
        rows = [row for row in self.sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(source.ARCHIVE+'/')]
        self.assertEqual(9, len(rows))
        self.assertTrue(all(not row['referenceOnly'] and row['features'] == ['v046-message-notifications'] for row in rows))
        self.assertTrue(all(row['upstreamCommit'] == source.COMMIT and row['hashNormalization'] == 'raw' for row in rows))
        self.assertEqual({'MessageNotificationChecker.kt', 'DynamicModels.kt'},
                         {Path(row['localPath']).name for row in rows if row['partialReuse']})
        catalog = json.loads((REPO/'desktop/tools/compatibility-source-inputs.json').read_bytes())
        self.assertIn(source.ARCHIVE, catalog['buildInputRoots'])
        archive = next(item for item in catalog['archives'] if item['directory'] == source.ARCHIVE)
        self.assertEqual(11, len(archive['files']))
        # The v038 archive stays registered and byte-identical: it is the pinned history this slice replaces.
        self.assertIn(old.ARCHIVE.as_posix(), catalog['buildInputRoots'])

    def test_windows_clear_on_read_degrades_to_the_tray_menu_and_says_so(self):
        entries = text(UI/'DesktopMessageNotificationEntries.kt')
        windows = text(UI/'DesktopWindowsMessageNotifications.kt')
        consumer = text(UI/'DesktopMessageNotificationRootConsumer.kt')
        self.assertIn('pendingCounts: Map<String, Int>? = null', entries)
        self.assertIn('if (children == 0 && (pendingCounts[group] ?: 0) == 0) items.remove(summary)', entries)
        self.assertIn('pendingCounts: Map<String, Int>? = null) = withContext(Dispatchers.Main) {', windows)
        self.assertEqual(1, windows.count('entries.apply(pending, cancelled, pendingCounts)'))
        self.assertIn('cannot be withdrawn', windows)
        self.assertEqual(1, consumer.count('pendingCounts = desktopPendingSummaryCounts(value.state)'))
        self.assertEqual(1, consumer.count('notifications.postAll(batch, cancelledKeys, pendingCounts)'))
        # Balloons are never claimed to be withdrawn: the only balloon call submits one, nothing pretends to retract it.
        self.assertEqual(1, windows.count('displayMessage('))
        self.assertNotIn('removeBalloon', windows)
        settings = text(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopMessageNotificationSettingsContent.kt')
        self.assertIn('已弹出的托盘气泡无法撤回', settings)
        self.assertIn('需要 TV 授权登录', settings)

    def test_summary_keys_and_groups_the_menu_relies_on_still_come_from_the_original_poller(self):
        """DesktopMessageNotificationEntries hard-codes "<group>:summary" and the dm:/dynamic: key prefixes.
        If a later upstream re-pin renamed any of them, the summary cleanup would silently do nothing."""
        poller = self.body('checker')
        summaries = re.findall(r'PendingMessageNotification\(\s*"([a-z]+):summary",[^\n]*?,\s*"([a-z]+)",\s*isGroupSummary = true,',
                               poller)
        # Exactly the two group summaries, each carrying the group its key prefix names.
        self.assertEqual(2, poller.count('isGroupSummary = true'))
        self.assertEqual([('dm', 'dm'), ('dynamic', 'dynamic')], sorted(summaries))
        self.assertIn('"dm:summary" in activeNotificationKeys', poller)
        self.assertIn('"dynamic:summary" in activeNotificationKeys', poller)
        # Visible children and cancellations use the same prefixes the menu maps back to a group.
        self.assertEqual(1, poller.count('cancelNotificationKeys += "dm:$key"'))
        self.assertEqual(1, poller.count('cancelNotificationKeys += "dynamic:$id"'))
        self.assertEqual(2, poller.count('"dm:$key"'))
        self.assertEqual(1, poller.count('"dynamic:${item.id_str}"'))
        entries = text(UI/'DesktopMessageNotificationEntries.kt')
        self.assertIn('val summary = "$group:summary"', entries)
        self.assertIn('key.startsWith("dynamic:") -> "dynamic"', entries)
        self.assertIn('key.startsWith("dm:") -> "dm"', entries)
        groups = {group for _, group in summaries}
        # The pending counts the consumer hands over are keyed by exactly those groups.
        counts = text(UI/'DesktopMessageNotificationAccuracy.kt')
        keyed = set(re.findall(r'"(\w+)" to state\.pending', counts))
        self.assertEqual(groups, keyed)
        self.assertIn('"dynamic" to state.pendingDynamicIds.size', counts)
        self.assertIn('"dm" to state.pendingPrivateSessionKeys.size', counts)
        self.assertIn('val pendingDynamicIds: Map<String, Long> = emptyMap()', self.body('state'))
        self.assertIn('val pendingPrivateSessionKeys: Set<String> = emptySet()', self.body('state'))

    def test_new_and_changed_kotlin_keep_balanced_delimiters_and_the_jvm_regression_class_is_counted(self):
        files = [UI/'DesktopMessageNotificationAccuracy.kt', UI/'DesktopMessageNotificationEntries.kt',
                 UI/'DesktopMessageNotificationContext.kt', UI/'DesktopMessageNotificationRootConsumer.kt',
                 UI/'DesktopWindowsMessageNotifications.kt',
                 REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopMessageNotificationSettingsContent.kt',
                 REPO/'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopMessageNotificationAccuracyTest.kt']
        generated = [(key, self.body(key)) for key in self.main] + [(name, body) for name, (body, _) in self.tests.items()]
        for label, body in [(f.name, text(f)) for f in files] + generated:
            stack = []
            for token, first, _ in self.sync.kotlin_tokens(body):
                if token in ['(', '[', '{']:
                    stack.append(token)
                elif token in [')', ']', '}']:
                    self.assertTrue(stack, (label, first))
                    self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (label, first))
            self.assertEqual([], stack, label)
        regression = text(files[-1])
        self.assertEqual(12, len(re.findall(r'(?m)^    @Test\n    fun ', regression)))
        self.assertEqual(4, text(REPO/'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopMessageNotificationEntriesTest.kt').count('@Test'))
        self.assertNotIn('SettingsManager.', regression)


if __name__ == '__main__':
    unittest.main()
