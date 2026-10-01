"""Focused original fraud protocol -> fake Retrofit/raw transport, no sockets."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
SNAP=MAIN/'desktop/.local/stable-product-snapshot-11'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def main():
 attempt=sys.argv[1] if len(sys.argv)>1 else '01'
 out=HERE/('fixture-'+attempt);assert not safe(out).exists();safe(out).mkdir()
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
 def pins():
  r=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp]
  assert all(x['expected']==x['actual'] for x in r);return r
 save(out/'runtime-pins-before.json',pins())
 candidate=HERE/'compile-02/prepared-fraud-protocol.jar'
 assert json.loads(safe(HERE/'compile-02/compile-result.json').read_text())['status']=='PASS'
 cpath=MAIN/'desktop/.local/source9-appearance/compile-miuix.py'
 spec=importlib.util.spec_from_file_location('existing_compiler',cpath);c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 source=out/'FraudProtocolFixture.kt';safe(source).write_bytes(safe(HERE/'FraudProtocolFixture.kt').read_bytes())
 fixture=out/'fraud-protocol-fixture.jar'
 runtime=[str(candidate)]+[x['path'] for x in cp]
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(runtime),
  '-Xfriend-paths='+str(candidate)+','+str(SNAP/'main-kotlin.jar'),'-module-name','focused_fraud_protocol_fixture','-d',str(fixture),str(source)]
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=120)
 write(out/'compiler.log',p.stdout+p.stderr)
 if p.returncode:
  save(out/'compile-result.json',dict(status='FAIL',exitCode=p.returncode));print(p.stdout+p.stderr);return p.returncode
 scratch=out/'scratch';safe(scratch).mkdir()
 command=[str(c.JAVA),'-Djava.awt.headless=true','-cp',str(fixture)+';'+';'.join(runtime),
  'com.android.purebilibili.data.repository.fraudProof.FraudProtocolFixtureKt',str(scratch),str(candidate),str(SNAP/'main-kotlin.jar')]
 p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=90)
 write(out/'runtime.log',p.stdout+p.stderr);save(out/'runtime-pins-after.json',pins())
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,
  preparedCandidateSha256Bytes=sha(candidate),fixtureSha256Bytes=sha(fixture),fixtureSourceSha256Bytes=sha(source),
  actualStable11CpCount=len(cp),declaredPreparedProductOverrides=True,noMainEdits=True,noGradle=True,noSocket=True,noHTTP=True,noHWND=True)
 if p.returncode==0:
  data=json.loads(safe(scratch/'result.json').read_text());result.update(caseCount=data['caseCount'],assertions=data['assertions'],resultSha256Bytes=sha(scratch/'result.json'))
 save(out/'accepted-result.json',result)
 print(json.dumps(result));print((p.stdout+p.stderr)[-10000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
