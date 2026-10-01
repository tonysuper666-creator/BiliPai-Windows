from pathlib import Path
import hashlib,json,subprocess,importlib.util,sys,zipfile,re
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('proofcompiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
audit=json.loads(read(LANE/'input-audit.json'));cp=audit['verifiedDependencyPins'];assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
compiled=json.loads(read(LANE/'compile-09/compile-result.json'));jar=compiled['jar'];assert sha(jar)==compiled['jarSha256Bytes']
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('proof-'+number);safe(out).mkdir(exist_ok=True)
classpath=[jar]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+jar+';'+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','profile_platform_fixture','-d',str(out/'classes'),str(LANE/'ProfilePlatformFixture.kt')]
# Kotlin friend paths are comma-separated, not the Windows runtime semicolon separator.
args=[a.replace('-Xfriend-paths='+jar+';','-Xfriend-paths='+jar+',') if a.startswith('-Xfriend-paths=') else a for a in args]
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
fixture=out/'profile-platform-fixture.jar'
with zipfile.ZipFile(safe(fixture),'w',zipfile.ZIP_DEFLATED) as z:
 for p in safe(out/'classes').rglob('*'):
  if p.is_file():z.writestr(p.relative_to(safe(out/'classes')).as_posix(),p.read_bytes())
ffprobe=MAIN/'desktop/resources/common/native/windows-x64/ffprobe.exe'
ffmpeg=ffprobe.with_name('ffmpeg.exe')
assert sha(ffprobe)=='67176fa62f89f94c3bcd379fd05677a25651569a2eb8880ec2194e62c82be412'
assert sha(ffmpeg)=='9c60da6c0b083110d59084ea39f60ae149aa3e031c3b4bb4f573fafa1c1e7cea'
media=out/'synthetic-local.mp4'
commands=[str(ffmpeg),'-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=blue:s=160x90:r=15','-t','0.4','-an','-c:v','mpeg4','-y',str(media)]
generator=subprocess.run(commands,capture_output=True,text=True,timeout=10);safe(out/'media-generator.log').write_text(generator.stdout+generator.stderr,encoding='utf-8');assert generator.returncode==0
safe(out/'media-inputs.json').write_text(json.dumps({'ffprobeSha256Bytes':sha(ffprobe),'ffmpegSha256Bytes':sha(ffmpeg),'command':commands,'syntheticVideoSha256Bytes':sha(media)},indent=2),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',';'.join([str(fixture)]+classpath),'com.bilipai.desktop.ui.ProfilePlatformFixtureKt',str(out/'local-fixture'),str(ffprobe),str(media)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
safe(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stdout+r.stderr
match=re.search(r'PROFILE_PLATFORM_PROOF (\{[^\n]+\})',r.stdout);assert match,r.stdout
result=json.loads(match.group(1));result.update({'fixtureJarSha256Bytes':sha(fixture),'candidateJarSha256Bytes':sha(jar),'actual97PinsVerified':True,'localFixture':[{'path':str(p),'sha256Bytes':sha(p)} for p in safe(out/'local-fixture').rglob('*') if p.is_file()],'sharedGradleRun':False})
safe(out/'proof-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
