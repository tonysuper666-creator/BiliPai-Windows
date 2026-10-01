from pathlib import Path
import hashlib,json,subprocess,importlib.util,zipfile,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
def plain(p):return str(p).removeprefix(chr(92)*2+'?'+chr(92))
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
snapshot=MAIN/'desktop/.local/stable-product-snapshot-47';receipt=snapshot/'ordered-runtime-cp.json'
assert sha(snapshot/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(receipt)=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
cp=json.loads(read(receipt));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
shared=MAIN/'desktop/.local/stable-video-detail-full-ui-parity/runs/03/candidate.jar'
assert sha(shared)=='a95cfab0d91e528bf443edd529445352d5c2d250584d0b8a75ed69fb5ff34b65'
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('compile-'+number);assert not safe(out).exists();safe(out).mkdir()
kotlin=sorted(safe(LANE/'prepared').rglob('*.kt'))+sorted(safe(LANE/'compile-inputs').rglob('*.kt'))
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',str(shared)+';'+';'.join(r['path'] for r in cp),'-Xfriend-paths='+cp[1]['path']+','+str(shared),'-Xplugin='+str(c.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out/'classes')]+[plain(p) for p in kotlin]
argfile=out/'kotlin.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Xmx4g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(out/'kotlin.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stderr);sys.exit(r.returncode)
jar=out/'original-offline-player.jar'
with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(safe(out/'classes').rglob('*')):
  if p.is_file():z.writestr(p.relative_to(safe(out/'classes')).as_posix(),p.read_bytes())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
result={'status':'PASS_PREPARED_SOURCE','actualSnapshot':47,'actualRuntimeEntries':97,'sources':len(kotlin),'jar':str(jar),'jarSha256Bytes':sha(jar),'declaredProspectiveOverrides':['DanmakuOverlay sole existing family','3146 bridge not present in actual47; task-only modified frozen source'],'sharedGestureReference':{'jar':str(shared),'sha256Bytes':sha(shared),'prospectiveNotActual':True,'otherSharedJarFamiliesNotInstalledHere':True},'sourcePins':[{'path':plain(p),'sha256Bytes':sha(p)} for p in kotlin],'sharedGradleRun':False,'rootMounted':False,'nativeForegroundAccepted':False}
safe(out/'compile-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in result.items() if k!='sourcePins'},indent=2))
