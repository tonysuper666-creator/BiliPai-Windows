from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-36'
def safe(path):return Path('\\\\?\\'+str(Path(path).absolute()))
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def save(path,value):safe(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def main():
 out=HERE/'runs/fixture-01';assert not out.exists();out.mkdir()
 cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
 candidate=HERE/'runs/compile-02/prepared-category-page.jar';candidateSha=sha(candidate)
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 source=HERE/'fixtures/CategoryVmFixture.kt';copy=out/source.name;copy.write_bytes(source.read_bytes())
 jar=out/'category-fixture.jar';classpath=';'.join([str(candidate)]+[r['path'] for r in cp])
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xfriend-paths='+str(SNAP/'main-kotlin.jar')+','+str(candidate),'-module-name','prepared_category_page','-d',str(jar),str(copy)]
 argfile=out/'compiler.args';argfile.write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8')
 toolPins=[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA]+c.COMPILER]
 save(out/'dependency-pins-before.json',dict(runtime=cp,candidate=dict(path=str(candidate),sha256Bytes=candidateSha),tools=toolPins,sourceSha256Bytes=sha(copy)))
 compilation=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=120)
 (out/'compiler.log').write_text(compilation.stdout+compilation.stderr,encoding='utf-8');assert compilation.returncode==0,compilation.stdout+compilation.stderr
 with zipfile.ZipFile(jar) as z:
  assert all(name.startswith('categoryproof/') or name.startswith('META-INF/') for name in z.namelist())
 run=subprocess.run([str(c.JAVA),'-cp',str(jar)+';'+classpath,'categoryproof.CategoryVmFixtureKt',str(out/'real-global-store')],capture_output=True,text=True,encoding='utf-8',timeout=30)
 (out/'runtime.log').write_text(run.stdout+run.stderr,encoding='utf-8');assert run.returncode==0,run.stdout+run.stderr
 assert 'RESULT assertions=16 groups=3' in run.stdout,run.stdout
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
 assert sha(candidate)==candidateSha
 save(out/'dependency-pins-after.json',dict(runtime=cp,candidate=dict(path=str(candidate),sha256Bytes=candidateSha),tools=toolPins,sourceSha256Bytes=sha(copy)))
 save(out/'result.json',dict(status='PASS',assertions=16,groups=3,transport='In-memory required region callback, no socket',settings='Actual existing DesktopOriginalHomePreferences and same real temporary DesktopPluginStore',
  prospectiveCategoryOnly=True,actualRootAccountIntegration=False,productionClassOverrides=0,explicitReferenceOnlySharedFamilies=4,HTTP=False,GUI=False,HWND=False,Gradle=False,actualRuntimeEntries=97,candidateJarSha256Bytes=candidateSha,fixtureJarSha256Bytes=sha(jar),observedClassOrigins=[line for line in run.stdout.splitlines() if line.startswith('ORIGIN ')]))
 print(run.stdout)
if __name__=='__main__':main()
