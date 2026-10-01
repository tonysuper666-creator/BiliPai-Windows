from pathlib import Path
import json,hashlib,subprocess,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-46';BEFORE=MAIN/'desktop/.local/stable-product-snapshot-45';OUT=HERE/'root-navigation-classes-actual46';assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_bytes());assert len(cp)==97
def pins():
 for row in cp:
  p=Path(row['path']);wide=Path(str(p)if str(p).startswith('\\\\?\\')else'\\\\?\\'+str(p));assert sha(wide.read_bytes())==row['sha256Bytes'],row['path']
pins()
with zipfile.ZipFile(SNAP/'main-kotlin.jar')as now,zipfile.ZipFile(BEFORE/'main-kotlin.jar')as old:
 names=sorted(n for n in set(now.namelist())-set(old.namelist())if n.endswith('.class'))
 assert len(names)==61,len(names)
(OUT/'new-class-names.txt').write_text('\n'.join(n[:-6].replace('/','.')for n in names)+'\n',encoding='utf-8')
source='''import java.nio.file.*;import java.util.*;
public final class RootNavigationClassProof {
 public static void main(String[]args)throws Exception {
  var expected=Path.of(args[1]).toUri().toURL();int count=0;
  for(String name:Files.readAllLines(Path.of(args[0]))) {
   Class<?> type=Class.forName(name,false,RootNavigationClassProof.class.getClassLoader());
   if(!expected.equals(type.getProtectionDomain().getCodeSource().getLocation()))throw new AssertionError(name);
   type.getDeclaredMethods();type.getDeclaredConstructors();count++;
  }
  System.out.println("Loaded "+count+" new Root/navigation class definitions from actual46; no initialization or UI actions.");
 }
}
'''
java=MAIN.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe';javac=java.with_name('javac.exe');p=OUT/'RootNavigationClassProof.java';p.write_text(source,encoding='utf-8');classes=OUT/'classes';classes.mkdir()
r=subprocess.run([str(javac),'-encoding','UTF-8','-d',str(classes),str(p)],capture_output=True,encoding='utf-8');(OUT/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0
r=subprocess.run([str(java),'-Dfile.encoding=UTF-8','-cp',str(classes)+';'+';'.join(row['path']for row in cp),'RootNavigationClassProof',str(OUT/'new-class-names.txt'),str(SNAP/'main-kotlin.jar')],capture_output=True,encoding='utf-8',timeout=60);(OUT/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0
assert list(classes.rglob('*.class'))==[classes/'RootNavigationClassProof.class']
pins();(OUT/'result.json').write_text(json.dumps(dict(passed=True,actualSnapshot=46,runtimeEntries=97,productionOverrides=0,newClassDefinitionsLoaded=61,declaredMethodsResolved=True,actualKotlinProductSha256Bytes=sha((SNAP/'main-kotlin.jar').read_bytes()),actualOrderedCpSha256Bytes=sha((SNAP/'ordered-runtime-cp.json').read_bytes()),newTopFunctionChromeHasOnlyExistingCanonicalOwner=True,classInitialization=False,rootUiMounted=False,externalHttp=False),indent=2)+'\n',encoding='utf-8');print(r.stdout.strip())
