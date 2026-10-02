from pathlib import Path
import hashlib, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.dont_write_bytecode = True
import packet_inventory_current as inspection

H = Path(__file__).resolve().parent
MAIN = H.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-full-owner-root-mount-parity'
BASE_HEAD = '462507f8a175a74a823fa3ea138f6b490c20a953'
FROZEN_SHA = '4cacb53b6d15e036edde4fd60828e1f98a3ea7f6120d232c6e221dbf4ac0cd0d'
CONTRACT_SHA = '07b8fa09a068a74b2cbd466223638e9948b0784080aba6bb5bb07146c947066d'

def sha(value): return hashlib.sha256(value).hexdigest()
def read(path): return inspection.read(path)
def put(path, value):
    path = inspection.wide(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(value)
def clean():
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == BASE_HEAD
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=REPO)
def target(path):
    assert path.startswith(('desktop/src/', 'desktop/tools/')) and '..' not in Path(path).parts, path
    return path

clean()
packets = inspection.inspect()
manifest_bytes = read(LANE / 'frozen-handoff.json')
assert sha(manifest_bytes) == FROZEN_SHA
manifest = json.loads(manifest_bytes)
for row in manifest['artifacts']:
    data = read(LANE / row['path'])
    assert len(data) == row['size'] and sha(data) == row['sha256Bytes'], row['path']
contract_bytes = read(LANE / 'install-contract.json')
assert sha(contract_bytes) == CONTRACT_SHA
contract = json.loads(contract_bytes)
assert contract['candidateSourceBaseHead'] == BASE_HEAD
assert not contract['generatedInstallAllowed'] and not contract['wholeExistingFileInstallAllowed'] and not contract['classJarInstallAllowed']

prereq = H / (sys.argv[2] if len(sys.argv) > 2 else 'preflight06')
prereq_bytes = read(prereq / 'result.json')
prereq_result = json.loads(prereq_bytes)
assert prereq_result['candidateHead'] == BASE_HEAD and not prereq_result['conflicts']
assert prereq_result['packetsVerified'] == 20 and prereq_result['rawArtifactsVerified'] == 1614
assert not prereq_result['registryAndGradleRecipeApplied']
frozen_prereq_result = json.loads(read(H / 'preflight06/result.json'))
assert prereq_result['sourceOutputs'] == frozen_prereq_result['sourceOutputs']
assert prereq_result['localEditsAppliedInMemory'] == frozen_prereq_result['localEditsAppliedInMemory']
texts = {}
for row in prereq_result['sourceOutputs']:
    path = target(row['path'])
    data = read(prereq / 'source' / path)
    assert sha(data) == row['sha256LF'], path
    texts[path] = data.decode('utf-8')

copies = []
for row in contract['copyWhitelist']:
    path = target(row['toPath'])
    assert path not in texts and not inspection.wide(REPO / path).exists(), path
    data = read(LANE / row['fromPath'])
    assert sha(data) == row['sha256Bytes'] and sha(data.replace(b'\r\n', b'\n')) == row['sha256LF']
    texts[path] = data.replace(b'\r\n', b'\n').decode('utf-8')
    copies.append(dict(target=path, fromPath=row['fromPath'], sha256LF=row['sha256LF']))

hunks = json.loads(read(LANE / contract['exactHunksFile']))
assert len(hunks) == contract['exactHunkCount'] == 66
applied, bridged = [], []
for index, row in enumerate(hunks):
    path = target(row['target'])
    before, after = row['before'], row['after']
    assert before and sha(before.encode()) == row['beforeAnchorSha256LF']
    assert sha(after.encode()) == row['afterAnchorSha256LF']
    if path not in texts:
        texts[path] = read(REPO / path).replace(b'\r\n', b'\n').decode('utf-8')
    current = texts[path]
    if row['sourceHunkFile'] == 'download-assets-merge-hunks.json':
        # These two exact anchors were already applied in the explicitly reviewed
        # prerequisite graph. No general after-anchor or deletion skip is allowed.
        assert path == 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
        assert sha(current.encode()) == '2d8db29bcc41de05655f780d9e8e8b5facf8f2d8585341ed511f10a738b00b53'
        assert after and current.count(after) == 1
        bridged.append(dict(index=index, target=path, afterAnchorSha256LF=row['afterAnchorSha256LF']))
        continue
    if 'occurrence' in row:
        occurrence = row['occurrence']
        assert isinstance(occurrence, int) and occurrence >= 0 and current.count(before) > occurrence
        position = -1
        for _ in range(occurrence + 1): position = current.index(before, position + 1)
        texts[path] = current[:position] + after + current[position + len(before):]
    else:
        count = row.get('count', 1)
        assert current.count(before) == count, (index, path, current.count(before))
        texts[path] = current.replace(before, after, count)
    applied.append(dict(index=index, target=path, sourceHunkFile=row['sourceHunkFile'],
                        beforeAnchorSha256LF=row['beforeAnchorSha256LF'], afterAnchorSha256LF=row['afterAnchorSha256LF']))
