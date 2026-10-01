from pathlib import Path
import hashlib,json,subprocess,importlib.util
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[4]/'work/BiliPai'
spec=importlib.util.spec_from_file_location('uri_java',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
source='''import java.nio.file.*;
public class WindowsProfileFileUriProof {
 public static void main(String[] args) {
  Path path=Path.of("C:\\\\Users\\\\TONYS\\\\Documents\\\\Profile wallpaper 空格\\\\cover.jpg").toAbsolutePath().normalize();
  String uri=path.toUri().toString();
  if(!uri.startsWith("file:///"))throw new AssertionError(uri);
  if(!Path.of(java.net.URI.create(uri)).equals(path))throw new AssertionError("drive roundtrip");
  if(!new java.io.File(java.nio.file.Paths.get(java.net.URI.create(uri)).toString()).toPath().equals(path))throw new AssertionError("original file-existence adapter");
  if(!uri.contains("%20"))throw new AssertionError("escaped spaces");
  System.out.println("PASS 4 native Windows file URI/path assertions; no file read/network/Root window");
 }
}'''
(LANE/'WindowsProfileFileUriProof.java').write_text(source,encoding='utf-8')
r=subprocess.run([str(c.JAVA.with_name('javac.exe')),'-encoding','UTF-8','-d',str(LANE),str(LANE/'WindowsProfileFileUriProof.java')],capture_output=True,text=True,encoding='utf-8',errors='replace');(LANE/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(LANE),'WindowsProfileFileUriProof'],capture_output=True,text=True,encoding='utf-8',errors='replace');(LANE/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
files=['prepare.py','proof.py','local-hunks.json','prepared/extract-upstream-profile-main.py','WindowsProfileFileUriProof.java','compile.log','run.log']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
result={'baseProfile137Unchanged':True,'localHunks':3,'wholeSourceInstall':False,'nativeWindowsUriAssertions':4,'rootAccepted':False,'artifacts':[{'path':p,'sha256Bytes':sha(LANE/p)} for p in files]}
(LANE/'frozen-handoff.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'sha256Bytes':sha(LANE/'frozen-handoff.json'),'assertions':4},indent=2));print(r.stdout)
