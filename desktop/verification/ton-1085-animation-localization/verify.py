#!/usr/bin/env python3
"""Run the upstream JUnit class with original/corrected tests on fixed source fixtures.

Requires Java, javac, patch and the named artifacts in the local Gradle cache.
No Gradle task, registration or upstream file is modified.
"""
from pathlib import Path
import glob, os, shutil, subprocess, sys
import xml.etree.ElementTree as ET

repo=Path(__file__).resolve().parents[3]
evidence=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path('animation-evidence').resolve()
if evidence.exists(): raise SystemExit(f"Refusing to overwrite evidence directory: {evidence}")
evidence.mkdir(parents=True)
test='app/src/test/java/com/android/purebilibili/feature/settings/AnimationSettingsScreenStructureTest.kt'
source='app/src/main/java/com/android/purebilibili/feature/settings/screen/AnimationSettingsScreen.kt'
strings='app/src/main/res/values/strings.xml'
patch=repo/('desktop/upstream-core/patches/'+test+'.patch')
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,artifact,version):
    files=glob.glob(str(cache/group/artifact/version/'*'/(artifact+'-'+version+'.jar')))
    if len(files)!=1: raise RuntimeError((artifact,version,files))
    return files[0]
compiler=[jar('org.jetbrains.kotlin',x,'2.4.0') for x in ('kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable')]+[jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),jar('org.jetbrains','annotations','23.0.0'),jar('org.jetbrains.kotlin','kotlin-reflect','2.3.20')]
testcp=[jar('org.jetbrains.kotlin',x,'2.4.0') for x in ('kotlin-stdlib','kotlin-test','kotlin-test-junit5')]+[jar('org.junit.jupiter',x,'5.13.4') for x in ('junit-jupiter-api','junit-jupiter-engine')]+[jar('org.junit.platform',x,'1.13.4') for x in ('junit-platform-commons','junit-platform-engine','junit-platform-launcher')]+[jar('org.opentest4j','opentest4j','1.3.0'),jar('org.apiguardian','apiguardian-api','1.1.2')]
runner=evidence/'JUnitXmlRunner.java'
runner.write_text('''import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import org.junit.platform.engine.*;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;
import org.w3c.dom.*;
public class JUnitXmlRunner {
 public static void main(String[] args) throws Exception {
  Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
  Element suite=doc.createElement("testsuite"); suite.setAttribute("name",args[0]); doc.appendChild(suite);
  List<String> failures=new ArrayList<>(); int[] count={0,0,0};
  TestExecutionListener listener=new TestExecutionListener() {
   public void executionFinished(TestIdentifier id, TestExecutionResult result) {
    if (!id.isTest()) return;
    count[0]++; Element tc=doc.createElement("testcase"); tc.setAttribute("name",id.getDisplayName()); tc.setAttribute("classname",args[0]); suite.appendChild(tc);
    if (result.getStatus()==TestExecutionResult.Status.FAILED) {
     count[1]++; failures.add(id.getDisplayName()); Element f=doc.createElement("failure"); Throwable t=result.getThrowable().orElse(new AssertionError("no failure")); f.setAttribute("message",t.toString()); StringWriter s=new StringWriter(); t.printStackTrace(new PrintWriter(s)); f.appendChild(doc.createTextNode(s.toString())); tc.appendChild(f);
    } else if (result.getStatus()!=TestExecutionResult.Status.SUCCESSFUL) {count[2]++;tc.appendChild(doc.createElement("skipped"));}
   }
   public void executionSkipped(TestIdentifier id, String reason) { if (id.isTest()) {count[0]++;count[2]++;Element tc=doc.createElement("testcase");tc.setAttribute("name",id.getDisplayName());tc.setAttribute("classname",args[0]);tc.appendChild(doc.createElement("skipped"));suite.appendChild(tc);} }
  };
  Launcher launcher=LauncherFactory.create(); launcher.registerTestExecutionListeners(listener);
  launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(args[0])).build());
  suite.setAttribute("tests",String.valueOf(count[0])); suite.setAttribute("failures",String.valueOf(count[1])); suite.setAttribute("skipped",String.valueOf(count[2]));
  Transformer transformer=TransformerFactory.newInstance().newTransformer();transformer.setOutputProperty(OutputKeys.INDENT,"yes");transformer.transform(new DOMSource(doc),new StreamResult(new File(args[1])));
  System.out.println("tests="+count[0]+" failures="+count[1]+" skipped="+count[2]+" identities="+failures);
  if(count[0]!=6 || count[2]!=0) System.exit(2); if(count[1]!=0) System.exit(1);
 }
}''')
subprocess.run(['javac','-cp',os.pathsep.join(testcp),'-d',str(evidence),str(runner)],check=True)
for name in ('original','corrected','negative-label','negative-binding','negative-action'):
 d=evidence/name
 for path in (test,source,strings):
  (d/path).parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(repo/path,d/path)
 if name!='original': subprocess.run(['patch','--batch','-p1','-i',str(patch)],cwd=d,check=True)
 if name.startswith('negative-'):
  path=d/(strings if name=='negative-label' else source); text=path.read_text()
  before,after={
   'negative-label':('<string name="animation_title_transition_blur">转场时模糊背景</string>','<string name="animation_title_transition_blur">错误文案</string>'),
   'negative-binding':('title = localizedAnimationSettingTitle("animation.video_transition_realtime_blur_enabled")','title = localizedAnimationSettingTitle("wrong.key")'),
   'negative-action':('checked = videoTransitionRealtimeBlurEnabled','checked = false'),
  }[name]
  assert text.count(before)==1,(name,text.count(before)); path.write_text(text.replace(before,after))
 if name in ('original','corrected'):
  classes=d/'classes'; classes.mkdir()
  subprocess.run(['java','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(testcp),'-d',str(classes),str(d/test)],check=True)
 classes=evidence/('original' if name=='original' else 'corrected')/'classes'
 command=['java','-cp',os.pathsep.join(map(str,[evidence,classes]+testcp)),'JUnitXmlRunner','com.android.purebilibili.feature.settings.AnimationSettingsScreenStructureTest',str(evidence/(name+'.xml'))]
 result=subprocess.run(command,cwd=d,text=True,capture_output=True)
 (evidence/(name+'.log')).write_text('command: '+repr(command)+'\nexit: '+str(result.returncode)+'\n'+result.stdout+result.stderr)
 print(name,'exit',result.returncode,result.stdout.strip(),flush=True)
 if result.returncode!=1: raise RuntimeError((name,result.returncode,result.stderr))
 xml=ET.parse(evidence/(name+'.xml')).getroot()
 failures={case.attrib['name'] for case in xml.findall('testcase') if case.find('failure') is not None}
 predictive='animationSettingsScreen_controlsGlobalPredictivePreviewIndependently()'
 blur='animationSettingsScreen_exposesRealtimeTransitionBlurToggle()'
 expected={predictive} if name=='corrected' else ({predictive,blur} if name.startswith('negative-') else {
  predictive, blur, 'animationSettingsScreen_importsLiquidGlassSettingsAfterConfirmation()',
  'animationSettingsScreen_exposesVideoSharedReturnGestureFollowToggle()',
  'animationSettingsScreen_exposesLiveSurfaceCardTransitionToggle()'})
 if failures!=expected: raise RuntimeError((name,failures,expected))
print('Raw XML: '+str(evidence))
