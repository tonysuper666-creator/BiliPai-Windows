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
public final class PhysicalRootStartupFixture {
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
