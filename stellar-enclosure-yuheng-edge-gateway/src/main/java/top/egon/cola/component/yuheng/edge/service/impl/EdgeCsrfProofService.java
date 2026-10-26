package top.egon.cola.component.yuheng.edge.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceAccess;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;

/** Signed double-submit proof bound to the verified application authorization, not a static username. */
@Component
public class EdgeCsrfProofService {
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final EdgeOAuthStateCipher cipher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EdgeCsrfProofService(EdgeOAuthStateCipher cipher, @Qualifier("edgeClock") Clock clock) {
        this.cipher = cipher;
        this.clock = clock;
    }

    public Proof issue(String contextDigest, Instant authorizationExpiry) {
        EdgeOAuthStateDigests.requireDigest(contextDigest);
        Instant now = clock.instant();
        if (authorizationExpiry == null || !authorizationExpiry.isAfter(now)) {
            throw new EdgeOAuthStateException("EDGE_CSRF_CONTEXT_EXPIRED");
        }
        Instant expiry = now.plus(LIFETIME);
        if (expiry.isAfter(authorizationExpiry)) expiry = authorizationExpiry;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String payload = "v1~" + cipher.activeKeyId() + "~" + now.getEpochSecond() + "~" + expiry.getEpochSecond()
                + "~" + nonce + "~" + contextDigest;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.signCsrf(cipher.activeKeyId(), payload));
        return new Proof("X-CSRF-TOKEN", payload + "~" + signature, expiry);
    }

    public void verify(String cookie, String header, String contextDigest) {
        if (cookie == null || header == null || cookie.length() > 1024
                || !EdgeOAuthStateDigests.equal(cookie, header)) throw invalid();
        String[] parts = header.split("~", -1);
        if (parts.length != 7 || !"v1".equals(parts[0]) || !parts[1].matches("[A-Za-z0-9._-]{1,64}")
                || !parts[2].matches("[0-9]{1,12}") || !parts[3].matches("[0-9]{1,12}")
                || !parts[4].matches("[A-Za-z0-9_-]{43}") || !parts[6].matches("[A-Za-z0-9_-]{43}")
                || !EdgeOAuthStateDigests.equal(parts[5], contextDigest)) throw invalid();
        long issuedAt = Long.parseLong(parts[2]);
        long expiresAt = Long.parseLong(parts[3]);
        long now = clock.instant().getEpochSecond();
        if (issuedAt > now + 30 || expiresAt <= now || expiresAt <= issuedAt
                || expiresAt - issuedAt > LIFETIME.toSeconds()) throw invalid();
        byte[] signature;
        try {
            signature = cipher.signCsrf(parts[1], String.join("~", java.util.Arrays.copyOf(parts, 6)));
        } catch (EdgeOAuthStateException unknownKey) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(signature, Base64.getUrlDecoder().decode(parts[6]))) throw invalid();
    }

    public static String context(EdgeOAuthTargetBO target, String subject, String authorizationId, String authenticationJti) {
        return EdgeOAuthStateDigests.fields("edge-csrf-context-v1", target.digest(), subject, authorizationId, authenticationJti);
    }

    private static EdgeOAuthStateException invalid() { return new EdgeOAuthStateException("EDGE_CSRF_INVALID"); }

    public record Proof(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.Proof.headerName", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.Proof.headerName")
        String headerName,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.Proof.token", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.Proof.token")
        String token,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.Proof.expiresAt", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.Proof.expiresAt")
        Instant expiresAt) {
        @Override
        public String toString() { return "Proof[headerName=" + headerName + ", token=<redacted>, expiresAt=" + expiresAt + "]"; }
    }
}
