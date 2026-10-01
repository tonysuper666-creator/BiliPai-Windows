from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-player-plugin-final-write-parity'
OUT = REPO / 'desktop/verification/stable-original-video-plugin-owner-integration'
assert not OUT.exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == '5507a44a2085be09f52ff8e4561beb71713c9a97'

def wide(p):
    value = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def read(p): return wide(p).read_bytes()
def sha(value): return hashlib.sha256(value).hexdigest()
def lf(value): return value.replace(b'\r\n', b'\n')

rows = []
def put(name, value):
    path = wide(OUT / name)
    path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists(), name
    path.write_bytes(value)
    rows.append(dict(path=name, sha256Bytes=sha(value), sizeBytes=len(value)))

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == '829c748364219b1458151c546bbdae62d8cd053f613d877b94285f9b9143f5cb'
packet = json.loads(raw)
assert len(packet['artifacts']) == 120 and packet['focusedAssertions'] == 27
for row in packet['artifacts']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['bytes']
    put('prepared/' + row['path'], value)
put('prepared/frozen-handoff.json', raw)
review = MAIN / 'desktop/.local/stable-video-plugin-owner-bridge-review'
raw = read(review / 'frozen-review.json')
assert sha(raw) == 'e247dd558c410130f43fe587563ca49fe5def771a6809a6727c6f26275f55628'
review_packet = json.loads(raw)
assert len(review_packet['artifacts']) == 2
for row in review_packet['artifacts']:
    value = read(review / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['bytes']
    put('independent-review/' + row['path'], value)
put('independent-review/frozen-review.json', raw)

install_dir = HERE / 'player-plugin-owner-install72'
installed = json.loads(read(install_dir / 'installed.json'))
assert installed['payloadCount'] == 2 and installed['childExactHunks'] == 7 and installed['rootExactHunks'] == 1
repair = json.loads(read(install_dir / 'repairs/import01/repair.json'))
assert repair['exactHunks'] == 1 and not repair['originalBusinessLogicChanged']
memory_repair = json.loads(read(install_dir / 'repairs/compiler-memory01/repair.json'))
assert memory_repair['compilerMaxHeap'] == '5g' and not memory_repair['runtimeMemoryChanged']
assert sha(read(REPO / memory_repair['path'])) == memory_repair['afterSha256Bytes']
snapshot = MAIN / 'desktop/.local/stable-product-snapshot-72'
meta = json.loads(read(snapshot / 'manifest.json'))
assert meta['runtimeEntries'] == 101 and meta['sourceRegistryCount'] == 1169 and meta['resourceCount'] == 213
inputs = {row['path']: row['sha256Bytes'] for row in meta['inputs']}
for row in installed['targets']:
    expected = repair['afterSha256Bytes'] if row['path'] == repair['path'] else row['sha256Bytes']
    assert sha(read(REPO / row['path'])) == expected == inputs[row['path']]
for row in installed['changedFiles']:
    assert sha(read(REPO / row['path'])) == row['afterSha256Bytes'] == inputs[row['path']]
for path in sorted(wide(install_dir).rglob('*'), key=str):
    if path.is_file():
        put('root/install/' + path.relative_to(wide(install_dir)).as_posix(), path.read_bytes())
for name in ('CdnRegionPlugin.kt', 'SponsorBlockInsightPolicy.kt'):
    relative = 'com/android/purebilibili/feature/plugin/' + name
    actual = read(REPO / 'desktop/build/generated/plugins' / relative)
    assert lf(actual) == lf(read(LANE / 'generated' / relative)), name
    put('root/production-generated/' + relative, actual)
ordered = json.loads(read(snapshot / 'ordered-runtime-cp.json'))
assert len(ordered) == 101
for row in ordered:
    assert sha(read(row['path'])) == row['sha256Bytes']
for name in ('manifest.json', 'ordered-runtime-cp.json'):
    put('root/snapshot72/' + name, read(snapshot / name))
for name in ('classes-72.log', 'classpath-72.log'):
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
audit = json.loads(read(HERE / 'jvm-method-name-audit-72.json'))
assert audit['issueCount'] == 0 and audit['classCount'] > 15165
for name in ('install-player-plugin-owner72.py', 'repair-player-plugin-owner72-import.py',
    'repair-player-plugin-owner72-compiler-memory.py',
    'freeze-player-plugin-owner72.py', 'jvm-method-name-audit-72.json'):
    put('root/' + name, read(HERE / name))
report = dict(phase=72, preparedRawArtifacts=120, independentReviewRawArtifacts=2,
    preparedFocusedAssertions=27, preparedExistingFamilyOverrides=4,
    originalPreparedEvidenceRemainsSourceOnly=True, payloadCount=2,
    childExactHunks=7, rootRequestHelperHunks=1, rootBridgeImportRepairHunks=1,
    compilerHeapRepairHunks=1, compilerMaxHeap='5g', runtimeMemoryChanged=False,
    rootWholeCompileFailuresPreserved=2,
    existingFamilies=3, sharedWholeFilesReplaced=False,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True,
    kotlinClassCount=audit['classCount'], illegalJvmMethodNames=0,
    sourceIdentityCount=1169, resourceCount=213, runtimeEntries=101,
    existingIdentityFeatureUnions=2, newIdentityCount=0, newDependencyCount=0,
    sameRuntimeMutexStoreAndOriginalProviders=True,
    typedOriginalOwnerPluginBridgeInstalled=True,
    capturedCdnFactoryAndCallerCancelBodyExecutionInstalled=True,
    exactCompletedSeekTicketInSponsorHistoryPredicate=True,
    postHistoryAwaitFixedTicketAndDispatchRecheckInstalled=True,
    configurationLifecycleUsesSamePlayerMutex=True,
    finalDiskPermitLinearizesInFlightWrite=True,
    diskRenameAndAllIoOutsideRootGates=True,
    runtimeThreeArgumentAbiRetained=True, rootNewRequestHelperIndependentExactReplay=True,
    originalSponsorPublicTransportClosurePending=True,
    fullRootMounted=False, nativePluginCompletedSeekAccepted=False,
    realAccountOrExternalHttpOrMainStartupAccepted=False, desktopExeReplaced=False)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
manifest = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=[]), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(manifest)
wide(OUT / 'README.md').write_text('''Original video owner plugin integration
=======================================

Two platform sources and eight exact hunks across the existing Runtime, Store and sole plugin producer install the captured plugin write/transport view and typed full-owner bridge. Two original identities retain their hashes/modes and union the new feature. Root's pure new-request helper commutes exactly with the prepared Runtime patch. The original three-argument callback remains; CDN probes use the required captured four-argument transport. Provider identity, Runtime generation, account/entry/native publication and exact completed native seek remain their existing owners. No client, Store, cache actor, Runtime scope, player, identity or dependency is added.

The captured original CDN delayed write re-enters the same Runtime mutex. Private encoding/fsync and final rename stay outside Root gates. Final admission mints an in-flight publication permit; already accepted commits cannot be revoked by later retirement. Configuration follows configurationMutex then the same playerMutex. Sponsor history keeps the original merge/deduplication/order/100-limit; Root uses the exact completed native seek ticket and rechecks that fixed dispatch/ticket after its awaited history write before optional upload.

Actual72 whole classes and test sources compile and the static JVM method audit passes. The first whole compile found a missing canonical SponsorSegment import in Root's new bridge. A second complete backend compile exhausted the inherited 3GiB compiler heap; a 5GiB retry passed. The successful setting is now explicit in gradle.properties and its normal configured classes/test-sources pass is retained. Application runtime memory settings are unchanged. Both exact failed logs, before/after bytes and repairs are preserved. The two regenerated original plugin outputs match the frozen prepared sources. All 1169 source identities, 213 resources and 101 runtime artifacts remain pinned. The prepared 120 raw records and 27 focused assertions remain explicitly prospective, using four declared existing family overrides and memory transport/native predicates. The independent two-record read-only review is retained with its original remaining community-transport finding.

Original Sponsor community execution still needs captured final transport admission/cancellation in its original public client; the new post-history recheck alone does not close that transport boundary. Full MainShell mounting, real completed native plugin seek behavior, real account/CDN operation and whole application acceptance remain pending. No desktop EXE has been replaced and this source integration is not complete feature acceptance.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), kotlinClasses=audit['classCount'], manifestSha256Bytes=sha(manifest))))
