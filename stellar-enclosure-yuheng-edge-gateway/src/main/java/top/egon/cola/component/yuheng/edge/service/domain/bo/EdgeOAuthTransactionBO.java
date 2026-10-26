package top.egon.cola.component.yuheng.edge.service.domain.bo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One-use client transaction metadata; no authentication or user token is persisted here. */
public record EdgeOAuthTransactionBO(UUID transactionId, EdgeOAuthTargetBO target, String redirectUri,
                                     String returnPath, List<String> requestedScopes, String stateDigest,
                                     String browserBindingDigest, String nonceDigest, String codeChallenge,
                                     String status, Instant expiresAt, long version) {
    public EdgeOAuthTransactionBO {
        requestedScopes = List.copyOf(requestedScopes);
    }
}
