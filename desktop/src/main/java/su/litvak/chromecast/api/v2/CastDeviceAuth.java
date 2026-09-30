/* Windows platform binding derived from Open Screen's device-auth contract.
 * Open Screen Copyright 2019 The Chromium Authors; BSD-3-Clause (licenses/).
 * New JVM implementation; keeps trusted-chain and signature/TLS binding checks.
 */
package su.litvak.chromecast.api.v2;

import com.google.protobuf.ByteString;
import openscreen.cast.proto.CastChannel;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;

public final class CastDeviceAuth {
    private static final byte[] AUDIO_ONLY_OID = {0x2b, 0x06, 0x01, 0x04, 0x01, (byte)0xd6, 0x79, 0x02, 0x05, 0x02};
    private final Set<TrustAnchor> anchors;
    private final Clock clock;

    CastDeviceAuth(Collection<X509Certificate> roots, Clock clock) {
        if (roots.isEmpty()) throw new IllegalArgumentException("No Cast trust anchors");
        Set<TrustAnchor> set = new HashSet<>();
        roots.forEach(cert -> set.add(new TrustAnchor(cert, null)));
        this.anchors = Collections.unmodifiableSet(set);
        this.clock = clock;
    }

    public static CastDeviceAuth production() throws GeneralSecurityException, IOException {
        List<X509Certificate> roots = new ArrayList<>();
        for (String path : new String[]{"/cast-v2/cast-root.der", "/cast-v2/eureka-root.der"}) {
            try (InputStream input = CastDeviceAuth.class.getResourceAsStream(path)) {
                if (input == null) throw new IOException("Missing pinned Cast trust anchor");
                roots.add(parse(input.readAllBytes()));
            }
        }
        return new CastDeviceAuth(roots, Clock.systemUTC());
    }

