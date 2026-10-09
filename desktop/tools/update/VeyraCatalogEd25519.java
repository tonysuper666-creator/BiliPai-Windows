/* Source-only leaf. JDK 21; no DPAPI provider, network, file key or key generation. */
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

class VeyraCatalogEd25519 {
    static final String KEY_ID = "veyra-compatible-v1-102b3faa4e9e82a5";
    static final String SPKI = "MCowBQYDK2VwAyEAxA5zYD641A+LJEOBdlc5Dg9I6szOsSArcBjLEapZ6Cc=";
    static final int MAGIC = 0x42564331;
    static byte[] field(DataInputStream input, int min, int max) throws Exception {
        int size = input.readInt();
        if (size < min || size > max) throw new IllegalArgumentException();
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new IllegalArgumentException();
        return bytes;
    }
    static boolean verifies(byte[] payload, byte[] signature) throws Exception {
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(SPKI))));
        verifier.update(payload); // Exactly consumer payload bytes, no extra context/prefix.
        return signature.length == 64 && verifier.verify(signature);
    }
    public static void main(String[] args) {
        byte[] payload = null, privateDer = null, signature = null;
        int exitCode = 0;
        try {
            if (args.length != 1 || !(args[0].equals("sign") || args[0].equals("verify")))
                throw new IllegalArgumentException();
            if (Runtime.version().feature() < 21) throw new IllegalArgumentException();
            var input = new DataInputStream(System.in);
            if (input.readInt() != MAGIC) throw new IllegalArgumentException();
            payload = field(input, 1, 128 * 1024);
            if (args[0].equals("sign")) {
                // A future independently reviewed local provider supplies PKCS8 DER
                // through the pipe. No caller key path, DPAPI blob or argv secret.
                privateDer = field(input, 1, 4096);
                if (input.read() != -1) throw new IllegalArgumentException();
                var signer = Signature.getInstance("Ed25519");
                signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(
                        new PKCS8EncodedKeySpec(privateDer)));
                signer.update(payload);
                signature = signer.sign();
                if (!verifies(payload, signature)) throw new IllegalArgumentException();
                var encoder = Base64.getEncoder();
                String result = "{\"schema\":1,\"keyId\":\"" + KEY_ID +
                        "\",\"payloadBase64\":\"" + encoder.encodeToString(payload) +
                        "\",\"signatureBase64\":\"" + encoder.encodeToString(signature) + "\"}\n";
                System.out.write(result.getBytes(StandardCharsets.UTF_8));
            } else {
                signature = field(input, 64, 64);
                if (input.read() != -1 || !verifies(payload, signature))
                    throw new IllegalArgumentException();
                System.out.print("VERIFIED\n");
            }
        } catch (Throwable rejected) {
            // Never print key bytes, payload, input paths, provider output or exception.
            System.err.println("CATALOG_SIGNATURE_REJECTED");
            exitCode = 2;
        } finally {
            if (privateDer != null) Arrays.fill(privateDer, (byte) 0);
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (signature != null) Arrays.fill(signature, (byte) 0);
            // Provider/JDK key objects may retain copies: no claim of secure heap erasure.
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}
