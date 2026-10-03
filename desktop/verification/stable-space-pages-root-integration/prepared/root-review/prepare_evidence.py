from pathlib import Path
import difflib
import hashlib
import json
import sys

sys.stdout.reconfigure(encoding='utf8')
root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
source = main / 'desktop/.local/stable-original-space-pages-root-parity'
packet = root / 'packet'
packet.mkdir(exist_ok=False)
def wide(p): return Path('\\\\?\\' + str(p.absolute()))
def read(p): return wide(p).read_bytes()
def sha(raw): return hashlib.sha256(raw).hexdigest()
def copy(name, raw):
    target = wide(packet / name)
    assert not target.exists()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    return dict(path=name, sha256Bytes=sha(raw), bytes=len(raw))

installation = json.loads(read(root / 'installation.json'))
build = json.loads(read(root / 'actual-build02.json'))
assert build['exitCode'] == 0 and build['junitTests'] == 15 and build['actualProductOverrides'] == 0
assert build['junitFailures'] == build['junitErrors'] == build['junitSkipped'] == 0
raw_rows = []
original_frozen = read(source / 'frozen-handoff.json')
assert sha(original_frozen) == installation['frozenManifestSha256Bytes']
raw_rows.append(copy('agent/frozen-handoff.json', original_frozen))
for row in json.loads(original_frozen)['files']:
    raw = read(source / row['path'])
    assert sha(raw) == row['sha256Bytes'] and len(raw) == row['bytes']
    if Path(row['path']).suffix.lower() not in ('.class', '.jar', '.dll', '.exe', '.kotlin_module'):
        raw_rows.append(copy('agent/' + row['path'], raw))
# Keep the earlier failed prepared iterations as failures, alongside the final
# prospective run and Root's separate failed/passed normal builds.
for family in ('compile-runs', 'fixture-runs'):
    for path in sorted((source / family).glob('*/*')):
        relative = path.relative_to(source).as_posix()
        if path.is_file() and path.suffix in ('.json', '.log', '.args', '.kt'):
            if not any(r['path'] == 'agent/' + relative for r in raw_rows):
                raw_rows.append(copy('agent/' + relative, read(path)))
for name in ('repair_sole_owners.py', 'sole-owner-repair.json', 'installation-attempt01.json',
             'actual-build01.log', 'actual-build01.json', 'prepare_evidence.py'):
    raw_rows.append(copy('root-review/' + name, read(root / name)))
for path in sorted((root / 'normal-attempt01-before-repair').rglob('*')):
    if path.is_file():
        raw_rows.append(copy('root-review/normal-attempt01-before-repair/' +
            path.relative_to(root / 'normal-attempt01-before-repair').as_posix(), read(path)))

generated = candidate / 'desktop/build/generated/original-space-pages'
replay_raw = read(generated / 'source-transform-replay.json')
replay = json.loads(replay_raw)
generated_rows = []
for row in replay['files']:
    raw = read(generated / row['generated'])
    text = raw.decode().replace('\r\n', '\n')
    assert sha(text.encode()) == row['generatedSha256LF']
    inverse = text
    for step in reversed(row['transforms']):
        assert sha(inverse.encode()) == step['afterSha256']
        for at in reversed(step['positionsAfter']):
            assert inverse[at:at + len(step['adapted'])] == step['adapted']
            inverse = inverse[:at] + step['original'] + inverse[at + len(step['adapted']):]
        assert sha(inverse.encode()) == step['beforeSha256']
    assert inverse == read(candidate / row['source']).decode().replace('\r\n', '\n')
    assert sha(inverse.encode()) == row['sha256LF']
    raw_rows.append(copy('root-normal-generated/' + row['generated'], raw))
    generated_rows.append(dict(path=row['generated'], sha256Bytes=sha(raw),
        completeOriginalInverseByteEqualLF=True,
        differsFromFrozen=raw != read(source / 'generated' / row['generated'])))
