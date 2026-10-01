from pathlib import Path
import hashlib,importlib.util,json,os,shutil,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
SNAP=REPO/'desktop/.local/dynamic-detail-container-main-integration/main-product-snapshot-02'
def ext(p):
    s=str(Path(p).resolve());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def raw(p):return ext(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_text(v,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def argsfile(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
attempt=sys.argv[1];assert attempt.isalnum();out=HERE/'runs'/attempt;assert not out.exists();out.mkdir(parents=True)
manifest='f164846bb1ebe5545c727824bfa6fe853a5fca92bfd1bcfddf59abb53c943d17'
cpsha='be8a62821f74e73653ed2354afbb1de3d6ec8236db68f056328a482a5e0f4d9d'
assert sha(SNAP/'manifest.json')==manifest and sha(SNAP/'ordered-runtime-cp.json')==cpsha
cp=json.loads(raw(SNAP/'ordered-runtime-cp.json'));assert len(cp)==89
for row in cp:assert sha(row['path'])==row['sha256Bytes']
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
audit=json.loads(raw(HERE/'source-audit.json'))
canvas=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommentImageCanvas.kt'
assert sha(canvas)==audit['candidateCanvasSha256Bytes']
renderer=next((HERE/'generated/candidate').rglob('DesktopOriginalReplyImageRenderer.kt'))
assert sha(renderer)==audit['candidateRendererSha256Bytes']
canvastext=raw(canvas).decode('utf-8')
begin=canvastext.index('internal class DesktopCommentCanvas(')
end=canvastext.index('\n/** Only an explicitly chosen target',begin)
partial=out/'DesktopCommentCanvas.kt'
write(partial,'package com.bilipai.desktop.ui\nimport java.awt.Color\nimport java.awt.RenderingHints\nimport java.awt.geom.AffineTransform\nimport java.awt.geom.Rectangle2D\nimport java.awt.geom.RoundRectangle2D\nimport java.awt.image.BufferedImage\n'+canvastext[begin:end])
fixture=out/'QrClipFixture.kt';shutil.copyfile(HERE/fixture.name,fixture)
rendercopy=out/'DesktopOriginalReplyImageRenderer.kt';shutil.copyfile(renderer,rendercopy)
spec=importlib.util.spec_from_file_location('qr_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
existing=set()
for row in cp:
    with zipfile.ZipFile(ext(row['path'])) as z:existing.update(z.namelist())
results={}
for mode in ('baseline','candidate'):
    lane=out/mode;lane.mkdir();jar=lane/'fixture-and-declared-candidate.jar'
    sources=[fixture]+([partial,rendercopy] if mode=='candidate' else [])
    values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+main,
        '-module-name','comment_qr_'+mode,'-d',jar]+sources
    argsfile(lane/'compiler.args',values)
    r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(lane/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(lane/'compile.log',r.stdout+r.stderr)
    if r.returncode:
        save(lane/'failure.json',{'phase':'compile','exitCode':r.returncode});print(r.stdout+r.stderr);r.check_returncode()
    with zipfile.ZipFile(jar) as z:classes={n for n in z.namelist() if n.endswith('.class')}
    overlap=classes&existing
    allowed=lambda n:n=='com/bilipai/desktop/ui/DesktopCommentCanvas.class' or n.startswith('com/bilipai/desktop/ui/DesktopCommentCanvas$') or n=='com/android/purebilibili/feature/video/ui/components/DesktopOriginalReplyImageRendererKt.class' or n.startswith('com/android/purebilibili/feature/video/ui/components/DesktopOriginalReplyImageRendererKt$')
    assert (not overlap) if mode=='baseline' else all(allowed(n) for n in overlap)
    assert 'com/bilipai/desktop/ui/DesktopCommentImageCanvasKt.class' not in classes
    save(lane/'compile-evidence.json',{'passed':True,'fixtureJarSha256Bytes':sha(jar),'declaredProductionClassOverlap':sorted(overlap),
        'actualMainManifestSha256Bytes':manifest,'ordered89Classpath':cp,'writerSpecPaintBitmapFactoryOverride':False,
        'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources]})
    home=Path(tempfile.mkdtemp(prefix='bp-qr-'+mode+'-'));env=os.environ.copy()
    for n in ('APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP'):
        p=home/n.lower();p.mkdir();env[n]=str(p)
    proof=lane/'proof'
    values=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+str(home/'temp'),
        '-cp',';'.join([str(jar)]+[r['path'] for r in cp]),'com.bilipai.desktop.ui.qrclipproof.QrClipFixtureKt',mode,proof,main,jar]
    argsfile(lane/'run.args',values)
    r=subprocess.run([str(c.JAVA),'@'+str(lane/'run.args')],cwd=REPO,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
    write(lane/'run.log',r.stdout+r.stderr);print(r.stdout+r.stderr)
    if r.returncode:
        save(lane/'failure.json',{'phase':'actual runtime','exitCode':r.returncode,'MainInstalled':False});r.check_returncode()
    result=json.loads(raw(proof/'result.json'));assert result['passed'];results[mode]=result
    save(lane/'accepted-evidence.json',{**result,'actualMainManifestSha256Bytes':manifest,'actualOrdered89CpSha256Bytes':cpsha,
        'fixtureJarSha256Bytes':sha(jar),'declaredProductionClassOverlap':sorted(overlap),'taskPrivateHome':str(home)})
from PIL import Image
from PIL import ImageChops
changes=[]
for base,cand in zip(results['baseline']['routes'],results['candidate']['routes']):
    assert {k:v for k,v in base.items() if k not in ('decodedPayload','qrDecoded','originalQrMatrixPixelsExact','decodeFailure')}=={k:v for k,v in cand.items() if k not in ('decodedPayload','qrDecoded','originalQrMatrixPixelsExact','decodeFailure')}
    with Image.open(out/'baseline/proof'/f"{base['name']}.png") as a,Image.open(out/'candidate/proof'/f"{base['name']}.png") as b:
        a=a.convert('RGB');b=b.convert('RGB');assert a.size==b.size
        bounds=ImageChops.difference(a,b).getbbox()
        if bounds:assert bounds[0]>=base['qrLeft'] and bounds[1]>=base['qrTop'] and bounds[3]<=base['qrTop']+148,(base['name'],bounds)
        changes.append({'name':base['name'],'changedPixelBounds':bounds,'originalPayload':base['expectedFullOriginalPayload'],
                        'candidatePayloadDecoded':cand['decodedPayload'],'candidateQrPixelsPristine':cand['originalQrMatrixPixelsExact']})
assert results['candidate']['decodeFailures']==0 and results['candidate']['nonPristineQrCount']==0
assert results['baseline']['decodeFailures']>0
for row in cp:assert sha(row['path'])==row['sha256Bytes']
save(out/'accepted-comparison.json',{'passed':True,'actualMainManifestSha256Bytes':manifest,'actualOrdered89CpSha256Bytes':cpsha,
    'baselineWindowsDecodeFailures':results['baseline']['decodeFailures'],'candidateWindowsDecodeFailures':0,
    'candidateAssertions':results['candidate']['assertions'],'routes':len(changes),'pixelComparisons':changes,
    'specAllFieldsEqualBaselineCandidate':True,'originalGeometryAndFontsPreserved':True,
    'changedPixelsOnlyPrintedFooterAtOrBeyondQrLeft':True,'printedUrlMayBeClipped':True,
    'originalQRPayloadCompleteAndExact':True,'AndroidActualRenderAccepted':False,'MainInstalled':False,
    'writerSpecPaintBitmapFactoryActualMainNotOverridden':True,'declaredOverrides':'Canvas family + renderer family only',
    'sourceAuditSha256Bytes':sha(HERE/'source-audit.json'),'HTTP':False,'HWND':False,'picker':False})
print('PASS independent PNG pixel comparison: same original specs/geometry, only printed URL right region changes; all eight full payloads decoded')
