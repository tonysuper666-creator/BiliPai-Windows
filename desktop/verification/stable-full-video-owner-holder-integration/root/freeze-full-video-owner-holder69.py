from pathlib import Path
import hashlib, json, os, subprocess

HERE = Path(__file__).resolve().parent; MAIN = HERE.parents[2]; REPO = MAIN.parent/'BiliPai-v023'
OUT = REPO/'desktop/verification/stable-full-video-owner-holder-integration'; assert not OUT.exists()
baseline = 'd50f228490efa57fda72d679332b3bf9392df1e8'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip() == baseline
def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) or os.name != 'nt' else prefix+s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
rows, excluded = [], []
def put(name,b):
    p = wide(OUT/name); p.parent.mkdir(parents=True,exist_ok=True); assert not p.exists(), name
    p.write_bytes(b); rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def register(name,b):
    if Path(name).suffix.lower() in ('.class','.jar','.kotlin_module','.pyc','.png','.jpg','.mp4','.dll'):
        excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable binary or rendered output'))
    else: put(name,b)

lanes = [
    ('owner',MAIN/'desktop/.local/stable-video-full-owner-parity/install-packet-13',
     '51f079a6a39bbe00cf0c19cc40259b26b7696d7e4a5250aa740f3e62fdba5ff4',363),
    ('holder',MAIN/'desktop/.local/stable-video-holder-ui-parity',
     'e0764d9788d8993fb46b2ff86b6eeaa4e469da65d414610465835e9da75b46f9',337),
]
for name,lane,pin,count in lanes:
    raw = read(lane/'frozen-handoff.json'); assert sha(raw) == pin
    d = json.loads(raw); artifacts = d.get('artifacts',d.get('rawArtifacts')); assert len(artifacts) == count
    for item in artifacts:
        source = Path(item['path']); source = source if source.is_absolute() else lane/source
        b = read(source); size = next(item[k] for k in ('bytes','sizeBytes','size') if k in item)
        assert sha(b) == item['sha256Bytes'] and len(b) == size, str(source)
        register('prepared/'+name+'/'+source.relative_to(lane).as_posix(),b)
    put('prepared/'+name+'/frozen-handoff.json',raw)

install = HERE/'full-video-owner-holder-install69'; installed = json.loads(read(install/'installed.json'))
assert installed['applied'] and installed['payloadCount'] == 10 and installed['sourceIdentityCount'] == 1169
assert installed['laneCounts'] == dict(owner=dict(new=13,existingFeatureUnions=3),holder=dict(new=41,existingFeatureUnions=11))
assert installed['existingFamilyExactHunks'] == 4 and installed['contextChildHunks'] == 3
for item in installed['targets']:
    assert sha(read(REPO/item['path'])) == item['sha256Bytes'], item['path']
for item in installed['changedFiles']:
    assert sha(read(REPO/item['path'])) == item['afterSha256Bytes'], item['path']
    old = subprocess.check_output(['git','show',baseline+':'+item['path']],cwd=REPO)
    assert old.replace(b'\r\n',b'\n') == read(install/'before'/item['path']).replace(b'\r\n',b'\n'), item['path']
for p in sorted(wide(install).rglob('*'),key=str):
    if p.is_file(): register('root/install/'+p.relative_to(wide(install)).as_posix(),p.read_bytes())

proof = json.loads(read(HERE/'full-video-owner-holder-production69.json'))
assert proof['passed'] and proof['selectedProductionBodiesCompared'] == 38 and proof['directPoliciesSyncOnly'] == 31
assert proof['uniqueDirectOriginalIdentities'] == 31
folders = dict(owner='original-video-full-owner',holder='original-video-detail-holder-full')
for r in proof['exactFrozenLFSourceMatches']:
    b = read(REPO/'desktop/build/generated'/folders[r['family']]/r['path'])
    assert sha(b.replace(b'\r\n',b'\n')) == r['sha256LF']
    put('root/production-generated/'+r['family']+'/'+r['path'],b)
for r in proof['directSourceChecks']:
    assert sha(read(REPO/'desktop/build/generated/upstream'/r['source']).replace(b'\r\n',b'\n')) == r['sha256LF']
put('root/production-source-check.json',read(HERE/'full-video-owner-holder-production69.json'))

