from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
SNAPSHOT=REPO/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
MANIFEST='7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
CP='7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute()); return Path(v if v.startswith(EXT) else EXT+v)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):
 assert Path(p).absolute().is_relative_to(HERE)
 safe(p.parent).mkdir(parents=True,exist_ok=True)
 safe(p).write_text(t,encoding='utf-8',newline='\n')
def save(p,v): write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def argsfile(p,values): write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
def env(root):
 values=os.environ.copy(); values['PYTHONDONTWRITEBYTECODE']='1'
 for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TMP','TEMP']:
  p=root/key.lower();safe(p).mkdir(parents=True,exist_ok=True);values[key]=str(p)
 return values
assert sha(SNAPSHOT/'manifest.json')==MANIFEST
assert sha(SNAPSHOT/'ordered-runtime-cp.json')==CP
cp=json.loads(safe(SNAPSHOT/'ordered-runtime-cp.json').read_text())
assert len(cp)==92
for row in cp: assert sha(row['path'])==row['sha256Bytes'],row['path']
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
inventory=json.loads(safe(HERE/'source-inventory.json').read_text())
for row in inventory['candidates']:
 assert sha(row['candidate'])==row['candidateLfSha256']
 assert hashlib.sha256((REPO/row['path']).read_text(encoding='utf-8').encode()).hexdigest()==row['baseLfSha256']
