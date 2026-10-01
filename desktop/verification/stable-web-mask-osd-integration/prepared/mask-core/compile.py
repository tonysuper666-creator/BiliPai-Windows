from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;MAIN=L.parents[3]/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-31'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(SNAP/'manifest.json')=='999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d'
assert sha(SNAP/'ordered-runtime-cp.json')=='0da93f5423ef53986a1939d97b37114a97e4a8e64b646052d34eaeaa916a6dee'
cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
for r in cp:assert sha(r['path'])==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
names=['WebMaskParser.kt','DanmakuMaskFrame.kt','DesktopOriginalWebMaskProtocol.kt','DesktopOriginalWebMaskOwner.kt','DesktopOriginalWebMaskRefreshPolicy.kt']
sources=[]
for name in names:
 match=list(safe(L/'generated').rglob(name));assert len(match)==1;sources+=match
sources+=list(safe(L/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list(safe(L/'review-only').rglob('*.kt'))
out=L/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir()
sources += list(safe(L/'proof').rglob('*.kt')) if safe(L/'proof').exists() else []
target=out/'original-web-mask-candidate.jar';classpath=[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-Xplugin='+str(c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')),'-module-name','prepared_original_webmask','-d',str(target)]+list(map(str,sources))
safe(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
safe(out/'compiler.log').write_text(p.stdout+p.stderr,encoding='utf-8');print((p.stdout+p.stderr)[-9000:]);assert p.returncode==0
allActual=set()
for r in cp:
 with zipfile.ZipFile(safe(r['path'])) as z:allActual.update(n for n in z.namelist() if n.endswith('.class'))
with zipfile.ZipFile(safe(target)) as z:classes=[n for n in z.namelist() if n.endswith('.class')]
overlap=sorted(set(classes)&allActual)
assert all(n.startswith(('com/bilipai/desktop/danmaku/DanmakuOverlay','com/bilipai/desktop/danmaku/DanmakuPoolSourceSnapshot','com/bilipai/desktop/DesktopPlaybackController','com/bilipai/desktop/DesktopPlaybackDataSource','com/bilipai/desktop/DesktopPlaybackState','com/bilipai/desktop/danmaku/DanmakuSettings','com/bilipai/desktop/ui/DesktopDanmakuSettingsProjectionKt','com/bilipai/desktop/ui/DesktopDanmakuPresentation','com/bilipai/desktop/danmaku/DesktopOriginalDanmakuRenderPlatform','com/bilipai/desktop/danmaku/DesktopWindowsDanmakuRenderPlatform','com/bilipai/desktop/danmaku/DesktopDanmakuConfigLog','com/bilipai/desktop/danmaku/DesktopDanmakuPaintGeometry')) for n in overlap),overlap
if (L/'proof').exists():
 r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(target)]+classpath),'com.bilipai.desktop.danmaku.WebMaskProofKt',str(out)],capture_output=True,text=True,encoding='utf-8',timeout=80)
 safe(out/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr)[-8000:]);assert r.returncode==0
for r in cp:assert sha(r['path'])==r['sha256Bytes']
save(out/'compile-result.json',dict(status='PASS',actualWholeSnapshot=sha(SNAP/'manifest.json'),actual92CP=sha(SNAP/'ordered-runtime-cp.json'),jarSha256Bytes=sha(target),sourceInputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],declaredExistingClassFamilies=overlap,newClassOverlapZero=True,sourceOnly=True,actualRootWindowRuntime=False))
print('COMPILE PASS',sha(out/'compile-result.json'))
