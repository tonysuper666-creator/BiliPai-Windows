from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
H=Path(__file__).resolve().parent;MAIN=H.parents[2];prefix=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
sp=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(sp);sp.loader.exec_module(cc)
cp=json.loads(wide(MAIN/'desktop/.local/stable-product-snapshot-50/ordered-runtime-cp.json').read_text());paths=[r['path'] for r in cp]
for r in cp:assert sha(r['path'])==r['sha256Bytes']
production=H/'core-compile-07/candidate.jar';native=MAIN/'desktop/.local/stable-video-player-section-parity/native-state-runs/04/candidate.jar';content=MAIN/'desktop/.local/stable-video-player-page-parity/content-runs/06/candidate.jar'
paths=[str(production),str(native),str(content)]+paths
out=H/('core-proof-'+sys.argv[1]);wide(out).mkdir(exist_ok=False)
fixture=H/'fixture/OriginalPlaybackCoreFixture.kt';wide(out/'fixture.kt').write_bytes(wide(fixture).read_bytes())
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(production)+','+str(native)+','+cp[1]['path'],'-cp',';'.join(paths),'-d',str(out/'classes'),str(out/'fixture.kt')]
wide(out/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
wide(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());assert r.returncode==0
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+paths),'com.bilipai.desktop.ui.FixtureKt'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35)
wide(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
wide(out/'result.json').write_text(json.dumps(dict(preparedOnly=True,actual50=True,strictRuntimeEntries=97,explicitCandidateJarSha=sha(production),nativeFacadeCandidateSha=sha(native),childContentCandidateSha=sha(content),fixtureSha=sha(out/'fixture.kt'),passed=r.returncode==0,noHttp=True,noHwnd=True,noAccountMutation=True,fullVmHolderAccepted=False,groups=3),indent=2)+'\n',encoding='utf-8')
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sys.exit(r.returncode)
