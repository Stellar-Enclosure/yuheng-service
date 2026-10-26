package top.egon.cola.component.yuheng.edge.service.domain.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;

/** Secrets exist only in the winning callback request, after the SQL copy has been erased. */
public record EdgeOAuthExchangeClaimBO(EdgeOAuthTransactionBO transaction, UUID attemptId,
                                      UUID owner, Instant deadlineAt, @JsonIgnore String codeVerifier, @JsonIgnore String nonce,
                                      boolean reauthenticate) {
    @Override
    public String toString() {
        return "EdgeOAuthExchangeClaimBO[transactionId=" + transaction.transactionId() + ", secrets=<redacted>]";
    }
}
