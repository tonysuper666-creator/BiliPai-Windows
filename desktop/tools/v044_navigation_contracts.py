"""Whole current pure original Nav3 regression suite on the existing producer."""
from pathlib import Path
import json

TEST='BiliPaiNavKeyMappingPolicyTest.kt'
CLASS='com.android.purebilibili.navigation3.BiliPaiNavKeyMappingPolicyTest'

def emit(repo,output):
 from v044_favorite_search import fixed,need,sha,COMMIT,MANIFEST_SHA256,ARCHIVE
 source=fixed(repo)[TEST]
 need(source.count('@Test')==22,'Whole original Nav3 regression inventory changed')
 need('class BiliPaiNavKeyMappingPolicyTest {' in source,'Original Nav3 regression class changed')
 root=Path(output).resolve();originals=(Path(repo)/ARCHIVE).resolve()
 need(not(root==originals or root.is_relative_to(originals) or originals.is_relative_to(root)),
      'Whole navigation test output overlaps archived originals')
 target=root/'com/android/purebilibili/navigation3'/TEST
 target.parent.mkdir(parents=True,exist_ok=True)
 target.write_text(source,encoding='utf8',newline='\n')
 receipt=dict(upstreamCommit=COMMIT,sourceManifestSHA256=MANIFEST_SHA256,sourceFile=ARCHIVE+'/'+TEST,
  originalSuiteSHA256=sha(source),outputSHA256=sha(target.read_bytes()),testClass=CLASS,
  wholeOriginalSuite=True,originalCasesSelected=22,existingSearchAndAudioMethodsRetained=True,
  existingSoleNavigationTestProducer=True,testsExecuted=False,applicationExecuted=False)
 (root/'v044-whole-navigation-test-proof.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8',newline='\n')
 return receipt
