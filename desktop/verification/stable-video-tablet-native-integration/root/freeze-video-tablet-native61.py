from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent/'BiliPai-v023'
OUT = REPO/'desktop/verification/stable-video-tablet-native-integration'
assert not OUT.exists()
def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
rows, excluded = [], []
def put(name, b):
    p = wide(OUT/name); p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(), name
    p.write_bytes(b); rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def register(name, b):
    if Path(name).suffix.lower() in ('.class','.jar','.kotlin_module','.pyc','.png','.jpg','.mp4','.dll'):
        excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable binary or private rendered output'))
    else: put(name,b)

lanes = [
    ('comment',MAIN/'desktop/.local/stable-video-comment-url-parity',
     '02fcdbad3d2a9f58c342d2173e142dfcb1bb1b181a94658c143e1efee8480173'),
    ('tablet',MAIN/'desktop/.local/stable-video-tablet-audio-parity/frozen-tablet-slice-02',
     'bb3f26f6960555378a02ae1bd9b2bb7688ae4d6b3d93483e8c14c36668064bbd'),
    ('native',MAIN/'desktop/.local/stable-video-native-owner-cancellation-parity',
     '8ef0b3d2e03ac1b862b7b1ab6ee1a1274b374aee34ee8ed299c8ed9e418832df')]
for name,lane,pin in lanes:
    raw = read(lane/'frozen-handoff.json'); assert sha(raw)==pin
    data = json.loads(raw)
    for item in data.get('files',data.get('rawArtifacts')):
        source = Path(item['path']); source = source if source.is_absolute() else lane/source
        b = read(source); assert sha(b)==item['sha256Bytes'] and len(b)==item.get('bytes',item.get('size'))
        register('prepared/'+name+'/'+source.relative_to(lane).as_posix(),b)
    put('prepared/'+name+'/frozen-handoff.json',raw)

install = HERE/'video-tablet-native-install61'
installed = json.loads(read(install/'installed.json'))
assert installed['applied'] and installed['installedPayloadCount']==4 and installed['sourceIdentityCount']==1064
for item in installed['targets']:
    assert sha(read(REPO/item['path']))==item['installedSha256Bytes'],item['path']
for folder_name,folder in [('install',install),('actual61-initial-publication-fixture01',HERE/'actual61-initial-publication-fixture01')]:
    for p in sorted(wide(folder).rglob('*'),key=str):
        if p.is_file(): register('root/'+folder_name+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())

comment_path = REPO/'desktop/build/generated/upstream-video-comment-url/com/android/purebilibili/feature/video/screen/DesktopOriginalCommentUrlNavigation.kt'
assert sha(read(comment_path))=='818b31846491cbef3f45782cf32cf223bd8b8c9b9c004278a2ee8cf89fd70282'
put('root/production-generated/comment/DesktopOriginalCommentUrlNavigation.kt',read(comment_path))
tablet = lanes[1][1]
reference = sorted(wide(tablet/'generation-proof/com').rglob('*.kt'),key=str)
assert len(reference)==10
compared=[]
for p in reference:
    rel = p.relative_to(wide(tablet/'generation-proof'))
    if p.name in ('TabletVideoLayoutPolicy.kt','TabletVideoInfoEntrancePolicy.kt'): continue
    actual = REPO/'desktop/build/generated/video-tablet-full'/rel
    assert read(actual)==p.read_bytes(),str(rel)
    compared.append(dict(relativePath=rel.as_posix(),sha256Bytes=sha(read(actual))))
    put('root/production-generated/tablet/'+rel.as_posix(),read(actual))
assert len(compared)==8

