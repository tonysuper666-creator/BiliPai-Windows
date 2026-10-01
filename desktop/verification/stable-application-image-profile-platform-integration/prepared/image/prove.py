from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-43';OUT=LANE/('proof-'+sys.argv[1]);assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_bytes());candidate=LANE/'compile-03/candidate.jar';assert candidate.exists()
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
jar=OUT/'fixture.jar';classpath=str(candidate)+';'+';'.join(r['path']for r in cp)
fixtureSource=OUT/'ImageLoaderFixture.kt';fixtureSource.write_bytes((LANE/'ImageLoaderFixture.kt').read_bytes())
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xfriend-paths='+str(candidate)+','+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),str(fixtureSource)]
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'inspect compiler.log'
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(jar)+';'+classpath,'com.bilipai.desktop.ui.ImageLoaderFixtureKt',str(OUT/'synthetic-local-cache')],capture_output=True,text=True,encoding='utf-8',timeout=45)
(OUT/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and 'PASS application image loader' in r.stdout,'inspect runtime.log'
print(r.stdout)
