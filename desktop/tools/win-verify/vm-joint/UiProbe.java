import com.sun.tools.attach.VirtualMachine;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javax.accessibility.*;
import javax.swing.SwingUtilities;

/** Test-only accessibility driver attached to a PID already measured by the launcher. */
public class UiProbe {
  public static void main(String[] args) throws Exception {
    VirtualMachine vm = VirtualMachine.attach(args[0]);
    try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
  }
  static final Path BOUNDARY = Path.of("C:\\BuildVM\\cards\\ton1111\\profile").toAbsolutePath();
  static Path inside(String raw) throws Exception {
    Path p=Path.of(raw).toAbsolutePath().normalize();
    if(!p.startsWith(BOUNDARY)) throw new SecurityException("test output outside isolated profile");
    for(Path c=p;c!=null && c.startsWith(BOUNDARY);c=c.getParent())
      if(Files.exists(c) && Files.isSymbolicLink(c)) throw new SecurityException("symbolic link");
    return p;
  }
  public static void agentmain(String argument, Instrumentation unused) throws Exception {
    Properties cfg=new Properties();
    try(var reader=Files.newBufferedReader(inside(argument),StandardCharsets.UTF_8)){cfg.load(reader);}
    String command=cfg.getProperty("command");
    String value=new String(Base64.getDecoder().decode(cfg.getProperty("value","")),StandardCharsets.UTF_8);
    Path result=inside(cfg.getProperty("result"));
    List<AccessibleContext> contexts=new ArrayList<>(); StringBuilder dump=new StringBuilder();
    dump.append("JVM user.home=").append(System.getProperty("user.home")).append("\nJVM java.io.tmpdir=")
      .append(System.getProperty("java.io.tmpdir")).append("\n");
    if("reveal".equals(command)) {
      Pattern target=Pattern.compile(value);
      boolean[] present={false}; Rectangle[] bounds={null};
      Robot robot=new Robot();
      for(int attempt=0;attempt<20;attempt++) {
        SwingUtilities.invokeAndWait(()->{
          List<AccessibleContext> rows=new ArrayList<>();
          Window active=KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
          if(active==null || !active.isShowing()) throw new IllegalStateException("owned JVM window is not active");
          bounds[0]=active.getBounds();
          walk(active.getAccessibleContext(),rows,new StringBuilder(),0);
          present[0]=rows.stream().anyMatch(c->target.matcher(Objects.toString(c.getAccessibleName(),"")).matches());
        });
        if(present[0]) break;
        Rectangle r=bounds[0]; robot.mouseMove(r.x+r.width*3/4,r.y+r.height*2/3); robot.mouseWheel(3);
        Thread.sleep(250);
      }
      if(!present[0]) throw new IllegalStateException("setting did not become accessible during bounded scroll");
      command="dump";
    }
    final String selectedCommand=command;
    String[] outcome={"ERROR"}; CountDownLatch finished=new CountDownLatch(1);
    SwingUtilities.invokeLater(()->{
      try {
        for(Window w:Window.getWindows()) if(w.isShowing()) walk(w.getAccessibleContext(),contexts,dump,0);
        if("click".equals(selectedCommand)) {
          Pattern pattern=Pattern.compile(value);
          List<AccessibleContext> matches=new ArrayList<>();
          for(var c:contexts) if(c.getAccessibleAction()!=null && c.getAccessibleAction().getAccessibleActionCount()>0 &&
              pattern.matcher(Objects.toString(c.getAccessibleName(),"")).matches()) matches.add(c);
          if(matches.size()!=1) throw new IllegalStateException("expected one actionable control, got "+matches.size()+": "+value);
          if(!matches.get(0).getAccessibleAction().doAccessibleAction(0)) throw new IllegalStateException("control rejected action");
        } else if("text".equals(selectedCommand)) {
          List<AccessibleEditableText> edits=new ArrayList<>();
          for(var c:contexts) if(c.getAccessibleEditableText()!=null) edits.add(c.getAccessibleEditableText());
          if(edits.size()!=1) throw new IllegalStateException("expected one visible editable text, got "+edits.size());
          edits.get(0).setTextContents(value);
        } else if(!"dump".equals(selectedCommand)) throw new IllegalArgumentException("unknown command");
        outcome[0]="OK";
      } catch(Throwable t) {outcome[0]="ERROR: "+t;}
      finally {finished.countDown();}
    });
    if(!finished.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("UI thread did not respond");
    Files.writeString(result,outcome[0]+"\n"+Base64.getEncoder().encodeToString(dump.toString().getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8);
  }
  static void walk(AccessibleContext c,List<AccessibleContext> list,StringBuilder out,int depth) {
    if(c==null || depth>70 || list.size()>15000) return;
    if(!c.getAccessibleStateSet().contains(AccessibleState.SHOWING)) return;
    list.add(c); out.append("  ".repeat(Math.min(depth,12))).append(c.getAccessibleRole()).append(" | ")
      .append(c.getAccessibleName()).append(" | ").append(c.getAccessibleDescription()).append('\n');
    for(int i=0;i<c.getAccessibleChildrenCount();i++) {
      Accessible child=c.getAccessibleChild(i); if(child!=null) walk(child.getAccessibleContext(),list,out,depth+1);
    }
  }
}
