from pathlib import Path
import hashlib,json,re
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=HERE/'palette-home-retainer-install43'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def verified_lane(name,digest,count):
 lane=MAIN/'desktop/.local'/name;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==digest
 doc=json.loads(raw);rows=doc.get('artifacts',doc.get('rawArtifacts'));assert len(rows)==count
 for r in rows:assert sha(wide(lane/r['path']).read_bytes())==r['sha256Bytes'],r['path']
 return lane
palette=verified_lane('stable-wallpaper-palette-parity','9ac7dea50e721b6a445ed2103c15a9c433782a3c92943d4abd0162cb2e19216f',43)
home=verified_lane('stable-home-root-mount-parity','4540a111a30feda4e34f5b4d1e598554622db22c52a014c6a5ee0f93ffc6dfc1',47)
pending=[];receipt=[]
for r in json.loads(wide(palette/'install-contract.json').read_bytes())['payloads']:
 data=wide(r['source']).read_bytes();assert sha(data)==r['sha256Bytes'];target=REPO/r['target'];assert not target.exists()
 pending.append((target,data));receipt.append(dict(target=r['target'],sha256Bytes=sha(data),new=True))
for r in json.loads(wide(home/'install-whitelist.json').read_bytes())['installOnly']:
 data=wide(home/r['source']).read_bytes();assert sha(data)==r['sha256Bytes'];target=REPO/r['destination']
 assert target.exists()==r['destination'].endswith('DesktopTodayWatchRepository.kt')
 pending.append((target,data));receipt.append(dict(target=r['destination'],sha256Bytes=sha(data),new=not target.exists()))
def exact_patch(path,patch_path,metadata,desired_key):
 target=REPO/path;before=target.read_bytes().replace(b'\r\n',b'\n');m=json.loads(wide(home/metadata).read_bytes());assert sha(before)==m['baseLfSha256'],path
 lines=before.decode().splitlines(keepends=True);patch=wide(home/patch_path).read_text(encoding='utf-8').splitlines(keepends=True)
 result=[];cursor=0;i=0;hunks=0
 while i<len(patch):
  if not patch[i].startswith('@@ '):i+=1;continue
  match=re.match(r'@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@',patch[i]);assert match
  start=int(match[1])-1;assert start>=cursor;result.extend(lines[cursor:start]);cursor=start;i+=1;hunks+=1
  while i<len(patch) and not patch[i].startswith('@@ '):
   line=patch[i];assert line[0] in ' +-'
   if line[0] in ' -':assert lines[cursor]==line[1:],(path,cursor);cursor+=1
   if line[0] in ' +':result.append(line[1:])
   i+=1
 result.extend(lines[cursor:]);after=''.join(result).encode();assert sha(after)==m[desired_key],path
 pending.append((target,after));receipt.append(dict(target=path,baseLF=sha(before),desiredLF=sha(after),semanticHunks=hunks))
exact_patch('desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt','patches/runtime-retirement.patch','patches/runtime-base-desired.json','desiredLfSha256')
exact_patch('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt','patches/shell-lifetime-only.patch','patches/shell-base-desired.json','lifetimeDesiredLfSha256')
reg=REPO/'desktop/upstream-sources.json';raw=reg.read_bytes();registry=json.loads(raw);recipe=json.loads(wide(palette/'registry-merge-recipe.json').read_bytes())
assert len(registry['sources'])==926 and sha(raw)==recipe['observedRegistrySha256Bytes']
assert len(recipe['records'])==1
for r in recipe['records']:
 assert all(old['path']!=r['path'] for old in registry['sources']);assert sha((REPO/r['path']).read_bytes().replace(b'\r\n',b'\n'))==r['sha256']
 registry['sources'].append({k:r[k]for k in ('path','sha256','mode','features')})
pending.append((reg,(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()))
gradle=REPO/'desktop/build.gradle.kts';text=gradle.read_text(encoding='utf-8');marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {'
assert text.count(marker)==1 and 'val extractOriginalWallpaperPalette 'not in text
snippet=wide(palette/'gradle-tasks.snippet.kts').read_text(encoding='utf-8');pending.append((gradle,text.replace(marker,snippet+'\n'+marker,1).encode()))
OUT.mkdir()
for target,data in pending:
 if target.exists():
  save=wide(OUT/'baseline'/target.relative_to(REPO));save.parent.mkdir(parents=True,exist_ok=True);save.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
(OUT/'installed.json').write_text(json.dumps(dict(payloads=receipt,originalCountBefore=926,originalCountAfter=927,rootMounted=False,oldPlannerRemoved=True,newDependencies=0),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(applied=True,payloads=8,narrowPatches=2,sourceCount=927,rootMounted=False)))
