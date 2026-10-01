from pathlib import Path
import hashlib,json,re
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-home-concrete-mount-parity';OUT=HERE/'concrete-root-install48'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def lf(p):return read(p).replace(b'\r\n',b'\n')
raw=read(LANE/'frozen-handoff.json');assert sha(raw)=='2c6919d962ede9437e09e1094e88f7385bb28e7565f9a72ff56d3abe692086f4'
frozen=json.loads(raw);assert len(frozen['artifacts'])==108
for row in frozen['artifacts']:assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'install-whitelist.json');assert sha(raw)=='d06e268bcc40587390d76c227a8a0f8d99c1a6a90cb088a51f2ee8ebe01591f4'
contract=json.loads(raw);assert len(contract['newManualPayloads'])==4 and len(contract['existingExactHunkTargets'])==12
pending={};hunks=[]
for row in contract['newManualPayloads']:
 data=lf(LANE/row['source']);assert sha(data)==row['sha256LF'];target=REPO/row['target'];assert not wide(target).exists();pending[target]=data
for patch in contract['patchOrder']:
 lines=lf(LANE/patch).decode('utf-8').splitlines(keepends=True);path=None;i=0
 while i<len(lines):
  line=lines[i]
  if line.startswith('+++ b/'):path=line[6:].strip();i+=1;continue
  if line.startswith('@@ '):
   header=re.match(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@',line);assert header
   before=[];after=[];i+=1
   while i<len(lines) and not lines[i].startswith(('@@ ','--- a/')):
    line=lines[i];assert line[:1] in [' ','+','-'],line
    if line[:1] in [' ','-']:before.append(line[1:])
    if line[:1] in [' ','+']:after.append(line[1:])
    i+=1
   assert len(before)==int(header[2] or '1') and len(after)==int(header[4] or '1')
   hunks.append(dict(path=path,before=''.join(before),after=''.join(after),patch=patch));continue
  i+=1
assert set(h['path'] for h in hunks)==set(r['path'] for r in contract['existingExactHunkTargets'])
for row in contract['existingExactHunkTargets']:
 target=REPO/row['path'];data=lf(target).decode('utf-8');assert sha(data.encode())==row['baseSha256LF'],row['path']
 for hunk in (h for h in hunks if h['path']==row['path']):
  assert data.count(hunk['before'])==1,(row['path'],hunk['before']);data=data.replace(hunk['before'],hunk['after'],1)
 assert sha(data.encode())==row['desiredSha256LF'],row['path'];pending[target]=data.encode()
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==938
inventory=json.loads(read(LANE/contract['originalSourceDelta']));newCount=0
for row in inventory['rows']:
 assert sha(lf(REPO/row['path']))==row['sha256'],row['path']
 existing=next((r for r in registry['sources'] if r['path']==row['path']),None)
 if row['operation']=='append-new-identity':
  assert existing is None;registry['sources'].append({k:row[k] for k in ['path','sha256','features','mode']});newCount+=1
 else:
  assert existing and existing['sha256']==row['sha256'] and existing['mode']==row['mode'],row['path']
  for feature in row['features']:
   if feature not in existing['features']:existing['features'].append(feature)
assert newCount==1 and len(registry['sources'])==939
pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
# Separately installed Offline3146 manuals remain the sole task-player producer.
for name,pin in [('DesktopOfflineTaskPlayerBinding.kt','29a47115d6eb7e340c38907ea5b12766ad70e2c3877c504341d4a2fe7d7e473c'),('DesktopOfflineTaskPlayerHost.kt','fac05aaeddf932c741dea972d41ba9423927cf190e3bf4dd44b14b21065d9d74')]:
 assert sha(read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/name))==pin
OUT.mkdir()
for target,data in pending.items():
 if wide(target).exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(read(target))
 wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(data)
report=dict(applied=True,newManualPayloads=4,exactHunks=len(hunks),existingSourceFamilies=12,newOriginalIdentities=1,sourceRegistryCount=939,newGradleTasks=0,newRuntimeArtifacts=0,wholeBuildPending=True,actualWindowAcceptancePending=True,newExeDeployed=False,hunks=hunks,payloads=contract['newManualPayloads'])
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['hunks','payloads']}))
