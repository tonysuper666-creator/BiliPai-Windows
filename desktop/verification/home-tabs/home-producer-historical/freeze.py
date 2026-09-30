from pathlib import Path
import hashlib,json,os,re,subprocess
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def ext(p):
 p=str(Path(p).absolute());return Path(p if p.startswith('\\\\?\\') else '\\\\?\\'+p)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(path,value):ext(path.parent).mkdir(parents=True,exist_ok=True);ext(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
evidence=json.loads((HERE/'compile-evidence.json').read_text());assert evidence['passed'];assert evidence['activeClasses']=='classes-attempt10'
for row in evidence['sources']:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
dependencies=json.loads((HERE/'dependency-identities.json').read_text())
for row in dependencies:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
baselines=json.loads((HERE/'target-baselines.json').read_text())
for row in baselines:
 assert sha(REPO/row['path'])==row['baseSha256Bytes']
 assert sha(HERE/'prepared'/row['path'])==row['desiredSha256Bytes']
r=subprocess.run(['git','apply','--check',str(HERE/'consumer.patch')],cwd=REPO,capture_output=True,text=True);assert r.returncode==0,r.stderr
(HERE/'patch-check.log').write_text('git apply --check: PASS\n'+r.stdout+r.stderr,encoding='utf-8')
# Keep the exact earlier crowded consumer bytes, recovered without modifying any existing proof.
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt'
historical=(HERE/'prepared'/path).read_text(encoding='utf-8')
historical=historical.replace('FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp))','Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween)')
historical=historical.replace(', modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)','')
history=HERE/'historical-crowded-consumer/DiscoveryScreens.kt';ext(history.parent).mkdir(parents=True,exist_ok=True);ext(history).write_text(historical,encoding='utf-8',newline='\n')
assert sha(history)=='a6e18f3cfb6fc4ed6fd46b35caddb13ebe16cc58fbc48705eeeb9321a6ab156a'
write(HERE/'historical-evidence.json',dict(crowdedConsumerSha256Bytes=sha(history),
 original5065SnapshotProof='proof/5',newC013SnapshotBeforeFooterAdaptation='proof/6 and proof/7',
 attempt8=dict(result='failed',reason='Actual existing footer OnClick height40 below48',log='ui-8.log'),
 attempt9=dict(result='fixture assertion failed',reason='Viewport-clipped second-row bounds compared as full button. Final test checks actual layout size before visible bounds.',log='ui-9.log'),
 finalAccepted='proof/10',thresholdNotLowered=True))
proof=HERE/'proof/10';junit=json.loads((proof/'junit.json').read_text());ui=json.loads((proof/'ui.json').read_text());cold=json.loads((proof/'cold-disk.json').read_text())
assert junit['actualAnnotatedUnitMethods']==10 and junit['passed']
assert ui['actualFlows']==8 and ui['actualPointerPairs']==64 and ui['passed']
assert len(cold)==8 and all(row['passed'] for row in cold)
pngs=list(proof.rglob('*.png'));assert len(pngs)==40,(len(pngs),[p.name for p in pngs]) #34screenshots+6 actual local source images
owned=[]
for source in sorted((HERE/'prepared').rglob('*')):
 if source.is_file() and source.suffix in ('.kt','.py'):
  path=str(source.relative_to(HERE/'prepared')).replace('\\','/');baseline=next((r for r in baselines if r['path']==path),None)
  owned.append(dict(path=path,prepared=str(source.relative_to(HERE)).replace('\\','/'),sha256Bytes=sha(source),
   existingTarget=baseline is not None,baseSha256Bytes=baseline['baseSha256Bytes'] if baseline else None))
assert len(owned)==6,len(owned)
excluded={'artifact-manifest.json','frozen-handoff.json'};files=[]
for directory,_,names in os.walk(ext(HERE)):
 for name in names:
  path=Path(directory)/name;relative=str(path)[len(str(ext(HERE)))+1:].replace('\\','/')
  if relative in excluded:continue
  files.append(dict(path=relative,sha256Bytes=sha(path),sizeBytes=path.stat().st_size))
external=[];roots=set()
for args in HERE.glob('*.args'):
 for line in args.read_text(encoding='utf-8').splitlines():
  value=line.strip('"')
  if value.startswith('-Duser.home='):
   root=Path(value.split('=',1)[1]);owner=root/'fixture-owner.json'
   if owner.exists():
    assert json.loads(owner.read_text())['task']=='settings-home-card-parity';roots.add(root)
for root in roots:
 for directory,_,names in os.walk(ext(root)):
  for name in names:
   path=Path(directory)/name;normal=Path(str(path).removeprefix('\\\\?\\'))
   external.append(dict(path=str(normal),sha256Bytes=sha(path),sizeBytes=path.stat().st_size,taskOwned=True))
manifest=dict(files=sorted(files,key=lambda row:row['path']),externalTaskOwnedFiles=sorted(external,key=lambda row:row['path']),
 pathTraversal='Win32 extended absolute path \\\\?\\ read/traversal, all long generated paths included')
write(HERE/'artifact-manifest.json',manifest)
contract=dict(frozen=True,phase='prepared-original-home-card-layout-four-fields',ownedFiles=owned,
 artifactManifest='artifact-manifest.json',artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),
 artifacts=len(files),externalTaskOwnedArtifacts=len(external),productSnapshotManifestSha256Bytes=evidence['productSnapshotManifestSha256Bytes'],
 currentOriginalRegistrySha256Bytes=sha(REPO/'desktop/upstream-sources.json'),
 actualEvidence=dict(annotatedJUnitUnitMethods=10,pythonSourceTests=6,uiFlows=8,actualPointerPressReleasePairs=64,
 screenshotPngs=34,actualLocalSourcePngs=6,coldJvmReaders=8,actualMainClassResourcePins=12,
 actualFooterMeasuredMinimumDp=48,actualNativeWindow=False,realAccountOrApi=False,productionOverlaysExplicit=True,
 replacementStoreRendererOrOriginalGrid=0,wholeMainOrRootIntegrated=False,packagedNativeAccepted=False),
 sourceInventory='source-inventory.json',sourceRegistrationDelta='source-registration-delta.json',
 sourceIdentities=7,newIdentities=5,newResources=0,newDependencies=0,
 acceptedProof='proof/10',acceptedClasses=evidence['activeClasses'],rootContract='ROOT-INTEGRATION.md',
 mainMutation=False,sharedGradle=False,existingFrozenEvidenceChanged=False,
 explicitRemainingGaps='FIELD-AUDIT.md / field-audit.json')
write(HERE/'frozen-handoff.json',contract)
print('FROZEN',sha(HERE/'frozen-handoff.json'),'artifact',sha(HERE/'artifact-manifest.json'),'files',len(files),'external',len(external))
