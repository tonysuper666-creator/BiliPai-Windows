"""Freeze one closed normal actual50 run independently of historical diagnostics."""
from pathlib import Path
import argparse, hashlib, json, sys
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8')
lane = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--run', required=True)
args = parser.parse_args()
assert args.run.startswith('actual50-') and '/' not in args.run and '\\' not in args.run
def safe(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def row(p): return {'path': str(p), 'sha256Bytes': sha(p), 'size': safe(p).stat().st_size}
def load(p): return json.loads(safe(p).read_text(encoding='utf-8'))
run = lane/'runs'/args.run
before = load(run/'pins-before.json'); after = load(run/'pins-after.json')
assert before == after and len(before['runtimeEntries']) == 97
assert before['snapshot'] == 50 and safe(run/'runtime.log').exists()
assert sha(run/'OfflineNativeFixture.kt') == before['source']['sha256Bytes']
for entry in before['runtimeEntries']: assert sha(entry['path']) == entry['sha256Bytes']
proof = load(run/'fixture-owned-runtime/native-proof.json')
assert not proof['diagnosticOnly'] and not proof['diagnosticBaselineMounted'] and not proof['JDWPEnabled']
actual = Path(before['runtimeEntries'][1]['path']).as_posix().lower()
for name, origin in proof['classOrigins'].items():
    assert Path(origin.removeprefix('file:/')).as_posix().lower().replace('%20',' ') == actual, (name, origin)
dest = lane/('closed-'+args.run)
assert not safe(dest).exists()
safe(dest).mkdir()
for p in [lane/'run.py', lane/'CONTRACT.md', Path(__file__)]:
    safe(dest/p.name).write_bytes(safe(p).read_bytes())
summary = {
    'status': proof['status'], 'snapshot': 50, 'runtimeEntries': 97,
    'productionOverrides': 0, 'diagnosticBaselinesMounted': False,
    'originalEntryFullscreenAccepted': proof['originalEntryFullscreenAccepted'],
    'stableOriginalControlFullscreenAccepted': proof['stableOriginalControlFullscreenAccepted'],
    'realSkyMousePairs': proof['pointerPairs'], 'assertions': proof['assertions'],
    'requestedScopeFullyAccepted': proof['status'] == 'PASS',
    'layoutAccepted': False, 'actualMainShellAccepted': False,
    'hardwareMouseAccepted': False, 'realAccountAccepted': False,
    'OSSMTCButtonAccepted': False, 'externalF11Accepted': False,
    'arbitraryRouteDisposalPlacementRestorationAccepted': False,
    'checks': proof['checks'],
}
safe(dest/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
artifacts = []; excluded = []
for ep in sorted(safe(run).rglob('*')):
    if not ep.is_file(): continue
    p = run/ep.relative_to(safe(run)); rel = p.relative_to(run); item = row(p)
    if p.suffix in {'.class', '.jar', '.kotlin_module'}:
        excluded.append({**item, 'reason': 'Rebuildable fixture output; compiler arguments/source and exact output hash retained.'})
    elif p.suffix == '.png':
        excluded.append({**item, 'reason': 'Fixture-owned Skia backing capture; raw pixels excluded, exact hash retained.'})
    elif 'fixture-owned-runtime' in rel.parts and p.name not in {'native-proof.json','failure-state.json','external-input-request.json'} and not p.name.startswith('external-input-'):
        excluded.append({**item, 'reason': 'Disposable synthetic task/Store/media data; no user account or personal files.'})
    else: artifacts.append(item)
for ep in sorted(safe(dest).iterdir()):
    if ep.is_file(): artifacts.append(row(dest/ep.name))
receipt = dest/'frozen-handoff.json'
data = {**summary, 'all97PinsBeforeAfter': True, 'classOriginsAllActualMainKotlin': True,
        'artifacts': artifacts, 'excludedWithHashAndReason': excluded,
        'sharedGradle': False, 'productMutations': False, 'historical13And14Modified': False}
safe(receipt).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
print(json.dumps({**row(receipt), 'status': data['status'], 'artifacts': len(artifacts), 'excluded': len(excluded)}, ensure_ascii=False, indent=2))
