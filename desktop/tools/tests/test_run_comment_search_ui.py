"""Offline receipts only: no Gradle, Main, native window, account or HTTP."""
from pathlib import Path
import copy, hashlib, importlib.util, json, struct, tempfile, unittest

TOOLS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('comment_ui_runner_under_test', TOOLS / 'run-comment-search-ui.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

def blocked_heartbeat_record():
    return dict(stage='requestObserved', scheme='https', host='api.bilibili.com', port=443,
        path='/x/click-interface/web/heartbeat', method='POST', hasQuery=False, hasFragment=False)


def malformed_heartbeat_records():
    for key, value in (('stage', 'memoryResponse'), ('stage', None), ('scheme', 'http'), ('host', 'other.invalid'),
            ('port', 80), ('port', 443.0), ('hasQuery', True), ('hasQuery', 0), ('hasFragment', True),
            ('path', '/x/unknown'), ('method', 'DELETE'), ('remoteMutationSent', False)):
        row = blocked_heartbeat_record(); row[key] = value; yield row


def stable_attachment_evidence():
    def rect(x,y,width,height): return dict(x=x,y=y,width=width,height=height)
    classes = dict(pane='com.bilipai.desktop.ui.DesktopInlineEmotePane', viewport='javax.swing.JViewport',
        scroll='com.bilipai.desktop.ui.DesktopCommentEmoteScrollPane', interopGroup='androidx.compose.ui.awt.SwingInteropViewGroup')
    geometry = dict(sameOwnedEditor=True)
    for index,(name,clazz) in enumerate(classes.items()):
        geometry[name] = dict(identity=100+index, **{'class':clazz}, bounds=rect(0,0,500,350),
            screenBounds=rect(30,80,500,350), visibleRect=rect(0,0,500,350), opaque=True,showing=True)
    geometry['interopGroup']['bounds'].update(x=10,y=50)
    text = '私有编辑器草稿[夹具表情] @合成好友'
    fixed = dict(dialogIdentity=42,dialogHwnd=12345,dialogBounds=rect(10,10,660,620),clientBounds=rect(20,30,640,580),
        geometry=geometry,documentIdentity=51,caretIdentity=52,caretDot=0,caretMark=0,rawText=text,
        selectionStart=0,selectionEnd=0,composition=None,viewportPosition=dict(x=0,y=0),draftText=text,syncToDynamic=True,
        publishBounds=rect(570,550,60,40))
    def row(id,serial,**values): return dict(id=id,serial=serial,keyType='Video',sameRootAndRouteAssembly=True,
        actualWindowIdentity=701,contract='windows-comment-attachments-stable-editor/v1',forcedRepaintOrLayout=False,
        physicalPixelsRequireReview=True,wholeUiPhysicalPass=False,**values)
    immediate = row('composer-stable-image-215-v1',20,dialogIdentity=41,beforeGeometry=copy.deepcopy(geometry),
        immediateGeometry=copy.deepcopy(geometry),robotStartNanos=-10,robotEndNanos=10,selectedImageCount=1,
        sameDialogAndEditor=True,original215Retained=True)
    states = []
    for stage,indices in [('zero',[]),('cancel-zero',[]),('one',[0]),('nine',list(range(9))),
            ('cancel-nine',list(range(9))),('nine-last-visible',list(range(9)))]+[
            ('removed-%d'%n,list(range(n))) for n in range(8,-1,-1)]:
        states.append(dict(stage=stage,state=copy.deepcopy(fixed),assetIndices=indices))
    tools=dict(before=0.0,after=100.0,maximum=100.0,viewport=rect(40,550,220,40))
    attachments=dict(before=0.0,after=360.0,maximum=360.0,viewport=rect(330,550,180,40))
    proof=row('composer-stable-attachments-v1',21,clientWidthBeforeBaseline=640,states=states,
        toolScroll=tools,imageButtonAtToolEnd=rect(220,550,32,32),attachmentScroll=attachments,
        removals=[dict(beforeCount=n,afterCount=n-1,removedAssetIndex=n-1,hitBounds=rect(470,550,20,20),thumbnailBounds=rect(450,550,40,40),
            effectiveViewport=rect(330,550,180,40),scrollBefore=float(max(0,(n-3)*40))) for n in range(9,0,-1)],
        privateUniqueAssetCount=9,realRobotInput=True,selectedFilesInjected=False,draftWrittenByFixtureInPhase=False)
    data = {('composer-private-additional-images/%02d.png' % n): bytes([n])*64 for n in range(2,10)}
    assets = [dict(file=name,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()) for name,raw in data.items()]
    return [immediate,proof], assets, data


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
        if case == 'fullscreen':
            for name in RUNNER.CAPTURES_BY_CASE[case] + ['110-ordinary-playing', '160-original-back-home']:
                (self.report / (name + '.png')).write_bytes(self.png)
                (self.report / (name + '-frame.txt')).write_text('owned fixed Main frame\n')
                (self.report / (name + '-accessibility.tsv')).write_text('owned Main controls\n')
            (self.report / '110-ordinary-playing-native.png').write_bytes(self.png)
        if case in ('composer', 'feedback'): (self.report / 'composer-private-image.png').write_bytes(self.png)
        if case == 'video_share':
            (self.report / 'video-share-payload-receipt.json').write_text(json.dumps(transport['videoDynamicShare']), encoding='utf-8')

    def evidence(self, case):
        observation = dict(allPreExitAssertionsPassed=True, sameLiveRootAndWindow=True,
            apiReplayInjected=True, loopbackMediaInjected=True, actualMainInvocations=1,
            defaultRenderer='DIRECT3D', realAccountUsed=False, guestRealApi=False,
            ordinaryFullscreenResizeRegressionExecuted=False, commentSearchFourKTested=False)
        for key in ('composerInputProofRequested','composerInputProofCompleted','syntheticAccountSeededThroughActualSessionStore',
                    'brandFeedbackPlacementProofRequested','brandFeedbackPlacementProofCompleted','brandFeedbackPhysicalFramesRequireHumanReview',
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
            brandFeedbackPlacementInput=False, brandFeedbackPlacement=None,
            apiRequests=[dict(method='POST', host='app.bilibili.com',
                path='/bilibili.main.community.reply.v1.Reply/MainList')])
        if case == 'fullscreen':
            observation['ordinaryFullscreenResizeRegressionExecuted'] = True
            def state(paused=False): return dict(sourceVersion=7, ready=True, loading=False, ended=False,
                firstVideoFrameReady=True, hasError=False, volume=0.0, muted=True, nativePaused=paused, positionSeconds=17.0)
            baseline = row('110-ordinary-playing', sameAcceptedSourceVersion=7, fullImmutableSourceStillOwned=True,
                nativeState=state(), clockBefore=1.0, clockAfter=3.0, actualNativeScreenshot='110-ordinary-playing-native.png',
                nativeScreenshotWidth=48, nativeScreenshotHeight=32, sampledNativeColourCount=5, windowPlacement='Floating')
            core = []
            for id in RUNNER.CAPTURES_BY_CASE[case]:
                values = dict(sameAcceptedSourceVersion=7, fullImmutableSourceStillOwned=True, sameActualCanvasRetained=True,
                    nativeState=state(id == '123-fullscreen-paused-hold'), physicalVideoPixelsIndependentlyChecked=False,
                    captureStateHeldAcrossRead=True, nativeCanvasBoundsMatched=True, physicalCanvasInputDelivered=True,
                    physicalScreenHumanReviewRequired=True, screenCaptureFile=id + '-screen.png',
                    screenCaptureClientBounds=dict(x=0, y=0, width=48, height=32))
                if id == '121-fullscreen-idle-hidden': values.update(idleMillis=4100, clockBefore=10.0, clockAfter=14.5,
                    shownCanvasHeight=780, hiddenCanvasHeight=900, topAndBottomControlsHidden=True)
                elif id == '122-fullscreen-mouse-restored': values.update(inputMechanism='OS_ROBOT_MOUSE_MOVE',
                    topAndBottomControlsRestored=True)
                else: values.update(nativePauseAcknowledged=True, controlsStayedVisible=True, menuHoldExecuted=False)
                core.append(row(id, **values))
            ordinary = [row(id, sameAcceptedSourceVersion=7, fullImmutableSourceStillOwned=True, nativeState=state(),
                clockBefore=1.0, clockAfter=3.0, windowPlacement='Fullscreen' if id == '120-fullscreen-playing' else 'Floating')
                for id in ('120-fullscreen-playing', '130-fullscreen-exit-playing', '140-resized-playing', '150-restored-playing')]
            bounded = row('comment-search-bounded-main', scope='ONLY_ACTUAL_AVAILABLE_RUNNER_VIEWPORT', x=0, y=0,
                width=1024, height=684, fourKTested=False, fullscreenResizeRegressionExecuted=False, appScaleChangedByFixture=False)
            observation['observations'] = [baseline] + core + ordinary + [bounded, back]
            transport.update(sameActualRepository=True, realBilibiliDataAccepted=False, newRootCreated=False,
                newPlayerCreated=False, newControllerCreated=False, originalVmStateWritten=False,
                actualNativeStateWritten=False, physicalStackWritten=False, qualityMetadataIsSynthetic=True,
                codecMetadataIsSynthetic=True, container='MJPEG_AVI_PLUS_PCM_WAV', videoDynamicShareInput=False,
                videoDynamicShare=None, chapterMetadataIsSynthetic=False, collectionMetadataIsSynthetic=False,
                videoMetadataIsSynthetic=False, bgmMetadataIsSynthetic=False, bgmDetailAndRecommendResponsesAreSynthetic=False,
                singleBgmDetailOnlyScope=False, originalInteractionMetadataIsSynthetic=False, commentsSent=False,
                creatorFollowMutationSubmitted=False, bgmAccountMutationSubmitted=False,
                originalInteractionRemoteMutationSubmitted=False, collectionSubscriptionMutationSubmitted=False,
                realDASHCodecAccepted=False, apiRequests=[dict(method='GET', path='/x/web-interface/view'),
                dict(method='GET', path='/x/player/wbi/playurl')], loopbackRequests=[dict(file='video.avi', method='GET'),
                dict(file='audio.wav', method='GET')])
        elif case == 'search':
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
        if case == 'feedback':
            stable_rows, private_images, private_bytes = stable_attachment_evidence()
            transport['composerInput']['additionalPrivateImages'] = private_images
            for name, raw in private_bytes.items():
                path = self.report / name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(raw)
            for key in ('brandFeedbackPlacementProofRequested','brandFeedbackPlacementProofCompleted','brandFeedbackPhysicalFramesRequireHumanReview'):
                observation[key] = True
            observation['observations'] += [
                row('feedback-owned-modal-hides-same-peer', samePeer=True, actualDialogModal=True, sameFullSource=True,
                    liveOwnedModalOverlapObserved=True, liveOwnedChooserOverlapObserved=True),
                row('feedback-full-client-actual-main-scope', actualOriginalLikeProtocol=True, inputMechanism='OS_ROBOT',
                    sameActualComposerCommentsAndEngagement=True, ownedModalAndChooserObserved=True,
                    sourcePausePreferencesPreserved=True, sameFullSource=True, physicalFramesRequireHumanReview=True,
                    liveOwnedModalOverlapObserved=True, liveOwnedChooserOverlapObserved=True, liveOwnerMinimizedOverlapObserved=True,
                    samePeerModalRestoreObserved=False, samePeerMinimizeRestoreObserved=False, liveVideoFallbackNavigationObserved=False,
                    remoteMutationSent=False)]
            transport.update(brandFeedbackPlacementInput=True, brandFeedbackPlacement=dict(
                actualOriginalLikeProtocolConsumed=True, syntheticResponsesOnly=True, remoteMutationSent=False,
                otherMutationPermitted=False, realCredentialsUsed=False, actions=[1,2,1,2,1]))
            transport['apiRequests'] += [item for _ in range(5) for item in (
                dict(stage='requestObserved', scheme='https', port=443, hasQuery=False, hasFragment=False,
                    method='POST', host='api.bilibili.com', path='/x/web-interface/archive/like'),
                dict(method='POST', host='api.bilibili.com', path='/x/web-interface/archive/like',
                    originalLikeProtocolMemoryOnly=True, remoteMutationSent=False))]
        if case == 'feedback': observation['observations'] += stable_rows
        return observation, transport

    def verify(self, case, observations=None, transport=None):
        if observations is None: observations, transport = self.evidence(case)
        self.write(observations, transport, case)
        return RUNNER.verify(self.report, self.local, self.health, self.token, self.process, case)

    def test_stable_attachment_contract_rejects_shrink_and_document_replacement(self):
        for target in ('height','document','caret','selection','viewport'):
            obs, transport = self.evidence('feedback')
            state = next(row for row in obs['observations'] if row['id'] == 'composer-stable-attachments-v1')['states'][3]['state']
            if target == 'height': state['geometry']['scroll']['bounds']['height'] -= 1
            elif target == 'document': state['documentIdentity'] += 1
            elif target == 'caret': state['caretIdentity'] += 1
            elif target == 'selection': state['caretDot'] = state['selectionEnd'] = 1
            else: state['viewportPosition']['y'] = 1
            with self.subTest(target=target), self.assertRaises(ValueError): self.verify('feedback',obs,transport)

    def test_stable_attachment_contract_rejects_clipped_hit_and_false_overflow(self):
        for target in ('hit','motion','last','cancel','injection'):
            obs, transport = self.evidence('feedback')
            row = next(row for row in obs['observations'] if row['id'] == 'composer-stable-attachments-v1')
            if target == 'hit': row['removals'][0]['hitBounds']['x'] = 510
            elif target == 'motion': row['toolScroll']['before'] = row['toolScroll']['after']
            elif target == 'last': row['removals'][0]['removedAssetIndex'] = 0
            elif target == 'cancel': row['states'][4]['assetIndices'] = list(range(8))
            else: row['selectedFilesInjected'] = True
            with self.subTest(target=target), self.assertRaises(ValueError): self.verify('feedback',obs,transport)

    def test_stable_attachment_contract_requires_new_record_and_private_file_bytes(self):
        obs, transport = self.evidence('feedback')
        obs['observations'] = [row for row in obs['observations'] if row['id'] != 'composer-stable-image-215-v1']
        with self.assertRaises(ValueError): self.verify('feedback',obs,transport)
        obs, transport = self.evidence('feedback')
        (self.report / transport['composerInput']['additionalPrivateImages'][0]['file']).write_bytes(b'changed')
        with self.assertRaises(ValueError): self.verify('feedback',obs,transport)

    def test_stable_attachment_capture_clock_wrap_and_physical_scope(self):
        rows, assets, _ = stable_attachment_evidence()
        rows[0]['robotStartNanos'] = (1 << 63)-10
        rows[0]['robotEndNanos'] = -(1 << 63)+10
        result = RUNNER.verify_stable_attachments_v1({row['id']:row for row in rows}, assets)
        self.assertFalse(result['composerWholeUiPhysicalPass'])
        rows[0]['robotStartNanos'],rows[0]['robotEndNanos'] = rows[0]['robotEndNanos'],rows[0]['robotStartNanos']
        with self.assertRaises(ValueError): RUNNER.verify_stable_attachments_v1({row['id']:row for row in rows}, assets)

    def test_blocked_heartbeat_is_only_an_observation_in_composer_and_feedback(self):
        for case in ('composer', 'feedback'):
            obs, transport = self.evidence(case); transport['apiRequests'] += [blocked_heartbeat_record() for _ in range(3)]
            self.verify(case, obs, transport)
            for bad in malformed_heartbeat_records():
                obs, transport = self.evidence(case); transport['apiRequests'].append(bad)
                with self.subTest(case=case, row=bad), self.assertRaises(ValueError): self.verify(case, obs, transport)
        for case in ('search', 'fullscreen'):
            obs, transport = self.evidence(case); transport['apiRequests'].append(blocked_heartbeat_record())
            with self.subTest(case=case), self.assertRaises(ValueError): self.verify(case, obs, transport)

    def test_feedback_requires_five_ordered_observation_and_fulfillment_pairs(self):
        for change in ('missing_observed', 'extra_observed', 'missing_fulfilled', 'extra_fulfilled', 'orphan_fulfilled',
                       'foreign_host', 'foreign_scheme', 'wrong_port', 'query', 'fragment', 'extra_marker'):
            obs, transport = self.evidence('feedback')
            rows = transport['apiRequests']; at = next(i for i,r in enumerate(rows) if r.get('path') == '/x/web-interface/archive/like')
            if change == 'missing_observed': rows.pop(at)
            elif change == 'extra_observed': rows.insert(at, dict(rows[at]))
            elif change == 'missing_fulfilled': rows.pop(at + 1)
            elif change == 'extra_fulfilled': rows.insert(at + 1, dict(rows[at + 1]))
            elif change == 'orphan_fulfilled': rows[at], rows[at + 1] = rows[at + 1], rows[at]
            else:
                key,value = dict(foreign_host=('host','other.invalid'),foreign_scheme=('scheme','http'),wrong_port=('port',80),
                    query=('hasQuery',True),fragment=('hasFragment',True),extra_marker=('remoteMutationSent',False))[change]
                rows[at][key] = value
            with self.subTest(change=change), self.assertRaises(ValueError): self.verify('feedback', obs, transport)

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

    def test_feedback_reuses_complete_composer_proof_and_all_sixteen_captures(self):
        result = self.verify('feedback')
        self.assertEqual(len(result), 21)
        self.assertEqual([item['path'] for item in result[:8]],
            [name + '-screen.png' for name in RUNNER.CAPTURES_BY_CASE['composer']])
        self.assertEqual(result[-1]['path'], '227-feedback-video-fallback-screen.png')
        for key, bad in (('sourcePausedAndPreferencesPreserved', False), ('publishClicked', True)):
            observations, transport = self.evidence('feedback')
            observations['observations'][1][key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('feedback', observations, transport)
        observations, transport = self.evidence('feedback')
        transport['composerInput']['imageSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Private chooser image'): self.verify('feedback', observations, transport)

    def test_feedback_runtime_and_cross_case_receipts_have_no_fallback(self):
        runtime = dict(schema=1, commentUiCase='feedback', task='windowsVideoLocalReplayUiSmoke',
            mainClass='com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture', classpath=['original.jar'],
            javaExecutable='fixed/bin/java.exe', applicationResources='original/resources')
        RUNNER.validate_runtime(runtime, 'feedback')
        for requested in ('search', 'composer'):
            with self.subTest(requested=requested), self.assertRaises(ValueError): RUNNER.validate_runtime(runtime, requested)
        for original in ('search', 'composer', 'feedback'):
            for requested in ('search', 'composer', 'feedback'):
                if original == requested: continue
                observations, transport = self.evidence(original)
                with self.subTest(original=original, requested=requested), self.assertRaises((ValueError, KeyError)):
                    self.verify(requested, observations, transport)

    def test_old_cases_reject_brand_flags_details_and_original_like_requests(self):
        for case in ('search', 'composer'):
            for key in ('brandFeedbackPlacementProofRequested','brandFeedbackPlacementProofCompleted','brandFeedbackPhysicalFramesRequireHumanReview'):
                for bad in (True, 0, None):
                    observations, transport = self.evidence(case); observations[key] = bad
                    with self.subTest(case=case, key=key, bad=bad), self.assertRaises(ValueError): self.verify(case, observations, transport)
            for key, bad in (('brandFeedbackPlacementInput', True), ('brandFeedbackPlacement', {})):
                observations, transport = self.evidence(case); transport[key] = bad
                with self.subTest(case=case, key=key), self.assertRaises(ValueError): self.verify(case, observations, transport)
            observations, transport = self.evidence(case)
            transport['apiRequests'].append(dict(method='POST', host='api.bilibili.com', path='/x/web-interface/archive/like',
                originalLikeProtocolMemoryOnly=True, remoteMutationSent=False))
            with self.subTest(case=case), self.assertRaises(ValueError): self.verify(case, observations, transport)

    def test_feedback_literal_flags_protocol_and_exact_action_sequence_are_required(self):
        for key in ('brandFeedbackPlacementProofRequested','brandFeedbackPlacementProofCompleted','brandFeedbackPhysicalFramesRequireHumanReview'):
            for bad in (False, 1, None):
                observations, transport = self.evidence('feedback'); observations[key] = bad
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError): self.verify('feedback', observations, transport)
        for key, bad in (('actualOriginalLikeProtocolConsumed', False), ('syntheticResponsesOnly', 1),
                ('remoteMutationSent', True), ('otherMutationPermitted', True), ('realCredentialsUsed', True),
                ('actions', [1,2,1,2]), ('actions', [1,2,1,2,1,2]), ('actions', [1,1,1,2,1]),
                ('actions', [True,2,1,2,1]), ('actions', None)):
            observations, transport = self.evidence('feedback'); transport['brandFeedbackPlacement'][key] = bad
            with self.subTest(key=key, bad=bad), self.assertRaises(ValueError): self.verify('feedback', observations, transport)
        observations, transport = self.evidence('feedback'); transport['brandFeedbackPlacementInput'] = False
        with self.assertRaises(ValueError): self.verify('feedback', observations, transport)

    def test_feedback_like_count_host_path_and_memory_only_markers_are_exact(self):
        for count in (0, 4, 6):
            observations, transport = self.evidence('feedback')
            pair = transport['apiRequests'][-2:]
            transport['apiRequests'] = transport['apiRequests'][:-10] + [copy.deepcopy(item) for _ in range(count) for item in pair]
            with self.subTest(count=count), self.assertRaises(ValueError): self.verify('feedback', observations, transport)
        for key, bad in (('host','app.bilibili.com'), ('host','other.example'), ('host', None),
                ('method','GET'), ('path','/x/v2/reply/add'), ('originalLikeProtocolMemoryOnly', False),
                ('originalLikeProtocolMemoryOnly', 1), ('remoteMutationSent', True), ('remoteMutationSent', None)):
            observations, transport = self.evidence('feedback'); transport['apiRequests'][-1][key] = bad
            with self.subTest(key=key, bad=bad), self.assertRaises(ValueError): self.verify('feedback', observations, transport)

    def test_feedback_still_rejects_other_mutations_and_false_composer_scope(self):
        for request in (dict(method='POST',host='api.bilibili.com',path='/x/v2/reply/add'),
                dict(method='GET',host='api.bilibili.com',path='/x/relation/modify'),
                dict(method='GET',host='api.bilibili.com',path='/x/web-interface/archive/like'),
                dict(method='DELETE',host='api.bilibili.com',path='/x/v2/reply'),
                dict(method='POST',host='api.bilibili.com',path='/x/dynamic/feed/create/dyn/submit')):
            observations, transport = self.evidence('feedback'); transport['apiRequests'].append(request)
            with self.subTest(request=request), self.assertRaises(ValueError): self.verify('feedback', observations, transport)
        for key in ('commentsSent','creatorFollowMutationSubmitted','originalInteractionRemoteMutationSubmitted'):
            observations, transport = self.evidence('feedback'); transport[key] = True
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('feedback', observations, transport)

    def test_feedback_owned_modal_source_os_input_and_human_review_scope_are_required(self):
        for row_index, key, bad in ((3,'samePeer',False), (3,'actualDialogModal',False), (3,'sameFullSource',False),
                (3,'liveOwnedModalOverlapObserved',False), (3,'liveOwnedChooserOverlapObserved',False),
                (4,'inputMechanism','COMPOSE_TEST'), (4,'sameActualComposerCommentsAndEngagement',False),
                (4,'ownedModalAndChooserObserved',False), (4,'sourcePausePreferencesPreserved',False),
                (4,'sameFullSource',False), (4,'liveOwnedModalOverlapObserved',False), (4,'liveOwnedChooserOverlapObserved',False),
                (4,'liveOwnerMinimizedOverlapObserved',False), (4,'samePeerModalRestoreObserved',None),
                (4,'samePeerMinimizeRestoreObserved',1), (4,'liveVideoFallbackNavigationObserved',None),
                (4,'remoteMutationSent',True), (4,'physicalFramesRequireHumanReview',False), (4,'actualWindowIdentity',702)):
            observations, transport = self.evidence('feedback'); observations['observations'][row_index][key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('feedback', observations, transport)
        observations, transport = self.evidence('feedback'); observations['observations'].pop(3)
        with self.assertRaises(KeyError): self.verify('feedback', observations, transport)
        # Natural completion before a restore is accepted as false, never forged
        # into true. The same receipt can also report a genuinely observed restore.
        observations, transport = self.evidence('feedback')
        for key in ('samePeerModalRestoreObserved','samePeerMinimizeRestoreObserved','liveVideoFallbackNavigationObserved'):
            observations['observations'][4][key] = True
        self.assertEqual(len(self.verify('feedback', observations, transport)), 21)

    def test_feedback_original_and_added_png_tsv_files_are_all_required(self):
        for name in ('210-composer-text-draft', '220-feedback-client-baseline', '227-feedback-video-fallback'):
            for suffix in ('-screen.png', '-accessibility.tsv'):
                observations, transport = self.evidence('feedback'); self.write(observations, transport, 'feedback')
                (self.report / (name + suffix)).unlink()
                with self.subTest(name=name, suffix=suffix), self.assertRaises(ValueError):
                    RUNNER.verify(self.report, self.local, self.health, self.token, self.process, 'feedback')

    def test_feedback_init_and_workflow_keep_same_task_private_home_and_bounded_scope(self):
        init = (TOOLS / 'comment-search-ui.init.gradle').read_text(encoding='utf-8')
        workflow = (TOOLS.parents[1] / '.github/workflows/windows-desktop.yml').read_text(encoding='utf-8')
        runner = (TOOLS / 'run-comment-search-ui.py').read_text(encoding='utf-8')
        self.assertEqual(init.count("tasks.named('windowsVideoLocalReplayUiSmoke', JavaExec)"), 1)
        self.assertNotIn('JavaExec)', init.replace("tasks.named('windowsVideoLocalReplayUiSmoke', JavaExec)", ''))
        self.assertIn("systemProperty('bilipai.validation.composerInput', (uiCase in ['composer', 'feedback', 'video_share']).toString())", init)
        self.assertIn("systemProperty('bilipai.validation.brandFeedbackPlacementInput', (uiCase == 'feedback').toString())", init)
        self.assertIn("if (uiCase in ['composer', 'feedback', 'video_share', 'fullscreen']) {", init)
        self.assertIn("task.systemProperty('user.home', privateHome.absolutePath)", init)
        self.assertIn('marker.getText(\'UTF-8\') != token', init)
        self.assertIn('task.setDependsOn([])', init)
        self.assertIn('snapshot(ui.get()) != prepared', init)
        self.assertIn('options: [search, composer, feedback, video_share, fullscreen]', workflow)
        self.assertIn("@('search', 'composer', 'feedback', 'video_share', 'fullscreen')", workflow)
        self.assertIn("run_owned(command, repo, env, output / 'gradle.log', 180, process)", runner)
        self.assertIn('physicalVisibilityReviewed=False', runner)
        self.assertIn('fullNativeScreenGatePassed=False', runner)
        for name in RUNNER.CAPTURES_BY_CASE['feedback']:
            for suffix in ('-screen.png', '-accessibility.tsv'):
                self.assertEqual(workflow.count('actual-ui/' + name + suffix), 1)
        for suffix in ('-screen.png', '-accessibility.tsv'):
            self.assertEqual(workflow.count('actual-ui/feedback-placement-failure' + suffix), 1)
        self.assertNotIn('actual-ui/composer-private-image.png', workflow)

    def test_fullscreen_requires_three_real_client_captures_and_original_baseline(self):
        captures = self.verify('fullscreen')
        self.assertEqual([item['path'] for item in captures], [name + '-screen.png' for name in RUNNER.CAPTURES_BY_CASE['fullscreen']])
        self.assertEqual(len(captures), 3)
        observation, transport = self.evidence('fullscreen')
        for id in ('120-fullscreen-playing', '130-fullscreen-exit-playing', '140-resized-playing', '150-restored-playing', 'comment-search-bounded-main'):
            bad = copy.deepcopy(observation); bad['observations'] = [row for row in bad['observations'] if row['id'] != id]
            with self.subTest(id=id), self.assertRaises(ValueError): self.verify('fullscreen', bad, transport)
        for name in RUNNER.CAPTURES_BY_CASE['fullscreen'] + ['110-ordinary-playing', '160-original-back-home']:
            for suffix in ('.png', '-frame.txt', '-accessibility.tsv'):
                observation, transport = self.evidence('fullscreen'); self.write(observation, transport, 'fullscreen')
                (self.report / (name + suffix)).unlink()
                with self.subTest(name=name, suffix=suffix), self.assertRaises(ValueError):
                    RUNNER.verify(self.report, self.local, self.health, self.token, self.process, 'fullscreen')
        observation, transport = self.evidence('fullscreen'); self.write(observation, transport, 'fullscreen')
        (self.report / '110-ordinary-playing-native.png').unlink()
        with self.assertRaises(ValueError): RUNNER.verify(self.report, self.local, self.health, self.token, self.process, 'fullscreen')

    def test_fullscreen_and_original_four_case_receipts_cannot_cross_accept(self):
        observation, transport = self.evidence('fullscreen')
        for case in ('search', 'composer', 'feedback', 'video_share'):
            with self.subTest(case=case), self.assertRaises(ValueError): self.verify(case, observation, transport)
        for case in ('search', 'composer', 'feedback'):
            observation, transport = self.evidence(case)
            with self.subTest(case=case), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)
        for key in ('ordinaryFullscreenResizeRegressionExecuted', 'composerInputProofCompleted', 'nvidiaUiProofRequested', 'pipInputProofCompleted'):
            observation, transport = self.evidence('fullscreen'); observation[key] = key != 'ordinaryFullscreenResizeRegressionExecuted'
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)

    def test_fullscreen_full_source_and_native_pause_are_strict(self):
        for id, field, value in [('121-fullscreen-idle-hidden', 'sameAcceptedSourceVersion', 8),
                                ('122-fullscreen-mouse-restored', 'sameActualCanvasRetained', False),
                                ('123-fullscreen-paused-hold', 'fullImmutableSourceStillOwned', False)]:
            observation, transport = self.evidence('fullscreen')
            next(row for row in observation['observations'] if row['id'] == id)[field] = value
            with self.subTest(id=id, field=field), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)
        for id, field, value in [('110-ordinary-playing', 'sourceVersion', 8),
                                ('121-fullscreen-idle-hidden', 'nativePaused', True),
                                ('123-fullscreen-paused-hold', 'nativePaused', False),
                                ('122-fullscreen-mouse-restored', 'firstVideoFrameReady', False),
                                ('123-fullscreen-paused-hold', 'muted', False)]:
            observation, transport = self.evidence('fullscreen')
            next(row for row in observation['observations'] if row['id'] == id)['nativeState'][field] = value
            with self.subTest(id=id, field=field), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)

    def test_fullscreen_idle_clock_layout_and_input_require_actual_existing_receipts(self):
        for id, field, value in [('121-fullscreen-idle-hidden', 'idleMillis', 3999),
                                ('121-fullscreen-idle-hidden', 'idleMillis', True),
                                ('121-fullscreen-idle-hidden', 'clockAfter', 10.5),
                                ('121-fullscreen-idle-hidden', 'clockAfter', float('nan')),
                                ('121-fullscreen-idle-hidden', 'hiddenCanvasHeight', 780),
                                ('121-fullscreen-idle-hidden', 'topAndBottomControlsHidden', False),
                                ('122-fullscreen-mouse-restored', 'inputMechanism', 'OS_ROBOT'),
                                ('123-fullscreen-paused-hold', 'controlsStayedVisible', False),
                                ('123-fullscreen-paused-hold', 'menuHoldExecuted', True)]:
            observation, transport = self.evidence('fullscreen')
            next(row for row in observation['observations'] if row['id'] == id)[field] = value
            with self.subTest(id=id, field=field), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)

    def test_fullscreen_capture_boundaries_are_not_physical_pixel_acceptance(self):
        for field, value in [('screenCaptureFile', '../another.png'), ('physicalScreenHumanReviewRequired', False),
                             ('physicalVideoPixelsIndependentlyChecked', True),
                             ('captureStateHeldAcrossRead', False), ('nativeCanvasBoundsMatched', False),
                             ('physicalCanvasInputDelivered', False),
                             ('screenCaptureClientBounds', dict(x=0, y=0, width=47, height=32)),
                             ('screenCaptureClientBounds', dict(x=True, y=0, width=48, height=32))]:
            observation, transport = self.evidence('fullscreen'); observation['observations'][1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)
        for name in RUNNER.CAPTURES_BY_CASE['fullscreen']:
            for suffix in ('-screen.png', '-accessibility.tsv'):
                observation, transport = self.evidence('fullscreen'); self.write(observation, transport, 'fullscreen')
                (self.report / (name + suffix)).unlink()
                with self.subTest(name=name, suffix=suffix), self.assertRaises(ValueError):
                    RUNNER.verify(self.report, self.local, self.health, self.token, self.process, 'fullscreen')

    def test_fullscreen_remains_guest_readonly_and_same_owned_loopback(self):
        for field, value in [('realAccountUsed', True), ('newPlayerCreated', True), ('composerInput', {}),
                             ('composerInputResponsesAreSynthetic', True), ('qualityMetadataIsSynthetic', False),
                             ('loopbackRequests', [dict(file='video.avi', method='GET')])]:
            observation, transport = self.evidence('fullscreen'); transport[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)
        for request in [dict(method='POST', host='api.bilibili.com', path='/x/web-interface/archive/like'),
                        dict(method='POST', host='api.bilibili.com', path='/x/dynamic/feed/create/dyn'),
                        dict(method='POST', host='app.bilibili.com', path='/unknown')]:
            observation, transport = self.evidence('fullscreen'); transport['apiRequests'].append(request)
            with self.subTest(request=request), self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)
        observation, transport = self.evidence('fullscreen'); self.process['cleanupCompleted'] = False
        with self.assertRaises(ValueError): self.verify('fullscreen', observation, transport)

    def test_fullscreen_init_workflow_and_fixture_reuse_original_task_and_three_captures(self):
        init = (TOOLS / 'comment-search-ui.init.gradle').read_text(encoding='utf-8')
        workflow = (TOOLS.parents[1] / '.github/workflows/windows-desktop.yml').read_text(encoding='utf-8')
        fixture = (TOOLS.parent / 'src/test/kotlin/com/bilipai/desktop/ui/WindowsVideoActualRootUiFixture.kt').read_text(encoding='utf-8')
        self.assertEqual(init.count("tasks.named('windowsVideoLocalReplayUiSmoke', JavaExec)"), 1)
        self.assertIn("systemProperty('bilipai.validation.fullscreenIdleInput', (uiCase == 'fullscreen').toString())", init)
        self.assertIn("['composer', 'feedback', 'video_share', 'fullscreen']", init)
        self.assertIn("task.systemProperty('user.home', privateHome.absolutePath)", init)
        self.assertIn('options: [search, composer, feedback, video_share, fullscreen]', workflow)
        self.assertIn('guiTimeoutSeconds=180', (TOOLS / 'run-comment-search-ui.py').read_text(encoding='utf-8'))
        for name in RUNNER.CAPTURES_BY_CASE['fullscreen']:
            for suffix in ('.png', '-screen.png', '-frame.txt', '-accessibility.tsv'):
                self.assertEqual(workflow.count('actual-ui/' + name + suffix), 1)
        seam = fixture.split('private fun exerciseFullscreenIdleChrome()', 1)[1].split('private fun exercise(replay:', 1)[0]
        self.assertIn('java.awt.Robot().createScreenCapture(client)', seam)
        preparation = fixture.split('await("first actual drawn Root")', 1)[1].split('if (replay != null) enterVideoThroughActualSearch', 1)[0]
        self.assertIn('System.getProperty("bilipai.validation.fullscreenIdleInput") == "true") {', preparation)
        self.assertIn('boundCommentSearchWindow()', preparation)
        # Normal capture and failure-only diagnostics each define their own owner
        # check. Keep each scope exact, including the order around Robot and PNG IO.
        capture_start = '        fun capture(id: String, expectedCanvas:'
        failure_start = '        fun captureFullscreenFailureScreen()'
        self.assertEqual(seam.count(capture_start), 1)
        self.assertEqual(seam.count(failure_start), 1)
        capture = seam.split(capture_start, 1)[1].split('        var chromeDiagnosticCount =', 1)[0]
        failure = seam.split(failure_start, 1)[1].split('        check(playing()', 1)[0]
        for scope, read, write in (
            (capture, 'val screen = java.awt.Robot().createScreenCapture(client)',
             'check(ImageIO.write(screen, "png", report.resolve("$id-screen.png").toFile()))'),
            (failure, 'val image = canvasRobot.createScreenCapture(captured.third)',
             'Files.newOutputStream(report.resolve(name), CREATE_NEW, WRITE).use { check(ImageIO.write(image, "png", it)) }'),
        ):
            self.assertEqual(scope.count('fun sameCaptureOwner() = edt'), 1)
            self.assertEqual(scope.count('sameCaptureOwner()'), 4)
            markers = {'sameCaptureOwner()', read, write}
            self.assertEqual([line.strip() for line in scope.splitlines() if line.strip() in markers],
                             ['sameCaptureOwner()', read, 'sameCaptureOwner()', write, 'sameCaptureOwner()'])
        self.assertIn('sameCaptureState()\n            actions.capture(id, edt { current() })\n            sameCaptureState()', capture)
        self.assertIn('fun sameCaptureOwner() = edt {\n                sameCaptureState()', capture)
        self.assertIn('captureMain === window()', capture)
        self.assertIn('bounds() == expectedCanvas && nativeCanvasMatches(expectedCanvas)', capture)
        self.assertIn('if (expectedChromeVisible) completeChrome() else anchorsGone()', capture)
        self.assertIn('actualPlayer.state.value.nativePaused == expectedPaused', capture)
        self.assertIn('Native.getWindowPointer(main) == hwnd && failureWindowApi.GetForegroundWindow() == hwnd', failure)
        self.assertIn('Integer.toUnsignedLong(pid.value) == ProcessHandle.current().pid()', failure)
        self.assertIn('failureWindowApi.ClientToScreen(hwnd, origin)', failure)
        self.assertIn('"diagnosticOnly" to JsonPrimitive(true)', failure)
        self.assertIn('"physicalVideoPixelsIndependentlyChecked" to JsonPrimitive(false)', failure)
        self.assertIn('capture("121-fullscreen-idle-hidden", requireNotNull(hidden), false, false,', seam)
        self.assertIn('physicalVideoPixelsIndependentlyChecked', seam)
        self.assertIn('canvasRobot.mouseMove(point.x, point.y)', seam)
        self.assertIn('check(!EventQueue.isDispatchThread())', seam)
        self.assertNotIn('dispatchEvent(MouseEvent', seam)


