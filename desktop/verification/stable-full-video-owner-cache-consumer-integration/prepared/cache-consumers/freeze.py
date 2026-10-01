from pathlib import Path
import hashlib, json, importlib.util, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];ROOT=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b.encode()if isinstance(b,str)else b).hexdigest()
def text(p):return data(p).decode().replace('\r\n','\n')
def write(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(v.encode()if isinstance(v,str)else v)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def row(p):return dict(path=Path(p).relative_to(H).as_posix(),sha256Bytes=sha(data(p)),size=len(data(p)))
def external(p):return dict(path=str(Path(p).absolute()),sha256Bytes=sha(data(p)),size=len(data(p)))
assert not (H/'frozen-handoff.json').exists()
exact=json.loads(data(H/'exact-hunks.json'));assert len(exact['hunks'])==12
paths=list(dict.fromkeys(r['path']for r in exact['hunks']));assert len(paths)==8
families=[]
for p in paths:
 old=text(H/'baseline'/p);new=text(H/'prepared/existing'/p)
 assert text(ROOT/p)==old,('Candidate family drift',p)
 write(H/'baseline-bytes'/p,data(ROOT/p))
 forward=old
 for r in [r for r in exact['hunks']if r['path']==p]:
  assert forward.count(r['before'])==1;assert sha(r['before'])==r['beforeSha256LF'];forward=forward.replace(r['before'],r['after'],1)
 assert forward==new
 reverse=new
 for r in reversed([r for r in exact['hunks']if r['path']==p]):
  assert reverse.count(r['after'])==1;reverse=reverse.replace(r['after'],r['before'],1)
 assert reverse==old
 families.append(dict(target=p,baselineWholeFileSha256Bytes=sha(data(ROOT/p)),baselineWholeFileSha256LF=sha(old),
  candidateWholeFileSha256LF=sha(new),baselineRaw=row(H/'baseline-bytes'/p),baselineLF=row(H/'baseline'/p),
  reviewOnlyCandidate=row(H/'prepared/existing'/p),install='exact-hunks-only',forwardReversePASS=True))
dump(H/'baseline-families.json',dict(baselineCommit=exact['baselineCommit'],hashMeaning='whole file hashes; hunk beforeSha256LF/afterSha256LF hash only the exact anchor text',families=families))

def module(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
sourcechecks=[]
def verify(label,value):assert value,label;sourcechecks.append(dict(check=label,PASS=True))
registry=json.loads(data(ROOT/'desktop/upstream-sources.json'));assert registry['upstreamCommit']==COMMIT
source_rows={r['path']:r for r in registry['sources']}
inv=json.loads(data(H/'generated-source-inventory.json'))
deltas=json.loads(data(H/'consumer-source-deltas.json'));merge=[]
for family,name,delta_key,gen in [('full-owner','extract-upstream-video-full-owner.py','fullOwner','full-owner'),('portrait','extract-upstream-video-fullscreen-pager.py','portrait','portrait')]:
 producer=module('baseline_'+family,H/'baseline/desktop/tools'/name)
 baseline_out=H/'source-audit'/('original-output-'+family);baseline_rows=producer.generate(ROOT,baseline_out)
 affected=list(dict.fromkeys(r['path']for r in deltas[delta_key]))
 for output in affected:
  candidate=text(H/'generated'/gen/output);reversed_body=candidate
  for edit in reversed([r for r in deltas[delta_key]if r['path']==output]):
   count=edit.get('count',1)
   if edit['after']:
    verify(output+' inverse unique '+sha(edit['after'])[:8],reversed_body.count(edit['after'])==count)
    reversed_body=reversed_body.replace(edit['after'],edit['before'],count)
   else:
    # Deleted companion is at the same original trailing member location.
    # Verify forward replay exactly; empty insertion cannot have a unique anchor.
    continue
  original=text(baseline_out/output);replayed=original
  for edit in [r for r in deltas[delta_key]if r['path']==output]:
   count=edit.get('count',1);verify(output+' source delta anchor '+sha(edit['before'])[:8],replayed.count(edit['before'])==count)
   replayed=replayed.replace(edit['before'],edit['after'],count)
  verify(output+' full original output plus explicit delta',replayed==candidate)
  if all(e['after']for e in deltas[delta_key]if e['path']==output):verify(output+' inverse exact original output',reversed_body==original)
  info=next(r for r in inv[delta_key]if r['path']==output)
  blob=subprocess.check_output(['git','show',COMMIT+':'+info['origin']],cwd=ROOT).decode().replace('\r\n','\n')
  existing=source_rows[info['origin']];verify(info['origin']+' fixed Git blob matches existing registry',existing['sha256']==sha(blob))
  write(H/'source-audit/original-git-blobs'/info['origin'],blob)
  verify(output+' emitted inventory output hash',info['sha256LF']==sha(candidate))
  merge.append(dict(path=info['origin'],sha256=sha(blob),mode=existing['mode'],existingFeatures=existing['features'],
   requiredFeature='stable-original-video-byte-cache-consumers',output=output,identityRowsAdded=0,preserveAllExistingFeatures=True,
   originalGeneratedBodySha256LF=sha(original),candidateGeneratedBodySha256LF=sha(candidate)))

manual=H/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoByteCacheConsumers.kt'
body=text(manual);cache=text(H/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopMediaByteCache.kt')
verify('full captured plan includes primary aliases keys role headers',all(t in cache for t in ['a.url == b.url','a.urls == b.urls','a.cacheKey == b.cacheKey','a.representation == b.representation','a.headers == b.headers']))
verify('full raw MPD identity retained', 'manifestFingerprint(fullAdaptiveManifest)'in cache and 'preparedManifestFingerprint.compareAndSet'in cache)
verify('reuse checks native Source and complete plan','prior.validate(source); prior.bound.matchesCapturedPlan(tracks, fullAdaptiveManifest)'in body)
verify('lease cancellation begins immediately','leaseJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true)'in cache)
probe=text(H/'prepared/manual/com/bilipai/desktop/player/cache/DesktopMediaByteProbeCalls.kt')
verify('CallerJob cancellation begins immediately','callerJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true)'in probe)
verify('body-read ownership before after actual read','super.read(sink, byteCount).also { assertCurrent() }'in probe)
verify('actual probe uses existing lease admission', 'lease.probeAdmission().calls'in probe and 'OkHttpClient('not in probe)
verify('new bridge has no additional client/store/cache actor', all(t not in body for t in ['OkHttpClient(', 'DesktopSessionStore(', 'DesktopMediaByteCache(']))
verify('Direct is typed no unsupported cache success','UNSUPPORTED_TRANSPORT, CACHE_IO_FAILURE'in body and 'takeIf { it.nativeTransport != null }'in body)
verify('prepare retains same captured Bound','prior.bound, true'in body)
verify('cached publication must be actual accepted object','selection?.accepted(publish(source, intent))'in body)
verify('recover actual failure ticket/source guarded','expectedFailureAttemptId = expectedFailureAttemptId'in text(H/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt'))
dump(H/'source-proof.json',dict(upstreamCommit=COMMIT,checks=sourcechecks,count=len(sourcechecks),RootInstalledAcceptance=False))
dump(H/'registry-merge-recipe.json',dict(identityRowsAdded=0,featuresOnly=True,rows=merge,noNewOriginalIdentities=True,keepOtherFamilies=True))

recipe='''Actual source-only cache consumers; Root installation recipe

This packet is based on Candidate commit 08d31bd020c515fc1e48775809766082fead6117, its installed71 Source/Native/Invocation families, and immutable actual70/101 runtime with the declared prepared overrides. It is not an installed Root/native acceptance receipt. Closed actual70 native21 is historical and is not rerun here.

Install only the two new manual files in copyWhitelist and the 12 ordered exact-hunks across eight families. prepared/existing holds review/compile copies; NEVER overwrite these whole files. baseline-families distinguishes full-file raw/LF hashes from exact-hunks.beforeSha256LF, which hashes only the snippet. Apply strict-one hunk replacement, reverse it to the same baseline, then compile the product once. Producers perform each new delta only AFTER their existing fixed-stable source/full-body inverse verification. Merge the four existing original identities' feature sets; add zero identity rows. No new dependency, Gradle, HTTP client, Store, native actor or Cast lifecycle.

Required initial request factory

- Capture exactly one DesktopOriginalVideoRepositoryBinding with the real resolver Job, request/page generation and current Store/entry authorization. Call binding.captureMediaBytes(the same application DesktopMediaByteCache). This captures the private authorization, namespace, requestJob and commit gate; do not reconstruct from latest account getters.
- Construct DesktopOriginalVideoCachedMediaFactory(request, legacyOrigin, adaptiveOrigin, progressiveOrigin, legacyTracks, publish, onPreparation, reuseAccepted, isRetainedCurrent). All constructor callbacks are required. The three origin callbacks are CPU-only preparations carrying the exact receipt, final User-Agent/Referer/Cookie (including deliberately empty fields), streamHeaders, title and remote URL semantics. Clear old nativeTransport before a new prepare; do not strip metadata or copy an old nativePublication as new authority.
- legacyTracks uses desktopOriginalLegacyByteTracks with the complete actual raw video/audio candidates and the original explicit cache-key map. adaptive uses the complete AdaptiveDashPlaybackSource.manifest plus ALL representations/backups; progressive uses ALL PlaybackSource.progressiveSegments. One Source.nativeTransport carries the exact Bound. Only identical remote Source fingerprint, track order/primary/aliases/key/role/headers and full raw MPD can reuse an accepted carrier.
- Pass .media to the sole DesktopOriginalVideoMediaIntentView consumer. Same lexical withPlaybackIntent freezes start position/playWhenReady across prepare/accept. publish(source,intent) must actually publish through the same NativeOwner using captured resolved request/CID/token and return that real AcceptedPublication, not a boolean guessed from queue submission. This callback marks Cached accepted and keeps normal resolver completion from discarding it. onPreparation must consume its typed Cached/Direct decision; it is not a successful-cache notification.

Accepted lifetime / adoption / recovery

- NativeOwner.mediaByteAdmission already receives the real accepted source lifetime. Its ownerJob must be the entry/source child Job and its guard must use real source/receipt/entry/native acceptance, independently of the completed resolver Job. It must not capture the OLD Accepted object's identity: accepted.set(next) occurs before bind/adopt. Use the supplied stillOwned predicate and current actual source lifetime; Store->entry->native remains the only admission order.
- Retained adoption carries the same Source.nativeTransport object and internal Bound. Never bind a new cache for a same-version publication handoff. The plan comparison refuses changed MPD ranges, representations, aliases or explicit keys even when selected URL stays the same.
- NativeOwner.acceptedMedia intercepts accept rather than invoking the delegate .media.accept. Its CPU cache preparation may use the factory, but the byte request ownerJob MUST be that real accepted source lifetime, not a temporary recovery resolver Job. No short-lived owner may discard a natively accepted Bound.
- Direct preparation is typed UNSUPPORTED_TRANSPORT or CACHE_IO_FAILURE; original remote fields and lexical intent remain intact. Adaptive unsupported/cache failure returns null to the original UseCase, which chooses its existing legacy fallback; it does not falsely keep a two-track Source as an adaptive MPD.
- After an actual native byte-cache failure, capture the current AcceptedPublication, the real current position/playWhenReady and actual PlayerState.failure.attemptId. Call recoverDesktopOriginalVideoDirectAfterCacheError(owner,expected,intent,attemptId) once. This uses existing same-owner recoverSource, exact receipt/source/failure ticket, preserves subtitle/native actor ownership, removes only the failed cache carrier and retires its capability through existing MPV load. Full adaptive failure intentionally becomes original remote separate-source recovery; no new cache is allocated or retry loop started. This packet compiles this guarded path; real installed native-error observer delivery/ACK remains Root integration verification, not a proved local-IO success.

Original VM CDN consumer

- Env now REQUIRES cdnRangeCapture:(DesktopOriginalVideoAcceptedPublication)->DesktopOriginalCdnRangeCapture?. Return null ONLY if that actual source has no carrier/has retired. Otherwise construct DesktopOriginalCdnRangeCapture(expected,nativeOwner::isCurrent), once before launching original worker. It references the carrier's same Bound and uses exact final source headers.
- The worker probe factory is capturedCache.calls(actual current coroutine Job). Each read and cancellation uses this caller + exact accepted lease; no repository.latest client lookup. Range writes complete real committed spans before cachedSegments increments. Keep original 15s safe buffer, original segment ranking/frontier/CDN winner/Wi-Fi/plugin scheduling unchanged.

Portrait consumer

- Implement REQUIRED DesktopOriginalPortraitPlatform.captureMediaCache(request,playData,streamUrls). request is the exact object retained by the two original preload callers; it must remain that preload scope/Job/generation.
- Use request.captureMediaBytes(sameCache); authorize a semantic Source via that request, with selected video/audio streamUrls and the exact original buildPortraitPlaybackHttpHeaders / final typed headers used for later native playback. Retain playData raw mirrors and cache-key policy; call captureDesktopOriginalPortraitByteCache(...,desktopOriginalLegacyByteTracks(...)). No cached latest/request replacement.
- The original helper keeps actual Wi-Fi guard and concurrent video1536KiB/audio256KiB defaults. Closing/dismissal cancels its capability and caller IO. Warm persisted spans belong to the same Store namespace/key/validator and are later actually consumed by native Bound; a preload capability itself is not a native playback lease.

Evidence and remaining boundaries

Final proof-06: 15 exact compiled source inputs, 447 classes; declared overrides only; actual70/101 pins before=after. Five groups/27 assertions use an ephemeral real same Store/Repository/cache, localhost origin and frozen local MPD/media. Real original SIDX selection writes committed bytes; final empty headers are preserved; complete 2video+1audio/ranges are retained; source intent/key/mirror/MPD plan checks and caller/lease cancellation are real. No external HTTP, account, window, MPV/native rerun or MainShell mount. compile-01's whitelist failure and proof-02's original15s buffer fixture premise failure remain raw, not product failures. Closed03/04/05 passed earlier source versions and are superseded by06. Root must compile/install the unique combinations and wire real required factory/failure observer; no claim that currently mounted MainShell already exercises this consumer.
'''
write(H/'ROOT-INTEGRATION.md',recipe)
payload=[]
for p in sorted((H/'prepared/manual').rglob('*.kt')):
 relative=p.relative_to(H/'prepared/manual').as_posix()
 payload.append(dict(source=row(p),target='desktop/src/main/kotlin/'+relative,kind='manual-platform-source'))
assert len(payload)==2
dump(H/'install-contract.json',dict(schema=1,copyWhitelist=payload,exactHunks=row(H/'exact-hunks.json'),baselineFamilies=row(H/'baseline-families.json'),
 registryMerge=row(H/'registry-merge-recipe.json'),recipe=row(H/'ROOT-INTEGRATION.md'),productionPayloads=2,exactHunkCount=12,existingFamilies=8,
 noWholeExistingOverwrite=True,noProofSourcesOrClasses=True,dependencyChanges=0,identityRowsAdded=0,RootInstalledAcceptance=False))
proof=H/'runs/proof-06';receipt=json.loads(data(proof/'receipt.json'));assert receipt['compilePASS']and receipt['runtimeExit']==0 and receipt['pinsUnchanged']
inputs=json.loads(data(proof/'inputs.json'))
for item in inputs:
 assert sha(data(item['source']['path']))==item['source']['sha256Bytes'],('final input drift',item['source']['path'])
dump(H/'validation-status.json',dict(final=receipt,groups=5,assertions=27,sourceChecks=len(sourcechecks),
 originalWholeProducerInverseReplay=True,familyHunkReverse=True,fullPlanReuse=True,immediateCallerAndLeaseCancellation=True,
 failedHistory={'compile-01':'declared whitelist missed existing enums/helpers, corrected test audit only','proof-02':'fixturebuffer2s violates original15s policy; corrected fixture20s'},
 supersededPassedHistory=['proof-03','proof-04','proof-05'],notProved=['installed Root factory/VM/Pager mount','native cache-error recovery observer/ACK','external CDN/account'],native70Replayed=False))
refs=[MAIN/'desktop/.local/stable-product-snapshot-70/manifest.json',MAIN/'desktop/.local/stable-product-snapshot-70/ordered-runtime-cp.json',MAIN/'desktop/.local/stable-video-media-intent-parity/frozen-handoff.json',MAIN/'desktop/.local/stable-native-media-byte-cache-actual70-proof/frozen-handoff.json']
dump(H/'external-inputs.json',[external(p)for p in refs])
raw=[];excluded=[]
for p in sorted(wide(H).rglob('*')):
 if not p.is_file():continue
 # Strip the extended prefix to retain the standard lane-relative artifact schema.
 rel=str(p)[len(str(wide(H)))+1:].replace('\\','/');normal=H/rel
 if rel=='frozen-handoff.json':continue
 exclusion=None
 if '/classes/'in rel or rel.endswith('.jar') or rel.endswith('.kotlin_module'):exclusion='rebuildable compiler output; exact inputs and commands retained'
 elif '/owned-data/'in rel:exclusion='fixture-owned ephemeral Store/cache/local IO data; not installation payload'
 elif '__pycache__/'in rel or rel.endswith('.pyc'):exclusion='rebuildable Python bytecode'
 r=dict(path=rel,sha256Bytes=sha(data(normal)),size=len(data(normal)))
 if exclusion:r['reason']=exclusion;excluded.append(r)
 else:raw.append(r)
dump(H/'frozen-handoff.json',dict(schema=1,kind='source-only-original-byte-cache-consumers',baselineCommit=exact['baselineCommit'],upstreamCommit=COMMIT,
 runtimeSnapshot=70,entries=101,explicitPreparedFamilyOverrides=True,RootInstalledAcceptance=False,productionPayloads=2,existingFamilies=8,exactHunks=12,
 groups=5,assertions=27,sourceChecks=len(sourcechecks),rawArtifactCount=len(raw),artifacts=raw,excluded=excluded))
print('Frozen',len(raw),'raw; 2 payloads/12hunks/8families;',len(sourcechecks),'source checks; 5groups/27assertions.')
print(sha(data(H/'frozen-handoff.json')))
