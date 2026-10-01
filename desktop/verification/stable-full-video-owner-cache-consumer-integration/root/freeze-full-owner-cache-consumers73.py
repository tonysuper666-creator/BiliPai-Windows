from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = REPO / 'desktop/verification/stable-full-video-owner-cache-consumer-integration'
assert not OUT.exists()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert head == '08d31bd020c515fc1e48775809766082fead6117'

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

lanes = (
    ('public-sponsor', 'stable-sponsor-public-execution-parity',
     '068527d481025253f76c4fd2a57b827b7ff34a2f443cddc4b6bf428b706e6cd3', 34),
    ('root-effects', 'stable-video-root-effects-parity',
     '25771d468adc09c45e200ff8e85477688532e869d9c6acb4befcbd02eaa0374a', 10),
    ('cache-consumers', 'stable-original-video-byte-cache-consumers-parity',
     'b0f87d87632549bf9e5b28ae0f4447f999947253e8cf0bc8d66fde0466a07628', 260),
    ('owner-assembly', 'stable-video-full-owner-assembly-parity',
     '6f2c8dbdd7bf2e0bf9f9c1ecd9cd55ce5fcda1fd0afecb3693efc19ac8161df6', 90),
)
prepared_counts = {}
for label, directory, digest, count in lanes:
    lane = MAIN / 'desktop/.local' / directory
    raw = read(lane / 'frozen-handoff.json')
    assert sha(raw) == digest
    packet = json.loads(raw)
    artifacts = packet['rows'] if 'rows' in packet else packet['artifacts']
    assert len(artifacts) == count
    for row in artifacts:
        value = read(lane / row['path'])
        size = row.get('size', row.get('bytes', row.get('sizeBytes')))
        assert sha(value) == row['sha256Bytes'] and len(value) == size, row['path']
        put('prepared/' + label + '/' + row['path'], value)
    put('prepared/' + label + '/frozen-handoff.json', raw)
    prepared_counts[label] = count

install_names = ('public-sponsor-execution-install73', 'video-root-effects-install73',
                 'original-cache-consumers-install73', 'full-owner-assembly-install73')
latest, installs = {}, []
for name in install_names:
    directory = HERE / name
    installed = json.loads(read(directory / 'installed.json'))
    installs.append(installed)
    for row in installed['changedFiles']:
        latest[row['path']] = row['afterSha256Bytes']
    for row in installed.get('targets', []):
        latest[row['path']] = row['sha256Bytes']
    for path in sorted(wide(directory).rglob('*'), key=str):
        if path.is_file():
            put('root/install/' + name + '/' + path.relative_to(wide(directory)).as_posix(), path.read_bytes())
assert installs[2]['payloadCount'] == 2 and installs[2]['exactHunks'] == 12
assert installs[3]['payloadCount'] == 4 and installs[3]['exactHunks'] == 5
assert all(row['exactIndexedInverse'] and row['requiredCacheThenAssemblyOrderPreserved']
           for row in installs[3]['exactRebasedFamilies'])
history = HERE / 'full-owner-assembly-install73-preflight-history'
for path in sorted(wide(history).rglob('*'), key=str):
    if path.is_file():
        put('root/preflight-history/' + path.relative_to(wide(history)).as_posix(), path.read_bytes())

snapshot = MAIN / 'desktop/.local/stable-product-snapshot-73'
meta_raw = read(snapshot / 'manifest.json')
assert sha(meta_raw) == '4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829'
meta = json.loads(meta_raw)
assert meta['runtimeEntries'] == 101 and meta['sourceRegistryCount'] == 1169 and meta['resourceCount'] == 213
inputs = {row['path']: row['sha256Bytes'] for row in meta['inputs']}
for name, digest in latest.items():
    assert sha(read(REPO / name)) == digest == inputs[name], name
ordered_raw = read(snapshot / 'ordered-runtime-cp.json')
assert sha(ordered_raw) == meta['orderedRuntimeClasspathSha256Bytes']
ordered = json.loads(ordered_raw)
assert len(ordered) == 101
for row in ordered:
    assert sha(read(row['path'])) == row['sha256Bytes']

checks = (
    ('plugins/com/android/purebilibili/data/repository/SponsorBlockRepository.kt',
     'stable-sponsor-public-execution-parity',
     'generated/com/android/purebilibili/data/repository/SponsorBlockRepository.kt'),
    ('original-video-full-owner/com/android/purebilibili/data/repository/DesktopOriginalVideoActionStatus.kt',
     'stable-video-full-owner-assembly-parity',
     'generated/com/android/purebilibili/data/repository/DesktopOriginalVideoActionStatus.kt'),
    ('original-video-full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt',
     'stable-video-full-owner-assembly-parity',
     'compile-reference/cache-compatible/VideoPlaybackViewModel.kt'),
    ('original-video-full-owner/com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt',
     'stable-original-video-byte-cache-consumers-parity',
     'generated/full-owner/com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt'),
    ('original-video-fullscreen-pager/com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt',
     'stable-original-video-byte-cache-consumers-parity',
     'generated/portrait/com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'),
)
generated_checks = []
for actual_name, lane, expected_name in checks:
    actual = read(REPO / 'desktop/build/generated' / actual_name)
    expected = read(MAIN / 'desktop/.local' / lane / expected_name)
    assert lf(actual) == lf(expected), actual_name
    generated_checks.append(dict(path=actual_name, sha256Bytes=sha(actual), sha256LF=sha(lf(actual))))
    put('root/production-generated/' + actual_name, actual)
