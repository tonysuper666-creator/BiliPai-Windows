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
inputs=json.loads(safe(lane/'inputs.json').read_text(encoding='utf-8'))
for r in inputs['orderedCP']:assert sha(r['path'])==r['sha256Bytes']
assert 'actualLazyWinRtLoad=PASS' in safe(lane/'winrt-load.log').read_text(encoding='utf-8')
assert 'actualRoInitialize=0' in safe(lane/'callback-registration.log').read_text(encoding='utf-8')
assert 'actualComDelegateRegistration=PASS' in safe(lane/'callback-registration.log').read_text(encoding='utf-8')
receipt=lane/'frozen-handoff.json';assert not safe(receipt).exists()
artifacts=[];excluded=[]
for ep in sorted(safe(lane).rglob('*')):
    if not ep.is_file():continue
    p=lane/ep.relative_to(safe(lane));item=row(p)
    if p.suffix=='.class':excluded.append({**item,'reason':'Rebuildable headless diagnostic class; exact source and hash retained.'})
    else:artifacts.append(item)
data={'status':'READONLY_DIAGNOSTIC_AND_UNACCEPTED_ROOT_ADAPTER_PROPOSAL','diagnosticOnly':True,'actualSnapshot':50,'runtimeEntries':97,'productionOverrides':0,'sameActualLazyWinRtLoad':True,'sameActualMtaInitialize':True,'sameActualCallbackRegistration':True,'trueSMTCGetForWindowAccepted':False,'normalWindowLayoutAccepted':False,'rootCauseNativeCppLineProven':False,'UIInput':False,'WindowCreated':False,'CandidateMutations':False,'sharedGradle':False,'all97PinsBeforeAfter':True,'artifacts':artifacts,'excludedWithHashAndReason':excluded}
safe(receipt).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({**row(receipt),'artifacts':len(artifacts),'excluded':len(excluded)},ensure_ascii=False,indent=2))
