"""Offline receipts only: no Gradle, Main, native window, account or HTTP."""
from pathlib import Path
import copy, hashlib, importlib.util, json, struct, tempfile, unittest

TOOLS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('comment_ui_runner_under_test', TOOLS / 'run-comment-search-ui.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

class CommentUiCaseReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='comment-case-receipts-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.root / 'report'; self.report.mkdir()
        self.local = self.root / 'local'; self.local.mkdir()
        self.token = 'offline-owned-token'
        self.health = self.local / 'startup-health.txt'; self.health.write_text(self.token)
        self.health.with_name('startup-version.txt').write_text('0.2.427.24')
        self.process = dict(exitCode=0, forcedCleanup=False, cleanupCompleted=True)
        self.png = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 48, 32)

    def write(self, observations, transport, case):
        (self.report / 'observations.json').write_text(json.dumps(observations), encoding='utf-8')
        (self.report / 'local-replay-receipt.json').write_text(json.dumps(transport), encoding='utf-8')
        for name in RUNNER.CAPTURES_BY_CASE[case]:
            (self.report / (name + '-screen.png')).write_bytes(self.png)
            (self.report / (name + '-accessibility.tsv')).write_text('original owned controls\n')
        if case == 'composer': (self.report / 'composer-private-image.png').write_bytes(self.png)

    def evidence(self, case):
        observation = dict(allPreExitAssertionsPassed=True, sameLiveRootAndWindow=True,
            apiReplayInjected=True, loopbackMediaInjected=True, actualMainInvocations=1,
            defaultRenderer='DIRECT3D', realAccountUsed=False, guestRealApi=False,
            ordinaryFullscreenResizeRegressionExecuted=False, commentSearchFourKTested=False)
        for key in ('composerInputProofRequested','composerInputProofCompleted','syntheticAccountSeededThroughActualSessionStore',
                    'commentSearchProofRequested','commentSearchInputProofCompleted','commentSearchReadResponsesAreSynthetic',
                    'commentSearchPhysicalTextHumanReviewRequired','commentPublishingAccepted','imageUploadAccepted','loginUiAccepted',
                    'commentsSent','nvidiaUiProofRequested','nvidiaUiProofCompleted','interactionProofRequested','interactionProofCompleted',
                    'featureInputProofCompleted','hotInputProofRequested','hotInputProofCompleted','collectionInputProofRequested',
                    'collectionInputProofCompleted','videoMetadataProofCompleted','bgmInputProofRequested','bgmInputProofCompleted',
                    'pipInputProofRequested','pipInputProofCompleted','originalInteractionProofRequested','originalInteractionProofCompleted',
                    'physicalStackWrittenByFixture','directPhysicalStackListMutation','newNativeActorCreatedByFixture','newRootCreatedByFixture'):
            observation[key] = False
        def row(id, **values): return dict(id=id, actualWindowIdentity=701,
                sameRootAndRouteAssembly=True, **values)
        back = row('160-original-back-home', physicalStack=['MainHost'])
        transport = dict(realAccountUsed=False, commentSearchResponsesAreSynthetic=False,
            commentSearch=None, composerInputResponsesAreSynthetic=False, composerInput=None,
            apiRequests=[dict(method='POST', host='app.bilibili.com',
                path='/bilibili.main.community.reply.v1.Reply/MainList')])
        if case == 'search':
            for key in ('commentSearchProofRequested','commentSearchInputProofCompleted',
                        'commentSearchReadResponsesAreSynthetic','commentSearchPhysicalTextHumanReviewRequired'):
                observation[key] = True
            proof = row('186-original-comment-search-completed', sameOriginalCommentVm=True,
                sameAcceptedPublicationIdentity=True, samePausedNativeSourceAndPreferences=True,
                mainCommentsUnchanged=True, actualOriginalCloseRetryScopeSortAndSubreplyConsumed=True,
                physicalOwnedDialogCapturesCollected=True)
            observation['observations'] = [proof, back]
            transport.update(commentSearchResponsesAreSynthetic=True, commentSearch=dict(
                actualOptionalCallCancellationObserved=True, grpcAndRestErrorStageObserved=True,
                originalTwoPageLoadCompleted=True, chargedControlProtobufField=31, subReplyOriginalRootRequested=91001))
        else:
            for key in ('composerInputProofRequested','composerInputProofCompleted','syntheticAccountSeededThroughActualSessionStore'):
                observation[key] = True
            session = row('composer-synthetic-session-actual-root-generation', sameActualRepository=True,
                originalGuestEntryAndRoutesRetired=True, actualRetainedHomeGenerationChanged=True,
                sameNativeMainWindow=True, sameWindowLevelRootHandle=True, actualAccountEpoch=1,
                syntheticPrimaryMid=990000024, loginUiAccepted=False)
            proof = row('composer-original-input-closed-without-publish', sameActualComposerDomain=True,
                sourcePausedAndPreferencesPreserved=True, textDraftRestored=True,
                originalEmoteAndMentionInserted=True, originalSyncFlagRestored=True,
                realOwnedOsChooserPrivatePngSelected=True, selectedImageRemoved=True,
                publishClicked=False, realCredentialsUsed=False, originalImageUploadAccepted=False)
            observation['observations'] = [session, proof, back]
            transport.update(sameActualRepository=True, composerInputResponsesAreSynthetic=True,
                composerInput=dict(syntheticSessionSeededThroughActualStore=True, syntheticPrimaryMid=990000024,
                    syntheticAccountEpoch=1, loginUiAccepted=False, realAccountUsed=False, mutationRequestsPermitted=False,
                    imageFile='composer-private-image.png', imageSha256=hashlib.sha256(self.png).hexdigest(),
                    imageCreatedByFixture=True, emoteImagesArePrivateFiles=True,
                    reads=[dict(kind='emote', path='/x/emote/package'), dict(kind='mention', query='合成')]))
            for key in ('realBilibiliDataAccepted','newRootCreated','newPlayerCreated','newControllerCreated',
                        'originalVmStateWritten','actualNativeStateWritten','physicalStackWritten','commentsSent',
                        'creatorFollowMutationSubmitted','bgmAccountMutationSubmitted','originalInteractionRemoteMutationSubmitted',
                        'collectionSubscriptionMutationSubmitted'):
                transport[key] = False
        return observation, transport

    def verify(self, case, observations=None, transport=None):
        if observations is None: observations, transport = self.evidence(case)
        self.write(observations, transport, case)
        return RUNNER.verify(self.report, self.local, self.health, self.token, self.process, case)

    def test_search_existing_original_proof_and_eight_captures_remain_required(self):
        result = self.verify('search')
        self.assertEqual([x['path'] for x in result], [x + '-screen.png' for x in RUNNER.CAPTURES])
        observation, transport = self.evidence('search')
        observation['observations'][0]['sameAcceptedPublicationIdentity'] = False
        with self.assertRaisesRegex(ValueError, 'original comment'): self.verify('search', observation, transport)

    def test_composer_complete_actual_generation_store_and_controls_evidence(self):
        result = self.verify('composer')
        self.assertEqual(len(result), 8)
        self.assertEqual(result[4]['path'], '214-composer-owned-os-chooser-screen.png')

    def test_runtime_receipt_binds_mode_and_same_original_task(self):
        runtime = dict(schema=1, commentUiCase='search', task='windowsVideoLocalReplayUiSmoke',
            mainClass='com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture', classpath=['original.jar'],
            javaExecutable='fixed/bin/java.exe', applicationResources='original/resources')
        RUNNER.validate_runtime(runtime, 'search')
        with self.assertRaisesRegex(ValueError, 'runtime/case'): RUNNER.validate_runtime(runtime, 'composer')
        runtime['commentUiCase'] = 'composer'; RUNNER.validate_runtime(runtime, 'composer')
        for key, bad in (('commentUiCase', None), ('task', 'secondMain'), ('classpath', []), ('mainClass', 'other')):
            value = copy.deepcopy(runtime); value[key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): RUNNER.validate_runtime(value, 'composer')

    def test_cross_mode_observation_receipts_cannot_satisfy_other_case(self):
        for original, requested in (('search','composer'), ('composer','search')):
            observation, transport = self.evidence(original)
            with self.subTest(original=original), self.assertRaises((ValueError, KeyError)):
                self.verify(requested, observation, transport)

    def test_composer_account_epoch_store_source_pause_and_current_window_gates(self):
        for row_index, key, bad in ((0,'actualAccountEpoch',True),(0,'syntheticPrimaryMid',42),
                (0,'originalGuestEntryAndRoutesRetired',False),(1,'sourcePausedAndPreferencesPreserved',False),
                (1,'sameActualComposerDomain',False),(2,'actualWindowIdentity',702)):
            observation, transport = self.evidence('composer'); observation['observations'][row_index][key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('composer', observation, transport)
        observation, transport = self.evidence('composer'); transport['composerInput']['syntheticAccountEpoch'] = 2
        with self.assertRaises(ValueError): self.verify('composer', observation, transport)

    def test_composer_mutations_login_real_credentials_and_other_modes_are_rejected(self):
        for key in ('imageUploadAccepted','commentPublishingAccepted','loginUiAccepted','pipInputProofCompleted','realAccountUsed'):
            observation, transport = self.evidence('composer'); observation[key] = True
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('composer', observation, transport)
        for key in ('mutationRequestsPermitted','syntheticSessionSeededThroughActualStore'):
            observation, transport = self.evidence('composer')
            transport['composerInput'][key] = key == 'mutationRequestsPermitted'
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('composer', observation, transport)
        observation, transport = self.evidence('composer'); observation['observations'][1]['publishClicked'] = True
        with self.assertRaises(ValueError): self.verify('composer', observation, transport)

    def test_only_existing_readonly_mainlist_detaillist_posts_remain_allowed(self):
        for case in ('search', 'composer'):
            for request in (dict(method='POST',host='app.bilibili.com',path='/x/v2/reply/add'),
                            dict(method='POST',host='other.example',path='/bilibili.main.community.reply.v1.Reply/MainList')):
                observation, transport = self.evidence(case); transport['apiRequests'] = [request]
                with self.subTest(case=case,request=request), self.assertRaises(ValueError): self.verify(case, observation, transport)
        observation, transport = self.evidence('composer')
        transport['apiRequests'] = [dict(method='GET',host='api.bilibili.com',path='/x/relation/modify')]
        with self.assertRaises(ValueError): self.verify('composer', observation, transport)

    def test_original_emote_mention_and_private_image_pin_are_required(self):
        for key, bad in (('reads', []), ('imageSha256','0' * 64), ('imageFile','outside.png')):
            observation, transport = self.evidence('composer'); transport['composerInput'][key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('composer', observation, transport)

    def test_eighth_capture_and_tsv_cannot_be_omitted(self):
        observation, transport = self.evidence('composer'); self.write(observation, transport, 'composer')
        (self.report / '217-composer-image-removed-accessibility.tsv').unlink()
        with self.assertRaises(ValueError):
            RUNNER.verify(self.report, self.local, self.health, self.token, self.process, 'composer')

    def test_nonnatural_process_exit_is_not_functional_acceptance(self):
        self.process['forcedCleanup'] = True
        with self.assertRaisesRegex(ValueError, 'naturally'): self.verify('composer')

    def test_invalid_case_has_no_fallback(self):
        with self.assertRaises(ValueError): RUNNER.validate_ui_case('other')

if __name__ == '__main__': unittest.main()
