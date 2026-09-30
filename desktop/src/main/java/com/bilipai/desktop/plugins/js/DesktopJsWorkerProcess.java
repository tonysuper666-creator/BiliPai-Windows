package com.bilipai.desktop.plugins.js;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** App-owned process transport. JS never supplies the executable, classpath, environment or argv. */
public final class DesktopJsWorkerProcess implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FRAMES = 512;
    private final Object gate = new Object();
    private final Set<Execution> executions = new HashSet<>();
    private final List<String> command;
    private final Path directory;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "js-plugin-deadline"); thread.setDaemon(true); return thread;
    });
    private boolean stopped;

    /** Validate the installed application's actual runtime files before constructing this host. */
    public DesktopJsWorkerProcess(Path javaExecutable, List<Path> classpath, Path directory, String workerMain) throws IOException {
        if (workerMain == null || !workerMain.matches("[A-Za-z_$][A-Za-z0-9_$.]{1,200}"))
            throw new IllegalArgumentException("JS worker class");
        Path java = javaExecutable.toRealPath();
        if (!Files.isRegularFile(java)) throw new IOException("JS worker runtime missing");
        // javaw preserves stdin/stdout pipes while avoiding a new console window on Windows.
        if (System.getProperty("os.name", "").startsWith("Windows") && !java.getFileName().toString().equalsIgnoreCase("javaw.exe"))
            throw new IOException("JS worker requires the bundled javaw.exe");
        List<String> entries = new ArrayList<>();
        for (Path entry : List.copyOf(classpath)) {
            Path canonical = entry.toRealPath();
            if (!Files.isRegularFile(canonical) && !Files.isDirectory(canonical)) throw new IOException("JS worker classpath missing");
            String text = canonical.toString();
            if (text.indexOf(File.pathSeparatorChar) >= 0) throw new IOException("JS worker classpath separator");
            entries.add(text);
        }
        if (entries.isEmpty()) throw new IOException("JS worker classpath missing");
        this.directory = directory.toRealPath();
        if (!Files.isDirectory(this.directory)) throw new IOException("JS worker working directory missing");
        this.command = List.of(java.toString(), "-Xmx96m", "-XX:MaxMetaspaceSize=128m", "-Xss1m",
            "-Dfile.encoding=UTF-8", "-classpath", String.join(File.pathSeparator, entries), workerMain);
    }

    public interface FrameHandler {
        FrameResult handle(String workerFrame) throws Exception;
        /** Must promptly cancel any app-owned HTTP call or other blocking bridge operation. */
        default void cancel() {}
    }
    public record FrameResult(boolean complete, String value) {
        public static FrameResult reply(String json) { return new FrameResult(false, Objects.requireNonNull(json)); }
        public static FrameResult complete(String payload) { return new FrameResult(true, Objects.requireNonNull(payload)); }
    }
    public Execution create() {
        synchronized (gate) {
            if (stopped) throw new IllegalStateException("JS plugin host stopped");
            if (executions.size() >= 4) throw new IllegalStateException("JS plugin execution limit");
            Execution execution = new Execution(); executions.add(execution); return execution;
        }
    }

    public final class Execution implements AutoCloseable {
        private final AtomicInteger state = new AtomicInteger(); // 0 created, 1 running, 2 ended
        private final AtomicReference<Process> child = new AtomicReference<>();
        private final AtomicReference<FrameHandler> bridge = new AtomicReference<>();
        private final AtomicReference<String> cancellation = new AtomicReference<>();
        private final CountDownLatch joined = new CountDownLatch(1);
        private volatile Thread executingThread;

        /** Runs only on a host IO thread; cancellation and shutdown can happen from another thread. */
        public String execute(String request, FrameHandler handler, long timeoutMillis) throws Exception {
            if (!state.compareAndSet(0, 1)) throw new CancellationException("JS execution already ended");
            executingThread = Thread.currentThread();
            ScheduledFuture<?> timeout = null;
            try {
                if (timeoutMillis < 1 || timeoutMillis > 15_000) throw new IllegalArgumentException("JS execution deadline");
                checkedBytes(request);
                bridge.set(Objects.requireNonNull(handler));
                timeout = deadlines.schedule(() -> stop("deadline"), timeoutMillis, TimeUnit.MILLISECONDS);
                synchronized (gate) {
                    checkCancelled();
                    if (stopped) throw new CancellationException("JS plugin host stopped");
                    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
                    // Runtime flags and authentication from the app's environment must not enter the worker.
                    builder.environment().clear();
                    if (System.getProperty("os.name", "").startsWith("Windows")) {
                        String systemRoot = System.getenv("SystemRoot");
                        if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
                    }
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                    child.set(builder.start());
                }
                Process process = child.get();
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
                     DataInputStream in = new DataInputStream(new BufferedInputStream(process.getInputStream()))) {
                    checkCancelled(); write(out, request);
                    for (int count = 0; count < MAX_FRAMES; count++) {
                        checkCancelled();
                        String frame = read(in);
                        checkCancelled();
                        FrameResult result = Objects.requireNonNull(handler.handle(frame));
                        checkCancelled();
                        if (result.complete()) return result.value();
                        write(out, result.value());
                    }
                    throw new IOException("JS IPC operation limit");
                }
            } catch (Exception failure) {
                checkCancelled();
                throw failure;
            } finally {
                if (timeout != null) timeout.cancel(false);
                bridge.set(null);
                try { terminateAndJoin(); }
                finally {
                    state.set(2);
                    synchronized (gate) { executions.remove(this); }
                    executingThread = null;
                    joined.countDown();
                }
            }
        }
        private void checkCancelled() throws TimeoutException {
            String reason = cancellation.get();
            if ("deadline".equals(reason)) throw new TimeoutException("JS execution deadline");
            if (reason != null || Thread.currentThread().isInterrupted()) throw new CancellationException("JS execution cancelled");
        }
        private void stop(String reason) {
            cancellation.compareAndSet(null, reason);
            FrameHandler handler = bridge.get();
            if (handler != null) { try { handler.cancel(); } catch (RuntimeException ignored) {} }
            Process process = child.get();
            if (process != null) process.destroyForcibly();
            if (state.compareAndSet(0, 2)) {
                synchronized (gate) { executions.remove(this); }
                joined.countDown();
            }
        }
        @Override public void close() { stop("cancelled"); }
        public long pid() { Process process = child.get(); return process == null ? -1 : process.pid(); }
        public boolean isAlive() { Process process = child.get(); return process != null && process.isAlive(); }
        public boolean awaitStopped(Duration timeout) throws InterruptedException {
            if (Thread.currentThread() == executingThread) throw new IllegalStateException("JS execution cannot join itself");
            return joined.await(timeout.toMillis(), TimeUnit.MILLISECONDS) && !isAlive();
        }
        private void terminateAndJoin() {
            Process process = child.get();
            if (process == null) return;
            process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.isAlive())
                    throw new IllegalStateException("JS worker did not terminate");
                for (Closeable pipe : List.of(process.getInputStream(), process.getOutputStream(), process.getErrorStream())) {
                    try { pipe.close(); } catch (IOException ignored) {}
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                throw new IllegalStateException("JS worker termination interrupted", failure);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /** Restore calls this off the UI thread and must abort if a bridge fails to quiesce. */
    public void shutdownAndJoin(Duration timeout) throws InterruptedException, TimeoutException {
        List<Execution> active;
        synchronized (gate) { stopped = true; active = List.copyOf(executions); }
        active.forEach(Execution::close);
        long end = System.nanoTime() + timeout.toNanos();
        for (Execution execution : active) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0 || !execution.awaitStopped(Duration.ofNanos(remaining)))
                throw new TimeoutException("JS plugin host did not quiesce");
        }
        deadlines.shutdownNow();
        if (!deadlines.awaitTermination(3, TimeUnit.SECONDS)) throw new TimeoutException("JS deadline thread did not stop");
    }
    @Override public void close() {
        try { shutdownAndJoin(Duration.ofSeconds(5)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("JS host shutdown interrupted", failure); }
        catch (TimeoutException failure) { throw new IllegalStateException("JS host shutdown deadline", failure); }
    }
    private static byte[] checkedBytes(String text) throws IOException {
        byte[] data = Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8);
        if (data.length < 1 || data.length > MAX_FRAME_BYTES) throw new IOException("JS IPC frame bounds");
        return data;
    }
    private static void write(DataOutputStream out, String text) throws IOException {
        byte[] bytes = checkedBytes(text); out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
    private static String read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_FRAME_BYTES) throw new IOException("JS IPC frame bounds");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("JS IPC frame truncated");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new IOException("JS IPC invalid UTF-8"); }
    }
}
