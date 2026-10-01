from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-home-ui-media-integration'
PREFIX='\\\\?\\'
def wide(p):
    s=str(Path(p).absolute()); return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
    b=read(p);assert sha(b)==h,str(p);return b
assert not wide(OUT/'artifact-manifest.json').exists()
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def frozen(label,lane,manifest,hash):
    raw=pin(lane/manifest,hash);put(label+'/'+manifest,raw)
    for r in json.loads(raw)['artifacts']:
        b=pin(lane/r['path'],r['sha256Bytes'])
        if Path(r['path']).suffix in ('.class','.jar','.kotlin_module'):
            excluded.append(dict(label=label,path=r['path'],sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable prepared/fixture compiler output; raw hash retained, no binary payload installed'))
        else:put(label+'/'+r['path'],b)
frozen('prepared/home-ui',MAIN/'desktop/.local/stable-home-page-parity','frozen-handoff.json','0b38723109616c73096d6e81fcf932a16f89164aa14bcbdb3db2fe5461626df8')
frozen('prepared/native-media',MAIN/'desktop/.local/stable-home-platform-media-parity','frozen-handoff.json','9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966')
native=MAIN/'desktop/.local/stable-home-native-media-proof'
frozen('root/actual33-native',native,'actual33-frozen-handoff.json','98632d3aced01f4e391148dea26fc7446a3266703a8a0e7f9e7c8e7f3fba03b8')
# Original preparation receipt lists fixture implementation and native pins; preserve its source artifacts.
frozen('root/native-fixture-preparation',native,'prepared-fixture-handoff.json','f853e695355758d2e5ab1ad39a260a3f686abf239fd340dd03196578dc9377f8')
accepted=json.loads(read(native/'runs/actual33-01/accepted.json'));assert accepted['status']=='PASS' and accepted['productionClassOverrides']==0
prefs=MAIN/'desktop/.local/stable-home-request-ports-parity/actual33-prefs-01'
p=json.loads(read(prefs/'sourcebinding.json'));assert p['actualMainPrefsStoreAcceptance'] and p['productOverrideCount']==0
for name in ('compile.args','compile.log','run.log','sourcebinding.json'):put('root/actual33-preferences/'+name,read(prefs/name))
source=MAIN/'desktop/.local/stable-home-page-parity/fixtures/HomePreferencesFixture.kt'
put('root/actual33-preferences/HomePreferencesFixture.kt',pin(source,p['pureFixtureSourceSha']))
put('root/run_preferences_actual33.py',read(MAIN/'desktop/.local/stable-home-request-ports-parity/run_preferences_actual33.py'))
snap=MAIN/'desktop/.local/stable-product-snapshot-33'
put('root/snapshot33/manifest.json',pin(snap/'manifest.json','a94cf303442cc285a81650237f0cc1a3910c47e4f3b1a43e42a23e36e0c63883'))
cp=pin(snap/'ordered-runtime-cp.json','e76eb70649106c7acc0d446c539e6b971fcf578daaca68221a5897fda2fdb09d');put('root/snapshot33/ordered-runtime-cp.json',cp)
assert len(json.loads(cp))==97
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
for folder in ('home-ui-media-install',):
    for f in sorted(wide(HERE/folder).rglob('*'),key=str):
        if f.is_file():put('root/install/'+f.relative_to(wide(HERE/folder)).as_posix(),read(f))
for name in ('install-home-ui-media.py','jvm-method-name-audit-33.json'):
    put('root/'+name,read(HERE/name))
put('root/classes-33.log',read(REPO/'desktop/.local/stable-build-repair/classes-33.log'))
put('root/freeze-home-ui-media33.py',read(__file__))
put('root/compiled-artifact-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
report=dict(targetTag='v0.2.3',wholeClassesPhase=33,wholeClassesPassed=True,sourceIdentityCount=831,resourceCount=213,
    sourceOnlyUiAndMediaInstalled=True,preparedHomeUiArtifactsVerified=524,preparedMediaArtifactsVerified=74,
    actualNativeMediaAssertions=19,actualNativeLoadedClassSources=9,actualPreferencesGates=6,
    productionOverrides=0,orderedRuntimeEntries=97,allRuntimeEntriesPinnedBeforeAndAfter=True,
    actualIndependentLocalNativeVideoAndOffscreenCompose=True,actualHomeVmAndRawProtocolsInstalled=False,
    oldTodayWatchPlannerRetired=False,actualFullRootHomeMounted=False,realAccountOrExternalHttpAccepted=False,
    rootWindowNativeViewportOrScreenPresentationAccepted=False,performanceBenchmarkAccepted=False,newExeDeployed=False,
    pending=['Retained complete original HomeViewModel and same-owner raw request binding','Complete original Home Root/window/embedded-page navigation','Actual overlay/native viewport and full window acceptance','Full Windows EXE delivery'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
wide(OUT/'artifact-manifest.json').write_bytes((json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode())
wide(OUT/'README.md').write_text('''# Original Home UI and actual native media source integration

Whole classes33 compiles 831 original source identities and 213 resources. The sole Home generator emits the complete original Home/category/drawer/preview/pull-refresh components, original settings projection and shared effects; the existing full-card generator retains its single wallpaper/effects ownership. Media uses the existing libmpv DLL/client implementation with an owned software render target, copied opaque frames and Compose image composition, plus the existing animated-image/Coil paths. No HomeViewModel/runtime planner replacement or full Home Root mounting is claimed in this slice.

Actual33 product classes, zero production overrides and 97 immutable runtime entries pass 19 native/offscreen assertions: actual local decoded moving pixels, pause/seek/source replacement, frame ownership/retirement, Compose alpha/clip/first-frame and independent-session/thread cleanup. Nine loaded product class sources are pinned. Six byte-identical original preferences fixture gates exercise the actual same global disk-backed store, defaults/migration/setters and freeze rejection. Its old fixture scope text is preserved and explicitly corrected by the actual33 outer receipt.

Prepared524 UI and74 media artifacts are checked byte for byte. Source, logs, receipts and local fixture media are retained; rebuildable compiler binaries are excluded from this formal source evidence with exact hash/size records. Native local decode/offscreen composition does not prove Root HWND, screen presentation, account HTTP, HDR/performance or the delivered EXE. Those remain pending with the full retained Home state owner and routes.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedRebuildableBinaries=len(excluded),actualNativeAssertions=19,actualPreferencesGates=6)))
