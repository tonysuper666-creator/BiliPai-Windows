from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-original-audio-lyrics-integration';assert not (OUT/'artifact-manifest.json').exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():assert p.read_bytes()==b,name
    else:p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
LANE=MAIN/'desktop/.local/stable-original-audio-lyrics-parity';raw=read(LANE/'frozen-handoff.json')
assert sha(raw)=='094597826959fce0c07ee93e3c2f1bd9bdc61becf04fd4b053d1d2480bf69543'
packet=json.loads(raw);assert len(packet['artifacts'])==74
for row in packet['artifacts']:
    b=read(LANE/row['path']);assert sha(b)==row['sha256Bytes'] and len(b)==row['bytes'];put('prepared/'+row['path'],b)
put('prepared/frozen-handoff.json',raw)
installedRoot=H/'original-audio-lyrics-install77';installed=json.loads(read(installedRoot/'installed.json'))
assert installed['newManual']==1 and installed['exactProducerHunks']==1 and installed['registryUnchanged']
for p in sorted(wide(installedRoot).rglob('*')):
    if p.is_file():put('root/install/'+p.relative_to(wide(installedRoot)).as_posix(),p.read_bytes())
S=MAIN/'desktop/.local/stable-product-snapshot-77';raw=read(S/'manifest.json')
assert sha(raw)=='b366ccf36b0b2c5de3b848a9c526de85d2c3de02c95a44330c35f044f16c6f8f'
meta=json.loads(raw);assert meta['runtimeEntries']==101 and meta['sourceRegistryCount']==1170 and meta['resourceCount']==213
pins={r['path']:r['sha256Bytes'] for r in meta['inputs']}
for target in installed['targets']:
    assert sha(read(REPO/target['path']))==target['afterSHA256Bytes']==pins[target['path']]
put('root/snapshot77/manifest.json',raw)
cp=read(S/'ordered-runtime-cp.json');assert sha(cp)=='4219d7fb3a53989184991ef20aa27655fc26dcfff6a983d27a8aaaded01f2dda'
for row in json.loads(cp):assert sha(read(row['path']))==row['sha256Bytes']
put('root/snapshot77/ordered-runtime-cp.json',cp)
for name in ['classes-77.log','classpath77.log']:
    b=read(REPO/'desktop/.local/stable-build-repair'/name);assert b'BUILD SUCCESSFUL' in b and b'BUILD FAILED' not in b;put('root/'+name,b)
audit=read(H/'jvm-method-name-audit-77.json');a=json.loads(audit);assert a['classCount']==15359 and a['issueCount']==0
put('root/jvm-method-name-audit-77.json',audit)
review=read(MAIN/'desktop/.local/stable-original-audio-lyrics-review/source-review.json')
assert sha(review)=='50aeccf517359bd2b70da0c5638dd07412c7abb98badb302c52464bb621120c8'
put('root/independent-source-review.json',review)
put('root/install-original-audio-lyrics77.py',read(H/'install-original-audio-lyrics77.py'))
initialFreezer=OUT/'root/freeze-original-audio-lyrics77.py'
put('root/freeze-original-audio-lyrics77.py',read(initialFreezer) if wide(initialFreezer).exists() else read(H/'freeze-original-audio-lyrics77.py'))
put('root/freeze-original-audio-lyrics77-final.py',read(H/'freeze-original-audio-lyrics77.py'))
generatedPath='com/android/purebilibili/feature/audio/viewmodel/DesktopOriginalMusicLyricsController.kt'
prepared=read(LANE/'generated-04'/generatedPath);actual=read(REPO/'desktop/build/generated/audio'/generatedPath)
preparedLF=prepared.replace(b'\r\n',b'\n');actualLF=actual.replace(b'\r\n',b'\n')
assert preparedLF==actualLF
selection=json.loads(read(LANE/'generated-04/music-lyrics-selection.json'));assert selection['exactSelectedBodyInverse']
put('root/sole-generated-controller-audit.json',(json.dumps(dict(actualGeneratedPath='desktop/build/generated/audio/'+generatedPath,
    preparedSHA256LF=sha(preparedLF),actualSHA256LF=sha(actualLF),sameByteBodyLF=True,
    exactSelectedBodyInverse=True,selectedOriginalFieldsAndMethods=selection['selected']),indent=2)+'\n').encode())
