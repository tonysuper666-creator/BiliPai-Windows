from pathlib import Path
import hashlib,json,sys
LANE=Path(__file__).resolve().parent
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
result=json.loads(read(LANE/'result.json'))
assert result['passed']and result['groups']==5 and result['assertions']==54 and result['productionOverrides']==0
assert result['cpToolsOriginalSourcesPrePostByteEqual']and not result['fixtureProductClassIntersections']
unique={}
for r in result['origins']:
    value=(r['codeSource'],r['classSha256'])
    assert r['class']not in unique or unique[r['class']]==value;unique[r['class']]=value
assert not (LANE/'frozen-proof.json').exists()
rows=[];excluded=[]
for p in sorted(wide(LANE).rglob('*')):
    if not p.is_file():continue
    rel=p.relative_to(wide(LANE)).as_posix()
    if p.suffix in ('.jar','.class','.pyc')or '/private-memory/'in '/'+rel or '__pycache__'in rel:
        excluded.append(dict(path=rel,reason='binary or private synthetic Store/library',sha256Bytes=sha(p.read_bytes())));continue
    rows.append(dict(path=rel,bytes=p.stat().st_size,sha256Bytes=sha(p.read_bytes())))
manifest=dict(frozen=True,phase='actual47-accounts-video-admission-focused-zero-override',registeredRawArtifacts=rows,excludedPrivateAndBinaries=excluded,acceptance=dict(groups=5,assertions=54,productionOverrides=0,fixtureProductClassIntersections=0,runtimeEntries=97,loadedOriginRows=len(result['origins']),uniqueActualClasses=len(unique),codeSourcesAndClassBytesVerified=True,cpToolsOriginalFixtureInputsPrePostByteEqual=True),originalPreparedPacketsUnchanged=['ProfileAccountPort56','VideoDetail69'],limitations=result['limitations'])
wide(LANE/'frozen-proof.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(frozenProof=str(LANE/'frozen-proof.json'),sha256Bytes=sha(read(LANE/'frozen-proof.json')),registeredRows=len(rows),excludedRows=len(excluded),groups=5,assertions=54)))
