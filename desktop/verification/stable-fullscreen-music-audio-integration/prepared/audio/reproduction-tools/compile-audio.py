from pathlib import Path
import argparse,hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
cli=argparse.ArgumentParser(description='Source-only full Audio consumer against immutable actual product and explicitly owned Music/Comment sources')
cli.add_argument('number');args=cli.parse_args()
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode()if isinstance(t,str)else t)
out=LANE/('compile-audio-'+args.number);assert not safe(out).exists();safe(out).mkdir()
snap=MAIN/'desktop/.local/stable-product-snapshot-61'
assert sha(snap/'manifest.json')=='6b5777dbe6855ddb9529f612580c11aa24dfcd23c771828e51369d610e4a9445'
assert sha(snap/'ordered-runtime-cp.json')=='746bdf5e200774e0b1765b31fa0bd35918080cdddf04606318482147f774c057'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes']
music=LANE/'frozen-music-slice';assert sha(music/'frozen-handoff.json')=='f95b38ff95b90e9dcc38d0a174d24c386438d9f0d23d9ec1086f4a7989e136bc'
for row in json.loads(safe(music/'frozen-handoff.json').read_text())['rawArtifacts']:
 assert sha(row['path'])==row['sha256Bytes']
sources=[];receipts=[]
def add(source,relative,owner):
 target=out/'source-inputs'/relative;write(target,safe(source).read_bytes());sources.append(target)
 receipts.append({'path':str(source),'compileCopy':str(target),'sha256Bytes':sha(source),'owner':owner,'installInThisPacket':owner=='audio-sole'})
for p in sorted(safe(music/'selected').rglob('*.kt')):
 normal=music/'selected'/p.relative_to(safe(music/'selected'));add(normal,Path('reference/music')/p.relative_to(safe(music/'selected')),'frozen-music-sole')
for name in ['DesktopOriginalMusicUiPlatform','DesktopMusicRaster']:
 p=music/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui'/(name+'.kt');add(p,Path('reference/music-platform')/p.name,'frozen-music-sole')
for name in ['AudioModeScreen','AudioModeMusicPlayer']:
 p=LANE/'prepared/generated/com/android/purebilibili/feature/video/screen'/(name+'.kt');add(p,Path('audio/com/android/purebilibili/feature/video/screen')/p.name,'audio-sole')
p=LANE/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalAudioModePlatform.kt';add(p,Path('audio/com/bilipai/desktop/ui')/p.name,'audio-sole')
comment=MAIN/'desktop/.local/stable-video-fullscreen-pager-parity/prepared/generated/com/android/purebilibili'
for relative in ['feature/video/ui/components/VideoCommentSheetHost.kt','feature/video/ui/pager/PortraitCommentPresentationPolicy.kt','core/ui/skeleton/DesktopOriginalVideoCommentListSkeleton.kt','core/store/DesktopOriginalVideoCommentSheetSettings.kt','feature/video/ui/pager/DesktopOriginalPortraitVideoViewport.kt']:
 p=comment/relative;add(p,Path('reference/child-comment/com/android/purebilibili')/relative,'child-comment-sole')
assert len(sources)==32
write(out/'source-inputs.json',json.dumps(receipts,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
compilerArgs=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','full_audio_source_parity','-d',str(out/'classes')]+list(map(str,sources))
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in compilerArgs))
run=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
write(out/'compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-12000:])
result={'status':'PASS'if run.returncode==0 else'FAIL','actualSnapshot':61,'entries':101,'productionOverrides':0,'actualRootMounted':False,'sourceCount':len(sources),'soleAudioSources':3,'declaredMusicReferences':24,'declaredChildCommentReferences':5,'sources':receipts}
if run.returncode==0:
 jar=out/'full-audio-source-candidate.jar'
 with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED)as z:
  for p in sorted(safe(out/'classes').rglob('*')):
   if p.is_file():z.writestr(p.relative_to(safe(out/'classes')).as_posix(),p.read_bytes())
 with zipfile.ZipFile(safe(cp[1]['path']))as product,zipfile.ZipFile(safe(jar))as candidate:
  overlap=sorted(n for n in set(product.namelist())&set(candidate.namelist())if n.endswith('.class'))
 assert not overlap,overlap
 result['candidateJar']={'path':str(jar),'sha256Bytes':sha(jar)};result['existingClassOverlap']=overlap
for row in cp:assert sha(row['path'])==row['sha256Bytes']
for row in receipts:assert sha(row['path'])==row['sha256Bytes']
write(out/'compile-result.json',json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items()if k!='sources'},indent=2));sys.exit(run.returncode)
