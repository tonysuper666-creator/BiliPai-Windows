from pathlib import Path
import hashlib,json,re,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
H=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def row(p,reason=None):
 v=dict(path=p.relative_to(H).as_posix(),sha256Bytes=sha(p),size=len(read(p)))
 if reason:v['reason']=reason
 return v
def dump(p,v):wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert not (H/'frozen-handoff.json').exists()
r1=H/'runs/actual70-01';r2=H/'runs/actual70-02'
for r in (r1,r2):
 result=json.loads(read(r/'runtime-result.json'));receipt=json.loads(read(r/'receipt.json'))
 assert result['snapshot']==70 and result['entries']==101 and result['productionOverrides']==0
 assert receipt['pinsUnchanged'] and receipt['nativeUnchanged'] and receipt['compilePASS']
assert json.loads(read(r1/'runtime-result.json'))['exit']==1
assert json.loads(read(r2/'runtime-result.json'))['exit']==0
log=read(r2/'runtime.log').decode()
assert len(re.findall(r'^PASS ',log,re.M))==21
summary=json.loads(next(l for l in log.splitlines()if l.startswith('{')))
assert summary['snapshot']==70 and summary['groups']==4 and summary['assertions']==21
assert summary['actualNativeCarrier'] and summary['actualStorePartition'] and summary['actualFinalWireHeaders']
assert summary['actualOwnerPublishAckAdopt'] and summary['fullAdaptiveMpdNative'] and not summary['MainShell']
origins=json.loads(read(r2/'class-origins.json'))
assert len(origins)==7 and all(len(v)==1 and v[0]['jar']['sha256Bytes']=='816e0d12b3f27b48cde35cecc8a5e7e4466692f748503d2ecaaa2c79a1159802'for v in origins.values())
accepted=dict(schema=1,actualSnapshot=70,groups=4,assertions=21,productionOverrides=0,actualNativeCarrier=True,
 actualStoreGuestPartition=True,actualFinalEmptyHeaderWire=True,actualOwnerPublishAckAdopt=True,
 sameVersionCarrierReadLeaseTransferred=True,normalCompletedResolverSupported=True,oldOwnerRetirementSafe=True,
 newLoadAndCloseCapabilitiesRevoked=True,completeMpdRepresentations=3,actualVideoAudioDecoding=True,
 warmNativeNoSecondOriginBodyRange=True,strictRuntimeEntries=101,classpathAndNativePinsUnchanged=True,
 failedHistory=[dict(run='actual70-01',kind='fixture premise',reason='HTTP localhost CookieJar correctly rejected; incorrect fixture assertion')],
 unaccepted=['MainShell mount','real account/CDN','cache-error direct-source recovery','operating-system input/window','all EDL/music paths'],
 acceptedRun='runs/actual70-02',contract=row(H/'contract.md'))
dump(H/'acceptance.json',accepted)
excluded=[];raw=[]
for p in sorted(H.rglob('*')):
 if not p.is_file()or p.name=='frozen-handoff.json':continue
 rel=p.relative_to(H).parts
 if 'classes'in rel:excluded.append(row(p,'rebuildable fixture compiler output; never installed'))
 elif 'owned-data'in rel:excluded.append(row(p,'fixture-owned temporary cache/session/task data; exact hashes retained'))
 elif p.name=='excluded-artifacts.json':continue
 else:raw.append(row(p))
dump(H/'excluded-artifacts.json',excluded);raw.append(row(H/'excluded-artifacts.json'))
manifest=dict(schema=1,kind='actual-installed-native-media-carrier-acceptance',actualSnapshot=70,
 snapshotManifestSha='f1e410523c6991a1f588ffc4e1d571f1251c81f6ab2b2d25ed9c947f9be03621',
 orderedCpSha='3e2c79ad73fd69d07025ab12d617d8aa9932cef580ef1046e3550458b8482aba',runtimeEntries=101,
 productionOverrides=0,groups=4,assertions=21,rawCount=len(raw),raw=raw,excludedCount=len(excluded),
 acceptance=row(H/'acceptance.json'),old240Unchanged=True,MainShellAccepted=False)
dump(H/'frozen-handoff.json',manifest)
print(json.dumps(dict(rawCount=len(raw),excludedCount=len(excluded),manifest=row(H/'frozen-handoff.json'))))