snapshot = MAIN/'desktop/.local/stable-product-snapshot-61'
assert sha(read(snapshot/'manifest.json'))=='6b5777dbe6855ddb9529f612580c11aa24dfcd23c771828e51369d610e4a9445'
assert sha(read(snapshot/'ordered-runtime-cp.json'))=='746bdf5e200774e0b1765b31fa0bd35918080cdddf04606318482147f774c057'
for name in ('manifest.json','ordered-runtime-cp.json'): put('root/snapshot61/'+name,read(snapshot/name))
for name in ('classes-61.log','classpath-61.log'): put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
for name in ('install-video-tablet-native61.py','run-actual61-initial-publication-fixture.py','freeze-video-tablet-native61.py','jvm-method-name-audit-61.json'):
    put('root/'+name,read(HERE/name))
audit = json.loads(read(HERE/'jvm-method-name-audit-61.json')); assert audit['issueCount']==0 and audit['classCount']==13688
fixture = json.loads(read(HERE/'actual61-initial-publication-fixture01/runner-result.json'))
proof = json.loads(read(HERE/'actual61-initial-publication-fixture01/result.json'))
assert fixture['passed'] and fixture['productionOverrides']==0 and len(fixture['pinnedProductOrigins'])==5
assert proof['passed'] and proof['assertions']==15
registry = json.loads(read(REPO/'desktop/upstream-sources.json'))
assert len(registry['sources'])==1064 and len(registry['resources'])==213
report = dict(phase=61,sourceIdentityCount=1064,resourceRegistryCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,illegalJvmMethodNames=0,
    commentUrlHasOneSourceProducer=True,tabletCompleteRendererBodiesGenerated=True,
    tabletProductionBodiesExactlyMatchFrozenReferences=compared,tabletDirectFilesSyncOnly=True,
    existingIdentityFeaturesMergedWithoutModeReplacement=True,newSourceIdentities=7,newDependencies=0,
    correctedNativeOwnerInstalled=True,initialAdmissionHasRequiredCallingJobAndGeneration=True,
    transientChecksConsumedOnlyByActualNativeAck60=True,installedProductFixture=fixture,
    fixtureAssertions=15,fixtureCommandsAndAckAreSynthetic=True,nativeFixtureRerun=False,
    actualMainRerun=False,lastActualMainStartupPhase=57,
    fullOrdinaryVideoRootMounted=False,tabletPageMounted=False,fullControllerDrainAccepted=False,
    realAccountUsed=False,newExeDeployed=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest = (json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Original Tablet/Cinema renderers and native-owner admission
=========================================================

The normal selected-source build now generates the complete original TabletVideoLayout, TabletCinemaLayout, TabletDanmakuChromeState, TabletOwnerUploadsPane and BottomInputBar. Original settings declarations and the complete SpaceUiState schema are preserved. Two direct pure policies are copied solely by Sync. Existing source identities receive feature unions without replacing their modes. The sole comment URL producer supplies the exact original declarations to Tablet and the future full owner. Four whitelisted payloads install two producers and two required platform/native adapters; no generated reference is copied into product sources and no dependency is added.

The corrected native owner requires the real calling request Job and generation before the first native command. Only the existing actual60 successful loadfile ACK consumes those checks; later replay uses the retained entry/source lease. Adoption calls the same existing MPV publication adoption API without reloading. Whole classes and test sources61 pass with1064 original identities,213 resources and101 runtime entries. The JVM audit finds zero illegal names among13688 classes. The normal production comment body and eight selected Tablet outputs exactly match frozen proofs. Fifteen original admission assertions replay over only actual61 product JARs, with zero product overrides, five pinned code origins and runtime/source/compiler pins before and after. The fixture models command/ACK callbacks and does not execute native media; actual58/60 native evidence remains separate.

The complete ordinary VM/Holder is not yet mounted. Tablet requires the real same-entry owner-upload, settings, comments, engagement, content and native Surface providers. The old Controller has not drained or handed its native authority to the new owner. No Main startup or broad native cohort is rerun for this inactive source/API integration. No account/HTTP/window interaction or EXE replacement is claimed. Source inventory coverage does not measure functional completion or source reuse.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
