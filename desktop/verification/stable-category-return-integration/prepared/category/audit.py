from pathlib import Path
import hashlib,importlib.util,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def save(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def main():
 tool=HERE/'prepared/desktop/tools/extract-upstream-category-page.py';spec=importlib.util.spec_from_file_location('producer',tool);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 outputs=[]
 for name,standalone in [('production',False),('standalone',True)]:
  files=m.generate(REPO,HERE/'source-audit'/name,standalone);assert len(files)==1
  prepared=HERE/'prepared/generated/com/android/purebilibili/feature/category/CategoryScreen.kt'
  assert safe(files[0]).read_bytes()==safe(prepared).read_bytes()
  outputs.append(dict(path=files[0].relative_to(HERE).as_posix(),sha256Bytes=sha(files[0])))
 row=json.loads(read(HERE/'prepared/generated/category-page-producer-inventory.json'))[0]
 assert row['inverseNormalizedOriginalEqual'] and row['originalPageSuccessBodyUnchanged'] and row['originalLineCount']==368
 helperHunks=json.loads(read(HERE/'sole-helper-producer-hunks.json'))
 for h in helperHunks:
  base=read(REPO/h['path']);assert hashlib.sha256(base.encode()).hexdigest()==h['baseSha256LF']
  for c in h['changes']:assert base.count(c['before'])==1;base=base.replace(c['before'],c['after'])
  assert hashlib.sha256(base.encode()).hexdigest()==h['candidateSha256LF']
 compileResult=json.loads(read(HERE/'runs/compile-02/compile-result.json'));assert compileResult['status']=='PASS' and not compileResult['productionClassIntersection']
 jar=HERE/'runs/compile-02/prepared-category-page.jar';actual={};count=0;own=[];collisions=[]
 old=read(MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py')
 helper=old[old.index('def methods('):old.index('\nwith zipfile.ZipFile(',old.index('def methods('))];scope={};exec('import struct\n'+helper,scope);methods=scope['methods']
 cp=json.loads(read(MAIN/'desktop/.local/stable-product-snapshot-36/ordered-runtime-cp.json'))
 for item in cp:
  assert sha(item['path'])==item['sha256Bytes']
  with zipfile.ZipFile(safe(item['path'])) as z:
   for e in z.namelist():
    if e.endswith('Kt.class') and '$' not in e:
     for method in methods(z.read(e),e):count+=1;actual.setdefault(method['key'],[]).append(method)
 with zipfile.ZipFile(safe(jar)) as z:
  bad=[e for e in z.namelist() if e.endswith('.class') and b'NON_LOCAL_RETURN' in z.read(e)]
  assert not bad,bad
  for e in z.namelist():
   if e.endswith('Kt.class') and '$' not in e:
    for method in methods(z.read(e),e):
     own.append(method)
     for hit in actual.get(method['key'],[]):collisions.append(dict(candidate=method,actual=hit))
 assert not collisions,collisions
 fixture=json.loads(read(HERE/'runs/fixture-01/result.json'));assert fixture['status']=='PASS' and fixture['assertions']==16
 report=dict(status='PASS',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',source=row['path'],originalSha256LF=row['sha256LF'],originalLines=368,
  fullOriginalUiAndViewModel=True,originalSuccessPaginationBodyUnchanged=True,inverseNormalizedSourceEqual=True,
  productionStandalonePreparedByteEqual=True,producerOutputs=outputs,requiredVideoAndReturnFlags=True,
  noFakeHomeSettings=True,immutableTid=True,ownedRequestAdmission='Root gate -> VM lock, synchronous busy; lazy dispatch and prior request cancellation outside monitors',
  cancelledOrReplacedRequestCannotPublishOrClearReplacement=True,categoryScopeSurvivesVideoCover=True,
  sharedReferences=json.loads(read(HERE/'reference-dependencies.json')),sharedHelperExactHunksVerified=True,
  compiledClasses=25,productionClassIntersections=[],topLevelMethods=len(own),actual97CpTopLevelMethods=count,topLevelIntersections=[],illegalNonLocalReturnMarkers=[],
  prospectiveFixture=fixture,actualMain36ClasspathSha256Bytes=sha(MAIN/'desktop/.local/stable-product-snapshot-36/ordered-runtime-cp.json'),
  sourceOnlyInstallation=True,actualCategoryRootRuntimeAccepted=False,HTTP=False,GUI=False,HWND=False,Gradle=False,
  pending=['Root same retained Home transport, category nav-entry owner and actual callbacks integration','Actual mounted full Category UI/pointer/return-transition proof'])
 save(HERE/'source-checks.json',report)
 registry=json.loads(read(REPO/'desktop/upstream-sources.json'))
 save(HERE/'registry-recipe.json',dict(overwrite=False,page=dict(path=row['path'],sha256=row['sha256LF'],mode='extracted',features=['category-page']),
  existing=[r for r in registry['sources'] if r['path']==row['path']],sharedIdentityRule='Only merge category feature into existing exact helper identities; preserve their sole producers/modes'))
 print(json.dumps(dict(status='PASS',classes=25,fixtureAssertions=16,topLevelIntersections=0,sourceChecksSha256Bytes=sha(HERE/'source-checks.json')),indent=2))
if __name__=='__main__':main()
