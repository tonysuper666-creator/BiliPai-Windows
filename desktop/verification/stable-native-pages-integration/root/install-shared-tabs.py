from pathlib import Path
import hashlib,json,shutil
main=Path(__file__).resolve().parents[3];lane=main/'desktop/.local/stable-shared-liquid-tabs-parity';repo=main.parent/'BiliPai-v023'
def ext(p):return Path('\\\\?\\'+str(p.absolute()))
def read(p):return ext(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
manifest=read(lane/'frozen-handoff.json');assert sha(manifest)=='1f4281b92252abf686042bde32ec125ee5bbd0e0313b16da3bdfaf0abac5120f'
records=json.loads(manifest)['artifacts']
for row in records:
 b=read(lane/row['path']);assert sha(b)==row['sha256Bytes'] and len(b)==row['byteSize'],row['path']
for target,digest in [('desktop/tools/extract-upstream-shared-liquid-tabs.py','169fdab92c35419522121e012532f936a111137e614cce3374083ea3fb54189c'),
 ('desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopLiquidTabSettings.kt','3f5fcfdce31474bba7deaede2666ea61a1f42e591deecc6483079fa01f8a1a94'),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiquidReadabilityPlatform.kt','889d73e20d9711335de27310f88232f955822579f1cbfe14c6578b3d393fb041')]:
 b=read(lane/'prepared'/target);assert sha(b)==digest
 dest=repo/target;assert not dest.exists();dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(b)
p=repo/'desktop/upstream-sources.json';data=json.loads(p.read_text(encoding='utf-8'));idx={r['path']:r for r in data['sources']};before=len(idx)
for row in json.loads(read(lane/'source-inventory.json'))['sources']:
 digest=sha((repo/row['path']).read_text(encoding='utf-8').replace('\r\n','\n').encode());assert digest==row['sha256LF']
 if row['path'] in idx:
  old=idx[row['path']];assert old['sha256']==digest and old['mode']!='direct',row['path']
  old['features']=list(dict.fromkeys(old['features']+['stable-shared-liquid-tabs']))
 else:data['sources'].append(dict(path=row['path'],sha256=digest,mode='extracted',features=['stable-shared-liquid-tabs']))
p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
report=dict(installed=True,sourceCountBefore=before,sourceCountAfter=len(data['sources']),generatedSources=32,platformFiles=2)
Path(__file__).with_name('shared-tabs-install.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
