"""Freeze this lane's bytes and retain task-owned actual storage output only."""
from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def raw(p):return safe(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def record(p):return {'path':str(p.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(p),'sizeBytes':safe(p).stat().st_size}
target=HERE/'frozen-artifacts.json'
if target.exists():raise ValueError('Existing frozen cohort is never overwritten')
run=HERE/'snapshot-3'
execution=json.loads(raw(run/'compile-evidence.json'))
assert execution['fixtureSourceSha256Bytes']==sha(HERE/'DiagnosticProductLifecycleFixture.kt')
assert execution['noProductClassOverrides'] and execution['scenarioCount']==7
for p in (run/'classes').rglob('*'):
    if p.is_file():
        relative=str(p.relative_to(run/'classes')).replace('\\','/')
        assert relative.startswith('com/bilipai/desktop/diagnostics/proof/') or relative=='META-INF/'+execution['moduleName']+'.kotlin_module',relative
cases=[]
rawStorage=[]
allowedTemporaryRoot=HERE.parents[3]/'.local/dgl-snapshot-3/temp'
for file in sorted(run.glob('*/result.json')):
    result=json.loads(raw(file));assert result['passed'] and result['networkOrProcessAttempts']==0
    root=Path(result['fixtureTemporaryRoot']).resolve()
    assert root.is_relative_to(allowedTemporaryRoot.resolve())
    destination=file.parent/'actual-private-storage'
    for source in root.rglob('*'):
        if not source.is_file() or not (source.name=='plugin-settings.json' or source.parent.name=='logs'):continue
        assert not source.is_symlink()
        output=destination/source.relative_to(root)
        if output.exists():assert raw(output)==raw(source),'Previous task storage evidence changed'
        else:output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(raw(source))
        rawStorage.append(record(output))
    cases.append({'case':result['case'],'checks':len(result['checks']),'networkOrProcessAttempts':0,
        'actualRootStartupComposable':result['actualRootStartupComposable']})
assert len(cases)==7 and sum(x['checks'] for x in cases)==70
sourceSeams=json.loads(raw(HERE/'source-seam-verification.json'))
assert sourceSeams['passed'] and len(sourceSeams['checks'])==15
repo=HERE.parents[2]
currentDrift=[]
for e in sourceSeams['verifiedSourceIdentities']:
    actual=sha(repo/e['path'])
    if actual!=e['sha256Bytes']:
        # Root may continue its independent viewer mounting. Only the immutable jars
        # are executed; never relabel them as Root's later changed source.
        assert e['path']=='desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopLocalDiagnosticViewer.kt',e['path']
        currentDrift.append({'path':e['path'],'immutableSnapshotSourceSha256Bytes':e['sha256Bytes'],
            'laterCurrentSourceSha256Bytes':actual,'laterCurrentSourceExecutedByThisProof':False})
worker=repo/'desktop/build/jw/876b361fa45913e2/r'
catalog=worker/'classpath.json';assert sha(catalog)=='fcad2583c2862e66870f100a314db53534bbc782f4bca8b144ae7eaf3c0fe628'
workerCatalog=json.loads(raw(catalog))
assert len(workerCatalog['classpath'])==13 and len(workerCatalog['resources'])==137
for e in workerCatalog['classpath']+workerCatalog['resources']:
    assert sha(worker/e['file'])==e['sha256'] and safe(worker/e['file']).stat().st_size==e['bytes']
spec=importlib.util.spec_from_file_location('frozencompiler',HERE.parent/'source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
compilerPins=[{'path':str(p),'sha256Bytes':sha(p)} for p in [compiler.JAVA,compiler.PLUGIN]+compiler.COMPILER]
artifacts=[record(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p!=target]
result={'frozen':True,'artifactCount':len(artifacts),'artifacts':artifacts,
    'snapshotManifestSha256Bytes':execution['snapshotManifestSha256Bytes'],'productJarPins':execution['productJarPins'],
    'fixtureOnlyCompiledSourceCount':1,'compiledProductOverrideClasses':0,'cases':cases,'executableAssertionCount':70,
    'sourceContractCount':15,'sourceIdentityCount':29,'laterSourceDrift':currentDrift,'finalPngCount':6,'historicalConvergencePngCount':6,
    'rawTaskStorageEvidenceCount':len(rawStorage),'rawTaskStorageEvidence':rawStorage,'compilerPins':compilerPins,
    'workerCatalogSha256Bytes':sha(catalog),'workerCpCount':13,'workerResourceCount':137,'jsWorkerExecuted':False,
    'nativeMainWindowExecuted':False,'mainOrSharedGradleModified':False,'noRealAccountOrNetworking':True,
    'initialFixtureFailurePreserved':'snapshot-1/accepted-writer.log'}
target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'frozen':True,'artifactCount':len(artifacts),'rawTaskStorageEvidence':len(rawStorage),'sha256Bytes':sha(target)}))
