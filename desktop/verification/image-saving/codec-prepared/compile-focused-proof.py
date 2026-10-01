"""Compile and execute prepared stateless codec only, on immutable actual Main03/92.
Fixture inputs/outputs, native caches and compiler logs remain in this owned lane.
"""
from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = ROOT / 'desktop/.local/dynamic-media-main-integration/main-product-snapshot-03'
def safe(p):
    s = str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, text):
    safe(p.parent).mkdir(parents=True, exist_ok=True); safe(p).write_text(text, encoding='utf-8', newline='\n')
def save(p, value): write(p, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
assert sha(SNAP/'manifest.json') == 'f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6'
assert sha(SNAP/'ordered-runtime-cp.json') == '3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac'
cp = json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8')); assert len(cp) == 92
for row in cp: assert sha(row['path']) == row['sha256Bytes']
main = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
spec = importlib.util.spec_from_file_location('static_codec_compiler', ROOT/'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
run = HERE/(sys.argv[1] if len(sys.argv) > 1 else 'proof-01'); assert not safe(run).exists()
sources = [HERE/'generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalStaticGalleryFormat.kt',
    HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicStaticImageCodec.kt', HERE/'StaticGalleryCodecFixture.kt']
retained = []
for p in sources:
    target = run/'sources'/p.name; write(target, safe(p).read_text(encoding='utf-8')); retained.append(target)
jar = run/'candidate-codec-fixture.jar'
classpath = ';'.join(row['path'] for row in cp)
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', classpath, '-Xfriend-paths='+main,
    '-module-name', 'static_gallery_codec_task_candidate', '-d', jar] + retained
write(run/'compiler.args', '\n'.join('"'+str(v).replace('\\','/')+'"' for v in args)+'\n')
r = subprocess.run([str(c.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@'+str(run/'compiler.args')], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
write(run/'compile.log', r.stdout+r.stderr); print(r.stdout+r.stderr); r.check_returncode()
existing = set(); wrappers = {p:set() for p in ['com.bilipai.desktop.ui','com.android.purebilibili.feature.dynamic.components']}
for row in cp:
    with zipfile.ZipFile(safe(row['path'])) as z:
        existing.update(n for n in z.namelist() if n.endswith('.class'))
        for n in z.namelist():
            if n.endswith('Kt.class') and '$' not in n:
                fqn = n[:-6].replace('/','.'); package = fqn.rsplit('.',1)[0]
                if package in wrappers: wrappers[package].add(fqn)
with zipfile.ZipFile(safe(jar)) as z: classes = {n for n in z.namelist() if n.endswith('.class')}
assert not(classes & existing), classes & existing
def methods(names, cp_value, output):
    txt = subprocess.check_output([str(c.JAVA.parent/'javap.exe'), '-p', '-s', '-classpath', cp_value, *sorted(names)], text=True, encoding='utf-8')
    write(run/output, txt); member=None; keys=set()
    for line in txt.splitlines():
        if line.strip().startswith('public static') and '(' in line: member=line.split('(')[0].split()[-1]
        elif line.strip().startswith('descriptor:') and member:
            if not member.startswith('access$') and '$default' not in member: keys.add((member,line.split(':',1)[1].strip().split(')',1)[0]+')'))
            member=None
    return keys
signatures=[]
for package, renderer in [('com.bilipai.desktop.ui','DesktopDynamicStaticImageCodecKt'),('com.android.purebilibili.feature.dynamic.components','DesktopOriginalStaticGalleryFormatKt')]:
    candidate=methods([package+'.'+renderer], str(jar)+';'+classpath, renderer+'-candidate-methods.txt')
    base=methods(wrappers[package], classpath, renderer+'-actual-methods.txt')
    assert not(candidate & base), candidate & base
    signatures.append(dict(package=package,candidateMethods=len(candidate),actualMethods=len(base),intersection=[]))

from PIL import Image, ImageOps
proof = run/'fixture'; inputs=proof/'inputs'; safe(inputs).mkdir(parents=True,exist_ok=True)
alpha=Image.new('RGBA',(96,64))
for y in range(64):
    for x in range(96): alpha.putpixel((x,y),((x*7)%256,(y*11)%256,((x+y)*3)%256,(x*13+y*5)%256))
alpha.save(safe(inputs/'alpha.png'))
rgb=Image.new('RGB',(96,64))
for y in range(64):
    for x in range(96): rgb.putpixel((x,y),(220 if x<48 else 20,180 if y<32 else 30,170 if (x<48)==(y<32) else 40))
rgb.save(safe(inputs/'rgb.png'))
frames=[Image.new('RGB',(20,14),color) for color in [(255,0,0),(0,255,0),(0,0,255)]]
frames[0].save(safe(inputs/'animated.gif'),save_all=True,append_images=frames[1:],duration=100,loop=0)
frames[0].save(safe(inputs/'animated.webp'),save_all=True,append_images=frames[1:],duration=100,loop=0,lossless=True)
for origin in range(1,9):
    exif=Image.Exif(); exif[274]=origin; rgb.save(safe(inputs/f'origin-{origin}.jpg'),quality=95,subsampling=0,exif=exif)
home=run/'private-home'; safe(home/'temp').mkdir(parents=True,exist_ok=True)
runtime_args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+str(home/'temp'),'-cp',str(jar)+';'+classpath,
    'com.bilipai.desktop.ui.StaticGalleryCodecFixtureKt',str(proof)]
write(run/'runtime.args','\n'.join('"'+str(v).replace('\\','/')+'"' for v in runtime_args)+'\n')
r=subprocess.run([str(c.JAVA),*runtime_args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(run/'runtime.log',r.stdout+r.stderr); print(r.stdout+r.stderr); r.check_returncode()
result=json.loads(safe(proof/'kotlin-result.json').read_text(encoding='utf-8')); assert result['passed']
independent=[]
def check(value,label,details=None):
    independent.append(dict(label=label,passed=bool(value),details=details)); assert value,label
with Image.open(safe(proof/'outputs/alpha.png')) as out:
    check(out.format=='PNG','PNG container')
    source_pixels=list(alpha.get_flattened_data()); output_pixels=list(out.convert('RGBA').get_flattened_data())
    check(all(a[3]==b[3] for a,b in zip(source_pixels,output_pixels)),'decoded PNG alpha channel exact')
    check(all(a==b for a,b in zip(source_pixels,output_pixels) if a[3]==255),'decoded PNG opaque pixels exact')
    visible_error=max(abs(round(a[channel]*a[3]/255)-round(b[channel]*b[3]/255)) for a,b in zip(source_pixels,output_pixels) for channel in range(3))
    check(visible_error<=1,'decoded PNG visible premultiplied colour preserved',{'maxBlackCompositeChannelError':visible_error,
        'hiddenRgbExact':out.convert('RGBA').tobytes()==alpha.tobytes(), 'nativePremultipliedConversionObserved':True})
rgb.save(safe(proof/'pillow-reference95.jpg'),quality=95)
with Image.open(safe(proof/'outputs/jpeg95.jpg')) as out,Image.open(safe(proof/'pillow-reference95.jpg')) as q95:
    check(out.format=='JPEG','remaining static JPEG container')
    check(out.quantization==q95.quantization,'JPEG quantization independently matches Pillow quality95')
for origin in range(1,9):
    with Image.open(safe(inputs/f'origin-{origin}.jpg')) as source,Image.open(safe(proof/f'outputs/origin-{origin}.jpg')) as out:
        expected=ImageOps.exif_transpose(source).convert('RGB'); actual=out.convert('RGB')
        check(actual.size==expected.size,f'EXIF origin{origin} output dimensions',{ 'expected':expected.size,'actual':actual.size })
        diff=[abs(a-b) for a,b in zip(actual.tobytes(),expected.tobytes())]; mean=sum(diff)/len(diff)
        check(mean<4,f'EXIF origin{origin} output pixels match independent transpose',{'meanAbsoluteChannelError':mean})
check(safe(proof/'outputs/raw.gif').read_bytes()==safe(inputs/'animated.gif').read_bytes(),'raw GIF independently byte-equal')
check(safe(proof/'outputs/raw.webp').read_bytes()==safe(inputs/'animated.webp').read_bytes(),'raw WebP independently byte-equal')
for row in cp: assert sha(row['path'])==row['sha256Bytes']
save(run/'independent-pillow-result.json',dict(passed=True,assertions=len(independent),checks=independent,AndroidBitmapExecuted=False))
save(run/'accepted-evidence.json',dict(passed=True,caseCount=result['caseCount'],kotlinAssertions=result['assertions'],independentPillowAssertions=len(independent),
    actualMain03ManifestSha256Bytes=sha(SNAP/'manifest.json'),orderedRuntimeCpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),
    runtimeCpCount=len(cp),runtimeBinaryPinsPrePostVerified=True,candidateJarSha256Bytes=sha(jar),candidateClassCount=len(classes),
    classFqnIntersection=[],packageMethodAudits=signatures,declaredMainOverrides=[],
    compiledSources=[dict(path=str(p.relative_to(HERE)).replace('\\','/'),sha256Bytes=sha(p)) for p in retained],
    PNGJPEGSkiaNativeExecuted=True,WindowsExifOriginsOneThroughEightObserved=True,originalAndroidBitmapParityProven=False,
    sameAssetsOwnerOrStoreIntegrated=False,codecStageOnly=True,HTTP=False,HWND=False,MainChanged=False,sourceRegistryChanged=False,sharedGradle=False))
print(json.dumps(dict(accepted=str(run/'accepted-evidence.json'),sha256Bytes=sha(run/'accepted-evidence.json'))))