spec=importlib.util.spec_from_file_location('video_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
phase,attempt=sys.argv[1:3];assert attempt.isalnum()
out=HERE/'runs'/attempt
if phase=='compile':
 assert not out.exists();out.mkdir(parents=True)
 base_fixture=REPO/'desktop/.local/native-known-folder-save-parity/KnownFolderFixture.kt'
 native=base_fixture.read_text(encoding='utf-8')
 native=native.replace('3081e2331e4e7646835a98395c3bc3bb','1d9b9818b5995b45841cab7c74e4ddfc').replace('resolveDefault(', 'resolveDefaultVideo(')
 native=native.replace('Pictures','Videos').replace('移动图片','移动视频')
 write(HERE/'VideoKnownFolderFixture.kt',native)
 sources=[Path(r['candidate']) for r in inventory['candidates']]+[HERE/'VideoKnownFolderFixture.kt',HERE/'VideoDefaultFixture.kt']
 frozen=[]
 for p in sources:
  dst=out/'frozen-sources'/p.name;write(dst,safe(p).read_text(encoding='utf-8'));frozen.append(dst)
 candidate=out/'candidate.jar';home=out/'compiler-home';environ=env(home)
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+main,
       '-module-name','com_bilipai_desktop_bilipai_windows','-d',candidate]+frozen
 argsfile(out/'compile.args',args)
 command=[str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Duser.home='+str(home),'-Djava.io.tmpdir='+environ['TEMP'],'-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
          'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')]
 result=subprocess.run(command,cwd=HERE,env=environ,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
 write(out/'compile.log',result.stdout+result.stderr);print(result.stdout+result.stderr)
 if result.returncode:
  save(out/'compile-failure.json',{'exitCode':result.returncode,'compileLogSha256':sha(out/'compile.log'),'MainIntegrated':False});sys.exit(result.returncode)
 existing=set()
 for row in cp:
  with zipfile.ZipFile(safe(row['path'])) as archive: existing.update(n for n in archive.namelist() if n.endswith('.class'))
 with zipfile.ZipFile(safe(candidate)) as archive:classes={n for n in archive.namelist() if n.endswith('.class')}
 overlap=sorted(classes&existing)
 fixture=[n for n in classes if 'videoDefaultProof/' in n or 'VideoKnownFolderFixture' in n or '/FakeKnownFolder' in n or '/TrackingKnownFolder' in n]
 assert overlap and not any(n in overlap for n in fixture)
 save(out/'compile-evidence.json',{'passed':True,'Main04ManifestSha256':MANIFEST,'Main04Ordered92ClasspathSha256':CP,
 'classpath':cp,'compilerInputs':[{'path':str(p),'sha256Bytes':sha(p)} for p in compiler.COMPILER],
 'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in frozen],'candidateJarSha256':sha(candidate),
 'candidateClassCount':len(classes),'existingMainProductOverrides':overlap,'fixtureClassCount':len(fixture),
 'nativeFixtureDerivedFrom':{'path':str(base_fixture),'sha256Bytes':sha(base_fixture),'changes':'Videos GUID/name and resolver only'},
 'MainIntegrated':False,'newDependencies':False,'sharedGradle':False})
 print(f'PASS task-only candidate compile; {len(overlap)} declared product class overrides')
elif phase=='run':
 evidence=json.loads(safe(out/'compile-evidence.json').read_text());candidate=out/'candidate.jar'
 assert sha(candidate)==evidence['candidateJarSha256']
 for row in evidence['sources']:assert sha(row['path'])==row['sha256Bytes']
 proof=out/'proof';assert not proof.exists();proof.mkdir()
 old_input=REPO/'desktop/.local/dynamic-gallery-motion-photo-parity/runs/04/media/source.mp4'
 assert sha(old_input)=='4aebf236a1c56fc00d2d129c83c96b4f51c7dceb80784f1acd29809d1af08c7f'
 safe(proof/'input.mp4').write_bytes(safe(old_input).read_bytes())
 home=out/'task-home';environ=env(home)
 for mode in ['fake','native','path']:
  if mode=='path':target='com.bilipai.desktop.ui.videoDefaultProof.VideoDefaultFixtureKt';tail=[proof,candidate,main]
  else:target='com.bilipai.desktop.platform.VideoKnownFolderFixtureKt';tail=[mode]
  args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+environ['TEMP'],'-Djna.tmpdir='+environ['TEMP'],
        '-cp',';'.join([str(candidate)]+[r['path'] for r in cp]),target]+tail
  argsfile(out/(mode+'.args'),args)
  command=[str(compiler.JAVA),'@'+str(out/(mode+'.args'))]
  native_environ=os.environ.copy()
  for key in ['TEMP','TMP']:native_environ[key]=environ[key]
  # SHGetKnownFolderPath must observe the real account's Windows folder
  # environment. Only its JNA extraction/temp are isolated; the fixture calls
  # the read-only bridge with flags0 (no CREATE) and never a settings writer.
  selected_environ=native_environ if mode=='native' else environ
  result=subprocess.run(command,cwd=HERE,env=selected_environ,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=50)
  write(out/(mode+'.log'),result.stdout+result.stderr);print(mode,result.stdout+result.stderr)
  save(out/(mode+'-process.json'),{'exitCode':result.returncode,'command':command,'candidateJarSha256':sha(candidate),
   'nativeActualAccountEnvironment':mode=='native','JnaAndJavaTemporaryIsolated':True})
  result.check_returncode()
  if mode!='path':save(out/(mode+'-proof.json'),json.loads(result.stdout.strip()))
 result=json.loads(safe(proof/'result.json').read_text());assert result['passed'] and result['cases']==5 and result['requests']==3
 actual_files=[p for p in safe(proof/'videos-base'/'BiliPai').iterdir() if p.name.startswith('BiliPai_Live_') and p.name!='BiliPai_Live_42.mp4']
 assert len(actual_files)==1 and sha(actual_files[0])==sha(proof/'input.mp4')
 save(out/'independent-byte-readback.json',{'passed':True,'savedVideoSha256':sha(actual_files[0]),'inputMp4Sha256':sha(proof/'input.mp4'),
 'actualOutputName':actual_files[0].name,'oldSentinelUnchanged':safe(proof/'videos-base'/'BiliPai'/'BiliPai_Live_42.mp4').read_text()=='declared old MP4 sentinel',
 'customImageFolderFiles':len(list(safe(proof/'custom-images').iterdir())),'videoNativeUserDirectoryWrites':False})
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
 save(out/'accepted-evidence.json',{**result,'Main04ManifestSha256':MANIFEST,'Main04Ordered92ClasspathSha256':CP,
 'candidateJarSha256':sha(candidate),'existingMainProductOverrides':evidence['existingMainProductOverrides'],
 'nativeFakeCases':len(json.loads(safe(out/'fake-proof.json').read_text())['cases']),
 'actualNativeReadOnlyCases':len(json.loads(safe(out/'native-proof.json').read_text())['cases']),
 'stableHelperByteEqual':True,'scope':'prepared candidate only, not actual Main installation'})
 print('PASS Main04 bytes unchanged; native read-only and isolated candidate path proofs')
else:raise ValueError(phase)
