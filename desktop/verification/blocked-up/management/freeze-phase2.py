from pathlib import Path
import hashlib,difflib,importlib.util,json
HERE=Path(__file__).resolve().parent;STAGE1=HERE.parent;ROOT=STAGE1.parents[2]
def ext(p):
    name=str(p.absolute());return Path(name if name.startswith('\\\\?\\') else '\\\\?\\'+name)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(name,value):(HERE/name).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
evidence=json.loads((HERE/'compile-evidence.json').read_text())
assert evidence['passed'] and evidence['normalRuntimeExit'] and evidence['normalComposeRuntimeExit']
for path,digest in evidence['compiledSourceIdentities'].items():assert sha(HERE/path)==digest,path
assert sha(HERE/'proof/result.json')==evidence['runtimeReportSha256']
assert sha(HERE/'ui-proof/result.json')==evidence['composeReportSha256']
assert sha(STAGE1/'store-contract.json')=='5dac0250ccf68fd1e51993327d03c97c23f1773fc79994b2b63ae126069f5f36'
python=json.loads((HERE/'extraction-evidence.json').read_text());assert python['passed'] and python['testsRun']==3
spec=importlib.util.spec_from_file_location('blocked_freeze_extractor',HERE/'prepared/desktop/tools/extract-upstream-blocked-list-ui.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
assert python['extractorSha256Bytes']==sha(HERE/'prepared/desktop/tools/extract-upstream-blocked-list-ui.py')
rows=json.loads((HERE/'owned-base-files.json').read_text());byPath={r['path']:r for r in rows}
for file in (HERE/'prepared').rglob('*'):
    if not file.is_file():continue
    path=file.relative_to(HERE/'prepared').as_posix()
    if path not in byPath:
        row=dict(path=path,prepared=file.relative_to(HERE).as_posix(),baseSha256Bytes=None,sha256Bytes=sha(file));rows.append(row);byPath[path]=row
for row in rows:
    assert sha(HERE/row['prepared'])==row['sha256Bytes'],row['path']
    if row['baseSha256Bytes']:
        base=ROOT/row['path'];assert sha(base)==row['baseSha256Bytes'],f'Main base changed: {row["path"]}'
        baseline=HERE/'baseline'/row['path'];baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(ext(base).read_bytes())
        patch=HERE/'patches'/(Path(row['path']).name+'.patch');patch.parent.mkdir(parents=True,exist_ok=True)
        patch.write_text(''.join(difflib.unified_diff(base.read_text(encoding='utf-8').splitlines(True),(HERE/row['prepared']).read_text(encoding='utf-8').splitlines(True),
            fromfile='a/'+row['path'],tofile='b/'+row['path'])),encoding='utf-8',newline='\n')
write('owned-files.json',rows);write('source-inventory.json',tool.inventory(ROOT))
resources=[]
for asset in ['lv0','lv1','lv2','lv3','lv4','lv5','lv6','lv6_s']:
    path=f'app/src/main/res/drawable-nodpi/{asset}.png';destination=f'desktop/src/main/resources/blocked-up-badges/{asset}.png'
    assert sha(ROOT/path)==sha(HERE/'prepared'/destination)
    resources.append(dict(path=path,destination=destination,sha256=sha(ROOT/path),features=['settings-blocked-up']))
write('resource-inventory.json',resources)
origin=[]
for path in ['app/src/main/java/com/android/purebilibili/feature/search/SearchViewModel.kt','app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt',
    'app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt','app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt']:
    origin.append(dict(path=path,sha256Bytes=sha(ROOT/path),compiledInThisLane=False))
write('origin-reference-identities.json',origin)
artifacts=[]
for path in sorted(ext(HERE).rglob('*')):
    if not path.is_file():continue
    relative=path.relative_to(ext(HERE)).as_posix()
    if relative in ['contract.json','artifact-manifest.json'] or '__pycache__' in relative or relative.startswith(('proof/appdata-',
        'classes/','classes-current-store/','fixture-only-source-overrides/')):continue
    artifacts.append(dict(path=relative,sha256Bytes=sha(path)))
write('artifact-manifest.json',dict(frozen=True,extendedWin32Paths=True,files=artifacts))
write('contract.json',dict(frozen=True,stage=2,parentStoreContractSha256=sha(STAGE1/'store-contract.json'),
    artifactManifestSha256=sha(HERE/'artifact-manifest.json'),ownedFiles=rows,sourceInventorySha256=sha(HERE/'source-inventory.json'),
    resourceInventorySha256=sha(HERE/'resource-inventory.json'),compileEvidenceSha256=sha(HERE/'compile-evidence.json'),
    junitMethodsPassed=15,headlessActualPointers=28,extractionTestsPassed=3,rootIntegrated=False,mainEdited=False,
    sharedGradle=False,nativeWindow=False,realAccountReadOrWrite=False,realRemoteProfilesVerified=False,
    filePickerAndClipboardVerified=False,relatedVideoRootHookRemaining=True,globalStoreSingletonRootHookRequired=True))
for row in artifacts:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
print('Frozen',len(rows),'owned files;',len(artifacts),'extended-path artifacts; contractSHA',sha(HERE/'contract.json'))
