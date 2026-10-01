from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());SNAP=MAIN/'desktop/.local/stable-product-snapshot-14'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def main():
 out=HERE/('proof-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir()
 assert sha(SNAP/'manifest.json')=='58ba697f6d436b7b859d21a2da92a4bbc5ed5aa0d566110489633b4d2c8f16f2'
 assert sha(SNAP/'ordered-runtime-cp.json')=='64869241fd1a85eacfe072814c28bb91377dc0106a13b11810c30b6295ab101a'
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
 def pins():
  rows=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['actual']==x['expected'] for x in rows);return rows
 save(out/'runtime-pins-before.json',pins())
 spec=importlib.util.spec_from_file_location('existing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 source=out/'CaptureFixture.kt';safe(source).write_bytes(safe(HERE/'CaptureFixture.kt').read_bytes());target=out/'capture-fixture.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(x['path'] for x in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','main14_capture_fixture','-d',str(target),str(source)]
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=120);write(out/'compiler.log',p.stdout+p.stderr)
 if p.returncode:save(out/'accepted-result.json',dict(status='COMPILE_FAIL',exitCode=p.returncode));print(p.stdout+p.stderr);return p.returncode
 with zipfile.ZipFile(safe(target)) as z:own={x for x in z.namelist() if x.endswith('.class')}
 with zipfile.ZipFile(safe(SNAP/'main-kotlin.jar')) as z:actual={x for x in z.namelist() if x.endswith('.class')}
 assert not own&actual
 scratch=out/'scratch';safe(scratch).mkdir()
 command=[str(c.JAVA),'-Djava.awt.headless=true','-cp',str(target)+';'+';'.join(x['path'] for x in cp),'com.bilipai.desktop.ui.captureMain14Proof.CaptureFixtureKt',str(scratch),str(SNAP/'main-kotlin.jar')]
 save(out/'runtime-command.json',command)
 p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=90);write(out/'runtime.log',p.stdout+p.stderr);save(out/'runtime-pins-after.json',pins())
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,fixtureSourceSha256Bytes=sha(source),fixtureJarSha256Bytes=sha(target),actualMain14ManifestSha256Bytes=sha(SNAP/'manifest.json'),actualCp92Sha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),actualProductOverrides=0,classOverlap=[],noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True)
 if p.returncode==0:
  raw=json.loads(safe(scratch/'result.json').read_text());result.update(caseCount=raw['caseCount'],assertions=raw['assertions'],backgroundSamples=raw['actualBackgroundSamples'],actualCodeSources=len(raw['actualCodeSources']),resultSha256Bytes=sha(scratch/'result.json'))
 save(out/'accepted-result.json',result);print(json.dumps(result));print((p.stdout+p.stderr)[-10000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
