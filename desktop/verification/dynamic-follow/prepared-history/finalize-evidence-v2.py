from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def ext(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,value):ext(p).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(HERE/'frozen-handoff.json')=='7b7ad3fdc43fbc18ece03644be567f41625929cb8350556ba5b54f7ac2d45654'
original=json.loads((HERE/'frozen-handoff.json').read_bytes())
for row in original['files']:assert sha(HERE/row['path'])==row['sha256Bytes'] and ext(HERE/row['path']).stat().st_size==row['bytes']
review=json.loads((HERE/'review-final.json').read_bytes())
review['completeRawManifestCorrection']='Windows extended-path enumeration includes 12 previously omitted long-path raw files; all prior source/acceptance/review bytes unchanged'
review['originalPartialRawManifestSha256Bytes']=sha(HERE/'frozen-handoff.json')
save(HERE/'review-final-v2.json',review)
allowed={'.kt','.py','.json','.md','.patch','.args','.log','.txt'}
files=[]
for file in sorted(ext(HERE).rglob('*')):
 if not file.is_file() or file.name=='frozen-handoff-v2.json' or file.suffix not in allowed or '__pycache__' in file.parts:continue
 normal=Path(str(file).removeprefix('\\\\?\\'));relative=normal.relative_to(HERE).as_posix()
 files.append(dict(path=relative,sha256Bytes=hashlib.sha256(file.read_bytes()).hexdigest(),bytes=file.stat().st_size))
oldpaths={r['path'] for r in original['files']};added=[r['path'] for r in files if r['path'] not in oldpaths]
manifest=dict(frozen=True,completeExtendedPathEnumeration=True,kind='prepared-original-dynamic-follow-observer-parity',preparedFollowObserverPassed=True,MainIntegrated=False,DesktopDeployed=False,finalAcceptedRun='runs/08',actualMainBaselineSha256Bytes=original['actualMainBaselineSha256Bytes'],orderedRuntimeCpSha256Bytes=original['orderedRuntimeCpSha256Bytes'],reviewFinalSha256Bytes=sha(HERE/'review-final-v2.json'),rootIntegrationChecklistSha256Bytes=original['rootIntegrationChecklistSha256Bytes'],supersededPartialRawManifestSha256Bytes=sha(HERE/'frozen-handoff.json'),previousRawBytesPreserved=True,additionalRawPaths=added,files=files,exclusions=original['exclusions'],historyAcceptedAsCurrent=False)
assert not(HERE/'frozen-handoff-v2.json').exists();save(HERE/'frozen-handoff-v2.json',manifest)
for row in files:assert sha(HERE/row['path'])==row['sha256Bytes'] and ext(HERE/row['path']).stat().st_size==row['bytes']
observed={Path(str(p).removeprefix('\\\\?\\')).relative_to(HERE).as_posix() for p in ext(HERE).rglob('*') if p.is_file() and p.suffix in allowed and p.name!='frozen-handoff-v2.json' and '__pycache__' not in p.parts}
assert observed=={r['path'] for r in files}
print(json.dumps(dict(rawFiles=len(files),frozenHandoffV2Sha256Bytes=sha(HERE/'frozen-handoff-v2.json'),reviewFinalV2Sha256Bytes=sha(HERE/'review-final-v2.json'),integrationChecklistSha256Bytes=sha(HERE/'root-integration-checklist.json'),additionalPaths=len(added)),indent=2))
