from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
P=Path(__file__).resolve().parent;M=P.parents[2];cache=M.parent/'toolchain/gradle-home/caches/modules-2/files-2.1'
cp=json.loads((M/'desktop/.local/stable-product-snapshot-85/ordered-runtime-cp.json').read_text())
extra=[r['path']for r in json.loads((P/'dependencies/identities.json').read_text())if r['type']=='jar']
tests=[]
for group,name in [('org.jetbrains.kotlin','kotlin-test'),('org.jetbrains.kotlin','kotlin-test-junit5'),('org.junit.jupiter','junit-jupiter-api'),('org.apiguardian','apiguardian-api'),('org.opentest4j','opentest4j')]:
 files=list((cache/group/name).rglob('*.jar'));assert len(files)==1,(name,files);tests.append(str(files[0]))
out=P/('junit-prospective-compile-'+sys.argv[1]);out.mkdir(exist_ok=False);prod=P/'runs'/sys.argv[2]/'prospective.jar'
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
source=P/'prepared/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalMessagePagesTest.kt'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(prod)+','+main,'-cp',';'.join([str(prod)]+[r['path']for r in cp]+extra+tests),'-d',str(out/'classes'),str(source)]
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=120)
(out/'compile.log').write_bytes(r.stdout+r.stderr)
(out/'result.json').write_text(json.dumps(dict(passed=r.returncode==0,actualProductSnapshot=85,prospectiveOverrides=21,mainJUnitExecuted=False,actualMethods=14,additionalTestDependencies=[dict(path=f,sha256Bytes=hashlib.sha256(Path(f).read_bytes()).hexdigest())for f in tests]),indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
