package top.egon.cola.component.yuheng.edge.service.domain.bo;

import java.time.Instant;
import java.util.UUID;

/** Operation status only; a completed operation cannot be used to retrieve credentials. */
public record EdgeRefreshOperationBO(UUID operationId, EdgeRefreshContextBO context,
                                     String requestDigest, String refreshTokenDigest, String status,
                                     UUID leaseOwner, Instant leaseExpiresAt, Instant deadlineAt,
                                     int upstreamAttempts, Instant accessExpiresAt,
                                     Long successorGeneration, String failureCode, long version) {
}
