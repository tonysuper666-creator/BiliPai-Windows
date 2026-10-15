"""Complete fixed current cover declarations and callers in the sole producer."""
from pathlib import Path
import hashlib,json
import v040_player_cover as prior
COMMIT='d18fb20e31adb11bbec9fae6dfce7d705d556217'
ARCHIVE='desktop/upstream-slices/v041-player-cover'
MANIFEST_SHA256='3f2e18b0ddc03a47219a91ad03869c5b2eefc495fbed82b8add36e743d4faec9'
NAMES=('ManualStartPlayButtonAnchor', 'ManualStartPlayButtonLayoutSpec', 'VIDEO_PLAYER_COVER_FADE_ENTER_DURATION_MILLIS', 'VIDEO_PLAYER_COVER_REVEAL_HOLD_DELAY_MILLIS', 'VIDEO_PLAYER_COVER_REVEAL_MIN_SATURATION', 'VIDEO_PLAYER_COVER_REVEAL_SETTLE_BUFFER_MILLIS', 'VIDEO_PLAYER_SURFACE_REVEAL_DURATION_MILLIS', 'VIDEO_PLAYER_SURFACE_REVEAL_INITIAL_SCALE', 'VideoPlayerCoverBootstrapState', 'VideoPlayerCoverContentScaleMode', 'VideoPlayerCoverMotionSpec', 'VideoPlayerEntryPresentationSpec', 'VideoPlayerRevealMotionSpec', 'VideoPlayerSurfaceRevealSpec', 'canSurfaceParticipateInAlphaCrossfade', 'resolveForcedReturnCoverSharedElementSourceRoute', 'resolveManualStartPlayButtonLayoutSpec', 'resolvePreferredVideoCoverUrl', 'resolveVideoPlayerCoverBootstrapState', 'resolveVideoPlayerCoverCornerDp', 'resolveVideoPlayerCoverLayerZIndex', 'resolveVideoPlayerCoverMotionSpec', 'resolveVideoPlayerCoverRevealColorFilter', 'resolveVideoPlayerCoverRevealSaturation', 'resolveVideoPlayerCoverRevealSettleDelayMillis', 'resolveVideoPlayerEntryPresentationSpec', 'resolveVideoPlayerRevealMotionSpec', 'resolveVideoPlayerSurfaceRevealSpec', 'shouldCommitSmoothCoverReveal', 'shouldDisableCoverFadeAnimation', 'shouldEnableCoverImageCrossfade', 'shouldEnableCoverOverlaySharedBounds', 'shouldEnableForcedReturnCoverSharedBounds', 'shouldEnableManualStartCoverOverlay', 'shouldFillPlayerViewportForManualStartCover', 'shouldForceCoverDuringReturnAnimation', 'shouldHoldEntryCoverUnderlay', 'shouldKeepCoverForManualStart', 'shouldLoadVideoPlayerCoverImage', 'shouldResetSmoothCoverReveal', 'shouldShowCoverImage', 'shouldShowManualStartPlayButton', 'shouldStartSmoothCoverReveal', 'shouldUseReturnLandingMotionForForcedReturnCover')
OLD_MEMBER_HASHES={'ManualStartPlayButtonAnchor': '61e7e48f84b94675e02d005586daedfeb8aa0feff4d3a55fa3c19ff24bba0c82', 'ManualStartPlayButtonLayoutSpec': 'a76f1c3a962235be227131d9eb8e36a78878c102b133bf8eb91504c48ccb1b5d', 'VIDEO_PLAYER_COVER_FADE_ENTER_DURATION_MILLIS': '1a7caeae48adc01a93f94026849e7d03a15aec93e5ce926e7310ef0e9b134c2f', 'VIDEO_PLAYER_COVER_REVEAL_HOLD_DELAY_MILLIS': '9cded9f84addeefe2c040056dd0d0f26032fa842c1f23fef372654db8ef551af', 'VIDEO_PLAYER_COVER_REVEAL_MIN_SATURATION': 'abc7d465c0e3dfb0754636fdbd0ef3d26f98cb5e88fce589f832d97a1f0249c0', 'VIDEO_PLAYER_COVER_REVEAL_SETTLE_BUFFER_MILLIS': '0117f3cfe354b745becb4b4869b823e1d2c6683046a00802fac3ad32b3d8d9e3', 'VIDEO_PLAYER_SURFACE_REVEAL_DURATION_MILLIS': '6abc37ad30fec161ff12c0992b5c0f3b320ca49e628ee10fa4c8d4a4f04aee97', 'VIDEO_PLAYER_SURFACE_REVEAL_INITIAL_SCALE': 'bfb3a0970d2b1a37e059360f9da8b74de15225432997bf1a08bf7364bfad9583', 'VideoPlayerCoverBootstrapState': '843a24df8d6bf4103b875435ec3e1bcaa358ad97935b55a150ad7554e0470c04', 'VideoPlayerCoverContentScaleMode': '54e408d296dfe6cbe21653a0c59cbdca4d221f099619158df883f2e68073ed7b', 'VideoPlayerCoverMotionSpec': '1d81fad73737ee0dc789e4efbd5caacc308c28c58ffd856ab4cc1b5bd17d6577', 'VideoPlayerEntryPresentationSpec': 'ee1c630f4da3fb939eeaee849e676d022ce3035d99d54394ee8ab92e4aa00faf', 'VideoPlayerRevealMotionSpec': 'b9ef9340ad21686f5237fcb50ffec781be943321ee6c72983d0f8f22e30ccf89', 'VideoPlayerSurfaceRevealSpec': 'c2e223ceebbcf8d73e8865ddca9f73882ee66a5ff3e4aa50f0d5ee9b42b51863', 'canSurfaceParticipateInAlphaCrossfade': None, 'resolveForcedReturnCoverSharedElementSourceRoute': '7a7f94762dfeee19232ec3eef4f44c760eff58c61eb2e77b818c832a3bfeff60', 'resolveManualStartPlayButtonLayoutSpec': '9f61370b6f44558fb26c314753c8ce13740f59a2eeebe7cef88323d354c183d5', 'resolvePreferredVideoCoverUrl': '57c7e6fdb0339d440dc4bd609b9fc1b88ac51ccac753e8ed57383e5776decbae', 'resolveVideoPlayerCoverBootstrapState': 'f69ee2c883a768d5a7aa2a38d5a50cbaf5501b302fd324bafdb5c3a809bc3e9a', 'resolveVideoPlayerCoverCornerDp': '7ba0daec5dcbfd25217a0fa0ea55b19446935dbfa7ebeb838384f8a3ad916d28', 'resolveVideoPlayerCoverLayerZIndex': '43a7a83de4c10d9222e45e9640265d0f2307665c1307c9e8331a1d46b4854161', 'resolveVideoPlayerCoverMotionSpec': 'acfe22284620ba56a1f6dd2a60df0d5840ee072580f79e565f4424af88c5ba1a', 'resolveVideoPlayerCoverRevealColorFilter': 'a9b1b3d0fa1ce6729a5f3f782e75e9bfb25a29752a90a41f46b7d0b7b8e00872', 'resolveVideoPlayerCoverRevealSaturation': 'a0f9dce56c4249c6d19f0b0e481719351fc0d4c5eb47c6302d222ceaf24345aa', 'resolveVideoPlayerCoverRevealSettleDelayMillis': '5baeabf596977ef701b1bcdb9c3f1a386657633759fd487529143da42c52fe76', 'resolveVideoPlayerEntryPresentationSpec': '465d9876ba9ed8e6522859b2761885442ec8b6b2ec2ab9ae35959325f3dc1d9b', 'resolveVideoPlayerRevealMotionSpec': 'fb3a713b8934b0b3ac21156fc266961ed40f4dae6cf8b4f04187e31bf6cc5143', 'resolveVideoPlayerSurfaceRevealSpec': 'e2e93ee13ee71bd99f0a4babb1b3c017cec39e82970f4f5669701cae762446c9', 'shouldCommitSmoothCoverReveal': '3fdc404f36f604d6f510fbadd82abcb9f0c334f376a436270b078d8751440e9b', 'shouldDisableCoverFadeAnimation': '51b6c45c8ee80567a46b4c7fe035cef2935bf5153dbc4a0a1af3c9ca0cdf0142', 'shouldEnableCoverImageCrossfade': '3d98e3b91a75766071e0afaeb9551ec743f7df74f851c545461061f23fc3f14a', 'shouldEnableCoverOverlaySharedBounds': '3438ee237706963333548b62bd9d5d616de8973a6405da8e2badc53f029d20b2', 'shouldEnableForcedReturnCoverSharedBounds': 'f6e49ded6b5c610e645fcf221112970677cf4f23e79979eaf5785f9294748266', 'shouldEnableManualStartCoverOverlay': '473629f3d74db4900767cc0652dfa70804366982c65fd27009cc3b42476ea7f4', 'shouldFillPlayerViewportForManualStartCover': '55649e0b2c206a58164fc1522ef8a3341c865c47fed4746980df20a4d1ed5ac1', 'shouldForceCoverDuringReturnAnimation': '5dd3bb7b15c40327f6afbdf2b8ac0b404ee7768d8fb3fb019c21ab94df7d5fef', 'shouldHoldEntryCoverUnderlay': 'b1334b31e7fa5a20f6e94e32be51644a01c62c80aacfc87014614ed111410154', 'shouldKeepCoverForManualStart': '3b19d20f990d7acabaf1b787b901ca97edd7aa49aef9d682edf54a1445f89706', 'shouldLoadVideoPlayerCoverImage': '59a9e6b1d34bbda8169f21f7d11cb442b08acf7a6dda36ef4fba5eb1c4e648f9', 'shouldResetSmoothCoverReveal': '38eb8745fc6240c567cc31afaad2e0ab2f8f8ef8f6dbf0ff07669802ecaa78f4', 'shouldShowCoverImage': '3b9e314e2aec5f8cf2c91284e0023788a1a072dc5ad577c713541a85357ae98d', 'shouldShowManualStartPlayButton': '894518aca814089c8fe258a40466b8710e12eca67f4b298b7f34dbcc21bffbfa', 'shouldStartSmoothCoverReveal': '8c137d91024621ff722532113622099e7f72ae65363851381efd4e6f4f253935', 'shouldUseReturnLandingMotionForForcedReturnCover': 'e3521c8a7d78d441903d77a9a9e0a2a488dc79b99a107e67829e484c7c46d84a'}
NEW_MEMBER_HASHES={'ManualStartPlayButtonAnchor': '61e7e48f84b94675e02d005586daedfeb8aa0feff4d3a55fa3c19ff24bba0c82', 'ManualStartPlayButtonLayoutSpec': 'a76f1c3a962235be227131d9eb8e36a78878c102b133bf8eb91504c48ccb1b5d', 'VIDEO_PLAYER_COVER_FADE_ENTER_DURATION_MILLIS': '499faea6a90f200862100c4239e415084f661d2a6c925ffc238cd1dd57afd339', 'VIDEO_PLAYER_COVER_REVEAL_HOLD_DELAY_MILLIS': '9cded9f84addeefe2c040056dd0d0f26032fa842c1f23fef372654db8ef551af', 'VIDEO_PLAYER_COVER_REVEAL_MIN_SATURATION': 'abc7d465c0e3dfb0754636fdbd0ef3d26f98cb5e88fce589f832d97a1f0249c0', 'VIDEO_PLAYER_COVER_REVEAL_SETTLE_BUFFER_MILLIS': '0117f3cfe354b745becb4b4869b823e1d2c6683046a00802fac3ad32b3d8d9e3', 'VIDEO_PLAYER_SURFACE_REVEAL_DURATION_MILLIS': '6abc37ad30fec161ff12c0992b5c0f3b320ca49e628ee10fa4c8d4a4f04aee97', 'VIDEO_PLAYER_SURFACE_REVEAL_INITIAL_SCALE': 'bfb3a0970d2b1a37e059360f9da8b74de15225432997bf1a08bf7364bfad9583', 'VideoPlayerCoverBootstrapState': '843a24df8d6bf4103b875435ec3e1bcaa358ad97935b55a150ad7554e0470c04', 'VideoPlayerCoverContentScaleMode': '54e408d296dfe6cbe21653a0c59cbdca4d221f099619158df883f2e68073ed7b', 'VideoPlayerCoverMotionSpec': '1d81fad73737ee0dc789e4efbd5caacc308c28c58ffd856ab4cc1b5bd17d6577', 'VideoPlayerEntryPresentationSpec': 'ee1c630f4da3fb939eeaee849e676d022ce3035d99d54394ee8ab92e4aa00faf', 'VideoPlayerRevealMotionSpec': 'b9ef9340ad21686f5237fcb50ffec781be943321ee6c72983d0f8f22e30ccf89', 'VideoPlayerSurfaceRevealSpec': '8b5e8e1ef25115451ebd06f250ab26f56e0e66a3e17f0e548a39ef21acc4df09', 'canSurfaceParticipateInAlphaCrossfade': '2de044f820defb21dc4d83ffa90022cc25ee628fa8b85248ac6ac4e8a7270149', 'resolveForcedReturnCoverSharedElementSourceRoute': '7a7f94762dfeee19232ec3eef4f44c760eff58c61eb2e77b818c832a3bfeff60', 'resolveManualStartPlayButtonLayoutSpec': '9f61370b6f44558fb26c314753c8ce13740f59a2eeebe7cef88323d354c183d5', 'resolvePreferredVideoCoverUrl': '57c7e6fdb0339d440dc4bd609b9fc1b88ac51ccac753e8ed57383e5776decbae', 'resolveVideoPlayerCoverBootstrapState': 'f69ee2c883a768d5a7aa2a38d5a50cbaf5501b302fd324bafdb5c3a809bc3e9a', 'resolveVideoPlayerCoverCornerDp': '7ba0daec5dcbfd25217a0fa0ea55b19446935dbfa7ebeb838384f8a3ad916d28', 'resolveVideoPlayerCoverLayerZIndex': 'de43e31d6a58881650a1cc4843553df8cab58d96da78a4b60f6cbd1467faaa80', 'resolveVideoPlayerCoverMotionSpec': 'c8eb1cf02d3d80ee02bac3806e6ddd7f082a6be3c0ad4dd5ae0cc5309130686e', 'resolveVideoPlayerCoverRevealColorFilter': 'a9b1b3d0fa1ce6729a5f3f782e75e9bfb25a29752a90a41f46b7d0b7b8e00872', 'resolveVideoPlayerCoverRevealSaturation': 'a0f9dce56c4249c6d19f0b0e481719351fc0d4c5eb47c6302d222ceaf24345aa', 'resolveVideoPlayerCoverRevealSettleDelayMillis': '5baeabf596977ef701b1bcdb9c3f1a386657633759fd487529143da42c52fe76', 'resolveVideoPlayerEntryPresentationSpec': '00cda2e8966b5765bc9e255b65bed2e2c240a2dc5f59f48b9a7b7da58e05f883', 'resolveVideoPlayerRevealMotionSpec': 'fb3a713b8934b0b3ac21156fc266961ed40f4dae6cf8b4f04187e31bf6cc5143', 'resolveVideoPlayerSurfaceRevealSpec': 'e2e93ee13ee71bd99f0a4babb1b3c017cec39e82970f4f5669701cae762446c9', 'shouldCommitSmoothCoverReveal': '3fdc404f36f604d6f510fbadd82abcb9f0c334f376a436270b078d8751440e9b', 'shouldDisableCoverFadeAnimation': '51b6c45c8ee80567a46b4c7fe035cef2935bf5153dbc4a0a1af3c9ca0cdf0142', 'shouldEnableCoverImageCrossfade': '3d98e3b91a75766071e0afaeb9551ec743f7df74f851c545461061f23fc3f14a', 'shouldEnableCoverOverlaySharedBounds': '3438ee237706963333548b62bd9d5d616de8973a6405da8e2badc53f029d20b2', 'shouldEnableForcedReturnCoverSharedBounds': 'f6e49ded6b5c610e645fcf221112970677cf4f23e79979eaf5785f9294748266', 'shouldEnableManualStartCoverOverlay': '473629f3d74db4900767cc0652dfa70804366982c65fd27009cc3b42476ea7f4', 'shouldFillPlayerViewportForManualStartCover': '55649e0b2c206a58164fc1522ef8a3341c865c47fed4746980df20a4d1ed5ac1', 'shouldForceCoverDuringReturnAnimation': '5dd3bb7b15c40327f6afbdf2b8ac0b404ee7768d8fb3fb019c21ab94df7d5fef', 'shouldHoldEntryCoverUnderlay': 'b1334b31e7fa5a20f6e94e32be51644a01c62c80aacfc87014614ed111410154', 'shouldKeepCoverForManualStart': '3b19d20f990d7acabaf1b787b901ca97edd7aa49aef9d682edf54a1445f89706', 'shouldLoadVideoPlayerCoverImage': '59a9e6b1d34bbda8169f21f7d11cb442b08acf7a6dda36ef4fba5eb1c4e648f9', 'shouldResetSmoothCoverReveal': '38eb8745fc6240c567cc31afaad2e0ab2f8f8ef8f6dbf0ff07669802ecaa78f4', 'shouldShowCoverImage': '80d5b9ec1c160d1b214dee3d0d091345bc41374e83d5f1d29691dab665d9ef6f', 'shouldShowManualStartPlayButton': '894518aca814089c8fe258a40466b8710e12eca67f4b298b7f34dbcc21bffbfa', 'shouldStartSmoothCoverReveal': '8c137d91024621ff722532113622099e7f72ae65363851381efd4e6f4f253935', 'shouldUseReturnLandingMotionForForcedReturnCover': 'e3521c8a7d78d441903d77a9a9e0a2a488dc79b99a107e67829e484c7c46d84a'}
OLD_CALLER_SHA256='b142dd2cf41fe0beada9158dbdc74bc78727d1544c59d547b882c6e176db4508'
NEW_CALLER_SHA256='e28fbf9fa58c1f62e193b1471d5821b9df5cba5a79e8c2af1c5bf381a02bf87c'
OLD_EFFECTS_SHA256='8eeac74837e8f7501411447f8dd4179116dea60414a21806957eb1b53cb62896'
NEW_EFFECTS_SHA256='8eeac74837e8f7501411447f8dd4179116dea60414a21806957eb1b53cb62896'

