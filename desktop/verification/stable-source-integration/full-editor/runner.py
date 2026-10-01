from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai'
SNAPSHOT=PRIMARY/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
MANIFEST='7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
CP='7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
    v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):
    assert Path(p).absolute().is_relative_to(HERE)
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def argsfile(p,args):write(p,'\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
def env(root):
    values=os.environ.copy();values['PYTHONDONTWRITEBYTECODE']='1'
    for name in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
        directory=root/name.lower();safe(directory).mkdir(parents=True,exist_ok=True);values[name]=str(directory)
    return values
inventory=json.loads(read(HERE/'candidate-source-inventory.json'))
for row in inventory['sourceCandidates']:
    assert sha(HERE/'prepared'/row['path'])==row['candidateLfSha256']
    assert hashlib.sha256(read(REPO/row['path']).encode()).hexdigest() in {row['baseLfSha256'],row['candidateLfSha256']},row['path']
assert sha(SNAPSHOT/'manifest.json')==MANIFEST and sha(SNAPSHOT/'ordered-runtime-cp.json')==CP
cp=json.loads(read(SNAPSHOT/'ordered-runtime-cp.json'));assert len(cp)==92
for row in cp:assert sha(row['path'])==row['sha256Bytes']
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
phase,attempt=sys.argv[1:3];assert attempt.isalnum();out=HERE/'runs'/attempt
if phase=='compile':
    assert not out.exists();out.mkdir(parents=True)
    generated=json.loads(read(HERE/'generated-inventory.json'))
    for row in generated:assert sha(row['path'])==row['sha256Bytes']
    sources=[Path(row['path']) for row in generated]
    sources += [HERE/'prepared'/row['path'] for row in inventory['sourceCandidates'] if row['path'].endswith('.kt')]
    stream=REPO/'desktop/.local/stable-editor-stream-upload'
    stream_manifest=json.loads(read(stream/'evidence-manifest.json'))
    assert sha(stream/'evidence-manifest.json')=='e48ee2b9dbf7bd58f8460fee44f32fe700cad98e8ed8d2a834c9472042575f29'
    stream_rows=[r for r in stream_manifest['sourceCandidates'] if r['path'].endswith('.kt') and not r['path'].endswith('DesktopDynamicEditorSelectedImages.kt')]
    for row in stream_rows:assert sha(stream/'prepared'/row['path'])==row['candidateLfSha256']
    sources += [stream/'prepared'/row['path'] for row in stream_rows]
    sources += [REPO/'desktop/build/generated/dynamic-gallery-motion-photo/com/android/purebilibili/core/util/DesktopOriginalGalleryResultPolicy.kt',HERE/'CapacityFixture.kt']
    frozen=[]; source_records=[]
    for index,p in enumerate(sources):
        target=out/'frozen-sources'/p.name;assert target not in frozen;write(target,read(p));frozen.append(target)
        source_records.append(dict(originalPath=str(p),originalLfSha256=hashlib.sha256(read(p).encode()).hexdigest(),frozenPath=str(target),sha256Bytes=sha(target)))
    spec=importlib.util.spec_from_file_location('full_ui_compile',PRIMARY/'desktop/.local/source9-appearance/compile-miuix.py')
    compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
    home=out/'compiler-home';values=env(home);jar=out/'candidate.jar'
    arguments=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+main,
        '-Xplugin='+str(compiler.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-d',jar]+frozen
    argsfile(out/'compile.args',arguments)
    command=[str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Duser.home='+str(home),'-Djava.io.tmpdir='+values['TEMP'],'-Xmx2g','-cp',';'.join(map(str,compiler.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')]
    result=subprocess.run(command,cwd=HERE,env=values,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(out/'compile.log',result.stdout+result.stderr);print(result.stdout+result.stderr)
    if result.returncode:
        save(out/'compile-failure.json',dict(exitCode=result.returncode,logSha256=sha(out/'compile.log'),sourceRecords=source_records,MainChanged=False));sys.exit(result.returncode)
    existing=set()
    for row in cp:
        with zipfile.ZipFile(safe(row['path'])) as archive:existing.update(n for n in archive.namelist() if n.endswith('.class'))
    with zipfile.ZipFile(safe(jar)) as archive:classes={n for n in archive.namelist() if n.endswith('.class')}
    overlap=sorted(classes&existing)
    save(out/'compile-evidence.json',dict(passed=True,Main04ManifestSha256=MANIFEST,Main04ClasspathSha256=CP,runtimeClasspath=cp,
        sourceRecords=source_records,candidateJarSha256=sha(jar),candidateClassCount=len(classes),existingMainProductOverrides=overlap,
        fullComposerOriginalBodyReceiptSha256=sha(HERE/'full-composer-retention-proof.json'),frozenStreamChainManifestSha256=sha(stream/'evidence-manifest.json'),
        sharedGradle=False,MainChanged=False,scope='Sole complete editor producer 8 outputs, 18-image adapter delta and frozen stream consumers against actual Main04 92-file graph; no whole stable Main build'))
    print('PASS scoped full editor UI compile; product overrides '+str(len(overlap)))
elif phase=='run':
    evidence=json.loads(read(out/'compile-evidence.json'));jar=out/'candidate.jar'
    assert sha(jar)==evidence['candidateJarSha256']
    for row in evidence['sourceRecords']:assert sha(row['frozenPath'])==row['sha256Bytes']
    proof=out/'proof';assert not proof.exists();proof.mkdir()
    home=out/'task-home';values=env(home);java=REPO.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
    args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+values['TEMP'],
        '-cp',';'.join([str(jar)]+[r['path'] for r in cp]),'com.bilipai.desktop.ui.editorFullUiProof.CapacityFixtureKt',proof,jar,main]
    argsfile(out/'run.args',args)
    result=subprocess.run([str(java),'@'+str(out/'run.args')],cwd=HERE,env=values,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=55)
    write(out/'run.log',result.stdout+result.stderr);print(result.stdout+result.stderr)
    save(out/'runtime-process.json',dict(exitCode=result.returncode,candidateJarSha256=sha(jar),runLogSha256=sha(out/'run.log')))
    result.check_returncode()
    accepted=json.loads(read(proof/'result.json'));assert accepted['passed'] and accepted['cases']==3
    for row in cp:assert sha(row['path'])==row['sha256Bytes']
    save(out/'accepted-evidence.json',dict(**accepted,Main04ManifestSha256=MANIFEST,Main04ClasspathSha256=CP,
        candidateJarSha256=sha(jar),existingMainProductOverrides=evidence['existingMainProductOverrides'],MainChanged=False,
        scope='Prepared full source UI compile plus actual Main04 Store capacity/lifetime seam only; full original composer interaction remains Root validation'))
    print('PASS capacity/lifetime runtime; exact Main04 92 artifact bytes unchanged')
else:raise ValueError(phase)
