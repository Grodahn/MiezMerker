package org.miezmerker.backend.crypto;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * P-256 (secp256r1) helpers shared by offline credentials (#17) and node claim (#18).
 *
 * <p>Wire rules (see ADR-0012):
 * <ul>
 *   <li>Curve: NIST P-256 / secp256r1, ECDSA with SHA-256 (ES256).</li>
 *   <li>Public key coordinates {@code x}/{@code y} are 32-byte big-endian values,
 *       base64url-encoded without padding.</li>
 *   <li>Key fingerprint: SHA-256 over uncompressed point {@code 0x04 || x || y},
 *       base64url-encoded without padding (43 chars).</li>
 *   <li>BLE proof-of-possession signatures are raw 64-byte {@code r || s} (IEEE P1363),
 *       matching Web Crypto {@code subtle.sign("ECDSA")} output. Java JCA uses DER;
 *       use {@link #rawToDer} / {@link #derToRaw} at the boundary.</li>
 *   <li>JWT/JWS signatures (ES256) internally use the same raw form, base64url-encoded
 *       by the JOSE library. No custom crypto is invented.</li>
 * </ul>
 */
public final class EcKeyUtils {
    private EcKeyUtils() {}

    public static final String CURVE = "secp256r1";
    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64U_DEC = Base64.getUrlDecoder();

    public static KeyPair generateP256() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate P-256 keypair", e);
        }
    }

    public static byte[] toFixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    public static String b64u(byte[] bytes) {
        return B64U.encodeToString(bytes);
    }

    public static byte[] b64uDecode(String s) {
        return B64U_DEC.decode(s);
    }

    public static String xOf(ECPublicKey key) {
        return b64u(toFixed32(key.getW().getAffineX()));
    }

    public static String yOf(ECPublicKey key) {
        return b64u(toFixed32(key.getW().getAffineY()));
    }

    public static ECPublicKey publicFromXY(String xB64u, String yB64u) {
        try {
            byte[] xb = b64uDecode(xB64u);
            byte[] yb = b64uDecode(yB64u);
            if (xb.length != 32 || yb.length != 32) {
                throw new IllegalArgumentException("P-256 coordinates must be 32 bytes");
            }
            ECParameterSpec params = p256Params();
            ECPoint point = new ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
            // KeyFactory can construct off-curve points; validate registration explicitly.
            BigInteger p = ((java.security.spec.ECFieldFp) params.getCurve().getField()).getP();
            BigInteger x = point.getAffineX(), y = point.getAffineY();
            if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                    || !y.multiply(y).mod(p).equals(x.multiply(x).multiply(x)
                        .add(params.getCurve().getA().multiply(x))
                        .add(params.getCurve().getB()).mod(p))) {
                throw new IllegalArgumentException("Public key is not a P-256 point");
            }
            if (!b64u(xb).equals(xB64u) || !b64u(yb).equals(yB64u)) {
                throw new IllegalArgumentException("Noncanonical P-256 coordinates");
            }
            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(point, params));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid P-256 public key", e);
        }
    }

    public static String fingerprintOf(ECPublicKey key) {
        byte[] x = toFixed32(key.getW().getAffineX());
        byte[] y = toFixed32(key.getW().getAffineY());
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(x, 0, uncompressed, 1, 32);
        System.arraycopy(y, 0, uncompressed, 33, 32);
        return fingerprintOfUncompressed(uncompressed);
    }

    public static String fingerprintOfXY(String xB64u, String yB64u) {
        byte[] x = b64uDecode(xB64u);
        byte[] y = b64uDecode(yB64u);
        if (x.length != 32 || y.length != 32) {
            throw new IllegalArgumentException("P-256 coordinates must be 32 bytes");
        }
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(x, 0, uncompressed, 1, 32);
        System.arraycopy(y, 0, uncompressed, 33, 32);
        return fingerprintOfUncompressed(uncompressed);
    }

    private static String fingerprintOfUncompressed(byte[] uncompressed) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return b64u(sha.digest(uncompressed));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verify ECDSA/SHA256 with a raw 64-byte {@code r||s} signature. */
    public static boolean verifyRaw(ECPublicKey key, byte[] message, byte[] rawSignature) {
        try {
            if (rawSignature == null || rawSignature.length != 64) {
                return false;
            }
            byte[] der = rawToDer(rawSignature);
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(key);
            sig.update(message);
            return sig.verify(der);
        } catch (Exception e) {
            return false;
        }
    }

    /** Sign with ECDSA/SHA256, returning raw 64-byte {@code r||s}. */
    public static byte[] signRaw(PrivateKey privateKey, byte[] message) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(privateKey, new SecureRandom());
            sig.update(message);
            byte[] der = sig.sign();
            return derToRaw(der);
        } catch (Exception e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public static byte[] rawToDer(byte[] raw) {
        if (raw.length != 64) {
            throw new IllegalArgumentException("raw signature must be 64 bytes");
        }
        byte[] r = Arrays.copyOfRange(raw, 0, 32);
        byte[] s = Arrays.copyOfRange(raw, 32, 64);
        BigInteger ri = new BigInteger(1, r);
        BigInteger si = new BigInteger(1, s);
        byte[] rb = stripLeadingZeroes(ri.toByteArray());
        byte[] sb = stripLeadingZeroes(si.toByteArray());
        // Ensure positive INTEGER encoding (prepend 0x00 if high bit set).
        rb = toDerIntegerBytes(rb);
        sb = toDerIntegerBytes(sb);
        int len = 2 + rb.length + 2 + sb.length;
        byte[] der;
        int off;
        if (len < 128) {
            der = new byte[2 + len];
            der[0] = 0x30;
            der[1] = (byte) len;
            off = 2;
        } else {
            der = new byte[3 + len];
            der[0] = 0x30;
            der[1] = (byte) 0x81;
            der[2] = (byte) len;
            off = 3;
        }
        der[off++] = 0x02;
        der[off++] = (byte) rb.length;
        System.arraycopy(rb, 0, der, off, rb.length);
        off += rb.length;
        der[off++] = 0x02;
        der[off++] = (byte) sb.length;
        System.arraycopy(sb, 0, der, off, sb.length);
        return der;
    }

    public static byte[] derToRaw(byte[] der) {
        // Minimal DER parser for SEQUENCE { INTEGER r, INTEGER s }.
        int off = 0;
        if (der[off++] != 0x30) {
            throw new IllegalArgumentException("Not a DER SEQUENCE");
        }
        int seqLen = der[off++] & 0xff;
        if (seqLen == 0x81) {
            seqLen = der[off++] & 0xff;
        }
        if (der[off++] != 0x02) {
            throw new IllegalArgumentException("Expected INTEGER r");
        }
        int rLen = der[off++] & 0xff;
        byte[] r = Arrays.copyOfRange(der, off, off + rLen);
        off += rLen;
        if (der[off++] != 0x02) {
            throw new IllegalArgumentException("Expected INTEGER s");
        }
        int sLen = der[off++] & 0xff;
        byte[] s = Arrays.copyOfRange(der, off, off + sLen);
        byte[] out = new byte[64];
        byte[] rn = stripLeadingZeroes(r);
        byte[] sn = stripLeadingZeroes(s);
        if (rn.length > 32 || sn.length > 32) {
            throw new IllegalArgumentException("r/s out of range for P-256");
        }
        System.arraycopy(rn, 0, out, 32 - rn.length, rn.length);
        System.arraycopy(sn, 0, out, 64 - sn.length, sn.length);
        return out;
    }

    private static byte[] stripLeadingZeroes(byte[] v) {
        int i = 0;
        while (i < v.length - 1 && v[i] == 0) {
            i++;
        }
        return i == 0 ? v : Arrays.copyOfRange(v, i, v.length);
    }

    private static byte[] toDerIntegerBytes(byte[] v) {
        byte[] stripped = stripLeadingZeroes(v);
        if (stripped.length > 0 && (stripped[0] & 0x80) != 0) {
            byte[] out = new byte[stripped.length + 1];
            out[0] = 0x00;
            System.arraycopy(stripped, 0, out, 1, stripped.length);
            return out;
        }
        if (stripped.length == 0) {
            return new byte[]{0x00};
        }
        return stripped;
    }

    private static ECParameterSpec p256Params() throws Exception {
        // Derive standard P-256 params from a generated key (avoids hardcoding).
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec(CURVE));
        ECPublicKey sample = (ECPublicKey) gen.generateKeyPair().getPublic();
        return sample.getParams();
    }

    public static ECPrivateKey privateFromPkcs8B64(String pkcs8B64) {
        try {
            byte[] der = Base64.getDecoder().decode(pkcs8B64.trim());
            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPrivateKey) kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PKCS8 private key", e);
        }
    }

    public static String pkcs8B64(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
