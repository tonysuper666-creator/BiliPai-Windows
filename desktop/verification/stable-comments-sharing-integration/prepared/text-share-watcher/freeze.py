import hashlib, json
from pathlib import Path

lane = Path(__file__).resolve().parent
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p, value): p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
plan = json.loads((lane/'patch-plan.json').read_text(encoding='utf-8'))
base = (lane/'base-inputs/DesktopNativeTextShare.kt').read_text(encoding='utf-8')
prepared = lane/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt'
candidate = prepared.read_text(encoding='utf-8')
assert base.count(plan['hunk']['before']) == 1
assert candidate == base.replace(plan['hunk']['before'], plan['hunk']['after'])
assert prepared.read_bytes() == (lane/'compile-01/DesktopNativeTextShare.kt').read_bytes()
compiled = json.loads((lane/'compile-01/compile-result.json').read_text(encoding='utf-8'))
accepted = json.loads((lane/'proof-01/accepted-result.json').read_text(encoding='utf-8'))
assert compiled['status'] == accepted['status'] == 'PASS'
assert not compiled['undeclaredOverlap']
assert accepted['assertions'] == 17 and accepted['caseCount'] == 3
write(lane/'source-audit.json', {'status':'PASS', 'soleHunkExact':True, 'compiledInputByteEqual':True,
    'baseSha256LF':plan['baseSha256LF'], 'candidateSha256LF':sha(prepared),
    'declaredActorClassOverlaps':compiled['declaredActorClassOverlaps'], 'undeclaredOverlap':[]})
write(lane/'install-contract.json', {'preparedOnly':True, 'applyHunkOnly':True,
    'target':plan['path'], 'hunk':plan['hunk'], 'sameExistingRootActor':True,
    'noCppOrNativeApiOrOpsStoreChanges':True, 'ownerGuardBeforeStateOnSameActiveToken':True,
    'oldTokenCannotRetireReplacement':True,
    'normalPollingCadenceMs':500, 'additionalDelayPossible':'mutex or native transport',
    'notSynchronousOwnerToNativeDataRequestedAdmissionProof':True,
    'fixture':'Actual compiled watcher coroutine and existing closeActive with task-only private native API spy; no DLL, HWND, ShareShow, receiver or C++ DataRequested',
    'historicalTypedBindings42Unchanged':True})
assert not (lane/'frozen-handoff.json').exists()
rows = [{'path':str(p.relative_to(lane)).replace('\\','/'), 'bytes':p.stat().st_size, 'sha256Bytes':sha(p)}
        for p in sorted(lane.rglob('*')) if p.is_file() and '__pycache__' not in p.parts]
write(lane/'frozen-handoff.json', {'status':'FROZEN_PREPARED', 'artifactCount':len(rows),
    'actualSnapshot15ManifestSha256Bytes':accepted['actualMain15ManifestSha256Bytes'],
    'actualCp92Sha256Bytes':accepted['actualCp92Sha256Bytes'], 'runtimeCases':3, 'assertions':17,
    'candidateJarSha256Bytes':compiled['jarSha256Bytes'], 'artifacts':rows,
    'noMainEdits':True, 'noGradle':True, 'noHTTP':True, 'noHWND':True,
    'notNativePaneOrSynchronousDataRequestedAdmissionProof':True})
print(json.dumps({'manifest':str(lane/'frozen-handoff.json'), 'sha256Bytes':sha(lane/'frozen-handoff.json'), 'artifacts':len(rows)}))
