from pathlib import Path
import difflib, hashlib, json, subprocess, zipfile

HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PREPARED=MAIN/'desktop/.local/stable-runtime-plugin-dispatch-actual68-proof'
OUT=REPO/'desktop/verification/stable-runtime-plugin-dispatch';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists(),name
    p.write_bytes(b);rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))

baseline='78a1d1a405d415d01dbbd00e69e32f5b29e496aa'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()==baseline
proof_raw=read(PREPARED/'frozen-proof.json')
assert sha(proof_raw)=='a9577b92c9dcddca14ee7a5cd63f54e90a52beee8c610f89aea219008b3e705e'
proof=json.loads(proof_raw);assert proof['passed'] and proof['assertions']==33 and proof['productionOverrides']==0
assert proof['rawArtifactCount']==len(proof['artifacts'])==18 and proof['actualLoadedProductClasses']==4
for row in proof['artifacts']:
    content=read(PREPARED/row['path']);assert sha(content)==row['sha256Bytes'],row['path']
    assert Path(row['path']).suffix.lower() not in ('.jar','.class','.dll','.pyc')
    put('prepared/'+row['path'],content)
put('prepared/frozen-proof.json',proof_raw)
result=json.loads(read(PREPARED/'runs/01/result.json'))
assert result['passed'] and result['assertions']==33 and result['actualRuntimeShutdownJoined']
assert not result['httpOrAccountsOrGuiOrNativeRun'] and result['actualCodeOriginsVerified']==4
assert read(PREPARED/'runs/01/pins-before.json')==read(PREPARED/'runs/01/pins-after.json')
snapshot=MAIN/'desktop/.local/stable-product-snapshot-68'
assert sha(read(snapshot/'manifest.json'))=='605583c59ae5975391dc4f705834ab927cfef84d0ac6c3ac40b9844284dfabdd'
assert sha(read(snapshot/'ordered-runtime-cp.json'))=='14eeaec07e050b1fe181cfe82fb199b2f5c481d959582407412b702932fbcec9'
meta=json.loads(read(snapshot/'manifest.json'));assert meta['runtimeEntries']==101 and meta['sourceRegistryCount']==1115 and meta['resourceCount']==213
cp=json.loads(read(snapshot/'ordered-runtime-cp.json'));assert len(cp)==101
for row in cp:assert sha(read(row['path']))==row['sha256Bytes']
origins=json.loads(read(PREPARED/'runs/01/actual-code-origins.json'))
assert origins['passed'] and len(origins['loaded'])==4 and origins['productionOverrides']==0
runtime_paths={row['path'] for row in cp}
for origin in origins['loaded']:
    assert origin['codeSource'] in runtime_paths
    with zipfile.ZipFile(wide(origin['codeSource'])) as archive:
        assert sha(archive.read(origin['entry']))==origin['classSHA256Bytes']
relative='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt'
before=subprocess.check_output(['git','show',baseline+':'+relative],cwd=REPO);after=read(REPO/relative)
assert sha(after)=={row['path']:row['sha256Bytes']for row in meta['inputs']}[relative]
assert after==read(PREPARED/'source-review/DesktopPluginRuntime.kt')
put('root/source-before/'+relative,before);put('root/source-after/'+relative,after)
diff=''.join(difflib.unified_diff(before.decode().splitlines(True),after.decode().replace('\r\n','\n').splitlines(True),
    fromfile=baseline+':'+relative,tofile='actual68:'+relative))
put('root/source-diffs/DesktopPluginRuntime.kt.diff',diff.encode())
for name in ('manifest.json','ordered-runtime-cp.json'):put('root/snapshot68/'+name,read(snapshot/name))
for name in ('classes-68.log','classpath-68.log'):put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
for name in ('freeze-runtime-dispatch68.py','jvm-method-name-audit-68.json'):put('root/'+name,read(HERE/name))
audit=json.loads(read(HERE/'jvm-method-name-audit-68.json'));assert audit['issueCount']==0 and audit['classCount']==14335
report=dict(phase=68,sourceIdentityCount=1115,resourceCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClassCount=14335,illegalJvmMethodNames=0,
    sourceDelta=dict(path=relative,beforeCommit=baseline,beforeSha256LF=sha(before),afterSha256Bytes=sha(after)),
    capturedExistingRuntimeVideoIdentityGenerationAndProviders=True,samePlayerMutexUsed=True,
    synchronousMutationUsesRequiredShortAdmission=True,suspendingCallbackIoOutsideShortAdmission=True,
    callerCancellationAndCurrentDispatchCheckedBeforeAndAfter=True,
    retirementUsesExistingRuntimeScopeAndExactOldGeneration=True,newPollersOrActors=0,
    actualMemoryFixtureAssertions=33,byteVerifiedProductClasses=4,productionOverrides=0,
    actualRuntimeShutdownJoined=True,memoryNativeLeaseAndProviderAreExplicitTestDoubles=True,
    secondRuntimeOnlyForeignTokenFixture=True,secondProductionRuntimeAuthorityAccepted=False,
    delayedCdnWriteAndSponsorHistoryFinalWriteGuardStillPending=True,
    fullOriginalVmHolderRootMountAndFacadeSwitchAccepted=False,nativeRerun=False,httpUsed=False,
    realAccountUsed=False,newExeDeployed=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=proof['excluded']),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Serialize captured original playback plugin callbacks
=====================================================

Runtime now captures a view of its existing CurrentVideo identity, generation, provider identities and required Root source admission. It creates no new generation authority, player, store, scope, actor or poller. Waiting uses the same existing playerMutex outside short Store/entry/native admission. Synchronous provider mutation executes in that admission; suspending original callbacks run outside it, with caller cancellation, captured ownership and enabled-provider checks before and after. Retiring an old dispatch captures its provider identities and queues cleanup on the existing Runtime scope. A later load increments generation before waiting, so old cleanup cannot reset its provider state. The sole original Sponsor provider is exposed internally for the full owner bridge; disabled cleanup only permits that same instance. Legacy callback APIs remain unchanged.

Actual68 whole classes and test sources compile, with14335 Kotlin classes and zero illegal JVM method names. All1115 original identities,213 resources and101 runtime entries remain fixed. An independent actual-product memory fixture passes33 checks with four byte-verified loaded classes and zero product overrides. Runtime/Manager/Store are the actual product; MemoryNativeLease and delayed/counter PlayerPlugin are explicit fixture doubles. All original production PlayerPlugins including Sponsor are disabled, and no HTTP/account/GUI/native operation is performed. Wrong subjects/generations/runtime/provider, declined admission, waiting cancellation/entry retirement, disabled provider and changed generation reject mutation or result. The fixture checks synchronous mutation inside admission, suspending work outside, exact old retirement on the real Runtime scope, refusal of duplicate retirement, cleanup after entry death, and old cleanup not resetting a later load. Both actual shutdownForRestore calls join and freeze the shared temporary Store. The second Runtime exists only to test foreign-token refusal in that temporary global Manager environment; it is not support for another production authority. The real compiled JS catalog and every existing worker resource, and all runtime/compiler/source pins, are verified before and after.

Root has not yet mounted the full original VM/Holder or switched all controller clients. Original CDN delayed writes, original Sponsor history final-write admission and binding these captured predicates to NativeOwner remain pending. This seam does not undo already admitted in-flight side effects. Native source admission itself was verified separately in phase67; no new native acceptance, Main startup, real account/network, independent visuals, packaging or EXE deployment is claimed here. Inventory counts are not functional completion or source reuse percentages.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSha256Bytes=sha(manifest),preparedArtifactsVerified=18,productOriginsVerified=4)))
