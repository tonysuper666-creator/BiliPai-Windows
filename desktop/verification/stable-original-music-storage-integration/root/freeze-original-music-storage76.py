from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-original-music-storage-integration'
assert not (OUT/'artifact-manifest.json').exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
for name,pin,count in [('stable-original-settings-io-parity','0c9daf306710df68f2af52aae9bf75c1b21b217fdd2b70b780e2fc2a585e0b83',21),
    ('stable-original-music-storage-parity','f3a99438b304983ca7dd9806c507baeecd0baf292a63d9bd573c0807c71fece1',80)]:
    lane=MAIN/'desktop/.local'/name;raw=read(lane/'frozen-handoff.json');assert sha(raw)==pin
    packet=json.loads(raw);assert len(packet['artifacts'])==count
    for row in packet['artifacts']:
        b=read(lane/row['path']);assert sha(b)==row['sha256Bytes'] and len(b)==row['bytes']
        put('prepared/'+name+'/'+row['path'],b)
    put('prepared/'+name+'/frozen-handoff.json',raw)
installedRoot=H/'original-music-storage-install76';installed=json.loads(read(installedRoot/'installed.json'))
assert installed['newManual']==1 and installed['existingFamilies']==3 and installed['exactHunks']==5
for p in sorted(wide(installedRoot).rglob('*')):
    if p.is_file():put('root/install/'+p.relative_to(wide(installedRoot)).as_posix(),p.read_bytes())
S=MAIN/'desktop/.local/stable-product-snapshot-76';raw=read(S/'manifest.json')
assert sha(raw)=='65078b6029eeeaf44f9df605a884386b9f0495de7a76b28df1b616bd5c82176a'
meta=json.loads(raw);assert meta['runtimeEntries']==101 and meta['sourceRegistryCount']==1170 and meta['resourceCount']==213
pins={r['path']:r['sha256Bytes'] for r in meta['inputs']}
for target in installed['targets']:
    assert sha(read(REPO/target['path']))==target['afterSha256Bytes']==pins[target['path']]
put('root/snapshot76/manifest.json',raw)
cp=read(S/'ordered-runtime-cp.json');assert sha(cp)=='b8f76524bb39b8d09e50ea8532cd8eae6ee94c569a2e8140d650b86b43f11507'
for row in json.loads(cp):assert sha(read(row['path']))==row['sha256Bytes']
put('root/snapshot76/ordered-runtime-cp.json',cp)
for name in ['classes-76.log','classpath76.log']:
    b=read(REPO/'desktop/.local/stable-build-repair'/name);assert b'BUILD SUCCESSFUL' in b and b'BUILD FAILED' not in b
    put('root/'+name,b)
audit=read(H/'jvm-method-name-audit-76.json');a=json.loads(audit);assert a['classCount']==15347 and a['issueCount']==0
put('root/jvm-method-name-audit-76.json',audit)
review=read(MAIN/'desktop/.local/stable-original-settings-io-review/receipt.json')
assert sha(review)=='258d28e7eb3a8b942890bf27b99ff234d78c1ff42e267ea22188d9953a3c7419'
assert json.loads(review)['blockingFindings']==[]
put('root/independent-source-review.json',review)
for name in ['install-original-music-storage76.py','freeze-settings-music-prepared76.py','freeze-original-music-storage76.py']:
    put('root/'+name,read(H/name))
generated=[]
for name in ['DesktopOriginalAudioHistoryStore','DesktopOriginalLocalPlaylistStore']:
    relative='com/android/purebilibili/core/store/'+name+'.kt'
    prepared=read(MAIN/'desktop/.local/stable-original-music-storage-parity/generated'/relative)
    actual=read(REPO/'desktop/build/generated/music-player-full'/relative)
    assert actual.replace(b'\r\n',b'\n')==prepared
    generated.append(dict(originalObjectOnlyRename=True,actualGeneratedPath='desktop/build/generated/music-player-full/'+relative,
        preparedSHA256LF=sha(prepared),actualSHA256LF=sha(actual.replace(b'\r\n',b'\n')),sameByteBody=True))
