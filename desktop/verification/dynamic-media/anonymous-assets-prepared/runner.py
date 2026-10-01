"""Declared Assets-only candidate overrides, real frozen Main owner, isolated loopback HTTP."""
from pathlib import Path
import difflib, hashlib, importlib.util, json, os, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
OLD=REPO/'desktop/.local/dynamic-gallery-motion-photo-parity'
SNAP=REPO/'desktop/.local/dynamic-detail-reply-main-integration/main-product-snapshot-01'
def ext(path):
    text=str(Path(path).resolve()); return Path(text if text.startswith('\\\\?\\') else '\\\\?\\'+text)
def sha(path): return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def write(path,value): path.parent.mkdir(parents=True,exist_ok=True); path.write_text(value,encoding='utf-8',newline='\n')
def save(path,value): write(path,json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def argsfile(path,values): write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
phase,attempt=sys.argv[1:3]; assert attempt.isalnum()
out=HERE/'runs'/attempt
assert sha(SNAP/'manifest.json')=='8286563186e5d5be2495a598e1e32fb15091b7a459a5bbbc3d7e65243557e290'
assert sha(SNAP/'ordered-runtime-cp.json')=='06316248361f2fae9d8eb53976ba1dc6936c5bd6416f98d2b6026c207ef9a217'
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8')); assert len(cp)==89
for row in cp: assert sha(row['path'])==row['sha256Bytes']
main=next(row['path'] for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
assert sha(OLD/'frozen-handoff.json')=='08da9ef3efd871724d1cc58482dc5945e18c9312229e4749f75e0dc405826c18'
old_manifest=json.loads((OLD/'frozen-handoff.json').read_text(encoding='utf-8'))
oldpins={r['path']:r['sha256Bytes'] for r in old_manifest['files']}
librarypins={r['path']:r['sha256Bytes'] for r in json.loads((OLD/'binary-artifact-inventory.json').read_text(encoding='utf-8'))['files']}
extra=[OLD/'dependencies'/n for n in ['commons-imaging-1.0.0-alpha6.jar','commons-io-2.19.0.jar','commons-lang3-3.17.0.jar']]
for p in extra: assert sha(p)==librarypins[str(p.relative_to(OLD)).replace('\\','/')]
cp92=cp+[{'path':str(p),'sha256Bytes':sha(p),'source':'approved graph from immutable Gallery handoff'} for p in extra]
spec=importlib.util.spec_from_file_location('motion_download_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
candidate=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
old_sources=[OLD/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui'/n for n in ['DesktopDynamicMotionPhotoFiles.kt','DesktopDynamicMotionPhotoExif.kt']]+[OLD/'generated-acceptance-final/com/android/purebilibili/feature/dynamic/components/DesktopOriginalMotionPhotoPacking.kt']
for p in old_sources: assert sha(p)==oldpins[str(p.relative_to(OLD)).replace('\\','/')]
if phase=='compile':
    assert not out.exists(); out.mkdir(parents=True)
    full=candidate.read_text(encoding='utf-8')
    omit='internal data class DesktopDynamicSaveTarget(val path: Path, val replaceExisting: Boolean)'
    assert full.count(omit)==1
    compile_asset=out/'src/DesktopDynamicImageAssets.kt'; write(compile_asset,full.replace(omit,'// Actual frozen Main DesktopDynamicSaveTarget retained; no class override.'))
    sources=[compile_asset]+old_sources+[HERE/'DownloadFixture.kt']
    for p in sources: write(out/'frozen-sources'/p.name,p.read_text(encoding='utf-8'))
    write(out/'install-source.kt',full)
    original=(HERE/'original/DesktopDynamicImageAssets.kt').read_text(encoding='utf-8')
    helpers=original[original.index('private suspend fun selectDynamicSaveTarget'):]
    actual_helpers=full[full.index('internal suspend fun selectDynamicSaveTarget'):]
    assert actual_helpers==helpers.replace('private suspend fun selectDynamicSaveTarget','internal suspend fun selectDynamicSaveTarget',1)
    write(out/'candidate-vs-original.diff',''.join(difflib.unified_diff(original.splitlines(True),full.splitlines(True),fromfile='actual-Main-ImageAssets',tofile='prepared-ImageAssets')))
    jar=out/'candidate-and-fixture.jar'
    values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(row['path'] for row in cp92),'-Xfriend-paths='+main,
            '-module-name','dynamic_motion_download_owner_parity','-d',jar]+sources
    argsfile(out/'compiler.args',values)
    r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(out/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
    all_existing=set()
    for row in cp92:
        with zipfile.ZipFile(ext(row['path'])) as z: all_existing.update(z.namelist())
    with zipfile.ZipFile(jar) as z: classes={n for n in z.namelist() if n.endswith('.class')}
    overlap=classes&all_existing
    allowed=lambda n:n.startswith('com/bilipai/desktop/ui/DesktopDynamicImageAssets$') or n in ['com/bilipai/desktop/ui/DesktopDynamicImageAssets.class','com/bilipai/desktop/ui/DesktopDynamicImageAssetsKt.class'] or n.startswith('com/bilipai/desktop/ui/DesktopDynamicImageAssetsKt$')
    assert all(allowed(n) for n in overlap),overlap
    assert 'com/bilipai/desktop/ui/DesktopDynamicSaveTarget.class' not in classes
    save(out/'compile-evidence.json',{'passed':True,'actualMainManifestSha256Bytes':sha(SNAP/'manifest.json'),'actualOrdered89CpSha256Bytes':sha(SNAP/'ordered-runtime-cp.json'),
        'ordered92Classpath':cp92,'candidateJarSha256Bytes':sha(jar),'declaredAssetsOnlyOverrideClassNames':sorted(overlap),
        'OpsSessionRepositorySaveTargetOverride':False,'soleSaveSelectorBodyUnchanged':True,'selectorVisibilityChangeOnly':'private -> internal',
        'installSourceSha256Bytes':sha(candidate),'compileAssetSourceSha256Bytes':sha(compile_asset),'compileAdaptationOnly':'omit unchanged SaveTarget declaration to reuse actual Main class',
        'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources], 'noMainGradleRegistryWrites':True})
    print('PASS candidate compile; declared Assets family overlap only; actual Main owner classes retained')
elif phase=='run':
    evidence=json.loads((out/'compile-evidence.json').read_text(encoding='utf-8'))
    assert sha(out/'candidate-and-fixture.jar')==evidence['candidateJarSha256Bytes']
    assert sha(candidate)==evidence['installSourceSha256Bytes']
    for row in evidence['sources']: assert sha(row['path'])==row['sha256Bytes']
    proof=out/'proof'; assert not proof.exists();proof.mkdir()
    home=Path(tempfile.mkdtemp(prefix='bp-download-'));env=os.environ.copy()
    for n in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
        d=home/n.lower();d.mkdir();env[n]=str(d)
    image=OLD/'runs/04/media/source.png';video=OLD/'runs/04/media/source.mp4'
    for p in [image,video]:assert sha(p)==oldpins[str(p.relative_to(OLD)).replace('\\','/')]
    values=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+str(home/'temp'),
        '-cp',';'.join([str(out/'candidate-and-fixture.jar')]+[row['path'] for row in cp92]),'com.bilipai.desktop.ui.downloadproof.DownloadFixtureKt',proof,image,video,main]
    argsfile(out/'run.args',values)
    r=subprocess.run([str(c.JAVA),'@'+str(out/'run.args')],cwd=REPO,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=75)
    write(out/'run.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
    result=json.loads((proof/'result.json').read_text(encoding='utf-8'));assert result['passed']
    from PIL import Image
    import xml.etree.ElementTree as ET
    motion=(proof/'motion-photo.jpg').read_bytes();mp4=video.read_bytes()
    assert motion[-len(mp4):]==mp4
    start=motion.index(b'<x:xmpmeta');end=motion.index(b'</x:xmpmeta>')+len(b'</x:xmpmeta>');xml=ET.fromstring(motion[start:end])
    desc=list(xml.iter('{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description'))[0]
    assert desc.attrib['{http://ns.google.com/photos/1.0/camera/}MicroVideoOffset']==str(len(mp4))
    with Image.open(proof/'motion-photo.jpg') as img:
        assert img.size==(96,64);tags=img.getexif();sub=tags.get_ifd(34665)
        assert tags[271] and tags[272] and tags[306]==sub[36867]
    for row in cp92:assert sha(row['path'])==row['sha256Bytes']
    save(out/'accepted-evidence.json',{**result,'actualMainManifestSha256Bytes':sha(SNAP/'manifest.json'),'actualOrdered89CpSha256Bytes':sha(SNAP/'ordered-runtime-cp.json'),
        'runtimeEntries':92,'candidateJarSha256Bytes':sha(out/'candidate-and-fixture.jar'),'installSourceSha256Bytes':sha(candidate),
        'declaredAssetsOnlyOverrideClassNames':evidence['declaredAssetsOnlyOverrideClassNames'],'independentPillowFourExifTagsAndJpegDecode':True,'independentXmlAndExactMp4Tail':True,
        'syntheticMediaPins':[{'path':str(p.relative_to(OLD)).replace('\\','/'),'sha256Bytes':sha(p)} for p in [image,video]],'taskPrivateNativeHome':str(home),
        'freshActualMainInstalledExecutionClaim':False})
    print('PASS independent actual downloaded EXIF/JPEG/XMP/MP4 readback; isolated loopback only, declared candidate Assets override')
else:raise ValueError(phase)
