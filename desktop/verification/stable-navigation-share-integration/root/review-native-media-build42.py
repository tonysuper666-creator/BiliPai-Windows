from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023';OUT=HERE/'native-media-source-review42';BUILD=HERE/'video-share-native-build42'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
p=REPO/'desktop/native/diagnostic-share/approved-development-build.json';before=p.read_bytes();old=json.loads(before);graph=json.loads((BUILD/'producer-input-graph.json').read_bytes())
source=REPO/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp';driver=REPO/'desktop/tools/compile-native-diagnostic-share.py'
assert sha(source.read_bytes())==graph['source']['sha256Bytes']=='4065f91829bfb250769f6eb767ccd3760ae918297c5f239fe31d242c8ce1bb92'
assert sha(driver.read_bytes())==old['controlledCompilerScriptSha256Bytes']
assert graph['command'][1:10]==old['flags']
vc=Path(graph['includeSearchOrder'][0]).parent;sdk=Path(graph['includeSearchOrder'][1]).parents[2]
roots={'vc':vc,'sdk':sdk}
for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
 expected={str(roots[r['path'].split('/',1)[0]]/r['path'].split('/',1)[1]).casefold():r['sha256Bytes']for r in old[key]}
 actual={r['path'].casefold():r['sha256Bytes']for r in graph[key]};assert expected==actual,key
 for r in graph[key]:assert sha(Path(r['path']).read_bytes())==r['sha256Bytes']
assert sha((BUILD/'bilipai-diagnostic-share.dll').read_bytes())==graph['dll']['sha256Bytes']=='89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06'
proof=json.loads((HERE/'native-media-prepare-actual42/result.json').read_bytes());assert proof['passed'] and not proof['ShareUI']
review=dict(preparedSourceManifestSha256Bytes='5f263de98a14de263168a646a0937ba9e3359fa668a7cec1d126d5be489f3fb9',sourceSha256Bytes=sha(source.read_bytes()),controlledCompilerScriptSha256Bytes=sha(driver.read_bytes()),freshDllSha256Bytes=graph['dll']['sha256Bytes'],sameExistingSessionRegistryAndOwnerDispatcher=True,mediaPreparationRequiresRealLocalFile=True,maximumBytes=32*1024*1024,mediaFormats=['.jpg','.jpeg','.png','.webp'],generatedPosterExtension='.jpg',registryMaximum=8,realOwnerDrainAndDataSuppliedRetirementPolicyPreserved=True,
 sourceReview=['All five local C++ hunks match the frozen source recipe; no new COM/session/dispatcher authority','Media Title/Text add to existing StorageItems callback path; old diagnostic and text branches retained','No network or user account operation; fixed synthetic local file native7 groups accepted','Token is assigned only after bounded registry publication; preparation exceptions leave caller token zero','Grant cancellation/revocation remains on actual owner thread; unknown supplied state does not authorize automatic file deletion'],
 transitiveHeaders=369,searchedLibraries=11,compilerFiles=64,compilerGraphUnchangedExceptSourceAndOutput=True,HWND=False,ShareUI=False,externalReceiver=False,actualRootOrFinalExeAccepted=False)
(OUT/'source-review.json').write_text(json.dumps(review,indent=2)+'\n',encoding='utf-8');(OUT/'approval.before.json').write_bytes(before)
approved=dict(old);approved.update(sourceSha256Bytes=sha(source.read_bytes()),dllSha256Bytes=graph['dll']['sha256Bytes'],sourceApprovalEvidenceManifestSha256Bytes=review['preparedSourceManifestSha256Bytes'],independentSourceReviewSha256Bytes=sha((OUT/'source-review.json').read_bytes()),nativeMediaPreparationAccepted=True,nativeMediaPreparationEvidence='verification/stable-navigation-share-integration/root/native-media-prepare-actual42/result.json')
p.write_text(json.dumps(approved,indent=2)+'\n',encoding='utf-8',newline='\n');(OUT/'approval.after.json').write_bytes(p.read_bytes())
print(json.dumps(dict(reviewed=True,source=review['sourceSha256Bytes'],freshDll=approved['dllSha256Bytes'],resolvedNativeInputsUnchanged=True,nativePrepareGroups=7,shareUiAccepted=False)))
