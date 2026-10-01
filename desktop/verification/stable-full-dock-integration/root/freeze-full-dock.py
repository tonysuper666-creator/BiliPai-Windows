from pathlib import Path
import hashlib,json,sys
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
phase=int(sys.argv[1]); OUT=REPO/'desktop/verification/stable-full-dock-integration'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
rows=[];seen=set();excluded=[]
def put(name,b):
    assert name not in seen,name;seen.add(name)
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
for prefix,lane,digest in (
    ('prepared-linked','stable-linked-dock-parity','e5eb03ec2e44d0b20e6817bcd53eab30851ff60682f7467cb19ce2475e4a9602'),
    ('prepared-frosted','stable-frosted-audio-renderer-parity','a35f99c0f41a474c08814fcddfc9dd99f01b003aab83b54b919d9059901efb73')):
    directory=REPO/'desktop/.local'/lane
    raw=pin(directory/'frozen-handoff.json',digest);m=json.loads(raw)
    put(prefix+'/frozen-handoff.json',raw)
    for r in m['evidence']:
        b=pin(r['path'],r['sha256Bytes'])
        relative=wide(r['path']).relative_to(wide(directory)).as_posix()
        if Path(relative).suffix in ('.jar','.class','.png','.dll','.exe'):excluded.append(dict(cohort=prefix,**r))
        else:put(prefix+'/'+relative,b)
    for r in m['payload']:put(prefix+'/prepared/'+r['target'],pin(r['source'],r['sha256Bytes']))
for name in ('install-full-dock.py','full-dock-install.json','correct-full-dock-name-collision.py',
             'full-dock-name-collision-correction.json','correct-full-dock-shared-reference.py',
             'full-dock-shared-reference-correction.json','freeze-product.py','prove-full-dock-actual-product.py'):
    put('root/'+name,read(HERE/name))
put('root/freeze-full-dock.py',read(Path(__file__)))
for p in sorted(wide(HERE/'full-dock-install-baseline').rglob('*'),key=str):
    if p.is_file():put('root/initial-baselines/'+p.relative_to(wide(HERE/'full-dock-install-baseline')).as_posix(),read(p))
for n in range(23,phase+1):put('root/classes-'+str(n)+'.log',read(REPO/f'desktop/.local/stable-build-repair/classes-{n:02}.log'))
snap=MAIN/f'desktop/.local/stable-product-snapshot-{phase}'
manifest=read(snap/'manifest.json');metadata=json.loads(manifest)
assert metadata['wholeCandidateClassesPassed'] and metadata['sourceRegistryCount']==765
put('root/snapshot'+str(phase)+'/manifest.json',manifest)
cp=pin(snap/'ordered-runtime-cp.json',metadata['orderedRuntimeClasspathSha256Bytes'])
put('root/snapshot'+str(phase)+'/ordered-runtime-cp.json',cp)
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
relocation=MAIN/'desktop/.local/stable-miuix5157-original-runtime'
put('root/historical-cp-relocation/relocation-ledger.json',pin(relocation/'relocation-ledger.json','68b9a804eabd84eedaf40c8b8e2487daad060616a4bac754c1f2f7d3be4fafec'))
for p in sorted(relocation.glob('ordered-runtime-cp-*.json')):put('root/historical-cp-relocation/'+p.name,read(p))
put('root/historical-cp-relocation/freeze-mutable-fork-runtime.py',read(HERE/'freeze-mutable-fork-runtime.py'))
proof=MAIN/f'desktop/.local/stable-full-dock-main{phase}-ui-proof'
result=json.loads(read(proof/'result.json'))
assert result['passed'] and result['productionClassOverrides']==0
for p in sorted(wide(proof).rglob('*'),key=str):
    if p.is_file() and p.suffix in ('.kt','.json','.args','.log'):
        put('root/actual'+str(phase)+'/'+p.relative_to(wide(proof)).as_posix(),read(p))
for directory in ('linked-dock','frosted-audio-renderer','shared-liquid-tabs'):
    root=REPO/'desktop/build/generated'/directory
    for p in sorted(wide(root).rglob('*'),key=str):
        if p.is_file() and p.suffix in ('.kt','.json'):
            put('root/actual-generated/'+directory+'/'+p.relative_to(wide(root)).as_posix(),read(p))
for target in [r['path'] for r in json.loads(read(HERE/'full-dock-install.json'))['installedFiles']]:
    put('root/final-installed/'+target,read(REPO/target))
put('root/source-parity-report.json',read(HERE/f'full-dock-source-parity-{phase}.json'))
report=dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    sourceRegistryCount=765,resourceCount=213,wholeClassesPhase=phase,wholeClassesPassed=True,
    originalLinkedDockSourceCompiled=True,originalFrostedNavigationSourceCompiled=True,originalAudioNowPlayingBarSourceCompiled=True,
    sameMiuix5157ForkExtendedWith18UnchangedFullIcons=True,noNewBinaryLibraryAdded=True,
    reusedSoleFavoritesHomeScrollPolicyAndTwoCompositionLocals=True,
    independentOriginalPrivateSpecularValuesPreserved=True,onlyGeneratedBottomBarIdentifierMapping=True,
    actualProductUiAssertions=result['assertions'],actualProductUiPointerPairs=result['pointerPairs'],productionClassOverrides=0,
    actualProductUiCases=result['cases'],historical92CpLibraryRelocationSameBytesOnly=True,
    actualSnapshotMutableSourceBuiltDependencyCopiedSameBytes=True,
    fullHomeRootMounted=False,fullDockRootMounted=False,fullMusicPlayerScreenAccepted=False,
    actualNativePlaybackAccepted=False,realAccountOrHTTPUsed=False,systemHWNDUsed=False,desktopExeReplaced=False,
    originalStableMiuix5c91RevisionAlignmentPending=True,postTagSixPatchSupplementApplied=False,
    scope='Actual frozen whole candidate product classes and actual same-fork built library, fixture-only compilation with zero production class overrides; offscreen Compose pointer/behavior checks; Root Home/route/audio ownership integration remains required.',
    failedBuilds=[dict(phase=23,cause='Original file-private same-named values collided after one was widened.'),dict(phase=24,cause='The existing shared selected surface also required the same exact generated identifier mapping.')],
    excludedTaskBinaries=excluded)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(raw)
print(json.dumps(dict(artifacts=len(rows),manifestSHA256=sha(raw),assertions=result['assertions'],pointerPairs=result['pointerPairs'])))
