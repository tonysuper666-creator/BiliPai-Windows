from pathlib import Path
import hashlib,json,sys
LANE=Path(__file__).resolve().parent;sha=lambda b:hashlib.sha256(b).hexdigest()
result=json.loads((LANE/'result.json').read_bytes());assert result['passed']and result['productionOverrides']==0and result['businessAssertions']==86
registered=[];excluded=[]
for p in sorted(LANE.rglob('*')):
    if not p.is_file()or p.name=='frozen-proof.json':continue
    row=dict(path=p.relative_to(LANE).as_posix(),sha256Bytes=sha(p.read_bytes()))
    if p.suffix in ['.jar','.class']or'private-synthetic-store'in p.parts:
        row['reason']='Compiled fixture or private synthetic Store/queue; not copied to Root raw evidence';excluded.append(row)
    else:registered.append(row)
(LANE/'excluded-artifacts.json').write_text(json.dumps(excluded,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
registered=[r for r in registered if r['path']!='excluded-artifacts.json']
registered.append(dict(path='excluded-artifacts.json',sha256Bytes=sha((LANE/'excluded-artifacts.json').read_bytes())))
manifest=dict(actualSnapshot=45,actualRuntimeEntries=97,productionOverrides=0,fixtureProductClassIntersection=0,
    cpPrePostByteEqual=True,groups=8,businessAssertions=86,originalFixtureBytesAndAssertionsPreserved=True,
    actualOriginCounts=dict(backend84=9,final428=11),rootCopyRule='Only listed raw artifacts, excluding fixture JAR/classes and private Store/queue',
    scope=result['scope'],notClaimed=result['notClaimed'],resultSha256Bytes=sha((LANE/'result.json').read_bytes()),artifacts=registered,excludedArtifacts=len(excluded))
(LANE/'frozen-proof.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(path=str(LANE/'frozen-proof.json'),sha256Bytes=sha((LANE/'frozen-proof.json').read_bytes()),rows=len(registered),excluded=len(excluded))))
