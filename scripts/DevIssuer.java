import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

/** Private subprocess protocol for PowerShell 5.1. Never pass keys as CLI arguments. */
class DevIssuer {
    static void requireCompleteDer(byte[] der) {
        if (der.length < 2 || der[0] != 0x30) throw new IllegalArgumentException();
        int prefix = 2, first = der[1] & 255;
        long length = first;
        if (first >= 128) {
            int count = first & 127;
            if (count == 0 || count > 4 || der.length < 2 + count || der[2] == 0)
                throw new IllegalArgumentException();
            prefix += count; length = 0;
            for (int i = 2; i < prefix; i++) length = (length << 8) | (der[i] & 255);
            if (length < 128) throw new IllegalArgumentException();
        }
        if (length != der.length - prefix) throw new IllegalArgumentException();
    }
    static byte[] coordinate(BigInteger value) {
        byte[] encoded = value.toByteArray(), result = new byte[32];
        int count = Math.min(encoded.length, result.length);
        System.arraycopy(encoded, encoded.length - count, result, result.length - count, count);
        return result;
    }
    public static void main(String[] args) {
        String phase = "input";
        try {
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String privateText = input.readLine(), publicText = input.readLine();
            // .NET Framework's redirected stdin writer may emit the console's
            // UTF-8 preamble before the caller writes its exact ASCII lines.
            if (privateText != null && privateText.startsWith("\uFEFF")) privateText = privateText.substring(1);
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair;
            if ((privateText == null || privateText.isEmpty()) && (publicText == null || publicText.isEmpty())) {
                pair = generator.generateKeyPair();
            } else {
                phase = "Base64 decoding";
                var factory = KeyFactory.getInstance("EC");
                byte[] privateDer = Base64.getDecoder().decode(privateText);
                byte[] publicDer = Base64.getDecoder().decode(publicText);
                phase = "private DER framing";
                requireCompleteDer(privateDer);
                phase = "public DER framing";
                requireCompleteDer(publicDer);
                phase = "public key import";
                var publicKey = factory.generatePublic(new X509EncodedKeySpec(publicDer));
                phase = "private key import";
                pair = new KeyPair(publicKey, factory.generatePrivate(new PKCS8EncodedKeySpec(privateDer)));
                // JDK providers may normalize valid PKCS8 encodings differently.
                // Validate framing, curve and signing consistency, not re-encoding equality.
            }
            phase = "P-256 validation";
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            var expected = parameters.getParameterSpec(ECParameterSpec.class);
            for (var key : List.of((ECKey) pair.getPrivate(), (ECKey) pair.getPublic())) {
                var actual = key.getParams();
                if (!actual.getCurve().equals(expected.getCurve()) || !actual.getOrder().equals(expected.getOrder())
                        || !actual.getGenerator().equals(expected.getGenerator()) || actual.getCofactor() != 1)
                    throw new IllegalArgumentException();
            }
            phase = "signature consistency";
            var signature = Signature.getInstance("SHA256withECDSA");
            byte[] probe = "miezmerker-local-issuer-check".getBytes(StandardCharsets.US_ASCII);
            signature.initSign(pair.getPrivate()); signature.update(probe);
            byte[] signed = signature.sign();
            signature.initVerify(pair.getPublic()); signature.update(probe);
            if (!signature.verify(signed)) throw new IllegalArgumentException();
            var publicKey = (ECPublicKey) pair.getPublic();
            byte[] point = new byte[65]; point[0] = 4;
            System.arraycopy(coordinate(publicKey.getW().getAffineX()), 0, point, 1, 32);
            System.arraycopy(coordinate(publicKey.getW().getAffineY()), 0, point, 33, 32);
            // Captured private pipe output only; caller must never log these lines.
            System.out.println(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            System.out.println(HexFormat.of().formatHex(point));
            System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(point)));
        } catch (Exception error) {
            System.err.println("Invalid/mismatched P-256 issuer pair at " + phase + " ("
                    + error.getClass().getSimpleName() + "); no replacement performed.");
            System.exit(1);
        }
    }
}
