import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Standalone, local-media diagnosis. Its exit code is never a product release gate.
 * All mpv client calls belong to one worker. AWT mutations belong to the EDT.
 * Screenshots are physical Robot captures; no decoded image can satisfy a gate. */
public final class AwtMpvProbe {
    private static final String DLL_SHA = "673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4";
    private static final long DLL_BYTES = 120342528L;
    private static final int WIDTH = 320, HEIGHT = 180, FPS = 20, SECONDS = 10;
    private static final long PID = ProcessHandle.current().pid();
    private final String caseName;
    private final Path output;
    private final Path dll;
    private final long started = System.nanoTime(), deadline;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final List<Object> observations = new ArrayList<>();
    private volatile String lastReport = "{}";
    private JFrame frame;
    private Canvas canvas;
    private JWindow overlay;
    private Tint tint;
    private javax.swing.Timer repaintTimer;
    private MpvActor actor;
    private User32 user32;
    private DwmApi dwm;
    private Rectangle screenBounds;

    private AwtMpvProbe(String caseName, Path output, Path dll) {
        this.caseName = caseName; this.output = output; this.dll = dll;
        deadline = started + TimeUnit.SECONDS.toNanos(caseName.equals("awt-alpha-only") ? 25 : 30);
        result.put("schema", 1); result.put("case", caseName); result.put("diagnosticOnly", true);
        result.put("passed", false); result.put("beganUtc", Instant.now().toString());
        result.put("ownPid", PID); result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("screenGate", Map.of("cyan", "B>180,G>135,R<135,count>=100",
            "pink", "R>180,G<145,B=100..200,count>=100"));
        result.put("alphaGate", "0.25 < dim/baseline < 0.6; abs(restored-baseline) < 5");
        result.put("observations", observations);
        publishReport();
    }

