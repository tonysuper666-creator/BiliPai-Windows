import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import org.junit.platform.launcher.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.TestExecutionResult;
import org.json.*;
import java.nio.file.*;
public final class NavigationJUnitRunner {
 public static void main(String[] args) throws Exception {
  var request=LauncherDiscoveryRequestBuilder.request().selectors(
   DiscoverySelectors.selectClass("com.bilipai.desktop.settings.DesktopFullNavigationSettingsTest"),
   DiscoverySelectors.selectClass("com.bilipai.desktop.settings.DesktopNavigationInteractionSettingsTest")).build();
  var listener=new SummaryGeneratingListener();var methods=new JSONArray();
  var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener,new TestExecutionListener(){
   public void executionFinished(TestIdentifier id,TestExecutionResult result){if(id.isTest()){
    methods.put(new JSONObject().put("name",id.getDisplayName()).put("status",result.getStatus().toString())
     .put("error",result.getThrowable().map(Throwable::toString).orElse("")));}}
  });
  launcher.execute(request);var summary=listener.getSummary();
  var report=new JSONObject().put("passed",summary.getTestsFailedCount()==0&&summary.getTestsFoundCount()==20)
   .put("found",summary.getTestsFoundCount()).put("succeeded",summary.getTestsSucceededCount())
   .put("failed",summary.getTestsFailedCount()).put("skipped",summary.getTestsSkippedCount()).put("methods",methods);
  Files.writeString(Path.of(args[0]),report.toString(2));summary.printTo(new java.io.PrintWriter(System.out));
  if(summary.getTestsFailedCount()>0){summary.printFailuresTo(new java.io.PrintWriter(System.out));throw new AssertionError("JUnit failed");}
  if(summary.getTestsFoundCount()!=20)throw new AssertionError("Not all 14 new + 6 earlier methods were discovered");
 }
}