put('root/sole-generated-object-audit.json',(json.dumps(generated,indent=2)+'\n').encode())
PROOF=MAIN/'desktop/.local/stable-original-music-storage-actual76-proof'
proof=json.loads(read(PROOF/'runs/01/result.json'))
assert proof['passed'] and proof['assertions']==52 and proof['productionOverrides']==0 and proof['uniqueInstalledProductClassOrigins']==9
assert proof['pinsUnchanged'] and json.loads(read(PROOF/'runs/01/overlap.json'))['productClassOverlap']==[]
excluded=[]
for p in sorted(wide(PROOF).rglob('*')):
    if not p.is_file():continue
    path=p.relative_to(wide(PROOF)).as_posix();b=p.read_bytes()
    if p.suffix in ['.jar','.class','.pyc']:
        excluded.append(dict(path=path,sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable fixture output; not production payload.'))
    else:put('root/installed-proof/'+path,b)
put('root/installed-proof/excluded-artifacts.json',(json.dumps(excluded,indent=2)+'\n').encode())
report=dict(phase=76,preparedPackets=[dict(raw=21,sha256='0c9daf306710df68f2af52aae9bf75c1b21b217fdd2b70b780e2fc2a585e0b83'),
    dict(raw=80,sha256='f3a99438b304983ca7dd9806c507baeecd0baf292a63d9bd573c0807c71fece1')],
    newManual=1,existingFamilies=3,exactHunks=5,newSourceIdentities=0,sourceIdentityCount=1170,resourceCount=213,
    registryUnchanged=True,newDependencies=0,newGlobalStoreOrActor=False,wholeWindowsClassesPassed=True,kotlinClasses=15347,
    illegalJvmMethodNames=0,runtimeEntries=101,installedAssertions=52,productionOverrides=0,uniqueInstalledProductClassOrigins=9,
    fullOriginalHistoryAndLocalPlaylistObjects=True,canonicalModelsRemainSoleOwners=True,
    diskAndBackingOutsideRootAdmission=True,acceptedGlobalCommitMayFinishAfterEntryRetires=True,
    suspendDataStoreCallerCancelledBeforePermitRejected=True,standaloneMirrorCallerCancellationClaimed=False,
    synchronousMirrorApplyRemainsSynchronous=True,completeRootMounted=False,nativeWindowOrAccountAccepted=False,desktopExeReplaced=False)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
assert len({r['path'] for r in rows})==len(rows)
assert {p.relative_to(wide(OUT)).as_posix() for p in wide(OUT).rglob('*') if p.is_file()}=={r['path'] for r in rows}
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Original audio history and local playlists now use their complete fixed-v0.2.3 object bodies with only object identifiers and consumed Context/key imports adapted. Existing canonical Serializable models remain their sole producers. The existing music producer adds two object outputs, and one required Root binding supplies the original history and save-playlist ports on the same settings context.

The existing PluginStore gains a generic read-dependent CAS operation over its existing backing. Pure edits, staging, fsync, backing reads and final replacement run outside Root admission. Admission only mints a typed one-use permit for the actual Store; conflicts discard the permit and staging file before ownership is checked again. A minted global settings edit may finish after an entry retires, while global Store freeze still rejects final replacement. Synchronous mirror apply remains synchronous and does not claim generic coroutine-cancellation admission.

Installation is one new manual source and five exact hunks in three existing families; the original registry remains 1170 sources and 213 resources with no dependency changes. Whole desktop classes pass in 16 seconds. The immutable actual76 snapshot has 101 runtime entries and 15347 Kotlin classes with no illegal JVM method names.

Two exact frozen fixtures compile against that installed snapshot with zero product overrides. All 52 real disk/Store checks pass, including concurrent original history counts, original keys and schemas, last-session milliseconds, local playlist replacement/add/delete, cold generation reads, cancellation and entry retirement, Root/backing lock separation, CAS cleanup, permit identity/one-use and global freeze. Nine exercised production types have unique actual76 application-JAR origins; all pinned runtime/compiler/input bytes remain unchanged. The independent review is source-only and does not claim test execution by the reviewer.

This slice has not mounted the complete player/Holder/Music Root into Main, accepted native/window/account behavior, or replaced the desktop EXE. Prepared generated sources are provenance and compilation evidence, never extra production source owners. Rebuildable fixture outputs and task-private temporary Store data are excluded from installation.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256=sha(manifest),installedAssertions=52,productionOverrides=0)))
