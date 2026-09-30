"""Freeze current product proof and raw task-owned outputs; never rewrite evidence."""
from pathlib import Path
from PIL import Image
import hashlib,importlib.util,io,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def raw(p):return safe(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def record(p):return dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),sizeBytes=safe(p).stat().st_size)
target=HERE/'frozen-handoff.json';assert not target.exists(),'Frozen evidence is never overwritten'
run=HERE/'current-product-1';execution=json.loads(raw(run/'compile-evidence.json'))
assert execution['noProductionSourcesCompiled'] and execution['noProductClassOverrides'] and execution['scenarioCount']==18
for e in execution['fixtureSources']:assert sha(HERE/e['path'])==e['sha256Bytes']
for e in json.loads(raw(run/'dependency-identities.json')):assert sha(e['path'])==e['sha256Bytes']
for source in safe(run/'classes').rglob('*'):
    if source.is_file():
        name=source.relative_to(safe(run/'classes')).as_posix()
        assert any(name.startswith(p) for p in execution['compiledPackages']) or name=='META-INF/'+execution['moduleName']+'.kotlin_module',name
seams=json.loads(raw(HERE/'source-seam-verification.json'));assert seams['passed'] and len(seams['checks'])==18
for e in seams['verifiedIdentities']:assert sha(REPO/e['path'])==e['sha256Bytes']
taskPrefix=(HERE.parents[3]/'.local/cmp-current-product-1/temp').resolve()
cases=[];storage=[]
for resultFile in sorted(run.glob('*/result.json')):
    value=json.loads(raw(resultFile));assert value['passed']
    attempts=value.get('networkAttempts',value.get('networkOrProcessAttempts'));assert attempts==0
    root=Path(value.get('taskRoot',value.get('fixtureTemporaryRoot'))).resolve();assert root.is_relative_to(taskPrefix)
    for source in safe(root).rglob('*'):
        if not source.is_file() or not (source.name in ['plugin-settings.json','explicit-fixture-export.txt'] or source.parent.name=='logs'):continue
        assert not source.is_symlink()
        destination=resultFile.parent/'actual-task-storage'/source.relative_to(safe(root))
        assert not destination.exists(),'Raw storage evidence is never overwritten'
        safe(destination.parent).mkdir(parents=True,exist_ok=True);safe(destination).write_bytes(raw(source));storage.append(record(destination))
    cases.append(dict(case=resultFile.parent.name,assertions=len(value['checks']),networkOrProcessAttempts=attempts,
        actualRootCrashComposable=value.get('actualDesktopApp',False),sameLifecycleOwnedController=value.get('actualLifecycleOwnedController',False)))
assert len(cases)==18 and sum(x['assertions'] for x in cases)==278
assert sum(x['assertions'] for x in cases if x['case'].startswith('crash-'))==98
assert sum(x['assertions'] for x in cases if x['case'].startswith('lifecycle-'))==70
assert sum(x['assertions'] for x in cases if x['case'].startswith('root-'))==110
images=[]
for path in sorted(run.rglob('*.png')):
    image=Image.open(io.BytesIO(raw(path))).convert('RGBA');pixels=list(image.getdata());background=image.getpixel((15,15))
    opaque=sum(p[3]==255 for p in pixels);ink=sum(p[3]==255 and sum(abs(p[i]-background[i]) for i in range(3))>180 for p in pixels)
    assert image.size in [(1080,900),(1080,800)] and opaque==image.width*image.height and ink>500,path
    images.append(dict(**record(path),width=image.width,height=image.height,opaquePixels=opaque,contrastedInkPixels=ink))
assert len(images)==32
worker=Path(execution['workerResources']);catalog=worker/'classpath.json'
assert sha(catalog)=='fcad2583c2862e66870f100a314db53534bbc782f4bca8b144ae7eaf3c0fe628'
workerValue=json.loads(raw(catalog));assert len(workerValue['classpath'])==13 and len(workerValue['resources'])==137
for e in workerValue['classpath']+workerValue['resources']:assert sha(worker/e['file'])==e['sha256'] and safe(worker/e['file']).stat().st_size==e['bytes']
spec=importlib.util.spec_from_file_location('freezecompiler',HERE.parent/'source9-appearance/compile-miuix.py');compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
compilerPins=[dict(path=str(p),sha256Bytes=sha(p)) for p in [compiler.JAVA,compiler.PLUGIN]+compiler.COMPILER]
summary=dict(passed=True,cases=cases,executableFixtureAssertions=278,originalCrashAssertions=98,lifecycleAssertions=70,
    currentActualRootAssertions=110,currentActualRootScenarios=8,actualPointerPairs=26,originalHostPointerPairs=14,
    previousRootErrorPointerPairs=4,currentRootCrashPointerPairs=8,sourceContractCount=18,sourceIdentityCount=51,
    newSnapshotSourceAndGeneratedIdentities=39,unchangedSupplementalIdentities=12,
    pngCount=32,images=images,taskStorageFileCount=len(storage),taskStorageFiles=storage,
    workerCatalogSha256Bytes=sha(catalog),workerCpCount=13,workerResourceCount=137,workerExecuted=False,
    compilerPins=compilerPins,OSChooserClicked=False,nativeMainWindowExecuted=False,automaticUploads=False,
    existingProductionClassesOverridden=False,mainOrSharedGradleModified=False,platformExternalSharingPending=True)
assert not (HERE/'proof-summary.json').exists()
safe(HERE/'proof-summary.json').write_bytes((json.dumps(summary,ensure_ascii=False,indent=2)+'\n').encode())
artifacts=[record(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p!=target]
frozen=dict(frozen=True,currentProductProof=True,artifactCount=len(artifacts),artifacts=artifacts,
    snapshotManifestSha256Bytes=execution['snapshotManifestSha256Bytes'],productJarPins=execution['productJarPins'],
    proofSummarySha256Bytes=sha(HERE/'proof-summary.json'),fixtureScenarioCount=18,fixtureAssertionCount=278,
    sourceContractCount=18,sourceIdentityCount=51,pngCount=32,actualPointerPairs=26,rawTaskStorageFileCount=len(storage),
    productionOverrideClasses=0,compiledFixtureSources=3,HWND=False,networkOrAccounts=False,OSChooserClicked=False,
    noSharedGradleOrMainMutations=True,AndroidExternalSharePending=True,
    priorPreparedCohortSha256Bytes='7ec64c983f5ac9464bcae5a39fa9f46745b9f0eb0f062ee9a8161e61a1a6f437',
    priorLifecycleCohortSha256Bytes='a458464369723db357bf1f09d505707aa4fa672b7054bddbdce0014017c0f6f5')
safe(target).write_bytes((json.dumps(frozen,ensure_ascii=False,indent=2)+'\n').encode())
print(json.dumps(dict(frozen=True,artifactCount=len(artifacts),rawTaskStorageFileCount=len(storage),sha256Bytes=sha(target),proofSummarySha256Bytes=sha(HERE/'proof-summary.json'))))
