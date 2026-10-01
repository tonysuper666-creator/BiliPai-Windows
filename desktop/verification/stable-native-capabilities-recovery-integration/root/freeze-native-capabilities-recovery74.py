from pathlib import Path
import hashlib,json,subprocess
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-native-capabilities-recovery-integration'
assert not OUT.exists()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists(),name;p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
prepared=[]
for label,name,digest in [('capabilities','stable-native-capabilities-window-parity','837b6ee79a39843c67a63304157015b34ba94952e9c6f1d767d6ca3dd989edb0'),
 ('byte-recovery','stable-native-byte-failure-recovery-parity','ab3eda364cdc3a85b812787ad99792e160ce18354de5808817a24d3b6b618499')]:
 lane=MAIN/'desktop/.local'/name;raw=read(lane/'frozen-handoff.json');assert sha(raw)==digest
 packet=json.loads(raw);artifacts=packet['files']if label=='capabilities'else json.loads(read(lane/'raw-manifest.json'))
 assert len(artifacts)==(144 if label=='capabilities'else 103)
 for r in artifacts:
  b=read(lane/r['path']);assert sha(b)==r['sha256Bytes']and len(b)==r['size']
  put('prepared/'+label+'/'+r['path'],b)
 put('prepared/'+label+'/frozen-handoff.json',raw)
 prepared.append(dict(label=label,raw=len(artifacts),sha256Frozen=digest,prospectiveProofPreserved=True))

install=H/'native-capabilities-recovery-install74';installed=json.loads(read(install/'installed.json'))
assert installed['newManual']==6 and installed['exactHunks']==17
for p in sorted(wide(install).rglob('*')):
 if p.is_file():put('root/install/'+p.relative_to(wide(install)).as_posix(),p.read_bytes())
snapshot=MAIN/'desktop/.local/stable-product-snapshot-74';metaRaw=read(snapshot/'manifest.json')
assert sha(metaRaw)=='6e6f2ca97fc5e98f00f3806ad7ffeceff271466a20cc90d68479e37e38ffe210'
meta=json.loads(metaRaw);assert meta['runtimeEntries']==101 and meta['sourceRegistryCount']==1169 and meta['resourceCount']==213
pins={r['path']:r['sha256Bytes']for r in meta['inputs']}
for r in installed['targets']+installed['changedFiles']:
 digest=r.get('afterSha256Bytes',r.get('sha256Bytes'));assert sha(read(REPO/r['path']))==digest==pins[r['path']]
cpRaw=read(snapshot/'ordered-runtime-cp.json');assert sha(cpRaw)=='e5e3a3375a6d5a85fab9d51343af496bb16b46eff71aadeb98e227b766dbf2cd'
cp=json.loads(cpRaw);assert len(cp)==101
for r in cp:assert sha(read(r['path']))==r['sha256Bytes']
put('root/snapshot74/manifest.json',metaRaw);put('root/snapshot74/ordered-runtime-cp.json',cpRaw)
for name in ['classes-74.log','classpath74.log']:
 b=read(REPO/'desktop/.local/stable-build-repair'/name);assert b'BUILD SUCCESSFUL'in b and b'BUILD FAILED'not in b;put('root/'+name,b)
auditRaw=read(H/'jvm-method-name-audit-74.json');audit=json.loads(auditRaw)
assert audit['classCount']==15296 and audit['issueCount']==0;put('root/jvm-method-name-audit-74.json',auditRaw)
for name in ['install-native-capabilities-recovery74.py','freeze-native-capabilities-recovery74.py']:
 put('root/'+name,read(H/name))

proof=MAIN/'desktop/.local/stable-native-capabilities-recovery-actual74-proof'
run=proof/'runs/actual74-01';result=json.loads(read(run/'runtime-result.json'))
assert result['exit']=={'capabilities':0,'byte-recovery':0}and result['productionOverrides']==0
assert result['snapshot']==74 and result['pinsUnchanged'] and result['workerUnchanged']
assert json.loads(read(run/'overlap.json'))['classOverlap']==[]
origins=json.loads(read(run/'class-origins.json'));assert len(origins)==14
assert all(len(v)==1 and v[0]['jar']['sha256Bytes']==cp[1]['sha256Bytes']for v in origins.values())
caplog=read(run/'capabilities.log');bytelog=read(run/'byte-recovery.log')
assert b'PASS checks=20;'in caplog and b'"assertions":19'in bytelog and b'"productionOverrides":0'in bytelog
excluded=[]
for p in sorted(wide(proof).rglob('*')):
 if not p.is_file():continue
 rel=p.relative_to(wide(proof)).as_posix();b=p.read_bytes()
 if '/classes/'in rel or '/owned-data/'in rel:
  excluded.append(dict(path=rel,sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable fixture bytecode or fixture-owned private task data; not a production payload.'))
 else:put('root/installed-proof/'+rel,b)
put('root/installed-proof/excluded-artifacts.json',(json.dumps(excluded,indent=2)+'\n').encode())
report=dict(phase=74,prepared=prepared,newManual=6,existingFamilies=4,exactHunks=17,
 sourceIdentityCount=1169,resourceCount=213,newIdentityCount=0,newDependencyCount=0,registryUnchanged=True,
 wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClasses=15296,illegalJvmMethodNames=0,
 installedRuntimeEntries=101,installedNativeAssertions=39,productionOverrides=0,uniqueInstalledProductionClassOrigins=14,
 installedSameSessionDecoderQueryAccepted=True,installedActual503BridgeDirectRecoveryAccepted=True,
 hardwareDecoderSelectedOrPhysicalMonitorAccepted=False,windowLeaseRuntimeAccepted=False,wholeRootVMOrMainShellAccepted=False,
 realAccountExternalHttpAccepted=False,desktopExeReplaced=False,
 diagnosticTextEncoding='Native stdout/stderr stored after UTF-8 decoding with replacement; structured pass/observations are ASCII/UTF-8.')
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''The actual native player queries decoder-list from its existing Session and publishes typed capabilities before ready. Original codec selection sees real HEVC/AV1/EAC3 decoder availability, including software support. The same Root Windows window adapter adds source-owned fullscreen/chrome/wake/PiP registrations. HDR display information uses the actual HWND monitor and HDR-specific Advanced Color INFO_2; unavailable API results and absent Dolby Vision profile negotiation remain explicit.

Native cache metadata/body IOException can publish a single typed, receipt/publication-bound event. The existing original observer and actual PluginBridge consume it through the installed Invocation/NativeOwner and recover the same source version directly, preserving actual position and desired pause. It does not fabricate MPV failure attempts, add a polling actor or retry the failed cache capability. Cancellation/prefetch exclusions are source-verified; the actual runtime case is body HTTP503.

Six new platform files and seventeen exact hunks across four existing source families pass whole classes/test-source compilation in 1m37s. Original source/resource identities remain 1169/213. Static JVM checks find no illegal names in 15296 Kotlin classes. All installation rows and indexed inverses are verified before production writes. Prepared source packets retain their original prospective labels and failed histories.

A separate installed actual74 test compiles only two fixture sources with zero production overrides. Fourteen exercised production classes have unique origins in the immutable application JAR. Actual decoder query passes 20 checks; real HTTP503 -> actual Bridge/Invocation -> same-version direct native ACK/frame/readback passes 19. Native DLL, 101 runtime entries and borrowed verified worker resources remain pinned before/after. Injected monitor cases prove only capability policy. Window leases, physical monitor/output, whole original VM/Holder/Main, account/CDN and desktop deployment remain pending. The desktop EXE is unchanged.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSha256=sha(manifest),installedAssertions=39,productionOverrides=0)))
