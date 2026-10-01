from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ADV=L.parent/'stable-danmaku-render-config-consumers-parity';MAIN=L.parents[3]/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-22';PANEL=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity/compile-06/original-danmaku-settings.jar'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(ADV/'frozen-handoff.json')=='6d221c053309abd218b55a129634726bf5f99f28d4458a374069351468b69103'
out=L/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir()
relocated=MAIN/'desktop/.local/stable-miuix5157-original-runtime/ordered-runtime-cp-22.json';assert sha(relocated)=='65e50623a5e52a7a4f0c7421e55dbd20bae752b5c53add8208be9c1f8c9772ae';cp=json.loads(safe(relocated).read_text());assert len(cp)==92
for r in cp:assert sha(r['path'])==r['sha256Bytes']
prior=ADV/'compile-06/original-danmaku-render-config-consumers.jar';priorSHA=sha(prior);assert priorSHA==json.loads(safe(ADV/'compile-06/compile-result.json').read_text())['jarSha256Bytes']
assert sha(PANEL)=='549caaf0e59e8cfb46ab07be964b0b163b5f4358e61cfc152167961e46dac63e'
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list(safe(L/'review-only').rglob('*.kt'))+[L/'proof/MonitorViewportProof.kt'];target=out/'monitor-candidate-and-fixture.jar';classpath=[str(prior),str(PANEL)]+[x['path'] for x in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+str(prior)+','+str(PANEL)+','+str(SNAP/'main-kotlin.jar'),'-module-name','prepared_required_monitor_port_delta','-d',str(target)]+list(map(str,sources))
safe(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=150);safe(out/'compiler.log').write_text(p.stdout+p.stderr,encoding='utf-8');print((p.stdout+p.stderr)[-8000:]);assert p.returncode==0
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(target)]+classpath),'com.bilipai.desktop.danmaku.MonitorViewportProofKt',str(out)],capture_output=True,text=True,encoding='utf-8',timeout=40);safe(out/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr)[-8000:]);assert r.returncode==0
for x in cp:assert sha(x['path'])==x['sha256Bytes']
assert sha(prior)==priorSHA
save(out/'compile-result.json',dict(status='PASS',productionSources=2,proofSources=1,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],prospectiveJar=sha(target),priorAdvancedJar=priorSHA,relocatedActual22CP=sha(relocated),actualProductNotChanged=True,wholeCommandCallerNotCompiled=True,commandExpressionABIChecked=True,actualRootRuntimeAcceptance=False))
print('COMPILE/PROOF PASS',sha(out/'compile-result.json'))
