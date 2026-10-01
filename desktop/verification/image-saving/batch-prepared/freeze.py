"""Freeze the completed task-only four-contract handoff; do not run product code."""
from pathlib import Path
import hashlib, json, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path, value):
    assert path.resolve().is_relative_to(HERE)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
evidence = json.loads((HERE / 'runs/02/accepted-evidence.json').read_text())
assert evidence['passed'] and evidence['assertions'] == 36 and evidence['cases'] == 4 and evidence['HTTPRequests'] == 9
assert evidence['StoreRepoOpsSaveTargetMotionFilesOverride'] is False
assert evidence['MainInstalledIntegration'] is False
compile_evidence = json.loads((HERE / 'runs/02/compile-evidence.json').read_text())
for row in compile_evidence['sources']: assert sha(Path(row['path'])) == row['sha256Bytes']
assert sha(HERE / 'runs/02/candidate-and-fixture.jar') == evidence['candidateJarSha256Bytes']
assert sha(HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt') == evidence['candidateInstallSourceSha256Bytes']
write(HERE / 'attempt-history.json', {
    'compile01': {'accepted': False, 'reason': 'Fixture-only CancellationException constructor import ambiguity; changed only fixture call to saving.cancel().', 'logSha256Bytes': sha(HERE / 'runs/01/compile.log')},
    'compile02': {'accepted': True, 'logSha256Bytes': sha(HERE / 'runs/02/compile.log')},
    'runtime02': {'fixturePassed': True, 'assertions': 36, 'cases': 4, 'logSha256Bytes': sha(HERE / 'runs/02/run.log'),
                  'acceptRecorderRepair': 'After successful fixture execution, duplicate MainInstalledIntegration keyword in Python record assembly was removed; accept mode finalized that same proof without rerunning fixture.'}
})
relative = [
    'runner.py', 'BatchFixture.kt', 'freeze.py', 'source-contract.json', 'candidate.diff', 'attempt-history.json',
    'original/DesktopDynamicImageAssets.kt', 'original/alpha9-save-all-contract.txt',
    'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
    'runs/02/src/DesktopDynamicImageAssets.kt', 'runs/02/frozen-sources/DesktopDynamicImageAssets.kt', 'runs/02/frozen-sources/BatchFixture.kt',
    'runs/02/compiler.args', 'runs/02/compile.log', 'runs/02/compile-evidence.json', 'runs/02/candidate-and-fixture.jar',
    'runs/02/run.args', 'runs/02/run.log', 'runs/02/proof/result.json', 'runs/02/accepted-evidence.json',
]
for path in (HERE / 'runs/02/proof').rglob('BiliPai-*'):
    if path.is_file(): relative.append(path.relative_to(HERE).as_posix())
files = [{'path': value, 'sha256Bytes': sha(HERE / value), 'bytes': (HERE / value).stat().st_size} for value in sorted(relative)]
write(HERE / 'frozen-handoff.json', {
    'frozen': True, 'status': 'task-only candidate accepted for four requested batch contracts',
    'base': 'immutable actual Main03 / 92 entries',
    'actualMain03ManifestSha256Bytes': evidence['actualMain03ManifestSha256Bytes'],
    'orderedActual92CpSha256Bytes': evidence['orderedActual92CpSha256Bytes'],
    'installSource': 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
    'permittedDelta': 'Only saveImages attempt-all ordinary-failure aggregation; CancellationException immediately propagates.',
    'acceptedAssertions': 36, 'acceptedCases': 4, 'actualLoopbackRequests': 9,
    'declaredAssetsFamilyOnlyOverrideClassNames': evidence['declaredAssetsFamilyOnlyOverrideClassNames'],
    'rootParentPickerAdapterBodyUnchanged': True, 'StoreRepoOpsSaveTargetMotionFilesOverride': False,
    'MainInstalledIntegration': False, 'staticCodecIntegrated': False,
    'allWritesWithinNewTaskLane': True, 'MainOtherLaneGradleHWNDUserDirectoryWrites': False,
    'old114Or135RerunOrAcceptance': False,
    'files': files,
})
print('FROZEN four-contract task-only candidate; 36 assertions; 9 loopback requests')
