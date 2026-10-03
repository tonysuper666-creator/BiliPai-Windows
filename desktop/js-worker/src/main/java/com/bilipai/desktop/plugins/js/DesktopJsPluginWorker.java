package com.bilipai.desktop.plugins.js;

/* Windows JS platform experiment. The original script wrapper is supplied unchanged by the parent. */
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class DesktopJsPluginWorker {
    private static final int MAX_FRAME = 2 * 1024 * 1024;
    private final DataInputStream input = new DataInputStream(System.in);
    private final DataOutputStream output = new DataOutputStream(System.out);
    private Context context;
    private String callId;
    private boolean finished;
    private String result;
    private String rejection;
    private long sequence;

    private String read() throws IOException {
        int size = input.readInt();
        if (size < 1 || size > MAX_FRAME) throw new IOException("JS IPC frame bounds");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("JS IPC frame truncated");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private void write(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("JS IPC frame bounds");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private Value parse(String text) { return context.getBindings("js").getMember("JSON").getMember("parse").execute(text); }
    private Object bridge(String name, Value[] arguments) {
        try {
            long id = ++sequence;
            StringBuilder args = new StringBuilder("[");
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) args.append(',');
                args.append(quote(arguments[i].isNull() ? "" : arguments[i].asString()));
            }
            args.append(']');
            write("{\"type\":\"bridge\",\"id\":" + id + ",\"name\":" + quote(name) + ",\"args\":" + args + "}");
            Value reply = parse(read());
            if (!reply.getMember("id").fitsInLong() || reply.getMember("id").asLong() != id)
                throw new IOException("JS IPC operation mismatch");
            if (reply.hasMember("error")) throw new IOException("JS host bridge rejected: " + reply.getMember("error").asString());
            Value value = reply.getMember("value");
            return value == null || value.isNull() ? null : value.asString();
        } catch (IOException failure) { throw new IllegalStateException(failure.getMessage()); }
    }
    private ProxyObject bridgeObject(String prefix, String... methods) {
        java.util.Map<String,Object> entries = new java.util.HashMap<>();
        for (String method : methods) entries.put(method, (ProxyExecutable) args -> bridge(prefix + "." + method, args));
        return ProxyObject.fromMap(entries);
    }
    private void execute() throws Exception {
        String request = read();
        OutputStream guestOutput = new OutputStream() {
            int count;
            @Override public void write(int ignored) throws IOException { if (++count > 32768) throw new IOException("JS guest log limit"); }
        };
        try (Context owned = Context.newBuilder("js").sandbox(SandboxPolicy.CONSTRAINED)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.NONE).allowMutableTargetMappings().build())
                .allowHostClassLookup(name -> false).allowHostClassLoading(false)
                .allowIO(IOAccess.NONE).allowNativeAccess(false).allowCreateProcess(false).allowCreateThread(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .in(new ByteArrayInputStream(new byte[0])).out(guestOutput).err(guestOutput)
                .option("engine.WarnInterpreterOnly", "false")
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(5_000_000, source -> true).build()).build()) {
            context = owned;
            Value data = parse(request);
            callId = data.getMember("callId").asString();
            Value bindings = context.getBindings("js");
            java.util.Map<String,Object> callbacks = new java.util.HashMap<>();
            callbacks.put("resolve", (ProxyExecutable) args -> {
                if (args.length != 2 || !callId.equals(args[0].asString())) throw new IllegalStateException("JS callback call ID");
                if (!finished) { result = args[1].asString(); finished = true; }
                return null;
            });
            callbacks.put("reject", (ProxyExecutable) args -> {
                if (args.length != 2 || !callId.equals(args[0].asString())) throw new IllegalStateException("JS callback call ID");
                if (!finished) { rejection = args[1].asString(); finished = true; }
                return null;
            });
            bindings.putMember("BiliPaiNative", ProxyObject.fromMap(callbacks));
            bindings.putMember("BiliPaiHttpNative", bridgeObject("http", "get", "post"));
            bindings.putMember("BiliPaiStorageNative", bridgeObject("storage", "get", "set", "remove"));
            bindings.putMember("BiliPaiLogNative", bridgeObject("log", "write"));
            bindings.putMember("BiliPaiDomNative", bridgeObject("dom", "parse", "select", "selectOne"));
            // The original BiliPai.dom wrapper receives browser-shaped nodes through the same bounded IPC.
            context.eval("js", """
                function biliPaiDesktopDomNode(encoded) {
                  if (encoded == null) return null;
                  const node = {
                    tagName: encoded.tagName, textContent: encoded.textContent, innerHTML: encoded.innerHTML,
                    getAttribute: function(name) {
                      const key = String(name).toLowerCase();
                      return Object.prototype.hasOwnProperty.call(encoded.attributes, key) ? encoded.attributes[key] : null;
                    },
                    querySelectorAll: function(selector) {
                      return JSON.parse(BiliPaiDomNative.select(String(encoded.id), String(selector))).map(biliPaiDesktopDomNode);
                    },
                    querySelector: function(selector) {
                      return biliPaiDesktopDomNode(JSON.parse(BiliPaiDomNative.selectOne(String(encoded.id), String(selector))));
                    }
                  };
                  if (Object.prototype.hasOwnProperty.call(encoded, 'title')) node.title = encoded.title;
                  if (Object.prototype.hasOwnProperty.call(encoded, 'body')) node.body = biliPaiDesktopDomNode(encoded.body);
                  return node;
                }
                globalThis.DOMParser = class {
                  parseFromString(html, contentType) {
                    if (String(contentType).toLowerCase() !== 'text/html') throw Error('Only HTML DOM parsing is supported');
                    return biliPaiDesktopDomNode(JSON.parse(BiliPaiDomNative.parse(String(html))));
                  }
                };
                """);
            context.eval("js", "globalThis.window = globalThis;");
            context.eval("js", data.getMember("executionScript").asString());
            // GraalJS drains Promise jobs at each guest boundary. Parent owns the total wall-clock limit.
            while (!finished) { context.eval("js", "void 0"); Thread.sleep(5); }
            if (rejection != null) write("{\"type\":\"rejected\",\"message\":" + quote(rejection) + "}");
            else write("{\"type\":\"resolved\",\"payload\":" + quote(result) + "}");
        }
    }
    private static String quote(String text) {
        StringBuilder value = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') value.append('\\').append(c);
            else if (c < 32) value.append(String.format("\\u%04x", (int)c));
            else value.append(c);
        }
        return value.append('"').toString();
    }
    public static void main(String[] args) throws Exception {
        DesktopJsPluginWorker worker = new DesktopJsPluginWorker();
        try { worker.execute(); }
        catch (Throwable failure) {
            if (worker.context == null) System.err.println("VM configuration: " + failure.getMessage());
            // Do not echo source, headers, URLs or VM diagnostics into the host's error log.
            try { worker.write("{\"type\":\"failed\",\"category\":" + quote(failure.getClass().getSimpleName()) + "}"); }
            catch (Throwable ignored) { }
            System.exit(2);
        }
    }
}
