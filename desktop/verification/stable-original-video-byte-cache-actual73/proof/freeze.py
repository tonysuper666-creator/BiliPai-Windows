from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
H=Path(__file__).resolve().parent;MAIN=H.parents[2]
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def write(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(v.encode()if isinstance(v,str)else v)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def row(p):return dict(path=Path(p).relative_to(H).as_posix(),sha256Bytes=sha(p),size=len(data(p)))
assert not (H/'frozen-handoff.json').exists()
one=H/'runs/actual73-01';two=H/'runs/actual73-02'
a=json.loads(data(one/'receipt.json'));b=json.loads(data(two/'receipt.json'))
assert a['compilePASS'] and a['runtimeExit']==1 and b['compilePASS'] and b['runtimeExit']==0
for r in [a,b]:assert r['productionOverrides']==0 and r['entries']==101 and r['snapshot']==73 and r['pinsUnchanged'] and r['nativeUnchanged']
assert data(H/'InstalledCacheConsumerProof.kt')==data(two/'inputs/InstalledCacheConsumerProof.kt')
for out in [one,two]:
 assert json.loads(data(out/'pins-before.json'))==json.loads(data(out/'pins-after.json'))
 assert json.loads(data(out/'overlap.json'))['classOverlap']==[]
 for origins in json.loads(data(out/'class-origins.json')).values():assert len(origins)==1 and origins[0]['jar']['sha256Bytes']=='35d74192fc4906068b9dc750d2906a6e423df7ff005011e6a2b052c29971d16e'
acceptance=dict(snapshot=73,entries=101,productionOverrides=0,closedRuns=['actual73-01','actual73-02'],successfulRun='actual73-02',groups=5,assertions=30,
 actualCachedFactoryLexicalIntentAndNativePublish=True,normalResolverCompleted=True,actualNativePauseResume=True,
 exactSameBoundAndCarrierReuse=True,fullMpdAllRepresentationsAndRanges=True,actualNativeFailureAttempt=True,
 actualFailureScenario='fixture-owned invalid media bytes, valid206/Content-Range/ETag; actual demux failure',
 actualDirectRemoteAck=True,actualSourceVersionPreserved=True,actualReaderRecoveryPositionMs=1375,
 actualReaderDesiredPause=True,actualErrorTimeNativePaused=None,actualRecoveredNativePaused=True,
 actualRecoveredNativePositionSeconds=1.4,oldCapabilities410=True,wrongAttemptAndOldIdentityRejected=True,noCacheRetryRegistration=True,
 pinsAndNativeDllUnchanged=True,
 retainedFailure={'run':'actual73-01','priorAssertionsPassed':17,'scenario':'fixture-owned HTTP503 after metadata success',
  'result':'real NanoHTTPD range IO exception and20s timeout awaiting actual NativeFailure.attemptId; no recovery acceptance',
  'noFakeStateAttemptOrUri':True},
 notAccepted=['HTTP503/network-buffer recovery','MainShell fullVM factory/page mount','real account or CDN','physical HWND/OS inputs'])
dump(H/'acceptance.json',acceptance)
contract='''Actual73 installed byte-cache consumer acceptance; fixture-only, zero production overrides

Both runs use immutable73/101: manifest4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829, CPf71557c1952176118a9eba16479da2e244cacfb6cc419a65f43dc5501f822d31, Kotlin35d74192fc4906068b9dc750d2906a6e423df7ff005011e6a2b052c29971d16e. Class origins are unique product KotlinJar; only new test classes precede CP, overlap0. Existing libmpv673e6397...3a4 and101jar byte hashes remain identical before/after. Candidate and source-only260 were never modified; historical70/21native not replayed.

actual73-01: initial CachedMediaFactory -> original lexical1250ms/paused intent -> actual NativeOwner.publish/ACK, native reader pause/resume, normal resolver completion, real acceptedMedia same Bound/carrier, full completeMPD2video+1audio/all ranges reuse and warmed no-second-body all pass(17 checks). Local503 body failure produces a real NanoHTTPD range IO exception but does not produce actual PlayerState.failure.attemptId within20s. This run fails honestly. It does not prove a recoverable native attempt for HTTP503 or network buffering; original earlier observations are retained, not a fabricated failure-state snapshot.

actual73-02: only fixture origin fault injection changes to valid206/Content-Range/validator carrying all-zero owned media. Real MPV demux produces actual failure attempt5. Error-time PlayerState.position1.375s and requestedPause=true(nativePaused unavailable/null) feed recovery; no old source position/default is guessed. Wrong attempt is rejected. New real direct helper uses same owned source, remote semantic URI, exact attempt/receipt and nativeVersion, removes failed carrier, invokes existing MPV recoverSource. Actual native command/frames/codecs/pause readback return; position1.4s is within one fixture frame of requested1.375s, nativePaused=true. Original Source is stamped exact1.375s/paused, sourceVersion3 unchanged, oldlocalcap410, registrations1->0, old source/attempt and already-direct source cannot retry. FullMPD and samecarrier checks remain.30checks/5groups pass. A successful queued return alone is not counted as decode/ACK.

No PlayerState/attempt/sourceVersion setters, reflection, URI projection, new production client/store/actor, hardware mouse, external HTTP, user files or account. SourceJobs are fixture-owned version lifetime Jobs, independent of normally completed resolver. All native frame backing is from the existing MpvSoftwareTarget; no window/screenshot/OS input. Native-error delivery is via actual demux of fixture bytes. Root still must add a real guarded cache-error->Root recovery observer chain for stream IO that stalls without a NativeFailure; this packet does not claim that path solved. No MainShell/fullVM factory/page runtime acceptance.
'''
write(H/'contract.md',contract)
raw=[];excluded=[]
for p in sorted(wide(H).rglob('*')):
 if not p.is_file():continue
 rel=str(p)[len(str(wide(H)))+1:].replace('\\','/');normal=H/rel
 if rel=='frozen-handoff.json':continue
 value=dict(path=rel,sha256Bytes=sha(normal),size=len(data(normal)))
 reason=None
 if '/classes/'in rel or rel.endswith('.jar')or rel.endswith('.kotlin_module'):reason='rebuildable fixture compiler output; exact inputs and commands retained'
 elif '/owned-data/'in rel:reason='fixture-owned ephemeral Store/cache bytes; source media/reference hashes retained'
 if reason:value['reason']=reason;excluded.append(value)
 else:raw.append(value)
dump(H/'frozen-handoff.json',dict(schema=1,kind='actual73-installed-byte-cache-consumer-native-proof',snapshot=73,entries=101,
 productionOverrides=0,rawArtifactCount=len(raw),artifacts=raw,excluded=excluded,acceptance=row(H/'acceptance.json'),
 groups=5,assertions=30,HTTP503RecoveryAccepted=False,invalidCachedMediaDirectRecoveryAccepted=True,MainShellAccepted=False))
print('Frozen',len(raw),'raw; actual73/101 zerooverride;30nativechecks; HTTP503 still unaccepted.')
print(sha(H/'frozen-handoff.json'))
