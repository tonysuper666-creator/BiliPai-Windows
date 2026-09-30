"""Freeze one raw attempt; keep automatic and visual acceptance distinct."""
from pathlib import Path
import argparse,hashlib,json,os
p=argparse.ArgumentParser();p.add_argument('--attempt',required=True);a=p.parse_args()
assert a.attempt.isalnum()
HERE=Path(__file__).resolve().parent;attempt=HERE/'runs'/a.attempt
def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def load(path):return json.loads(ext(path).read_text(encoding='utf-8'))
accepted=load(attempt/'proof'/'accepted-evidence.json');visual=load(attempt/'proof'/'visual-review.json');trace=load(attempt/'proof'/'trace-conformance.json')
manifest=attempt/('accepted-frozen-manifest.json' if visual['passed'] else 'historical-frozen-manifest.json')
assert not ext(manifest).exists(),'Frozen attempts are immutable'
files=[]
for root,dirs,names in os.walk(ext(attempt)):
    dirs[:]=[d for d in dirs if d!='__pycache__']
    for name in sorted(names):
        path=Path(root)/name;plain=Path(str(path).removeprefix('\\\\?\\'))
        relative=plain.relative_to(HERE).as_posix()
        if path.suffix.lower() in {'.jar','.class','.dll','.exe'}:continue
        if '/cold-roots/' in relative and name!='independent-cold-card-read.json':continue
        files.append({'path':relative,'sha256Bytes':sha(path),'bytes':ext(path).stat().st_size})
files.sort(key=lambda row:row['path'])
value={
 'attempt':a.attempt,'status':'accepted-fixture-ui' if visual['passed'] else 'historical-automatic-pass-visual-failure',
 'finalFixtureUiAcceptance':bool(accepted['passed'] and visual['passed'] and trace['passed']),
 'productManifestSha256Bytes':accepted['productManifestSha256Bytes'],
 'orderedRuntimeCpSha256Bytes':accepted['orderedRuntimeCpSha256Bytes'],
 'fixtureSources':2,'productionOverrides':accepted['productionOverrides'],
 'actualLoadedClasses':accepted['actualLoadedClasses'],'orderedRuntimeEntries':len(accepted['orderedDependencies']),
 'cells':accepted['matrixCells'],'actualPointerPairs':accepted['pointerPairs'],'screenshots':accepted['uiScreenshots'],
 'independentColdJvms':len(accepted['independentSeedJvms'])+len(accepted['independentColdCardReaderJvms']),
 'rawCacheEqualityChecks':sum(row['checkpointCacheEqualsAllCount'] for row in trace['checks']),
 'automaticChecksPassed':accepted['passed'],'visualReviewPassed':visual['passed'],
 'wholeShellExecuted':False,'nativeHWND':False,'packageAcceptance':False,'realAccountOrSocket':False,'sharedGradle':False,
 'privateAccountAndStoreDiskExcluded':True,'artifactCount':len(files),'files':files}
ext(manifest).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
for row in files:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
print(json.dumps({'manifest':str(manifest),'sha256Bytes':sha(manifest),'artifacts':len(files),'allBytesVerified':True},ensure_ascii=False))
