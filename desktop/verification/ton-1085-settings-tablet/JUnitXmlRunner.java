import java.io.*;
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
  if(count[0]!=(args[0].endsWith("SettingsRootCategoryContentStructureTest") ? 25 : 5) || count[2]!=0) System.exit(2); if(count[1]!=0) System.exit(1);
 }
}