package top.egon.cola.component.yuheng.edge.service.domain.bo;

import java.time.Instant;
import java.util.UUID;

/** Durable denial and delivery metadata; no plaintext credential is part of status. */
public record EdgeRevocationIntentBO(UUID intentId, EdgeOAuthTargetBO target, String requestDigest,
                                     String tokenDigest, String sourceKind, String authorizationId,
                                     String authenticationJti, String browserBindingDigest, String status,
                                     String reasonCode, int attempts, UUID leaseOwner, Instant leaseExpiresAt,
                                     Instant dispatchDeadlineAt, Instant denyUntil, long version) {
}
