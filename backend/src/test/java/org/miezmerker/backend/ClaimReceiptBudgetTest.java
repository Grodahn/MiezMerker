package org.miezmerker.backend;

import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("auth")
@Tag("node")
class ClaimReceiptBudgetTest {
    static final Path FIXTURE = Path.of("..", "protocol", "fixtures", "claim-receipt-budget-v1.json");

    @Test
    void serializedUtf8BudgetAndSignedInterop() throws Exception {
        var random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed("claim-budget-v1-test-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), random);
        var pair = generator.generateKeyPair();
        var issuer = CredentialIssuerService.withStableKey((ECPrivateKey) pair.getPrivate(),
                (ECPublicKey) pair.getPublic(), 86400);
        String x = issuer.publicXB64u(), y = issuer.publicYB64u();
        String fp = EcKeyUtils.fingerprintOfXY(x, y);
        var node = UUID.fromString("44444444-4444-4444-8444-444444444444");
        var org = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var now = Instant.parse("2026-10-02T00:00:00Z");
        String emoji = new String(Character.toChars(0x1f63a));
        String control = Character.toString(1);
        for (String unit : List.of("a", "ä", emoji, "\"", "\\", control)) {
            String name = unit.repeat(255 / unit.length()) + (unit.length() == 2 ? "a" : "");
            String contact = unit.repeat(500 / unit.length());
            try {
                String jwt = issuer.issueClaimReceipt(node, org, "org", name, contact, x, y, fp, now);
                assertTrue(CredentialIssuerService.claimReceiptFits(jwt));
                assertEquals(name, issuer.verify(jwt).getStringClaim("org_name"));
                assertEquals(contact, issuer.verify(jwt).getStringClaim("contact"));
            } catch (CredentialIssuerService.ClaimReceiptTooLargeException e) {
                assertEquals(control, unit);
            }
        }
        String name = control.repeat(255);
        String contact = control.repeat(81);
        String last = null, lastContact = null;
        boolean rejected = false;
        for (int i = 0; i <= 419; ++i) {
            String candidate = contact + "a".repeat(i);
            try {
                last = issuer.issueClaimReceipt(node, org, "org", name, candidate, x, y, fp, now);
                lastContact = candidate;
            } catch (CredentialIssuerService.ClaimReceiptTooLargeException e) {
                assertNotNull(last);
                assertEquals(lastContact.length() + 1, candidate.length());
                rejected = true;
                break;
            }
        }
        assertTrue(rejected, "last JWS bytes=" + last.length() + ", contact chars=" + lastContact.length());
        assertTrue(last.length() >= 4094 && last.length() <= 4096);
        String numbered = issuer.issueClaimReceipt(node, org, "org", name, lastContact,
                x, y, fp, now, null, "猫".repeat(100));
        assertTrue(CredentialIssuerService.claimReceiptFits(numbered));
        assertNull(issuer.verify(numbered).getStringClaim("display_name"));
        assertFalse(CredentialIssuerService.claimReceiptFits(last + "x".repeat(4097 - last.length())));
        assertThrows(CredentialIssuerService.ClaimReceiptTooLargeException.class,
                () -> issuer.issueClaimReceipt(node, org, "org", control.repeat(255),
                        control.repeat(500), x, y, fp, now));
        assertThrows(CredentialIssuerService.ClaimReceiptTooLargeException.class,
                () -> issuer.issueClaimReceipt(node, org, "org", "nul" + Character.toString(0),
                        "contact", x, y, fp, now));
        var mapper = new tools.jackson.databind.ObjectMapper();
        var fixture = mapper.createObjectNode();
        fixture.put("issuer_x", x); fixture.put("issuer_y", y);
        fixture.put("receipt", last); fixture.put("name", name); fixture.put("contact", lastContact);
        fixture.put("now", now.getEpochSecond());
        fixture.put("utf8_receipt", issuer.issueClaimReceipt(node, org, "org", "ä".repeat(255),
                emoji.repeat(250), x, y, fp, now));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "claim-receipt-budget-v1.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixture) + "\n");
        {
            var committed = mapper.readTree(Files.readString(FIXTURE));
            assertEquals(x, committed.get("issuer_x").asText());
            var claims = issuer.verify(committed.get("receipt").asText());
            assertEquals(name, claims.getStringClaim("org_name"));
            assertEquals(lastContact, claims.getStringClaim("contact"));
            var utf8 = issuer.verify(committed.get("utf8_receipt").asText());
            assertEquals("ä".repeat(255), utf8.getStringClaim("org_name"));
            assertEquals(emoji.repeat(250), utf8.getStringClaim("contact"));
        }
    }
}
