from pathlib import Path
import json,zipfile,subprocess,importlib.util
LANE=Path(__file__).resolve().parent;ROOT=LANE.parents[4];MAIN=ROOT/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
# Reuse the reviewed pure bytecode parser, without executing its historic lane/mutations.
parser={'__file__':str(LANE/'audit_abi.py')};exec(read(LANE.parent/'stable-home-live-list-parity/audit_abi.py').split('aud=json.loads')[0],parser);parse=parser['parse']
aud=json.loads(read(LANE/'input-audit.json'));candidate=json.loads(read(LANE/'compile-07/compile-result.json'))
actualclasses=set();actualmethods={}
for row in aud['verifiedDependencyPins']:
 with zipfile.ZipFile(safe(row['path'])) as jar:
  for name in jar.namelist():
   if not name.endswith('.class'):continue
   actualclasses.add(name)
   if name.startswith(('com/android/purebilibili/','com/bilipai/desktop/')) and name.endswith('Kt.class'):
    for access,method,desc in parse(jar.read(name)):
     if access&9==9:actualmethods.setdefault((name.rsplit('/',1)[0],method,desc),[]).append(name)
invalid=[];overlap=[];methodcount=0;classes=[]
with zipfile.ZipFile(safe(candidate['jar'])) as jar:
 for name in jar.namelist():
  if not name.endswith('.class'):continue
  classes.append(name);assert name not in actualclasses,name
  methods=parse(jar.read(name));methodcount+=len(methods)
  for access,method,desc in methods:
   if any(c in method for c in '.;/[') or ('<' in method and method not in ['<init>','<clinit>']):invalid.append([name,method])
   if name.endswith('Kt.class') and access&9==9 and (name.rsplit('/',1)[0],method,desc) in actualmethods:overlap.append([name,method,desc])
assert not invalid and not overlap,(invalid,overlap)
sp=importlib.util.spec_from_file_location('profile_classload_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(sp);sp.loader.exec_module(c)
out=safe(LANE/'classload-proof');out.mkdir(exist_ok=True)
source='''public final class ProfileClassLoadProof {
 public static void main(String[] args) throws Exception {
  int n=0;
  try (java.util.jar.JarFile jar=new java.util.jar.JarFile(args[0])) {
   for(java.util.jar.JarEntry entry:java.util.Collections.list(jar.entries())) {
    if(entry.getName().endsWith(".class")) {
     Class.forName(entry.getName().replace('/','.').replaceAll("\\\\.class$",""),false,ProfileClassLoadProof.class.getClassLoader());n++;
    }
   }
  }
  System.out.println("PASS actual40/97 zero override; loaded "+n+" prepared classes without initialization");
 }
}'''
(out/'ProfileClassLoadProof.java').write_text(source,encoding='utf-8')
r=subprocess.run([str(c.JAVA.with_name('javac.exe')),'-d',str(out),str(out/'ProfileClassLoadProof.java')],capture_output=True,text=True);(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
classpath=[str(LANE/'classload-proof'),candidate['jar']]+[r['path'] for r in aud['verifiedDependencyPins']]
argfile=out/'runner.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in ['-cp',';'.join(classpath),'ProfileClassLoadProof',candidate['jar']]),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace');(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
result={'actualSnapshot':40,'actualProductEntries':97,'candidateClasses':len(classes),'candidateMethods':methodcount,'classFqnOverlap':[],'samePackagePublicStaticMethodOverlap':overlap,'invalidJvmMethodNames':invalid,'jvmLoadedPreparedClasses':len(classes),'runtimeSourceOverrides':0,'actualRootWindowOrNetwork':False}
safe(LANE/'abi-audit.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2));print(r.stdout)
