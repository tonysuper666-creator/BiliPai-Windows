from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=REPO/'desktop/.local/stable-profile-main-parity';OUT=HERE/'profile-main-install'
if OUT.exists():assert not any(OUT.iterdir())
else:OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
manifest=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(manifest)=='5d4ce31834b9bb34c09fca2bd22ea092f24f504a1ff74f49cfb1aaa6ab22ebe7'
for r in json.loads(manifest)['artifacts']:assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
contract=json.loads((LANE/'install-contract.json').read_text(encoding='utf-8'));payloads=[]
for r in contract['payloads']:
 b=wide(r['source']).read_bytes();assert sha(b)==r['sha256Bytes'];p=REPO/r['target']
 if p.exists():assert p.read_bytes()==b
 else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
 payloads.append(r)
p=REPO/'desktop/upstream-sources.json';raw=p.read_bytes();registry=json.loads(raw);count=len(registry['sources']);index={r['path']:r for r in registry['sources']};new=[];merged=[]
recipe=json.loads((LANE/'registry-merge-recipe.json').read_text(encoding='utf-8'));assert sha(raw)==recipe['observedRegistrySha256Bytes']and count==recipe['observedRegistryRows']
for r in recipe['records']:
 if r['referenceOnly']:continue
 path=r['path'];assert sha((REPO/path).read_bytes().replace(b'\r\n',b'\n'))==r['sha256']
 if path in index:
  current=index[path];assert current['sha256']==r['sha256']and current['mode']==r['existingMode']
  for f in r['features']:
   if f not in current['features']:current['features'].append(f)
  merged.append(path)
 else:
  row={k:r[k]for k in ('path','sha256','mode','features')};registry['sources'].append(row);index[path]=row;new.append(path)
assert len(new)==19 and len(merged)==3
p.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
p=REPO/'desktop/build.gradle.kts';text=p.read_text(encoding='utf-8');snippet=(LANE/'gradle-tasks.snippet.kts').read_text(encoding='utf-8');assert 'val extractOriginalProfileMain by'not in text
marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1;p.write_text(text.replace(marker,snippet+'\n'+marker),encoding='utf-8',newline='\n')
report=dict(payloads=payloads,newOriginalIdentities=new,featureMerges=merged,referenceOnlyIdentitiesNotMerged=[r['path']for r in recipe['records']if r['referenceOnly']],sourceCountBefore=count,sourceCountAfter=len(registry['sources']),preparedManifestSHA256=sha(manifest),originalProfileMounted=False,requiredAuthorizationThemeFileMediaBoundariesPending=True)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(payloads=len(payloads),sourceCount=len(registry['sources']),profileMounted=False)))
