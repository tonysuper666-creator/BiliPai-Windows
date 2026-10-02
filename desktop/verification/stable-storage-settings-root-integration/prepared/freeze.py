from pathlib import Path
import hashlib,json,os
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def dump(p,v):wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
contract=json.loads(read(P/'install-contract.json'));assert contract['modifiedExistingSources']==19
final=json.loads(read(P/'compile/08/pins-before.json'));native=json.loads(read(P/'native/01/pins-before.json'))
assert json.loads(read(P/'compile/08/result.json'))['passed']
assert json.loads(read(P/'compile/08/fixture-result.json'))['passed']
assert json.loads(read(P/'native/01/fixture-result.json'))['passed']
for row in final['sourceInputs']:assert sha(row['path'])==row['sha256Bytes'],row['path']
nativeStable=[]
for name in ('MpvPlayer.kt','DesktopVideoShareFiles.kt'):
 row=next(r for r in native['sourceInputs']if Path(r['path']).name==name)
 assert sha(row['path'])==row['sha256Bytes'];nativeStable.append(row)
for row in native['nativeAndClip']:assert sha(row['path'])==row['sha256Bytes']
sourceReceipts=[]
for row in json.loads(read(P/'registry-delta.json'))['rows']:
 path=C/row['path'];raw=read(path);assert hashlib.sha256(raw).hexdigest()==row['sourceRawSha256Bytes']
 text=raw.decode('utf8').replace('\r\n','\n')
 markers={
 'SettingsSections.kt':['fun DataStorageSection'],
 'SettingsManager.kt':['enum class AutoCacheClearInterval','DEFAULT_AUTO_CACHE_CLEAR_THRESHOLD_GB','fun getDownloadPath','fun setDownloadPath','fun setAutoCacheClearInterval','fun getLastAutoCacheClearAt'],
 'CacheUtils.kt':['enum class CacheClearTarget','fun shouldAutomaticallyClearCache','data class CacheBreakdown'],
 'CacheClearUiPolicy.kt':['fun resolveDefaultCacheClearTargets','fun resolveCacheClearOptions'],
 'CacheClearAnimation.kt':['fun CacheClearAnimationDialog','fun CacheClearConfirmDialog'],
 'SettingsScreen.kt':['DataStorageSection(','getAutoCacheClearInterval','getAutoCacheClearThresholdGb'],
 'SettingsPrefsCache.kt':['setDownloadPath','setDownloadExportTreeUri'],
 'ms_check_fill_24.xml':['<vector']}
 anchors=[]
 for marker in markers.get(path.name,[]):
  occurrences=[i+1 for i,line in enumerate(text.splitlines())if marker in line]
  anchors.append(dict(marker=marker,lines=occurrences))
 sourceReceipts.append(dict(path=row['path'],rawSHA=row['sourceRawSha256Bytes'],lfSHA=row['sourceLFSha256'],anchors=anchors))
dump(P/'source-provenance.json',dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',identities=sourceReceipts))
generated=list((P/'generated06').rglob('*.kt'));assert len(generated)==5
dump(P/'producer-output-receipt.json',dict(soleProducer='extractOriginalStorageSettings',generatorSHA=sha(P/'prepared/desktop/tools/extract-upstream-storage-settings.py'),
    generated=[dict(relativePath=p.relative_to(P/'generated06').as_posix(),sha256Bytes=sha(p))for p in generated],
    directPolicySoleProducer='prepareUpstreamSources',directPolicySHA=next(row['sourceLFSha256']for row in json.loads(read(P/'registry-delta.json'))['rows']if row['path'].endswith('/CacheClearUiPolicy.kt')),
    duplicateDirectProducerFindingClosed=True))
dump(P/'evidence-scope.json',dict(finalWholeClosure='compile/08',finalClosureInputs=len(final['sourceInputs']),snapshot84Manifest=final['actual84Manifest'],CP101=final['orderedCP'],
    CPUAssertions=46,actualNativeAssertions=20,nativeOwnerEvidence='native/01',nativeTestedOwnersSameFinalBytes=nativeStable,
    nativeProspectiveJarSHA=sha(P/'native/01/prospective.jar'),finalProspectiveJarSHA=sha(P/'compile/08/prospective.jar'),
    receiverUI=False,longPathSupported=False,RootMounted=False,accountVideoPassed=False,detachTimeoutBoundaryPassed=False,
    nativeLaterDelta=['Shell startup ownership-cancel feedback','removed duplicate direct CacheClearUiPolicy producer; same original LF bytes'],
    noJARorGeneratedTreesForGit=True,productionInstalled=False,deployedEXEReplaced=False))
contract['frozen']=True;dump(P/'install-contract.json',contract)
paths=set()
for folder in ('prepared','baseline','patches'):
 for p in (P/folder).rglob('*'):
  if p.is_file()and p.suffix in('.kt','.py','.json'):paths.add(p.relative_to(P).as_posix())
for name in ('README.md','install-contract.json','registry-delta.json','gradle-delta.json','gradle-append.kts','inverse-checks.json','source-provenance.json','producer-output-receipt.json','evidence-scope.json',
    'StorageOwnerFixture.kt','StorageNativeOwnerFixture.kt','compile84.py','run_native84.py','build_contract.py','freeze.py'):
 paths.add(name)
for folder in ('compile/08','native/01'):
 for p in (P/folder).iterdir():
  if p.is_file()and p.suffix in('.json','.log','.args'):paths.add(p.relative_to(P).as_posix())
# Carry concise actual failures; older intermediate outputs stay local and untouched.
for name in ('compile/01/compile.log','compile/01/result.json','compile/04/compile.log','compile/04/result.json'):
 if wide(P/name).exists():paths.add(name)
rows=[dict(path=name,sha256Bytes=sha(P/name),bytes=len(read(P/name)))for name in sorted(paths)]
for row in rows:assert not any(s in row['path']for s in('/generated','prospective.jar','.dll'))
dump(P/'frozen-handoff.json',dict(frozen=True,packet='stable-settings-storage-owner-parity',baselineHead=contract['baselineHead'],raw=rows,
    rawCount=len(rows),inputSourceIdentities=7,newSourceIdentities=3,newXMLIdentities=1,modifiedFamilies=19,totalHunks=66,
    finalFullShellCompilePassed=True,CPUAssertionsPassed=46,actualNativeOwnerAssertionsPassed=20,
    installedInCandidate=False,RootMounted=False,ordinaryAccountVideoPassed=False,systemShareReceiverPassed=False,longPathSupported=False,detachTimeoutBoundaryPassed=False,
    desktopEXEReplaced=False,sourceRegistryWholeReplacement=False,buildFileWholeReplacement=False,depsChanged=False))
print('FROZEN',len(rows),'raw; handoffSHA',sha(P/'frozen-handoff.json'))
