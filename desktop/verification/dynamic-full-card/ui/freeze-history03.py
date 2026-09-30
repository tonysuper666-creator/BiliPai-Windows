"""Freeze the historical automatic pass and visual failure without private stores."""
from pathlib import Path
import hashlib,json,os
HERE=Path(__file__).resolve().parent
ATTEMPT=HERE/'runs'/'03'
def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def save(path,value):ext(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
manifest=ATTEMPT/'historical-frozen-manifest.json'
assert not ext(manifest).exists(),'Historical attempt bytes are immutable'
files=[]
for root,dirs,names in os.walk(ext(ATTEMPT)):
    dirs[:]=[d for d in dirs if d!='__pycache__']
    for name in sorted(names):
        path=Path(root)/name
        plain=Path(str(path).removeprefix('\\\\?\\'))
        relative=plain.relative_to(HERE).as_posix()
        if path.suffix.lower() in {'.jar','.class','.dll','.exe'}:continue
        if '/cold-roots/' in relative and name!='independent-cold-card-read.json':continue
        files.append({'path':relative,'sha256Bytes':sha(path),'bytes':ext(path).stat().st_size})
files.sort(key=lambda row:row['path'])
value={
 'attempt':'03','status':'historical-automatic-pass-visual-failure','finalAcceptance':False,
 'productManifestSha256Bytes':'5d35c5c5d1af113502de1c6988ea81a54a3e4588e711414aeefeeb7f8c447dd6',
 'orderedRuntimeCpSha256Bytes':'70618e55ee1ce108a75086c8344e41b44f902f518ede9ade75863ec6f6590788',
 'fixtureSources':2,'productionOverrides':0,'actualLoadedClasses':29,'orderedRuntimeEntries':89,
 'externalEntries':86,'cells':4,'actualPointerPairs':76,'screenshots':32,'independentColdJvms':8,
 'rawCacheEqualityChecks':24,'automaticChecksPassed':True,'visualReviewPassed':False,
 'remainingIssue':'Original action row follows system dark while desktop light is forced; light-mode text/icons have poor contrast.',
 'actualCommunityReadyAndRegistry':True,'wholeShellExecuted':False,'nativeHWND':False,
 'packageAcceptance':False,'realAccountOrSocket':False,'sharedGradle':False,
 'privateAccountAndStoreDiskExcluded':True,'artifactCount':len(files),'files':files}
save(manifest,value)
for row in files:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
print(json.dumps({'manifest':str(manifest),'sha256Bytes':sha(manifest),'artifacts':len(files),'allBytesVerified':True},ensure_ascii=False))
