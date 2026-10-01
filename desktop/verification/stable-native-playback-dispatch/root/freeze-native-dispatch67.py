from pathlib import Path
import difflib, hashlib, json, subprocess

HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-native-playback-dispatch';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists(),name
    p.write_bytes(b);rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def register(name,b):
    if Path(name).suffix.lower() in ('.jar','.class','.pyc','.dll','.mp4','.png'):
        excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable fixture binary'))
    else:put(name,b)

baseline='8b8f1a4f02da6ac4a923b597213a6c2316c3e213'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()==baseline
snapshot=MAIN/'desktop/.local/stable-product-snapshot-67'
assert sha(read(snapshot/'manifest.json'))=='db10acafc95ee255a5c921f74d4646ace585998d67644cde53b53aac7cf54ef6'
assert sha(read(snapshot/'ordered-runtime-cp.json'))=='25f8c144a4a8aeb5230beb25a927f294567abf019c6249173aec9967195e4807'
meta=json.loads(read(snapshot/'manifest.json'));assert meta['runtimeEntries']==101 and meta['sourceRegistryCount']==1115
assert meta['resourceCount']==213
input_pins={row['path']:row['sha256Bytes']for row in meta['inputs']}
changed=[
    'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt',
]
source_rows=[]
for relative in changed:
    before=subprocess.check_output(['git','show',baseline+':'+relative],cwd=REPO)
    after=read(REPO/relative);assert sha(after)==input_pins[relative]
    put('root/source-before/'+relative,before);put('root/source-after/'+relative,after)
    diff=''.join(difflib.unified_diff(before.decode().splitlines(True),after.decode().replace('\r\n','\n').splitlines(True),
        fromfile=baseline+':'+relative,tofile='actual67:'+relative))
    put('root/source-diffs/'+Path(relative).name+'.diff',diff.encode())
    source_rows.append(dict(path=relative,beforeCommit=baseline,beforeSha256LF=sha(before),afterSha256Bytes=sha(after)))
put('root/source-delta.json',(json.dumps(source_rows,indent=2)+'\n').encode())
for folder in ('native-dispatch67','actual67-native-dispatch-fixture01'):
    for p in sorted(wide(HERE/folder).rglob('*'),key=str):
        if p.is_file():register('root/'+folder+'/'+p.relative_to(wide(HERE/folder)).as_posix(),p.read_bytes())
for name in ('manifest.json','ordered-runtime-cp.json'):put('root/snapshot67/'+name,read(snapshot/name))
for name in ('classes-67.log','classpath-67.log'):put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
for name in ('create-native-dispatch-runner67.py','run-actual67-native-dispatch-fixture01.py','freeze-native-dispatch67.py','jvm-method-name-audit-67.json'):
    put('root/'+name,read(HERE/name))

audit=json.loads(read(HERE/'jvm-method-name-audit-67.json'));assert audit['issueCount']==0 and audit['classCount']==14331
runner=json.loads(read(HERE/'actual67-native-dispatch-fixture01/runner-result.json'))
proof=json.loads(read(HERE/'actual67-native-dispatch-fixture01/result.json'))
assert runner['passed'] and runner['productionOverrides']==0 and len(runner['byteVerifiedProductOrigins'])==4
assert proof['passed'] and proof['assertions']==95 and len(proof['checks'])==95
assert not proof['sponsorHistoryWritten'] and not proof['fullOriginalOwnerMounted']
assert read(HERE/'actual67-native-dispatch-fixture01/pins-before.json')==read(HERE/'actual67-native-dispatch-fixture01/pins-after.json')
report=dict(phase=67,sourceIdentityCount=1115,resourceCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClassCount=14331,illegalJvmMethodNames=0,
    sourceDelta=source_rows,canonicalAcceptedObjectRequired=True,completeImmutableNativeSnapshotCompared=True,
    shortDispatchStoreEntryNativeAdmission=True,noIoWaitOrJoinInsideProductionDispatch=True,
    exactNativeSeekCompletionReadbackRequired=True,queuedTicketIsNotCompletion=True,
    actualNativeFixtureAssertions=95,installedProductFixture=runner,newPollersOrActors=0,
    currentRequestCanCompleteAfterActualLoadAck=True,validOwnedSameVersionRecoveryAccepted=True,
    entryAccountForeignPublicationForeignVersionChangedPayloadAndClosedOwnerRefused=True,
    samePublicationChangedPayloadCannotReuseAcceptedLease=True,
    runtimePluginDispatchAndSponsorHistoryBridgeStillPending=True,fullOriginalVmAndHolderNotMounted=True,
    lastActualMainStartupPhase=57,actualMainRerun=False,realAccountUsed=False,httpUsed=False,newExeDeployed=False,
    independentVisualAcceptance=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Admit captured original playback actions on their actual native source
====================================================================

NativeOwner now exposes isCurrent(expected), admitPlaybackDispatch(expected, action) and completedSeekPositionMs(expected, submission). The canonical accepted object, current account/entry admission and complete immutable native source must agree. Short synchronous dispatch enters the existing Store -> entry -> native locks; waiting, network, disk, shutdown and join remain outside. The complete source comparison also rejects same-version recovery payload changes even if the publication object was retained. A queue ticket is not success: completion requires that exact sourceVersion/operationId and the finite actual PlayerState.seekCompletedPositionSeconds under the same atomic admission. No target/time/latest-ticket heuristic, new actor, scope or poller is introduced.

Actual67 whole classes and test sources compile, with zero illegal JVM method names among14331 Kotlin classes. The fixed product contains1115 original source identities,213 resources and101 ordered runtime entries. One local MPV fixture passes95 checks with four byte-verified product class origins and zero product overrides; all compiler/source/runtime/native/clip pins match before and after. It uses the actual temporary Repository/SessionStore receipt and existing original controls/listener. A fixture-only hook delays a selected publication call before any admission lock. The checks cover queue-before-completion, exact native IDs, canonical identity, declined entry admission, cancellation, ordinary failure propagation, superseding seeks, and actual same-version accepted recovery at its own clock. Delayed dispatch is rejected after entry/account retirement, same-version foreign publication, foreign version, unaccepted same-publication payload recovery, and owner close. The payload misuse case proves refusal, not successful recovery. Valid recovery is separately performed through NativeOwner's real acceptedMedia path. All seven owned windows/native players close normally.

The full original VM/Holder and atomic Root facade transition remain unmounted. Runtime plugin serialization and guarded original Sponsor history/ping publication are still pending. No actual Main rerun, real account or HTTP, OS input, independent visual acceptance, Sponsor history write, package or EXE replacement is claimed. Inventory counts are separate from functional completion and code reuse.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
