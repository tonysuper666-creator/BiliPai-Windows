from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
out=LANE/('raster-proof-'+sys.argv[1]);assert not safe(out).exists();safe(out).mkdir()
snap=MAIN/'desktop/.local/stable-product-snapshot-54';cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==101
assert sha(snap/'ordered-runtime-cp.json')=='add7053c45aa02db763c7c2d105447b071646041ef59bcabfd217026fbbd3065'
for r in cp:assert sha(r['path'])==r['sha256Bytes']
candidate=LANE/'compile-07/tablet-audio-candidate.jar';assert sha(candidate)=='aa83c9f637d48eaf5b25d158f4d7076cbfa485e0c33b991df688260566174c30'
source=LANE/'MusicRasterProof.kt';write(out/'MusicRasterProof.kt',safe(source).read_text(encoding='utf-8'))
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
paths=[str(candidate)]+[r['path']for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(paths),'-Xfriend-paths='+cp[1]['path']+','+str(candidate),'-d',str(out/'classes'),str(out/'MusicRasterProof.kt')]
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
compile=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
write(out/'compile.log',compile.stdout+compile.stderr);print((compile.stdout+compile.stderr)[-7000:])
if compile.returncode:sys.exit(compile.returncode)
run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',';'.join([str(out/'classes')]+paths),'com.bilipai.desktop.ui.MusicRasterProofKt',str(out/'fixture-owned')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(out/'runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-7000:])
for r in cp:assert sha(r['path'])==r['sha256Bytes']
result={'status':'PASS'if run.returncode==0 else'FAIL','exitCode':run.returncode,'snapshot':54,'entries':101,'productionOverrides':0,'explicitProspectiveJar':{'path':str(candidate),'sha256Bytes':sha(candidate)},'sourceSha256LF':sha(source),'windowOrAccount':False,'notRootAcceptance':True}
write(out/'result.json',json.dumps(result,indent=2)+'\n');sys.exit(run.returncode)
