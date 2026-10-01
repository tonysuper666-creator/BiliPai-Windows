from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=REPO/'desktop/.local/stable-video-share-consent-parity';OUT=HERE/'video-share-consent-install'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
raw=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='5f263de98a14de263168a646a0937ba9e3359fa668a7cec1d126d5be489f3fb9'
frozen=json.loads(raw);assert len(frozen['artifacts'])==87
for row in frozen['artifacts']:assert sha(wide(LANE/row['path']).read_bytes())==row['sha256Bytes'],row['path']
contract=json.loads(wide(LANE/'install-contract.json').read_bytes());assert sha(wide(LANE/'install-contract.json').read_bytes())==frozen['installContractSha256Bytes']
pending=[];records=[]
for row in contract['payloads']:
 data=wide(row['source']).read_bytes();assert sha(data)==row['sha256Bytes'];target=REPO/row['target'];assert not target.exists(),target
 pending.append((target,data));records.append(dict(target=row['target'],sha256Bytes=sha(data),newPayload=True))
ops=json.loads(wide(LANE/'operations-local-hunk.json').read_bytes())
hunks=json.loads(wide(LANE/'native-local-hunks.json').read_bytes())
for row in [dict(target=ops['target'],baseSha256LF=ops['observedBaseSha256LF'],candidateSha256LF=ops['candidateSha256LF'])]+hunks['targets']:
 target=REPO/row['target'];raw=target.read_bytes();text=raw.decode().replace('\r\n','\n');assert sha(text.encode())==row['baseSha256LF'],row['target']
 changes=[ops]if row['target']==ops['target']else [h for h in hunks['rows']if h['target']==row['target']]
 for h in changes:
  assert text.count(h['before'])==1,(row['target'],h['before'][:100])
  if 'beforeSha256LF'in h:assert sha(h['before'].encode())==h['beforeSha256LF'] and sha(h['after'].encode())==h['afterSha256LF']
  text=text.replace(h['before'],h['after'],1)
 data=text.encode();assert sha(data)==row['candidateSha256LF'],row['target']
 pending.append((target,data));records.append(dict(target=row['target'],baseSha256Bytes=sha(raw),baseSha256LF=row['baseSha256LF'],desiredSha256LF=sha(data),exactHunks=len(changes)))
reg=REPO/'desktop/upstream-sources.json';raw=reg.read_bytes();registry=json.loads(raw);recipe=json.loads(wide(LANE/'registry-merge-recipe.json').read_bytes())
assert sha(raw)==recipe['observedRegistrySha256Bytes'] and len(registry['sources'])==recipe['observedRegistryRows']==895
by={r['path']:r for r in registry['sources']};new=[];merged=[]
for row in recipe['records']:
 src=(REPO/row['path']).read_text(encoding='utf-8').replace('\r\n','\n');assert sha(src.encode())==row['sha256']
 if row['path']in by:
  old=by[row['path']];assert old['sha256']==row['sha256'] and row['existingIdentity']
  old['features']=list(dict.fromkeys(old.get('features',[])+row['features']));merged.append(row['path'])
 else:
  assert not row['existingIdentity'];new.append(row['path']);registry['sources'].append(dict(path=row['path'],sha256=row['sha256'],mode=row['mode'],features=row['features'],reason='Original complete share/consent declarations with explicit same-owner Windows effects'))
assert len(new)==7 and len(registry['sources'])==902
pending.append((reg,(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()))
gradle=REPO/'desktop/build.gradle.kts';text=gradle.read_text(encoding='utf-8');marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1 and 'val extractOriginalVideoShareConsent 'not in text
snippet=wide(LANE/'gradle-tasks.snippet.kts').read_text(encoding='utf-8');text=text.replace(marker,snippet+'\n'+marker,1);pending.append((gradle,text.encode()))
OUT.mkdir()
for target,data in pending:
 if target.exists():
  before=wide(OUT/'baseline'/target.relative_to(REPO));before.parent.mkdir(parents=True,exist_ok=True);before.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
report=dict(preparedManifestSHA256='5f263de98a14de263168a646a0937ba9e3359fa668a7cec1d126d5be489f3fb9',payloads=records,newOriginalIdentities=new,featureMerges=merged,sourceCountBefore=895,sourceCountAfter=902,nativeExactHunks=18,rootMounted=False,nativeRebuilt=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(applied=True,payloads=6,exactHunks=19,newSourceIdentities=7,sourceCount=902,nativeRebuilt=False)))
