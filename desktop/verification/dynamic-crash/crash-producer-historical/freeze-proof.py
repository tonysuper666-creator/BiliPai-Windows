"""Verify original provenance and task-owned bytes before freezing the new slice."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
from PIL import Image
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def raw(p):return safe(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def record(p):return dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),sizeBytes=safe(p).stat().st_size)
def load(p,n):
    s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
destination=HERE/'frozen-handoff.json';assert not destination.exists(),'Frozen bytes are never overwritten'
producer=load(HERE/'extract-crash-prompt.py','freezecrashproducer')
assert hashlib.sha256((REPO/producer.SOURCE).read_text(encoding='utf-8').encode()).hexdigest()==producer.PIN
run=HERE/'actual-2';compile=json.loads(raw(run/'compile-evidence.json'))
for entry in compile['sourcePins']:assert sha(HERE/entry['path'])==entry['sha256Bytes']
fixtureResult=[];rawFiles=[];taskPrefix=HERE.parents[3]/'.local/cp-actual-2/temp'
for resultFile in sorted(run.glob('*/result.json')):
    result=json.loads(raw(resultFile));assert result['passed'] and result['networkAttempts']==0
    root=Path(result['taskRoot']).resolve();assert root.is_relative_to(taskPrefix.resolve())
    for source in root.rglob('*'):
        if not source.is_file() or not (source.parent.name=='logs' or source.name in ['plugin-settings.json','explicit-fixture-export.txt']):continue
        assert not source.is_symlink()
        target=resultFile.parent/'actual-task-storage'/source.relative_to(root)
        assert not target.exists(),'Task output evidence is never overwritten'
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw(source));rawFiles.append(record(target))
    fixtureResult.append(dict(case=result['case'],assertions=len(result['checks']),networkAttempts=0))
assert len(fixtureResult)==3 and sum(x['assertions'] for x in fixtureResult)==98
images=[]
for file in sorted(run.rglob('*.png')):
    image=Image.open(file).convert('RGBA');pixels=list(image.getdata());background=image.getpixel((15,15))
    opaque=sum(p[3]==255 for p in pixels);ink=sum(p[3]==255 and sum(abs(p[i]-background[i]) for i in range(3))>180 for p in pixels)
    assert image.size==(1080,900) and opaque==1080*900 and ink>500,file
    images.append(dict(**record(file),width=1080,height=900,opaquePixels=opaque,contrastedInkPixels=ink))
assert len(images)==10
test=subprocess.run([sys.executable,str(HERE/'test_extractor.py')],capture_output=True,text=True,encoding='utf-8');assert test.returncode==0
(HERE/'extractor-test.log').write_text(test.stdout+test.stderr,encoding='utf-8')
license=next(REPO.glob('LICENSE*'))
actor=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnostics.kt'
viewer=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopLocalDiagnosticViewer.kt'
snapshot=HERE.parent/'diagnostics-viewer-product-snapshot/manifest.json'
assert sha(snapshot)==compile['snapshotManifestSha256Bytes']
snapshotValue=json.loads(raw(snapshot))
for source in [actor,viewer]:
    e=next(x for x in snapshotValue['sourceFiles'] if x['path']==source.relative_to(REPO).as_posix());assert sha(source)==e['sha256Bytes']
contract=dict(preparedOnly=True,existingMainChanged=False,originalSources=producer.inventory(),resources=[],newDependencies=[],
    generatedUniqueSources=['com/android/purebilibili/DesktopCrashLogPromptPolicy.kt','com/android/purebilibili/DesktopPendingCrashLogPrompt.kt'],
    ownedPreparedSources=['prepared/DesktopCrashPromptController.kt','prepared/DesktopCrashPromptHost.kt'],
    actorFqn='com.bilipai.desktop.diagnostics.DesktopDiagnostics',host='DesktopCrashPromptHost(controller,chooseExportPath)',
    lifecycle='controller.shutdownForRestore() before same diagnosticLifecycle.shutdownForRestore()',
    platformBoundary='Android Intent sharing becomes same local viewer plus separate explicit export; no OS chooser proof',
    snapshotManifestSha256Bytes=sha(snapshot),actualActorSourceSha256Bytes=sha(actor),actualViewerSourceSha256Bytes=sha(viewer),
    rootLicense=dict(path=license.relative_to(REPO).as_posix(),sha256Bytes=sha(license)),
    fixtureCases=fixtureResult,fixtureAssertionCount=98,extractorTests=3,actualPointerPairs=14,actualPngs=images,
    rawTaskStorageFileCount=len(rawFiles),rawTaskStorageFiles=rawFiles,HWND=False,automaticUploads=False)
(HERE/'contract.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
files=[record(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p!=destination]
destination.write_text(json.dumps(dict(frozen=True,preparedOnly=True,artifactCount=len(files),artifacts=files,
    contractSha256Bytes=sha(HERE/'contract.json'),snapshotManifestSha256Bytes=sha(snapshot),fixtureAssertionCount=98,
    extractorTests=3,actualPointerPairs=14,actualPngs=10,actualTaskStorageFileCount=len(rawFiles)),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(frozen=True,artifactCount=len(files),sha256Bytes=sha(destination),contractSha256Bytes=sha(HERE/'contract.json'))))
