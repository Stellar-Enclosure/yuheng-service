package top.egon.cola.component.yuheng.edge.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthTransactionRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRefreshOperationRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;

/** Every node may recover; SQL ownership and CAS decide which state transition commits. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeOAuthStateRecovery {
    private final EdgeOAuthTransactionRepository transactions;
    private final EdgeRefreshOperationRepository refreshOperations;
    private final EdgeRevocationIntentRepository revocations;
    private final EdgeOAuthStateProperties properties;

    @Scheduled(fixedDelayString = "${egon.yuheng.edge.oauth-state.recovery-interval:5s}")
    public Mono<Void> recover() {
        return Mono.defer(() -> {
            int batch = properties.getRecoveryBatchSize();
            return refreshOperations.recoverExpired(batch)
                    .then(transactions.recoverExpired(batch))
                    .then(revocations.purgeExpired(batch))
                    .then(refreshOperations.purgeExpired(batch))
                    .then(transactions.purgeExpired(batch)).then();
        }).onErrorResume(failure -> {
            log.error("EDGE OAuth database recovery did not complete; failureType={}", failure.getClass().getSimpleName());
            return Mono.empty();
        });
    }
}
