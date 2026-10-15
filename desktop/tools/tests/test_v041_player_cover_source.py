from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v040_player_cover as prior
import v041_player_cover as cover

def module(name,relative):
 spec=importlib.util.spec_from_file_location(name,REPO/'desktop/tools'/relative)
 result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result

class V041PlayerCoverSourceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temporary=tempfile.TemporaryDirectory(prefix='bp-current-cover-')
  cls.root=Path(cls.temporary.name)
  cls.producer=module('actual_v041_cover_source_producer','extract-upstream-video-player-section-full.py')
  cls.producer.generate(REPO,cls.root/'main',standalone=True,test_output=cls.root/'tests')
  cls.inputs=dict(cls.producer.SOURCES)
  cls.rows=list(cls.producer.OUTPUTS)
  cls.current=cover.fixed_sources(REPO)
  cls.policy=(cls.root/'main/com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoPlayerSectionPolicy.kt').read_text(encoding='utf-8')
  cls.section=(cls.root/'main/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt').read_text(encoding='utf-8')
 @classmethod
 def tearDownClass(cls):cls.temporary.cleanup()

 def test_actual_final_sole_producer_retains_whole_current_declarations(self):
  current=self.current['VideoPlayerSectionPolicy.kt'].decode('utf-8')
  original,_=self.producer.policy_members(current,self.producer.parser)
  emitted,_=self.producer.policy_members(self.policy,self.producer.parser)
  proof=json.loads((self.root/'main/v041-player-cover-policy-projection-proof.json').read_bytes())
  self.assertEqual(44,len(cover.NAMES));self.assertEqual(set(cover.NAMES),set(proof['selectedDeclarationNames']))
  for name in cover.NAMES:
   # The sole producer deliberately joins complete original members with one
   # extra separator newline. No character inside the member may change.
   self.assertIn(emitted[name],(original[name],original[name]+'\n'),name)
   self.assertEqual(1,self.policy.count(original[name]),name)
   self.assertEqual(cover.NEW_MEMBER_HASHES[name],cover.sha(original[name]),name)
  self.assertTrue(proof['completeOriginalMembers']);self.assertTrue(proof['allOtherMembersUnmodified'])
  self.assertFalse(proof['fullCurrentPlayerParityClaimed'])
  full=json.loads((self.root/'main/v037-player-section-policy-platform-proof.json').read_bytes())
  for name in cover.NAMES:self.assertEqual(cover.COMMIT,full['fixedCommitByDeclaration'][name])

 def test_projected_caller_and_reveal_effects_have_complete_inverse(self):
  proof=json.loads((self.root/'main/v041-player-cover-caller-proof.json').read_bytes())
  previous=(REPO/'desktop/upstream-slices/v037-player-owner-state/VideoPlayerSection.kt').read_text(encoding='utf-8')
  previous,_=prior.project_caller(REPO,previous,{},self.producer.protocol)
  projected,actual=cover.project_caller(REPO,previous,{},self.producer.protocol)
  restored=projected
  for row in reversed(actual['indexedEdits']):
   self.assertEqual(1,restored.count(row['after']));restored=restored.replace(row['after'],row['before'],1)
  self.assertEqual(previous,restored)
  self.assertEqual(prior.caller_block(self.current['VideoPlayerSection.kt'].decode('utf-8'),self.producer.protocol),
    prior.caller_block(self.section,self.producer.protocol))
  self.assertEqual(1,self.section.count('val surfaceCanCrossfade = canSurfaceParticipateInAlphaCrossfade(false)'))
  self.assertFalse(proof['nativeAlphaCapability']);self.assertTrue(proof['allOtherSourceBytesPreserved'])

 def test_all_other_final_emitted_files_and_previous_policy_members_are_preserved(self):
  with patch.object(cover,'project_members',side_effect=lambda repo,members,sources,protocol,select_members:(members,{})), \
       patch.object(cover,'project_caller',side_effect=lambda repo,text,sources,protocol:(text,{})), \
       patch.object(cover,'emit_tests',return_value=None):
   self.producer.generate(REPO,self.root/'previous',standalone=True)
  previous_rows=list(self.producer.OUTPUTS)
  self.assertEqual({row['path']for row in self.rows},{row['path']for row in previous_rows})
  for row in self.rows:
   old=(self.root/'previous'/row['path']).read_text(encoding='utf-8')
   new=(self.root/'main'/row['path']).read_text(encoding='utf-8')
   if row['path'].endswith('/DesktopOriginalVideoPlayerSectionPolicy.kt'):
    oldmembers,oldstarts=self.producer.policy_members(old,self.producer.parser)
    newmembers,_=self.producer.policy_members(new,self.producer.parser)
    for name,body in oldmembers.items():
     if name not in cover.NAMES:
      # Appending the new capability after the previous final member inserts
      # exactly the existing producer's one inter-member separator newline.
      expected=body+'\n'if name==oldstarts[-1][0]else body
      self.assertEqual(expected,newmembers[name],name)
   elif row['path'].endswith('/VideoPlayerSection.kt'):
    edits=json.loads((self.root/'main/v041-player-cover-caller-proof.json').read_bytes())['indexedEdits']
    for edit in reversed(edits):new=new.replace(edit['after'],edit['before'],1)
    self.assertEqual(old,new)
   else:self.assertEqual(old,new,row['path'])

 def test_thirty_one_original_methods_are_unchanged_and_exclusions_explicit(self):
  original=self.current['VideoPlayerCoverPolicyTest.kt'].decode('utf-8')
  result=next((self.root/'tests').rglob('DesktopV041OriginalPlayerCoverPolicyTest.kt')).read_text(encoding='utf-8')
  self.assertEqual(31,result.count('@Test'))
  for name in cover.ORIGINAL_TEST_NAMES:
   self.assertEqual(prior.declaration(original,name,self.producer.protocol),prior.declaration(result,name,self.producer.protocol))
  self.assertEqual(3,len(cover.EXCLUDED_ORIGINAL_TESTS))
  for name in cover.EXCLUDED_ORIGINAL_TESTS:self.assertNotIn('fun '+name+'(',result)
  contradiction=prior.declaration(original,'horizontalManualStartCover_usesCoverSharedBoundsWithoutViewportFill',self.producer.protocol)
  self.assertIn('assertFalse(spec.fillCoverViewport)',contradiction)
  self.assertFalse(json.loads((self.root/'tests/v041-player-cover-original-test-proof.json').read_bytes())['allUpstreamTestsClaimed'])

 def test_existing_three_test_class_uses_current_complete_original_methods(self):
  result=next((self.root/'tests').rglob('DesktopV040OriginalPlayerCoverPolicyTest.kt')).read_text(encoding='utf-8')
  original=self.current['VideoPlayerSectionPolicyTest.kt'].decode('utf-8')
  self.assertEqual(3,result.count('@Test'))
  for name in prior.ORIGINAL_TEST_NAMES:
   self.assertEqual(prior.declaration(original,name,self.producer.protocol),prior.declaration(result,name,self.producer.protocol))
  self.assertTrue(json.loads((self.root/'tests/v040-player-cover-original-test-proof.json').read_bytes())['intermediateProjectionOnly'])

 def test_manifest_and_each_raw_original_reject_tampering(self):
  for leaf in ('manifest.json',*self.current):
   with self.subTest(leaf=leaf),tempfile.TemporaryDirectory(prefix='bp-cover-identity-')as directory:
    repo=Path(directory);shutil.copytree(REPO/cover.ARCHIVE,repo/cover.ARCHIVE)
    target=repo/cover.ARCHIVE/leaf;target.write_bytes(target.read_bytes()+b'\n')
    with self.assertRaises(ValueError):cover.fixed_sources(repo)

 def test_missing_or_changed_existing_member_is_rejected(self):
  old=(REPO/'desktop/upstream-slices/v037-player-section-policy/VideoPlayerSectionPolicy.kt').read_text(encoding='utf-8')
  members,_=self.producer.policy_members(old,self.producer.parser)
  members,_=prior.project_members(REPO,members,{},self.producer.protocol)
  selector=lambda text:self.producer.policy_members(text,self.producer.parser)[0]
  for name in cover.NAMES:
   if cover.OLD_MEMBER_HASHES[name]is None:continue
   changed=dict(members);changed.pop(name)
   with self.assertRaisesRegex(ValueError,'declaration drift'):cover.project_members(REPO,changed,{},self.producer.protocol,selector)
  changed=dict(members);changed['resolveVideoPlayerCoverMotionSpec']+='\n// drift'
  with self.assertRaisesRegex(ValueError,'declaration drift'):cover.project_members(REPO,changed,{},self.producer.protocol,selector)

 def test_ambiguous_or_changed_caller_effects_and_capability_are_rejected(self):
  old=(REPO/'desktop/upstream-slices/v037-player-owner-state/VideoPlayerSection.kt').read_text(encoding='utf-8')
  old,_=prior.project_caller(REPO,old,{},self.producer.protocol)
  block=prior.caller_block(old,self.producer.protocol);effects=cover.effects_block(old)
  for changed in (old+block,old+effects,old.replace(block,block.replace('remember(','remember( /* drift */',1),1),
    old.replace(effects,effects.replace('withFrameNanos','/* drift */ withFrameNanos',1),1),old+'\nval surfaceCanCrossfade = true'):
   with self.assertRaises(ValueError):cover.project_caller(REPO,changed,{},self.producer.protocol)

 def test_repeated_actual_generation_is_deterministic(self):
  self.producer.generate(REPO,self.root/'repeat',standalone=True,test_output=self.root/'repeat-tests')
  for left,right in [(self.root/'main',self.root/'repeat'),(self.root/'tests',self.root/'repeat-tests')]:
   actual={p.relative_to(left).as_posix():cover.sha(p.read_bytes())for p in left.rglob('*')if p.is_file()}
   repeated={p.relative_to(right).as_posix():cover.sha(p.read_bytes())for p in right.rglob('*')if p.is_file()}
   self.assertEqual(actual,repeated)

 def test_build_declares_helper_archive_and_all_raw_source_identities(self):
  build=(REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
  task=build.split('val extractOriginalVideoPlayerSectionFull by tasks.registering(Exec::class) {',1)[1].split('\n}\n',1)[0]
  self.assertIn('inputs.file("tools/v041_player_cover.py")',task)
  self.assertIn('inputs.dir("upstream-slices/v041-player-cover")',task)
  for filename in self.current:
   self.assertEqual(cover.COMMIT,self.inputs[cover.ARCHIVE+'/'+filename]['upstreamCommit'])
  readback=json.loads((self.root/'main/v037-complete-player-section-source-readback.json').read_bytes())
  self.assertEqual(cover.COMMIT,readback['currentCoverCommit'])
  self.assertEqual(set(cover.NAMES),set(readback['currentCoverPolicyDeclarations']))
  self.assertTrue(readback['ordinaryBvCoverConsumerImplemented'])
  self.assertFalse(readback['fullV041PlayerParityClaimed'])


 def test_checked_windows_consumer_is_the_complete_original_subtree_projection(self):
  projected,proof=cover.windows_cover_consumer(REPO,self.producer.protocol)
  actual=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsVideoEntryCover.kt').read_text(encoding='utf-8')
  self.assertEqual(projected,actual)
  self.assertTrue(proof['wholeSubtreeInverseVerified']);self.assertTrue(proof['wholeEffectsInverseVerified'])
  self.assertFalse(proof['fullCurrentPlayerParityClaimed']);self.assertFalse(proof['sharedCardReturnConnected'])
  key=actual.index('    key(contentKey) {')
  self.assertLess(key,actual.index('val latestCurrent by rememberUpdatedState(stillCurrent)'))
  self.assertLess(key,actual.index('val latestPlay by rememberUpdatedState(onPlayFromCover)'))
  self.assertIn('visible = showCover, // Opaque loading slot',actual)
  self.assertIn('.desktopCommandHitRegion("windows-entry-cover")',actual)
  self.assertIn('if (!latestCanReveal()) return@LaunchedEffect',actual)
  self.assertNotIn('android.util.Log',actual)
  self.assertNotIn('MpvPlayer(',actual)
  self.assertNotIn('SwingPanel(',actual)

 def test_actual_ordinary_bv_leaf_consumes_only_its_request_and_admitted_native_frame(self):
  leaf=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsVideoPhysicalLeaf.kt').read_text(encoding='utf-8')
  start=leaf.index('    // Cover inputs never borrow')
  end=leaf.index('    var showSponsorSegment ',start)
  binding=leaf[start:end]
  self.assertIn('load.currentRequest === coverRequest',binding)
  self.assertIn('load.currentLoadRequestToken == coverRequestToken',binding)
  self.assertNotIn('load.currentCid == coverRequest.cid',binding)
  gate=binding.index('assembly.native.admitPlaybackDispatch(expected)')
  self.assertGreater(binding.index('assembly.section.capturePresentationState()'),gate)
  self.assertGreater(binding.index('native.captureNativeVideoCanvasLease'),gate)
  self.assertIn('it.lease.peer === captured.lease.peer',binding)
  self.assertIn('it.lease.playbackRevision == captured.lease.playbackRevision',binding)
  self.assertIn('?.state?.firstVideoFrameReady == true',binding)
  self.assertIn('playPlayerFromUserAction(assembly.section)',binding)
  popup=leaf.index('DesktopVideoCommandPopup(viewportSize, {')
  call=leaf.index('DesktopWindowsVideoEntryCover(',popup)
  self.assertLess(call,leaf.index('actions.overlay()',popup))
  self.assertEqual(1,leaf.count('DesktopWindowsVideoEntryCover('))

if __name__=='__main__':unittest.main()
