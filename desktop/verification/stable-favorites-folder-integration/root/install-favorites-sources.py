from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
def wide(p):return Path('\\\\?\\'+str(p.absolute()))
def read(p):return wide(p).read_bytes()
def digest(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert digest(b)==d,p;return b
lanes=[]
for name,d in [('stable-favorites-parity','9131efcc50ed7129653bb6d8e585eda86a4d8bb95584b31c0ac011822c8efcbe'),
    ('stable-favorites-folder-sheet-parity','df54bc254658fd114a75ec96f5f01776629b02b0623aef7b80c39a44cb3db9d1')]:
    lane=MAIN/'desktop/.local'/name
    m=json.loads(pin(lane/'frozen-handoff.json',d))
    for row in m['artifacts']:
        b=pin(lane/row['path'],row['sha256Bytes']);assert len(b)==row['bytes'],row['path']
    lanes.append((lane,m))
parent,child=[r[0] for r in lanes]
whitelist=json.loads(pin(parent/'install-whitelist.json','2606d3e218eb850c8d9b0657f5dea973b0eeb63c4ead45244557cca4da02e052'))
contract=json.loads(pin(child/'install-contract.json','e403ad9a67e5206730902cffe974f31c43d02ed800f751ab7fd5dc4658df4f15'))
payloads=[]
for row in whitelist['installFiles']:
    payloads.append((row['target'],pin(parent/row['path'],row['sha256Bytes'])))
child_paths=[contract['producer']]+contract['manualPlatformPayloads']
child_rows={r['path']:r for r in lanes[1][1]['artifacts']}
for path in child_paths:
    payloads.append((path.removeprefix('prepared/'),pin(child/path,child_rows[path]['sha256Bytes'])))
assert len(payloads)==8
for target,b in payloads:assert not (REPO/target).exists(),target
registry_path=REPO/'desktop/upstream-sources.json'
before=read(registry_path);registry=json.loads(before)
assert len(registry['sources'])==697
original={r['path']:r for r in registry['sources']}
for row in original.values():
    b=read(REPO/row['path']).decode().replace('\r\n','\n').encode();assert digest(b)==row['sha256'],row['path']
changes=[]
def merge(path,d,mode,feature):
    b=read(REPO/path).decode().replace('\r\n','\n').encode();assert digest(b)==d,path
    if path in original:
        row=original[path];assert row['sha256']==d,path
        if feature not in row['features']:row['features'].append(feature)
        changes.append(dict(path=path,operation='merge',preservedMode=row['mode']))
    else:
        row=dict(path=path,sha256=d,features=[feature],mode=mode)
        original[path]=row;registry['sources'].append(row);changes.append(dict(path=path,operation='append',mode=mode))
for row in json.loads(read(parent/'prepared/registry-delta.json')):
    merge(row['path'],row['sha256'],row['mode'],'stable-favorites')
for row in contract['originalSourceIdentities']:
    path=row['path'];mode='direct' if any(r['path']==path for r in contract['productionDirectReferences']) else 'selected'
    merge(path,row['sha256LF'],mode,'stable-favorite-folder-sheet')
assert len(registry['sources'])==len(original)
for target,b in payloads:
    p=wide(REPO/target);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
wide(registry_path).write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
report=dict(passed=True,beforeRegistrySha256Bytes=digest(before),sourceIdentities=len(registry['sources']),
    resources=len(registry['resources']),frozenArtifactsVerified=sum(len(x[1]['artifacts']) for x in lanes),
    payloads=[dict(path=p,sha256Bytes=digest(b)) for p,b in payloads],registryOperations=changes,
    generatedReferenceCopied=False,allExistingModesPreserved=True)
wide(HERE/'favorites-source-install.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:report[k] for k in ['passed','sourceIdentities','resources','frozenArtifactsVerified']}))
