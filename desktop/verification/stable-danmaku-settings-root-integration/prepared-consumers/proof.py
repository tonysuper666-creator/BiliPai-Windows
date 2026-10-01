from pathlib import Path
import importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('consumer',HERE/'compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=json.loads(c.safe(c.MAIN/'desktop/.local/stable-miuix5157-original-runtime/ordered-runtime-cp-22.json').read_text())
inputs=HERE/'compile-03/original-danmaku-root-consumers.jar'
pins=c.pins(cp)
spec=importlib.util.spec_from_file_location('compiler',c.MAIN/'desktop/.local/source9-appearance/compile-miuix.py');k=importlib.util.module_from_spec(spec);spec.loader.exec_module(k)
out=HERE/'proof-02';out.mkdir(exist_ok=False);target=out/'root-consumer-proof.jar'
paths=[str(inputs),str(c.PANEL)]+[x['path'] for x in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(paths),'-Xfriend-paths='+str(inputs)+','+str(c.PANEL)+','+str(c.SNAP/'main-kotlin.jar'),'-module-name','root_danmaku_consumer_proof','-d',str(target),str(HERE/'proof/RootDanmakuConsumerProof.kt')]
(out/'compiler.args').write_text('\n'.join('"'+a.replace(chr(92),'/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
r=subprocess.run([str(k.JAVA),'-Xmx1g','-cp',';'.join(map(str,k.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=60)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stdout+r.stderr
r=subprocess.run([str(k.JAVA),'-cp',';'.join([str(target)]+paths),'com.bilipai.desktop.ui.RootDanmakuConsumerProofKt'],capture_output=True,text=True,encoding='utf-8',timeout=30)
(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stdout+r.stderr
c.save(out/'result.json',dict(status='PASS',output=r.stdout.strip(),groups=3,assertions=27,consumerJarSHA256=c.sha(inputs),panelJarSHA256=c.sha(c.PANEL),runtimePinsAfter=c.pins(cp),nativeWindowAndChooserNotExercised=True))
print(r.stdout)