snapshot = MAIN/'desktop/.local/stable-product-snapshot-69'
assert sha(read(snapshot/'manifest.json')) == '4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
assert sha(read(snapshot/'ordered-runtime-cp.json')) == '125c041f103947b5aa4be065a182cc561528bde575afe682db9e9af43a42dc9f'
snap = json.loads(read(snapshot/'manifest.json')); assert snap['runtimeEntries'] == 101 and snap['sourceRegistryCount'] == 1169 and snap['resourceCount'] == 213
for r in json.loads(read(snapshot/'ordered-runtime-cp.json')): assert sha(read(r['path'])) == r['sha256Bytes']
snapshot_pins = {r['path']:r['sha256Bytes'] for r in snap['inputs']}
for r in installed['targets']: assert r['sha256Bytes'] == snapshot_pins[r['path']]
for r in installed['changedFiles']: assert r['afterSha256Bytes'] == snapshot_pins[r['path']]
for name in ('manifest.json','ordered-runtime-cp.json'): put('root/snapshot69/'+name,read(snapshot/name))
for name in ('classes-69.log','classpath-69.log'): put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
audit = json.loads(read(HERE/'jvm-method-name-audit-69.json')); assert audit['classCount'] == 15048 and audit['issueCount'] == 0
for name in ('install-full-video-owner-holder69.py','verify-full-video-owner-holder69.py','freeze-full-video-owner-holder69.py','jvm-method-name-audit-69.json'):
    put('root/'+name,read(HERE/name))
report = dict(phase=69,sourceIdentityCount=1169,resourceCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClassCount=15048,illegalJvmMethodNames=0,
    installedSourcePayloads=10,upstreamSourceIdentitiesAdded=54,existingIdentityFeatureUnions=14,
    sourceBeforeCommit=baseline,existingFamilyExactHunks=4,contextChildHunks=3,
    installedCorePortraitMembersPreserved=True,contextStringSetAndPresenceAwareRemovalCoexist=True,
    wholeExistingFamilyReplaced=False,normalProductionSelectedSourcesExactLFMatches=38,directPoliciesSyncOnly=31,
    soleFullPlaybackVmAndHolderSourceProducers=True,fullOriginalNotesAndMetadataProtocolsIncluded=True,
    newDependencies=0,proofJarsOrWholeReferenceFamiliesInstalled=False,
    uniqueFullOwnerConstructed=False,fullOrdinaryVideoRootMounted=False,rootFacadeSwitchPending=True,
    inheritedPausedHandoffVmSkipPrepareDeltaPending=True,primaryVipFreshCapturePending=True,
    originalPluginDelayedCdnAndHistoryFinalWriteAdmissionPending=True,
    mediaByteCacheCarrierIntegrationPending=True,requiredPlatformEffectsNeedActualSameRootBindings=True,
    actualMainRerun=False,lastActualMainStartupPhase=57,nativeFixtureRerun=False,
    accountOrHttpOrUiAcceptance=False,newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest = (json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Complete original video Playback VM and detail Holder source integration
======================================================================

The normal Windows build now contains the complete original Playback ViewModel, metadata and Notes protocols, the complete video detail Holder, original Composer and the required renderer/policy closure from fixed upstream v0.2.3. Ten whitelisted source files are installed. Four existing families receive only exact reviewed deltas: settings StringSet, read-only invocation repository access, the original synchronous completion setting reader and Core WBI visibility. The Holder adds three nullable/presence-aware preference removal hunks to that same settings context. Existing Core portrait methods and global store admission remain in place. Registry merge adds54 original identities and14 feature unions, preserving existing hashes and modes, for1169 sources,213 resources and101 runtime entries. Two sole producers emit38 selected source bodies. All31 direct original policies are copied exactly once by the normal registry Sync, and are absent from those producer roots. No generated reference family, proof JAR or additional runtime dependency is installed.

Actual69 whole classes and test sources compile successfully. All38 selected production bodies match the frozen source recipes after LF normalization, and all31 direct Sync bodies match the pinned originals. Static classfile audit finds zero illegal JVM method names among15048 Kotlin classes. The immutable product snapshot pins the exact production inputs and ordered runtime graph. The parent363 and child337 raw packets preserve their original prospective scoped compiles and historical failures; they are not relabeled actual69 runtime proofs. This integration verifies those700 raw artifacts before installation and again before formal evidence capture.

The full owner is not constructed or mounted in the Root route yet. Existing Controller clients continue to operate pending one coordinated switch of ordinary video, queue/favorites, Story, mini-player, audio/music, native controls and retained actors. Required environment ports must bind to the same account request, native receipt, entry, Window, settings, repository, notes, subtitle assets, cache, plugins and domain owners. Actual byte-cache native carriers, delayed CDN and Sponsor history final-write admission, explicit new captures after primary VIP identity changes, and preserving paused adoption in the original skip-prepare load branch remain pending. No empty implementation, second client/store/player or fabricated success is accepted as those bindings. Native handoff and captured action/Runtime proofs from prior phases remain separate. No Main startup, real account/HTTP/UI acceptance, packaging or desktop EXE replacement is claimed here. Source inventory is not functional completion or a line-reuse percentage.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
