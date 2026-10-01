"""Freeze the freshly compiled Main and its actual Gradle runtime graph."""
from pathlib import Path
import hashlib,json,os,zipfile,sys
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
OUT=HERE/(sys.argv[1] if len(sys.argv)>1 else 'main-product-snapshot-03')
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def save(path,value):ext(path).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
raw=(HERE/(sys.argv[2] if len(sys.argv)>2 else 'gradle-main-01.log')).read_bytes()
log=raw.decode('utf-16' if raw.startswith(b'\xff\xfe') else 'utf-8-sig')
assert 'BUILD SUCCESSFUL' in log
assert len(json.loads((REPO/'desktop/upstream-sources.json').read_bytes())['sources'])==622
assert not OUT.exists()
OUT.mkdir()
artifacts=[];replacements={}
for name,relative in [('main-kotlin','desktop/build/classes/kotlin/main'),('main-java','desktop/build/classes/java/main'),('main-resources','desktop/build/resources/main')]:
    base=REPO/relative;target=OUT/(name+'.jar');count=0
    with zipfile.ZipFile(ext(target),'w',zipfile.ZIP_DEFLATED) as archive:
        for directory,dirs,names in os.walk(ext(base)):
            dirs.sort()
            for name in sorted(names):
                path=Path(directory)/name
                entry=zipfile.ZipInfo(path.relative_to(ext(base)).as_posix(),(1980,1,1,0,0,0))
                entry.compress_type=zipfile.ZIP_DEFLATED
                archive.writestr(entry,ext(path).read_bytes());count+=1
    row=dict(path=str(target),source=relative,entries=count,sha256Bytes=sha(target))
    artifacts.append(row);replacements[str(base.resolve()).casefold()]=row
runtime=json.loads((HERE/'actual-main-runtime-paths.json').read_bytes())['entries']
rows=[replacements.get(str(Path(path).resolve()).casefold()) or dict(path=path,sha256Bytes=sha(path)) for path in runtime]
assert len(rows)==92
save(OUT/'ordered-runtime-cp.json',rows)
sources={'desktop/build.gradle.kts','desktop/upstream-sources.json'}
for root in ['desktop/src/main/kotlin','desktop/src/main/java','desktop/tools']:
    for directory,dirs,names in os.walk(ext(REPO/root)):
        dirs[:]=sorted(n for n in dirs if n!='__pycache__')
        for name in sorted(names):
            if Path(name).suffix in {'.kt','.java','.py'}:
                sources.add((Path(directory)/name).relative_to(ext(REPO)).as_posix())
source_rows=[dict(path=path,sha256Lf=hashlib.sha256(ext(REPO/path).read_bytes().replace(b'\r\n',b'\n')).hexdigest()) for path in sorted(sources)]
generated=[]
for directory,dirs,names in os.walk(ext(REPO/'desktop/build/generated')):
    dirs.sort()
    for name in sorted(names):
        if Path(name).suffix in {'.kt','.java'}:
            path=Path(directory)/name
            generated.append(dict(path=path.relative_to(ext(REPO)).as_posix(),sha256Bytes=sha(path)))
save(OUT/'manifest.json',dict(frozen=True,phase='original-dynamic-media-current-main',
    artifacts=artifacts,sourceFiles=source_rows,generatedProductFiles=generated,
    actualRuntimeClasspath=dict(entries=len(rows),externalEntries=len(rows)-3,orderedIdentitiesSha256Bytes=sha(OUT/'ordered-runtime-cp.json')),
    compilePassed=True,mainConsumerSourceIntegrated=True,mainConsumerRuntimeAccepted=False,
    nativeShareAccepted=False,liquidGlassEnabled=False,packaged=False,registryCount=622))
print(json.dumps(dict(snapshot=str(OUT),manifestSha256Bytes=sha(OUT/'manifest.json'),
    orderedRuntimeCpSha256Bytes=sha(OUT/'ordered-runtime-cp.json'),runtimeEntries=len(rows),sourceFiles=len(source_rows))))
