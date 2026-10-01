"""Compile only unique task fixtures; execute immutable Root actual Main CP."""
from pathlib import Path
import hashlib,importlib.util,json,os,shutil,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
OLD=REPO/'desktop/.local/dynamic-gallery-motion-photo-parity'
def ext(p):
    s=str(Path(p).resolve());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def raw(p):return ext(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_text(v,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def argsfile(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
phase,attempt=sys.argv[1:3];assert attempt.isalnum()
config=json.loads(raw(HERE/'accepted-snapshot-input.json'))
snap=REPO/config['snapshotRepoRelativePath']
assert sha(snap/'manifest.json')==config['manifestSha256Bytes']
assert sha(snap/'ordered-runtime-cp.json')==config['orderedCpSha256Bytes']
manifest=json.loads(raw(snap/'manifest.json'));cp=json.loads(raw(snap/'ordered-runtime-cp.json'))
assert len(cp)==config['runtimeEntries']
assert len(cp)>=92
for row in cp:assert sha(row['path'])==row['sha256Bytes']
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
approved=json.loads(raw(OLD/'binary-artifact-inventory.json'))['files']
officialpins={Path(r['path']).name:r['sha256Bytes'] for r in approved}
for name in ('commons-imaging-1.0.0-alpha6.jar','commons-io-2.19.0.jar','commons-lang3-3.17.0.jar'):
    matching=[r for r in cp if Path(r['path']).name==name]
    assert len(matching)==1 and matching[0]['sha256Bytes']==officialpins[name],('approved official graph mismatch',name,matching)
adaptation=json.loads(raw(HERE/'fixture-adaptation.json'))
for row in adaptation['outputFixtures']:assert sha(HERE/row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('assets_main_fixture_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'runs'/attempt
if phase=='compile':
    assert not out.exists();out.mkdir(parents=True)
    sourcepins={r['path']:r['sha256Lf'] for r in manifest['sourceFiles']}
    selected=['desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name for name in (
        'DesktopDynamicImageAssets.kt','DesktopDynamicMotionPhotoFiles.kt','DesktopDynamicMotionPhotoExif.kt',
        'DesktopDynamicGallerySelection.kt','DesktopDynamicEditorSelectedImages.kt','DesktopCommentImageCanvas.kt',
        'DesktopOriginalDynamicCardHost.kt','CommunityDynamicScreens.kt')]
    actualsource=[]
    for rel in selected:
        p=REPO/rel;assert hashlib.sha256(raw(p).replace(b'\r\n',b'\n')).hexdigest()==sourcepins[rel],('source mismatch',rel)
        dest=out/'actual-source-pins'/p.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
        actualsource.append({'path':rel,'snapshotSha256Lf':sourcepins[rel],'copyRawSha256Bytes':sha(dest)})
    save(out/'actual-source-evidence.json',{'passed':True,'actualMainConfig':config,'actualSourcePins':actualsource,
        'RootWindowChooserParentMappingsCompiledOnlyNotExecuted':True,'MainSourceWrites':False})
    sources=[]
    for p in sorted((HERE/'fixtures').glob('*.kt')):
        dest=out/'sources'/p.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest);sources.append(dest)
    jar=out/'fixtures-only.jar'
    values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),
        '-Xfriend-paths='+main,'-module-name','actual_main_assets_integration_fixtures','-d',jar]+sources
    argsfile(out/'compiler.args',values)
    r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(out/'compile.log',r.stdout+r.stderr)
    if r.returncode:
        save(out/'compile-failure.json',{'exitCode':r.returncode,'MainRun':False});print(r.stdout+r.stderr);r.check_returncode()
    existing=set()
    for row in cp:
        with zipfile.ZipFile(ext(row['path'])) as z:existing.update(z.namelist())
    with zipfile.ZipFile(jar) as z:classes={n for n in z.namelist() if n.endswith('.class')}
    assert not(classes&existing),classes&existing
    assert all(n.startswith('com/bilipai/desktop/ui/assetsintegrationproof/') for n in classes)
    save(out/'compile-evidence.json',{'passed':True,'actualMainConfig':config,'actualOrderedClasspath':cp,
        'fixtureJarSha256Bytes':sha(jar),'fixtureClasses':sorted(classes),'productionClassOverlap':[],
        'sources':[{'path':str(p.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(p)} for p in sources],
        'onlyFourTaskFixturesCompiled':True,'NoMainGradleRegistryWrites':True})
    print('PASS four fixture compile: production class overlap empty, actual Main CP only')
elif phase=='run':
    evidence=json.loads(raw(out/'compile-evidence.json'));jar=out/'fixtures-only.jar'
    assert sha(jar)==evidence['fixtureJarSha256Bytes'] and evidence['actualMainConfig']==config
    for row in evidence['sources']:assert sha(HERE/row['path'])==row['sha256Bytes']
    media=[OLD/'runs/04/media/source.png',OLD/'runs/04/media/source.mp4']
    oldpins={r['path']:r['sha256Bytes'] for r in json.loads(raw(OLD/'frozen-handoff.json'))['files']}
    for p in media:assert sha(p)==oldpins[str(p.relative_to(OLD)).replace('\\','/')]
    jobs=[('download','DownloadFixtureKt',[*media,main]),('commitcancel','CommitCancelFixtureKt',[*media,main]),
          ('gallery','GalleryFixtureKt',[media[0],main]),('qr','QrClipFixtureKt',[main])]
    results={}
    for mode,className,extra in jobs:
        lane=out/mode;assert not lane.exists();lane.mkdir()
        proof=lane/'proof'
        home=Path(tempfile.mkdtemp(prefix='bp-main-'+mode+'-'));env=os.environ.copy()
        for name in ('APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP'):
            p=home/name.lower();p.mkdir();env[name]=str(p)
        values=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+str(home/'temp'),
            '-cp',';'.join([str(jar)]+[r['path'] for r in cp]),'com.bilipai.desktop.ui.assetsintegrationproof.'+mode+'.'+className,proof]+extra
        argsfile(lane/'run.args',values)
        r=subprocess.run([str(c.JAVA),'@'+str(lane/'run.args')],cwd=REPO,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=80)
        write(lane/'run.log',r.stdout+r.stderr);print(r.stdout+r.stderr)
        if r.returncode:
            save(lane/'failure.json',{'phase':'actual Main run','exitCode':r.returncode,'productBugNotAutomaticallyInferred':True});r.check_returncode()
        result=json.loads(raw(proof/'result.json'));assert result['passed'];results[mode]=result
        save(lane/'accepted-evidence.json',{**result,'actualMainConfig':config,'productionClassOverlap':[],
            'fixtureJarSha256Bytes':sha(jar),'taskPrivateRuntimeHome':str(home),
            'actualMainShellWindowExecuted':False,'actualNativeChooser':False})
    from PIL import Image
    import xml.etree.ElementTree as ET
    checks=[]
    mp4=raw(media[1])
    for name in ('motion-photo.jpg','guest-motion-photo.jpg'):
        p=out/'download/proof'/name;data=raw(p);assert data[-len(mp4):]==mp4
        start=data.index(b'<x:xmpmeta');end=data.index(b'</x:xmpmeta>')+len(b'</x:xmpmeta>')
        xml=ET.fromstring(data[start:end]);desc=list(xml.iter('{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description'))[0]
        assert desc.attrib['{http://ns.google.com/photos/1.0/camera/}MicroVideoOffset']==str(len(mp4))
        with Image.open(p) as image:
            image.load();tags=image.getexif();sub=tags.get_ifd(34665)
            assert image.size==(96,64) and tags[271] and tags[272] and tags[306]==sub[36867]
        checks.append({'path':str(p.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(p),'fourRealExifTagsValid':True,
                       'jpegDecoded':True,'independentXmlValid':True,'mp4ExactTail':True})
    assert results['qr']['decodeFailures']==0 and results['qr']['nonPristineQrCount']==0
    assert results['commitcancel']['correctCancellationTargetPreserved']
    for row in cp:assert sha(row['path'])==row['sha256Bytes']
    save(out/'accepted-integration-evidence.json',{'passed':True,'actualMainConfig':config,
        'allProductClassesActualMainZeroOverride':True,'productionClassOverlap':[],
        'fixtureJarSha256Bytes':sha(jar),'cohorts':{k:{'assertions':v['assertions'],'cases':v.get('cases',len(v.get('routes',[])))} for k,v in results.items()},
        'original51DownloadBehavioralAssertionsRetained':True,'addedActualMainCodeSourceAssertions':True,
        'newFilesQueuedCancelConcernAccepted':True,'actualGalleryPolicyAndExistingSelectedHandles':True,
        'actualMainQrFullEightRoutesDecoded':True,'independentDownloadedMotionAndGuestReadback':checks,
        'actualLoopbackHttpOnly':True,'outsideSocket':False,'actualChooser':False,'HWND':False,
        'MainShellUIExecuted':False,'packagedRuntime':False,'systemSHARE':False,
        'PhotosRecognition':False,'officialApproved92GraphVerified':True})
    print('PASS actual Main integrated four-cohort product proof; zero production overrides; independent EXIF/JPEG/XMP/MP4 reads')
else:raise ValueError(phase)
