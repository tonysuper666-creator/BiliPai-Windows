from pathlib import Path
import json, hashlib, subprocess, shutil
base=Path(__file__).resolve().parents[1]/'stable-weekly-series-parity'
repo=base.parents[3]/'BiliPai-v023'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
plan=base/'handoff-final/install-plan.json'
assert sha(plan)=='dea8f80fd5a62f3496a7c4b4c300162950295f825506178738f72562a1265a16'
data=json.loads(plan.read_text(encoding='utf-8'))
for row in data['copyPayload']+data['patches']:
    assert sha(base/row['path'])==row['sha256Bytes'],row['path']
for row in data['patches']:
    subprocess.run(['git','apply','--check',str(base/row['path'])],cwd=repo,check=True)
for row in data['copyPayload']:
    dest=repo/row['target']; assert not dest.exists(),dest
    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(base/row['path'],dest)
for row in data['patches']:
    subprocess.run(['git','apply',str(base/row['path'])],cwd=repo,check=True)
manifest=repo/'desktop/upstream-sources.json'
current=json.loads(manifest.read_text(encoding='utf-8')); sources={r['path']:r for r in current['sources']}
delta=json.loads((base/'handoff-final/source-inventory-delta.json').read_text(encoding='utf-8'))
before=len(sources)
for r in delta['records']:
    assert hashlib.sha256((repo/r['path']).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()==r['sha256']
    if r['path'] in sources:
        old=sources[r['path']];assert old['sha256']==r['sha256']
        old['features']=list(dict.fromkeys(old['features']+r['features']))
    else: current['sources'].append({k:v for k,v in r.items() if k not in ('operation','producer')})
manifest.write_text(json.dumps(current,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'installed':True,'sourcesBefore':before,'sourcesAfter':len(current['sources'])}))
