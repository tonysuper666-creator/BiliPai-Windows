package su.litvak.chromecast.api.v2;

import java.io.*;
import java.net.*;
import java.util.*;

/** Windows JVM provider. Construction does not open sockets or start discovery. */
public final class DesktopGoogleCastProvider implements AutoCloseable {
    public record Route(String id, String name, String model, String host, int port) {}
    public record MediaRequest(String url, String title, String author, String mimeType, boolean autoplay, long startPositionMs) {}
    public static final String DEFAULT_RECEIVER_APP = "CC1AD845";
    private final CastDeviceAuth fixtureAuth;
    private volatile ChromeCast active;
    private final java.util.concurrent.atomic.AtomicLong operationEpoch = new java.util.concurrent.atomic.AtomicLong();
    private boolean discoveryOwned;
    public DesktopGoogleCastProvider() { this.fixtureAuth = null; }
    DesktopGoogleCastProvider(CastDeviceAuth fixtureAuth) { this.fixtureAuth = Objects.requireNonNull(fixtureAuth); }

    public synchronized void startDiscovery(InetAddress networkAddress) throws IOException {
        ChromeCasts.startDiscovery(Objects.requireNonNull(networkAddress)); discoveryOwned = true;
    }
    public List<Route> routes() {
        return ChromeCasts.get().stream().filter(device -> device.getAddress() != null)
            .map(device -> new Route(device.getName(), device.getTitle(), device.getModel(), device.getAddress(), device.getPort())).toList();
    }
    public synchronized void stopDiscovery() throws IOException {
        if (discoveryOwned) { ChromeCasts.stopDiscovery(); discoveryOwned = false; }
    }
    public synchronized MediaStatus cast(Route route, MediaRequest request) throws IOException, java.security.GeneralSecurityException {
        long expectedEpoch = operationEpoch.get();
        URI media;
        try { media = URI.create(request.url()); } catch (IllegalArgumentException failure) { throw new IOException("Invalid Cast media URL"); }
        if (!("http".equals(media.getScheme()) || "https".equals(media.getScheme())) || media.getHost() == null || media.getUserInfo() != null
            || request.startPositionMs() < 0 || request.mimeType() == null || request.mimeType().isBlank()) throw new IOException("Invalid Cast media request");
        stopActive();
        ChromeCast device = fixtureAuth == null ? new ChromeCast(route.host(), route.port()) : new ChromeCast(route.host(), route.port(), fixtureAuth);
        active = device;
        device.setAutoReconnect(false); device.setRequestTimeout(3000);
        try {
            device.connect();
            device.requireMediaType(request.mimeType());
            Application app = device.launchApp(DEFAULT_RECEIVER_APP);
            if (app == null || app.sessionId == null || app.transportId == null) throw new IOException("Cast receiver did not acknowledge launch");
            Map<String,Object> metadata = new LinkedHashMap<>(); metadata.put("metadataType",1);
            metadata.put("title", request.title() == null || request.title().isBlank() ? "BiliPai Video" : request.title());
            if (request.author() != null && !request.author().isBlank()) metadata.put("subtitle",request.author());
            Media resource = new Media(request.url(), request.mimeType(), null, Media.StreamType.BUFFERED, null, metadata, null, null);
            MediaStatus status = device.load(resource, request.autoplay(), request.startPositionMs()/1000.0);
            if (status == null || status.mediaSessionId <= 0) throw new IOException("Cast receiver did not acknowledge media");
            if (operationEpoch.get() != expectedEpoch || !device.isConnected()) throw new IOException("Cast operation cancelled");
            return status;
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException failure) {
            device.cancelPendingOperations(); device.disconnect(); if (active == device) active = null; throw failure;
        }
    }
    public synchronized MediaStatus status() throws IOException { return requireActive().getMediaStatus(); }
    public synchronized void play() throws IOException { requireActive().play(); }
    public synchronized void pause() throws IOException { requireActive().pause(); }
    public synchronized void seek(long positionMs) throws IOException {
        if (positionMs < 0) throw new IOException("Invalid Cast position"); requireActive().seek(positionMs/1000.0);
    }
    public synchronized void stop() throws IOException { ChromeCast current = requireActive(); try { current.stopApp(); } finally { stopActive(); } }
    public void cancelPendingOperations() { operationEpoch.incrementAndGet(); ChromeCast current = active; if (current != null) current.cancelPendingOperations(); }
    private ChromeCast requireActive() throws IOException { ChromeCast current = active; if (current == null || !current.isConnected()) throw new IOException("No authenticated Cast session"); return current; }
    private void stopActive() throws IOException { ChromeCast current = active; active = null; if (current != null) current.disconnect(); }
    @Override public void close() throws IOException {
        cancelPendingOperations();
        synchronized (this) { try { stopActive(); } finally { stopDiscovery(); } }
    }
}