def sha(raw):return hashlib.sha256(raw.encode('utf-8')if isinstance(raw,str)else raw).hexdigest()
def fixed_sources(repo):
 root=Path(repo)/ARCHIVE;manifest_raw=(root/'manifest.json').read_bytes()
 if sha(manifest_raw)!=MANIFEST_SHA256:raise ValueError('Unreviewed v041 cover manifest')
 manifest=json.loads(manifest_raw)
 if manifest['fixedUpstreamCommit']!=COMMIT or {r['filename']for r in manifest['files']}!={
  'VideoPlayerSectionPolicy.kt','VideoPlayerSection.kt','VideoDetailCoverContentScale.kt',
  'VideoPlayerCoverPolicyTest.kt','VideoPlayerSectionPolicyTest.kt'}:raise ValueError('Unreviewed v041 cover source set')
 sources={}
 for row in manifest['files']:
  raw=(root/row['filename']).read_bytes()
  blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
  if len(raw)!=row['bytes']or sha(raw)!=row['sha256Bytes']or blob!=row['gitBlob']:raise ValueError('Modified v041 cover source: '+row['path'])
  sources[row['filename']]=raw
 return sources

def register_identity(repo,filename,sources):
 raw=fixed_sources(repo)[filename]
 row=next(r for r in json.loads((Path(repo)/ARCHIVE/'manifest.json').read_bytes())['files']if r['filename']==filename)
 local=ARCHIVE+'/'+filename;text=raw.decode('utf-8').replace('\r\n','\n')
 identity=dict(text=text,upstreamCommit=COMMIT,originalPath=row['path'],sha256LF=sha(text),gitBlob=row['gitBlob'],
  localPath=local,sourceManifestSha256=MANIFEST_SHA256)
 sources[local]=identity
 return text,{k:v for k,v in identity.items()if k!='text'}

