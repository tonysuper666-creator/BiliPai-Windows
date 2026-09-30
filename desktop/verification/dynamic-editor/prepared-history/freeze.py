from pathlib import Path
import difflib
import hashlib
import json
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / '.git').exists())
TAG = 'v0.2.3-alpha.9'
OPERATIONS = 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
BASE_SHA = '50aece83a0efe799e48947f62b3c5f47cbe1ed6c32ce5457a2e74b2701292ac7'
SNAPSHOT = REPO / 'desktop/.local/dynamic-full-card-main-product-snapshot-lifecycle-final'


def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)


def sha(path):
    return hashlib.sha256(safe(path).read_bytes()).hexdigest()


def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')


def load(path):
    return json.loads(safe(path).read_text(encoding='utf-8'))


def row(path):
    return dict(path=Path(str(path).removeprefix('\\\\?\\')).relative_to(HERE).as_posix(),
                bytes=safe(path).stat().st_size, sha256Bytes=sha(path))


def descendants(path):
    return sorted((Path(str(p).removeprefix('\\\\?\\')) for p in safe(path).rglob('*')
                   if p.is_file()), key=lambda p: str(p).casefold())


def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO)


def lf(data):
    return data.decode('utf-8-sig').replace('\r\n', '\n').replace('\r', '\n').encode('utf-8')


assert sha(REPO / OPERATIONS) == BASE_SHA, 'Current Main Operations baseline changed; do not replace it.'
compile_proof = load(HERE / 'compile-evidence.json')
run_proof = load(HERE / 'run-evidence-13.json')
assert compile_proof['passed'] and compile_proof['activeClasses'] == 'classes-attempt13'
assert run_proof['passed'] and run_proof['classes'] == 'classes-attempt13'
for source in compile_proof['sources']:
    assert sha(source['path']) == source['sha256Bytes'], source['path']
dependencies = load(HERE / 'dependency-identities.json')
assert len(dependencies) == 90
for dependency in dependencies:
    assert sha(dependency['path']) == dependency['sha256Bytes'], dependency['path']

desired = HERE / 'prepared' / OPERATIONS
base_text = safe(REPO / OPERATIONS).read_text(encoding='utf-8').replace('\r\n', '\n')
desired_text = safe(desired).read_text(encoding='utf-8').replace('\r\n', '\n')
patch = ''.join(difflib.unified_diff(base_text.splitlines(True), desired_text.splitlines(True),
                                   fromfile='a/' + OPERATIONS, tofile='b/' + OPERATIONS))
write(HERE / 'operations.patch', patch)
checked = subprocess.run(['git', 'apply', '--check', str(HERE / 'operations.patch')],
                         cwd=REPO, capture_output=True, text=True)
assert checked.returncode == 0, checked.stderr
write(HERE / 'patch-check.json', json.dumps(dict(passed=True, applied=False,
      baselinePath=OPERATIONS, baselineSha256Bytes=BASE_SHA, desiredSha256Bytes=sha(desired),
      patchSha256Bytes=sha(HERE / 'operations.patch'), stdout=checked.stdout,
      stderr=checked.stderr), indent=2) + '\n')

originals = load(HERE / 'source-inventory.json')
detail = load(HERE / 'protocol-review/detail-source-identities.json')
audited = {}
for item in originals:
    audited[item['path']] = item['sha256']
for item in detail['sources']:
    previous = audited.setdefault(item['path'], item['sha256LfUtf8'])
    assert previous == item['sha256LfUtf8']
source_evidence = []
for path, expected in sorted(audited.items()):
    current = hashlib.sha256(lf(safe(REPO / path).read_bytes())).hexdigest()
    upstream = hashlib.sha256(lf(git('show', TAG + ':' + path))).hexdigest()
    assert current == upstream == expected, path
    source_evidence.append(dict(path=path, sha256LfUtf8=current, matchesTag=True,
                                gitBlob=git('rev-parse', TAG + ':' + path).decode().strip()))
write(HERE / 'original-source-evidence.json', json.dumps(dict(
      tag=TAG, tagCommit=git('rev-parse', TAG + '^{commit}').decode().strip(),
      sourceCount=len(source_evidence), sources=source_evidence), indent=2) + '\n')

# Main class overlap must consist only of the one intentional augmented Operations family.
main_entries = set()
for dependency in dependencies[:3]:
    with zipfile.ZipFile(safe(dependency['path'])) as jar:
        main_entries.update(n for n in jar.namelist() if n.endswith('.class'))
classes = descendants(HERE / 'classes-attempt13')
class_entries = {p.relative_to(HERE / 'classes-attempt13').as_posix() for p in classes
                 if p.suffix == '.class'}
overlap = sorted(class_entries & main_entries)
prefix = 'com/bilipai/desktop/data/DesktopDynamicCardOperations'
assert overlap and all(n == prefix + '.class' or n.startswith(prefix + '$') for n in overlap), overlap
write(HERE / 'class-overlap-evidence.json', json.dumps(dict(
      preparedProductOverrideFQNs=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],
      productClassOverlap=overlap, compiledClassCount=len(class_entries),
      MainIntegration=False, modelsApisAndWbiFromActualMain=True), indent=2) + '\n')

