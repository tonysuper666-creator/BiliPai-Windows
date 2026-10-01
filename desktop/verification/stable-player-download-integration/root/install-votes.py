from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-vote-parity'
def lf(path): return Path(path).read_bytes().replace(b'\r\n',b'\n')
def sha(raw): return hashlib.sha256(raw).hexdigest()
def git(*args): return subprocess.check_output(['git','-c','core.longpaths=true',*args],cwd=REPO)
raw=(LANE/'install-plan.json').read_bytes()
assert sha(raw)=='7429d4cfa9ff22e0ea6b2cac5264d2ee6afe4d11c1dd305486c9ebb7e5c9c99d'
plan=json.loads(raw)
for row in plan['copy']:
    assert sha((LANE/row['source']).read_bytes())==row['sha256Bytes']
    assert not (REPO/row['target']).exists()
patches=[]
for row in plan['patches']:
    source=LANE/row['source'];assert sha(source.read_bytes())==row['sha256Bytes'];patches.append(str(source))
git('apply','--check',*patches)
raw=(LANE/'source-inventory-delta.json').read_bytes()
assert sha(raw)=='57ce84f3ccaf76a04ce4c33239e251288f109127a9018d0aa6dd918a61dbaa24'
delta=json.loads(raw);manifest=json.loads(lf(REPO/'desktop/upstream-sources.json'))
assert manifest['upstreamCommit']==delta['upstreamCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(manifest['sources'])==628
by_path={r['path']:r for r in manifest['sources']};assert len(by_path)==628
for row in delta['records']:
    path=row['path'];source=lf(REPO/path)
    assert sha(source)==row['sha256']
    assert git('show',manifest['upstreamCommit']+':'+path).replace(b'\r\n',b'\n')==source
    if row['operation']=='append-new':
        assert path not in by_path
        manifest['sources'].append({k:row[k] for k in ('path','sha256','mode','features')})
    else:
        target=by_path[path];assert target['sha256']==row['sha256'] and target['mode']==row['mode']
        target['features']=list(dict.fromkeys(target['features']+row['features']))
for row in plan['copy']:
    target=REPO/row['target'];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((LANE/row['source']).read_bytes())
git('apply',*patches)
(REPO/'desktop/upstream-sources.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
(HERE/'vote-install.json').write_text(json.dumps(dict(candidateInstalled=True,mainInstalled=False,sourceRegistryCount=len(manifest['sources']),
    installedCopy=plan['copy'],installedPatches=plan['patches'],wholeCompiled=False,rootBindingPending=True,nativePopupAccepted=False),indent=2)+'\n',encoding='utf-8')
print('Installed two payloads/four scoped patches; merged three new fixed source identities.')
