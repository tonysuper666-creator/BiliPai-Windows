"""Freeze closed runs 01-13 separately from subsequent root-cause diagnostics."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
lane=Path(__file__).resolve().parent
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def row(p):return {'path':str(p),'sha256Bytes':sha(p),'size':safe(p).stat().st_size}
def load(p):return json.loads(safe(p).read_text(encoding='utf-8'))
receipt=lane/'closed01-13/frozen-handoff.json'
assert not safe(receipt).exists()
safe(receipt.parent).mkdir(parents=True,exist_ok=True)
for src,name in [(lane/'CONTRACT.md','CONTRACT.md'),(lane/'run.py','run.py'),(lane/'freeze_closed.py','freeze_closed.py')]:
    safe(receipt.parent/name).write_bytes(safe(src).read_bytes())
summary=receipt.parent/'acceptance-summary.md'
safe(summary).write_text('''# Actual49 closed fixture runs 01–13

Requested full native scope is not accepted. Root must preserve these failures when reporting product readiness.

Run13 loads the real original manager task in the full actual DesktopOriginalOfflineRootHost and receives the real native first video frame. Exactly 97 immutable product runtime entries are used, with zero production overrides. All class origins and graph hashes are checked before/after.

The blank Window, same MPV SwingPanel, and sole original shared Surface with empty foreground all retain fullscreen after the native frame is ready. Full RootHost initial orientation requests fullscreen at 10004ms; the actual window/device enters fullscreen at 10036ms. Its new owned ComposeDialog reports WINDOW_OPENED at 10981ms, when the device is already cleared and the Window is Floating. WindowState still says Fullscreen until the resize/state events at 11344–11372ms. Throughout that interval the own Main remains active; there is no own deactivated/focus-lost event and no original false request. This is correlation retained for the separate root-cause diagnosis, not a proven cause.

Root computer-use sky delivers four real mouse press/release pairs. The first original exit button changes original state; the stable enter button successfully holds actual Window and WindowState Fullscreen for two seconds, and the next exit button returns Floating. Initial automatic fullscreen remains FAIL. The next-episode click is not accepted: the observed AWT point is x115,y508, while the measured original target center is about x179,y508. Root's screenshot was clipped after a negative-X Main drag. The exact task remains BVfixture_1_80/native source3. The receipt does not convert AWT delivery into a successful next callback.

SMTC, PiP, queue commands and route-disposal placement were not reached in this cohort. MainShell/account/hardware mouse/OS media-button input/F11 synchronization are not accepted by this standalone fixture. Historical PostMessage trials are failures and are not treated as real control input. Run14 geometry/JDI diagnostics and all future sources are excluded from this immutable receipt.
''',encoding='utf-8',newline='\n')
artifacts=[];excluded=[];runs=[]
for i in range(1,14):
    run=lane/f'runs/actual49-{i:02d}'
    assert safe(run).exists()
    before=load(run/'pins-before.json')
    assert sha(run/'OfflineNativeFixture.kt')==before['source']['sha256Bytes']
    record={'number':i,'source':row(run/'OfflineNativeFixture.kt'),'compilePassed':safe(run/'compile-result.json').exists(),
            'runtimeClosed':safe(run/'runtime.log').exists(),'pinsAfter':safe(run/'pins-after.json').exists()}
    for extended in sorted(safe(run).rglob('*')):
        if not extended.is_file():continue
        p=run/extended.relative_to(safe(run));relative=p.relative_to(run);item=row(p)
        if p.suffix in {'.class','.jar','.kotlin_module'}:
            excluded.append({**item,'reason':'Rebuildable fixture output; raw source/compiler arguments and output hashes retained.'})
        elif p.suffix=='.png':
            excluded.append({**item,'reason':'Fixture-owned Skia capture only. Hash retained; raw pixels unnecessary for replay.'})
        elif 'fixture-owned-runtime' in relative.parts and p.name not in {'native-proof.json','failure-state.json','external-input-request.json'} and not p.name.startswith('external-input-'):
            excluded.append({**item,'reason':'Disposable synthetic task/Store/media data, not private account or user data.'})
        else:artifacts.append(item)
    runs.append(record)
proof=load(lane/'runs/actual49-13/fixture-owned-runtime/native-proof.json')
assert proof['status']=='FAIL' and proof['pointerPairs']==4
before=load(lane/'runs/actual49-13/pins-before.json');after=load(lane/'runs/actual49-13/pins-after.json')
assert before==after and len(before['runtimeEntries'])==97
for entry in before['runtimeEntries']:assert sha(entry['path'])==entry['sha256Bytes']
actual=Path(before['runtimeEntries'][1]['path']).as_posix().lower()
for name,origin in proof['classOrigins'].items():assert Path(origin.removeprefix('file:/')).as_posix().lower().replace('%20',' ')==actual,(name,origin)
for p in sorted(safe(receipt.parent).iterdir()):
    if p.is_file():artifacts.append(row(receipt.parent/p.name))
data={'status':'PARTIAL_ACTUAL49_ACCEPTANCE_WITH_FAILURES','actualSnapshot':49,'runtimeEntries':97,'productionOverrides':0,
      'requestedScopeFullyAccepted':False,'initialFullscreenAccepted':False,'stableOriginalFullscreenButtonAccepted':True,
      'nextEpisodeAccepted':False,'realSkyMousePairs':4,'all97PinsBeforeAfter':True,'classOriginsAllActualMainKotlin':True,
      'SMTCAccepted':False,'PiPAccepted':False,'actualMainShellAccepted':False,'realAccountAccepted':False,
      'OSSMTCButtonAccepted':False,'hardwareMouseAccepted':False,'externalF11Accepted':False,
      'closedRuns':runs,'artifacts':artifacts,'excludedWithHashAndReason':excluded,
      'futureRun14Included':False,'sharedGradle':False,'productMutations':False}
safe(receipt).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({**row(receipt),'status':data['status'],'artifacts':len(artifacts),'excluded':len(excluded)},ensure_ascii=False,indent=2))
