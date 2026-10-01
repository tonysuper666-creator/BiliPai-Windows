from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];REPO=BASE.parent/'BiliPai-v023'
LANE=REPO/'desktop/.local/stable-build-repair'
def sha(raw):return hashlib.sha256(raw).hexdigest()
def lf(path):return Path(path).read_bytes().replace(b'\r\n',b'\n')
payloads=[
('extract-upstream-diagnostics.py','b8a4a4ee08eceee4edf0af28c5d86087b03e7455e9d297dbce5dd40a0d3c518f','5801bc6b79824caa5c01577eedf1a54997b6317f822c5a2d7e6847c5b7f3a29c'),
('extract-upstream-dynamic-tabs.py','2d69ecf0766b7c9c7207f5cf3e8f30396e1364aaae7ee348591c3abaeb87383d','bca1f195aa5f5fd202cffb759ef208b9715e7f5a834363cce475db48f181f3ab'),
('extract-upstream-plugins.py','1bc550ca4b2ddd145710cb6601e530b6040d620ece7b094a8f2c23f4b53af310','4cfc9d0f889f4334c292cb12b2a0e5f7441c73df2999bf7504726a058a3b2756')]
for name,old,new in payloads:
    assert sha(lf(REPO/'desktop/tools'/name))==old,name
    assert sha(lf(LANE/'prepared/desktop/tools'/name))==new,name
registry_path=REPO/'desktop/upstream-sources.json';registry=json.loads(lf(registry_path))
rows=json.loads(lf(LANE/'registry-append-rows.json'))
for row in rows:
    assert not any(item['path']==row['path'] for item in registry['sources'])
    blob=subprocess.check_output(['git','show',registry['upstreamCommit']+':'+row['path']],cwd=REPO)
    assert blob==lf(REPO/row['path']) and sha(blob)==row['sha256']
patch=str(LANE/'gradle-input-only.patch')
subprocess.run(['git','-c','core.longpaths=true','apply','--check',patch],cwd=REPO,check=True)
for name,old,new in payloads:(REPO/'desktop/tools'/name).write_bytes(lf(LANE/'prepared/desktop/tools'/name))
registry['sources'].extend(rows)
registry_path.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
subprocess.run(['git','-c','core.longpaths=true','apply',patch],cwd=REPO,check=True)
receipt=dict(schema='stable-build-repair-candidate-install-v1',candidateInstalled=True,mainInstalled=False,
    payloads=[dict(path='desktop/tools/'+n,sha256Lf=h) for n,_,h in payloads],sourceRows=rows,
    registryCount=len(registry['sources']),preparedAgentEvidenceAwaitingFreeze=True,
    wholeStableCompiled=False,stableRuntimeAccepted=False)
(HERE/'build-repair-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(payloads=3,registryCount=len(registry['sources']))))
