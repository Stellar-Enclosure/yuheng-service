package top.egon.cola.component.yuheng.edge.service.domain.bo;

import java.time.Instant;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

/** Verified JWT/owner-state facts, never an opaque Refresh-to-principal lookup result. */
public record EdgeRefreshContextBO(EdgeOAuthTargetBO target, String subject, String authorizationId,
                                    String authenticationJti, long generation, Instant authorizationExpiresAt,
                                    Instant authenticationExpiresAt, String browserBindingDigest) {
    public EdgeRefreshContextBO {
        if (target == null || subject == null || subject.isBlank() || subject.length() > 256
                || authorizationId == null || !authorizationId.matches("[1-9][0-9]{0,18}")
                || authenticationJti == null || authenticationJti.isBlank() || authenticationJti.length() > 256
                || generation < 0 || generation == Long.MAX_VALUE
                || authorizationExpiresAt == null || authenticationExpiresAt == null) {
            throw new IllegalArgumentException("Invalid EDGE refresh context");
        }
        EdgeOAuthStateDigests.requireDigest(browserBindingDigest);
    }
}
