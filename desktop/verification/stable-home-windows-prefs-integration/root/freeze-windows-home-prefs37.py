from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-home-windows-prefs-integration';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
rows=[];excluded=[]
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
LANE=MAIN/'desktop/.local/stable-home-windows-prefs-ports-parity'
raw=(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='d3c1d609dc624eca5894e8d68e13727f24adc3000dfd9428f70088585f0a08ac'
put('prepared/frozen-handoff.json',raw)
for r in json.loads(raw)['artifacts']:
 b=(LANE/r['path']).read_bytes();assert sha(b)==r['sha256Bytes'];put('prepared/'+r['path'],b)
for folder in ['windows-home-prefs-install','windows-home-prefs-actual37']:
 for p in sorted(wide(HERE/folder).rglob('*'),key=str):
  if not p.is_file():continue
  b=p.read_bytes();name='root/'+folder+'/'+p.relative_to(wide(HERE/folder)).as_posix()
  if p.suffix in ('.class','.kotlin_module','.jar','.dll'):
   excluded.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable output; trusted actual native asset has its fresh producer receipt and exact digest'))
  else:put(name,b)
for name in ['install-windows-home-prefs.py','prove-windows-home-prefs37.py','HomeWindowsPreferencesActualFixture.kt','freeze-windows-home-prefs37.py','jvm-method-name-audit-37.json']:
 put('root/'+name,(HERE/name).read_bytes())
for name in ['manifest.json','ordered-runtime-cp.json']:
 put('root/snapshot37/'+name,(MAIN/'desktop/.local/stable-product-snapshot-37'/name).read_bytes())
put('root/classes-37.log',(REPO/'desktop/.local/stable-build-repair/classes-37.log').read_bytes())
producer=REPO/'desktop/build/generated/native-diagnostic-share/producer-receipt.json';receipt=json.loads(producer.read_bytes())
put('root/native-producer-receipt.json',producer.read_bytes());put('root/native-actual-input-graph.json',Path(receipt['actualBuildGraph']).read_bytes())
put('root/native-approved-development-build.json',(REPO/'desktop/native/diagnostic-share/approved-development-build.json').read_bytes())
proof=json.loads((HERE/'windows-home-prefs-actual37/runtime.log').read_text(encoding='utf-8'));assert proof['passed']and proof['assertions']==18
report=dict(wholeClassesPhase=37,wholeClassesPassed=True,sourceIdentityCount=833,resourceCount=213,
 actualRuntimeEntries=97,productionOverrides=0,actualProductClassOrigins=4,actualWindowAndPreferredProfileAssertions=18,
 freshSameNativeDllSha256=receipt['expectedDllSha256Bytes'],sameReviewedNativeProducerAndGraph=True,
 originalLargeScreenPolicyOneProducer=True,observedPhysicalMonitor=[3840,2160],observedScale=[1.5,1.5],observedPreferredIanaInterfaceType=71,
 actualCellularHardwareAvailableForThisProof=False,actualFullRootMounted=False,actualAccountOrExeAccepted=False,
 pending=['Full original Home retained entry/four embedded pages/return navigation','Root Windows preference construction and unavailable capability UX','Final Windows EXE deployment'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''# Actual Windows Home preference platform

One required platform adapter, two native source hunks and one original classification selector hunk compile in the whole candidate classes37. The same existing native producer rebuilds its DLL and checks all resolved SDK/STL/tool/library inputs against the new reviewed development graph; no installed DLL becomes a trust root. The original threshold and existing source identity keep one producer. No new persistent store, HTTP client, network actor, profile cache or event subscription is introduced.

Actual immutable37/97 runtime, zero production overrides and four actual product class origins pass18 checks on a real hidden JFrame/EDT and the freshly built same DLL. Observed monitor is3840×2160 at150%; resizing leaves the initial device-default observation retained. Real preferred-profile queries succeed from EDT and a fixture thread, twelve repeated native calls finish, and both closed-owner and retirement-during-query results are rejected. The current profile reports IANA71/Wi-Fi/InternetAccess and is not cellular. No actual cellular hardware transition was tested. Windows hinge detection remains explicitly unavailable. Metered cost is not used as cellular status.

This isolated actual-window/platform proof does not claim the full Root Home mount, actual account/network repository requests or a deployed EXE. The required Root construction, four original embedded pages, complete navigation and return geometry remain pending. The full candidate preserves the previous Root until that closure can be switched together.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(raw),actualAssertions=18)))
