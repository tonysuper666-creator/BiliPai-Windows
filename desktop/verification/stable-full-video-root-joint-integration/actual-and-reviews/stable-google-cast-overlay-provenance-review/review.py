from pathlib import Path
import hashlib,json,subprocess
MAIN=Path('C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai')
CAND=MAIN.parent/'BiliPai-v023'; DESKTOP=CAND/'desktop'
LANE=MAIN/'desktop/.local/stable-video-overlay-windows-platform-parity'
OUT=Path(__file__).parent
def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def dump(v):return (json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode()
def put(p,b):
 d=wide(OUT/p);d.parent.mkdir(parents=True,exist_ok=True)
 if d.exists():raise RuntimeError('Immutable review already exists')
 d.write_bytes(b)
def git(path):return subprocess.check_output(['git','-C',str(CAND),'show','HEAD:'+path])

target='desktop/src/main/kotlin/com/bilipai/desktop/cast/DesktopGoogleCastDialog.kt'
before=read(LANE/'baseline'/target);after=read(CAND/target)
assert sha(before)=='00837b4322e6164ed582fcb560cdeadec7b90ba2330123af63e0dd1b1f29eb4b'
assert sha(lf(after))=='2ccafbe32fcc1493f9adbace00cad0e6b568ef55f3ab93693e56280a584fa56e'
allhunks=read(LANE/'exact-hunks.json');hunks=[h for h in json.loads(allhunks) if h['target']==target]
assert len(hunks)==3
rebuilt=lf(before).decode();actual=lf(after).decode()
for h in hunks:
 assert rebuilt.count(h['before'])==1
 rebuilt=rebuilt.replace(h['before'],h['after'],1)
assert rebuilt==actual
inverse=actual
for h in reversed(hunks):
 assert inverse.count(h['after'])==1
 inverse=inverse.replace(h['after'],h['before'],1)
assert inverse==lf(before).decode()
assert lf(git(target))==lf(before)

manifestRel='desktop/third-party/google-cast-v2/SOURCES.json'
headBytes=git(manifestRel);mainBytes=read(CAND/manifestRel)
mirrorBytes=read(DESKTOP/'resources/common/notices/google-cast-v2/SOURCES.json')
head=json.loads(headBytes);main=json.loads(mainBytes);mirror=json.loads(mirrorBytes)
def audit(manifest):
 rows=[];fail=[]
 for record in manifest['records']:
  path=DESKTOP/record['path'];data=read(path)
  if path.suffix in {'.java','.kt','.py','.txt','.patch','.json','.pom','.proto','.h','.cc'} or path.name=='LICENSE':data=lf(data)
  row={'path':record['path'],'recordedSha256':record['sha256'],'actualSha256':sha(data),'actualSizeNormalized':len(data)}
  rows.append(row)
  if row['recordedSha256']!=row['actualSha256']:fail.append(row)
 return rows,fail
rows,headMismatch=audit(head);mainRows,mainMismatch=audit(main)
assert len(headMismatch)==1 and headMismatch[0]['path']==target[len('desktop/'):]
assert len(mainMismatch) in [0,1]
assert all(x['path']==target[len('desktop/'):] for x in mainMismatch)
nonrecords=lambda m:{k:v for k,v in m.items() if k!='records'}
assert nonrecords(main)==nonrecords(head)==nonrecords(mirror)
headMap={r['path']:r for r in head['records']};mainMap={r['path']:r for r in main['records']}
assert headMap.keys()==mainMap.keys()
recordChanges=[{'path':p,'before':headMap[p],'after':mainMap[p]} for p in headMap if headMap[p]!=mainMap[p]]
assert all(x['path']==target[len('desktop/'):] for x in recordChanges)

for name,data in {'before-dialog.kt':before,'actual-dialog.kt':after,'overlay110-exact-hunks.json':allhunks,
 'overlay110-frozen-handoff.json':read(LANE/'frozen-handoff.json'),
 'head-SOURCES.json':headBytes,'observed-main-SOURCES.json':mainBytes,
 'observed-notices-SOURCES.json':mirrorBytes,
 'source-verifier.py':read(DESKTOP/'tools/verify-google-cast-sources.py')}.items():put('inputs/'+name,data)
receipt={'review':'independent read-only Google Cast Overlay110 provenance delta',
 'blockingCount':0,'dialogHunks':3,'exactForwardReplay':True,'exactInverseReplay':True,
 'baselineDialogSha256Bytes':sha(before),'actualDialogSha256Bytes':sha(after),'actualDialogSha256LF':sha(lf(after)),
 'headInventoryRecordCount':len(rows),'headInventoryMismatch':headMismatch,
 'observedMainInventoryMismatch':mainMismatch,'observedMainRecordChanges':recordChanges,
 'allNonRecordFieldsUnchanged':True,'mirrorNonRecordFieldsEqualMain':True,
 'semantics':'Existing public media overload preserved by same-body private renderer; added internal route-selection callback plus current selection state. Legacy media branch still uses actual publication and existing cast plugin. The three exact hunks do not modify certificates, Cast authentication, TLS/protocol, trust anchors, or source/transport implementations.',
 'mirrorNote':'Main record update/mirror refresh belongs to Root. Observed notices records may be older than main; no mirror acceptance claimed by this receipt.',
 'recordAudit':rows,'scope':{'CandidateMutation':False,'compile':False,'runtime':False,'HTTP':False,'physicalCastDevice':False,'sourceVerifierExecuted':False}}
put('review-receipt.json',dump(receipt))
artifacts=[]
for p in sorted(OUT.rglob('*')):
 if p.is_file():
  b=read(p);artifacts.append({'path':p.relative_to(OUT).as_posix(),'sha256Bytes':sha(b),'size':len(b)})
put('frozen-review.json',dump({'schema':'source-only-review-v1','rawCount':len(artifacts),'blockingCount':0,'artifacts':artifacts}))
print(json.dumps({'manifestSha256':sha(read(OUT/'frozen-review.json')),'receiptSha256':sha(read(OUT/'review-receipt.json')),
 'rawCount':len(artifacts),'records':len(rows),'headMismatch':headMismatch,
 'observedMainMismatch':mainMismatch,'observedMainRecordChanges':recordChanges},ensure_ascii=False,indent=2))