PROOF=MAIN/'desktop/.local/stable-original-audio-lyrics-actual77-proof'
proof=json.loads(read(PROOF/'runs/01/result.json'))
assert proof['passed'] and proof['assertions']==33 and proof['productionOverrides']==0 and proof['uniqueInstalledProductClassOrigins']==8
assert proof['pinsUnchanged'] and json.loads(read(PROOF/'runs/01/overlap.json'))['productClassOverlap']==[]
assert read(PROOF/'runs/01/inputs/AudioLyricsProof.kt')==read(LANE/'AudioLyricsProof.kt')
excluded=[]
for p in sorted(wide(PROOF).rglob('*')):
    if not p.is_file():continue
    path=p.relative_to(wide(PROOF)).as_posix();b=p.read_bytes()
    if p.suffix in ['.jar','.class','.pyc']:
        excluded.append(dict(path=path,sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable fixture output; not production payload.'))
    else:put('root/installed-proof/'+path,b)
put('root/installed-proof/excluded-artifacts.json',(json.dumps(excluded,indent=2)+'\n').encode())
report=dict(phase=77,preparedRaw=74,newManual=1,existingProducerFamilies=1,exactProducerHunks=1,
    newOriginalIdentities=0,sourceIdentityCount=1170,resourceCount=213,registryUnchanged=True,newDependencies=0,
    newPlayerCacheOrClient=False,portMethods=6,originalDomainMethodsSelected=5,originalPrivateLoadSelected=True,
    canonicalMusicUiStateRepositoryModelsAndCacheOwnersPreserved=True,wholeWindowsClassesPassed=True,
    kotlinClasses=15359,illegalJvmMethodNames=0,runtimeEntries=101,installedAssertions=33,
    productionOverrides=0,uniqueInstalledProductClassOrigins=8,allPinsUnchanged=True,
    actualOriginalRepositoryAndFileCacheExercised=True,blockingPublicHttpCancellationClaimed=False,
    completeRootMounted=False,nativeOrWindowAccepted=False,desktopExeReplaced=False)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
assert len({r['path'] for r in rows})==len(rows)
assert {p.relative_to(wide(OUT)).as_posix() for p in wide(OUT).rglob('*') if p.is_file()}=={r['path'] for r in rows}
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''The BV audio-mode lyrics port now supplies five original MusicViewModel domain methods and their original private load helper. The existing audio producer selects the complete consumed fields/method bodies and verifies an exact inverse of the declared platform adaptations. The sixth consumed port method, initPlayer, accepts Root's existing Repository instead of constructing Android MiniPlayer/client/cache objects. Canonical MusicUiState, Repository, providers, models and FileLyricsCache remain their existing sole owners; the full AU player/ViewModel is not emitted by this slice.

Every original launch carries its actual coroutine Job and the Root-captured logical subject lease. Original UI and private query publication enter only the same short Store/entry admission; Repository/cache/provider IO stays outside. The private query is associated with that exact lease, so an early retry/search/select/offset cannot retarget a previous source while a new load is still queued. The synchronous offset action checks the closed lifetime inside final publication. A child lifetime corresponding to the original ViewModel tracks all original jobs, including replaced cancelled jobs; closeCancelJoin must run from external Root teardown outside all gates and returns false until IO really drains. The existing public blocking transport keeps its original timeouts; underlying HTTP cancellation is not claimed here.

Installation adds one platform source and one exact hunk to the existing sole audio producer. The source registry stays 1170 sources and 213 resources with no added dependencies, player, client or cache. Whole Windows classes pass in 11 seconds. The immutable actual77 snapshot has 101 runtime entries and 15359 Kotlin classes with no illegal method names.

The exact frozen fixture compiles against installed actual77 classes with zero product overrides. All 33 checks pass using the real original LyricsRepository/models/FileLyricsCache and deterministic fake providers without HTTP: original cache/provider/retry/manual search/select/offset/NotFound/Failed behavior, late noncancellable cache responses after a source switch, private-context guards before a queued new-source load starts, closed synchronous publication rejection, and an honest false drain followed by successful join. Eight exercised production types have unique application-JAR origins; all runtime/compiler/input pins remain unchanged. The independent review is source-only.

The prepared first generator assertion failure, intermediate selection and prospective verification runs are retained with their scope labels. Generated sources in verification are evidence only. This slice has not mounted the complete player/Music Root into Main, accepted real HTTP/native/window/account behavior, or replaced the desktop EXE.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(manifest),installedAssertions=33,productionOverrides=0)))
