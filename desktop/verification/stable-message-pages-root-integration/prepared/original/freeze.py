from pathlib import Path
import hashlib,json,subprocess,os
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def raw(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
inventory=json.loads((P/'source-inventory.json').read_text(encoding='utf8'));registry=json.loads((C/'desktop/upstream-sources.json').read_text(encoding='utf8'));old={r['path']:r for r in registry['sources']}
verified=[]
for row in inventory:
 source=subprocess.check_output(['git','-C',str(C),'show',commit+':'+row['path']]).replace(b'\r\n',b'\n')
 assert hashlib.sha256(source).hexdigest()==row['sha256'],row['path']
 verified.append(dict(**row,inCurrentRegistry=row['path']in old,currentMode=old.get(row['path'],{}).get('mode')))
(P/'source-proof.json').write_text(json.dumps(dict(upstreamCommit=commit,actualOriginalPaths=len(verified),newRegistryPaths=sum(not r['inCurrentRegistry']for r in verified),sources=verified),ensure_ascii=False,indent=2),encoding='utf8')
copies=[]
for f in wide(P/'prepared').rglob('*'):
 if f.is_file() and '__pycache__' not in f.parts and f.name not in ['DesktopRepository.kt','DesktopCommunityRepository.kt']:
  copies.append(dict(target=f.relative_to(wide(P/'prepared')).as_posix(),prepared=f.relative_to(wide(P)).as_posix(),sha256Bytes=sha(f),operation='copy-new'))
assert len(copies)==4
hunks=json.loads((P/'prospective-hunks.json').read_text(encoding='utf8'))
for row in hunks:
 row['patch']=Path(row['target']).stem+'.patch';row['patchSha256Bytes']=sha(P/row['patch'])
 assert sha(C/row['target'])==row['baseRawSha256']
 row['installInstruction']='Apply only exact hunk. Full prepared copy is an explicit compile overlay, NEVER a whole-file installer.'
generators=[dict(path=f.relative_to(wide(P)).as_posix(),sha256Bytes=sha(f))for f in wide(P/'generated').rglob('*.kt')]
assert len(generators)==17
deps=json.loads((P/'dependencies/identities.json').read_text(encoding='utf8'))
for row in deps:assert sha(row['path'])==row['sha256Bytes']
assert json.loads((P/'runs/11/result.json').read_text(encoding='utf8'))['passed']
assert json.loads((P/'fixture-runs/18/report.json').read_text(encoding='utf8'))['passed']
assert json.loads((P/'fixture-runs/19/ui/ui-report.json').read_text(encoding='utf8'))['passed']
assert json.loads((P/'junit-prospective-compile-05/result.json').read_text(encoding='utf8'))['passed']
contract=dict(schemaVersion=1,candidateBase='6ac84c8036ed4c75e2944d92d1020566e283e983',upstreamCommit=commit,
 snapshot85ManifestSha256='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012',
 orderedRuntimeCpSha256='6398174d444cf02365bc66480c1c37bb00800a520e681c4649100067af68b419',
 copyTargets=copies,hunkTargets=hunks,sourceInventory='source-inventory.json',sourceProof='source-proof.json',
 generatedFiles=generators,dependencies=deps,
 reusedSoleProducers=['UserBasicInfo','DesktopDynamicMessageUserInfoLoader','InboxUserInfoResolver','InboxSessionPaginationPolicy','MessageFeedError','totalPrivateUnreadCount','totalMessageUnreadCount','desktopOriginalOpenMessageLink','original MessageApi/response models'],
 proofs=dict(prospectiveCompile='runs/11/result.json',actualProtocolAndVmFixtures='fixture-runs/18/report.json',actualLoadedClassIdentity='fixture-runs/18/actual-loaded-12-class-identities.json',
 ui='fixture-runs/19/ui/ui-report.json',uiLoadedClassIdentity='fixture-runs/19/ui/actual-loaded-12-class-identities.json',prospectiveJUnitCompile='junit-prospective-compile-05/result.json',actualMeaningfulMethods=14),
 limits=dict(candidateWrites=0,sharedGradleRuns=0,realBilibiliRequests=0,actualAccountTesting=False,wholeRootAcceptance=False,nativeFileChooserAcceptance=False,wideMessageCenterAndFullChatUiRuntimeAccepted=False,productionJUnitExecuted=False),
 rootHooks='INTEGRATION.md')
(P/'install-contract.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2),encoding='utf8')
files=[]
for f in wide(P).rglob('*'):
 if f.is_file() and f.name not in ['frozen-handoff.json'] and ('__pycache__' not in str(f)):
  files.append(dict(path=f.relative_to(wide(P)).as_posix(),sha256Bytes=sha(f),size=len(raw(f))))
(P/'frozen-handoff.json').write_text(json.dumps(dict(schemaVersion=1,files=files,contract='install-contract.json',contractSha256=sha(P/'install-contract.json'),candidateUntouched=True),ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(dict(contractSha256=sha(P/'install-contract.json'),frozenSha256=sha(P/'frozen-handoff.json'),copyTargets=len(copies),hunkTargets=len(hunks),originalPaths=17,newRegistryPaths=sum(not r['inCurrentRegistry']for r in verified),generatedFiles=len(generators),actualFixtures=14,uiCells=2,rootAccepted=False),indent=2))
