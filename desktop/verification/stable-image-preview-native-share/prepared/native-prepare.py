from pathlib import Path
import hashlib,json,subprocess,sys
P=Path(__file__).resolve().parent; R=P/'prepared'; VC=Path('C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207'); SDK=Path('C:/Program Files (x86)/Windows Kits/10')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n',encoding='utf8')
source=R/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp'; approvalPath=source.with_name('approved-development-build.json'); old=json.loads(approvalPath.read_bytes()); compiler=R/'desktop/tools/compile-native-diagnostic-share.py'
assert sha(compiler)==old['controlledCompilerScriptSha256Bytes']
assert sha(P/'baseline/desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp')==old['sourceSha256Bytes']
def resolved(row):
 root,rel=row['path'].split('/',1);p=({'vc':VC,'sdk':SDK}[root]/rel).resolve(strict=True);assert sha(p)==row['sha256Bytes'] and p.stat().st_size==row['bytes'];return p
for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
 for row in old[key]:resolved(row)
out=P/'native-reviewed-producer01'
r=subprocess.run([sys.executable,str(compiler),'--source',str(source),'--output',str(out),'--vc-root',str(VC),'--sdk-root',str(SDK),'--sdk-version',old['sdkVersion']],capture_output=True)
(P/'native-reviewed-producer01.log').write_bytes(r.stdout+r.stderr);r.check_returncode()
graph=json.loads((out/'producer-input-graph.json').read_bytes());assert graph['command'][1:10]==old['flags']
for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
 expected={str(resolved(row)).casefold():(row['sha256Bytes'],row['bytes']) for row in old[key]};actual={str(Path(row['path']).resolve()).casefold():(row['sha256Bytes'],row['bytes'])for row in graph[key]};assert expected==actual,key
new=dict(old);new['sourceSha256Bytes']=sha(source);new['dllSha256Bytes']=sha(out/'bilipai-diagnostic-share.dll');dump(approvalPath,new)
assert [key for key in old if old[key]!=new[key]]==['sourceSha256Bytes','dllSha256Bytes']
output=R/'desktop/build/native-diagnostic-share'; asset=R/'desktop/resources/common/native/windows-x64'
r=subprocess.run([sys.executable,str(R/'desktop/tools/prepare-native-diagnostic-share.py'),'--repo',str(R),'--output',str(output),'--asset-dir',str(asset),'--vc-root',str(VC),'--sdk-root',str(SDK)],capture_output=True)
(P/'native-fresh-verifier01.log').write_bytes(r.stdout+r.stderr);r.check_returncode()
receipt=json.loads((output/'producer-receipt.json').read_bytes());assert receipt['resolvedGraphMatchesReviewedBuild'] and receipt['expectedDllSha256Bytes']==new['dllSha256Bytes']==receipt['stagedDllSha256Bytes']
dump(P/'native-verification.json',dict(passed=True,approvalChangedFields=['sourceSha256Bytes','dllSha256Bytes'],oldSource=old['sourceSha256Bytes'],newSource=new['sourceSha256Bytes'],oldDLL=old['dllSha256Bytes'],newDLL=new['dllSha256Bytes'],driver=sha(compiler),headers=len(old['transitiveHeaders']),libraries=len(old['searchedLibrariesConservativePins']),compilerFiles=len(old['compilerBinDirectoryConservativePins']),commandsFlagsUnchanged=True,resolvedGraphUnchanged=True,freshBuilds=2,prepareReceiptSha=sha(output/'producer-receipt.json'),candidateWritten=False,ShareUI=False,externalReceiver=False))
print(json.dumps(dict(passed=True,newDLL=new['dllSha256Bytes'])))
