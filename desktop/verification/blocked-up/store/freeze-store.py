from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def extended(path):
    name=str(path.absolute());return Path(name if name.startswith('\\\\?\\') else '\\\\?\\'+name)
def sha(path):return hashlib.sha256(extended(path).read_bytes()).hexdigest()
def write(name,value):(HERE/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
owned=json.loads((HERE/'owned-files.json').read_text());assert len(owned)==6
for row in owned:
    assert sha(HERE/'prepared'/row['path'])==row['sha256']
    if row['kind']=='modify':
        assert sha(HERE/'baseline'/row['path'])==row['baseSha256']
        assert sha(ROOT/row['path'])==row['baseSha256']
report=json.loads((HERE/'store-proof/result.json').read_text());compiled=json.loads((HERE/'store-compile-evidence.json').read_text())
assert report['passed'] and report['junitMethodsPassed']==8 and report['actualArchiveRestoreAndQueuedWriterFence']
assert compiled['passed'] and compiled['normalExit']
for path,digest in compiled['compiledSourceIdentities'].items():assert sha(HERE/path)==digest,path
contract=dict(status='frozen',stage=1,integrated=False,ownedFiles=owned,sourceInventory='source-inventory.json',generatedSourceCount=3,
    newSourceIdentities=2,newDependencies=[],newResources=[],originalFullModelStored=True,globalSingleActiveStore=True,
    originalManagementUiImplemented=False,profilesRemotelyRefreshed=False,remoteSyncVerified=False,
    communitySearchSpaceConsumersImplemented=False,junitMethodsPassed=8,actualDiskRestoreFenceVerified=True,
    mainEdited=False,sharedGradleInvoked=False,nativeWindowCreated=False,realAccountReads=False,networkRequests=False,
    immutableProductManifestSha256=compiled['snapshotSha256'])
contract['ownedSetSha256']=hashlib.sha256(json.dumps(owned,sort_keys=True,separators=(',',':')).encode()).hexdigest()
write('store-contract.json',contract)
files=[p for p in extended(HERE).rglob('*') if p.is_file() and p.suffix in ['.kt','.py','.md','.json','.patch','.log','.class','.args','.kotlin_module']
    and p.name!='store-artifact-manifest.json' and '__pycache__' not in p.parts]
write('store-artifact-manifest.json',dict(status='frozen',files=[dict(path=p.relative_to(extended(HERE)).as_posix(),sha256Bytes=sha(p),bytes=p.stat().st_size) for p in sorted(files)]))
for row in json.loads((HERE/'store-artifact-manifest.json').read_text())['files']:
    assert sha(HERE/row['path'])==row['sha256Bytes']
print(json.dumps(dict(frozen=True,ownedFiles=owned,contractSha256=sha(HERE/'store-contract.json'),
    artifactManifestSha256=sha(HERE/'store-artifact-manifest.json'),artifacts=len(files))))