def project_members(repo,members,sources,protocol,select_members):
 current,identity=register_identity(repo,'VideoPlayerSectionPolicy.kt',sources)
 register_identity(repo,'VideoDetailCoverContentScale.kt',sources) # Retained fixed comparison/dependency evidence only.
 selected=select_members(current);result=dict(members);changes=[]
 for name in NAMES:
  before=members.get(name);after=selected[name]
  if (sha(before)if before is not None else None)!=OLD_MEMBER_HASHES[name]or sha(after)!=NEW_MEMBER_HASHES[name]:
   raise ValueError('Unreviewed v041 cover declaration drift: '+name)
  result[name]=after
  changes.append(dict(name=name,before=before,after=after,beforeSHA256=OLD_MEMBER_HASHES[name],afterSHA256=sha(after)))
 return result,dict(selectedFixedCommit=COMMIT,source=identity,declarations=changes,selectedDeclarationNames=list(NAMES),
  completeOriginalMembers=True,allOtherMembersUnmodified=all(result[name]==body for name,body in members.items()if name not in NAMES),
  fullCurrentPlayerParityClaimed=False)

def effects_block(text):
 anchor='    LaunchedEffect(\n        bvid,\n        isFirstFrameRendered,\n';ending='    val isSurfaceRevealSettling ='
 if text.count(anchor)!=1 or text.count(ending)!=1:raise ValueError('Ambiguous original cover reveal/settle effects')
 start=text.index(anchor);return text[start:text.index(ending,start)]

