from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;MAIN=L.parents[3]/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-31';MASK=L.parent/'stable-danmaku-web-mask-parity';MEDIA=MAIN/'desktop/.local/stable-home-platform-media-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(SNAP/'manifest.json')=='999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d'
assert sha(SNAP/'ordered-runtime-cp.json')=='0da93f5423ef53986a1939d97b37114a97e4a8e64b646052d34eaeaa916a6dee'
assert sha(MASK/'frozen-handoff.json')=='5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5'
assert sha(MEDIA/'frozen-handoff.json')=='9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966'
core=MASK/'compile-09/original-web-mask-candidate.jar';media=MEDIA/'runs/04/prepared-home-platform.jar'
coreReceipt=json.loads(safe(MASK/'compile-09/compile-result.json').read_text());assert sha(core)==coreReceipt['jarSha256Bytes']
assert sha(media)=='ec6c0e4a310bdfec4f10377ca65c7198c869073a1c9f5a426cea7291290cc4f0'
cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
pins=[dict(path=str(core),sha256Bytes=sha(core),kind='declared prospective frozen mask core100'),dict(path=str(media),sha256Bytes=sha(media),kind='declared prospective frozen media74')]+cp
for r in pins:assert sha(r['path'])==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list(safe(L/'review-only').rglob('*.kt'))+list(safe(L/'proof').rglob('*.kt'));assert len(sources)==5
out=L/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir()
target=out/'native-osd-mask-candidate.jar';classpath=[r['path'] for r in pins]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+','.join(map(str,[SNAP/'main-kotlin.jar',core,media])),'-module-name','prepared_native_osd_mask','-d',str(target)]+list(map(str,sources))
safe(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
safe(out/'compiler.log').write_text(p.stdout+p.stderr,encoding='utf-8');print((p.stdout+p.stderr)[-9000:]);assert p.returncode==0
actual=set();prospective=set()
for r in cp:
 with zipfile.ZipFile(safe(r['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
for path in [core,media]:
 with zipfile.ZipFile(safe(path)) as z:prospective.update(n for n in z.namelist() if n.endswith('.class'))
with zipfile.ZipFile(safe(target)) as z:classes=set(n for n in z.namelist() if n.endswith('.class'))
families=('com/bilipai/desktop/player/MpvPlayer','com/bilipai/desktop/player/MpvCallException','com/bilipai/desktop/player/PlayerVideoOutputState','com/bilipai/desktop/player/PlayerVideoShaderOptions','com/bilipai/desktop/player/NativeVideoCapability','com/bilipai/desktop/player/PlayerVideoOutputKt','com/bilipai/desktop/danmaku/DanmakuOverlay','com/bilipai/desktop/danmaku/DanmakuPoolSourceSnapshot','com/bilipai/desktop/danmaku/DesktopWebMaskPath')
overlap=sorted(classes&(actual|prospective));assert all(n.startswith(families) for n in overlap),overlap
assert 'com/bilipai/desktop/player/PlayerVideoViewport.class' not in actual|prospective
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(target)]+classpath),'com.bilipai.desktop.danmaku.OsdMaskProofKt',str(out)],capture_output=True,text=True,encoding='utf-8',timeout=80)
safe(out/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr)[-8000:]);assert r.returncode==0
for r in pins:assert sha(r['path'])==r['sha256Bytes']
save(out/'compile-result.json',dict(status='PASS',actualWholeSnapshot=sha(SNAP/'manifest.json'),actual92CP=sha(SNAP/'ordered-runtime-cp.json'),prospectiveJarInputs=pins[:2],jarSha256Bytes=sha(target),sourceInputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],declaredExistingClassFamilies=overlap,newClassOverlapZero=True,sourceOnly=True,actualRootWindowRuntime=False,nativeMpvExecuted=False))
save(out/'ordered-cp-receipt.json',pins)
print('COMPILE PASS',sha(out/'compile-result.json'))
