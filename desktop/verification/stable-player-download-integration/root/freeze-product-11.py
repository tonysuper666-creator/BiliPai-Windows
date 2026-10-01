from pathlib import Path
import hashlib,json,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=MAIN/'desktop/.local/stable-product-snapshot-11'
assert not (OUT/'manifest.json').exists(), 'Never replace a completed frozen snapshot'
def ext(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def read(p): return ext(p).read_bytes()
def sha(data): return hashlib.sha256(data).hexdigest()
cp=json.loads(read(HERE/'stable-classpath-11.json'));assert len(cp)==92 and len(set(cp))==92
assert b'BUILD SUCCESSFUL in 50s' in read(HERE/'gradle-candidate-11.log')
OUT.mkdir(exist_ok=True);ordered=[];product=[]
for index,path in enumerate(cp):
    source=Path(path)
    if ext(source).is_dir():
        relative=str(source.relative_to(REPO)).replace('\\','/')
        kind={'desktop/build/classes/java/main':'java','desktop/build/classes/kotlin/main':'kotlin','desktop/build/resources/main':'resources'}[relative]
        target=OUT/('main-'+kind+'.jar')
        files=sorted((p for p in ext(source).rglob('*') if p.is_file()),key=lambda p:str(p))
        with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as archive:
            for file in files:
                name=str(file.relative_to(ext(source))).replace('\\','/')
                info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                archive.writestr(info,read(file))
        row=dict(path=str(target),source=relative,entries=len(files),sha256Bytes=sha(read(target)))
        product.append(row)
    else:
        assert ext(source).is_file(),path
        row=dict(path=path,sha256Bytes=sha(read(source)))
    ordered.append(row)
assert len(product)==3
raw=(json.dumps(ordered,indent=2)+'\n').encode();(OUT/'ordered-runtime-cp.json').write_bytes(raw)
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));assert len(registry['sources'])==631
metadata=dict(frozen=True,phase='actual-whole-stable-classes11',upstreamTag=registry['upstreamTag'],upstreamCommit=registry['upstreamCommit'],
    artifacts=product,orderedRuntimeClasspathSha256Bytes=sha(raw),runtimeEntries=len(ordered),
    classesLogSha256Bytes=sha(read(HERE/'gradle-candidate-11.log')),wholeCandidateClassesPassed=True,
    actualStableApplicationRuntimeAccepted=False,desktopExeReplaced=False,sourceRegistryCount=631,resourceCount=len(registry['resources']),
    inputs=[dict(path=p,sha256Bytes=sha(read(REPO/p))) for p in ('desktop/build.gradle.kts','desktop/upstream-sources.json',
        'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt','desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt')])
raw=(json.dumps(metadata,indent=2)+'\n').encode();(OUT/'manifest.json').write_bytes(raw)
print(json.dumps(dict(path=str(OUT),manifestSha256Bytes=sha(raw),classpathSha256Bytes=metadata['orderedRuntimeClasspathSha256Bytes'],
    product=[dict(kind=p['source'],sha256Bytes=p['sha256Bytes']) for p in product])))
