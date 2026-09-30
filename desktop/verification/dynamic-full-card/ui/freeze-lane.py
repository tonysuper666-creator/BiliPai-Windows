"""Pin final raw acceptance and every retained history file, excluding binaries/stores."""
from pathlib import Path
import argparse,hashlib,json,os
p=argparse.ArgumentParser();p.add_argument('--final-attempt',required=True);a=p.parse_args()
assert a.final_attempt.isalnum()
HERE=Path(__file__).resolve().parent
def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def load(path):return json.loads(ext(path).read_text(encoding='utf-8'))
def verify(value):
    for row in value['files']:
        assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
        assert ext(HERE/row['path']).stat().st_size==row['bytes'],row['path']
manifest=HERE/'frozen-handoff.json';assert not ext(manifest).exists(),'Do not overwrite frozen lane'
attempt=HERE/'runs'/a.final_attempt
accepted_path=attempt/'accepted-frozen-manifest.json';accepted=load(accepted_path)
assert accepted['finalFixtureUiAcceptance'] and accepted['visualReviewPassed'] and accepted['automaticChecksPassed']
assert accepted['productionOverrides']==0
assert accepted['actualPointerPairs']==76 and accepted['rawCacheEqualityChecks']==24
assert accepted['cells']==4 and accepted['screenshots']==32 and accepted['independentColdJvms']==8
compiled=load(attempt/'compile-evidence.json');current_sources=load(HERE/'fixture-source-identities.json')
assert compiled['fixtureSources']==current_sources['files']
for row in current_sources['files']:assert sha(HERE/row['path'])==row['sha256Bytes']
assert sha(HERE/'runner.py')==sha(attempt/'frozen-fixture-sources'/'runner.py')
cohorts=[]
for path in sorted((HERE/'runs').glob('*/?*frozen-manifest.json')):
    value=load(path);verify(value)
    cohorts.append({'path':path.relative_to(HERE).as_posix(),'sha256Bytes':sha(path),'status':value['status'],'artifacts':len(value['files'])})
files=[]
for root,dirs,names in os.walk(ext(HERE)):
    dirs[:]=[d for d in dirs if d!='__pycache__']
    for name in sorted(names):
        path=Path(root)/name;plain=Path(str(path).removeprefix('\\\\?\\'))
        relative=plain.relative_to(HERE).as_posix()
        if path.suffix.lower() in {'.jar','.class','.dll','.exe'}:continue
        if '/cold-roots/' in relative and name!='independent-cold-card-read.json':continue
        files.append({'path':relative,'sha256Bytes':sha(path),'bytes':ext(path).stat().st_size})
files.sort(key=lambda row:row['path'])
value={
 'finalAttempt':a.final_attempt,'status':'actual-compiled-product-fixture-ui-pass','passed':True,
 'productManifestSha256Bytes':accepted['productManifestSha256Bytes'],
 'orderedRuntimeCpSha256Bytes':accepted['orderedRuntimeCpSha256Bytes'],
 'acceptedAttemptManifestSha256Bytes':sha(accepted_path),'acceptedEvidenceSha256Bytes':sha(attempt/'proof'/'accepted-evidence.json'),
 'fixtureSources':2,'productionOverrides':0,'actualLoadedClasses':accepted['actualLoadedClasses'],
 'exactOrderedRuntimeEntries':accepted['orderedRuntimeEntries'],'finalCells':4,'finalPointerPairs':76,'finalScreenshots':32,
 'finalIndependentColdJvms':8,'finalRawCacheEqualityChecks':24,
 'actualCommunityReadyAndOriginalFullCards':True,'actualRootSessionRegistryBindings':True,
 'fixtureParentMatchesRootMaterial3Surface':True,'automaticAndReviewedVisualChecksPassed':True,
 'wholeShellExecuted':False,'nativeHWND':False,'packageEXEAcceptance':False,'realAccountOrSocket':False,
 'sharedGradleWritten':False,'completeDynamicDetailEditorParity':False,
 'privateSyntheticAccountStoreDisksAndBinariesExcluded':True,'cohorts':cohorts,
 'artifactCount':len(files),'files':files}
ext(manifest).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
verify(value)
print(json.dumps({'manifest':str(manifest),'sha256Bytes':sha(manifest),'artifacts':len(files),
 'acceptedEvidenceSha256Bytes':value['acceptedEvidenceSha256Bytes'],'allBytesVerified':True},ensure_ascii=False))