assert sum(r['differsFromFrozen'] for r in generated_rows) == 2
helper_name = 'com/android/purebilibili/feature/space/DesktopOriginalSpaceRunCatching.kt'
assert read(generated / helper_name) == read(source / 'generated' / helper_name)
raw_rows.append(copy('root-normal-generated/' + helper_name, read(generated / helper_name)))
raw_rows.append(copy('root-normal-generated/source-transform-replay.json', replay_raw))
verification = dict(passed=True, fullOriginalBodies=11, normalOutputs=12,
    sourceInverseReconstructed=11, frozenIdenticalFullBodies=9,
    declaredSoleOwnerChangedBodies=2, existingSoleHelpersExcluded=3,
    rows=generated_rows, actualProductOverrides=0, normalBuildAttempt=2,
    testsExecuted=True, junitMethods=15, accountAccepted=False, rootRuntimeAccepted=False)
(root / 'generated-verification.json').write_text(json.dumps(verification, indent=2) + '\n', encoding='utf8')
raw_rows.append(copy('root-review/generated-verification.json', read(root / 'generated-verification.json')))
patch = []
for row in installation['sourceTargets']:
    current = read(candidate / row['path'])
    assert sha(current) == row['afterSha256Bytes']
    before = read(root / 'before' / row['path']) if row['beforeSha256Bytes'] else b''
    patch.extend(difflib.unified_diff(before.decode().replace('\r\n','\n').splitlines(keepends=True),
        current.decode().replace('\r\n','\n').splitlines(keepends=True),
        fromfile='a/' + row['path'], tofile='b/' + row['path']))
(root / 'source-diff.patch').write_text(''.join(patch), encoding='utf8', newline='\n')
summary = dict(baseCommit=installation['baseCommit'], sourceTargets=installation['sourceTargets'],
    normalMainAndTestCompilePassed=True, normalBuildAttempt=2,
    normalBuildLogSha256Bytes=build['logSha256Bytes'], actualProductOverrides=0,
    junitMethods=15, junitFailures=0, junitErrors=0, junitSkipped=0,
    fullOriginalBodies=11, sourceCount=1229, resourceCount=244,
    previousFailedNormalBuildRetained=True, declaredSoleOwnerRepair=True,
    rootRuntimeAccepted=False, realAccountAccepted=False, decodedNativePlaybackAccepted=False,
    installedExeAccepted=False, v025Accepted=False, allFeaturesComplete=False)
(root / 'integration-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf8')
(root / 'README.md').write_text('''The normal v023 Windows product now mounts the complete original Space, UpowerRank and MemberGuard pages through the existing physical Root. The same retained session, original requests, gallery/comment owners, playlist, progress reader and text-share actor are borrowed. Account replacement, restore and shutdown retire and drain these children.

Normal Gradle classes plus the exact DesktopOriginalSpacePagesTest passed in build02: 15 executed JUnit methods, no failures, errors or skips, and zero production overlays. Eleven full original source bodies were independently recovered from the normal generated outputs. No dependency or resource was added.

Build01 failed on three original helper declarations already emitted by the existing favorite/Space producers. Root removed only the duplicate bodies, checked their full source against the sole owners, and declared both producer dependencies/inputs. The failed build and exact repair remain recorded. Earlier prepared compile and loopback failures are preserved separately.

This is a v023 integration slice. Actual pointer/window behavior, live-account permissions, decoded native audio/video, public release and installed EXE remain unaccepted. v025 source deltas in the prepared packet are review evidence only. This receipt does not claim whole-project feature parity or latest-version deployment.
''', encoding='utf8')
frozen = dict(rawArtifacts=raw_rows, rawFiles=len(raw_rows), normalBuildAttempt=2,
    testsExecuted=True, junitMethods=15, rootRuntimeAccepted=False, v025Accepted=False)
frozen_raw = (json.dumps(frozen, indent=2) + '\n').encode()
(packet / 'frozen-handoff.json').write_bytes(frozen_raw)
print(json.dumps(dict(rawFiles=len(raw_rows), frozenSha256Bytes=sha(frozen_raw),
    normalBuildPassed=True, junitMethods=15, fullInverseBodies=11), indent=2))
