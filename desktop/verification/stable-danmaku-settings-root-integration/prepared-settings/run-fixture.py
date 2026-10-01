from pathlib import Path
import importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
import compile as c
HERE=c.HERE;SNAP=c.SNAP
out=HERE/('proof-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir()
cp,relocation=c.runtime();c.save(out/'same-byte-relocation.json',relocation);c.save(out/'runtime-pins-before.json',c.pins(cp))
phase=HERE/('compile-'+(sys.argv[2] if len(sys.argv)>2 else '05'));candidate=phase/'original-danmaku-settings.jar'
compiled=json.loads((phase/'compile-result.json').read_text(encoding='utf-8'));assert compiled['status']=='PASS' and c.sha(candidate)==compiled['jarSha256Bytes']
spec=importlib.util.spec_from_file_location('compiler',c.MAIN/'desktop/.local/source9-appearance/compile-miuix.py');compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
source=out/'SettingsFixture.kt';source.write_bytes((HERE/'SettingsFixture.kt').read_bytes());fixture=out/'settings-fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join([str(candidate)]+[x['path'] for x in cp]),'-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+','.join([str(candidate),str(SNAP/'main-kotlin.jar')]),'-module-name','original_danmaku_settings_proof','-d',str(fixture),str(source)]
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(compiler.JAVA),'-Xmx2g','-cp',';'.join(map(str,compiler.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
(out/'compiler.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n');c.save(out/'compile-result.json',dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,fixtureSourceSha256Bytes=c.sha(source)))
if p.returncode:print(p.stdout+p.stderr);raise SystemExit(p.returncode)
scratch=out/'scratch';scratch.mkdir()
command=[str(compiler.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(fixture),str(candidate)]+[x['path'] for x in cp]),'com.bilipai.desktop.ui.SettingsFixtureKt',str(scratch)]
c.save(out/'runtime-command.json',command)
p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=60)
(out/'runtime.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n');c.save(out/'runtime-pins-after.json',c.pins(cp))
accepted=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,candidateJarSha256Bytes=c.sha(candidate),fixtureJarSha256Bytes=c.sha(fixture),actualMain20ManifestSha256Bytes=c.sha(SNAP/'manifest.json'),originalOrdered92CpSha256Bytes=c.sha(SNAP/'ordered-runtime-cp.json'),sameByteRelocation=relocation,existingProductionOverrides=compiled['actualClassOverlap'],noSharedEdits=True,noGradle=True,noExternalHTTP=True,noSocket=True,noHWND=True,noAccountRead=True)
if not p.returncode:
 raw=json.loads((scratch/'result.json').read_text(encoding='utf-8'));assert raw['status']=='PASS'
 allowed={Path(x['path']).absolute() for x in cp}|{candidate.absolute()}
 for row in raw['actualCodeSources']:
  path=Path(row['path']);assert path.absolute() in allowed
  with zipfile.ZipFile(c.safe(path)) as z:assert c.hashlib.sha256(z.read(row['class'].replace('.','/')+'.class')).hexdigest()==row['classSha256Bytes']
 accepted.update(groupedCases=raw['groupedCases'],assertions=raw['assertions'],actualCodeSourceCount=len(raw['actualCodeSources']),codeSourceAndClassBytesVerified=True,resultSha256Bytes=c.sha(scratch/'result.json'),actualOriginalPanelMounted=False,actualRootProjectionInstalled=False,androidEngineTimingParityClaim=False)
c.save(out/'accepted-result.json',accepted);print(json.dumps({k:accepted[k] for k in ['status','exitCode','candidateJarSha256Bytes']}|dict(acceptedSha256Bytes=c.sha(out/'accepted-result.json'),groups=accepted.get('groupedCases'),assertions=accepted.get('assertions'))));print(p.stdout+p.stderr);raise SystemExit(p.returncode)
