from pathlib import Path
import hashlib, json

HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-fullscreen-music-audio-integration'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),name
    p.write_bytes(b);rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def register(name,b):
    if Path(name).suffix.lower()in('.class','.jar','.kotlin_module','.pyc','.png','.jpg','.mp4','.dll'):
        excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable binary or rendered output'))
    else:put(name,b)

lanes=[
    ('fullscreen',MAIN/'desktop/.local/stable-video-fullscreen-pager-parity','35d8c490ccce7c8b19ef86d2de59eda4c8fd65a9499da1e9e854f452f312dc8c',546),
    ('music',MAIN/'desktop/.local/stable-video-tablet-audio-parity/frozen-music-slice','f95b38ff95b90e9dcc38d0a174d24c386438d9f0d23d9ec1086f4a7989e136bc',131),
    ('audio',MAIN/'desktop/.local/stable-video-tablet-audio-parity/frozen-audio-slice','f5eebbb9ddff4fb0a16826de253ff1c90d93c930eca1b207a640ccc1307bbb05',46),
]
for name,lane,pin,count in lanes:
    raw=read(lane/'frozen-handoff.json');assert sha(raw)==pin
    d=json.loads(raw);artifacts=d.get('artifacts',d.get('rawArtifacts'));assert len(artifacts)==count
    for item in artifacts:
        source=Path(item['path']);source=source if source.is_absolute()else lane/source
        b=read(source);assert sha(b)==item['sha256Bytes']and len(b)==item.get('bytes',item.get('size'))
        register('prepared/'+name+'/'+source.relative_to(lane).as_posix(),b)
    put('prepared/'+name+'/frozen-handoff.json',raw)

install=HERE/'fullscreen-music-audio-install65'
installed=json.loads(read(install/'installed.json'))
assert installed['applied']and installed['payloadCount']==8 and installed['sourceIdentityCount']==1115
for item in installed['targets']:
    assert sha(read(REPO/item['path']))==item['sha256Bytes'],item['path']
for p in sorted(wide(install).rglob('*'),key=str):
    if p.is_file():register('root/install/'+p.relative_to(wide(install)).as_posix(),p.read_bytes())

proof=json.loads(read(HERE/'fullscreen-music-audio-production65.json'))
assert proof['passed']and proof['selectedProductionBodiesCompared']==39 and proof['directPoliciesSyncOnly']==19
folders={'fullscreen':'original-video-fullscreen-pager','music':'music-player-full','audio':'video-audio-full'}
for r in proof['exactFrozenLFSourceMatches']:
    b=read(REPO/'desktop/build/generated'/folders[r['family']]/r['path'])
    assert sha(b.replace(b'\r\n',b'\n'))==r['sha256LF']
    put('root/production-generated/'+r['family']+'/'+r['path'],b)
put('root/production-source-check.json',read(HERE/'fullscreen-music-audio-production65.json'))

snapshot=MAIN/'desktop/.local/stable-product-snapshot-65'
assert sha(read(snapshot/'manifest.json'))=='5d402b296762cce3cc35a228e9fef501683ccc2745680430d8f7c0e2073020f8'
assert sha(read(snapshot/'ordered-runtime-cp.json'))=='024adaeff9d44585e5019f1f654c05db52b06f895d560dd120df02603dc6da8b'
snap=json.loads(read(snapshot/'manifest.json'));assert snap['runtimeEntries']==101 and snap['sourceRegistryCount']==1115
for n in ('manifest.json','ordered-runtime-cp.json'):put('root/snapshot65/'+n,read(snapshot/n))
for n in ('classes-65.log','classpath-65.log'):put('root/'+n,read(REPO/'desktop/.local/stable-build-repair'/n))
audit=json.loads(read(HERE/'jvm-method-name-audit-65.json'));assert audit['classCount']==14330 and audit['issueCount']==0
for n in ('install-fullscreen-music-audio65.py','verify-fullscreen-music-audio65.py','freeze-fullscreen-music-audio65.py','jvm-method-name-audit-65.json'):
    put('root/'+n,read(HERE/n))
review=MAIN/'desktop/.local/stable-music-audio-integration-readonly-64/review-result.json'
assert sha(read(review))=='c38f33f9833ace3b27ed26ea1983e03841d76fd718fb49b8a2d21c124f9b2652'
put('root/music-audio-readonly-review.json',read(review))
report=dict(phase=65,sourceIdentityCount=1115,resourceCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClassCount=14330,illegalJvmMethodNames=0,
    installedSourcePayloads=8,upstreamSourceIdentitiesAdded=51,existingIdentityFeatureUnions=6,
    sharedCoreChangedByOneExactAnchor=True,wholeCoreReplaced=False,
    normalProductionSelectedSourcesExactLFMatches=39,directPurePoliciesCopiedOnlyBySync=19,
    newDependencies=0,proofJarsOrReferencesInstalled=False,
    originalFullscreenAndPortraitRendererBodiesIncluded=True,originalMusicLyricsImportHistoryBodiesIncluded=True,
    originalAudioModeScreenAndPlayerBodiesIncluded=True,
    actualMainRerun=False,lastActualMainStartupPhase=57,nativeFixtureRerun=False,
    nativeHandoffAcceptancePhase=64,fullOrdinaryVideoRootMounted=False,audioMusicRootMounted=False,
    originalPlaybackVmAndHolderStillPending=True,requiredPlatformEffectsStillNeedSameRootBindings=True,
    mediaRangePrefetchCapabilityStillNeedsActualTransportCache=True,
    inheritedAudioUserClickDefaultPreserved=True,physicalMonitorRotationNotClaimed=True,
    realAccountUsed=False,newExeDeployed=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Original fullscreen, portrait and music/audio source integration
==============================================================

The normal production build now includes the complete fixed-tag FullscreenPlayerOverlay, PortraitVideoPager and selected controls/subtitles/comment host/UP preview, plus complete original MusicPlayerContent, lyrics, artwork, history/import UI and AudioModeScreen/MusicPlayer. Eight whitelisted producer/platform sources are installed. The existing Core producer receives only its exact one-anchor two-member portrait request append; every existing family remains in place. Fifty-one new original source identities and six feature unions bring the inventory to1115, with213 resources and101 runtime entries. Nineteen direct pure policies remain copied solely by registry Sync. No generated reference or proof JAR is installed, and no dependency is added.

Actual65 whole classes and test sources compile successfully. Thirty-nine selected production source bodies match frozen references after LF normalization; all nineteen direct outputs match the single Sync copy and are absent from the new producer roots. The static JVM audit finds zero illegal method names among14330 Kotlin classes. The previous source-only packets retain their original scoped compilation/algorithm proofs and failed attempts. The final product snapshot binds actual source/build/tool inputs and all ordered runtime artifacts. No previous narrow compile is relabeled as actual65 native acceptance.

The complete original PlaybackVM/Holder and real Root factories remain in progress. These required platform interfaces must bind to the same retained MPV, source receipt, entry/account, Window, Section, comments, Listen, library/history/import and settings owners before these pages are mounted. Media-range prefetch still requires a real owned transport/cache facility; a raw PlayUrl cache is insufficient. Windows presentation intent does not imply physical monitor rotation. The original Audio comment user callback default is preserved. The current Root route and old Controller stay active. Actual64's72 native handoff checks remain separate; no broad Main/native cohort is repeated for inactive renderer/source integration. No account/HTTP/UI acceptance or EXE replacement is claimed. Source coverage is neither functional completion nor a source reuse percentage.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
