package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credentials")
public class IssuerController {
    private final CredentialIssuerService issuer;

    public IssuerController(CredentialIssuerService issuer) {
        this.issuer = issuer;
    }

    @Schema(name = "IssuerView")
    public record IssuerView(String issuer, String keyId, String algorithm,
            Map<String, String> jwk, long credentialTtlSeconds) {}

    @GetMapping(value = "/issuer", produces = "application/json")
    @Operation(operationId = "getCredentialIssuer",
            summary = "Backend trust anchor for offline node verification (public key only)")
    public IssuerView issuer() {
        return new IssuerView(CredentialIssuerService.ISSUER, CredentialIssuerService.KEY_ID,
                "ES256", issuer.publicJwk(), issuer.getTtlSeconds());
    }
}
