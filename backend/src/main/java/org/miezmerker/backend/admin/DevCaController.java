package org.miezmerker.backend.admin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A deliberately opt-in DEV-only public CA download behind the existing
 * /admin/** authenticated ADMIN role gate. This is not a trust bootstrap:
 * operators must verify the certificate fingerprint by an independent channel.
 */
@Profile("dev")
@ConditionalOnProperty(name = "miezmerker.dev-ca.enabled", havingValue = "true")
@RestController
@RequestMapping("/admin/dev")
public class DevCaController {
    private static final Logger LOG = LoggerFactory.getLogger(DevCaController.class);
    private static final Pattern CERT_PEM = Pattern.compile(
            "\\A-----BEGIN CERTIFICATE-----\\s+([A-Za-z0-9+/=\\r\\n]+)-----END CERTIFICATE-----\\s*\\z");
    private static final int MAX_CERT_BYTES = 16 * 1024;
    private final byte[] publicCaDer;

    public DevCaController(@Value("${miezmerker.dev-ca.public-cert-path:}") String certPath) {
        if (certPath == null || certPath.isBlank()) {
            throw new IllegalStateException("DEV CA enabled but public-cert-path is missing");
        }
        try {
            Path path = Path.of(certPath).toAbsolutePath().normalize();
            if (!"rootCA.pem".equals(path.getFileName().toString())
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > MAX_CERT_BYTES) {
                throw new IllegalStateException("Expected a regular, bounded mkcert rootCA.pem (no symlinks)");
            }
            byte[] pemBytes = Files.readAllBytes(path);
            String pem = new String(pemBytes, StandardCharsets.US_ASCII);
            if (!CERT_PEM.matcher(pem).matches()) {
                throw new IllegalStateException("Expected exactly one public PEM CERTIFICATE (no private key)");
            }
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pemBytes));
            if (ca.getBasicConstraints() < 0) {
                throw new IllegalStateException("Configured certificate is not a CA");
            }
            publicCaDer = ca.getEncoded();
            String sha256 = HexFormat.ofDelimiter(":").withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(publicCaDer));
            LOG.warn("DEV ONLY mkcert CA download enabled under /admin/dev/root-ca.crt; "
                    + "verify on the operator's console using an independent channel. "
                    + "CA SHA-256 fingerprint: {}", sha256);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load public mkcert rootCA.pem", e);
        }
    }

    @GetMapping("/root-ca.crt")
    public ResponseEntity<byte[]> download() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/x-x509-ca-cert"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("miezmerker-dev-rootCA.crt").build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        return ResponseEntity.ok().headers(headers).body(publicCaDer.clone());
    }
}
