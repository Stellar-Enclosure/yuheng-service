package top.egon.cola.component.yuheng.edge.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;

/** SQL claims and leases, including restart recovery, own delivery across all EDGE instances. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeLogoutRevocationWorker {
    private final EdgeRevocationIntentRepository intents;
    private final EdgeOAuthTokenRevocationClient client;
    private final EdgeOAuthStateProperties properties;

    @Scheduled(fixedDelayString = "${egon.yuheng.edge.oauth-state.recovery-interval:5s}")
    public Mono<Void> retryPending() {
        return Mono.defer(() -> intents.claimDue(properties.getRecoveryBatchSize()))
                .flatMapMany(Flux::fromIterable)
                .flatMap(claim -> client.revoke(claim).then(intents.confirm(claim))
                        .onErrorResume(failure -> intents.retry(claim, "EDGE_REVOCATION_UNCONFIRMED", null, false)), 4)
                .then().doOnError(failure -> log.warn("EDGE revocation delivery unavailable; failureType={}",
                        failure.getClass().getSimpleName()));
    }
}
