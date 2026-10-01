from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-original-video-byte-cache-actual73-proof'
OUT = REPO / 'desktop/verification/stable-original-video-byte-cache-actual73'
assert not (OUT / 'artifact-manifest.json').exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == '4dabae6df935e34f745ef84a4d73a645744acb6e'

def wide(p):
    value = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def read(p): return wide(p).read_bytes()
def sha(value): return hashlib.sha256(value).hexdigest()
rows = []
def put(name, value):
    path = wide(OUT / name)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        assert path.read_bytes() == value, name
    else:
        path.write_bytes(value)
    rows.append(dict(path=name, sha256Bytes=sha(value), sizeBytes=len(value)))

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == 'f8544e5f2fd6159ac472d11f395fbe76236cf315ca0eb4a08e27053985d8d116'
packet = json.loads(raw)
assert len(packet['artifacts']) == packet['rawArtifactCount'] == 35
assert packet['productionOverrides'] == 0 and packet['entries'] == 101
assert packet['groups'] == 5 and packet['assertions'] == 30
assert not packet['HTTP503RecoveryAccepted'] and packet['invalidCachedMediaDirectRecoveryAccepted']
for row in packet['artifacts']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['size'], row['path']
    put('proof/' + row['path'], value)
for row in packet['excluded']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['size'] and row['reason'], row['path']
put('proof/frozen-handoff.json', raw)
acceptance = json.loads(read(LANE / 'acceptance.json'))
assert acceptance['productionOverrides'] == 0 and acceptance['assertions'] == 30
assert acceptance['successfulRun'] == 'actual73-02'
assert acceptance['retainedFailure']['run'] == 'actual73-01'
assert acceptance['retainedFailure']['priorAssertionsPassed'] == 17
for run in acceptance['closedRuns']:
    assert read(LANE / 'runs' / run / 'pins-before.json') == read(LANE / 'runs' / run / 'pins-after.json')
    overlap = json.loads(read(LANE / 'runs' / run / 'overlap.json'))
    assert overlap['classOverlap'] == [] and overlap['productionOverrides'] == 0, overlap

snapshot = MAIN / 'desktop/.local/stable-product-snapshot-73'
meta_raw = read(snapshot / 'manifest.json')
assert sha(meta_raw) == '4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829'
meta = json.loads(meta_raw)
cp_raw = read(snapshot / 'ordered-runtime-cp.json')
assert sha(cp_raw) == 'f71557c1952176118a9eba16479da2e244cacfb6cc419a65f43dc5501f822d31'
cp = json.loads(cp_raw)
assert len(cp) == 101
for row in cp:
    assert sha(read(row['path'])) == row['sha256Bytes']
for row in meta['inputs']:
    assert sha(read(REPO / row['path'])) == row['sha256Bytes'], row['path']
native = json.loads(read(LANE / 'runs/actual73-02/inputs.json'))['native']
assert sha(read(native['path'])) == native['sha256Bytes'] == '673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
put('root/snapshot73/manifest.json', meta_raw)
put('root/snapshot73/ordered-runtime-cp.json', cp_raw)
put('root/freeze-native-cache-consumers-actual73.py', read(__file__))
report = dict(snapshot=73, preparedRawArtifacts=35, installedRuntimeEntries=101,
    fixtureSourceCount=1, productionOverrides=0, groups=5, assertions=30,
    actualNativeCachedFactoryAndOriginalLexicalIntentAccepted=True,
    actualNormalResolverCompletionAndNativePauseResumeAccepted=True,
    actualSameBoundAndWholePlanMpdReuseAccepted=True,
    actualInvalidCachedMediaNativeFailureAndDirectRemoteAckAccepted=True,
    actualFailureAttemptNotFabricated=True, exactSourceVersionRetained=True,
    actualRecoveryReadback=acceptance['actualRecoveredNativePositionSeconds'],
    actualErrorTimeDesiredPause=acceptance['actualReaderDesiredPause'],
    actualErrorTimeNativePaused=acceptance['actualErrorTimeNativePaused'],
    oldCapabilityGoneAndWrongAttemptRejected=True,
    nativeDllAndRuntimePinsUnchanged=True, sourceOnlyPacket260Unmodified=True,
    retained503FailurePriorChecks=17, HTTP503RecoveryAccepted=False,
    fullOwnerConstructed=False, MainShellAccepted=False,
    realAccountOrExternalCdnAccepted=False, desktopExeReplaced=False)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
manifest = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=packet['excluded']), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(manifest)
wide(OUT / 'README.md').write_text('''Actual73 installed cache-consumer native acceptance
===================================================

A single new fixture source executes the immutable actual73 ordered 101-artifact runtime with zero production overrides. Five groups and thirty checks pass using the existing libmpv and software-frame target: actual CachedMediaFactory/original lexical intent/NativeOwner publication, pause/resume and normal resolver completion, the same bound carrier and complete MPD reuse, and real invalid-media native failure followed by direct remote decode/ACK with the same source version. Old local capabilities return 410 and stale source identities/failure attempts cannot repeat recovery. Class origins, fixture overlaps and runtime/native byte pins are retained.

The successful fault case serves fixture-owned invalid media with valid range/validator responses; actual MPV demux creates its failure attempt. Actual error-time Reader position 1.375 seconds and desired pause feed recovery, whose native readback is 1.4 seconds and paused. Error-time native pause itself is unavailable/null and is recorded explicitly. No native failure, URI or source-version state is fabricated, and a queued recovery return alone is not counted as native success.

The first closed run is retained as failed: after seventeen successful checks, an actual HTTP503/range IO failure leaves MPV buffering without a typed native failure for twenty seconds. HTTP503/network-stall recovery is not accepted. A real guarded stream-IO error notification and Root recovery consumer remain pending. This independent proof does not mount the full original VM/Holder in MainShell, use a real account/CDN, create a physical HWND or replace the desktop EXE. Historical actual70 and source-only260 evidence is unchanged.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), assertions=30, productionOverrides=0,
    manifestSha256Bytes=sha(manifest), HTTP503RecoveryAccepted=False)))