# Select final proof only. Earlier failed/successful attempts remain historical and unmodified.
files = []
for directory in ('prepared', 'generated', 'classes-attempt13', 'proof-attempt13'):
    files.extend(descendants(HERE / directory))
for name in ('prepare.py', 'compile-fixture.py', 'run-fixtures.py', 'freeze.py',
             'UiFixture.kt', 'PolicyFixture.kt', 'ROOT-INTEGRATION.txt',
             'source-inventory.json', 'source-registry-delta.json', 'baseline.json',
             'dependency-identities.json', 'compile-evidence.json', 'run-evidence-13.json',
             'generated-protocol-members.kt', 'operations.patch', 'patch-check.json',
             'original-source-evidence.json', 'class-overlap-evidence.json', 'reused-callable-evidence.json', 'reused-callable-javap.log', 'collect-reused-callable-proof.py', 'PRELIMINARY-12-CORRECTION.txt',
             'compile-13.log', 'compiler-13.args', 'ui-13.log', 'ui-13.args',
             'policy-13.log', 'policy-13.args', 'protocol-13.log', 'protocol-13.args'):
    files.append(HERE / name)
# Review documents include a historical protocol-5 citation, not a second install producer.
for name in ('EditorTransportFixture.kt', 'original-identities.json',
             'fixture-source-identities.json', 'fixture-source-selection.txt',
             'protocol-contract.txt', 'fake-api-cases.txt', 'detail-dependency-review.txt',
             'detail-source-identities.json', 'detail-class-presence.json'):
    files.append(HERE / 'protocol-review' / name)
files.extend([HERE / 'protocol-5.log', HERE / 'proof-attempt5/editor-protocol-result.json'])
files = sorted(set(files), key=lambda p: p.relative_to(HERE).as_posix())
artifacts = [row(p) for p in files]
for artifact in artifacts:
    assert sha(HERE / artifact['path']) == artifact['sha256Bytes']
payloads = [row(p) for p in descendants(HERE / 'prepared')]
manifest = dict(
    formatVersion=1, scope='Prepared original native-style dynamic editor/publish plus detail foundations',
    MainIntegration=False, sharedGradle=False, HWND=False, realAccount=False, sockets=False,
    originalTag=TAG, originalCommit='fcf84853b287662e8a9129ea0d38576c36522a34',
    baseSnapshotManifestSha256Bytes=sha(SNAPSHOT / 'manifest.json'),
    baseOrderedRuntimeCpSha256Bytes=sha(SNAPSHOT / 'ordered-runtime-cp.json'),
    currentMainOperationsBaselineSha256Bytes=BASE_SHA,
    currentMainOperationsDesiredSha256Bytes=sha(desired),
    fixtureDependencies=dependencies,
    preparedProductOverrideFQNs=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],
    selectedOriginalIdentityCount=len(originals), auditedOriginalIdentityCount=len(source_evidence),
    generatedKotlinFiles=13, generatedSourceModeCounts=dict(direct=6, selected=7),
    sourceRegistryDeltaCounts=dict(new=20, existingMerge=2), resourcesAdded=0,
    verification=dict(compile=True, freshJvmProcesses=3, originalNativeThemes=2,
                      actualPressReleasePairsPerTheme=27, screenshots=10,
                      originalPolicyAndSelectedLocalFile=True, actualRetrofitProtocolGroups=9,
                      patchApplyCheck=True, applied=False,
                      originalFieldsAndAlgorithms=True, MainZeroOverride=False),
    remaining=['Full original liquid editor dock/segmented closure',
               'Full original DynamicDetail/ReplyItemView/SubReply/raw comment REST-gRPC flow',
               'Full comment decoration bitmap/export ReplyCommentImageSpec/QR/native clipboard-share seams',
               'Actual Windows file/date dialogs and live authorized publish/edit validation',
               'Android predictive back/MotionPhoto/platform routes'],
    installPayloads=payloads,
    artifactCount=len(artifacts), artifacts=artifacts,
    historicalEvidencePolicy='Only final attempt13 classes and proof are authoritative. '
       'Protocol review cites unchanged attempt5 logs retained as historical evidence. '
       'Earlier manual fragments and compiled attempts are not install payloads.')
write(HERE / 'frozen-handoff.json', json.dumps(manifest, indent=2) + '\n')
for dependency in dependencies:
    assert sha(dependency['path']) == dependency['sha256Bytes']
for artifact in artifacts:
    assert sha(HERE / artifact['path']) == artifact['sha256Bytes']
assert sha(REPO / OPERATIONS) == BASE_SHA
print(json.dumps(dict(verifiedArtifacts=len(artifacts), preparedPayloads=len(payloads),
                      originalIdentities=len(source_evidence), classOverlap=len(overlap),
                      desiredOperationsSha256Bytes=sha(desired),
                      frozenHandoffSha256Bytes=sha(HERE / 'frozen-handoff.json'))))
