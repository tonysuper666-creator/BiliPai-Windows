import java.awt.EventQueue;
import java.awt.Window;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.swing.JFrame;

/** Starts the unchanged product main, observes its own Window, then asks normal shutdown. */
public final class PhysicalRootRoutesFixture {
    private static final java.util.List<String> observedLabels = new java.util.ArrayList<>();
    private static void collectSemantics(Object node, String wanted, java.util.List<Object> matches) throws Exception {
        Object config = node.getClass().getMethod("getConfig").invoke(node);
        boolean labelled = false;
        Object click = null;
        for (Object item : (Iterable<?>) config) {
            java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) item;
            String name = (String) entry.getKey().getClass().getMethod("getName").invoke(entry.getKey());
            if (name.equals("Text") || name.equals("ContentDescription")) {
                for (Object value : (Iterable<?>) entry.getValue()) {
                    String label = value.toString(); observedLabels.add(label);
                    if (label.equals(wanted)) labelled = true;
                }
            }
            if (name.equals("OnClick")) click = entry.getValue();
        }
        if (labelled && click != null) matches.add(click);
        for (Object child : (Iterable<?>) node.getClass().getMethod("getChildren").invoke(node)) collectSemantics(child, wanted, matches);
    }
    private static java.util.List<Object> semantics(JFrame owned, String wanted) throws Exception {
        observedLabels.clear(); java.util.List<Object> matches = new java.util.ArrayList<>();
        for (Object owner : (Iterable<?>) owned.getClass().getMethod("getSemanticsOwners").invoke(owned)) {
            Object root = owner.getClass().getMethod("getRootSemanticsNode").invoke(owner);
            collectSemantics(root, wanted, matches);
        }
        return matches;
    }
    private static void clickOwned(JFrame owned, String label) throws Exception {
        EventQueue.invokeAndWait(() -> {
            try {
                java.util.List<Object> matches = semantics(owned, label);
                if (matches.size() != 1) throw new IllegalStateException("Expected one product semantic action for " + label + ", found " + matches.size());
                Object action = matches.get(0).getClass().getMethod("getAction").invoke(matches.get(0));
                Object result = Class.forName("kotlin.jvm.functions.Function0").getMethod("invoke").invoke(action);
                if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("Product action rejected " + label);
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }
    private static void observePage(JFrame owned, String required, Path render, Path labels) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            boolean[] ready = {false};
            EventQueue.invokeAndWait(() -> {
                try {
                    semantics(owned, "");
                    ready[0] = observedLabels.contains(required);
                    Files.writeString(labels, String.join("\n", observedLabels));
                    if (ready[0]) captureOwnedRender(owned, render);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            if (ready[0]) return;
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Product page missing " + required);
            Thread.sleep(100);
        }
    }
    private static Object findSkiaLayer(Component component, Class<?> type) {
        if (type.isInstance(component)) return component;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Object found = findSkiaLayer(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void captureOwnedRender(JFrame owned, Path output) throws Exception {
        // Read this product's own render backing, independent of other foreground windows.
        Class<?> layerType = Class.forName("org.jetbrains.skiko.SkiaLayer");
        Object layer = findSkiaLayer(owned, layerType);
        if (layer == null) throw new IllegalStateException("Product Skia layer missing");
        owned.getClass().getMethod("renderImmediately").invoke(owned);
        Object bitmap = layerType.getMethod("screenshot").invoke(layer);
        if (bitmap == null) throw new IllegalStateException("Product render backing missing");
        try {
            Class<?> imageType = Class.forName("org.jetbrains.skia.Image");
            Object companion = imageType.getField("Companion").get(null);
            Object image = companion.getClass().getMethod("makeFromBitmap", bitmap.getClass()).invoke(companion, bitmap);
            try {
                Class<?> formatType = Class.forName("org.jetbrains.skia.EncodedImageFormat");
                Object png = formatType.getField("PNG").get(null);
                Object data = imageType.getMethod("encodeToData", formatType, int.class, int.class).invoke(image, png, 100, 6);
                if (data == null) throw new IllegalStateException("Product render PNG missing");
                try { Files.write(output, (byte[]) data.getClass().getMethod("getBytes").invoke(data)); }
                finally { data.getClass().getMethod("close").invoke(data); }
            } finally { imageType.getMethod("close").invoke(image); }
        } finally { bitmap.getClass().getMethod("close").invoke(bitmap); }
    }
    public static void main(String[] args) throws Exception {
        Path health = Path.of(args[0]), report = Path.of(args[2]);
        String token = args[1];
        Thread observer = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(55).toNanos();
                while (!Files.isRegularFile(health) || !Files.readString(health).trim().equals(token)) {
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("Product startup health timed out");
                    Thread.sleep(100);
                }
                // Observe beyond the updater's normal 1.5-second acknowledgement window.
                Thread.sleep(8000);
                JFrame[] routeOwner = {null};
                EventQueue.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) if (window instanceof JFrame frame && frame.isShowing() && frame.getTitle().equals("BiliPai Windows")) {
                        if (routeOwner[0] != null) throw new IllegalStateException("Multiple product route Windows");
                        routeOwner[0] = frame;
                    }
                });
                JFrame routeWindow = routeOwner[0];
                if (routeWindow == null) throw new IllegalStateException("Product route Window missing");
                observePage(routeWindow, "我的", report.resolve("private-home-render.png"), report.resolve("private-home-labels.txt"));
                clickOwned(routeWindow, "我的"); Thread.sleep(1200);
                observePage(routeWindow, "离线缓存", report.resolve("private-profile-render.png"), report.resolve("private-profile-labels.txt"));
                clickOwned(routeWindow, "离线缓存"); Thread.sleep(1200);
                // Original LoggedOut Profile intentionally sends its download service to login.
                observePage(routeWindow, "请使用哔哩哔哩手机客户端扫码", report.resolve("private-login-render.png"), report.resolve("private-login-labels.txt"));
                clickOwned(routeWindow, "关闭"); Thread.sleep(1200);
                observePage(routeWindow, "离线缓存", report.resolve("private-return-render.png"), report.resolve("private-return-labels.txt"));
                Files.writeString(report.resolve("route-actions.json"), "{\"passed\":true,\"semanticActions\":3,\"routes\":[\"Home\",\"Profile\",\"Original guest download requires Login\",\"Profile after dialog close\"],\"actualProductionCallbacks\":true,\"physicalMouseInput\":false,\"playerRouteAccepted\":false,\"authenticatedDownloadRouteAccepted\":false}");
                EventQueue.invokeAndWait(() -> {
                    try {
                        JFrame owned = null;
                        int visible = 0;
                        for (Window window : Window.getWindows()) {
                            if (window instanceof JFrame frame && frame.isShowing() && frame.getTitle().equals("BiliPai Windows")) {
                                owned = frame;
                                visible++;
                            }
                        }
                        if (visible != 1 || owned == null || owned.getWidth() <= 0 || owned.getHeight() <= 0)
                            throw new IllegalStateException("Expected one actual product Window");
                        captureOwnedRender(owned, report.resolve("private-product-window.png"));
                        String result = "{\"startupHealthAcknowledged\":true,\"visibleProductWindows\":" + visible +
                            ",\"width\":" + owned.getWidth() + ",\"height\":" + owned.getHeight() +
                            ",\"normalCloseRequested\":true,\"imageSource\":\"owned-Skia-render-backing\",\"windowClass\":\"" + owned.getClass().getName() + "\"}";
                        Files.writeString(report.resolve("physical-window.json"), result);
                        owned.dispatchEvent(new WindowEvent(owned, WindowEvent.WINDOW_CLOSING));
                    } catch (Exception failure) {
                        failure.printStackTrace();
                        System.exit(91);
                    }
                });
            } catch (Exception failure) {
                failure.printStackTrace();
                System.exit(92);
            }
        }, "Fixture product-window observer");
        observer.setDaemon(true);
        observer.start();
        com.bilipai.desktop.MainKt.main(new String[] {"--update-health-file", args[0], "--update-health-token", args[1]});
    }
}
