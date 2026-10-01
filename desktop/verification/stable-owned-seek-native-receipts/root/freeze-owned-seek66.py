from pathlib import Path
import hashlib,json,subprocess,difflib

HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-owned-seek-native-receipts';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists(),name
    p.write_bytes(b);rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def register(name,b):
    if Path(name).suffix.lower()in('.jar','.class','.pyc','.dll','.mp4','.png'):
        excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable fixture binary'))
    else:put(name,b)

snapshot=MAIN/'desktop/.local/stable-product-snapshot-66'
assert sha(read(snapshot/'manifest.json'))=='e549c7badf5b11208e8b9fb3c2d5480759e9202eb157ae165feb0a8354cbcba3'
assert sha(read(snapshot/'ordered-runtime-cp.json'))=='43b1e432d592dd74088079e60731eda25d26f6c636df3570644a377b200ba9db'
meta=json.loads(read(snapshot/'manifest.json'));assert meta['runtimeEntries']==101 and meta['sourceRegistryCount']==1115
input_pins={r['path']:r['sha256Bytes']for r in meta['inputs']}
baseline='aa0f05d8a9911c93df50ccb499cd6307951d0471'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()==baseline
changed=[
    'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMpvOverlayControl.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMpvVideoPlayerState.kt',
]
source_rows=[]
for relative in changed:
    before=subprocess.check_output(['git','show',baseline+':'+relative],cwd=REPO)
    after=read(REPO/relative);assert sha(after)==input_pins[relative]
    put('root/source-before/'+relative,before);put('root/source-after/'+relative,after)
    diff=''.join(difflib.unified_diff(before.decode().splitlines(True),after.decode().replace('\r\n','\n').splitlines(True),fromfile=baseline+':'+relative,tofile='actual66:'+relative))
    put('root/source-diffs/'+Path(relative).name+'.diff',diff.encode())
    source_rows.append(dict(path=relative,beforeCommit=baseline,beforeSha256LF=sha(before),afterSha256Bytes=sha(after)))
put('root/source-delta.json',(json.dumps(source_rows,indent=2)+'\n').encode())
for folder in ('owned-seek66','owned-seek66-02','actual66-owned-seek-fixture01','actual66-owned-seek-fixture02'):
    for p in sorted(wide(HERE/folder).rglob('*'),key=str):
        if p.is_file():register('root/'+folder+'/'+p.relative_to(wide(HERE/folder)).as_posix(),p.read_bytes())
for n in ('manifest.json','ordered-runtime-cp.json'):put('root/snapshot66/'+n,read(snapshot/n))
for n in ('classes-66.log','classpath-66.log'):put('root/'+n,read(REPO/'desktop/.local/stable-build-repair'/n))
for n in ('create-owned-seek-runner66.py','repair-owned-seek-fixture66.py','run-actual66-owned-seek-fixture.py','run-actual66-owned-seek-fixture02.py','freeze-owned-seek66.py','jvm-method-name-audit-66.json'):
    put('root/'+n,read(HERE/n))
audit=json.loads(read(HERE/'jvm-method-name-audit-66.json'));assert audit['issueCount']==0 and audit['classCount']==14331
runner=json.loads(read(HERE/'actual66-owned-seek-fixture02/runner-result.json'))
proof=json.loads(read(HERE/'actual66-owned-seek-fixture02/result.json'))
assert runner['passed']and runner['productionOverrides']==0 and len(runner['byteVerifiedProductOrigins'])==4
assert proof['passed']and proof['assertions']==67 and len(proof['checks'])==67
assert not wide(HERE/'actual66-owned-seek-fixture01/result.json').exists()
report=dict(phase=66,sourceIdentityCount=1115,resourceCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClassCount=14331,illegalJvmMethodNames=0,
    sourceDelta=source_rows,requiredSourceOwnedSeekVersionCheckedAtomicallyAtEnqueue=True,
    ownedSeekExecutionChecksSourceVersionRevisionPublicationAndActualAdmission=True,
    queueSubmissionHasExactImmutableTicket=True,queuedIsNotNativeCompletion=True,
    existingSectionListenerScopeUsed=True,newPollersOrActors=0,existingControllerSeekSemanticsPreserved=True,
    actualNativeFixtureAssertions=67,installedProductFixture=runner,
    nativeTestUsesLocalClipAndTemporaryActualStore=True,fixtureGateBeforeAdmissionLocks=True,
    firstAttemptFixtureCompileFailed=True,firstAttemptNativeNotExecuted=True,productChangedForFixtureRepair=False,
    sponsorHistoryAndConsentBridgeStillPending=True,fullOriginalVmAndHolderNotMounted=True,
    lastActualMainStartupPhase=57,actualMainRerun=False,realAccountUsed=False,newExeDeployed=False,
    independentVisualAcceptance=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Carry original player seeks through native completion receipts
============================================================

The existing original Overlay/Section control now queues an atomic expected-version tracked seek and returns an immutable sourceVersion/operationId/targetPositionMs ticket. Its existing registered Section listener receives the exact ticket synchronously on the caller lane, with active registration/Job, entry and source admission. No extra player, observer scope, poller or actor is created. At native execution, owned seeks re-enter actual publication admission and check the requested/active source version, revision, session and captured publication identity. Retired or foreign commands produce no seek completion. The legacy Controller command path keeps its existing queued-command semantics, including pre-retirement commands. A queued ticket is only admission; actual PlayerState.seekCompletedId/position remains the native success evidence.

Actual66 whole classes and test sources compile. The static JVM audit finds zero illegal names among14331 Kotlin classes, with1115 original source identities,213 resources and101 runtime entries. The local MPV fixture passes67 checks using zero product overrides and four byte-verified loaded product classes, with all source/compiler/runtime/native/clip pins before and after. It uses the actual temporary Repository/Store receipt and short entry admission; only its native admission hook is held before any admission/native lock to test delayed execution. Exact caller-local capture, actual native playback restart, removed listeners, normalized targets, retired entry/account, same-version foreign publication, foreign version and recovery revision are checked. Its windows and native cores close normally. The first fixture attempt failed to compile because its two API call forms were incorrect; that failure is preserved and the second source uses the real signatures. Product source was unchanged for fixture repair.

The complete original VM must capture this ticket in its lexical seek operation and pass it explicitly with the intended Sponsor record. The Root bridge still must await that exact native completion and current source/Runtime generation/consent before history or viewed-segment publication. Neither history publication nor the full ordinary VM/Holder/Root facade transition is accepted here. No Main startup, account/HTTP, OS input, independent visual acceptance or EXE replacement is claimed. Source inventory remains separate from functional completion and source reuse.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
