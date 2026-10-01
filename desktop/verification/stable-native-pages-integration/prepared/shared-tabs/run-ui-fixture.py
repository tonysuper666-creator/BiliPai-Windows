from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());SNAP=MAIN/'desktop/.local/stable-product-snapshot-11'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,indent=2)+'\n')
def main():
 out=HERE/('ui-proof-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir()
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
 def pins():
  r=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['expected']==x['actual'] for x in r);return r
 save(out/'runtime-pins-before.json',pins())
 candidate=HERE/'compile-03/prepared-shared-liquid-tabs.jar';assert sha(candidate)=='96eb88f19f844115894297bb59e5081caa361c0d49e96f681852c55947ddc688'
 spec=importlib.util.spec_from_file_location('existing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 source=out/'LiquidTabsFixture.kt';safe(source).write_bytes(safe(HERE/'LiquidTabsFixture.kt').read_bytes());fixture=out/'liquid-tabs-fixture.jar'
 runtime=[str(candidate)]+[x['path'] for x in cp]
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(runtime),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(candidate)+','+str(SNAP/'main-kotlin.jar'),'-module-name','focused_shared_liquid_tabs_fixture','-d',str(fixture),str(source)]
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=120)
 write(out/'compiler.log',p.stdout+p.stderr)
 if p.returncode:save(out/'compile-result.json',dict(status='FAIL',exitCode=p.returncode));print(p.stdout+p.stderr);return p.returncode
 scratch=out/'scratch';safe(scratch).mkdir()
 command=[str(c.JAVA),'-Djava.awt.headless=true','-cp',str(fixture)+';'+';'.join(runtime),'com.bilipai.desktop.ui.liquidTabsProof.LiquidTabsFixtureKt',str(scratch),str(candidate),str(SNAP/'main-kotlin.jar')]
 p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=90);write(out/'runtime.log',p.stdout+p.stderr);save(out/'runtime-pins-after.json',pins())
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,candidateSha256Bytes=sha(candidate),fixtureSha256Bytes=sha(fixture),fixtureSourceSha256Bytes=sha(source),actualStable11CpCount=len(cp),offscreenActualComposeScene=True,noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True,preparedOnly=True)
 if p.returncode==0:
  data=json.loads(safe(scratch/'result.json').read_text());result.update(assertions=data['assertions'],caseCount=data['caseCount'],pointerPairs=data['pointerPairs'],actualRecordedLayerSamples=data['actualRecordedLayerSamples'],resultSha256Bytes=sha(scratch/'result.json'))
 save(out/'accepted-result.json',result);print(json.dumps(result));print((p.stdout+p.stderr)[-8000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
