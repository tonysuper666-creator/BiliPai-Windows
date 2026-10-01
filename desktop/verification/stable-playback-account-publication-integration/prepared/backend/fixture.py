from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-39'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def main():
 out=HERE/'runs'/sys.argv[1];assert not out.exists();out.mkdir()
 cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==97
 assert sha(SNAP/'manifest.json')=='4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae'
 assert sha(SNAP/'ordered-runtime-cp.json')=='e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f'
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
 candidate=HERE/'runs'/sys.argv[2]/'prepared-playback-account.jar'
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 source=out/'PlaybackAccountFixture.kt';safe(source).write_bytes(safe(HERE/source.name).read_bytes())
 jar=out/'playback-fixture.jar';classpath=';'.join([str(candidate)]+[r['path'] for r in cp])
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xfriend-paths='+str(SNAP/'main-kotlin.jar')+','+str(candidate),'-module-name','playback_account_fixture','-d',str(jar),str(source)]
 safe(out/'compiler.args').write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
 tools=[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA]+c.COMPILER]
 pins=dict(runtime=cp,candidate=dict(path=str(candidate),sha256Bytes=sha(candidate)),tools=tools,fixtureSourceSha256=sha(source))
 save(out/'pins-before.json',pins)
 compilation=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=120)
 safe(out/'compiler.log').write_text(compilation.stdout+compilation.stderr,encoding='utf-8',newline='\n')
 if compilation.returncode:print(compilation.stdout+compilation.stderr);save(out/'failure.json',dict(phase='fixture_compile',exitCode=compilation.returncode));return compilation.returncode
 with zipfile.ZipFile(safe(jar)) as z:fixtureClasses={n for n in z.namelist() if n.endswith('.class')}
 actual=set()
 for row in cp:
  with zipfile.ZipFile(safe(row['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
 with zipfile.ZipFile(safe(candidate)) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
 assert not fixtureClasses&actual,sorted(fixtureClasses&actual)
 run=subprocess.run([str(c.JAVA),'-cp',str(jar)+';'+classpath,'com.bilipai.desktop.data.PlaybackAccountFixtureKt',str(out/'synthetic-store')],capture_output=True,text=True,encoding='utf-8',timeout=60)
 safe(out/'runtime.log').write_text(run.stdout+run.stderr,encoding='utf-8',newline='\n')
 if run.returncode:print(run.stdout+run.stderr);save(out/'failure.json',dict(phase='fixture_run',exitCode=run.returncode));return run.returncode
 result=json.loads(safe(out/'synthetic-store/result.json').read_text())
 for row in result['origins']:
  expected=candidate if row['class'].startswith('com.bilipai.desktop.data.') else SNAP/'main-kotlin.jar'
  assert Path(row['codeSource'].removeprefix('file:/')).resolve()==expected.resolve(),(row,expected)
  with zipfile.ZipFile(safe(expected)) as z:assert hashlib.sha256(z.read(row['class'].replace('.','/')+'.class')).hexdigest()==row['classSha256']
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
 for row in tools:assert sha(row['path'])==row['sha256Bytes']
 assert sha(candidate)==pins['candidate']['sha256Bytes']
 save(out/'pins-after.json',pins)
 save(out/'accepted.json',dict(result,actualRootAccountAcceptance=False,fixtureClassIntersections=[],candidate= pins['candidate'],fixtureJarSha256=sha(jar),actualRuntimeEntries=97,HTTP=False,HWND=False,GUI=False,Gradle=False))
 print(run.stdout);return 0
if __name__=='__main__':raise SystemExit(main())
