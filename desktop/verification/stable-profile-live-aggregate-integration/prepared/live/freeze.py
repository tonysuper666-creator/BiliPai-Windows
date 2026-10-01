from pathlib import Path
import json,hashlib,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return json.loads(safe(p).read_text(encoding='utf-8'))
checks=read(HERE/'source-checks.json');assert checks['status']=='PASS' and checks['focusedFixture']['assertions']==25
compiled=read(HERE/'runs/compile04/compile-result.json')
for row in compiled['sourceInputs']:
 p=Path(row['path']);relative=p.relative_to(HERE/'runs/compile04/source-inputs')
 assert sha(p)==sha(HERE/relative),relative
for base in ['prepared/generated','production-generated']:
 for p in (HERE/base).rglob('*.kt'):
  if base=='production-generated':assert sha(p)==sha(HERE/'prepared/generated'/p.relative_to(HERE/base))
payloads=[dict(source=str(HERE/p),target=p.removeprefix('prepared/'),sha256Bytes=sha(HERE/p)) for p in [
 'prepared/desktop/tools/extract-upstream-live-navigation.py',
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiveNavigationBindings.kt']]
contract=dict(sourceOnly=True,pinnedCommit=checks['upstreamCommit'],payloads=payloads,defaultGeneratedOutputs=5,directOriginalPolicyCopies=1,
  manualBindings=1,sourceCompilerInputs=8,classes=110,existingProductionOverrides=0,sharedHunks='sole-shared-helper-hunk.json',registry='registry-merge-recipe.json',
  callerRecipe='ROOT-INTEGRATION.md',candidateJarSha256Bytes=checks['candidateJarSha256Bytes'],preparedJarInstall=False,
  actualRootMountedUiAccepted=False,actualAccountTransportAccepted=False,HTTP=False,GUI=False,HWND=False,Gradle=False)
safe(HERE/'install-contract.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
artifacts=[]
for p in sorted(HERE.rglob('*')):
 if p.is_file() and p.name!='frozen-handoff.json' and '__pycache__' not in p.parts:
  artifacts.append(dict(path=str(p),relative=p.relative_to(HERE).as_posix(),sizeBytes=safe(p).stat().st_size,sha256Bytes=sha(p)))
manifest=dict(frozen=True,scope='prepared-full-original-Live-sub-navigation-source-only',pinnedCommit=checks['upstreamCommit'],
  actualDependencySnapshot='stable-product-snapshot-39',actualManifestSha256Bytes=checks['actualSnapshotManifestSha256Bytes'],
  actual97CpSha256Bytes=checks['actualOrdered97CpSha256Bytes'],sourceChecksSha256Bytes=sha(HERE/'source-checks.json'),
  installContractSha256Bytes=sha(HERE/'install-contract.json'),rootIntegrationSha256Bytes=sha(HERE/'ROOT-INTEGRATION.md'),
  compiledSources=8,compiledClasses=110,fixtureAssertions=25,fixtureGroups=3,existingProductionOverrides=0,
  directOriginalCopyOnce=True,productionSelectedOutputs=5,mandatoryRouteOwner=True,sameGlobalSettings=True,
  actualRootMountedUiAccepted=False,artifacts=artifacts)
out=HERE/'frozen-handoff.json';assert not out.exists();safe(out).write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(manifest=str(out),sha256Bytes=sha(out),artifacts=len(artifacts),checks=sha(HERE/'source-checks.json'),contract=sha(HERE/'install-contract.json'),recipe=sha(HERE/'ROOT-INTEGRATION.md')),indent=2))
