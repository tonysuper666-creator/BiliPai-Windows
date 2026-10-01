from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;MAIN=L.parents[3]/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-22';PANEL=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity/compile-06/original-danmaku-settings.jar'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
out=L/('existing-tests-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir()
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
libs=[c.jar('org.jetbrains.kotlin','kotlin-test','2.4.0'),c.jar('org.jetbrains.kotlin','kotlin-test-junit5','2.4.0'),c.jar('org.junit.jupiter','junit-jupiter-api','5.10.1'),c.jar('org.junit.platform','junit-platform-commons','1.10.1'),c.jar('org.opentest4j','opentest4j','1.3.0'),c.jar('org.apiguardian','apiguardian-api','1.1.2')]
cp=json.loads(safe(MAIN/'desktop/.local/stable-miuix5157-original-runtime/ordered-runtime-cp-22.json').read_text());prepared=L/'compile-06/original-danmaku-render-config-consumers.jar'
for x in cp:assert sha(x['path'])==x['sha256Bytes']
classes=[str(prepared),str(PANEL)]+[x['path'] for x in cp]+list(map(str,libs));target=out/'fixture.jar';sources=[L/'review-only-tests/desktop/src/test/kotlin/com/bilipai/desktop/danmaku/DanmakuTest.kt',L/'proof/ExistingTestsReceipt.kt']
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classes),'-Xfriend-paths='+str(prepared)+','+str(PANEL)+','+str(SNAP/'main-kotlin.jar'),'-module-name','explicit_danmaku_tests_fixture','-d',str(target)]+list(map(str,sources))
safe(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=120);safe(out/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8');print((p.stdout+p.stderr)[-6000:]);assert p.returncode==0
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(target)]+classes),'com.bilipai.desktop.danmaku.ExistingTestsReceiptKt'],capture_output=True,text=True,encoding='utf-8',timeout=60);safe(out/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr)[-6000:]);assert r.returncode==0
for x in cp:assert sha(x['path'])==x['sha256Bytes']
save(out/'result.json',dict(status='PASS',output=r.stdout,testLibraries=[dict(path=str(p),sha256Bytes=sha(p)) for p in libs],fixtureJar=sha(target),sources=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],preparedJar=sha(prepared),testOnly=True,noGradleOrDependenciesModified=True))
