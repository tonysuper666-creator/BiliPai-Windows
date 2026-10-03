from pathlib import Path
import hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
HEAD='5f5f29a00609ec215b06553b59b3d3669ef0335b'
def wide(p):
    value=str(p);return Path(value if value.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(value))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
assert subprocess.check_output(['git','-C',str(C),'rev-parse','HEAD']).decode().strip()==HEAD
assert not subprocess.check_output(['git','-C',str(C),'status','--porcelain']).strip()
compile=json.loads(wide(P/'compile-runs/12/result.json').read_text(encoding='utf8'))
report=json.loads(wide(P/'fixture-runs/12/report.json').read_text(encoding='utf8'))
assert compile['passed'] and compile['snapshot']==89 and len(compile['explicitPreparedInputs'])==19
assert report['passed'] and report['actualMethods']==15 and len(report['methods'])==15
copies=[]
for f in sorted(wide(P/'prepared').rglob('*')):
    if not f.is_file():continue
    relative=f.relative_to(wide(P/'prepared')).as_posix()
    assert not (C/relative).exists(),relative
    copies.append(dict(target=relative,prepared='prepared/'+relative,sha256Bytes=sha(f),operation='copy-new'))
assert len(copies)==5
hooks=json.loads(wide(P/'root-hooks/exact-hunks.json').read_text(encoding='utf8'))
inventory=json.loads(wide(P/'source-inventory.json').read_text(encoding='utf8'))
for row in hooks['targets']:
    assert sha(C/row['target'])==row['baseRawSha256']
    assert sha(P/row['compilerOverlay'])==row['desiredRawSha256']
    normalized=wide(P/row['compilerOverlay']).read_bytes().decode().replace('\r\n','\n')
    assert hashlib.sha256(normalized.encode()).hexdigest()==row['desiredLfSha256']
    actualInput=next(r for r in compile['explicitPreparedInputs'] if Path(r['path']).name==Path(row['target']).name)
    assert actualInput['sha256Bytes']==row['desiredRawSha256']
for row in copies:
    if row['target'].endswith('.kt') and '/src/main/' in row['target']:
        actualInput=next(r for r in compile['explicitPreparedInputs']if Path(r['path']).name==Path(row['target']).name)
        assert actualInput['sha256Bytes']==row['sha256Bytes']
contract=dict(schemaVersion=1,candidateBase=HEAD,upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    actualProductSnapshot=89,snapshotManifest='desktop/.local/stable-product-snapshot-89/manifest.json',
    snapshotManifestSha256='aa28f253a357c107073d20ef279f28716a96dfa4015aca2be180f1865b14340b',
    orderedRuntimeEntries=105,orderedRuntimeCpSha256='63b3d1693889263d164badad1a3e45aa7b09168181be74cb1eff83b7a82bffb5',
    copyNewTargets=copies,existingCodeTargets=hooks['targets'],
    exactHunks='root-hooks/exact-hunks.json',exactHunksSha256=sha(P/'root-hooks/exact-hunks.json'),
    gradleOperation='append-single-producer-fragment',gradleFragment='root-hooks/gradle-fragment.kts',
    gradleFragmentSha256=sha(P/'root-hooks/gradle-fragment.kts'),gradleBaseRawSha256=sha(C/'desktop/build.gradle.kts'),
    registryOperation='union-existing-features-and-append-missing-paths-only',
    sourceInventory='source-inventory.json',sourceInventorySha256=sha(P/'source-inventory.json'),
    registryBaseRawSha256=sha(C/'desktop/upstream-sources.json'),sourceCountBefore=1225,sourceCountAfter=1229,
    sourceAppendCount=4,sourceUnionCount=8,resourcesBefore=244,resourcesAfter=244,
    originalReplay='generated/source-transform-replay.json',originalReplaySha256=sha(P/'generated/source-transform-replay.json'),
    finalCompile='compile-runs/12/result.json',finalCompileSha256=sha(P/'compile-runs/12/result.json'),
    actualJunitMethods=15,finalFixtureReport='fixture-runs/12/report.json',finalFixtureReportSha256=sha(P/'fixture-runs/12/report.json'),
    normalProductTestCommand='.\\gradlew.bat -p desktop test --tests com.bilipai.desktop.ui.DesktopOriginalSpacePagesTest',
    newDependencies=[],newResources=[],candidateWrites=0,sharedGradleRuns=0,hwndCreated=0,realAccountRequests=0,
    rootRuntimeAccepted=False,installedExeAccepted=False,v025Compiled=False)
wide(P/'install-contract.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2),encoding='utf8')
paths=[]
for prefix in ['prepared','generated','root-hooks','v025-delta','compile-runs/12','fixture-runs/12']:
    paths+=list(wide(P/prefix).rglob('*'))
paths+=[wide(P/name)for name in ['compile.py','fixtures.py','prepare-root-hooks.py','source-facts.py','freeze.py','INTEGRATION.md','source-inventory.json','install-contract.json']]
files=[]
for f in sorted(set(paths)):
    if f.is_file():files.append(dict(path=f.relative_to(wide(P)).as_posix(),sha256Bytes=sha(f),bytes=f.stat().st_size))
wide(P/'frozen-handoff.json').write_text(json.dumps(dict(schemaVersion=1,frozen=True,candidateBase=HEAD,
    contractSha256=sha(P/'install-contract.json'),files=files,
    noWholeExistingFileOverwrite=True,prospectiveCompileAndSyntheticLoopbackOnly=True,
    acceptedRoot=False,realAccounts=False),ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(dict(files=len(files),installContractSha256=sha(P/'install-contract.json'),frozenHandoffSha256=sha(P/'frozen-handoff.json'))))