def project_caller(repo,text,sources,protocol):
 current,identity=register_identity(repo,'VideoPlayerSection.kt',sources)
 before=prior.caller_block(text,protocol);after=prior.caller_block(current,protocol)
 oldeffects=effects_block(text);neweffects=effects_block(current)
 if sha(before)!=OLD_CALLER_SHA256 or sha(after)!=NEW_CALLER_SHA256 or sha(oldeffects)!=OLD_EFFECTS_SHA256 or sha(neweffects)!=NEW_EFFECTS_SHA256:
  raise ValueError('Unreviewed v041 cover caller/effects drift')
 if 'val surfaceCanCrossfade ='in text:raise ValueError('Duplicate native alpha capability')
 capability='    val surfaceCanCrossfade = canSurfaceParticipateInAlphaCrossfade(false) // Retained native HWND has no Compose alpha.\n'
 replacements=[(before,capability+after),(oldeffects,neweffects)]
 original=text
 for old,new in replacements:
  if text.count(old)!=1:raise ValueError('Ambiguous original cover caller')
  text=text.replace(old,new,1)
 inverse=text
 for old,new in reversed(replacements):
  if inverse.count(new)!=1:raise ValueError('Ambiguous inverse cover caller')
  inverse=inverse.replace(new,old,1)
 if inverse!=original:raise ValueError('Complete original caller inverse failed')
 return text,dict(selectedFixedCommit=COMMIT,source=identity,fullOriginalRememberCallerBlock=True,
  fullOriginalRevealAndSettleEffects=True,nativeAlphaCapability=False,sourceBeforeSHA256=sha(original),sourceAfterSHA256=sha(text),
  indexedEdits=[dict(before=a,after=b)for a,b in replacements],allOtherSourceBytesPreserved=True,
  windowsOrdinaryBvCallerStillRequiresExplicitLeafIntegration=True,fullCurrentPlayerParityClaimed=False)


