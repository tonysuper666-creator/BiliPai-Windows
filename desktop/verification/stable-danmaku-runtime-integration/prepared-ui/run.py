from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
SNAP=MAIN/'desktop/.local/stable-product-snapshot-27'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def pins(cp):
 rows=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['actual']==x['expected'] for x in rows);return rows
def main():
 assert sha(SNAP/'manifest.json')=='908d5cfdb65a99622fd2822771641b17d2bf35efe501a71ffcd231eace8c1c53'
 assert sha(SNAP/'ordered-runtime-cp.json')=='8dcd9678a9f669793267bfb530fda1c09ef3225c50cf54682a12228636afd772'
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'));assert len(cp)==92
 out=HERE/('runs/'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir(parents=True)
 save(out/'runtime-pins-before.json',pins(cp))
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 src=out/'source-inputs/DanmakuSettingsUiFixture.kt';safe(src.parent).mkdir();safe(src).write_bytes(safe(HERE/'DanmakuSettingsUiFixture.kt').read_bytes())
 target=out/'settings-main26-ui-fixture.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(x['path'] for x in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','actual_main26_settings_ui_fixture','-d',str(target),str(src)]
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=150)
 write(out/'compiler.log',p.stdout+p.stderr)
 compile=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,sourceInputs=[dict(path=str(src),sha256Bytes=sha(src))],productionSourceInputs=0)
 if p.returncode:
  save(out/'compile-result.json',compile);print(p.stdout+p.stderr);return p.returncode
 with zipfile.ZipFile(safe(target)) as z:own={n for n in z.namelist() if n.endswith('.class')}
 actual=set()
 for x in cp[:3]:
  with zipfile.ZipFile(safe(x['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
 assert not own&actual,sorted(own&actual)
 compile.update(jarSha256Bytes=sha(target),fixtureClasses=sorted(own),productionClassOverlap=[],actualSnapshotManifestSha256Bytes=sha(SNAP/'manifest.json'),orderedCp92Sha256Bytes=sha(SNAP/'ordered-runtime-cp.json'))
 save(out/'compile-result.json',compile)
 allresults=[]
 for style,height in [('MATERIAL3',900),('MIUIX',900)]:
  resultdir=out/f'{style}-{height}';safe(resultdir).mkdir()
  cmd=[str(c.JAVA),'-Djava.awt.headless=true','-Djava.security.manager=allow','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Xmx2g','-cp',str(target)+';'+';'.join(x['path'] for x in cp),'com.bilipai.desktop.ui.DanmakuSettingsUiFixtureKt',style,str(resultdir)]
  write(resultdir/'java-command.json',json.dumps(cmd,ensure_ascii=False,indent=2)+'\n')
  r=subprocess.run(cmd,capture_output=True,timeout=160)
  safe(resultdir/'stdout.raw').write_bytes(r.stdout);safe(resultdir/'stderr.raw').write_bytes(r.stderr)
  output=(r.stdout+r.stderr).decode('utf-8',errors='replace');write(resultdir/'runtime.log',output)
  if r.returncode:
   save(out/'accepted-comparison.json',dict(status='FAIL',style=style,exitCode=r.returncode));print(output);return r.returncode
  result=json.loads(safe(resultdir/'result.json').read_text(encoding='utf-8'))
  for row in result['actualCodeSources']:
   assert row['codeSource'].endswith('/stable-product-snapshot-27/main-kotlin.jar'),row
   name=row['class'].replace('.','/')+'.class'
   with zipfile.ZipFile(safe(SNAP/'main-kotlin.jar')) as z:expected=hashlib.sha256(z.read(name)).hexdigest()
   assert row['classSha256Bytes']==expected,row
  allresults.append(result)
 save(out/'runtime-pins-after.json',pins(cp))
 accepted=dict(status='PASS',cells=len(allresults),assertions=sum(x['assertions'] for x in allresults),pointerPairs=sum(x['pointerPairs'] for x in allresults),actualEditableTextActions=sum(x['actualEditableTextActions'] for x in allresults),productionClassOverrides=0,loadedClassBytePinsPerCell=11,results=allresults,scope='Actual installed Main27 complete original Danmaku Settings Host/Panel and BlockManager, same actual global Store/scoped preference implementation, offscreen pointer/scroll/edit. Fixture supplies presentation/account state and declared memory-only cloud/file chooser ports; no Root/account/HWND/Android engine parity claim.')
 save(out/'accepted-comparison.json',accepted);print(json.dumps({k:v for k,v in accepted.items() if k!='results'},ensure_ascii=False));return 0
if __name__=='__main__':raise SystemExit(main())
