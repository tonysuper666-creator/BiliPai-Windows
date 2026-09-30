from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
LANE=REPO/'desktop/.local/native-share-owner-thread-dispatch-parity'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,value):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
manifest=LANE/'frozen-handoff.json';expected='3ca2b5767f69af7e1f0d3db3f084516376d0bdc3d3119f6548c9c33245a4ea24'
assert sha(manifest)==expected
handoff=json.loads(manifest.read_bytes());assert handoff['fileCount']==len(handoff['files'])==234
for row in handoff['files']:
 p=(LANE/row['path']).resolve(strict=True);assert p.is_relative_to(LANE.resolve())
 assert sha(p)==row['sha256Bytes']and p.stat().st_size==row['bytes'],row['path']
review=LANE/'independent-review.json';assert sha(review)=='b5a63a273c08c542e8e5ac59d06a089a2604c1153f5833402cc94721c1469426'
candidate=LANE/'candidate06/DesktopDiagnosticShare.cpp';sourceSha='1606f70eb26cbff4d4e3278703753b27d271996b4971dae2abf13fbd50d5d8ea'
dllSha='2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514'
assert sha(candidate)==sourceSha
vc=Path('C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207').resolve(strict=True)
sdk=Path('C:/Program Files (x86)/Windows Kits/10').resolve(strict=True)
keys=['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins'];graphs=[]
def normalized(rows):
 values=[]
 for row in rows:
  p=Path(row['path']).resolve(strict=True);assert p.stat().st_size==row['bytes']and sha(p)==row['sha256Bytes']
  if p.is_relative_to(vc):relative='vc/'+p.relative_to(vc).as_posix()
  else:assert p.is_relative_to(sdk);relative='sdk/'+p.relative_to(sdk).as_posix()
  values.append(dict(path=relative,sha256Bytes=row['sha256Bytes'],bytes=row['bytes']))
 return sorted(values,key=lambda r:r['path'].casefold())
for folder,graphSha in [('build06','83da8fb2aede9fc3e9b9ac0ccb7b269bd64678d4a70c98753d38997a07e1dadf'),('build06-repro','c38b3b954649fee4b56bdc666d859d9d814e73f366eb4b3e344cd139fa8cb669')]:
 p=LANE/folder/'producer-input-graph.json';assert sha(p)==graphSha
 graph=json.loads(p.read_bytes());assert graph['passed']and graph['source']['sha256Bytes']==sourceSha and graph['dll']['sha256Bytes']==dllSha
 assert sha(Path(graph['dll']['path']))==dllSha
 graphs.append(dict(sha256Bytes=graphSha,flags=graph['command'][1:10],inputs={key:normalized(graph[key])for key in keys}))
assert graphs[0]['inputs']==graphs[1]['inputs']and graphs[0]['flags']==graphs[1]['flags']
assert [len(graphs[0]['inputs'][key])for key in keys]==[363,11,64]
compiler=REPO/'desktop/tools/compile-native-diagnostic-share.py';assert sha(compiler)=='67adac30b8cb9aa5016f7dd2c6382fddac733ffadfbbc6f8c26ed24098d7c861'
installed=REPO/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp';approvalPath=installed.with_name('approved-development-build.json')
assert sha(installed)=='dc810bdb5b6c43c7842768270795d998d76f0b0a221e84fed97a245c220c2a9a'
assert sha(approvalPath)=='88a8d70d549c1c253775f4f2d02152c7429aad0d4391762f5aa40b52d082c71a'
before=json.loads(approvalPath.read_bytes());approval=dict(before)
approval.update(sourceSha256Bytes=sourceSha,dllSha256Bytes=dllSha,sourceApprovalEvidenceManifestSha256Bytes=expected,
 independentSourceReviewSha256Bytes=sha(review),nativeShareFeatureAccepted=False,actualShareShowOrReceiverAccepted=False)
for key in keys:approval[key]=graphs[0]['inputs'][key]
assert approval['flags']==graphs[0]['flags']and approval['approvedDevelopmentBuild']and not approval['publicReleaseAuthorizationVerified']
installed.write_bytes(candidate.read_bytes());write(approvalPath,approval)
assert sha(installed)==sourceSha
write(HERE/'source-installation.json',dict(sourceInstalled=True,windowsBaseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
 verifiedFrozenRawFiles=234,handoffManifestSha256Bytes=expected,independentReviewSha256Bytes=sha(review),sourceSha256Bytes=sha(installed),
 approvalSha256Bytes=sha(approvalPath),expectedDllSha256Bytes=dllSha,graphInputs=[363,11,64],twoFreshCandidatesRechecked=True,
 taskHelperOrDllCopiedIntoMain=False,freshMainRebuildPending=True,nativeShareFeatureAccepted=False,publicReleaseAuthorizationVerified=False))
print(json.dumps(dict(sourceSha256Bytes=sha(installed),approvalSha256Bytes=sha(approvalPath),expectedDllSha256Bytes=dllSha,rawFilesVerified=234,graphInputs=[363,11,64])))
