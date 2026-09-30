from pathlib import Path
import hashlib, json, os, subprocess, zipfile, xml.etree.ElementTree as ET
REPO=Path(__file__).resolve().parents[2]
def safe(p):
    v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
previous=REPO/'desktop/.local/native-share-main-product-snapshot-01/manifest.json'
assert sha(previous)=='4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f'
assert 'BUILD SUCCESSFUL' in (REPO/'desktop/.local/dynamic-editor-root-main-compile-02.log').read_text(encoding='utf-8')
out=REPO/'desktop/.local/dynamic-editor-main-product-snapshot-01';out.mkdir(exist_ok=False)
artifacts=[];replacement={}
for name,source in [('main-kotlin','desktop/build/classes/kotlin/main'),('main-java','desktop/build/classes/java/main'),('main-resources','desktop/build/resources/main')]:
    base=REPO/source;target=out/(name+'.jar');count=0
    with zipfile.ZipFile(safe(target),'w',zipfile.ZIP_DEFLATED) as jar:
        for directory,dirs,files in os.walk(safe(base)):
            dirs.sort()
            for filename in sorted(files):
                p=Path(directory)/filename;entry=zipfile.ZipInfo(p.relative_to(safe(base)).as_posix(),(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
                jar.writestr(entry,safe(p).read_bytes());count+=1
    row=dict(path=str(target),source=source,entries=count,sha256Bytes=sha(target));artifacts.append(row)
    replacement[str(base.resolve()).casefold()]=row
runtime=REPO/'desktop/.local/native-share-main-runtime-paths.json'
ordered=[]
for path in json.loads(safe(runtime).read_bytes())['entries']:
    found=replacement.get(str(Path(path).resolve()).casefold())
    ordered.append(found if found else dict(path=path,sha256Bytes=sha(path)))
assert len(ordered)==89 and sum(r['path']in{a['path'] for a in artifacts} for r in ordered)==3
cp=out/'ordered-runtime-cp.json';safe(cp).write_text(json.dumps(ordered,indent=2)+'\n',encoding='utf-8',newline='\n')
reports=[]
for name in []:
    source=REPO/f'desktop/build/test-results/test/TEST-com.bilipai.desktop.diagnostics.{name}.xml'
    e=ET.parse(source).getroot();assert all(int(e.attrib[k])==0 for k in ['failures','errors','skipped'])
    target=out/source.name;safe(target).write_bytes(safe(source).read_bytes())
    reports.append(dict(path=str(target),tests=int(e.attrib['tests']),sha256Bytes=sha(target)))
sources={'desktop/build.gradle.kts','desktop/upstream-sources.json','desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp','desktop/native/diagnostic-share/approved-development-build.json'}
for root in ['desktop/src/main/kotlin','desktop/src/main/java','desktop/tools']:
    for directory,dirs,files in os.walk(safe(REPO/root)):
        dirs[:]=sorted(d for d in dirs if d!='__pycache__')
        for name in sorted(files):
            if Path(name).suffix in {'.kt','.java','.py'}:
                sources.add((Path(directory)/name).relative_to(safe(REPO)).as_posix())
sources.update(f'desktop/src/test/kotlin/com/bilipai/desktop/diagnostics/{name}.kt' for name in ['DesktopDiagnosticsTest'])
generated=[]
for directory,dirs,files in os.walk(safe(REPO/'desktop/build/generated')):
    dirs.sort()
    for name in sorted(files):
        if Path(name).suffix in {'.kt','.java'}:
            p=Path(directory)/name;generated.append(dict(path=p.relative_to(safe(REPO)).as_posix(),sha256Bytes=sha(p)))
sourceRows=[dict(path=p,sha256Bytes=sha(REPO/p),sha256Lf=hashlib.sha256(safe(REPO/p).read_bytes().replace(b'\r\n',b'\n')).hexdigest())for p in sorted(sources)]
record=dict(frozen=True,phase='original-dynamic-editor-actual-main-integration',windowsBaseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
    previousSnapshotManifestSha256Bytes=sha(previous),preparedCompleteManifestSha256Bytes='15465a9bf04fe3ac59839bfcf3717a3c35ac2ae45e4073d8452a29e2064c65a2',
    artifacts=artifacts,sourceFiles=sourceRows,generatedProductFiles=generated,testReports=reports,
    mainCompile=dict(passed=True,focusedTests=sum(r['tests']for r in reports),failures=0,errors=0,skipped=0),
    actualRuntimeClasspath=dict(gradleExportSha256Bytes=sha(runtime),orderedIdentities=str(cp),orderedIdentitiesSha256Bytes=sha(cp),entries=89,externalEntries=86,originalOutputOrderPreserved=True),
    integrationChanges=['original composer and editor repository members', 'same Root modal/session/epoch with shared emotes', 'original AUTH verification after 5 seconds', 'current feed/unread plus detail/space/topic refresh'],
    nativeWindowOrPackageTested=False,realAccountRequests=False,fullFeatureParityVerified=False)
nativeReceipt=REPO/'desktop/build/generated/native-diagnostic-share/producer-receipt.json'
native=json.loads(safe(nativeReceipt).read_bytes());assert native['passed']
asset=REPO/'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'
assert sha(asset)==native['expectedDllSha256Bytes']=='8ad04e3d32892376c2987c7ebf3e96a7f58cae14e2e8f6698f600712a9abe16f'
frozenAsset=out/asset.name;safe(frozenAsset).write_bytes(safe(asset).read_bytes())
record['nativeAsset']=dict(path=str(frozenAsset),sha256Bytes=sha(frozenAsset),bytes=safe(frozenAsset).stat().st_size,stagedPath=str(asset))
record['nativeProducer']=native
record['runtimeNativeWindowExecuted']=False
record['publicReleaseAuthorizationVerified']=False
manifest=out/'manifest.json';safe(manifest).write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(manifest=str(manifest),manifestSha256Bytes=sha(manifest),runtimeCp=str(cp),runtimeCpSha256Bytes=sha(cp),focusedTests=record['mainCompile']['focusedTests'],sourceFiles=len(sourceRows),generatedFiles=len(generated),artifacts=artifacts)))
