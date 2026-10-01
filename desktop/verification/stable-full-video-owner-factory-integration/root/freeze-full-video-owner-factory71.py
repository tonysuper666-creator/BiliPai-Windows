from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = REPO / 'desktop/verification/stable-full-video-owner-factory-integration'
assert not OUT.exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == 'efc9b0c0f1827b9d1d29b324f9ad25e9be3e513c'

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

installed_dir = HERE / 'full-video-owner-factory-install71'
installed = json.loads(read(installed_dir / 'installed.json'))
assert installed['payloadCount'] == 3 and installed['exactHunks'] == 15
assert installed['existingFamilyCount'] == 8
packet_raw_count = 0
for entry in installed['frozenPackets']:
    lane = MAIN / 'desktop/.local' / entry['lane']
    raw = read(lane / 'frozen-handoff.json')
    assert sha(raw) == entry['manifestSha256Bytes']
    packet = json.loads(raw)
    records = packet.get('artifacts', packet.get('raw'))
    assert len(records) == entry['rawCount']
    packet_raw_count += len(records)
    for row in records:
        value = read(lane / row['path'])
        assert sha(value) == row['sha256Bytes']
        assert len(value) == row.get('bytes', row.get('size', row.get('sizeBytes')))
        put('prepared/' + entry['lane'] + '/' + Path(row['path']).as_posix(), value)
    put('prepared/' + entry['lane'] + '/frozen-handoff.json', raw)
assert packet_raw_count == 167

snapshot = MAIN / 'desktop/.local/stable-product-snapshot-71'
assert sha(read(snapshot / 'manifest.json')) == '416f1ec5fdf577f13c607d52248ca3c97d39420d363bac78f500d119053e6ad1'
assert sha(read(snapshot / 'ordered-runtime-cp.json')) == '6ec3765f3ced6095ac01d2c2908550e9487bfcd586c1008ff4a582920f84f55f'
meta = json.loads(read(snapshot / 'manifest.json'))
assert meta['runtimeEntries'] == 101 and meta['sourceRegistryCount'] == 1169 and meta['resourceCount'] == 213
inputs = {r['path']: r['sha256Bytes'] for r in meta['inputs']}
for row in installed['targets']:
    assert sha(read(REPO / row['path'])) == row['sha256Bytes'] == inputs[row['path']]
for row in installed['changedFiles']:
    assert sha(read(REPO / row['path'])) == row['afterSha256Bytes'] == inputs[row['path']]
for path in sorted(wide(installed_dir).rglob('*'), key=str):
    if path.is_file():
        put('root/install/' + path.relative_to(wide(installed_dir)).as_posix(), path.read_bytes())

# The installed sole Core producer outputs match the frozen new lexical intent
# outputs. Direct originals continue through the existing pinned source set.
intent_lane = MAIN / 'desktop/.local/stable-video-media-intent-parity'
intent_packet = json.loads(read(intent_lane / 'frozen-handoff.json'))
generated_count = 0
for row in intent_packet['artifacts']:
    name = row['path']
    if name.startswith('generated-replay/') and name.endswith('.kt'):
        relative = name[len('generated-replay/'):]
        actual = REPO / 'desktop/build/generated/original-video-state-core' / relative
        if wide(actual).is_file():
            value = read(actual)
            assert lf(value) == lf(read(intent_lane / name)), relative
            put('root/production-core/' + relative, value)
            generated_count += 1
assert generated_count >= 6
full_vm = REPO / 'desktop/build/generated/original-video-full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
assert lf(read(full_vm)).count(b'if (shouldAutoPlay && !p.isPlaying) p.play()') == 1
for row in json.loads(read(snapshot / 'ordered-runtime-cp.json')):
    assert sha(read(row['path'])) == row['sha256Bytes']
for name in ('manifest.json', 'ordered-runtime-cp.json'):
    put('root/snapshot71/' + name, read(snapshot / name))
for name in ('classes-71.log', 'classpath-71.log'):
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
audit = json.loads(read(HERE / 'jvm-method-name-audit-71.json'))
assert audit['issueCount'] == 0 and audit['classCount'] == 15165
for name in ('install-full-video-owner-factory71.py', 'freeze-full-video-owner-factory71.py', 'jvm-method-name-audit-71.json'):
    put('root/' + name, read(HERE / name))
report = dict(phase=71, preparedRawArtifacts=167, newManualSources=3,
    exactHunks=15, existingFamilies=8, sharedWholeFilesReplaced=False,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True,
    kotlinClassCount=15165, illegalJvmMethodNames=0,
    sourceIdentityCount=1169, resourceCount=213, runtimeEntries=101,
    newIdentityCount=0, newDependencyCount=0, newGradleTaskCount=0,
    generatedCoreOutputsMatchFrozen=generated_count,
    retainedPauseGuardInstalled=True,
    requestBindingMetadataSubtitleAndDomainFactoryInstalled=True,
    metadataRepositoryAccessorRebasedOverExactCache70=True,
    originalCorePlaybackIntentCapturedBeforeNativeEnqueue=True,
    nativeInitialIntentAccepted=False, fullRootFactoryMounted=False,
    pluginBridgeInstalled=False, actualVipRecaptureAccepted=False,
    realAccountOrExternalHttpOrMainStartupAccepted=False, desktopExeReplaced=False)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
manifest = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=[]), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(manifest)
wide(OUT / 'README.md').write_text('''Complete video owner request and domain factory integration
==========================================================

Three platform source payloads and fifteen exact hunks across eight existing families integrate the captured metadata facet, full original request repository/Notes/account views, required domain factory, request-owned subtitle calls, retained pause policy and original Core lexical initial playback intent. The metadata visitor accessor is rebased only over the exact installed cache70 Repository. Existing source families are never replaced wholesale. The sole Core and full VM producers remain authoritative; the generated Core outputs match the frozen replay and the retained-handoff play guard respects shouldAutoPlay. No source identity, resource, dependency or Gradle task is added.

Actual71 whole classes and test sources compile. The static classfile audit reports zero illegal JVM method names among 15165 Kotlin classes. All 1169 original source identities, 213 resources and 101 runtime entries remain pinned. The four immutable prepared packets preserve 167 raw records and their historical failures. Their narrow source-only/CPU evidence is explicitly prospective and is not relabeled mounted native or account acceptance.

The factories require the same real Root capabilities, actual original request token/resolved CID, entry and account admission, same subtitle files and captured Repository transport. Core preserves the original source policy and later playWhenReady/seek/prepare expression order. This compile does not construct the complete Root owner or mount its Holder. Plugin bridge, cache/Portrait consumers, explicit VIP receipt recapture, native initial intent, real accounts and whole MainShell acceptance remain pending. No desktop EXE has been replaced; inventory is not functional completion or line reuse.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), generatedCoreOutputs=generated_count, manifestSha256Bytes=sha(manifest))))