ORIGINAL_TEST_NAMES=('firstFrameRevealHasNoFixedHoldOrSecondZoom', 'reducedMotionShortensRevealWithoutRemovingOpaqueCoverUnderlay', 'immediatePlaybackCoverBecomesThePlayerUnderlay', 'verticalVideo_fillsPlayerViewportDuringCoverPhase', 'returnCoverSharedBounds_doesNotForceViewportFill', 'horizontalManualStartCover_keepsInlineCoverContainer', 'verticalManualStartCover_canFillViewport', 'coverBootstrap_reusesFirstFrameButStillStartsFromCoverForReentry', 'coverBootstrap_fullscreenSwitchKeepsCurrentFrameVisible', 'smoothRevealReset_onlyWhenForcedCoverOrManualStart', 'immediatePlayback_holdsOpaqueCoverUnderlayUntilFirstFrameReveal', 'coverFirst_andReturn_keepUnderlayWithoutCrossfade', 'surfaceRevealSettling_keepsOpaqueCoverUnderlayUntilVideoIsOpaque', 'coverRevealSettleDelay_coversSurfaceRevealPlusBuffer', 'surfaceViewRemovesSettledCoverWithoutFade', 'coverExitFadeMatchesSurfaceRevealDuration', 'coverRevealPolish_desaturatesDuringBlend_withoutTouchingLuminance', 'autoPlayEnabled_neverUsesManualStartCoverUnderlay', 'manualStartCover_staysVisibleForSavedProgressBeforeUserPlay', 'manualStartCover_staysVisibleBeforeLoadResetsFreshPlayerFlag', 'manualStartCover_hidesAfterUserPlayIntentEvenWithSavedProgress', 'manualStartCover_hidesWhenAutoPlayOverrideStartsPlayback', 'horizontalLoadingCoverFillsInlineSlotButKeepsFullscreenFit', 'verticalManualStartCover_usesViewportFillAndFitContentScale', 'autoPlaybackCover_doesNotStealSharedBoundsFromPlayerContainer', 'forcedReturnCoverSharedBounds_keepsHomeCoverKeyMatchedDuringReturn', 'forcedReturnCoverSharedBounds_stillActiveForNonHomeCardReturnTargets', 'coverFirstOverlaySharedBounds_usesSameCardRouteGuardAsReturn', 'forcedReturnCoverSourceRoute_keepsEveryVideoCardReturnTargetRoute', 'detailReturnCoverCrossfade_showsCoverForCoverFirst_butDoesNotFadeAbsentPlayer', 'verticalManualStart_usesBlackPlayerWithoutLoadingCover')
EXCLUDED_ORIGINAL_TESTS={
 'horizontalManualStartCover_usesCoverSharedBoundsWithoutViewportFill':
  'The fixed upstream test expects false but its same-commit production manual-start entry spec returns true. Raw test preserved; not adapted or executed.',
 'detailReturnCoverUsesSingleAlphaTimelineWithoutCoilCrossfade':
  'Reads Android source paths; a Windows runtime behavior claim requires the actual Windows caller and UI validation.',
 'manualStartPlaybackButton_usesPiliTvAssetWithThemeColor':
  'Reads Android resources/source; Windows resource identity and actual generated consumer are checked separately.',
}

