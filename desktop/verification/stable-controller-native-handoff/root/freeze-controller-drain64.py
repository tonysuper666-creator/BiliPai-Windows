from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-controller-native-handoff';assert not OUT.exists()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[];excluded=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
def register(name,b):
 if Path(name).suffix.lower() in ('.class','.jar','.kotlin_module','.pyc','.png','.jpg','.mp4','.dll'):
  excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable binary or private rendered output'))
 else:put(name,b)
for item in json.loads(read(HERE/'controller-drain64/source-delta.json'))['sources']:
 assert sha(read(REPO/item['sourcePath']).replace(b'\r\n',b'\n'))==item['candidateLFsha256']
for phase in (62,63,64):
 for folder in (HERE/f'controller-drain{phase}',HERE/f'actual{phase}-controller-native-drain-fixture01'):
  for p in sorted(wide(folder).rglob('*'),key=str):
   if p.is_file():register('root/'+folder.name+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
 snapshot=MAIN/f'desktop/.local/stable-product-snapshot-{phase}'
 for name in ('manifest.json','ordered-runtime-cp.json'):put(f'root/snapshot{phase}/'+name,read(snapshot/name))
 for name in (f'classes-{phase}.log',f'classpath-{phase}.log'):put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
 for name in (f'record-controller-drain{phase}.py',f'create-controller-drain-runner{phase}.py',f'run-actual{phase}-controller-native-drain-fixture.py',f'jvm-method-name-audit-{phase}.json'):
  put('root/'+name,read(HERE/name))
put('root/freeze-controller-drain64.py',read(HERE/'freeze-controller-drain64.py'))
snapshot=MAIN/'desktop/.local/stable-product-snapshot-64'
assert sha(read(snapshot/'manifest.json'))=='5f93c921297a6571ef6074f6b3afb364ff1c20ca09a8f3e17db15133dde2bf42'
assert sha(read(snapshot/'ordered-runtime-cp.json'))=='58d593ab155d1a864b3213cc35e7c633fae7b7f6f55b8b8c22f49fcc71613701'
proof=json.loads(read(HERE/'actual64-controller-native-drain-fixture01/result.json'))
runner=json.loads(read(HERE/'actual64-controller-native-drain-fixture01/runner-result.json'))
assert proof['passed'] and proof['assertions']==72 and len(proof['checks'])==72
assert runner['passed'] and runner['productionOverrides']==0 and len(runner['byteVerifiedProductOrigins'])==4
audit=json.loads(read(HERE/'jvm-method-name-audit-64.json'));assert audit['issueCount']==0 and audit['classCount']==13697
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));assert len(registry['sources'])==1064 and len(registry['resources'])==213
report=dict(phase=64,sourceIdentityCount=1064,resourceRegistryCount=213,runtimeEntries=101,
 wholeClassesPassed=True,wholeTestSourcesCompiled=True,illegalJvmMethodNames=0,
 controllerDrainApiInstalled=True,retirementIsTerminalOnFailureOrCancellation=True,
 allOldProducerJoinsAndNativeBarrierOutsideAdmissionLocks=True,repeatableRetiredProducerJoinInstalled=True,
 retainedQueueRawMetadataClockPauseSpeedExternalSubtitlesAndCanvas=True,
 originalSponsorRuntimeNotEndedOrReloadedByDrain=True,
 typedInheritedNativeMuteInterval=True,restorationReadsCurrentRootUserMutePreference=True,
 inheritedNativeMuteRestoresOnActualEof=True,newLoadExplicitlyAppliesBaseMute=True,
 userMuteBeforeFirstAttachUpdatesPendingLoadIntent=True,
 retainedCommandAdmissionUsesStoreEntryNativeLockOrder=True,
 ownedMuteChecksNativeSourceVersionRevisionAndPublicationIdentity=True,
 oldOwnerCloseCannotStopNewNativeOwner=True,actualNativeFixtureAssertions=72,installedProductFixture=runner,
 historical62FixtureFailedOnIncorrectPreSelectionObjectIdentity=True,historical63Assertions=60,
 historicalDraftsWereReviewedAndSuperseded=True,newDependencies=0,newPlayers=0,
 memoryTransportInjection=True,actualTemporaryStoreReceiptAdmission=True,syntheticPrivateMuteIntervalSetup=True,
 localNativeClipOnly=True,realAccountUsed=False,httpExecuted=False,
 fullControllerFacadeSwitchAccepted=False,fullOriginalVmHolderMounted=False,
 actualMainRerun=False,lastActualMainStartupPhase=57,newExeDeployed=False,
 independentVisualAcceptance=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''Retain the real native source while retiring the old Controller
==============================================================

The Root-only drain API runs on the existing UI request lane. It verifies the exact account receipt, ready source version, raw BVID/CID and selected queue before retiring admission. It saves the actual checkpoint/final heartbeat, cancels and joins the whole old Controller child tree plus its existing independent heartbeat and subtitle producers, then drains the same native actor outside all admission locks. It never invokes old close/invalidate, stop, pause, seek, native reload, subtitle asset deletion or a plugin video-end/load lifecycle. The immutable handoff carries the actual raw detail and selected resolved source, queue/index/token/shuffle, same native snapshot/readback, runtime generation, suspension and typed legacy native-mute interval. Successful adoption changes only the native publication. Timeout, cancellation, retired account or foreign source yields no handoff and cannot resurrect the old owner. Terminal cleanup clears retired references. Root can retry awaitRetiredOrdinaryProducers; false prohibits constructing another equivalent producer.

The existing native owner retains that mute interval across accepted recovery, with no new poller. Its original full-owner position observer must call observeInheritedPluginMute. Restoration reads the required current Root user mute preference rather than the handoff's historical value, and waits for actual native readback before consuming the interval. EOF restores even when it lies inside the interval. A real new publication applies the current base mute in the same admitted native load command. Pending user writes before first Canvas attach update that one intent; replay/adoption/recovery keep their existing source behavior. Retained queued commands re-enter Store -> entry -> native admission, close retires under the actual entry gate, and mute commands verify actual version/revision/publication identity at execution.

Actual64 whole classes and test sources pass with1064 original identities,213 resources,101 runtime entries and13697 Kotlin classes; JVM illegal method names0. The local native API fixture passes72 checks using only actual64 artifacts, zero product overrides and four loaded class byte hashes matched to the product JARs. It renders the pinned local clip through the existing MPV, uses actual temporary Repository/SessionStore receipt admission, and injects only memory metadata/heartbeat/subtitle IO. The old child is deliberately held/released to prove join behavior; the old mute interval is seeded in its private bookkeeping while native mute/readback is real. It checks queue/raw metadata, checkpoint/final heartbeat, scope completion, no-reload clock/pause/speed/subtitle/Canvas preservation, old-owner rejection, current mute preference, new-load mute reset, EOF restoration, user writes before first attach, timeout, account retirement and foreign replacement. Its own windows/player close normally. No real account, HTTP, OS input or independent visual acceptance is claimed.

Draft62's fixture incorrectly compared the injected pre-selection source object with the Controller's real selected copy; it failed and is preserved. Draft63's60 checks passed but read-only review found EOF/pending-mute omissions; its evidence is preserved as superseded, rather than reused as final acceptance. The final fixture compares the actual Current.source identity and adds those two regressions. This is an API handoff acceptance. The complete original VM/Holder and simultaneous Favorite/Listen/dashboard facade transition are still required before production Root mounting. These inactive APIs do not alter the current Root route and no EXE is replaced. Source inventory does not measure functional completion or reuse.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(raw))))