    public static void main(String[] args) {
        int code = 2;
        try {
            Map<String, String> cli = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length || !Set.of("--case", "--output", "--mpv").contains(args[i]) ||
                    cli.put(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Invalid or duplicate CLI argument");
            }
            String name = cli.get("--case");
            if (!Set.of("awt-alpha-only", "mpv-default-flip", "mpv-bitblt").contains(name))
                throw new IllegalArgumentException("--case must select one diagnostic case");
            Path output = Path.of(Objects.requireNonNull(cli.get("--output"), "Missing --output")).toAbsolutePath().normalize();
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("--output must not exist");
            Path parent = Objects.requireNonNull(output.getParent()).toRealPath();
            output = parent.resolve(output.getFileName());
            Files.createDirectory(output);
            Path dll = name.equals("awt-alpha-only") ? null : Path.of(Objects.requireNonNull(cli.get("--mpv"), "Missing --mpv")).toRealPath();
            code = new AwtMpvProbe(name, output, dll).run();
        } catch (Throwable error) {
            System.err.println(error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
        System.exit(code); // This direct diagnostic JVM owns all its windows and native handles.
    }

    private int run() throws Exception {
        Thread watchdog = new Thread(() -> {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                Files.writeString(output.resolve("timeout.json"), json(Map.of("case", caseName,
                    "diagnosticOnly", true, "passed", false, "exitCode", 124,
                    "cleanupGraceful", false, "reason", "Own diagnostic JVM exceeded its hard case limit",
                    "lastObservedReport", lastReport)), StandardOpenOption.CREATE_NEW);
            } catch (InterruptedException finished) { return; }
            catch (Throwable ignored) { }
            Runtime.getRuntime().halt(124); // Do not unmount an HWND beneath an unresponsive native worker.
        }, "probe-own-process-deadline");
        watchdog.setDaemon(true); watchdog.start();
        int code = 2;
        boolean nativeClosed = dll == null, windowDisposed = false;
        Throwable original = null;
        try {
            require(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"), "Windows is required");
            require(Native.POINTER_SIZE == 8, "64-bit JNA is required");
            require(!GraphicsEnvironment.isHeadless(), "An interactive physical desktop is required");
            user32 = Native.load("user32", User32.class);
            dwm = Native.load("dwmapi", DwmApi.class);
            result.put("desktop", desktopFacts());
            if (dll != null) {
                require(Files.isRegularFile(dll) && Files.size(dll) == DLL_BYTES && sha256(dll).equals(DLL_SHA), "mpv DLL fixed size/SHA256 mismatch");
                result.put("mpvDll", Map.of("path", dll.toString(), "size", DLL_BYTES, "sha256", DLL_SHA));
            }
            createWindow();
            observe("window-created");
            if (dll == null) {
                waitScreen("solid AWT baseline", "screen-baseline.png", 3_000, image -> brightness(image) > 180.0);
            } else {
                Path video = output.resolve("fixture.avi"), audio = output.resolve("fixture.wav");
                createVideo(video); createAudio(audio);
                result.put("media", Map.of("videoSha256", sha256(video), "audioSha256", sha256(audio),
                    "videoWidth", WIDTH, "videoHeight", HEIGHT, "fps", FPS, "seconds", SECONDS,
                    "audioSampleRate", 48000, "audioChannels", 1, "volume", 0, "muted", true));
                long hwnd = edt(() -> Pointer.nativeValue(Native.getComponentPointer(canvas)) & 0xffffffffL);
                requireOwn(new Pointer(hwnd));
                actor = new MpvActor(dll, hwnd, video, audio, caseName.equals("mpv-bitblt"));
                actor.start();
                waitCondition("native initialization", 7_000, () -> actor.ready.isDone());
                actor.ready.get();
                waitCondition("file-loaded and real clock", 5_000, () -> actor.fileLoaded && actor.number("time-pos") >= 1.1);
                double time = actor.number("time-pos");
                waitCondition("real clock advancement", 1_500, () -> actor.number("time-pos") > time + 0.1);
                waitScreen("native Windows cyan/pink visibility", "screen-baseline.png", 5_000, AwtMpvProbe::hasVideoColors);
                actor.setPause(true);
                waitCondition("native pause readback", 1_500, () -> "yes".equals(actor.value("pause")));
                // Replace the moving-video baseline with a physical capture of the paused frame.
                BufferedImage paused = capture();
                if (!hasVideoColors(paused)) throw new GateFailure("Paused native baseline lost the original visible colors");
                ImageIO.write(paused, "png", output.resolve("screen-paused.png").toFile());
            }
            alphaStages();
            observe("gates-passed");
            code = 0;
        } catch (Throwable error) {
            original = error;
            code = error instanceof GateFailure ? 1 : error instanceof ProbeTimeout ? 124 : 2;
            result.put("failure", Map.of("type", error.getClass().getSimpleName(), "message", safe(error.getMessage())));
            try { observe("failure"); } catch (Throwable diagnostic) { result.put("telemetryError", safe(diagnostic.toString())); }
            try { if (canvas != null) ImageIO.write(capture(), "png", output.resolve("screen-failed.png").toFile()); }
            catch (Throwable diagnostic) { result.put("failureCaptureError", safe(diagnostic.toString())); }
        } finally {
            if (actor != null) {
                result.put("native", actor.snapshot);
                try { actor.close(); nativeClosed = true; }
                catch (Throwable cleanup) { result.put("nativeCleanupError", safe(cleanup.toString())); }
                try { Files.writeString(output.resolve("native-log.txt"), actor.logs(), StandardOpenOption.CREATE_NEW); }
                catch (Throwable cleanup) { result.put("logWriteError", safe(cleanup.toString())); }
                result.put("nativeLifecycle", Map.of("fileLoaded", actor.fileLoaded, "playbackRestartObserved", actor.playbackRestart,
                    "terminated", actor.terminated.get(), "droppedLogLines", actor.droppedLines));
            }
            if (nativeClosed) {
                try {
                    windowDisposed = edt(() -> { if (repaintTimer != null) repaintTimer.stop(); if (overlay != null) overlay.dispose();
                        if (frame != null) frame.dispose();
                        return (overlay == null || !overlay.isDisplayable()) && (frame == null || !frame.isDisplayable()); });
                } catch (Throwable cleanup) { result.put("windowCleanupError", safe(cleanup.toString())); }
            }
            result.put("nativeClosed", nativeClosed); result.put("windowDisposed", windowDisposed);
            boolean graceful = nativeClosed && windowDisposed;
            result.put("cleanupGraceful", graceful);
            if (!graceful && code == 0) code = 2;
            result.put("exitCode", code); result.put("passed", code == 0);
            result.put("endedUtc", Instant.now().toString()); result.put("elapsedMs", elapsedMs());
            publishReport();
            try { Files.writeString(output.resolve("result.json"), lastReport + "\n", StandardOpenOption.CREATE_NEW); }
            catch (Throwable receiptFailure) {
                System.err.println("Receipt write failed: " + safe(receiptFailure.toString()));
                if (original != null) System.err.println("Original failure: " + safe(original.toString()));
                if (code == 0) code = 2;
            } finally { watchdog.interrupt(); }
            if (!nativeClosed) Runtime.getRuntime().halt(code == 0 ? 2 : code);
        }
        if (original != null) System.err.println(original.getClass().getSimpleName() + ": " + safe(original.getMessage()));
        return code;
    }

    private void createWindow() throws Exception {
        edt(() -> {
            frame = new JFrame("BiliPai isolated AWT/mpv diagnosis: " + caseName);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setAlwaysOnTop(true);
            canvas = dll == null ? new Canvas() {
                @Override public void paint(Graphics graphics) { graphics.setColor(new Color(220, 220, 220)); graphics.fillRect(0, 0, getWidth(), getHeight()); }
                @Override public void update(Graphics graphics) { paint(graphics); }
            } : new Canvas();
            canvas.setBackground(dll == null ? new Color(220, 220, 220) : Color.BLACK);
            frame.add(canvas, BorderLayout.CENTER);
            GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            Rectangle usable = new Rectangle(gc.getBounds());
            usable.x += insets.left; usable.y += insets.top; usable.width -= insets.left + insets.right; usable.height -= insets.top + insets.bottom;
            require(usable.width >= 400 && usable.height >= 300, "Own monitor is too small for the diagnostic window");
            frame.setBounds(usable.x + (usable.width - Math.min(720, usable.width)) / 2,
                usable.y + (usable.height - Math.min(440, usable.height)) / 2, Math.min(720, usable.width), Math.min(440, usable.height));
            frame.setVisible(true); frame.toFront(); frame.requestFocus(); canvas.requestFocusInWindow();
            screenBounds = new Rectangle(canvas.getGraphicsConfiguration().getBounds());
            if (dll == null) { repaintTimer = new javax.swing.Timer(50, event -> canvas.repaint()); repaintTimer.start(); }
            return null;
        });
    }

    private void alphaStages() throws Exception {
        BufferedImage baseline = capture();
        double original = brightness(baseline);
        result.put("pausedOrSolidBaseline", imageStats(baseline));
        require(original > 5.0, "A black baseline cannot prove alpha composition");
        edt(() -> {
            require(canvas.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT), "Per-pixel AWT translucency is unsupported");
            overlay = new JWindow(frame);
            overlay.setFocusableWindowState(false); overlay.setAutoRequestFocus(false); overlay.setAlwaysOnTop(true);
            overlay.setBackground(new Color(0, 0, 0, 0));
            tint = new Tint(); overlay.setContentPane(tint);
            overlay.setBounds(viewport()); overlay.setVisible(true);
            Pointer hwnd = Native.getComponentPointer(overlay); requireOwn(hwnd);
            int style = user32.GetWindowLongW(hwnd, -20);
            int desired = style | 0x00080000 | 0x00000020 | 0x08000000; // LAYERED, TRANSPARENT, NOACTIVATE
            Native.setLastError(0); int previous = user32.SetWindowLongW(hwnd, -20, desired);
            require(previous != 0 || Native.getLastError() == 0, "Cannot set own overlay native styles");
            require((user32.GetWindowLongW(hwnd, -20) & desired) == desired, "Own overlay styles did not read back");
            tint.alpha = 0.6f; tint.repaint(); overlay.toFront();
            return null;
        });
        BufferedImage dim = waitScreen("AWT per-pixel dim", "screen-dim.png", 3_000, image -> brightness(image) < original * 0.6);
        if (!(brightness(dim) > original * 0.25)) throw new GateFailure("Alpha dim replaced the visible image with an opaque window");
        observe("alpha-dim");
        edt(() -> { tint.alpha = 0f; tint.repaint(); return null; });
        BufferedImage restored = waitScreen("AWT alpha clear restores screen", "screen-restored.png", 3_000,
            image -> Math.abs(brightness(image) - original) < 5.0);
        result.put("alpha", Map.of("baselineBrightness", original, "dimBrightness", brightness(dim),
            "dimRatio", brightness(dim) / original, "restoredBrightness", brightness(restored),
            "restoreDelta", Math.abs(brightness(restored) - original), "requestedOpacity", 0.6));
        observe("alpha-restored");
    }

    private static final class Tint extends JComponent {
        private float alpha;
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setComposite(AlphaComposite.Src); g.setColor(new Color(0, 0, 0, 0)); g.fillRect(0, 0, getWidth(), getHeight());
                g.setComposite(AlphaComposite.SrcOver); g.setColor(new Color(0f, 0f, 0f, alpha)); g.fillRect(0, 0, getWidth(), getHeight());
            } finally { g.dispose(); }
        }
    }

    private BufferedImage waitScreen(String stage, String file, long budgetMs, java.util.function.Predicate<BufferedImage> predicate) throws Exception {
        long end = stageDeadline(budgetMs);
        BufferedImage image = null;
        do {
            checkDeadline();
            image = capture();
            if (predicate.test(image)) {
                ImageIO.write(image, "png", output.resolve(file).toFile());
                result.put(stage, imageStats(image)); observe(stage); return image;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        if (image != null) ImageIO.write(image, "png", output.resolve(file).toFile());
        if (image != null) result.put(stage, imageStats(image));
        throw new GateFailure("Physical screen gate failed: " + stage + " " + (image == null ? "no capture" : json(imageStats(image))));
    }

    private BufferedImage capture() throws Exception {
        Rectangle bounds = edt(() -> {
            require(frame.isShowing() && canvas.isShowing() && canvas.getWidth() > 0 && canvas.getHeight() > 0, "Own Canvas is not showing");
            frame.toFront(); frame.requestFocus();
            Rectangle rect = viewport();
            require(screenBounds.contains(rect), "Own Canvas extends outside its physical monitor capture region");
            return rect;
        });
        Toolkit.getDefaultToolkit().sync();
        return new Robot(edt(() -> canvas.getGraphicsConfiguration().getDevice())).createScreenCapture(bounds);
    }

    private Rectangle viewport() {
        Point point = canvas.getLocationOnScreen();
        return new Rectangle(point.x, point.y, canvas.getWidth(), canvas.getHeight());
    }

    private void waitCondition(String operation, long budgetMs, Callable<Boolean> ready) throws Exception {
        long end = stageDeadline(budgetMs);
        do {
            checkDeadline();
            if (actor != null && actor.failure != null) throw new IllegalStateException("Native actor failed", actor.failure);
            if (ready.call()) return;
            Thread.sleep(40);
        } while (System.nanoTime() < end);
        throw new GateFailure("Timed out observing " + operation);
    }

    private long stageDeadline(long budgetMs) { return Math.min(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs), deadline - TimeUnit.SECONDS.toNanos(3)); }
    private void checkDeadline() { if (System.nanoTime() >= deadline - TimeUnit.SECONDS.toNanos(3)) throw new ProbeTimeout(); }
    private long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private void publishReport() { lastReport = json(result); }

    private void observe(String stage) throws Exception {
        Map<String, Object> observation = edt(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", stage); data.put("elapsedMs", elapsedMs());
            data.put("frameShowing", frame.isShowing()); data.put("frameFocused", frame.isFocused());
            data.put("canvasShowing", canvas.isShowing()); data.put("canvasDisplayable", canvas.isDisplayable());
            data.put("overlayRequestedAlpha", tint == null ? 0f : tint.alpha);
            data.put("frameBounds", rect(frame.getBounds()));
            if (canvas.isShowing()) { Rectangle r = viewport(); data.put("canvasScreenBounds", rect(r)); data.put("screenIntersection", rect(r.intersection(screenBounds))); }
            GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
            data.put("graphicsConfiguration", gc.getClass().getName()); data.put("monitorBounds", rect(gc.getBounds()));
            data.put("defaultTransform", gc.getDefaultTransform().toString());
            Pointer root = Native.getComponentPointer(frame), child = Native.getComponentPointer(canvas);
            data.put("frameNative", windowFacts(root)); data.put("canvasNative", windowFacts(child));
            if (overlay != null && overlay.isDisplayable()) data.put("overlayNative", windowFacts(Native.getComponentPointer(overlay)));
            if (actor != null) {
                data.put("native", actor.snapshot);
                String rawChild = actor.value("window-id");
                if (rawChild != null) {
                    try {
                        long id = rawChild.startsWith("0x") ? Long.parseUnsignedLong(rawChild.substring(2), 16) : Long.parseUnsignedLong(rawChild);
                        if (id != 0) data.put("mpvChildNative", windowFacts(new Pointer(id)));
                    } catch (RuntimeException invalid) { data.put("mpvChildObservationError", safe(invalid.getMessage())); }
                }
            }
            return data;
        });
        observations.add(observation); publishReport();
    }

    private Map<String, Object> windowFacts(Pointer hwnd) {
        requireOwn(hwnd);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hwnd", Long.toUnsignedString(Pointer.nativeValue(hwnd)));
        data.put("ownPidVerified", true); data.put("visible", user32.IsWindowVisible(hwnd));
        Pointer parent = user32.GetParent(hwnd);
        data.put("parentHwnd", parent == null ? "0" : Long.toUnsignedString(Pointer.nativeValue(parent)));
        data.put("style", Integer.toUnsignedString(user32.GetWindowLongW(hwnd, -16)));
        data.put("extendedStyle", Integer.toUnsignedString(user32.GetWindowLongW(hwnd, -20)));
        Rect rect = new Rect();
        if (user32.GetWindowRect(hwnd, rect)) data.put("windowScreenRect", nativeRect(rect));
        Rect client = new Rect();
        if (user32.GetClientRect(hwnd, client)) {
            PointNative origin = new PointNative();
            if (user32.ClientToScreen(hwnd, origin)) data.put("clientScreenRect", Map.of("x", origin.x, "y", origin.y,
                "width", client.right - client.left, "height", client.bottom - client.top));
        }
        IntByReference cloak = new IntByReference();
        int code = dwm.DwmGetWindowAttribute(hwnd, 14, cloak, 4);
        data.put("cloakedQueryHresult", code); if (code == 0) data.put("cloaked", cloak.getValue());
        data.put("isForeground", Objects.equals(user32.GetForegroundWindow(), hwnd));
        return data;
    }

    private void requireOwn(Pointer hwnd) {
        IntByReference pid = new IntByReference();
        require(hwnd != null && user32.GetWindowThreadProcessId(hwnd, pid) != 0 && Integer.toUnsignedLong(pid.getValue()) == PID,
            "Refusing telemetry or mutation of an HWND outside this diagnostic JVM");
    }

    private Map<String, Object> desktopFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        Kernel32 kernel = Native.load("kernel32", Kernel32.class);
        IntByReference session = new IntByReference();
        boolean found = kernel.ProcessIdToSessionId((int) PID, session);
        facts.put("ownSessionIdQuerySucceeded", found); facts.put("sessionQueryLastError", Native.getLastError());
        if (found) {
            facts.put("ownSessionId", session.getValue());
            try {
                WtsApi wts = Native.load("wtsapi32", WtsApi.class);
                for (int kind : new int[]{8, 16}) {
                    PointerByReference buffer = new PointerByReference(); IntByReference bytes = new IntByReference();
                    boolean ok = wts.WTSQuerySessionInformationW(null, session.getValue(), kind, buffer, bytes);
                    facts.put(kind == 8 ? "wtsStateQuerySucceeded" : "wtsProtocolQuerySucceeded", ok);
                    Pointer ptr = buffer.getValue();
                    try {
                        if (ok && ptr != null && bytes.getValue() >= (kind == 8 ? 4 : 2))
                            facts.put(kind == 8 ? "wtsConnectionState" : "wtsProtocolType", kind == 8 ? ptr.getInt(0) : Short.toUnsignedInt(ptr.getShort(0)));
                    } finally { if (ptr != null) wts.WTSFreeMemory(ptr); }
                }
            } catch (Throwable unavailable) { facts.put("wtsError", safe(unavailable.toString())); }
        }
        Pointer ownDesktop = user32.GetThreadDesktop(kernel.GetCurrentThreadId());
        Native.setLastError(0); Pointer inputDesktop = user32.OpenInputDesktop(0, false, 1);
        facts.put("ownThreadDesktopAvailable", ownDesktop != null); facts.put("inputDesktopOpened", inputDesktop != null);
        facts.put("inputDesktopLastError", Native.getLastError());
        // Different handle values may still name the same desktop; do not infer desktop identity from this comparison.
        facts.put("desktopHandlesEqualOnly", ownDesktop != null && ownDesktop.equals(inputDesktop));
        if (inputDesktop != null) user32.CloseDesktop(inputDesktop);
        IntByReference composition = new IntByReference(); int code = dwm.DwmIsCompositionEnabled(composition);
        facts.put("dwmQueryHresult", code); if (code == 0) facts.put("dwmCompositionEnabled", composition.getValue() != 0);
        return facts;
    }

    private static Map<String, Object> rect(Rectangle value) { return Map.of("x", value.x, "y", value.y, "width", value.width, "height", value.height); }
    private static Map<String, Object> nativeRect(Rect value) { return Map.of("left", value.left, "top", value.top, "right", value.right, "bottom", value.bottom); }
    private static Map<String, Object> imageStats(BufferedImage image) {
        int cyan = 0, pink = 0; long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            if (b > 180 && g > 135 && r < 135) cyan++;
            if (r > 180 && g < 145 && b >= 100 && b <= 200) pink++;
            sum += r + g + b;
        }
        return Map.of("width", image.getWidth(), "height", image.getHeight(), "cyanPixels", cyan, "pinkPixels", pink,
            "brightness", sum / (image.getWidth() * (double) image.getHeight() * 3.0), "physicalScreenCapture", true);
    }
    private static double brightness(BufferedImage image) { return ((Number) imageStats(image).get("brightness")).doubleValue(); }
    private static boolean hasVideoColors(BufferedImage image) {
        Map<String, Object> stats = imageStats(image); return (int) stats.get("cyanPixels") >= 100 && (int) stats.get("pinkPixels") >= 100;
    }

    private <T> T edt(Callable<T> action) throws Exception {
        if (EventQueue.isDispatchThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action); EventQueue.invokeLater(task);
        return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private static final class MpvActor implements AutoCloseable {
        private static final String[] PROPERTIES = {"mpv-version", "current-vo", "current-gpu-context", "vo-configured", "time-pos", "pause",
            "video-codec", "hwdec-current", "video-dec-params/w", "video-dec-params/h", "video-out-params/w", "video-out-params/h", "window-id",
            "options/d3d11-flip", "options/d3d11-warp", "options/d3d11-output-mode", "options/d3d11-sync-interval", "options/d3d11-output-format",
            "options/d3d11-output-csp", "frame-drop-count", "volume", "mute", "current-ao", "audio-codec"};
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicBoolean stopping = new AtomicBoolean(), terminated = new AtomicBoolean();
        final BlockingQueue<String> pauseCommands = new LinkedBlockingQueue<>();
        volatile Map<String, Object> snapshot = Map.of();
        volatile Throwable failure;
        volatile boolean fileLoaded, playbackRestart;
        volatile int droppedLines;
        private final List<String> firstLogs = new ArrayList<>();
        private final Deque<String> recentLogs = new ArrayDeque<>();
        private final Path dll, video, audio;
        private final long hwnd;
        private final boolean bitblt;
        private final Thread worker;
        MpvActor(Path dll, long hwnd, Path video, Path audio, boolean bitblt) {
            this.dll = dll; this.hwnd = hwnd; this.video = video; this.audio = audio; this.bitblt = bitblt;
            worker = new Thread(this::run, "probe-single-mpv-actor"); worker.setDaemon(true);
        }
        void start() { worker.start(); }
        String value(String key) { Object row = snapshot.get(key); return row instanceof Map<?, ?> map ? (String) map.get("value") : null; }
        double number(String key) { try { return Double.parseDouble(value(key)); } catch (RuntimeException invalid) { return -1; } }
        void setPause(boolean value) { pauseCommands.offer(value ? "yes" : "no"); }
        private void run() {
            Mpv api = null; Pointer handle = null;
            try {
                api = Native.load(dll.toString(), Mpv.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                handle = api.mpv_create(); require(handle != null, "mpv_create returned null");
                Map<String, String> options = new LinkedHashMap<>();
                options.put("config", "no"); options.put("load-scripts", "no"); options.put("ytdl", "no");
                options.put("terminal", "no"); options.put("osc", "no"); options.put("osd-level", "0");
                options.put("input-default-bindings", "no"); options.put("input-vo-keyboard", "no"); options.put("input-cursor", "no");
                options.put("idle", "yes"); options.put("keep-open", "no"); options.put("wid", Long.toUnsignedString(hwnd));
                options.put("vo", "gpu"); options.put("gpu-api", "d3d11"); options.put("hwdec", "auto-safe");
                options.put("ao", "null"); options.put("ao-null-untimed", "no"); options.put("volume", "0"); options.put("mute", "yes");
                options.put("audio-files", audio.toString().replace(";", "\\;"));
                if (bitblt) options.put("d3d11-flip", "no"); // Default case does not override this option.
                for (Map.Entry<String, String> option : options.entrySet()) check(api, api.mpv_set_option_string(handle, option.getKey(), option.getValue()), option.getKey());
                check(api, api.mpv_request_log_messages(handle, "v"), "request-log-messages");
                check(api, api.mpv_initialize(handle), "initialize");
                check(api, api.mpv_command(handle, new StringArray(new String[]{"loadfile", video.toString(), "replace"}, "UTF-8")), "loadfile");
                ready.complete(null);
                long last = 0;
                while (!stopping.get()) {
                    String pause;
                    while ((pause = pauseCommands.poll()) != null) check(api, api.mpv_set_property_string(handle, "pause", pause), "pause");
                    Pointer event = api.mpv_wait_event(handle, 0.025);
                    int id = event.getInt(0);
                    if (id == 1) throw new IllegalStateException("Unexpected mpv shutdown");
                    if (id == 8) fileLoaded = true;
                    if (id == 21) playbackRestart = true;
                    if (id == 7) {
                        fileLoaded = false; Pointer data = event.getPointer(16);
                        if (data != null && data.getInt(0) == 4) throw new IllegalStateException("END_FILE native error " + data.getInt(4));
                    }
                    if (id == 2) {
                        Pointer data = event.getPointer(16);
                        if (data != null) {
                            String prefix = nativeText(data.getPointer(0), 96);
                            if (prefix.startsWith("vo") || prefix.startsWith("vd") || data.getInt(24) <= 30)
                                log(prefix + " [" + nativeText(data.getPointer(8), 32) + "] " + nativeText(data.getPointer(16), 1024));
                        }
                    }
                    if (System.nanoTime() - last >= 150_000_000L) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (String property : PROPERTIES) {
                            try (Memory memory = new Memory(Native.POINTER_SIZE)) {
                                memory.clear(); int code = api.mpv_get_property(handle, property, 1, memory);
                                Pointer text = code >= 0 ? memory.getPointer(0) : null;
                                Map<String, Object> entry = new LinkedHashMap<>(); entry.put("code", code);
                                try { if (text != null) entry.put("value", nativeText(text, 512)); }
                                finally { if (text != null) api.mpv_free(text); }
                                values.put(property, Collections.unmodifiableMap(entry));
                            }
                        }
                        snapshot = Collections.unmodifiableMap(values); last = System.nanoTime();
                    }
                }
            } catch (Throwable error) { failure = error; ready.completeExceptionally(error); log("ACTOR_FAILURE " + safe(error.toString())); }
            finally {
                if (api != null && handle != null) {
                    try { api.mpv_terminate_destroy(handle); terminated.set(true); }
                    catch (Throwable error) { failure = error; log("DESTROY_FAILURE " + safe(error.toString())); }
                } else terminated.set(true);
            }
        }
        private synchronized void log(String value) {
            String row = safe(value); if (firstLogs.size() < 150) firstLogs.add(row);
            else { if (recentLogs.size() == 150) { recentLogs.removeFirst(); droppedLines++; } recentLogs.addLast(row); }
        }
        synchronized String logs() { return String.join("\n", firstLogs) + "\n--- later bounded log ---\n" + String.join("\n", recentLogs) + "\n"; }
        @Override public void close() throws Exception {
            stopping.set(true); worker.join(2_000);
            require(!worker.isAlive() && terminated.get(), "Exact mpv actor did not terminate; owned HWND remains mounted");
        }
        private static void check(Mpv api, int code, String operation) {
            if (code < 0) throw new IllegalStateException("mpv " + operation + " returned " + code + ": " + api.mpv_error_string(code));
        }
    }

    private static String nativeText(Pointer value, int maximum) {
        if (value == null) return "";
        int length = 0; while (length < maximum && value.getByte(length) != 0) length++;
        return new String(value.getByteArray(0, length), StandardCharsets.UTF_8);
    }
    public interface Mpv extends Library {
        Pointer mpv_create(); int mpv_initialize(Pointer handle); void mpv_terminate_destroy(Pointer handle);
        int mpv_set_option_string(Pointer handle, String name, String value);
        int mpv_set_property_string(Pointer handle, String name, String value);
        int mpv_get_property(Pointer handle, String name, int format, Pointer data);
        int mpv_command(Pointer handle, StringArray args); int mpv_request_log_messages(Pointer handle, String level);
        Pointer mpv_wait_event(Pointer handle, double timeout); String mpv_error_string(int error); void mpv_free(Pointer data);
    }
    public interface User32 extends StdCallLibrary {
        int GetWindowThreadProcessId(Pointer hwnd, IntByReference pid); boolean IsWindowVisible(Pointer hwnd);
        Pointer GetParent(Pointer hwnd); Pointer GetForegroundWindow();
        int GetWindowLongW(Pointer hwnd, int index); int SetWindowLongW(Pointer hwnd, int index, int value);
        boolean GetWindowRect(Pointer hwnd, Rect rect); boolean GetClientRect(Pointer hwnd, Rect rect); boolean ClientToScreen(Pointer hwnd, PointNative point);
        Pointer GetThreadDesktop(int thread); Pointer OpenInputDesktop(int flags, boolean inherit, int access); boolean CloseDesktop(Pointer desktop);
    }
    public interface DwmApi extends StdCallLibrary {
        int DwmIsCompositionEnabled(IntByReference enabled); int DwmGetWindowAttribute(Pointer hwnd, int attribute, IntByReference value, int size);
    }
    public interface Kernel32 extends StdCallLibrary { boolean ProcessIdToSessionId(int processId, IntByReference sessionId); int GetCurrentThreadId(); }
    public interface WtsApi extends StdCallLibrary {
        boolean WTSQuerySessionInformationW(Pointer server, int sessionId, int kind, PointerByReference buffer, IntByReference bytes);
        void WTSFreeMemory(Pointer memory);
    }
    @Structure.FieldOrder({"left", "top", "right", "bottom"}) public static class Rect extends Structure { public int left, top, right, bottom; }
    @Structure.FieldOrder({"x", "y"}) public static class PointNative extends Structure { public int x, y; }

    /** Direct port of PlayerSelfTest's local MJPG AVI and PCM WAV writers. */
    private static void createVideo(Path file) throws Exception {
        List<byte[]> frames = new ArrayList<>();
        for (int index = 0; index < FPS * SECONDS; index++) {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(new Color(24, 27, 38)); g.fillRect(0, 0, WIDTH, HEIGHT);
                g.setColor(new Color(250, 106, 151)); g.fillRect(0, HEIGHT - 24, WIDTH * index / (FPS * SECONDS), 24);
                g.setColor(new Color(82, 191, 248)); g.fillOval(10 + index % 260, 45, 44, 44);
                g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 16)); g.drawString("BiliPai · Native Windows", 18, 30);
                g.setFont(new Font("Monospaced", Font.PLAIN, 15)); g.drawString("DASH test: " + index / FPS + "." + index % FPS * 5 + "s", 18, 135);
            } finally { g.dispose(); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); require(ImageIO.write(image, "jpg", bytes), "JPEG writer unavailable"); frames.add(bytes.toByteArray());
        }
        byte[] avih = leInts(1_000_000 / FPS, 0, 0, 0x10, frames.size(), 0, 1, 64 * 1024, WIDTH, HEIGHT, 0, 0, 0, 0);
        byte[] strh = concat(ascii("vidsMJPG"), leInts(0, 0, 0, 1, FPS, 0, frames.size(), 64 * 1024, -1, 0),
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0).putShort((short) 0).putShort((short) WIDTH).putShort((short) HEIGHT).array());
        byte[] strf = concat(leInts(40, WIDTH, HEIGHT), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1).putShort((short) 24).array(),
            ascii("MJPG"), leInts(WIDTH * HEIGHT * 3, 0, 0, 0, 0));
        byte[] hdrl = listChunk("hdrl", concat(chunk("avih", avih), listChunk("strl", concat(chunk("strh", strh), chunk("strf", strf)))));
        ByteArrayOutputStream movie = new ByteArrayOutputStream(), index = new ByteArrayOutputStream();
        for (byte[] frame : frames) { index.write(ascii("00dc")); index.write(leInts(0x10, movie.size() + 4, frame.length)); movie.write(chunk("00dc", frame)); }
        byte[] body = concat(ascii("AVI "), hdrl, listChunk("movi", movie.toByteArray()), chunk("idx1", index.toByteArray()));
        Files.write(file, concat(ascii("RIFF"), leInts(body.length), body), StandardOpenOption.CREATE_NEW);
    }
    private static void createAudio(Path file) throws Exception {
        int sampleRate = 48000; ByteBuffer samples = ByteBuffer.allocate(sampleRate * SECONDS * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleRate * SECONDS; i++) samples.putShort((short) (Math.sin(i * 2.0 * Math.PI * 440.0 / sampleRate) * 1500));
        byte[] format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1).putShort((short) 1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16).array();
        byte[] body = concat(ascii("WAVE"), chunk("fmt ", format), chunk("data", samples.array()));
        Files.write(file, concat(ascii("RIFF"), leInts(body.length), body), StandardOpenOption.CREATE_NEW);
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] leInts(int... values) { ByteBuffer out = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (int value : values) out.putInt(value); return out.array(); }
    private static byte[] concat(byte[]... values) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] value : values) out.writeBytes(value); return out.toByteArray(); }
    private static byte[] chunk(String name, byte[] value) { return concat(ascii(name), leInts(value.length), value, value.length % 2 == 0 ? new byte[0] : new byte[]{0}); }
    private static byte[] listChunk(String name, byte[] value) { return chunk("LIST", concat(ascii(name), value)); }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path)) { byte[] bytes = new byte[1024 * 1024]; int count; while ((count = stream.read(bytes)) >= 0) if (count > 0) digest.update(bytes, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String safe(String value) { if (value == null) return ""; return value.substring(0, Math.min(value.length(), 2048)).replace('\u0000', '?'); }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? number.toString() : json(number.toString());
        if (value instanceof Map<?, ?> map) { StringJoiner join = new StringJoiner(",", "{", "}"); map.forEach((key, child) -> join.add(json(key.toString()) + ":" + json(child))); return join.toString(); }
        if (value instanceof Iterable<?> list) { StringJoiner join = new StringJoiner(",", "[", "]"); for (Object child : list) join.add(json(child)); return join.toString(); }
        String text = value.toString(); StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); switch (c) {
            case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\""); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
        } }
        return out.append('"').toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static final class GateFailure extends RuntimeException { GateFailure(String message) { super(message); } }
    private static final class ProbeTimeout extends RuntimeException { ProbeTimeout() { super("Case observation budget exhausted before reserved cleanup time"); } }
}