assert len(applied) == 64 and len(bridged) == 2 and len(copies) == 19
compiled = json.loads(read(LANE / 'runs/21/pins-before.json'))['sourceInputs']
compiled_matches = []
for row in compiled:
    name = row['path'].replace('\\', '/').split('/')[-1]
    matches = [path for path in texts if path.split('/')[-1] == name]
    assert len(matches) <= 1, name
    if matches:
        path = matches[0]
        assert sha(texts[path].encode()) == row['sha256Bytes'], (name, 'frozen run21 source bytes disagree')
        compiled_matches.append(dict(target=path, sha256LF=row['sha256Bytes']))

delta_lane = MAIN / 'desktop/.local/stable-video-root-retained-cleanup-delta'
delta_bytes = read(delta_lane / 'frozen-handoff.json')
assert sha(delta_bytes) == '1f9fc952cc3622bc71d7e65a37ca711e53b026378f6759b68ff82f6380cd1609'
delta = json.loads(delta_bytes)
assert delta['requiresFrozenParentManifest'] == FROZEN_SHA
for row in delta['artifacts']:
    data = read(delta_lane / row['path'])
    assert len(data) == row['size'] and sha(data) == row['sha256Bytes'], row['path']
delta_contract_bytes = read(delta_lane / 'install-contract.json')
assert sha(delta_contract_bytes) == delta['installContractSha256Bytes']
delta_contract = json.loads(delta_contract_bytes)
assert delta_contract['copyWhitelist'] == [] and not delta_contract['wholePreparedFilesInstallable']
review_lane = MAIN / 'desktop/.local/stable-video-root-lifecycle-postfix-review'
review_bytes = read(review_lane / 'frozen-review.json')
assert sha(review_bytes) == delta['independentPostfixReview']
review = json.loads(review_bytes)
for row in review['artifacts']:
    data = read(review_lane / row['path'])
    assert len(data) == row['size'] and sha(data) == row['sha256Bytes'], row['path']
delta_hunks = json.loads(read(delta_lane / delta_contract['exactHunks']))
assert len(delta_hunks) == delta_contract['exactHunkCount'] == 4
delta_applied = []
for row in delta_hunks:
    path = target(row['target'])
    before, after = row['before'], row['after']
    assert sha(texts[path].encode()) == row['baselineWholeSHA256LF'], path
    assert sha(before.encode()) == row['beforeAnchorSHA256LF'] and sha(after.encode()) == row['afterAnchorSHA256LF']
    assert texts[path].count(before) == row['count'] == 1
    texts[path] = texts[path].replace(before, after, 1)
    assert sha(texts[path].encode()) == row['afterWholeSHA256LF'], path
    delta_applied.append(dict(target=path, beforeWholeSHA256LF=row['baselineWholeSHA256LF'], afterWholeSHA256LF=row['afterWholeSHA256LF']))
out = H / ('full-root-preflight-' + sys.argv[1])
assert not out.exists()
for path, value in texts.items(): put(out / 'source' / path, value.encode())
result = dict(schema=1, candidateHead=BASE_HEAD, candidateModified=False,
              prerequisiteResultSHA256Bytes=sha(prereq_bytes), prerequisitePacketsVerified=len(packets),
              prerequisiteArtifactsVerified=sum(row['rawVerified'] for row in packets),
              fullRootFrozenSHA256Bytes=FROZEN_SHA, fullRootRawVerified=len(manifest['artifacts']),
              rootCopies=copies, rootHunksAppliedInMemory=applied, exactBridgeHunksAlreadyComposed=bridged,
              sourceOutputs=[dict(path=path, sha256LF=sha(value.encode())) for path, value in sorted(texts.items())],
              frozenCompile21SourceMatches=compiled_matches,
              lifecycleDeltaFrozenSHA256Bytes=sha(delta_bytes), lifecycleRawVerified=len(delta['artifacts']),
              independentLifecyclePostfixReviewSHA256Bytes=sha(review_bytes), independentPostfixRawVerified=len(review['artifacts']),
              lifecycleDeltaApplied=True, lifecycleHunksAppliedInMemory=delta_applied,
              registryAndGradleApplied=False, actualMainAccepted=False)
put(out / 'result.json', (json.dumps(result, indent=2) + '\n').encode())
clean()
print(json.dumps(dict(sourceFamilies=len(texts), rootManuals=len(copies), rootHunks=len(applied),
                      reviewedAssetsAlreadyComposed=len(bridged), rawVerified=len(manifest['artifacts']),
                      compile21SourceMatches=len(compiled_matches), lifecycleHunks=len(delta_applied),
                      candidateModified=False, lifecycleDeltaApplied=True), indent=2))