    byte[] newNonce() { byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce); return nonce; }
    ByteString challenge(byte[] nonce) {
        return CastChannel.DeviceAuthMessage.newBuilder().setChallenge(CastChannel.AuthChallenge.newBuilder()
            .setSignatureAlgorithm(CastChannel.SignatureAlgorithm.RSASSA_PKCS1v15)
            .setHashAlgorithm(CastChannel.HashAlgorithm.SHA256).setSenderNonce(ByteString.copyFrom(nonce))).build().toByteString();
    }

    /** A temporary TLS certificate is accepted only after this returns successfully. */
    boolean verify(byte[] replyBytes, byte[] tlsPeerDer, byte[] expectedNonce) throws GeneralSecurityException, IOException {
        CastChannel.DeviceAuthMessage message = CastChannel.DeviceAuthMessage.parseFrom(replyBytes);
        if (message.hasError() || !message.hasResponse()) throw rejected("missing response");
        CastChannel.AuthResponse reply = message.getResponse();
        if (!reply.hasSenderNonce() || !MessageDigest.isEqual(expectedNonce, reply.getSenderNonce().toByteArray()))
            throw rejected("nonce mismatch");
        if (reply.getSignatureAlgorithm() != CastChannel.SignatureAlgorithm.RSASSA_PKCS1v15)
            throw rejected("signature algorithm");
        String signatureAlgorithm;
        if (reply.getHashAlgorithm() == CastChannel.HashAlgorithm.SHA256) signatureAlgorithm = "SHA256withRSA";
        else if (reply.getHashAlgorithm() == CastChannel.HashAlgorithm.SHA1) signatureAlgorithm = "SHA1withRSA";
        else throw rejected("digest algorithm");
        Date now = Date.from(clock.instant());
        X509Certificate tls = parse(tlsPeerDer);
        tls.checkValidity(now);
        if (tls.getNotAfter().toInstant().isAfter(clock.instant().plus(Duration.ofDays(4))))
            throw rejected("TLS remaining validity");
        X509Certificate leaf = parse(reply.getClientAuthCertificate().toByteArray());
        boolean[] usage = leaf.getKeyUsage();
        if (usage == null || usage.length == 0 || !usage[0]) throw rejected("digital signature key usage");
        List<X509Certificate> intermediates = new ArrayList<>();
        if (reply.getIntermediateCertificateCount() > 8) throw rejected("certificate count");
        for (ByteString der : reply.getIntermediateCertificateList()) intermediates.add(parse(der.toByteArray()));
        List<X509Certificate> path = findTrustedPath(leaf, intermediates, now);
        if (path == null) throw rejected("untrusted certificate chain");
        Signature verifier = Signature.getInstance(signatureAlgorithm);
        verifier.initVerify(leaf.getPublicKey());
        verifier.update(reply.getSenderNonce().toByteArray());
        verifier.update(tlsPeerDer);
        if (!verifier.verify(reply.getSignature().toByteArray())) throw rejected("signature mismatch");
        for (X509Certificate cert : path) if (hasAudioOnlyPolicy(cert)) return true;
        return false;
    }

    private List<X509Certificate> findTrustedPath(X509Certificate leaf, List<X509Certificate> candidates, Date now)
        throws GeneralSecurityException, IOException {
        return walk(new ArrayList<>(List.of(leaf)), candidates, now);
    }

    private List<X509Certificate> walk(List<X509Certificate> path, List<X509Certificate> candidates, Date now)
        throws GeneralSecurityException, IOException {
        X509Certificate current = path.get(path.size() - 1);
        current.checkValidity(now);
        if (!(current.getPublicKey() instanceof RSAPublicKey) || ((RSAPublicKey)current.getPublicKey()).getModulus().bitLength() < 2048)
            throw rejected("public key constraints");
        for (TrustAnchor anchor : anchors) {
            X509Certificate root = anchor.getTrustedCert();
            if (!current.getIssuerX500Principal().equals(root.getSubjectX500Principal())) continue;
            try {
                current.verify(root.getPublicKey());
                root.checkValidity(now);
                PKIXParameters params = new PKIXParameters(Set.of(anchor));
                params.setDate(now); params.setRevocationEnabled(false); // Open Screen's optional CRL baseline.
                CertPath explicit = CertificateFactory.getInstance("X.509").generateCertPath(path);
                CertPathValidator.getInstance("PKIX").validate(explicit, params);
                List<X509Certificate> trusted = new ArrayList<>(path); trusted.add(root);
                return trusted;
            } catch (GeneralSecurityException ignored) { /* Try only supplied candidates / pinned roots; no AIA HTTP. */ }
        }
        for (X509Certificate cert : candidates) {
            if (path.contains(cert) || !current.getIssuerX500Principal().equals(cert.getSubjectX500Principal())) continue;
            try { current.verify(cert.getPublicKey()); } catch (GeneralSecurityException ignored) { continue; }
            List<X509Certificate> next = new ArrayList<>(path); next.add(cert);
            try {
                List<X509Certificate> found = walk(next, candidates, now);
                if (found != null) return found;
            } catch (GeneralSecurityException rejectedCandidate) {
                // An expired cross-signed candidate must not hide a later valid path.
            }
        }
        return null;
    }

    private static boolean hasAudioOnlyPolicy(X509Certificate cert) throws IOException {
        byte[] wrapped = cert.getExtensionValue("2.5.29.32");
        if (wrapped == null) return false;
        Der outer = new Der(wrapped); byte[] encoded = outer.read(0x04); outer.end();
        Der policies = new Der(new Der(encoded).read(0x30));
        while (policies.remaining()) {
            Der policy = new Der(policies.read(0x30));
            if (Arrays.equals(AUDIO_ONLY_OID, policy.read(0x06))) return true;
        }
        return false;
    }
    private static final class Der {
        private final byte[] bytes; private int offset;
        Der(byte[] bytes) { this.bytes = bytes; }
        boolean remaining() { return offset < bytes.length; }
        void end() throws IOException { if (remaining()) throw new IOException("Invalid policy DER"); }
        byte[] read(int tag) throws IOException {
            if (offset >= bytes.length || (bytes[offset++] & 255) != tag || offset >= bytes.length) throw new IOException("Invalid policy DER");
            int length = bytes[offset++] & 255;
            if ((length & 128) != 0) {
                int count = length & 127; if (count == 0 || count > 3 || offset + count > bytes.length) throw new IOException("Invalid policy DER");
                length = 0; for (int i=0; i<count; i++) length = (length << 8) | (bytes[offset++] & 255);
            }
            if (length > bytes.length - offset) throw new IOException("Invalid policy DER");
            byte[] value = Arrays.copyOfRange(bytes, offset, offset + length); offset += length; return value;
        }
    }
    static X509Certificate parse(byte[] bytes) throws CertificateException {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        X509Certificate cert = (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);
        if (input.available() != 0) throw new CertificateException("Trailing certificate bytes");
        return cert;
    }
    private static GeneralSecurityException rejected(String reason) { return new GeneralSecurityException("Cast device authentication rejected: " + reason); }
}