def emit_tests(repo,output,protocol,sources):
 original,identity=register_identity(repo,'VideoPlayerCoverPolicyTest.kt',sources)
 methods=[prior.declaration(original,name,protocol)for name in ORIGINAL_TEST_NAMES]
 # The declarations remain complete and byte-identical; only their test wrapper
 # and test annotation move from kotlin.test to JUnit5 on Windows.
 header=('package com.android.purebilibili.feature.video.ui.section\n\n'
  'import org.junit.jupiter.api.Test\nimport kotlin.test.*\n'
  'import com.android.purebilibili.core.ui.transition.*\n\n')
 text=header+'class DesktopV041OriginalPlayerCoverPolicyTest {\n'+'\n'.join('    @Test\n'+body for body in methods)+'}\n'
 target=Path(output)/'com/android/purebilibili/feature/video/ui/section/DesktopV041OriginalPlayerCoverPolicyTest.kt'
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
 proof=dict(fixedCommit=COMMIT,source=identity,actualCases=len(methods),
  wholeOriginalMethods=[dict(name=name,sha256LF=sha(body))for name,body in zip(ORIGINAL_TEST_NAMES,methods)],
  originalMethodsUnmodified=True,onlyWrapperAndTestAnnotationPlatformAdapted=True,
  excludedOriginalTests=EXCLUDED_ORIGINAL_TESTS,allUpstreamTestsClaimed=False,jvmExecuted=False)
 (Path(output)/'v041-player-cover-original-test-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 current,legacy_identity=register_identity(repo,'VideoPlayerSectionPolicyTest.kt',sources)
 before=target.with_name('DesktopV040OriginalPlayerCoverPolicyTest.kt').read_text(encoding='utf-8')
 changes=[];after=before
 for name in prior.ORIGINAL_TEST_NAMES:
  old=prior.declaration(prior.fixed_sources(repo)['VideoPlayerSectionPolicyTest.kt'].decode('utf-8'),name,protocol)
  new=prior.declaration(current,name,protocol)
  if after.count(old)!=1:raise ValueError('Legacy original test drift: '+name)
  after=after.replace(old,new,1);changes.append(dict(name=name,before=old,after=new))
 inverse=after
 for row in reversed(changes):
  if inverse.count(row['after'])!=1:raise ValueError('Ambiguous original test inverse')
  inverse=inverse.replace(row['after'],row['before'],1)
 if inverse!=before:raise ValueError('Complete original test projection inverse failed')
 target.with_name('DesktopV040OriginalPlayerCoverPolicyTest.kt').write_text(after,encoding='utf-8',newline='\n')
 final=dict(fixedCommit=COMMIT,source=legacy_identity,actualCases=3,indexedEdits=changes,
  completeOriginalMethods=True,completeInverseVerified=True,jvmExecuted=False)
 (Path(output)/'v041-player-cover-legacy-tests-projection-proof.json').write_text(json.dumps(final,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 oldproof=Path(output)/'v040-player-cover-original-test-proof.json'
 legacy=json.loads(oldproof.read_bytes());legacy.update(intermediateProjectionOnly=True,finalOutputFixedCommit=COMMIT,
  finalProjectionProof='v041-player-cover-legacy-tests-projection-proof.json')
 oldproof.write_text(json.dumps(legacy,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 return proof,final


def windows_cover_consumer(repo,protocol):
 original=fixed_sources(repo)['VideoPlayerSection.kt'].decode('utf-8')
 anchor='    AnimatedVisibility(\n        visible = showCover && (currentCoverUrl.isNotEmpty() || entryPresentationSpec.showManualStartPlayButton),'
 if original.count(anchor)!=1:raise ValueError('Ambiguous original cover subtree')
 start=original.index(anchor);masked=protocol.masked(original)
 opening=masked.index('(',start);parameters=protocol.balanced(masked,opening,'(',')')
 opening=masked.index('{',parameters);end=protocol.balanced(masked,opening,'{','}')
 subtree=original[start:end];raw_subtree=subtree;edits=[]
 def once(old,new):
  nonlocal subtree
  if subtree.count(old)!=1:raise ValueError('Ambiguous Windows cover adaptation: '+old[:80])
  subtree=subtree.replace(old,new,1);edits.append(dict(before=old,after=new))
 once('visible = showCover && (currentCoverUrl.isNotEmpty() || entryPresentationSpec.showManualStartPlayButton),',
  'visible = showCover, // Opaque loading slot also prevents an old native frame leaking when no new image exists.')
 shape_start=subtree.index('        val coverCardShape = RoundedCornerShape(')
 m=protocol.masked(subtree);shape_open=m.index('(',shape_start);shape_end=protocol.balanced(m,shape_open,'(',')')
 once(subtree[shape_start:shape_end],'        val coverCardShape = RoundedCornerShape(0.dp)')
 shared_start=subtree.index('        val sharedCoverOverlayModifier = if (')
 shared_end=subtree.index('\n\n        Box(',shared_start)
 once(subtree[shared_start:shared_end],'        val sharedCoverOverlayModifier = Modifier // Native rectangular slot; shared-card return geometry is not yet connected.')
 once('.then(if (capturePlayerPage) playerPageBackdrop.cover.modifier else Modifier)',
  '.desktopCommandHitRegion("windows-entry-cover")')
 inverse=subtree
 for edit in reversed(edits):
  if inverse.count(edit['after'])!=1:raise ValueError('Ambiguous Windows cover inverse')
  inverse=inverse.replace(edit['after'],edit['before'],1)
 if inverse!=raw_subtree:raise ValueError('Complete original cover subtree inverse failed')
 effect=effects_block(original);raw_effect=effect;effect_edits=[]
 def effect_once(old,new):
  nonlocal effect
  if effect.count(old)!=1:raise ValueError('Ambiguous Windows cover effect adaptation: '+old[:80])
  position=effect.index(old);effect=effect.replace(old,new,1);effect_edits.append(dict(offset=position,before=old,after=new))
 effect_once('    ) {\n        if (\n            shouldResetSmoothCoverReveal(',
  '    ) {\n        if (!latestCurrent()) return@LaunchedEffect\n        if (\n            shouldResetSmoothCoverReveal(')
 effect_once('        withFrameNanos { }','        withFrameNanos { }\n        if (!latestCanReveal()) return@LaunchedEffect')
 effect_once('        if (\n            shouldCommitSmoothCoverReveal(',
  '        if (!latestCanReveal()) return@LaunchedEffect\n        if (\n            shouldCommitSmoothCoverReveal(')
 log=next(line+'\n'for line in effect.splitlines()if 'android.util.Log.d("VideoPlayerCover"'in line)
 effect_once(log,'')
 effect_once('    LaunchedEffect(bvid, hasStartedSmoothReveal, revealMotionSpec.surfaceRevealDurationMillis) {\n',
  '    LaunchedEffect(bvid, hasStartedSmoothReveal, revealMotionSpec.surfaceRevealDurationMillis) {\n        if (!latestCurrent()) return@LaunchedEffect\n')
 effect_once('        hasSurfaceRevealSettled = true',
  '        if (!latestCanReveal()) return@LaunchedEffect\n        hasSurfaceRevealSettled = true')
 inverse=effect
 for edit in reversed(effect_edits):
  position=edit['offset'];after=edit['after']
  if inverse[position:position+len(after)]!=after:raise ValueError('Cover effects inverse boundary changed')
  inverse=inverse[:position]+edit['before']+inverse[position+len(after):]
 if inverse!=raw_effect:raise ValueError('Complete original cover effects inverse failed')
 bootstrap=original[original.index('        val coverBootstrapState = remember('):original.index('        val surfaceRevealSpec = remember(')]
 selection=original[original.index('    val isSurfaceRevealSettling ='):original.index('\n    LaunchedEffect(\n        showControls,',original.index('    val isSurfaceRevealSettling ='))]
 entry_start=original.index('    val entryPresentationSpec = remember(')
 entry_end=original.index('    val fillPlayerViewportForManualStartCover =',entry_start)
 entry=original[entry_start:entry_end].replace('videoSharedTransitionVisualSpec.targetMode','targetMode')
 motion=prior.caller_block(original,protocol)
 header='''package com.bilipai.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext as LocalContext
import coil3.request.*
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.feature.video.ui.section.*
import com.bilipai.desktop.player.DesktopNativeVideoCanvasLease
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.delay

/** Display identity only. Actual Store/entry/native admission stays at the caller.
 * Value-equal entries/requests/peers must never inherit another reveal timeline. */
internal class DesktopWindowsVideoEntryCoverIdentity(
    val assembly: Any, val route: Any, val request: Any?, val requestToken: Long,
    val publication: Any?, val playbackRevision: Long?, val peer: Any?,
) {
    override fun equals(other: Any?): Boolean = other is DesktopWindowsVideoEntryCoverIdentity &&
        assembly === other.assembly && route === other.route && request === other.request &&
        requestToken == other.requestToken && publication === other.publication &&
        playbackRevision == other.playbackRevision && peer === other.peer
    override fun hashCode(): Int = arrayOf(System.identityHashCode(assembly),System.identityHashCode(route),
        System.identityHashCode(request),requestToken.hashCode(),System.identityHashCode(publication),
        playbackRevision.hashCode(),System.identityHashCode(peer)).contentHashCode()
    override fun toString() = "DesktopWindowsVideoEntryCoverIdentity(requestToken=$requestToken, revision=$playbackRevision)"
}

/** A short readback captured inside the genuine NativeOwner gate, never a new owner. */
internal class DesktopWindowsVideoEntryCoverRuntime(
    val source: DesktopOriginalVideoAcceptedPublication,
    val lease: DesktopNativeVideoCanvasLease,
    val state: PlayerState,
    val playWhenReady: Boolean, val isPlaying: Boolean, val currentPositionMs: Long,
)

/** Complete fixed v041 image/manual-play subtree and reveal effects with finite
 * Windows adapters. The sole retained native Canvas continues drawing underneath. */
@Composable
internal fun DesktopWindowsVideoEntryCover(
    contentKey: DesktopWindowsVideoEntryCoverIdentity,
    bvid: String, entryCoverUrl: String, detailCoverUrl: String,
    autoPlayOnOpenEnabled: Boolean, firstVideoFrameReady: Boolean,
    observedPlayWhenReady: Boolean, observedIsPlaying: Boolean, currentPositionMs: Long,
    hasMediaItem: Boolean, isVerticalVideo: Boolean, isFullscreen: Boolean,
    stillCurrent: () -> Boolean, canReveal: () -> Boolean, onPlayFromCover: () -> Boolean,
) {
    key(contentKey) {
    val latestCurrent by rememberUpdatedState(stillCurrent)
    val latestCanReveal by rememberUpdatedState(canReveal)
    val latestPlay by rememberUpdatedState(onPlayFromCover)
    val forceCoverDuringReturnAnimation = false // Actual shared-card return witness is not yet connected.
    val preserveCurrentFrameOnFullscreenChange = false // Fullscreen itself does not replace contentKey.
    val persistedRenderedFirstFrame = firstVideoFrameReady
    var hasManualStartPlaybackIntent by remember { mutableStateOf(false) }
    LaunchedEffect(observedPlayWhenReady, observedIsPlaying, hasMediaItem) {
        if (latestCurrent() && hasMediaItem && (observedPlayWhenReady || observedIsPlaying)) {
            hasManualStartPlaybackIntent = true
        }
    }
    val playFromManualStartCover = {
        if (latestCurrent() && latestPlay()) hasManualStartPlaybackIntent = true
    }
    val keepCoverForManualStart = shouldKeepCoverForManualStart(
        playWhenReady = observedPlayWhenReady,
        currentPositionMs = currentPositionMs,
        autoPlayEnabled = autoPlayOnOpenEnabled,
        hasManualStartPlaybackIntent = hasManualStartPlaybackIntent,
    )
'''
 footer='''
    }
}
'''
 input_adapter='''
    LaunchedEffect(firstVideoFrameReady) {
        if (!latestCurrent()) return@LaunchedEffect
        isFirstFrameRendered = firstVideoFrameReady
        if (!firstVideoFrameReady) {
            hasStartedSmoothReveal = false
            hasSurfaceRevealSettled = false
        }
    }
'''
 presentation='''
    val targetMode = if (isFullscreen) VideoSharedTransitionTargetMode.LandscapeFullscreen
        else VideoSharedTransitionTargetMode.InlinePlayer
'''+entry+'''
    val fillPlayerViewportForManualStartCover = entryPresentationSpec.fillCoverViewport
    val suppressCoverFade = forceCoverDuringReturnAnimation || holdEntryCoverUnderlay
    val surfaceCanCrossfade = canSurfaceParticipateInAlphaCrossfade(false)
'''+motion+'''
    val disableCoverFadeAnimation = !coverMotionSpec.shouldAnimateFade
    val coverLayerZIndex = 0f // Foreground popup paints this before controls; HWND cannot use a negative Compose underlay.
    val currentCoverUrl = FormatUtils.fixImageUrl(resolvePreferredVideoCoverUrl(
        entryCoverUrl = entryCoverUrl, detailCoverUrl = detailCoverUrl,
        preferDetailCoverUrl = keepCoverForManualStart && isVerticalVideo,
    ))
    val playerContentModifier = Modifier.fillMaxSize()
    val useStationaryListCover = false
    val stationaryListKey = ""
    val stationaryListCoverDecodeWidthPx = 0
    val stationaryListCoverDecodeHeightPx = 0
    val manualStartPlayIcon = painterResource("icons/ic_pili_tv_play.png")
'''
 result=header+bootstrap+input_adapter+effect+selection+presentation+subtree+footer
 return result,dict(fixedCommit=COMMIT,rawCompleteCoverSubtreeSHA256=sha(raw_subtree),rawCompleteEffectsSHA256=sha(raw_effect),
  subtreeEdits=edits,effectEdits=effect_edits,wholeSubtreeInverseVerified=True,wholeEffectsInverseVerified=True,
  fullOriginalBootstrapRegion=True,fullOriginalSelectionRegion=True,nativeAlphaCapability=False,
  fullCurrentPlayerParityClaimed=False,sharedCardReturnConnected=False,roundedNativeCornersQualified=False,
  ordinaryBvConsumerCallerRequired=True,jvmCompiled=False,pixelsQualified=False)
