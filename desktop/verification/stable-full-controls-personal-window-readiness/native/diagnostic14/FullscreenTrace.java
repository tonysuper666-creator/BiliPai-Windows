import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Fixture-only read-only debugger: records fullscreen authority call stacks. */
public final class FullscreenTrace {
    private static final Set<String> TYPES = Set.of("java.awt.GraphicsDevice", "sun.awt.Win32GraphicsDevice", "sun.java2d.d3d.D3DGraphicsDevice", "java.awt.Window");
    private static final Set<String> installed = new HashSet<>();
    private static final long started = System.nanoTime();
    private static String quoted(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static String value(Value v) {
        if (v == null) return "null";
        if (v instanceof ObjectReference o) return o.referenceType().name() + "#" + o.uniqueID();
        return v.toString();
    }
    private static void install(VirtualMachine vm, ReferenceType type) {
        if (!TYPES.contains(type.name())) return;
        for (Method method : type.methods()) {
            boolean wanted = method.name().equals("setFullScreenWindow") || (type.name().equals("java.awt.Window") && (method.name().equals("dispose") || method.name().equals("disposeImpl")));
            String key = method.declaringType().name() + "." + method.name() + method.signature();
            if (!wanted || method.isNative() || method.isAbstract() || !installed.add(key)) continue;
            BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(method.location());
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.enable();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("port output.jsonl");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        if (Files.exists(output)) throw new IllegalStateException("Refusing to overwrite diagnostic " + output);
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(args[0]);
        arguments.get("timeout").setValue("10000");
        VirtualMachine vm = connector.attach(arguments);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
            for (String name : TYPES) {
                for (ReferenceType type : vm.classesByName(name)) install(vm, type);
                ClassPrepareRequest request = vm.eventRequestManager().createClassPrepareRequest();
                request.addClassFilter(name);
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                request.enable();
            }
            writer.write("{\"diagnosticOnly\":true,\"productClassesModified\":0,\"inputInjected\":false,\"host\":\"127.0.0.1\",\"port\":" + args[0] + "}\n");
            writer.flush();
            vm.resume();
            boolean active = true;
            while (active) {
                EventSet events = vm.eventQueue().remove();
                try {
                    for (Event event : events) {
                        if (event instanceof ClassPrepareEvent e) install(vm, e.referenceType());
                        if (event instanceof BreakpointEvent e) {
                            ThreadReference thread = e.thread();
                            StackFrame top = thread.frame(0);
                            Method method = e.location().method();
                            List<Value> values = top.getArgumentValues();
                            List<String> frames = new ArrayList<>();
                            for (StackFrame frame : thread.frames()) {
                                Location l = frame.location();
                                frames.add(quoted(l.declaringType().name() + "." + l.method().name() + ":" + l.lineNumber()));
                            }
                            List<String> rendered = new ArrayList<>();
                            for (Value v : values) rendered.add(quoted(value(v)));
                            String row = "{\"elapsedMs\":" + ((System.nanoTime()-started)/1_000_000) + ",\"thread\":" + quoted(thread.name()) + ",\"method\":" + quoted(method.declaringType().name()+"."+method.name()) + ",\"receiver\":" + quoted(value(top.thisObject())) + ",\"arguments\":[" + String.join(",", rendered) + "],\"frames\":[" + String.join(",",frames) + "]}";
                            writer.write(row + "\n"); writer.flush();
                            System.out.println(method.name() + " " + rendered + " " + (frames.size()>1 ? frames.get(1) : ""));
                        }
                        if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) active = false;
                    }
                } finally { try { events.resume(); } catch (VMDisconnectedException ignored) {} }
            }
        } catch (VMDisconnectedException expected) {
            System.out.println("Diagnostic VM disconnected normally");
        } finally { try { vm.dispose(); } catch (VMDisconnectedException ignored) {} }
    }
}
