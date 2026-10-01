import java.awt.EventQueue;
import java.awt.Window;
import java.awt.Robot;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.swing.JFrame;
import javax.imageio.ImageIO;

/** Starts the unchanged product main, observes its own Window, then asks normal shutdown. */
public final class PhysicalRootStartupFixture {
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
                        // Only the freshly launched, task-owned application's visible rectangle.
                        ImageIO.write(new Robot().createScreenCapture(owned.getBounds()), "png", report.resolve("private-product-window.png").toFile());
                        String result = "{\"startupHealthAcknowledged\":true,\"visibleProductWindows\":" + visible +
                            ",\"width\":" + owned.getWidth() + ",\"height\":" + owned.getHeight() +
                            ",\"normalCloseRequested\":true,\"windowClass\":\"" + owned.getClass().getName() + "\"}";
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
