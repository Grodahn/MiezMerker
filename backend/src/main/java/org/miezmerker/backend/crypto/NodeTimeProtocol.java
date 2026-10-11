package org.miezmerker.backend.crypto;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;

/** ADR 0017 fixed-width signing contracts; no caller-defined signing bytes. */
public final class NodeTimeProtocol {
    private NodeTimeProtocol() {}
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    @Schema(name="NodeTimeChallengeV1", additionalProperties=Schema.AdditionalPropertiesValue.FALSE, requiredProperties={"nodeId","incarnation","bootCounter","challengeNonce",
        "challengeUptimeMs","devicePublicKey","devicePublicKeyFingerprint","deviceChallengeSignature","version"})
    public record Challenge(String nodeId, String incarnation,
            @Schema(minimum="1",maximum="4294967295") long bootCounter,
            String challengeNonce,
            @Schema(implementation=Long.class,type="integer",format="uint64",minimum="0",
                description="Unsigned 64-bit integer 0..18446744073709551615; clients must preserve integers losslessly") BigInteger challengeUptimeMs, String devicePublicKey,
            String devicePublicKeyFingerprint, String deviceChallengeSignature, int version) {}
    @Schema(name="NodeTimeBootstrapRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"organizationId","registrationState","challenge"})
    public record Request(String organizationId,
            @Schema(allowableValues={"CLAIMED","UNCLAIMED"}) String registrationState, Challenge challenge) {}
    public record Acceptance(String nodeId, String incarnation, long bootCounter,
            String proofDigest, String challengeNonceDigest, BigInteger acceptedUptimeMs,
            String anchorId, String signature, int version) {}

    public static JsonNode json(byte[] bytes) {
        try { return JSON.readTree(bytes); }
        catch (Exception e) { throw new IllegalArgumentException("Malformed JSON"); }
    }
    public static void fields(JsonNode n, String... fields) {
        if (n == null || !n.isObject() || n.size() != fields.length)
            throw new IllegalArgumentException("Unexpected fields");
        for (String f : fields) if (!n.hasNonNull(f)) throw new IllegalArgumentException("Missing field");
    }
    public static String text(JsonNode n, String f) {
        if (!n.path(f).isString()) throw new IllegalArgumentException("Expected string");
        return n.path(f).asText();
    }
    private static BigInteger integer(JsonNode n, String f, BigInteger max) {
        JsonNode v = n.path(f);
        if (!v.isIntegralNumber() || v.bigIntegerValue().signum() < 0
                || v.bigIntegerValue().compareTo(max) > 0) throw new IllegalArgumentException("Invalid integer");
        return v.bigIntegerValue();
    }
    public static Request parseRequest(byte[] body) {
        JsonNode n = json(body);
        fields(n, "organizationId", "registrationState", "challenge");
        String org = text(n, "organizationId"); uuid(org);
        String state = text(n, "registrationState");
        if (!Set.of("CLAIMED", "UNCLAIMED").contains(state)) throw new IllegalArgumentException("Invalid state");
        JsonNode c = n.path("challenge");
        fields(c, "nodeId", "incarnation", "bootCounter", "challengeNonce", "challengeUptimeMs",
                "devicePublicKey", "devicePublicKeyFingerprint", "deviceChallengeSignature", "version");
        Challenge challenge = new Challenge(text(c,"nodeId"), text(c,"incarnation"),
                integer(c,"bootCounter", BigInteger.valueOf(0xffff_ffffL)).longValueExact(),
                text(c,"challengeNonce"), integer(c,"challengeUptimeMs",U64_MAX),
                text(c,"devicePublicKey"), text(c,"devicePublicKeyFingerprint"),
                text(c,"deviceChallengeSignature"), integer(c,"version",BigInteger.valueOf(65535)).intValueExact());
        validate(challenge);
        return new Request(org, state, challenge);
    }
    public static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Noncanonical UUID");
        UUID id = UUID.fromString(value);
        if (id.version() != 4 || id.variant() != 2) throw new IllegalArgumentException("Expected UUIDv4");
        return id;
    }
    public static byte[] decode(String value, int size) {
        if (value == null || value.length() != (size * 8 + 5) / 6 || !value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid encoding");
        byte[] bytes = EcKeyUtils.b64uDecode(value);
        if (bytes.length != size || !EcKeyUtils.b64u(bytes).equals(value))
            throw new IllegalArgumentException("Noncanonical encoding");
        return bytes;
    }
    public static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static ECPublicKey key(Challenge c) {
        byte[] raw = decode(c.devicePublicKey(),65);
        if (raw[0] != 4) throw new IllegalArgumentException("Invalid point");
        return EcKeyUtils.publicFromXY(EcKeyUtils.b64u(Arrays.copyOfRange(raw,1,33)),
                EcKeyUtils.b64u(Arrays.copyOfRange(raw,33,65)));
    }
    public static void validate(Challenge c) {
        if (c == null || c.version() != 1 || c.bootCounter() < 1 || c.bootCounter() > 0xffff_ffffL)
            throw new IllegalArgumentException("Invalid challenge");
        uuid(c.nodeId()); uuid(c.incarnation()); uptime(c.challengeUptimeMs());
        byte[] nonce = decode(c.challengeNonce(),32);
        if (Arrays.equals(nonce,new byte[32])) throw new IllegalArgumentException("Zero nonce");
        decode(c.deviceChallengeSignature(),64); decode(c.devicePublicKeyFingerprint(),32);
        if (!EcKeyUtils.fingerprintOf(key(c)).equals(c.devicePublicKeyFingerprint()))
            throw new IllegalArgumentException("Fingerprint mismatch");
    }
    private static void uptime(BigInteger n) {
        if (n == null || n.signum() < 0 || n.compareTo(U64_MAX) > 0) throw new IllegalArgumentException("Invalid uptime");
    }
    private static void putUuid(ByteBuffer b, String id) {
        UUID u = uuid(id);
        b.put(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
    }
    private static ByteBuffer prefix(String domain, String node, String incarnation, long boot, int size) {
        byte[] d = domain.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(d.length + size).order(ByteOrder.LITTLE_ENDIAN);
        b.put(d).putShort((short)1); putUuid(b,node); putUuid(b,incarnation); b.putInt((int)boot);
        return b;
    }
    public static byte[] challengeBytes(Challenge c) {
        validate(c);
        return prefix("MM-NODE-TIME-CHALLENGE-v1\n",c.nodeId(),c.incarnation(),c.bootCounter(),143)
                .putLong(c.challengeUptimeMs().longValue()).put(decode(c.challengeNonce(),32))
                .put(decode(c.devicePublicKey(),65)).array();
    }
    public static void verifyChallenge(Challenge c) {
        if (!EcKeyUtils.verifyRaw(key(c),challengeBytes(c),decode(c.deviceChallengeSignature(),64)))
            throw new IllegalArgumentException("Invalid challenge signature");
    }
    public static byte[] acceptanceBytes(Challenge c, String proof, Acceptance a) {
        validate(c); uptime(a.acceptedUptimeMs()); uuid(a.anchorId());
        if (a.version() != 1 || !c.nodeId().equals(a.nodeId()) || !c.incarnation().equals(a.incarnation())
                || c.bootCounter() != a.bootCounter() || a.acceptedUptimeMs().compareTo(c.challengeUptimeMs()) < 0
                || !EcKeyUtils.b64u(digest(decode(c.challengeNonce(),32))).equals(a.challengeNonceDigest())
                || !EcKeyUtils.b64u(digest(proof.getBytes(StandardCharsets.UTF_8))).equals(a.proofDigest()))
            throw new IllegalArgumentException("Acceptance binding mismatch");
        return prefix("MM-NODE-TIME-ACCEPT-v1\n",c.nodeId(),c.incarnation(),c.bootCounter(),126)
                .put(decode(a.challengeNonceDigest(),32)).put(decode(a.proofDigest(),32))
                .putLong(a.acceptedUptimeMs().longValue()).put(ByteBuffer.allocate(16)
                    .putLong(uuid(a.anchorId()).getMostSignificantBits()).putLong(uuid(a.anchorId()).getLeastSignificantBits()).array()).array();
    }
}