class VideoShareReceiptTests(unittest.TestCase):
    setUp = CommentUiCaseReceiptTests.setUp
    write = CommentUiCaseReceiptTests.write
    verify = CommentUiCaseReceiptTests.verify

    def evidence(self, case='video_share'):
        obs, transport = CommentUiCaseReceiptTests.evidence(self, 'composer')
        for key in ('composerInputProofRequested', 'composerInputProofCompleted'): obs[key] = False
        for key in ('videoDynamicShareProofRequested', 'videoDynamicShareProofCompleted', 'videoDynamicSharePhysicalFramesRequireHumanReview'):
            obs[key] = True
        obs['observations'][1] = dict(id='video-share-original-dynamic-completed', actualWindowIdentity=701,
            sameRootAndRouteAssembly=True, inputMechanism='OS_ROBOT', confirmedShareInstanceId=3,
            confirmedShareSourceVersion=7, actualAccountEpoch=1, syntheticPrimaryMid=990000024,
            remoteMutationSent=False, realCredentialsUsed=False, **{key: True for key in (
                'sameActualEngagementDomain','sameAcceptedPublicationIdentity','samePausedNativeSourceAndPreferences',
                'actualOriginalSheetAndDynamicDialog','cancelProducedZeroPosts','originalFailureDraftAndErrorRetained',
                'manualRetryCompletedOriginalProtocol','sameSourceHiddenRestoreObserved',
                'openDraftSamePeerHiddenRestore','openDraftTextPreserved',
                'currentSourceConfirmedShareReceiptObserved','exactOwnedPeersDisposed','physicalFramesRequireHumanReview')})
        transport['videoDynamicShareInput'] = True
        transport['videoDynamicShare'] = dict(schema=1, expectedAid=170001, actualOriginalVideoDynamicProtocolConsumed=True,
            syntheticResponsesOnly=True, remoteMutationSent=False, otherMutationPermitted=False, realCredentialsUsed=False,
            payloads=[dict(method='POST', host='api.bilibili.com', path='/x/dynamic/feed/create/dyn', scene=5, dynType=8,
                rid=170001, text='LOCAL video share draft', csrfIsSynthetic=True, payloadSha256='a'*64,
                responseCode=code, terminatedInMemory=True, remoteMutationSent=False) for code in (-1,0)])
        transport['apiRequests'] += [item for _ in range(2) for item in (dict(stage='requestObserved',scheme='https',port=443,hasQuery=True,hasFragment=False,method='POST',host='api.bilibili.com',path='/x/dynamic/feed/create/dyn'),
            dict(stage='memoryResponse',method='POST',host='api.bilibili.com',path='/x/dynamic/feed/create/dyn',originalVideoDynamicProtocolMemoryOnly=True,remoteMutationSent=False))]
        return obs, transport

    def test_share_preserves_exact_two_posts_with_blocked_heartbeat_observations(self):
        obs, transport = self.evidence(); transport['apiRequests'] += [blocked_heartbeat_record() for _ in range(3)]
        self.assertEqual(len(self.verify('video_share', obs, transport)), 5)
        for bad in malformed_heartbeat_records():
            obs, transport = self.evidence(); transport['apiRequests'].append(bad)
            with self.subTest(row=bad), self.assertRaises(ValueError): self.verify('video_share', obs, transport)

    def test_explicit_fourth_case_has_five_pairs_without_borrowing_comment_or_private_image_proof(self):
        self.assertEqual(len(self.verify('video_share')), 5)
        self.assertFalse((self.report / 'composer-private-image.png').exists())
        (self.report / 'video-share-payload-receipt.json').unlink()
        with self.assertRaises(ValueError): RUNNER.verify(self.report,self.local,self.health,self.token,self.process,'video_share')

    def test_original_source_receipt_cancel_failure_retry_and_literal_scopes_are_required(self):
        for key, bad in (('currentSourceConfirmedShareReceiptObserved',False),('confirmedShareInstanceId',True),
                ('samePausedNativeSourceAndPreferences',False),('cancelProducedZeroPosts',False),
                ('originalFailureDraftAndErrorRetained',False),('sameSourceHiddenRestoreObserved',False),
                ('actualAccountEpoch',2),('remoteMutationSent',True)):
            obs, transport=self.evidence(); obs['observations'][1][key]=bad
            with self.subTest(key=key),self.assertRaises(ValueError): self.verify('video_share',obs,transport)
        for key in ('composerInputProofCompleted','commentPublishingAccepted'):
            obs,transport=self.evidence();obs[key]=True
            with self.subTest(key=key),self.assertRaises(ValueError):self.verify('video_share',obs,transport)

    def test_open_draft_same_peer_restore_and_text_preservation_are_separate_literal_proofs(self):
        for key in ('openDraftSamePeerHiddenRestore', 'openDraftTextPreserved'):
            for bad in (False, None, 1, 'true', 'missing'):
                obs, transport = self.evidence()
                proof = obs['observations'][1]
                if bad == 'missing': proof.pop(key)
                else: proof[key] = bad
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    self.verify('video_share', obs, transport)

    def test_real_model_payload_summary_order_origin_and_two_memory_posts_are_exact(self):
        for key,bad in (('scene',1),('dynType',1),('rid',7007),('rid',True),('responseCode',0),
                ('payloadSha256','x'),('csrfIsSynthetic',False),('terminatedInMemory',False),('remoteMutationSent',True)):
            obs,transport=self.evidence();transport['videoDynamicShare']['payloads'][0][key]=bad
            with self.subTest(key=key),self.assertRaises(ValueError):self.verify('video_share',obs,transport)
        for bad in (dict(method='POST',host='api.bilibili.com',path='/x/v2/reply/add'),
                    dict(method='GET',host='api.bilibili.com',path='/x/dynamic/feed/create/dyn')):
            obs,transport=self.evidence();transport['apiRequests'].append(bad)
            with self.assertRaises(ValueError):self.verify('video_share',obs,transport)

    def test_old_modes_reject_dynamic_case_and_init_workflow_keep_only_existing_task(self):
        obs,transport=self.evidence()
        for case in ('search','composer','feedback'):
            with self.subTest(case=case),self.assertRaises((ValueError,KeyError)):self.verify(case,obs,transport)
        runtime=dict(schema=1,commentUiCase='video_share',task='windowsVideoLocalReplayUiSmoke',
            mainClass='com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture',classpath=['original.jar'],
            javaExecutable='fixed/bin/java.exe',applicationResources='original/resources')
        RUNNER.validate_runtime(runtime,'video_share')
        for case in ('search','composer','feedback'):
            with self.assertRaises(ValueError):RUNNER.validate_runtime(runtime,case)
        workflow=(TOOLS.parents[1]/'.github/workflows/windows-desktop.yml').read_text(encoding='utf-8')
        init=(TOOLS/'comment-search-ui.init.gradle').read_text(encoding='utf-8')
        self.assertIn("systemProperty('bilipai.validation.videoDynamicShareInput', (uiCase == 'video_share').toString())",init)
        self.assertIn('options: [search, composer, feedback, video_share, fullscreen]',workflow)
        for stage in RUNNER.CAPTURES_BY_CASE['video_share']+['video-share-input-failure']:
            for suffix in ('-screen.png','-accessibility.tsv'):self.assertEqual(workflow.count('actual-ui/'+stage+suffix),1)
        self.assertEqual(workflow.count('actual-ui/video-share-payload-receipt.json'),1)
        self.assertNotIn('actual-ui/composer-private-image.png',workflow)


    def test_actual_replay_double_records_require_exact_observed_and_fulfilled_counts(self):
        obs,transport=self.evidence()
        self.assertEqual(len([r for r in transport['apiRequests'] if r.get('path')=='/x/dynamic/feed/create/dyn']),4)
        self.verify('video_share',obs,transport)
        for stage in ('requestObserved','memoryResponse'):
            for change in ('missing','extra','wrong_host','wrong_method'):
                obs,transport=self.evidence(); rows=transport['apiRequests']
                index=next(i for i,r in enumerate(rows) if r.get('stage')==stage)
                if change=='missing':rows.pop(index)
                elif change=='extra':rows.append(dict(rows[index]))
                elif change=='wrong_host':rows[index]['host']='foreign.invalid'
                else:rows[index]['method']='GET'
                with self.subTest(stage=stage,change=change),self.assertRaises(ValueError):self.verify('video_share',obs,transport)
        replay=(TOOLS.parent/'src/test/kotlin/com/bilipai/desktop/ui/WindowsVideoLocalReplay.kt').read_text(encoding='utf-8')
        seam=replay.split('if (videoDynamicShareInput) {',1)[1].split('if (composerInput) {',1)[0]
        self.assertIn('val dynamicResponse = try { videoDynamicShareScript?.respond',seam)
        self.assertIn('catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }',seam)
        self.assertIn('catch (failure: IOException) { throw failure }',seam)
        self.assertIn('catch (_: Exception) { throw IOException("LOCAL video share memory request rejected") }',seam)

if __name__ == '__main__': unittest.main()
