from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def write(p,t):wide(p).write_text(json.dumps(t,indent=2)if not isinstance(t,str)else t,encoding='utf8',newline='\n')
S=M/'desktop/.local/stable-product-snapshot-83';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'))
def pins():
 assert sha(S/'manifest.json')=='72934edb925c7793294bf504388dfbd1f849fe14d8ea00dd4f333befdabd4157'
 assert sha(S/'ordered-runtime-cp.json')=='f82828347f7cfad86cd101dfb13d44677ba88f780b21a9e21df8c06a97d4828b'
 assert len(cp)==101
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
pins();number=sys.argv[1];out=P/'native-runs'/number;wide(out).mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
pros=P/'runs/04/prospective.jar';main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
source=P/'RootMediaFixture.kt';wide(out/source.name).write_bytes(wide(source).read_bytes())
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','story_baseline_fixture','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(pros)+','+main,'-cp',';'.join([str(pros)]+[r['path']for r in cp]),'-d',str(out/'classes'),str(out/source.name)]
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);pins()
if r.returncode:print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
jar=out/'fixture.jar'
with zipfile.ZipFile(wide(jar),'w',zipfile.ZIP_DEFLATED)as z:
 for f in wide(out/'classes').rglob('*.class'):z.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
runtime=out/'runtime';wide(runtime).mkdir();local=runtime/'local-appdata';wide(local).mkdir()
resources=C/'desktop/resources/common';native=resources/'native/windows-x64/libmpv-2.dll';diagnostic=resources/'native/windows-x64/bilipai-diagnostic-share.dll';ffmpeg=resources/'native/windows-x64/ffmpeg.exe'
assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
assert sha(diagnostic)=='22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3'
clip=runtime/'fixture-clip.mp4'
generate=subprocess.run([str(ffmpeg),'-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x180:rate=24','-t','12','-c:v','mpeg4','-pix_fmt','yuv420p',str(clip)],capture_output=True,timeout=40);wide(out/'clip.log').write_bytes(generate.stdout+generate.stderr);assert generate.returncode==0
env=os.environ.copy();env['LOCALAPPDATA']=str(local.resolve())
cmd=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Dbilipai.mpv.path='+str(native),'-Dcompose.application.resources.dir='+str(resources),'-cp',';'.join([str(jar),str(pros)]+[r['path']for r in cp]),'com.bilipai.desktop.rootmediafixture.RootMediaFixtureKt',str(runtime.resolve())]
write(out/'runtime-command.json',dict(command=cmd,productionOverrides='explicit prospective Story89+review+baseline family declarations only',seededSuccess=False,realAccount=False,globalInput=False,cp=cp,sourceSha=sha(source),prospectiveSha=sha(pros)))
r=subprocess.run(cmd,env=env,capture_output=True,timeout=150);wide(out/'runtime.log').write_bytes(r.stdout+r.stderr);pins()
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-9000:])
proof=json.loads(wide(runtime/'root-media-proof.json').read_text())
for clazz,o in proof['classOrigins'].items():
 resource=clazz.replace('.','/')+'.class';actual=pros if 'prospective.jar' in o['origin']else main
 with zipfile.ZipFile(wide(actual))as z:assert hashlib.sha256(z.read(resource)).hexdigest()==o['sha256Bytes']
write(out/'result.json',dict(passed=r.returncode==0 and proof['status']=='PASS_PROSPECTIVE_STORY_BASELINE_NATIVE',checks=proof['checks'],actual83EntriesUnchanged=True,loadedBytesVerified=True,ordinaryDetailSuccessAccepted=False,realHTTPAccepted=False,fixtureProductSourceWrites=0))
sys.exit(0 if r.returncode==0 and proof['status']=='PASS_PROSPECTIVE_STORY_BASELINE_NATIVE' else 1)
