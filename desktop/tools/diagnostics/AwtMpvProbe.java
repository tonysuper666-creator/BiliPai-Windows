import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.bilipai.desktop.player.DesktopWindowsDxgiAdapters;
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
    private static final List<String> FAST = List.of("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_S.glsl", "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_S.glsl");
    private static final List<String> QUALITY = List.of("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl");
    private static final Map<String, String> SHADER_PINS = Map.ofEntries(
        Map.entry("Anime4K_AutoDownscalePre_x2.glsl", "8c58291740146bd766a4d73f132775a797fe80f7d07919b5d767e27a5dc85656"),
        Map.entry("Anime4K_AutoDownscalePre_x4.glsl", "5af62d8cd844916dc1126613e13bad3beab195787f93a71200b47c6ec78f2e41"),
        Map.entry("Anime4K_Clamp_Highlights.glsl", "6dafe6d4ccaed8f1675d1b5b13e2d1a981f1f65849f54ea71b897f2f439ecfed"),
        Map.entry("Anime4K_Restore_CNN_M.glsl", "67ea3ed26539e8de3b7d307688535d2ff17e8d147e11dda0247da7770dbecf41"),
        Map.entry("Anime4K_Restore_CNN_S.glsl", "97c24dc370ab300c108bfaa09db7f175aeff343674842c299cf3940a3d330427"),
        Map.entry("Anime4K_Restore_CNN_VL.glsl", "35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e"),
        Map.entry("Anime4K_Upscale_CNN_x2_M.glsl", "716e02098a68f0d648761f2b96b4dd139e1cb09b174bb369fca3aa34328fff7e"),
        Map.entry("Anime4K_Upscale_CNN_x2_S.glsl", "4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4"),
        Map.entry("Anime4K_Upscale_CNN_x2_VL.glsl", "5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d"));
    private static final long PID = ProcessHandle.current().pid();
    private final String caseName;
    private final Path output;
    private final Path dll;
    private final Path shaderRoot;
    private final long started = System.nanoTime(), deadline;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final List<Object> observations = new ArrayList<>();
    private final List<Object> captureTimeline = new ArrayList<>();
    private int droppedCaptures;
    private boolean surfacePhysicalFailure;
    private final boolean debugObservations;
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

    private AwtMpvProbe(String caseName, Path output, Path dll, Path shaderRoot, boolean debugObservations) {
        this.caseName = caseName; this.output = output; this.dll = dll; this.shaderRoot = shaderRoot;
        this.debugObservations = debugObservations;
        deadline = started + TimeUnit.SECONDS.toNanos(shaderCase() ? 90 : caseName.equals("awt-alpha-only") ? 25 : 30);
        result.put("schema", 1); result.put("case", caseName); result.put("diagnosticOnly", true);
        result.put("passed", false); result.put("beganUtc", Instant.now().toString());
        result.put("ownPid", PID); result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("screenGate", Map.of("cyan", "B>180,G>135,R<135,count>=100",
            "pink", "R>180,G<145,B=100..200,count>=100"));
        result.put("alphaGate", "0.25 < dim/baseline < 0.6; abs(restored-baseline) < 5");
        if (dll != null && !shaderCase()) result.put("fixtureBackgroundGate", Map.of(
            "sourceBands", "(4,98,312,12);(4,143,312,8)", "rgbTolerance", 18,
            "minimumFill", 0.98, "minimumRowFill", 0.95, "minimumColumnFill", 0.95,
            "viewport", "actual same-actor OSD dimensions and crop/pan margins; no aspect inference"));
        result.put("observations", observations);
        result.put("captureTimeline", captureTimeline);
        result.put("captureTimelineDropped", 0);
        publishReport();
    }

    public static void main(String[] args) {
        int code = 2;
        try {
            Map<String, String> cli = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length || !Set.of("--case", "--output", "--mpv", "--shader-root", "--surface-debug-observations").contains(args[i]) ||
                    cli.put(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Invalid or duplicate CLI argument");
            }
            String name = cli.get("--case");
            if (!Set.of("awt-alpha-only", "mpv-default-flip", "mpv-default-debug", "mpv-bitblt", "mpv-adaptive",
                "shader-clear-default-retained", "shader-clear-default-seek", "shader-clear-nodumb-retained", "shader-clear-nodumb-seek").contains(name))
                throw new IllegalArgumentException("--case must select one diagnostic case");
            String debugFlag = cli.getOrDefault("--surface-debug-observations", "false");
            if (!Set.of("true", "false").contains(debugFlag)) throw new IllegalArgumentException("Invalid debug observation flag");
            boolean debugObservations = debugFlag.equals("true");
            if (debugObservations && !Set.of("mpv-default-flip", "mpv-bitblt", "mpv-default-debug").contains(name))
                throw new IllegalArgumentException("Debug observations require the explicit surface-debug cases");
            if (name.equals("mpv-default-debug") && !debugObservations) throw new IllegalArgumentException("Debug case requires explicit observations");
            Path output = Path.of(Objects.requireNonNull(cli.get("--output"), "Missing --output")).toAbsolutePath().normalize();
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("--output must not exist");
            Path parent = Objects.requireNonNull(output.getParent()).toRealPath();
            output = parent.resolve(output.getFileName());
            Files.createDirectory(output);
            Path dll = name.equals("awt-alpha-only") ? null : Path.of(Objects.requireNonNull(cli.get("--mpv"), "Missing --mpv")).toRealPath();
            Path shaders = name.startsWith("shader-clear-") ? Path.of(Objects.requireNonNull(cli.get("--shader-root"), "Missing --shader-root")).toRealPath() : null;
            code = new AwtMpvProbe(name, output, dll, shaders, debugObservations).run();
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
                actor = new MpvActor(dll, hwnd, video, audio, caseName, debugObservations);
                actor.start();
                waitCondition("native initialization", 7_000, () -> actor.ready.isDone());
                actor.ready.get();
                if (caseName.equals("mpv-adaptive")) {
                    waitCondition("actual adaptive presentation option", 1_500, () ->
                        actor.expectedFlip.equals(actor.value("options/d3d11-flip")));
                }
                waitCondition("file-loaded and real clock", 5_000, () -> actor.fileLoaded && actor.number("time-pos") >= 1.1);
                double time = actor.number("time-pos");
                waitCondition("real clock advancement", 1_500, () -> actor.number("time-pos") > time + 0.1);
                if (shaderCase()) waitScreen("native Windows cyan/pink visibility", "screen-baseline.png", 5_000, AwtMpvProbe::hasVideoColors);
                else waitFixtureScreen("native Windows cyan/pink visibility", "screen-baseline.png", 5_000);
                actor.setPause(true);
                waitCondition("native pause readback", 1_500, () -> "yes".equals(actor.value("pause")));
                // Replace the moving-video baseline with a physical capture of the paused frame.
                BufferedImage paused = capture("paused-native-baseline");
                if (!hasVideoColors(paused)) {
                    surfacePhysicalFailure = !shaderCase();
                    throw new GateFailure("Paused native baseline lost the original visible colors");
                }
                if (!shaderCase()) checkSurfaceFixture(paused, "paused-native-baseline");
                ImageIO.write(paused, "png", output.resolve("screen-paused.png").toFile());
            }
            if (shaderCase()) shaderStages(); else alphaStages();
            observe("gates-passed");
            code = 0;
        } catch (Throwable error) {
            original = error;
            code = error instanceof GateFailure ? 1 : error instanceof ProbeTimeout ? 124 : 2;
            result.put("failure", Map.of("type", error.getClass().getSimpleName(), "message", safe(error.getMessage())));
            try { observe("failure"); } catch (Throwable diagnostic) { result.put("telemetryError", safe(diagnostic.toString())); }
            try { if (canvas != null) ImageIO.write(capture("failure"), "png", output.resolve("screen-failed.png").toFile()); }
            catch (Throwable diagnostic) { result.put("failureCaptureError", safe(diagnostic.toString())); }
            if (surfacePhysicalFailure && actor != null && !shaderCase()) {
                try { observeFailedSurfaceVideo(); }
                catch (Throwable diagnostic) { result.put("failureVideoAuxiliaryError", safe(diagnostic.toString())); }
                try { observeFailedSurfaceWindow(); }
                catch (Throwable diagnostic) { result.put("failureAuxiliaryError", safe(diagnostic.toString())); }
            }
        } finally {
            if (actor != null) {
                result.put("native", actor.snapshot);
                result.put("presentationSelection", actor.presentationSelection);
                try { actor.close(); nativeClosed = true; }
                catch (Throwable cleanup) { result.put("nativeCleanupError", safe(cleanup.toString())); }
                try { Files.writeString(output.resolve("native-log.txt"), actor.logs(), StandardOpenOption.CREATE_NEW); }
                catch (Throwable cleanup) { result.put("logWriteError", safe(cleanup.toString())); }
                if (debugObservations) {
                    result.put("criticalNativeLogs", actor.criticalLogFacts());
                    result.put("requestedStartupOptions", actor.requestedStartupOptions);
                    try { Files.writeString(output.resolve("native-key-log.txt"), actor.criticalLogText(), StandardOpenOption.CREATE_NEW); }
                    catch (Throwable diagnostic) { result.put("criticalLogWriteError", safe(diagnostic.toString())); }
                    result.put("loadedRuntimeModules", actor.runtimeModules);
                    result.put("gpuDebugObservation", actor.debugObservation());
                }
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

    private boolean shaderCase() { return caseName.startsWith("shader-clear-"); }

    private List<String> shaderPaths(List<String> names) throws Exception {
        List<String> paths = new ArrayList<>();
        for (String name : names) {
            Path file = shaderRoot.resolve(name);
            require(!Files.isSymbolicLink(file) && file.toRealPath().getParent().equals(shaderRoot) &&
                Files.size(file) <= 1_048_576 && sha256(file).equals(SHADER_PINS.get(name)), "Fixed original shader bytes differ: " + name);
            String path = file.toRealPath().toString();
            require(path.indexOf('\0') < 0 && path.indexOf('\n') < 0 && path.indexOf('\r') < 0, "Invalid shader path");
            // Match nativeVideoShaderPath: pinned mpv does not add Win32's extended prefix.
            paths.add(path.startsWith("\\\\?\\") ? path : path.startsWith("\\\\") ? "\\\\?\\UNC\\" + path.substring(2) : "\\\\?\\" + path);
        }
        return List.copyOf(paths);
    }

    private Set<String> descriptions(List<String> names) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) for (String line : Files.readAllLines(shaderRoot.resolve(name), StandardCharsets.UTF_8)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("//!DESC ")) result.add(stripped.substring(8).strip());
        }
        require(!result.isEmpty(), "Original shader has no render pass descriptions");
        return result;
    }

    private void shaderPlaybackGuard(String entry, double position) {
        require(actor.failure == null && actor.fileLoaded && "yes".equals(actor.value("pause")) &&
            Objects.equals(entry, actor.value("playlist/0/id")), "Shader stage changed loaded source identity or pause state");
        require(Math.abs(position - actor.number("time-pos")) < 0.15, "Shader stage changed paused position by >=0.15 seconds");
    }

    private void shaderStages() throws Exception {
        // A tracked local setup seek proves the initial paused frame is not a pending prior render.
        int restart = actor.restartCount;
        actor.command("seek", "2.0", "absolute+exact").get(3, TimeUnit.SECONDS);
        waitCondition("paused 2.0 setup seek PLAYBACK_RESTART and readback", 8_000, () -> actor.restartCount > restart &&
            "yes".equals(actor.value("pause")) && "no".equals(actor.value("seeking")) && Math.abs(actor.number("time-pos") - 2.0) < 0.15);
        String entry = actor.value("playlist/0/id");
        require(entry != null && "1".equals(actor.value("playlist-count")), "Missing exact one-file native playlist identity");
        double position = actor.number("time-pos");
        List<String> fast = shaderPaths(FAST), quality = shaderPaths(QUALITY);
        Set<String> fastDescriptions = descriptions(FAST), qualityDescriptions = descriptions(QUALITY);
        Set<String> fastExclusive = new LinkedHashSet<>(fastDescriptions); fastExclusive.removeAll(qualityDescriptions);
        Set<String> qualityExclusive = new LinkedHashSet<>(qualityDescriptions); qualityExclusive.removeAll(fastDescriptions);
        require(!fastExclusive.isEmpty() && !qualityExclusive.isEmpty(), "Original preset-exclusive passes missing");
        actor.shaders(List.of(), false).get(3, TimeUnit.SECONDS);
        waitCondition("initial empty NODE readback", 2_000, () -> actor.shaderFiles != null && actor.shaderFiles.isEmpty());
        Thread.sleep(200);
        BufferedImage baseline = waitScreen("shader-before", "shader-before.png", 5_000, AwtMpvProbe::hasVideoColors);
        Thread.sleep(150);
        double noise = difference(baseline, capture("shader-baseline-noise")).meanDelta();
        result.put("shaderExperiment", Map.ofEntries(Map.entry("gpuDumbModeRequested", caseName.contains("-nodumb-") ? "no" : "pinned default"),
            Map.entry("clearExtraSeek", caseName.endsWith("-seek")), Map.entry("nonEmptyExtraSeek", true),
            Map.entry("defaultD3d11FlipUnchanged", true), Map.entry("baselinePosition", position), Map.entry("playlistEntryId", entry),
            Map.entry("baselineNoise", noise), Map.entry("fastChain", FAST), Map.entry("qualityChain", QUALITY), Map.entry("fixedAssetSha256", SHADER_PINS),
            Map.entry("pixelGate", "preset meanDelta>max(0.1,noise*3) and changedPixels>100; clear meanDelta<=max(0.1,noise*2); abs(position delta)<0.15")));
        List<Object> stages = new ArrayList<>(); result.put("shaderStages", stages);
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String stage = index == 0 ? "fast" : index == 1 ? "quality" : "clear";
            List<String> paths = index == 0 ? fast : index == 1 ? quality : List.of();
            Set<String> exclusive = index == 0 ? fastExclusive : qualityExclusive;
            Map<String, Object> row = new LinkedHashMap<>(); row.put("stage", stage); row.put("passed", false);
            stages.add(row); BufferedImage image = null;
            try {
                shaderPlaybackGuard(entry, position);
                row.put("command", actor.shaders(paths, index < 2 || caseName.endsWith("-seek")).get(3, TimeUnit.SECONDS));
                waitCondition(stage + " actual NODE and exclusive executed pass", 15_000, () -> {
                    shaderPlaybackGuard(entry, position);
                    return paths.equals(actor.shaderFiles) && (paths.isEmpty() || actor.shaderPasses.stream()
                        .anyMatch(pass -> exclusive.stream().anyMatch(pass::contains)));
                });
                long end = stageDeadline(5_000); PixelDifference delta = null;
                boolean matched = false;
                do {
                    checkDeadline(); shaderPlaybackGuard(entry, position);
                    image = capture("shader-" + stage); delta = difference(baseline, image);
                    matched = index == 2 ? delta.meanDelta() <= Math.max(0.1, noise * 2) :
                        delta.meanDelta() > Math.max(0.1, noise * 3) && delta.changedPixels() > 100;
                    if (matched) break;
                    Thread.sleep(50);
                } while (System.nanoTime() < end);
                row.put("meanDelta", delta == null ? -1 : delta.meanDelta());
                row.put("changedPixels", delta == null ? -1 : delta.changedPixels());
                if (!matched) throw new GateFailure("Original physical shader pixel gate failed: " + stage);
                shaderPlaybackGuard(entry, position);
                row.put("passed", true);
            } catch (Exception error) {
                row.put("failure", Map.of("type", error.getClass().getSimpleName(), "message", safe(error.getMessage())));
                failures.add(stage + ": " + safe(error.getMessage()));
            } finally {
                if (image == null) image = capture("shader-" + stage + "-failure");
                ImageIO.write(image, "png", output.resolve("shader-" + stage + (Boolean.TRUE.equals(row.get("passed")) ? ".png" : "-failed.png")).toFile());
                row.put("pixels", imageStats(image)); row.put("rows", scanRows(image)); row.put("native", actor.snapshot);
                row.put("appliedFiles", actor.shaderFiles); row.put("executedPasses", actor.shaderPasses);
                row.put("restartEvents", actor.restartCount); observe("shader-" + stage); publishReport();
            }
        }
        // These commands can themselves redraw. Run only AFTER all physical pass/fail decisions.
        List<Object> auxiliary = new ArrayList<>(); result.put("nativeScreenshotsAfterPixelGates", auxiliary);
        for (String mode : List.of("video", "window")) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("mode", mode); row.put("auxiliaryOnly", true); auxiliary.add(row);
            Path target = output.resolve("native-clear-" + mode + ".png");
            try {
                actor.command("screenshot-to-file", target.toString(), mode).get(3, TimeUnit.SECONDS);
                require(Files.isRegularFile(target) && Files.size(target) <= 32 * 1024 * 1024, "Native screenshot absent/oversized");
                BufferedImage nativeImage = ImageIO.read(target.toFile());
                require(nativeImage != null && nativeImage.getWidth() <= 16_384 && nativeImage.getHeight() <= 16_384, "Native screenshot image invalid");
                row.put("written", true); row.put("sha256", sha256(target)); row.put("pixels", imageStats(nativeImage)); row.put("rows", scanRows(nativeImage));
            } catch (Exception error) { row.put("written", false); row.put("error", safe(error.toString())); }
        }
        BufferedImage after = capture("after-auxiliary-native-screenshot");
        ImageIO.write(after, "png", output.resolve("screen-after-native-screenshots.png").toFile());
        result.put("afterAuxiliaryMeanDelta", difference(baseline, after).meanDelta());
        shaderPlaybackGuard(entry, position);
        Files.writeString(output.resolve("shader-state.txt"), json(result) + "\n", StandardOpenOption.CREATE_NEW);
        if (!failures.isEmpty()) throw new GateFailure(String.join("; ", failures));
    }

    private record PixelDifference(double meanDelta, int changedPixels) { }
    private static PixelDifference difference(BufferedImage before, BufferedImage after) {
        require(before.getWidth() == after.getWidth() && before.getHeight() == after.getHeight(), "Physical capture geometry changed");
        long sum = 0; int changed = 0;
        for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++) {
            int a = before.getRGB(x, y), b = after.getRGB(x, y);
            int r = Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255));
            int g = Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)); int blue = Math.abs((a & 255) - (b & 255));
            sum += r + g + blue; if (Math.max(r, Math.max(g, blue)) > 8) changed++;
        }
        return new PixelDifference(sum / (before.getWidth() * (double) before.getHeight() * 3), changed);
    }

    private static List<Object> scanRows(BufferedImage image) {
        List<Object> rows = new ArrayList<>();
        for (int y = 0; y < image.getHeight(); y++) {
            long brightness = 0; int nonBlack = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                int c = image.getRGB(x, y), r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                brightness += r + g + b; if (Math.max(r, Math.max(g, b)) > 8) nonBlack++;
            }
            rows.add(Map.of("row", y, "mean", brightness / (image.getWidth() * 3.0), "nonBlackPixels", nonBlack));
        }
        return rows;
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
        BufferedImage baseline = capture("alpha-baseline");
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
        if (dll != null && !shaderCase()) checkSurfaceFixture(restored, "alpha-restored");
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
            image = capture(stage);
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

    /** Same fixed MJPEG geometry and thresholds as PlayerSelfTest, adapted to the probe's actual OSD poll. */
    static record FixtureViewport(int osdWidth, int osdHeight, int left, int top, int contentWidth, int contentHeight) {
        FixtureViewport {
            require(osdWidth > 0 && osdHeight > 0 && contentWidth > 0 && contentHeight > 0, "Invalid native fixture viewport");
        }
        static FixtureViewport from(Map<String, Object> snapshot) {
            int w = integer(snapshot, "osd-dimensions/w"), h = integer(snapshot, "osd-dimensions/h");
            int l = integer(snapshot, "osd-dimensions/ml"), r = integer(snapshot, "osd-dimensions/mr");
            int t = integer(snapshot, "osd-dimensions/mt"), b = integer(snapshot, "osd-dimensions/mb");
            require("320".equals(nativeValue(snapshot, "video-dec-params/w")) &&
                "180".equals(nativeValue(snapshot, "video-dec-params/h")), "Native fixture decoded dimensions changed");
            return new FixtureViewport(w, h, l, t, w - l - r, h - t - b);
        }
        private static int integer(Map<String, Object> snapshot, String name) {
            String value = nativeValue(snapshot, name);
            require(value != null, "Native fixture viewport unavailable: " + name);
            try { return Integer.parseInt(value); }
            catch (NumberFormatException invalid) { throw new IllegalStateException("Invalid native fixture viewport: " + name); }
        }
    }

    static Map<String, Object> checkFixtureSurface(BufferedImage image, FixtureViewport viewport) {
        require(hasVideoColors(image), "Native Windows fixture lost original cyan/pink pixel thresholds");
        require(viewport != null, "Native fixture viewport unavailable");
        java.awt.geom.AffineTransform transform = java.awt.geom.AffineTransform.getScaleInstance(
            image.getWidth() / (double) viewport.osdWidth(), image.getHeight() / (double) viewport.osdHeight());
        transform.translate(viewport.left(), viewport.top());
        transform.scale(viewport.contentWidth() / (double) WIDTH, viewport.contentHeight() / (double) HEIGHT);
        List<Object> bands = new ArrayList<>();
        // Circle ends at y88, labels at y135, progress starts at y164; JPEG block y144..159 is background. Never test those dynamic regions.
        for (Rectangle source : List.of(new Rectangle(4, 98, WIDTH - 8, 12), new Rectangle(4, 143, WIDTH - 8, 8))) {
            java.awt.geom.Rectangle2D mapped = transform.createTransformedShape(source).getBounds2D();
            int left = Math.max(0, (int) Math.ceil(mapped.getMinX()) + 1), top = Math.max(0, (int) Math.ceil(mapped.getMinY()) + 1);
            int right = Math.min(image.getWidth(), (int) Math.floor(mapped.getMaxX()) - 1);
            int bottom = Math.min(image.getHeight(), (int) Math.floor(mapped.getMaxY()) - 1);
            require(right - left >= 8 && bottom - top >= 2, "Native fixture background strip was not sufficiently visible");
            int[] rows = new int[bottom - top], columns = new int[right - left]; int good = 0;
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
                Color color = new Color(image.getRGB(x, y));
                if (Math.abs(color.getRed() - 24) <= 18 && Math.abs(color.getGreen() - 27) <= 18 && Math.abs(color.getBlue() - 38) <= 18) {
                    good++; rows[y - top]++; columns[x - left]++;
                }
            }
            int width = right - left, height = bottom - top;
            double fill = good / (double) (width * height);
            double row = Arrays.stream(rows).min().orElseThrow() / (double) width;
            double column = Arrays.stream(columns).min().orElseThrow() / (double) height;
            Map<String, Object> band = Map.of("physicalBounds", rect(new Rectangle(left, top, width, height)),
                "fillFraction", fill, "minimumRowFill", row, "minimumColumnFill", column);
            require(fill >= 0.98 && row >= 0.95 && column >= 0.95, "Native fixture background was discontinuous: " + json(band));
            bands.add(band);
        }
        return Map.of("bands", bands, "viewport", viewport.toString(), "pixels", imageStats(image));
    }

    private static String nativeValue(Map<String, Object> snapshot, String name) {
        Object row = snapshot.get(name);
        return row instanceof Map<?, ?> values && values.get("value") instanceof String value ? value : null;
    }

    private void checkSurfaceFixture(BufferedImage image, String stage) {
        try { result.put(stage + "FixtureIntegrity", checkFixtureSurface(image, FixtureViewport.from(actor.snapshot))); }
        catch (IllegalStateException invalid) {
            surfacePhysicalFailure = true;
            throw new GateFailure("Physical screen gate failed: " + stage + " " + safe(invalid.getMessage()));
        }
    }

    private BufferedImage waitFixtureScreen(String stage, String file, long budgetMs) throws Exception {
        long end = stageDeadline(budgetMs); BufferedImage image = null; String rejected = "No physical capture";
        do {
            checkDeadline(); Map<String, Object> before = actor.snapshot;
            image = capture(stage); Map<String, Object> after = actor.snapshot;
            try {
                FixtureViewport viewport = FixtureViewport.from(before);
                require(viewport.equals(FixtureViewport.from(after)) &&
                    Objects.equals(nativeValue(before, "playlist/0/id"), nativeValue(after, "playlist/0/id")) &&
                    nativeValue(before, "playlist/0/id") != null, "Native fixture viewport/source changed across physical capture");
                Map<String, Object> integrity = checkFixtureSurface(image, viewport);
                ImageIO.write(image, "png", output.resolve(file).toFile());
                result.put(stage, imageStats(image)); result.put(stage + "FixtureIntegrity", integrity);
                observe(stage); return image;
            } catch (IllegalStateException invalid) { rejected = safe(invalid.getMessage()); }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        if (image != null) { ImageIO.write(image, "png", output.resolve(file).toFile()); result.put(stage, imageStats(image)); }
        result.put("fixtureIntegrityFailure", rejected); surfacePhysicalFailure = true;
        throw new GateFailure("Physical screen gate failed: " + stage + " " + rejected);
    }

    /** Failure-only decoded input; never contributes to the physical screen gate. */
    private void observeFailedSurfaceVideo() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>(); result.put("failedSurfaceVideoAuxiliary", row);
        row.put("auxiliaryOnly", true); row.put("physicalResult", "failed; retained independently of this auxiliary capture");
        row.put("imageKind", "later decoded video-mode capture; not GPU output, swapchain or physical-screen proof");
        row.put("nativeTiming", "same actor/entry checks on native worker; cached state and Robot pixels are near samples, not atomic");
        if (deadline - System.nanoTime() <= TimeUnit.SECONDS.toNanos(9)) {
            row.put("status", "skipped: original deadline reserves existing window auxiliary and cleanup"); return;
        }
        Map<String, Object> before = actor.snapshot; String entry = nativeValue(before, "playlist/0/id");
        row.put("nativeBefore", before);
        if (actor.failure != null || !actor.fileLoaded || entry == null || !"1".equals(nativeValue(before, "playlist-count"))) {
            row.put("status", "skipped: original single source/actor is unavailable"); return;
        }
        CompletableFuture<Map<String, Object>> command = null;
        try {
            Path target = output.resolve("native-failure-video.png");
            command = actor.screenshotForEntry(entry, target, "video");
            row.put("workerSource", command.get(Math.min(TimeUnit.SECONDS.toNanos(3),
                Math.max(1, deadline - System.nanoTime() - TimeUnit.SECONDS.toNanos(6))), TimeUnit.NANOSECONDS));
            require(Files.isRegularFile(target) && Files.size(target) > 0 && Files.size(target) <= 32 * 1024 * 1024,
                "Auxiliary decoded video absent/oversized");
            BufferedImage image = ImageIO.read(target.toFile());
            require(image != null && (long) image.getWidth() * image.getHeight() <= 16_000_000,
                "Auxiliary decoded video invalid/oversized");
            int width = Integer.parseInt(nativeValue(before, "video-dec-params/w"));
            int height = Integer.parseInt(nativeValue(before, "video-dec-params/h"));
            boolean matches = image.getWidth() == width && image.getHeight() == height;
            row.put("image", target.getFileName().toString()); row.put("sha256", sha256(target));
            Map<String, Object> pixels = new LinkedHashMap<>(imageStats(image)); pixels.put("physicalScreenCapture", false);
            row.put("pixels", pixels); row.put("matchesObservedDecodedSize", matches);
            require(matches, "Auxiliary video did not match observed decoded dimensions");
            row.put("status", "captured");
        } catch (Exception | LinkageError invalid) {
            if (command != null && !command.isDone()) command.cancel(false);
            if (invalid instanceof InterruptedException) Thread.currentThread().interrupt();
            row.put("status", "auxiliary failed"); row.put("error", safe(invalid.toString()));
        } finally {
            Map<String, Object> after = actor.snapshot; row.put("nativeAfter", after);
            row.put("sameObservedEntry", entry.equals(nativeValue(after, "playlist/0/id")));
            publishReport();
        }
    }

    /** One later window capture only. GPU capture may fall back to software; the physical failure remains unchanged. */
    private void observeFailedSurfaceWindow() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>(); result.put("failedSurfaceWindowAuxiliary", row);
        row.put("auxiliaryOnly", true); row.put("physicalResult", "failed; retained independently of this auxiliary capture");
        row.put("imageKind", "later mpv window-size capture requested; GPU re-render may fall back to software; not swapchain readback or physical-screen proof");
        row.put("nativeTiming", "near cached same-actor snapshots; not atomic with Robot pixels or screenshot command");
        if (deadline - System.nanoTime() <= TimeUnit.SECONDS.toNanos(6)) {
            row.put("status", "skipped: original case deadline reserves native/window cleanup"); return;
        }
        Map<String, Object> before = actor.snapshot; String entry = nativeValue(before, "playlist/0/id");
        row.put("nativeBefore", before); row.put("screenshotSwBefore", nativeValue(before, "options/screenshot-sw"));
        if (actor.failure != null || !actor.fileLoaded || entry == null || !"1".equals(nativeValue(before, "playlist-count"))) {
            row.put("status", "skipped: original single source/actor is unavailable"); return;
        }
        CompletableFuture<Map<String, Object>> command = null;
        try {
            BufferedImage physical = capture("failure-before-window-auxiliary");
            ImageIO.write(physical, "png", output.resolve("screen-before-window-auxiliary.png").toFile());
            require(entry.equals(actor.value("playlist/0/id")) && actor.failure == null, "Original source retired before window auxiliary");
            Path target = output.resolve("native-failure-window.png");
            command = actor.screenshotForEntry(entry, target, "window");
            row.put("workerSource", command.get(Math.min(TimeUnit.SECONDS.toNanos(3), Math.max(1, deadline - System.nanoTime() - TimeUnit.SECONDS.toNanos(3))), TimeUnit.NANOSECONDS));
            require(Files.isRegularFile(target) && Files.size(target) > 0 && Files.size(target) <= 32 * 1024 * 1024, "Auxiliary native image absent/oversized");
            BufferedImage image = ImageIO.read(target.toFile());
            require(image != null && (long) image.getWidth() * image.getHeight() <= 16_000_000, "Auxiliary native image invalid/oversized");
            FixtureViewport viewport = FixtureViewport.from(before);
            boolean matches = image.getWidth() == viewport.osdWidth() && image.getHeight() == viewport.osdHeight();
            row.put("image", target.getFileName().toString()); row.put("sha256", sha256(target));
            Map<String, Object> stats = new LinkedHashMap<>(imageStats(image)); stats.put("physicalScreenCapture", false);
            row.put("pixels", stats); row.put("matchesObservedOsdSize", matches);
            require(matches, "Auxiliary window image did not match observed OSD size; window-size comparison unavailable");
            row.put("status", "captured");
        } catch (Exception | LinkageError invalid) {
            if (command != null && !command.isDone()) command.cancel(false);
            if (invalid instanceof InterruptedException) Thread.currentThread().interrupt();
            row.put("status", "auxiliary failed"); row.put("error", safe(invalid.toString()));
        } finally {
            Map<String, Object> after = actor.snapshot; row.put("nativeAfter", after);
            row.put("sameObservedEntry", entry.equals(nativeValue(after, "playlist/0/id")));
            row.put("screenshotSwAfter", nativeValue(after, "options/screenshot-sw"));
            try {
                BufferedImage physical = capture("failure-after-window-auxiliary");
                ImageIO.write(physical, "png", output.resolve("screen-after-window-auxiliary.png").toFile());
                row.put("physicalAfter", imageStats(physical));
            } catch (Exception | LinkageError invalid) { row.put("physicalAfterError", safe(invalid.toString())); }
            row.put("fallbackLogs", actor.fallbackLogText().lines().filter(line -> line.contains("Falling back to software screenshot")).limit(12).toList());
            row.put("fallbackLogTiming", "bounded actor log drained near auxiliary capture; absence is not proof GPU screenshot path succeeded");
            publishReport();
        }
    }

    private BufferedImage capture(String stage) throws Exception {
        long beganMs = elapsedMs();
        Rectangle bounds = edt(() -> {
            require(frame.isShowing() && canvas.isShowing() && canvas.getWidth() > 0 && canvas.getHeight() > 0, "Own Canvas is not showing");
            Rectangle rect = viewport();
            require(screenBounds.contains(rect), "Own Canvas extends outside its physical monitor capture region");
            return rect;
        });
        Toolkit.getDefaultToolkit().sync();
        BufferedImage image = new Robot(edt(() -> canvas.getGraphicsConfiguration().getDevice())).createScreenCapture(bounds);
        recordCapture(stage, beganMs, bounds, image);
        return image;
    }

    /** Diagnostic reads only: capturing pixels never requests focus, paint, or stacking changes. */
    private void recordCapture(String stage, long beganMs, Rectangle bounds, BufferedImage image) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage); row.put("beganMs", beganMs); row.put("capturedMs", elapsedMs());
        row.put("captureScreenBounds", rect(bounds)); row.put("pixels", imageStats(image));
        // One immutable readback snapshot from the same native actor; no new mpv command/query.
        Map<String, Object> nativeSnapshot = actor == null ? Map.of() : actor.snapshot;
        row.put("nativePause", nativeSnapshot.get("pause")); row.put("nativeClock", nativeSnapshot.get("time-pos"));
        try {
            row.putAll(edt(() -> {
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("telemetryMs", elapsedMs()); facts.put("frameFocused", frame.isFocused());
                facts.put("frameNative", windowFacts(Native.getComponentPointer(frame)));
                facts.put("canvasNative", windowFacts(Native.getComponentPointer(canvas)));
                if (overlay != null && overlay.isDisplayable())
                    facts.put("overlayNative", windowFacts(Native.getComponentPointer(overlay)));
                Object childEntry = nativeSnapshot.get("window-id");
                if (childEntry instanceof Map<?, ?> entry && entry.get("value") instanceof String rawChild) {
                    long id = rawChild.startsWith("0x") ? Long.parseUnsignedLong(rawChild.substring(2), 16) : Long.parseUnsignedLong(rawChild);
                    if (id != 0) facts.put("mpvChildNative", windowFacts(new Pointer(id)));
                }
                return facts;
            }));
        } catch (Exception | LinkageError unavailable) {
            // Supplemental telemetry must not replace the physical screen gate or its original cause.
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            row.put("telemetryError", unavailable.getClass().getSimpleName());
        }
        if (captureTimeline.size() == 32) { captureTimeline.removeFirst(); droppedCaptures++; }
        captureTimeline.add(row); result.put("captureTimelineDropped", droppedCaptures); publishReport();
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
            "options/d3d11-output-csp", "frame-drop-count", "volume", "mute", "current-ao", "audio-codec",
            "osd-dimensions/w", "osd-dimensions/h", "osd-dimensions/ml", "osd-dimensions/mr", "osd-dimensions/mt", "osd-dimensions/mb",
            "options/screenshot-sw", "playlist/0/id", "playlist-count"};
        private static final String[] SHADER_PROPERTIES = {"seeking", "playlist/0/id", "playlist-count", "playlist-pos", "video-frame-info/picture-type",
            "options/gpu-dumb-mode", "options/fbo-format", "glsl-shader-opts", "video-params/gamma", "video-out-params/gamma", "video-target-params/gamma"};
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicBoolean stopping = new AtomicBoolean(), terminated = new AtomicBoolean();
        final BlockingQueue<String> pauseCommands = new LinkedBlockingQueue<>();
        final BlockingQueue<NativeTask<?>> tasks = new LinkedBlockingQueue<>();
        volatile Map<String, Object> snapshot = Map.of();
        volatile Throwable failure;
        volatile boolean fileLoaded, playbackRestart;
        volatile int restartCount;
        volatile List<String> shaderFiles;
        volatile List<String> shaderPasses = List.of();
        volatile int droppedLines;
        private final List<String> firstLogs = new ArrayList<>();
        private final Deque<String> recentLogs = new ArrayDeque<>();
        private final List<String> severityLogs = new ArrayList<>(), infoQueueLogs = new ArrayList<>(),
            fallbackLogs = new ArrayList<>(), deviceLogs = new ArrayList<>();
        private int severityOverflow, infoQueueOverflow, fallbackOverflow, deviceOverflow;
        volatile Map<String, String> requestedStartupOptions = Map.of();
        private final Path dll, video, audio;
        private final long hwnd;
        private final String selectedCase;
        private final boolean debugObservations;
        volatile Map<String, Object> runtimeModules = Map.of();
        volatile String expectedFlip = "yes";
        volatile Map<String, Object> presentationSelection = Map.of();
        private final Thread worker;
        MpvActor(Path dll, long hwnd, Path video, Path audio, String selectedCase, boolean debugObservations) {
            this.dll = dll; this.hwnd = hwnd; this.video = video; this.audio = audio; this.selectedCase = selectedCase;
            this.debugObservations = debugObservations;
            worker = new Thread(this::run, "probe-single-mpv-actor"); worker.setDaemon(true);
        }
        void start() { worker.start(); }
        String value(String key) { Object row = snapshot.get(key); return row instanceof Map<?, ?> map ? (String) map.get("value") : null; }
        double number(String key) { try { return Double.parseDouble(value(key)); } catch (RuntimeException invalid) { return -1; } }
        void setPause(boolean value) { pauseCommands.offer(value ? "yes" : "no"); }
        private interface NativeOperation<T> { T run(Mpv api, Pointer handle) throws Exception; }
        private record NativeTask<T>(NativeOperation<T> operation, CompletableFuture<T> result) {
            void perform(Mpv api, Pointer handle) {
                try { result.complete(operation.run(api, handle)); }
                catch (Throwable error) { result.completeExceptionally(error); }
            }
        }
        private <T> CompletableFuture<T> submit(NativeOperation<T> operation) {
            CompletableFuture<T> result = new CompletableFuture<>();
            if (stopping.get() || failure != null || terminated.get()) result.completeExceptionally(new IllegalStateException("Own native actor unavailable"));
            else tasks.offer(new NativeTask<>(operation, result));
            return result;
        }
        CompletableFuture<Integer> command(String... args) {
            return submit((api, handle) -> { int code = api.mpv_command(handle, new StringArray(args, "UTF-8")); check(api, code, args[0]); return code; });
        }
        CompletableFuture<Map<String, Object>> screenshotForEntry(String entry, Path target, String mode) {
            require(Set.of("video", "window").contains(mode), "Unsupported auxiliary screenshot mode");
            CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
            if (stopping.get() || failure != null || terminated.get()) {
                result.completeExceptionally(new IllegalStateException("Own native actor unavailable"));
            } else tasks.offer(new NativeTask<>((api, handle) -> {
                require(!result.isDone() && !stopping.get() && failure == null && fileLoaded,
                    "Original actor retired/capture canceled before auxiliary command");
                String before = readString(api, handle, "playlist/0/id");
                String countBefore = readString(api, handle, "playlist-count");
                require(entry.equals(before) && "1".equals(countBefore), "Original single entry retired before auxiliary command");
                int code = api.mpv_command(handle, new StringArray(new String[]{"screenshot-to-file", target.toString(), mode}, "UTF-8"));
                check(api, code, "source-owned auxiliary screenshot");
                String after = readString(api, handle, "playlist/0/id");
                String countAfter = readString(api, handle, "playlist-count");
                require(entry.equals(after) && "1".equals(countAfter), "Original single entry retired during auxiliary command");
                return Map.of("mode", mode, "entryBefore", before, "entryAfter", after,
                    "playlistCountBefore", countBefore, "playlistCountAfter", countAfter, "commandCode", code);
            }, result));
            return result;
        }
        CompletableFuture<Map<String, Object>> shaders(List<String> paths, boolean refresh) {
            return submit((api, handle) -> {
                check(api, api.mpv_set_property_string(handle, "fbo-format", "auto"), "fbo-format");
                check(api, api.mpv_set_property_string(handle, "glsl-shader-opts", ""), "glsl-shader-opts");
                try (Nodes nodes = new Nodes()) { check(api, api.mpv_set_property(handle, "glsl-shaders", 6, nodes.array(paths)), "glsl-shaders NODE"); }
                String pause = readString(api, handle, "pause"), seeking = readString(api, handle, "seeking");
                String clock = readString(api, handle, "time-pos"); boolean issued = false;
                if (refresh && fileLoaded && "yes".equals(pause) && !"yes".equals(seeking) && clock != null) {
                    check(api, api.mpv_command(handle, new StringArray(new String[]{"seek", clock, "absolute+exact"}, "UTF-8")), "refresh paused video shaders"); issued = true;
                }
                Map<String, Object> result = new LinkedHashMap<>(); result.put("requestedFiles", paths); result.put("extraSeekRequested", refresh);
                result.put("extraSeekIssued", issued); result.put("nativePauseBeforeSeek", pause); result.put("nativeSeekingBeforeSeek", seeking);
                result.put("nativePositionBeforeSeek", clock); result.put("restartCountBeforeSeek", restartCount); return result;
            });
        }
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
                if (selectedCase.startsWith("shader-clear-")) {
                    // Match production: default flip, optional NVIDIA preference; no software-bitblt substitution.
                    DesktopWindowsDxgiAdapters.Snapshot inventory = DesktopWindowsDxgiAdapters.probe();
                    Map<String, Object> selection = new LinkedHashMap<>(); selection.put("policy", "production-default-flip");
                    selection.put("complete", inventory.complete()); selection.put("error", inventory.error());
                    selection.put("adapters", inventory.adapters().stream().map(adapter -> Map.of("vendorId", adapter.vendorId(),
                        "deviceId", adapter.deviceId(), "flags", adapter.flags(), "description", adapter.description(), "isSoftware", adapter.isSoftware())).toList());
                    selection.put("nvidiaPreferenceReturnCode", api.mpv_set_option_string(handle, "d3d11-adapter", "NVIDIA"));
                    presentationSelection = Collections.unmodifiableMap(selection);
                    if (selectedCase.contains("-nodumb-")) options.put("gpu-dumb-mode", "no");
                    options.put("screenshot-format", "png");
                }
                if (selectedCase.equals("mpv-default-debug")) options.put("gpu-debug", "yes");
                boolean bitblt = selectedCase.equals("mpv-bitblt");
                if (selectedCase.equals("mpv-adaptive")) {
                    // Compile and call the same production helper, not a diagnostic copy of the policy.
                    DesktopWindowsDxgiAdapters.Snapshot inventory = DesktopWindowsDxgiAdapters.probe();
                    bitblt = inventory.useBitblt();
                    Map<String, Object> selection = new LinkedHashMap<>();
                    selection.put("policy", "complete-all-software-dxgi");
                    selection.put("complete", inventory.complete()); selection.put("error", inventory.error());
                    selection.put("useBitblt", bitblt);
                    selection.put("adapters", inventory.adapters().stream().map(adapter -> Map.of(
                        "vendorId", adapter.vendorId(), "deviceId", adapter.deviceId(), "flags", adapter.flags(),
                        "description", adapter.description(), "isSoftware", adapter.isSoftware())).toList());
                    // Match production's existing validated NVIDIA preference; rejection keeps auto.
                    selection.put("nvidiaPreferenceReturnCode", api.mpv_set_option_string(handle, "d3d11-adapter", "NVIDIA"));
                    presentationSelection = Collections.unmodifiableMap(selection);
                }
                expectedFlip = bitblt ? "no" : "yes";
                if (bitblt) options.put("d3d11-flip", "no"); // Default/hardware cases leave the pinned default untouched.
                if (debugObservations) requestedStartupOptions = Collections.unmodifiableMap(new LinkedHashMap<>(options));
                for (Map.Entry<String, String> option : options.entrySet()) check(api, api.mpv_set_option_string(handle, option.getKey(), option.getValue()), option.getKey());
                check(api, api.mpv_request_log_messages(handle, debugObservations ? "debug" : "v"), "request-log-messages");
                check(api, api.mpv_initialize(handle), "initialize");
                check(api, api.mpv_command(handle, new StringArray(new String[]{"loadfile", video.toString(), "replace"}, "UTF-8")), "loadfile");
                ready.complete(null);
                long last = 0;
                while (!stopping.get()) {
                    String pause;
                    while ((pause = pauseCommands.poll()) != null) check(api, api.mpv_set_property_string(handle, "pause", pause), "pause");
                    NativeTask<?> task;
                    while ((task = tasks.poll()) != null) task.perform(api, handle);
                    Pointer event = api.mpv_wait_event(handle, 0.025);
                    int id = event.getInt(0);
                    if (id == 1) throw new IllegalStateException("Unexpected mpv shutdown");
                    if (id == 8) fileLoaded = true;
                    if (id == 21) { playbackRestart = true; restartCount++; }
                    if (id == 7) {
                        fileLoaded = false; Pointer data = event.getPointer(16);
                        if (data != null && data.getInt(0) == 4) throw new IllegalStateException("END_FILE native error " + data.getInt(4));
                    }
                    if (id == 2) {
                        Pointer data = event.getPointer(16);
                        if (data != null) {
                            String prefix = nativeText(data.getPointer(0), 96);
                            if (prefix.startsWith("vo") || prefix.startsWith("vd") ||
                                (!selectedCase.startsWith("shader-clear-") && prefix.equals("screenshot")) || data.getInt(24) <= 30)
                                log(prefix + " [" + nativeText(data.getPointer(8), 32) + "] " + nativeText(data.getPointer(16), 1024));
                        }
                    }
                    if (System.nanoTime() - last >= 150_000_000L) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        List<String> names = new ArrayList<>(Arrays.asList(PROPERTIES));
                        if (selectedCase.startsWith("shader-clear-")) names.addAll(Arrays.asList(SHADER_PROPERTIES));
                        if (debugObservations) names.add("options/gpu-debug");
                        for (String property : names) {
                            try (Memory memory = new Memory(Native.POINTER_SIZE)) {
                                memory.clear(); int code = api.mpv_get_property(handle, property, 1, memory);
                                Pointer text = code >= 0 ? memory.getPointer(0) : null;
                                Map<String, Object> entry = new LinkedHashMap<>(); entry.put("code", code);
                                try { if (text != null) entry.put("value", nativeText(text, 512)); }
                                finally { if (text != null) api.mpv_free(text); }
                                values.put(property, Collections.unmodifiableMap(entry));
                            }
                        }
                        if (selectedCase.startsWith("shader-clear-")) {
                            shaderFiles = readNodeStrings(api, handle, "glsl-shaders");
                            shaderPasses = readPasses(api, handle);
                            values.put("glsl-shaders-NODE", shaderFiles); values.put("vo-passes-descriptions-NODE", shaderPasses);
                            values.put("restartEventCount", restartCount);
                        }
                        snapshot = Collections.unmodifiableMap(values); last = System.nanoTime();
                    }
                }
            } catch (Throwable error) { failure = error; ready.completeExceptionally(error); log("ACTOR_FAILURE " + safe(error.toString())); }
            finally {
                if (debugObservations) runtimeModules = loadedRuntimeModules();
                NativeTask<?> task;
                while ((task = tasks.poll()) != null) task.result().completeExceptionally(new IllegalStateException("Own native actor stopped"));
                if (api != null && handle != null) {
                    try { api.mpv_terminate_destroy(handle); terminated.set(true); }
                    catch (Throwable error) { failure = error; log("DESTROY_FAILURE " + safe(error.toString())); }
                } else terminated.set(true);
            }
        }

        // One sample on the sole worker, before its device is destroyed. No module is loaded by this query.
        private Map<String, Object> loadedRuntimeModules() {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("scope", "own JVM loaded modules at worker retirement; not device identity or debug-layer ACK");
            facts.put("ownPid", PID);
            List<Object> modules = new ArrayList<>(); facts.put("modules", modules);
            try {
                Kernel32 kernel = Native.load("kernel32", Kernel32.class);
                for (String name : List.of("d3d10warp.dll", "d3d11.dll", "dxgi.dll", "d3d11sdklayers.dll", "dxgidebug.dll")) {
                    Map<String, Object> row = new LinkedHashMap<>(); row.put("name", name); modules.add(row);
                    Native.setLastError(0);
                    Pointer module = kernel.GetModuleHandleW(new com.sun.jna.WString(name));
                    row.put("loaded", module != null); row.put("handleQueryLastError", Native.getLastError());
                    if (module == null) continue;
                    char[] path = new char[32768]; Native.setLastError(0);
                    int length = kernel.GetModuleFileNameW(module, path, path.length);
                    row.put("pathQueryLastError", Native.getLastError());
                    if (length <= 0 || length >= path.length) { row.put("pathUnavailable", true); continue; }
                    String actual = new String(path, 0, length);
                    if (!Path.of(actual).getFileName().toString().equalsIgnoreCase(name)) {
                        row.put("pathUnavailable", true); row.put("reason", "module basename did not match"); continue;
                    }
                    row.put("path", actual);
                }
            } catch (Throwable unavailable) { facts.put("error", safe(unavailable.toString())); }
            return Collections.unmodifiableMap(facts);
        }
        private Map<String, Object> debugObservation() {
            String text = fallbackLogText();
            List<String> fallback = text.lines().filter(line -> line.contains("gpu-debug disabled due to error:")).limit(12).toList();
            List<String> messages; synchronized (this) { messages = List.copyOf(infoQueueLogs).stream().limit(24).toList(); }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("requested", selectedCase.equals("mpv-default-debug"));
            facts.put("state", !fallback.isEmpty() ? "EXPLICIT_FALLBACK" : !messages.isEmpty() ? "ACTUAL_MESSAGE_OBSERVED" :
                selectedCase.equals("mpv-default-debug") ? "REQUESTED_UNCONFIRMED" : "NOT_REQUESTED");
            facts.put("fallbackLogs", fallback); facts.put("debugLayerMessages", messages);
            facts.put("limits", "No device GetCreationFlags/InfoQueue queried; modules/options or absent logs are not SDK/debug-layer ACK");
            return facts;
        }
        private synchronized String fallbackLogText() {
            return debugObservations ? String.join("\n", fallbackLogs) : logs();
        }
        private synchronized Map<String, Object> criticalLogFacts() {
            return Map.of("scope", "independent first-message bounded buckets; unrelated shader text cannot evict them",
                "severity", List.copyOf(severityLogs), "infoQueue", List.copyOf(infoQueueLogs),
                "fallback", List.copyOf(fallbackLogs), "device", List.copyOf(deviceLogs),
                "limits", Map.of("severity", 64, "infoQueue", 128, "fallback", 16, "device", 32),
                "overflow", Map.of("severity", severityOverflow, "infoQueue", infoQueueOverflow,
                    "fallback", fallbackOverflow, "device", deviceOverflow));
        }
        private synchronized String criticalLogText() {
            return json(criticalLogFacts()) + "\n";
        }
        private synchronized void log(String value) {
            String row = safe(value);
            if (debugObservations) {
                if (row.contains(" [warn] ") || row.contains(" [error] ") || row.contains(" [fatal] ") ||
                    row.startsWith("ACTOR_FAILURE ") || row.startsWith("DESTROY_FAILURE ")) {
                    if (severityLogs.size() < 64) severityLogs.add(row); else severityOverflow++;
                }
                if (row.matches("(?s)vo/gpu/d3d11 \\[.*?\\] [0-9]+: .*")) {
                    if (infoQueueLogs.size() < 128) infoQueueLogs.add(row); else infoQueueOverflow++;
                }
                if (row.contains("gpu-debug disabled due to error:") || row.contains("Falling back to software screenshot")) {
                    if (fallbackLogs.size() < 16) fallbackLogs.add(row); else fallbackOverflow++;
                }
                if (row.contains("Device Name:") || row.contains("Device ID:") || row.contains("LUID:") ||
                    row.contains("Using a software adapter") || row.contains("Using flip-model presentation") ||
                    row.contains("Using bitblt-model presentation")) {
                    if (deviceLogs.size() < 32) deviceLogs.add(row); else deviceOverflow++;
                }
            }
            if (firstLogs.size() < 150) firstLogs.add(row);
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
        private static String readString(Mpv api, Pointer handle, String name) {
            try (Memory memory = new Memory(8)) {
                memory.clear(); if (api.mpv_get_property(handle, name, 1, memory) < 0) return null;
                Pointer text = memory.getPointer(0);
                try { return text == null ? null : nativeText(text, 8192); } finally { if (text != null) api.mpv_free(text); }
            }
        }
        private static List<Pointer> nodeArray(Pointer node, int max) {
            if (node == null || node.getInt(8) != 7) return null;
            Pointer list = node.getPointer(0); if (list == null) return null;
            int count = list.getInt(0); if (count < 0 || count > max) return null;
            Pointer values = list.getPointer(8); if (values == null) return count == 0 ? List.of() : null;
            List<Pointer> result = new ArrayList<>(); for (int i = 0; i < count; i++) result.add(values.share(i * 16L)); return result;
        }
        private static Map<String, Pointer> nodeMap(Pointer node, int max) {
            if (node == null || node.getInt(8) != 8) return Map.of();
            Pointer list = node.getPointer(0); if (list == null) return Map.of(); int count = list.getInt(0);
            if (count < 0 || count > max) return Map.of(); Pointer values = list.getPointer(8), keys = list.getPointer(16);
            if (count > 0 && (values == null || keys == null)) return Map.of();
            Map<String, Pointer> result = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) result.put(nativeText(keys.getPointer(i * 8L), 8192), values.share(i * 16L)); return result;
        }
        private static List<String> readNodeStrings(Mpv api, Pointer handle, String name) {
            try (Memory root = new Memory(16)) {
                root.clear(); if (api.mpv_get_property(handle, name, 6, root) < 0) return null;
                try {
                    List<Pointer> nodes = nodeArray(root, 32); if (nodes == null) return null;
                    List<String> result = new ArrayList<>(); for (Pointer node : nodes) {
                        if (node.getInt(8) != 1) return null; result.add(nativeText(node.getPointer(0), 8192));
                    } return List.copyOf(result);
                } finally { api.mpv_free_node_contents(root); }
            }
        }
        private static List<String> readPasses(Mpv api, Pointer handle) {
            try (Memory root = new Memory(16)) {
                root.clear(); if (api.mpv_get_property(handle, "vo-passes", 6, root) < 0) return List.of();
                try {
                    Set<String> descriptions = new LinkedHashSet<>();
                    for (Pointer frame : nodeMap(root, 8).values()) {
                        List<Pointer> passes = nodeArray(frame, 512); if (passes == null) continue;
                        for (Pointer pass : passes) {
                            Pointer desc = nodeMap(pass, 16).get("desc");
                            if (desc != null && desc.getInt(8) == 1 && descriptions.size() < 512) descriptions.add(nativeText(desc.getPointer(0), 8192));
                        }
                    } return List.copyOf(descriptions);
                } finally { api.mpv_free_node_contents(root); }
            }
        }
    }

    /** Same pinned x64 mpv_node ABI as MpvNodes; all memory lives through synchronous set_property. */
    private static final class Nodes implements AutoCloseable {
        private final List<Memory> buffers = new ArrayList<>();
        private Memory buffer(long bytes) { Memory m = new Memory(Math.max(1, bytes)); m.clear(); buffers.add(m); return m; }
        Pointer array(List<String> strings) {
            Memory root = buffer(16), list = buffer(24), values = buffer(strings.size() * 16L);
            for (int i = 0; i < strings.size(); i++) {
                byte[] bytes = strings.get(i).getBytes(StandardCharsets.UTF_8); Memory text = buffer(bytes.length + 1L); text.write(0, bytes, 0, bytes.length);
                values.setPointer(i * 16L, text); values.setInt(i * 16L + 8, 1);
            }
            list.setInt(0, strings.size()); list.setPointer(8, values); root.setPointer(0, list); root.setInt(8, 7); return root;
        }
        @Override public void close() { for (int i = buffers.size() - 1; i >= 0; i--) buffers.get(i).close(); }
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
        int mpv_set_property(Pointer handle, String name, int format, Pointer data);
        int mpv_get_property(Pointer handle, String name, int format, Pointer data);
        void mpv_free_node_contents(Pointer node);
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
    public interface Kernel32 extends StdCallLibrary {
        boolean ProcessIdToSessionId(int processId, IntByReference sessionId); int GetCurrentThreadId();
        Pointer GetModuleHandleW(com.sun.jna.WString name); int GetModuleFileNameW(Pointer module, char[] path, int size);
    }
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
                g.setColor(new Color(250, 106, 151)); g.fillRect(0, HEIGHT - 16, WIDTH * index / (FPS * SECONDS), 16);
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
