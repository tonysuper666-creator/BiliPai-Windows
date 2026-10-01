from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
inventory=json.loads((HERE/'prepared/source-inventory.json').read_text(encoding='utf-8'))
outputs=[o for r in inventory for o in r['outputs']]
assert len(inventory)==60 and len(outputs)==59
assert sum(o['mode']=='direct' for o in outputs)==25
assert sum(o['mode']=='selected' for o in outputs)==34
assert json.loads((HERE/'symbol-audit.json').read_text())['classOverlap']==[]
assert json.loads((HERE/'symbol-audit.json').read_text())['publicStaticOverlap']==[]
assert json.loads((HERE/'fixture-final-evidence.json').read_text())['assertions']==26
manual=list((HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'));assert len(manual)==4
install=[HERE/'prepared/desktop/tools/extract-upstream-favorites.py']+manual
whitelist=dict(preparedOnly=True,mainEdits=False,installFiles=[dict(path=p.relative_to(HERE).as_posix(),
 target=p.relative_to(HERE/'prepared').as_posix(),sha256Bytes=sha(p.read_bytes()),
 sha256LfUtf8=sha(p.read_bytes().replace(b'\r\n',b'\n').replace(b'\r',b'\n'))) for p in install],
 generatedReferenceOnly='prepared/generated',doNotCopyGeneratedReferenceToMain=True,
 registryDelta='prepared/registry-delta.json',mergeOnly=True,wholeRegistryReplacement=False,
 producerCommand='python desktop/tools/extract-upstream-favorites.py --repo <actual root> --output <desktop/build/generated/favorites>',
 defaultSelectedOutputs=34,directCopyOutputs=25,sourceIdentities=60,manualSources=4,
 childDrawerManifest='../stable-favorites-folder-sheet-parity/frozen-handoff.json',
 childDrawerSha256='df54bc254658fd114a75ec96f5f01776629b02b0623aef7b80c39a44cb3db9d1')
save(HERE/'install-whitelist.json',whitelist)
compiled=[]
for sub in ('classes-install-final','host-tail-classes'):
 for p in (HERE/sub).rglob('*.class'):
  compiled.append(dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(safe(p).read_bytes()),bytes=safe(p).stat().st_size))
save(HERE/'local-compiled-identities.json',dict(localProofOnly=True,doNotInstallOrCommit=True,artifacts=compiled))
names=['generate.py','prepare_install.py','compile.py','compile-final.args','compile-final.log','compile-final-evidence.json',
 'compile_host_tail.py','compile-host-tail.args','compile-host-tail.log','compile-host-tail-evidence.json',
 'run_fixture.py','fixture-final.log','fixture-final-evidence.json','audit_symbols.py','symbol-audit.json',
 'history-profile-missing-compile.log','initial-closure-diagnostics.json','compile.log','compile-evidence.json',
 'ROOT-INTEGRATION.md','install-whitelist.json','local-compiled-identities.json','freeze.py']
files=[HERE/n for n in names if (HERE/n).exists()]
files+=list((HERE/'prepared').rglob('*'))+list((HERE/'fixture').rglob('*.kt'))
files += [HERE/'audit-review/source-checks.json',HERE/'audit-review/audit.py']
files=sorted(set(p for p in files if safe(p).is_file()),key=lambda p:p.relative_to(HERE).as_posix())
artifacts=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(safe(p).read_bytes()),bytes=safe(p).stat().st_size) for p in files]
handoff=dict(schemaVersion=1,task='stable full original Favorites source candidate',
 originalTag='v0.2.3',originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 productBaseManifestSha256='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87',
 productOrderedCpSha256='bcbc863d545922aaa4308076a4d4651c219aafacf339e8cb3000e59696f9e335',
 integration=False,sharedGradle=False,remoteRequests=False,nativeWindow=False,newEXE=False,
 sourceIdentityCount=60,originalGeneratedOutputs=59,selectedProductionOutputs=34,directOnceOutputs=25,
 preparedPlatformSources=4,focusedFixtureGroups=5,focusedAssertions=26,
 separateFinalHostTailCompile=True,zeroProductClassAndTopLevelFunctionOverlap=True,
 installWhitelist='install-whitelist.json',rootRecipe='ROOT-INTEGRATION.md',
 artifacts=artifacts,artifactCount=len(artifacts),
 exclusions=['compiled .class files (local-compiled-identities.json references only)','previous scratch classes/generated/platform directories',
 'temporary task-store-final/plugin-settings.json','Root product jars and runtime dependency binaries'],
 pending=['Root API/session and retained entry mount','Root video or Listen queue continuation/owned append',
 'Root actual global preferences/dock ports','Root actual original whole Favorites/category/drawer UI pointer proof',
 'auxiliary History/Liked mounted pages and full Android GL dissolve','native popup/window and EXE acceptance'])
save(HERE/'frozen-handoff.json',handoff)
for row in artifacts:assert sha(safe(HERE/row['path']).read_bytes())==row['sha256Bytes']
print('Frozen '+str(len(artifacts))+' raw artifacts, manifest SHA '+sha((HERE/'frozen-handoff.json').read_bytes()))
