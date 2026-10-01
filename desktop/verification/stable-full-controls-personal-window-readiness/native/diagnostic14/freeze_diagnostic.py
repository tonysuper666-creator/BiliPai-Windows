"""Freeze the independent read-only JDWP/JDI diagnosis; never label it acceptance."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
lane=Path(__file__).resolve().parent
run=lane/'runs/actual49-14-jdi'
target=lane/'diagnostic14/frozen-handoff.json'
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def row(p):
    b=safe(p).read_bytes()
    return {'path':str(p),'sha256Bytes':hashlib.sha256(b).hexdigest(),'size':len(b)}
def load(p):return json.loads(safe(p).read_text(encoding='utf-8'))
assert not safe(target).exists()
before=load(run/'pins-before.json');after=load(run/'pins-after.json');assert before==after
assert len(before['runtimeEntries'])==97
for item in before['runtimeEntries']:assert row(item['path'])['sha256Bytes']==item['sha256Bytes']
assert row(run/'OfflineNativeFixture.kt')['sha256Bytes']==before['source']['sha256Bytes']
proof=load(run/'fixture-owned-runtime/native-proof.json')
assert proof['diagnosticOnly'] and proof['status']=='DIAGNOSTIC_COMPLETED_NOT_ACCEPTANCE' and proof['pointerPairs']==0
trace=[json.loads(line) for line in safe(run/'fixture-owned-runtime/fullscreen-jdi.jsonl').read_text(encoding='utf-8').splitlines()]
clears=[item for item in trace if item.get('method')=='sun.awt.Win32GraphicsDevice.setFullScreenWindow' and item.get('arguments')==['null'] and any('FullscreenAdapter.componentShown' in f for f in item['frames'])]
assert len(clears)>=2
safe(target.parent).mkdir(parents=True,exist_ok=True)
for source,name in [(lane/'run.py','run.py'),(lane/'freeze_diagnostic.py','freeze_diagnostic.py'),(lane/'jdi/FullscreenTrace.java','FullscreenTrace.java')]:
    safe(target.parent/name).write_bytes(safe(source).read_bytes())
summary=target.parent/'diagnostic-summary.md'
safe(summary).write_text('''# Actual49 independent fullscreen diagnosis

This cohort uses localhost JDWP suspend=y plus Root's JDK JDI breakpoint watcher. It is diagnostic-only and cannot satisfy normal renderer/native acceptance. Product sources/classes and all 97 immutable runtime entries remain unchanged. The watcher reads arguments/stack frames and immediately resumes the event thread; it invokes no debuggee methods and injects no input.

At the final unwanted clear, Win32GraphicsDevice.setFullScreenWindow receives null via Skiko PlatformOperations.setFullscreen, HardwareLayer.setFullscreen and FullscreenAdapter.componentShown. The event is not an original false request or a Window.dispose call. Complete frames, fixture placement events, geometry and native/classpath hashes are retained. Watcher and fixture timestamps have independent origins and are not compared as a single clock.

Raw geometry after entry is recorded separately for the actual Main, MPV anchor/native Canvas, and owned ComposeDialog. Main native client is 878×564 while the Canvas/dialog native client is 1317×846 at defaultTransform1.5. These are observed raw GetWindowRect/GetClientRect and AWT fields; screenshot-helper scaling is not inferred and no DPI/layout success is claimed.

Root is preparing the minimal platform readiness adaptation: deliver the latest original pending fullscreen request only after the actual foreground Window has processed its shown event, through the existing Root setter. Readiness must invalidate on instance disposal/recreation, and owner/source/epoch admission must reject stale callbacks. That future change is not present or tested here. Original fullscreen algorithm and historical closed01–13 failures remain unchanged.
''',encoding='utf-8',newline='\n')
artifacts=[];excluded=[]
for extended in sorted(safe(run).rglob('*')):
    if not extended.is_file():continue
    p=run/extended.relative_to(safe(run));relative=p.relative_to(run);item=row(p)
    if p.suffix in {'.class','.jar','.kotlin_module'}:
        excluded.append({**item,'reason':'Rebuildable fixture compiler output; source/compiler arguments and output hashes retained.'})
    elif p.suffix=='.png':
        excluded.append({**item,'reason':'Fixture-owned Skia backing hash only; no unrelated screen capture.'})
    elif 'fixture-owned-runtime' in relative.parts and p.name not in {'native-proof.json','failure-state.json','fullscreen-jdi.jsonl'} and not p.name.startswith('owned-geometry-'):
        excluded.append({**item,'reason':'Disposable synthetic fixture task/Store/media data, not user or private-account data.'})
    else:artifacts.append(item)
for p in sorted(safe(target.parent).iterdir()):
    if p.is_file():artifacts.append(row(target.parent/p.name))
for p in safe(lane/'jdi/classes').rglob('*'):
    if p.is_file():excluded.append({**row(lane/'jdi/classes'/p.relative_to(safe(lane/'jdi/classes'))),'reason':'Rebuildable Root JDI watcher class; full Java source retained.'})
data={'status':'DIAGNOSTIC_COMPLETED_NOT_ACCEPTANCE','actualSnapshot':49,'runtimeEntries':97,'productionOverrides':0,
      'diagnosticOnly':True,'JDWPHost':'127.0.0.1','JDWPPort':5009,'JDIReadOnly':True,'inputInjected':False,
      'requestedScopeAccepted':False,'all97PinsBeforeAfter':True,'classOriginsAllActualMainKotlin':True,
      'componentShownClearCalls':clears,'geometryAccepted':False,'futureProductFixIncluded':False,
      'closed01To13Receipt':row(lane/'closed01-13/frozen-handoff.json'),
      'artifacts':artifacts,'excludedWithHashAndReason':excluded,'sharedGradle':False}
safe(target).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({**row(target),'status':data['status'],'artifacts':len(artifacts),'excluded':len(excluded)},ensure_ascii=False,indent=2))
