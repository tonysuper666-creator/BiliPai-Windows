from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;MAIN=L.parents[3]/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-22';PANEL=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity/compile-06/original-danmaku-settings.jar'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
version=sys.argv[1] if len(sys.argv)>1 else '04';proofVersion=sys.argv[2] if len(sys.argv)>2 else '01'
prepared=L/('compile-'+version)/'original-danmaku-render-config-consumers.jar';result=json.loads(safe(prepared.parent/'compile-result.json').read_text());assert result['status']=='PASS' and result['jarSha256Bytes']==sha(prepared)
relocated=MAIN/'desktop/.local/stable-miuix5157-original-runtime/ordered-runtime-cp-22.json';assert sha(relocated)=='65e50623a5e52a7a4f0c7421e55dbd20bae752b5c53add8208be9c1f8c9772ae';cp=json.loads(safe(relocated).read_text())
for x in cp:assert sha(x['path'])==x['sha256Bytes']
assert sha(PANEL)=='549caaf0e59e8cfb46ab07be964b0b163b5f4358e61cfc152167961e46dac63e'
out=L/('proof-'+proofVersion);assert not out.exists();out.mkdir()
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
classpath=[str(prepared),str(PANEL)]+[x['path'] for x in cp];target=out/'fixture.jar';source=L/'proof/RenderConfigProof.kt'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(prepared)+','+str(PANEL)+','+str(SNAP/'main-kotlin.jar'),'-module-name','fixture_original_danmaku_render_config','-d',str(target),str(source)]
safe(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
safe(out/'compiler.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n');print((p.stdout+p.stderr)[-8000:]);assert p.returncode==0
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(target)]+classpath),'com.bilipai.desktop.danmaku.RenderConfigProofKt',str(out)],capture_output=True,text=True,encoding='utf-8',timeout=60)
safe(out/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n');print((r.stdout+r.stderr)[-9000:]);assert r.returncode==0
for x in cp:assert sha(x['path'])==x['sha256Bytes']
assert sha(PANEL)=='549caaf0e59e8cfb46ab07be964b0b163b5f4358e61cfc152167961e46dac63e' and result['jarSha256Bytes']==sha(prepared)
save(out/'proof-inputs.json',dict(snapshotManifest=sha(SNAP/'manifest.json'),relocatedCP=sha(relocated),preparedJar=sha(prepared),preparedCompileResult=sha(prepared.parent/'compile-result.json'),fixtureSource=sha(source),fixtureJar=sha(target),settings179Jar=sha(PANEL),noHTTPOrHWND=True,proofResult=sha(out/'proof-result.json')))
print('PROOF PASS',sha(out/'proof-inputs.json'))