binding = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
reference = MAIN / 'desktop/.local/stable-video-full-owner-assembly-parity/compile-reference/cache-compatible/DesktopOriginalVideoRepositoryBinding.kt'
assert lf(read(REPO / binding)) == lf(read(reference))
for name in ('manifest.json', 'ordered-runtime-cp.json'):
    put('root/snapshot73/' + name, read(snapshot / name))
for name in ('classes-73.log', 'classpath-73.log'):
    raw = read(REPO / 'desktop/.local/stable-build-repair' / name)
    assert b'BUILD SUCCESSFUL' in raw and b'BUILD FAILED' not in raw
    put('root/' + name, raw)
audit = json.loads(read(HERE / 'jvm-method-name-audit-73.json'))
assert audit['issueCount'] == 0 and audit['classCount'] == 15273
for name in ('install-public-sponsor-execution73.py', 'install-video-root-effects73.py',
             'install-original-cache-consumers73.py', 'install-full-owner-assembly73.py',
             'freeze-full-owner-cache-consumers73.py', 'jvm-method-name-audit-73.json'):
    put('root/' + name, read(HERE / name))

report = dict(phase=73, baseCommit=head, preparedRawArtifacts=prepared_counts,
    preparedTotalRawArtifacts=sum(prepared_counts.values()),
    originalPreparedEvidenceRemainsExplicitlyProspective=True,
    preparedPublicMemoryCallAssertions=26, preparedCacheLocalIoAssertions=27,
    preparedActionStatusMemoryAssertions=33, preparedNetworkEffectsSourceChecks=19,
    payloadCount=8, existingSourceFamilies=11, sourceExactHunks=20,
    existingIdentityFeatureUnionOperations=10, sourceIdentityCount=1169,
    resourceCount=213, runtimeEntries=101, newIdentityCount=0, newDependencyCount=0,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True,
    kotlinClassCount=audit['classCount'], illegalJvmMethodNames=0,
    generatedOutputsMatchingFrozenCandidateSources=generated_checks,
    originalActionFiveAndActualLoadStateProjectionInstalled=True,
    sameStoreThenEntryMemoryCommitOrderInstalled=True,
    cacheThenAssemblyOrderPreserved=True,
    sameBoundNativeOriginalCdnPortraitConsumerInstalled=True,
    cacheReuseRequiresWholeFingerprintOrderedTracksAndFullMpd=True,
    requiredOriginalIntentPreparationAndNativePublisherInstalled=True,
    callerAndLeaseCancellationThroughActualBodyReadInstalled=True,
    originalPublicSponsorFinalRequestPermitInstalled=True,
    alreadyAcceptedIoIsAnInFlightOperation=True,
    sameRootNetworkAndDiagnosticsViewsInstalled=True,
    firebaseRemoteTransportAvailable=False,
    installedNativeConsumerOrCacheFailureRecoveryAccepted=False,
    wholeOwnerConstructed=False, fullRootMounted=False,
    realAccountExternalHttpOrMainStartupAccepted=False, desktopExeReplaced=False)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
manifest = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=[]), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(manifest)
wide(OUT / 'README.md').write_text('''Original full video owner and cache consumer integration
======================================================

Eight new platform sources and twenty exact replacements across eleven existing source families install the full original owner composition, request/action/readback views, original byte-cache consumers, same-root network/diagnostic views and captured Sponsor public POST execution. Ten feature-union operations preserve all existing source identities, modes and hashes. No client, Store, MPV instance, dependency, resource identity or parallel cache actor is added.

The original five action status methods and watch-later membership helper are selected by the existing full-owner producer. The new read-only SessionState projection reads the actual original load state. Native/Binding callbacks retain entry-only admission; VM/domain memory commits acquire the existing Repository primary admission before entry. Original CDN and portrait preload jobs use the same bound cache, actual final headers and caller/lease cancellation. Retained-source reuse compares the complete source fingerprint, ordered tracks and full MPD. The required media preparation keeps the original lexical start/pause intent, and the direct recovery method consumes an actual native failure attempt and readbacks when a Root observer eventually invokes it.

Installation followed the frozen Cache then Assembly contract. Root's first additional preflight wrongly required the two orders to produce identical whole-file bytes: both recipes insert independent functions before generate, so order changes only function placement. No production file was written on that failed preflight. The corrected check preserves required installation order, exact indexed inverses and equal top-level AST definitions for the alternative order. Both preflight scripts/results remain recorded. Shared prepared existing files and compiled fixture outputs are never installation payloads.

Actual73 whole classes and test sources compile in 1m39s. Static JVM method-name checks pass for 15273 Kotlin classes. Five regenerated original outputs and the combined Binding match the frozen candidate sources byte-for-byte after LF normalization. The ordered 101-artifact runtime, 1169 source identities and 213 resources remain pinned. All four prepared packets and their failed histories retain their original prospective limits; their assertion counts are not installed runtime acceptance. The network view uses actual Windows profile types, while diagnostics uses the existing same-root preference/recording paths and explicitly declares Firebase remote transport unavailable. Captured Sponsor POST admission retains the original client, protocol and consent, and a request already accepted for execution remains in flight after later retirement.

The installed native consumer proof runs separately against the immutable actual73 runtime with zero production overrides. This source integration does not claim that result. A complete MainShell owner/Holder mount, simultaneous command/facade switching, real account/CDN behavior and whole application validation remain pending. The desktop EXE has not been replaced.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), payloadCount=8, kotlinClasses=audit['classCount'],
    manifestSha256Bytes=sha(manifest))))
