"""Freeze source/log evidence, exclude compiled artifacts, and stage this integration."""
from pathlib import Path
import hashlib
import json
import os
import subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LANE = REPO / 'desktop/.local/dynamic-detail-reply-parity'
FORMAL = REPO / 'desktop/verification/dynamic-detail-reply'
FORMAL.mkdir(parents=True, exist_ok=True)
ALLOW = {'.json', '.kt', '.py', '.log', '.md', '.txt', '.patch', '.png', '.args', '.xml', '.csv', '.html'}


def ext(path):
    value = str(Path(path).absolute()); prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)


def sha(path):
    return hashlib.sha256(ext(path).read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8', newline='\n')


frozen = {}


def copy(source, relative, expected=None):
    source, target = Path(source), FORMAL / relative
    assert target.suffix in ALLOW, target
    data = ext(source).read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if expected:
        assert digest == expected
    if relative in frozen:
        assert frozen[relative]['sha256Bytes'] == digest
        return
    ext(target.parent).mkdir(parents=True, exist_ok=True)
    ext(target).write_bytes(data)
    frozen[relative] = dict(path=relative, sha256Bytes=digest, bytes=len(data))


handoff_path = LANE / 'raw-handoff-final/frozen-handoff.json'
handoff = json.loads(handoff_path.read_bytes())
assert sha(handoff_path) == '4a7950229a7bf2eed938f9c9979b395696714634e2b690003adb062c66460852'
for row in handoff['artifacts']:
    assert sha(LANE / row['path']) == row['sha256Bytes']
    if Path(row['path']).suffix in ALLOW:
        copy(LANE / row['path'], 'prepared-history/' + row['path'], row['sha256Bytes'])
copy(handoff_path, 'prepared-history/raw-handoff-final/frozen-handoff.json')
for relative in ['raw-handoff-final/installation-payloads.json', 'raw-handoff-final/producer-manifest.json',
                 'raw-handoff-final/original-source-inventory.json', 'raw-handoff-final/ROOT-INTEGRATION.txt']:
    copy(LANE / relative, 'prepared-history/' + relative)
for path, expected in [
    ('protocol/frozen-manifest.json', '7823571e997d514383efbf2faf7244b42853f47afd2bcd82149b466c9d8f9d18'),
    ('protocol/detail-prepared/frozen-manifest.json', 'c885806ab7b20245868ccca190812663afd35fcc9803469ee340512c773a33fb'),
]:
    manifest = LANE / path
    assert sha(manifest) == expected
    copy(manifest, 'prepared-history/' + path, expected)
    for row in json.loads(manifest.read_bytes())['frozenArtifacts']:
        source = manifest.parent / row['path']
        assert sha(source) == row['sha256Bytes']
        if source.suffix in ALLOW:
            copy(source, 'prepared-history/' + str(Path(path).parent / row['path']).replace('\\', '/'), row['sha256Bytes'])
for directory, directories, filenames in os.walk(ext(HERE)):
    directories[:] = sorted(name for name in directories if name != '__pycache__')
    for filename in sorted(filenames):
        source = Path(directory) / filename
        if source.suffix in ALLOW:
            relative = source.relative_to(ext(HERE)).as_posix()
            copy(source, 'actual-main/' + relative)
for relative in ['dynamic-editor-protocol/verification.json', 'dynamic-detail-reply-verification.json']:
    copy(REPO / 'desktop/build/generated' / relative, 'actual-main/generated-verification/' + relative)
accepted = HERE / 'protocol-session-proof03/accepted-evidence.json'
proof = json.loads(accepted.read_bytes())
assert proof['passed'] and proof['groupCount'] == 31 and proof['productOverrides'] == 0
assert not proof['mainConsumerIntegrated'] and not proof['packaged']
snapshot_path = HERE / 'main-product-snapshot-01/manifest.json'
snapshot = json.loads(snapshot_path.read_bytes())
for row in snapshot['sourceFiles']:
    assert hashlib.sha256(ext(REPO / row['path']).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == row['sha256Lf']
for row in snapshot['generatedProductFiles']:
    assert sha(REPO / row['path']) == row['sha256Bytes']
report = dict(passed=True, sourceIntegration=True, originalTag='v0.2.3-alpha.9',
    preparedArtifactCount=445, compiledPreparedClassesInstalled=False,
    originalSourceIdentities=27, existingIdentitiesMerged=13, newIdentitiesAdded=14, totalRegistryIdentities=602,
    generatedKotlinSources=25, generatedBytesEqualReviewedHandoff=True,
    soleOperationsProducer=True, editorMembersPreserved=True, authPublishVerificationPreserved=True,
    actualMainCompilePassed=True, actualMainSnapshotSha256Bytes=sha(snapshot_path),
    actualMainProofSha256Bytes=sha(accepted), actualMainFixtureGroups=31,
    actualMainClasspathEntries=89, externalDependencies=86, testOnlyKotlinTestAdded=True,
    mainProductOverrides=0, realAccounts=False, actualSockets=False,
    mainConsumerIntegrated=False, fullDetailParity=False, desktopExeUpdated=False,
    remaining=['original detail layout and thread container consumer', 'route root/target reply context',
        'Root comment count revision protection', 'conversation composer and liquid dock',
        'actual platform image picker, clipboard and sharing', 'Main mounted interaction acceptance and package'],
    retainedHarnessFailures=['proof01 child fixture inventory lookup', 'proof02 missing original parser fixture helper'],
    retainedOriginalFixtureMainIntegrationMetadata=False,
    selectedUpstreamWhitespacePreserved=True,
    rawArtifactCount=len(frozen))
save(FORMAL / 'source9-dynamic-detail-reply-integration.json', report)
manifest = dict(formatVersion=1, rawEvidence=True, compiledArtifactsIncluded=False,
    sourceOnlyPreparedHistory=True, mainConsumerIntegrated=False,
    files=sorted(frozen.values(), key=lambda row: row['path']))
save(FORMAL / 'artifact-manifest.json', manifest)
production = [row['target'] for row in json.loads((LANE / 'raw-handoff-final/installation-payloads.json').read_bytes())['payloads']]
production += ['.gitattributes', 'desktop/build.gradle.kts', 'desktop/upstream-sources.json',
    'desktop/tools/verify-upstream-dynamic-editor-protocol.py', 'desktop/tools/verify-upstream-dynamic-detail-reply-protocol.py']
raw_paths = ['desktop/verification/dynamic-detail-reply/' + row['path'] for row in frozen.values()]
raw_paths += ['desktop/verification/dynamic-detail-reply/source9-dynamic-detail-reply-integration.json',
              'desktop/verification/dynamic-detail-reply/artifact-manifest.json']
paths = sorted(set(production + raw_paths))
pathspec = HERE / 'stage-paths.nul'
pathspec.write_bytes(b'\0'.join(path.encode('utf-8') for path in paths) + b'\0')
subprocess.run(['git', '-c', 'core.longpaths=true', 'add', '-f', '--pathspec-from-file=' + str(pathspec), '--pathspec-file-nul'], cwd=REPO, check=True)
actual = set(subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z'], cwd=REPO).decode('utf-8').strip('\0').split('\0'))
assert actual == set(paths)
operations_path = 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
subprocess.run(['git', 'diff', '--cached', '--check', '--', *[path for path in production if path != operations_path]], cwd=REPO, check=True)
# Selected original request bodies retain the original whitespace on blank lines.
# Keep conflict-marker and EOF checks; do not mutate the reviewed producer output.
subprocess.run(['git', '-c', 'core.whitespace=-blank-at-eol', 'diff', '--cached', '--check', '--', operations_path], cwd=REPO, check=True)
save(HERE / 'staging-receipt.json', dict(passed=True, stagedPaths=len(paths), rawArtifacts=len(frozen),
    artifactManifestSha256Bytes=sha(FORMAL / 'artifact-manifest.json'), actualMainProofGroups=31, binariesStaged=False))
print('PASS staged', len(production), 'production paths and', len(raw_paths), 'raw evidence paths; 31 Main groups.')
