from pathlib import Path
import hashlib,json,re
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-home-root-route-assembly-parity';OUT=HERE/'original-root-navigation-install46';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
raw=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='787815173a14836f7c6d3d8c7577318dbd74b83bb90ca56063a00e51cfdade04';frozen=json.loads(raw);assert len(frozen['artifacts'])==87
for r in frozen['artifacts']:assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
assert (MAIN/'desktop/.local/stable-product-snapshot-45/manifest.json').exists()
contract=json.loads((LANE/'install-whitelist.json').read_bytes());pending={};hunks=[]
for row in contract['newPayloads']:
 data=wide(LANE/row['source']).read_bytes();assert sha(data.replace(b'\r\n',b'\n'))==row['sha256LF'];target=REPO/row['target'];assert not target.exists();pending[target]=data
lines=(LANE/'existing-hunks.patch').read_text(encoding='utf-8').splitlines(keepends=True);path=None;i=0
while i<len(lines):
 line=lines[i]
 if line.startswith('+++ b/'):path=line[len('+++ b/'):].strip();i+=1;continue
 if line.startswith('@@ '):
  header=re.match(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@',line);assert header
  before=[];after=[];i+=1
  while i<len(lines)and not lines[i].startswith(('@@ ','--- a/')):
   l=lines[i];assert l[:1]in [' ','+','-'],l
   if l[:1]in [' ','-']:before.append(l[1:])
   if l[:1]in [' ','+']:after.append(l[1:])
   i+=1
  assert len(before)==int(header[2]or'1')and len(after)==int(header[4]or'1')
  hunks.append(dict(path=path,before=''.join(before),after=''.join(after)));continue
 i+=1
for row in contract['existingTargets']:
 p=REPO/row['path'];data=p.read_bytes().decode().replace('\r\n','\n');assert sha(data.encode())==row['baseSha256LF'],row['path']
 for h in [h for h in hunks if h['path']==row['path']]:
  assert data.count(h['before'])==1,(row['path'],h['before']);data=data.replace(h['before'],h['after'],1)
 assert sha(data.encode())==row['desiredSha256LF'],row['path'];pending[p]=data.encode()
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(reg.read_bytes());assert len(registry['sources'])==931
inventory=json.loads((LANE/'source-inventory.json').read_bytes());newCount=0
for row in inventory['rows']:
 assert sha((REPO/row['path']).read_bytes().replace(b'\r\n',b'\n'))==row['sha256']
 existing=next((r for r in registry['sources']if r['path']==row['path']),None)
 if row['operation']=='append-new-identity':
  assert existing is None;registry['sources'].append({k:row[k]for k in ['path','sha256','features','mode']});newCount+=1
 else:
  assert existing and existing['sha256']==row['sha256'];assert existing['mode']==row['mode'],row['path']
  for feature in row['features']:
   if feature not in existing['features']:existing['features'].append(feature)
assert newCount==4 and len(registry['sources'])==935;pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts';text=gradle.read_text(encoding='utf-8');marker='val extractNavigation3Host by tasks.registering(Exec::class) {';assert text.count(marker)==1
snippet=(LANE/'GRADLE-SNIPPET.txt').read_text(encoding='utf-8');assert 'val extractOriginalRootHomeNavigation 'not in text;pending[gradle]=text.replace(marker,snippet+'\n'+marker,1).encode()
OUT.mkdir()
for target,data in pending.items():
 if target.exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
(OUT/'installed.json').write_text(json.dumps(dict(payloads=contract['newPayloads'],existingHunks=hunks,newOriginalIdentities=4,sourceCountBefore=931,sourceCountAfter=935,newRuntimeArtifacts=0,concreteRootMainHostCompiledPending=True,rootMounted=False,fullMainHostPaletteCapturedGate=True,newExeDeployed=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(applied=True,newPayloads=7,exactExistingHunks=len(hunks),sourceCount=935,rootMounted=False)))
