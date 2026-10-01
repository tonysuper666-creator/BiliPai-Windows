"""Freeze one closed actual >=52 normal run without rewriting earlier receipts."""
from pathlib import Path
import argparse, hashlib, json, sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent
MAIN=LANE.parents[2]
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def row(p):return {'path':str(p),'sha256Bytes':sha(p),'size':safe(p).stat().st_size}
def load(p):return json.loads(safe(p).read_text(encoding='utf-8'))
def write(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
ap=argparse.ArgumentParser();ap.add_argument('--run',required=True);ap.add_argument('--root-observation',required=True)
args=ap.parse_args();assert '/' not in args.run and '\\' not in args.run
run=LANE/'runs'/args.run;before=load(run/'pins-before.json');after=load(run/'pins-after.json')
assert before==after and before['snapshot']>=52
assert len(before['runtimeEntries'])>0
assert sha(run/'OfflineNativeFixture.kt')==before['source']['sha256Bytes']
for e in before['runtimeEntries']:assert sha(e['path'])==e['sha256Bytes']
proof=load(run/'fixture-owned-runtime/native-proof.json')
assert not proof['diagnosticOnly'] and not proof['diagnosticBaselineMounted'] and not proof['JDWPEnabled']
origin=Path(before['runtimeEntries'][1]['path']).as_posix().lower()
for k,v in proof['classOrigins'].items():assert Path(v.removeprefix('file:/')).as_posix().lower().replace('%20',' ')==origin,(k,v)
dest=LANE/('closed-'+args.run);assert not safe(dest).exists();safe(dest).mkdir()
for p in [LANE/'run.py',LANE/'CONTRACT.md',LANE/'prepare.py',Path(__file__)]:
    safe(dest/p.name).write_bytes(safe(p).read_bytes())
observation=Path(args.root_observation)
safe(dest/'root-visual-observation.json').write_bytes(safe(observation).read_bytes())
passed_names={v['name'] for v in proof['checks'] if v['passed']}
smtc_failures=[v for v in proof['checks'] if 'SMTC' in v['name'] and not v['passed']]
summary={'status':proof['status'],'snapshot':before['snapshot'],'runtimeEntries':len(before['runtimeEntries']),
    'productionOverrides':0,'diagnosticBaselinesMounted':False,'JDWPEnabled':False,
    'originalEntryFullscreenAccepted':proof['originalEntryFullscreenAccepted'],
    'stableOriginalControlFullscreenAccepted':proof['stableOriginalControlFullscreenAccepted'],
    'realSkyMousePairs':proof['pointerPairs'],'checks':proof['checks'],'assertions':proof['assertions'],
    'passedChecks':sum(bool(v['passed']) for v in proof['checks']),
    'failedChecks':sum(not v['passed'] for v in proof['checks']),
    'requestedScopeFullyAccepted':False,'layoutAcceptedByRawGeometryAlone':False,
    'rootVisualObservation':row(dest/'root-visual-observation.json'),
    'rootVisualObservationSource':row(observation),
    'layoutAcceptanceBoundary':'Only the specific observations in the Root visual receipt are accepted; raw geometry alone does not imply layout acceptance.',
    'failure':proof.get('failure'),
    'SMTCFailedChecks':smtc_failures,
    'SMTCUnavailableCause':smtc_failures[0].get('details',{}).get('error','See unchanged actor runtime.log') if smtc_failures else None,
    'PiPAndSMTCCommandScopeCompleted':all(name in passed_names for name in [
        'Same product media adapter publishes original title/artist/bvid and exact current queue',
        'Injected source callback seeks same actual native owner',
        'PiP restore returns sole Canvas and original foreground without changing native source token']),
    'actualMainShellAccepted':False,'hardwareMouseAccepted':False,'realAccountAccepted':False,
    'OSSMTCButtonAccepted':False,'externalF11Accepted':False,'arbitraryRouteDisposalPlacementRestorationAccepted':False}
write(dest/'summary.json',summary)
raw=[];excluded=[]
preserved={'native-proof.json','failure-state.json','external-input-request.json','owned-geometries.json','owned-geometry-before-mount.json'}
for ep in sorted(safe(run).rglob('*')):
    if not ep.is_file():continue
    p=run/ep.relative_to(safe(run));relative=p.relative_to(run);a=row(p)
    if p.suffix in {'.class','.jar','.kotlin_module'}:
        excluded.append({**a,'reason':'Rebuildable fixture output; exact hash, compiler args and source retained.'})
    elif p.suffix=='.png':
        excluded.append({**a,'reason':'Fixture-owned Skia backing capture; pixels excluded, exact hash retained.'})
    elif 'fixture-owned-runtime' in relative.parts and p.name not in preserved and not p.name.startswith('external-input-'):
        excluded.append({**a,'reason':'Disposable synthetic task/Store/media data; no real account or personal files.'})
    else:raw.append(a)
for ep in sorted(safe(dest).iterdir()):
    if ep.is_file():raw.append(row(dest/ep.name))
write(dest/'frozen-handoff.json',{**summary,'schema':'path,sha256Bytes,size','rawArtifacts':raw,
    'excludedWithHashAndReason':excluded,'allExpectedRuntimePinsBeforeAfter':True,'classOriginsAllActualMainKotlin':True,
    'sharedGradle':False,'productionMutations':False,'previousClosedReceiptsModified':False})
print(json.dumps({'receipt':row(dest/'frozen-handoff.json'),'rawArtifacts':len(raw),'excluded':len(excluded),'status':summary['status']},indent=2))
