package su.litvak.chromecast.api.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import javax.net.ssl.*;
import javax.jmdns.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real TCP/TLS/Protobuf + original receiver/media JSON. Every peer is loopback. */
public final class GoogleCastFixtureTest {
    private static Path fixtures;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String,Object>> evidence = new ArrayList<>();
    private static final String SECRET = "SIGNED_URL_MUST_NEVER_APPEAR_IN_ERRORS";
    public interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        fixtures = Path.of(args[0]); Path report = Path.of(args[1]);
        try {
            for (FixtureCase item : cases(fixtures)) test(item.name(), item.body());
            System.out.println("Google Cast standalone fixture PASS: " + evidence.size() + " cases; no physical device claim");
        } finally {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("passed", !evidence.isEmpty() && evidence.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed"))));
            result.put("scope", "isolated-loopback-tls-protocol-fixture"); result.put("physicalDeviceTested", false);
            result.put("originalJavaVersion", "0.12.20"); result.put("cases", evidence);
            Files.createDirectories(report.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), result);
        }
    }
    public record FixtureCase(String name, Case body) {}
    public static List<FixtureCase> cases(Path directory) throws Exception {
        fixtures = directory;
        List<FixtureCase> cases = new ArrayList<>();
        cases.add(new FixtureCase("realTLSAuthenticatesLaunchesLoadsAndControlsOriginalMessages", GoogleCastFixtureTest::positive));
        cases.add(new FixtureCase("expiredCrossSignedCandidateDoesNotHideTheValidCertificatePath", () -> {
                try (Receiver receiver = new Receiver(Mode.BAD_FIRST_INTERMEDIATE); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
                    check(provider.cast(receiver.route(), request("video/mp4")).mediaSessionId == 42,"later valid path is tried");
                }
        }));
            for (Mode mode : List.of(Mode.BAD_CHAIN, Mode.BAD_SIGNATURE, Mode.BAD_NONCE, Mode.OTHER_TLS,
                Mode.MISSING_RESPONSE, Mode.EXPIRED_LEAF, Mode.NO_KEY_USAGE, Mode.WEAK_KEY, Mode.EXPIRED_TLS,
                Mode.LONG_TLS, Mode.WRONG_AUTH_NAMESPACE, Mode.BIG_FRAME, Mode.TRUNCATED_FRAME)) {
            cases.add(new FixtureCase("rejects" + mode.name(), () -> rejects(mode, false)));
            }
        cases.add(new FixtureCase("productionPinsRejectTheSyntheticFixtureRoot", () -> rejects(Mode.NORMAL, true)));
        cases.add(new FixtureCase("audioOnlyDeviceRejectsVideoBeforeReceiverLaunch", GoogleCastFixtureTest::audioOnly));
        cases.add(new FixtureCase("authenticationDeadlineClosesExactSocket", GoogleCastFixtureTest::timeout));
        cases.add(new FixtureCase("cancellationInterruptsHandshakeWaitAndMediaRequest", GoogleCastFixtureTest::cancellation));
        cases.add(new FixtureCase("requestDeadlineAndReceiverErrorAreSanitized", GoogleCastFixtureTest::requestFailure));
        cases.add(new FixtureCase("boundedFrameHasTotalDeadlineDespiteTrickledBytes", GoogleCastFixtureTest::trickle));
        cases.add(new FixtureCase("originalJmDnsDiscoversOnlyTaskOwnedAdvertisedFixture", GoogleCastFixtureTest::discovery));
        cases.add(new FixtureCase("resolvedDiscoveryWaitsForTxtAndRefreshesMetadataWithoutDuplicateRoutes", GoogleCastFixtureTest::resolvedReadiness));
        cases.add(new FixtureCase("validResolvedTxtWithoutFriendlyNameRemainsDiscoverable", GoogleCastFixtureTest::resolvedWithoutFriendlyName));
        cases.add(new FixtureCase("allProviderOwnedThreadsAreQuiesced", () -> {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < end && liveProviderThreads() > 0) Thread.sleep(10);
                check(liveProviderThreads() == 0,"no cast-reader/deadline/ping/fixture thread remains");
        }));
        return cases;
    }
    private static void test(String name, Case body) throws Exception {
        long start = System.nanoTime();
        try { body.run(); evidence.add(Map.of("name", name, "passed", true, "elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))); }
        catch (Exception | AssertionError failure) {
            evidence.add(Map.of("name", name, "passed", false, "errorCategory", failure.getClass().getSimpleName()));
            System.err.println("FAILED fixture case " + name + ": " + failure.getClass().getSimpleName());
            throw failure;
        }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static long liveProviderThreads() { return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
        .filter(thread -> Set.of("cast-reader","cast-deadline","cast-ping","cast-tls-fixture").contains(thread.getName())).count(); }
    private static long liveTransportThreads() { return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
        .filter(thread -> Set.of("cast-reader","cast-deadline","cast-ping").contains(thread.getName())).count(); }
    private static CastDeviceAuth fixtureAuth() throws Exception {
        return new CastDeviceAuth(List.of(CastDeviceAuth.parse(Files.readAllBytes(fixtures.resolve("root.der")))), fixtureClock());
    }
    private static Clock fixtureClock() throws IOException {
        return Clock.fixed(Instant.parse(Files.readString(fixtures.resolve("clock.txt")).strip()), ZoneOffset.UTC);
    }
    private static CastDeviceAuth productionPinsAtFixtureClock() throws Exception {
        List<X509Certificate> roots = new ArrayList<>();
        for (String path : List.of("/cast-v2/cast-root.der", "/cast-v2/eureka-root.der")) {
            try (InputStream input = CastDeviceAuth.class.getResourceAsStream(path)) {
                check(input != null, "actual production pin resource"); roots.add(CastDeviceAuth.parse(input.readAllBytes()));
            }
        }
        return new CastDeviceAuth(roots, fixtureClock());
    }
    private static DesktopGoogleCastProvider.MediaRequest request(String mime) {
        return new DesktopGoogleCastProvider.MediaRequest("http://127.0.0.1:49152/owned-media?signature="+SECRET, "Test & title", "Creator", mime, false, 9234);
    }
    private static void positive() throws Exception {
        try (Receiver receiver = new Receiver(Mode.NORMAL); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
            MediaStatus status = provider.cast(receiver.route(), request("video/mp4"));
            check(status.mediaSessionId == 42 && status.playerState == MediaStatus.PlayerState.PAUSED, "real receiver acknowledgement");
            JsonNode load = receiver.loads.get(0);
            check(!load.path("autoplay").asBoolean() && load.path("currentTime").asDouble() == 9.234, "original autoplay/start preserved");
            check(load.path("media").path("contentType").asText().equals("video/mp4"), "original content type");
            check(load.path("media").path("streamType").asText().equals("BUFFERED") && load.path("media").path("metadata").path("subtitle").asText().equals("Creator"),"original movie metadata and stream type");
            provider.play(); check(provider.status().playerState == MediaStatus.PlayerState.PLAYING, "play acknowledged");
            provider.pause(); check(provider.status().playerState == MediaStatus.PlayerState.PAUSED, "pause acknowledged");
            provider.seek(21000); check(provider.status().currentTime == 21.0, "seek acknowledged");
            provider.stop(); check(receiver.types.containsAll(List.of("CONNECT","LAUNCH","LOAD","PLAY","PAUSE","SEEK","STOP")), "real control messages");
            check(receiver.peerClosed.await(2, TimeUnit.SECONDS), "session socket closed after stop");
            check(liveTransportThreads() == 0, "stop joins every owned transport helper before returning");
        }
    }
    private static void rejects(Mode mode, boolean production) throws Exception {
        try (Receiver receiver = new Receiver(mode); DesktopGoogleCastProvider provider = production ? new DesktopGoogleCastProvider(productionPinsAtFixtureClock()) : new DesktopGoogleCastProvider(fixtureAuth())) {
            boolean rejected = false;
            try { provider.cast(receiver.route(), request("video/mp4")); }
            catch (IOException | GeneralSecurityException expected) { rejected = true; check(!String.valueOf(expected.getMessage()).contains(SECRET), "sanitized auth/frame error"); if (production) check(expected.getMessage().contains("untrusted certificate chain"), "actual pinned roots reject synthetic issuer"); }
            check(rejected, "must reject " + mode); check(!receiver.types.contains("LAUNCH"), "no receiver action before authentication");
            check(receiver.peerClosed.await(2, TimeUnit.SECONDS), "rejected channel closed");
        }
    }
    private static void audioOnly() throws Exception {
        try (Receiver receiver = new Receiver(Mode.AUDIO_ONLY); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
            boolean rejected = false;
            try { provider.cast(receiver.route(), request("video/mp4")); } catch (IOException expected) { rejected = true; }
            check(rejected && !receiver.types.contains("LAUNCH"), "audio-only certificate prevents video launch");
        }
        try (Receiver receiver = new Receiver(Mode.AUDIO_ONLY); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
            check(provider.cast(receiver.route(), request("audio/mp4")).mediaSessionId == 42, "same audio-only auth permits audio");
        }
    }
    private static void timeout() throws Exception {
        try (Receiver receiver = new Receiver(Mode.NO_AUTH); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
            long start = System.nanoTime(); boolean rejected = false;
            try { provider.cast(receiver.route(), request("video/mp4")); } catch (IOException expected) { rejected = true; }
            check(rejected && TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 5000, "auth overall deadline");
            check(receiver.peerClosed.await(1, TimeUnit.SECONDS), "timeout closes socket");
        }
    }
    private static void cancellation() throws Exception {
        for (Mode mode : List.of(Mode.NO_AUTH, Mode.SILENT_MEDIA)) {
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try (Receiver receiver = new Receiver(mode); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
                Future<?> operation = caller.submit(() -> {
                    try { provider.cast(receiver.route(), request("video/mp4")); throw new AssertionError("cancelled operation succeeded"); }
                    catch (IOException | GeneralSecurityException expected) { }
                });
                check((mode == Mode.NO_AUTH ? receiver.authReceived : receiver.loadReceived).await(2, TimeUnit.SECONDS), "request reached actual socket");
                long start = System.nanoTime(); provider.cancelPendingOperations(); operation.get(1, TimeUnit.SECONDS);
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 1000, "cancellation interrupts wait");
                check(receiver.peerClosed.await(1, TimeUnit.SECONDS), "cancelled exact socket closed");
            } finally { caller.shutdownNow(); check(caller.awaitTermination(2, TimeUnit.SECONDS), "caller quiesced"); }
        }
    }
    private static void requestFailure() throws Exception {
        for (Mode mode : List.of(Mode.SILENT_MEDIA, Mode.INVALID_MEDIA)) {
            try (Receiver receiver = new Receiver(mode); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider(fixtureAuth())) {
                long start = System.nanoTime(); boolean rejected = false;
                try { provider.cast(receiver.route(), request("video/mp4")); }
                catch (IOException expected) { rejected = true; check(!String.valueOf(expected.getMessage()).contains(SECRET), "receiver payload absent from exception"); }
                check(rejected && TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 5000, "media request deadline/error");
            }
        }
    }
    private static void trickle() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()); Socket reader = new Socket("127.0.0.1",server.getLocalPort()); Socket sender = server.accept()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> write = executor.submit(() -> {
                    try { sender.getOutputStream().write(ByteBuffer.allocate(4).putInt(10).array()); for (int i=0; i<10; i++) { sender.getOutputStream().write(i); sender.getOutputStream().flush(); Thread.sleep(80); } }
                    catch (IOException | InterruptedException expected) { Thread.currentThread().interrupt(); }
                });
                boolean rejected = false; long start = System.nanoTime();
                try { BoundedCastFrame.read(reader, 500, 250); } catch (SocketTimeoutException expected) { rejected = true; }
                check(rejected && TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 600, "total frame timeout despite progress");
                reader.close(); write.get(2, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); check(executor.awaitTermination(2, TimeUnit.SECONDS), "frame writer quiesced"); }
        }
    }
    private static void discovery() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        String name = "BiliPaiFixture-"+UUID.randomUUID();
        try (JmDNS advertiser = JmDNS.create(loopback); DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider()) {
            provider.startDiscovery(loopback);
            advertiser.registerService(ServiceInfo.create(ChromeCast.SERVICE_TYPE, name, 49153, 0, 0,
                Map.of("fn", "BiliPai isolated fixture", "md", "TLS fixture", "id", name)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            DesktopGoogleCastProvider.Route route = null;
            while (System.nanoTime() < end) {
                route = provider.routes().stream().filter(item -> name.equals(item.id())).findFirst().orElse(null);
                if (route != null) break; Thread.sleep(50);
            }
            check(route != null && "BiliPai isolated fixture".equals(route.name()) && route.port() == 49153 && route.host().equals("127.0.0.1"), "actual original JmDNS fixture route: " + route);
            provider.stopDiscovery(); advertiser.unregisterAllServices();
        }
    }

    private static void resolvedReadiness() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        String name = "BiliPaiFixture-resolved-" + UUID.randomUUID();
        try (DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider()) {
            provider.startDiscovery(loopback);
            Object instance = discoveryInstance();
            java.lang.reflect.Field dnsField = ChromeCasts.class.getDeclaredField("mDNS"); dnsField.setAccessible(true);
            JmDNS dns = (JmDNS)dnsField.get(instance);
            ServiceListener listener = discoveryListener(instance);
            ServiceInfo partial = resolvedInfo(name, 49153, Map.of());
            check(partial.hasData(), "real JmDNS considers empty TXT resolved");
            check(partial.getTextBytes().length == 1, "partial TXT is the actual JmDNS EMPTY_TXT sentinel");
            long requestStarted = System.nanoTime(); listener.serviceAdded(event(dns, partial));
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStarted) < 1000, "Added registration wait is bounded without freezing a partial descriptor");
            listener.serviceResolved(event(dns, partial));
            check(provider.routes().stream().noneMatch(route -> name.equals(route.id())), "partial address/SRV without TXT never published");
            ServiceInfo complete = resolvedInfo(name, 49153, Map.of("fn", "fixture first", "md", "model first", "id", name));
            listener.serviceResolved(event(dns, complete));
            check(provider.routes().stream().filter(route -> name.equals(route.id())).count() == 1, "complete resolved metadata publishes one route");
            listener.serviceResolved(event(dns, partial));
            check(provider.routes().stream().filter(route -> name.equals(route.id())).allMatch(route -> "fixture first".equals(route.name())), "later partial record cannot erase complete metadata");
            ServiceInfo refreshed = resolvedInfo(name, 49154, Map.of("fn", "fixture refreshed", "md", "model refreshed", "id", name));
            listener.serviceResolved(event(dns, refreshed));
            List<DesktopGoogleCastProvider.Route> same = provider.routes().stream().filter(route -> name.equals(route.id())).toList();
            check(same.size() == 1 && same.get(0).port() == 49154 && "fixture refreshed".equals(same.get(0).name()) && "model refreshed".equals(same.get(0).model()), "resolved update refreshes immutable fields without duplicating route");
            provider.stopDiscovery();
            check(provider.routes().isEmpty(), "stopped discovery clears routes");
            listener.serviceResolved(event(dns, refreshed));
            check(provider.routes().isEmpty(), "late event from stopped DNS cannot restore routes");
        }
    }
    private static void resolvedWithoutFriendlyName() throws Exception {
        String name = "BiliPaiFixture-no-fn-" + UUID.randomUUID();
        try (DesktopGoogleCastProvider provider = new DesktopGoogleCastProvider()) {
            provider.startDiscovery(InetAddress.getByName("127.0.0.1"));
            Object instance = discoveryInstance();
            java.lang.reflect.Field dnsField = ChromeCasts.class.getDeclaredField("mDNS"); dnsField.setAccessible(true);
            JmDNS dns = (JmDNS)dnsField.get(instance);
            ServiceListener listener = discoveryListener(instance);
            ServiceInfo valid = resolvedInfo(name, 49153, Map.of("id", name, "md", "fixture model"));
            listener.serviceResolved(event(dns, valid));
            DesktopGoogleCastProvider.Route route = provider.routes().stream().filter(item -> name.equals(item.id())).findFirst().orElse(null);
            check(route != null && route.name() == null && "fixture model".equals(route.model()) && route.port() == 49153 && "127.0.0.1".equals(route.host()), "valid nonempty TXT lacking fn retains original nullable field semantics");
            listener.serviceRemoved(event(dns, valid));
            check(provider.routes().stream().noneMatch(item -> name.equals(item.id())), "removed resolved route is removed");
        }
    }
    private static Object discoveryInstance() throws Exception {
        java.lang.reflect.Field field = ChromeCasts.class.getDeclaredField("INSTANCE"); field.setAccessible(true); return field.get(null);
    }
    private static ServiceListener discoveryListener(Object instance) throws Exception {
        java.lang.reflect.Field field = ChromeCasts.class.getDeclaredField("listener"); field.setAccessible(true); return (ServiceListener)field.get(instance);
    }
    private static ServiceInfo resolvedInfo(String name, int port, Map<String,String> properties) throws Exception {
        ServiceInfo info = ServiceInfo.create(ChromeCast.SERVICE_TYPE, name, port, 0, 0, properties);
        java.lang.reflect.Method server = info.getClass().getDeclaredMethod("setServer", String.class); server.setAccessible(true); server.invoke(info, "bilipai-fixture.local.");
        java.lang.reflect.Method address = info.getClass().getDeclaredMethod("addAddress", Inet4Address.class); address.setAccessible(true); address.invoke(info, InetAddress.getByName("127.0.0.1"));
        return info;
    }
    private static ServiceEvent event(JmDNS dns, ServiceInfo info) {
        return new javax.jmdns.impl.ServiceEventImpl((javax.jmdns.impl.JmDNSImpl)dns, ChromeCast.SERVICE_TYPE, info.getName(), info);
    }

    private enum Mode { NORMAL, BAD_CHAIN, BAD_SIGNATURE, BAD_NONCE, OTHER_TLS, MISSING_RESPONSE, EXPIRED_LEAF,
        NO_KEY_USAGE, WEAK_KEY, AUDIO_ONLY, EXPIRED_TLS, LONG_TLS, WRONG_AUTH_NAMESPACE, NO_AUTH, SILENT_MEDIA, INVALID_MEDIA,
        BIG_FRAME, TRUNCATED_FRAME, BAD_FIRST_INTERMEDIATE }

    private static final class Receiver implements AutoCloseable {
        final Mode mode; final SSLServerSocket server;
        final ExecutorService thread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"cast-tls-fixture");t.setDaemon(true);return t; });
        final CopyOnWriteArrayList<String> types = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<JsonNode> loads = new CopyOnWriteArrayList<>();
        final CountDownLatch peerClosed = new CountDownLatch(1), authReceived = new CountDownLatch(1), loadReceived = new CountDownLatch(1);
        volatile SSLSocket peer; volatile Throwable failure;
        double position; String playerState = "PAUSED"; boolean launched;
        Receiver(Mode mode) throws Exception {
            this.mode = mode;
            String tls = mode == Mode.EXPIRED_TLS ? "expired-tls" : mode == Mode.LONG_TLS ? "long-tls" : "tls";
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream file = Files.newInputStream(fixtures.resolve(tls+".p12"))) { keys.load(file, "cast-fixture".toCharArray()); }
            KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(keys,"cast-fixture".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS"); context.init(manager.getKeyManagers(),null,new SecureRandom());
            server = (SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            thread.submit(() -> {
                try (SSLSocket socket = (SSLSocket)server.accept()) {
                    peer = socket; socket.setSoTimeout(6000); socket.startHandshake();
                    CastChannel.CastMessage challenge = receive(socket); authReceived.countDown();
                    if (mode == Mode.NO_AUTH) { while (socket.getInputStream().read() != -1) {} return; }
                    if (mode == Mode.BIG_FRAME) { socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(65537).array()); socket.getOutputStream().flush(); return; }
                    if (mode == Mode.TRUNCATED_FRAME) { socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(20).array()); socket.getOutputStream().write(1); return; }
                    authenticate(socket, challenge, tls);
                    for (;;) {
                        CastChannel.CastMessage message = receive(socket);
                        if (message.getPayloadType() != CastChannel.CastMessage.PayloadType.STRING) throw new AssertionError("Unexpected binary media payload");
                        JsonNode request = JSON.readTree(message.getPayloadUtf8()); String type = request.path("type").asText(); types.add(type);
                        if (type.equals("CONNECT")) continue;
                        if (type.equals("PING")) { sendJson(socket,message,JSON.createObjectNode().put("type","PONG")); continue; }
                        if (type.equals("LAUNCH")) launched = true;
                        if (type.equals("LOAD")) {
                            loads.add(request); loadReceived.countDown(); position = request.path("currentTime").asDouble(); playerState = request.path("autoplay").asBoolean() ? "PLAYING" : "PAUSED";
                            if (mode == Mode.SILENT_MEDIA) { while (socket.getInputStream().read() != -1) {} return; }
                        }
                        if (type.equals("PLAY")) playerState = "PLAYING";
                        if (type.equals("PAUSE")) playerState = "PAUSED";
                        if (type.equals("SEEK")) position = request.path("currentTime").asDouble();
                        if (type.equals("STOP")) launched = false;
                        ObjectNode reply = JSON.createObjectNode().put("requestId", request.path("requestId").asLong());
                        if (type.equals("LOAD") && mode == Mode.INVALID_MEDIA) reply.put("type","INVALID_REQUEST").put("reason",SECRET);
                        else if (message.getNamespace().equals("urn:x-cast:com.google.cast.receiver")) {
                            reply.put("type","RECEIVER_STATUS"); ObjectNode state = reply.putObject("status");
                            state.putObject("volume").put("level",0.5).put("muted",false);
                            if (launched) state.putArray("applications").addObject().put("appId",DesktopGoogleCastProvider.DEFAULT_RECEIVER_APP).put("sessionId","fixture-session").put("transportId","fixture-transport").put("displayName","Fixture receiver");
                        } else {
                            reply.put("type","MEDIA_STATUS"); reply.putArray("status").addObject().put("mediaSessionId",42).put("playerState",playerState).put("currentTime",position).put("playbackRate",1);
                        }
                        sendJson(socket,message,reply);
                    }
                } catch (IOException expected) { /* Closing or rejecting the exact task-owned peer is expected. */ }
                catch (Throwable error) { failure = error; }
                finally { peerClosed.countDown(); }
            });
        }
        DesktopGoogleCastProvider.Route route() { return new DesktopGoogleCastProvider.Route("owned","Fixture","TLS","127.0.0.1",server.getLocalPort()); }
        private void authenticate(SSLSocket socket, CastChannel.CastMessage challenge, String tls) throws Exception {
            openscreen.cast.proto.CastChannel.DeviceAuthMessage request = openscreen.cast.proto.CastChannel.DeviceAuthMessage.parseFrom(challenge.getPayloadBinary());
            byte[] nonce = request.getChallenge().getSenderNonce().toByteArray();
            check(nonce.length == 16 && request.getChallenge().getHashAlgorithm() == openscreen.cast.proto.CastChannel.HashAlgorithm.SHA256,"actual nonce challenge");
            if (mode == Mode.BAD_NONCE) nonce[0] ^= 1;
            String cert = switch(mode) { case BAD_CHAIN -> "bad-chain"; case EXPIRED_LEAF -> "expired-leaf"; case NO_KEY_USAGE -> "no-usage"; case WEAK_KEY -> "weak"; case AUDIO_ONLY -> "audio"; default -> "leaf"; };
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(fixtures.resolve(mode == Mode.WEAK_KEY ? "weak-key.pk8" : "leaf-key.pk8"))));
            Signature sign = Signature.getInstance("SHA256withRSA"); sign.initSign(key); sign.update(nonce);
            sign.update(Files.readAllBytes(fixtures.resolve(mode == Mode.OTHER_TLS ? "other-tls.der" : tls+".der")));
            byte[] signature = sign.sign(); if (mode == Mode.BAD_SIGNATURE) signature[0] ^= 1;
            openscreen.cast.proto.CastChannel.AuthResponse.Builder authResponse = openscreen.cast.proto.CastChannel.AuthResponse.newBuilder()
                    .setClientAuthCertificate(ByteString.copyFrom(Files.readAllBytes(fixtures.resolve(cert+".der"))))
                    .setSenderNonce(ByteString.copyFrom(nonce)).setHashAlgorithm(openscreen.cast.proto.CastChannel.HashAlgorithm.SHA256)
                    .setSignature(ByteString.copyFrom(signature));
            if (mode == Mode.BAD_FIRST_INTERMEDIATE) authResponse.addIntermediateCertificate(ByteString.copyFrom(Files.readAllBytes(fixtures.resolve("expired-intermediate.der"))));
            authResponse.addIntermediateCertificate(ByteString.copyFrom(Files.readAllBytes(fixtures.resolve("intermediate.der"))));
            openscreen.cast.proto.CastChannel.DeviceAuthMessage response = mode == Mode.MISSING_RESPONSE ? openscreen.cast.proto.CastChannel.DeviceAuthMessage.newBuilder().build() :
                openscreen.cast.proto.CastChannel.DeviceAuthMessage.newBuilder().setResponse(authResponse).build();
            CastChannel.CastMessage reply = CastChannel.CastMessage.newBuilder().setProtocolVersion(CastChannel.CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId("receiver-0").setDestinationId(challenge.getSourceId()).setPayloadType(CastChannel.CastMessage.PayloadType.BINARY)
                .setNamespace(mode == Mode.WRONG_AUTH_NAMESPACE ? "urn:wrong" : challenge.getNamespace()).setPayloadBinary(response.toByteString()).build();
            BoundedCastFrame.write(socket,reply);
        }
        private static CastChannel.CastMessage receive(Socket socket) throws IOException { return CastChannel.CastMessage.parseFrom(BoundedCastFrame.read(socket,6000,3000)); }
        private static void sendJson(Socket socket, CastChannel.CastMessage request, JsonNode json) throws IOException {
            BoundedCastFrame.write(socket,CastChannel.CastMessage.newBuilder().setProtocolVersion(CastChannel.CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(request.getDestinationId()).setDestinationId(request.getSourceId()).setNamespace(request.getNamespace())
                .setPayloadType(CastChannel.CastMessage.PayloadType.STRING).setPayloadUtf8(JSON.writeValueAsString(json)).build());
        }
        @Override public void close() throws Exception {
            if (peer != null) peer.close(); server.close(); thread.shutdownNow();
            check(thread.awaitTermination(2,TimeUnit.SECONDS),"TLS fixture thread quiesced");
            if (failure != null) throw new AssertionError("Fixture implementation failed",failure);
        }
    }
}
