"""Fresh JVM class-origin identity only; does not rerun UI or initialize product services."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
deps=json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
spec=importlib.util.spec_from_file_location('origin_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=[r['path'] for r in deps];out=HERE/'origin-classes';safe(out).mkdir(parents=True,exist_ok=False)
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
file=HERE/'origin-compiler.args';args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-module-name','product_class_origin_proof','-d',str(out),str(HERE/'ProductClassOriginFixture.kt')])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write(HERE/'origin-compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
file=HERE/'origin-runtime.args';args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(out)+';'+';'.join(cp),'com.bilipai.desktop.ui.ProductClassOriginFixtureKt',cp[0],str(HERE/'proof/class-origins.json')])
r=subprocess.run([str(c.JAVA),'@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
write(HERE/'origin-runtime.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
