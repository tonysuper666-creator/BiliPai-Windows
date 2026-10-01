from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
result=json.loads((LANE/'run-02/result.json').read_bytes())
assert result['passed'] and result['totalBusinessAssertions']==77 and result['productionOverrides']==0
result['historicalFailure']={'run':'run-01','stage':'Profile subprocess log decoding','businessResultAccepted':False,
    'retained':'run-01/runner-failure.json','correction':'Only runner byte capture; original business claims unchanged'}
(LANE/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
registered=[];excluded=[]
for path in sorted(LANE.rglob('*')):
    if not path.is_file() or path.name=='frozen-proof.json':continue
    relative=path.relative_to(LANE).as_posix()
    item={'path':relative,'sha256Bytes':sha(path.read_bytes())}
    if path.suffix in ['.jar','.class'] or 'private-local-fixture' in path.parts or path.name=='private-synthetic-local.mp4':
        item['reason']='Compiled fixture or private synthetic media/temporary Store; not copied into Root raw evidence'
        excluded.append(item)
    else:registered.append(item)
(LANE/'excluded-artifacts.json').write_text(json.dumps(excluded,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
registered=[x for x in registered if x['path']!='excluded-artifacts.json']
registered.append({'path':'excluded-artifacts.json','sha256Bytes':sha((LANE/'excluded-artifacts.json').read_bytes())})
manifest={'actualSnapshot':44,'actualRuntimeEntries':97,'productionOverrides':0,
    'fixtureProductClassIntersection':0,'cpPrePostByteEqual':True,'groups':6,'businessAssertions':77,
    'actualLoadedOrigins':{'image':8,'profile':11,'unique':17},
    'originalBusinessAssertionsPreserved':True,'fixtureOnlyExtraChecks':'CodeSource and loaded class bytes equal actual44 main-kotlin.jar',
    'acceptedRun':'run-02','failedRunRetained':'run-01','scope':'Image/cache policy and Profile physical helpers with private local synthetic media and temporary Stores',
    'notClaimed':result['notClaimed'],'rootCopyRule':'Copy only artifacts listed here; no candidate JAR, class, private media or Store',
    'resultSha256Bytes':sha((LANE/'result.json').read_bytes()),'artifacts':registered,'excludedArtifacts':len(excluded)}
(LANE/'frozen-proof.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifestSha256Bytes':sha((LANE/'frozen-proof.json').read_bytes()),'rows':len(registered),'excluded':len(excluded),
    'resultSha256Bytes':manifest['resultSha256Bytes'],'path':str(LANE/'frozen-proof.json')}))
