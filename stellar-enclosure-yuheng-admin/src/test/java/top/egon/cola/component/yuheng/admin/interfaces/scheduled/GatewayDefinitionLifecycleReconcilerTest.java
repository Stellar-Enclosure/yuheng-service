package top.egon.cola.component.yuheng.admin.reporting.controller.scheduled;
import static top.egon.cola.component.yuheng.admin.config.GatewayPersistenceTestSupport.persistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.admin.application.domain.bo.GatewayApplicationBO;
import top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.reporting.repository.GatewayDefinitionLifecycleRepository;
import top.egon.cola.component.yuheng.admin.reporting.domain.vo.GatewayReconcileResultVO;
import top.egon.cola.component.yuheng.admin.reporting.controller.scheduled.GatewayDefinitionLifecycleReconciler.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GatewayDefinitionLifecycleReconcilerTest {
    private static final Instant NOW=Instant.parse("2026-08-26T03:00:00Z");
    @BeforeAll static void ids(){SnowflakeIdGenerator.initialize(0L,Duration.ofMillis(5));}
    private final GatewayApplicationRepository apps=mock(GatewayApplicationRepository.class);
    private final GatewayDefinitionLifecycleRepository lifecycle=mock(GatewayDefinitionLifecycleRepository.class);
    private final GatewayAuditLogRepository audits=mock(GatewayAuditLogRepository.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    @Test void absentKubernetesEvidenceNeverMutatesOfflineOrClaimsSuccess() throws Exception {
        reconciler(null).reconcile();verifyNoInteractions(lifecycle,audits,tx);
    }
    @Test void incompleteStaleExpiredForeignOrWrongScopeEvidenceNeverMutatesOffline() throws Exception {
        var reader=mock(NativeObservationReader.class);var reconciler=reconciler(reader);
        when(apps.findAllByDeletedFalseOrderByCreatedAtDesc()).thenReturn(List.of(app()));
        for(int kind=0;kind<5;kind++){
            final int variant=kind;
            when(reader.current(eq(7001L),any())).thenAnswer(call->{var scope=call.getArgument(1,top.egon.cola.component.yuheng.admin.reporting.domain.dto.GatewayDefinitionLifecycleScopeDTO.class);
                return Optional.of(new NativeObservationVO(variant==3?7002:7001,variant==4?new top.egon.cola.component.yuheng.admin.reporting.domain.dto.GatewayDefinitionLifecycleScopeDTO("other","orders","prod","logical"):scope,
                        variant!=0,variant!=1,NOW.minusSeconds(1),variant==2?NOW:NOW.plusSeconds(30),Set.of()));});
            reconciler.reconcile();
        }
        verifyNoInteractions(lifecycle,audits,tx);
    }
    @Test void completeFreshEvidenceReconcilesOnlyItsActiveSetsAndAuditsChanges() throws Exception {
        var reader=mock(NativeObservationReader.class);var reconciler=reconciler(reader);prepare(reader,Set.of("active"));
        when(lifecycle.activeOpenApiDefinitionSetIds()).thenReturn(Set.of());
        when(lifecycle.reconcile(Set.of("active"),NOW)).thenReturn(new GatewayReconcileResultVO(1,1,1,1));
        runTransactions();reconciler.reconcile();verify(lifecycle).reconcile(Set.of("active"),NOW);verify(audits).save(any());
    }
    @Test void completeEvidenceCanConfirmLastProviderDisappeared() throws Exception {
        var reader=mock(NativeObservationReader.class);var reconciler=reconciler(reader);prepare(reader,Set.of());
        when(lifecycle.activeOpenApiDefinitionSetIds()).thenReturn(Set.of());
        when(lifecycle.reconcile(Set.of(),NOW)).thenReturn(new GatewayReconcileResultVO(0,1,0,1));
        runTransactions();reconciler.reconcile();verify(lifecycle).reconcile(Set.of(),NOW);
    }
    @Test void independentlyValidOpenApiCatalogSetsRemainInTheUnion() throws Exception {
        var reader=mock(NativeObservationReader.class);var reconciler=reconciler(reader);prepare(reader,Set.of("native-set"));
        when(lifecycle.activeOpenApiDefinitionSetIds()).thenReturn(Set.of("openapi-set"));
        when(lifecycle.reconcile(Set.of("native-set","openapi-set"),NOW)).thenReturn(new GatewayReconcileResultVO(0,0,0,0));
        runTransactions();reconciler.reconcile();verify(lifecycle).reconcile(Set.of("native-set","openapi-set"),NOW);verifyNoInteractions(audits);
    }
    @Test void missingOneScopeOfTheInstallationPreventsGlobalOfflineInference() throws Exception {
        var reader=mock(NativeObservationReader.class);var reconciler=reconciler(reader);prepare(reader,Set.of());
        var another=app();another.setNamespace("another-logical");when(apps.findAllByDeletedFalseOrderByCreatedAtDesc()).thenReturn(List.of(app(),another));
        when(reader.current(eq(7001L),argThat(scope->"another-logical".equals(scope.namespace())))).thenReturn(Optional.empty());
        reconciler.reconcile();verifyNoInteractions(lifecycle,audits,tx);
    }
    private GatewayDefinitionLifecycleReconciler reconciler(NativeObservationReader reader){return new GatewayDefinitionLifecycleReconciler(reader,apps,lifecycle,audits,tx,Clock.fixed(NOW,ZoneOffset.UTC),persistenceContext());}
    private void prepare(NativeObservationReader reader,Set<String> ids){
        when(apps.findAllByDeletedFalseOrderByCreatedAtDesc()).thenReturn(List.of(app()));
        when(reader.current(eq(7001L),any())).thenAnswer(call->Optional.of(new NativeObservationVO(7001,call.getArgument(1),true,true,NOW.minusSeconds(1),NOW.plusSeconds(30),ids)));
    }
    private void runTransactions(){doAnswer(call->{java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action=call.getArgument(0);action.accept(null);return null;}).when(tx).executeWithoutResult(any());}
    private static GatewayApplicationBO app(){return new GatewayApplicationBO("application-1","platform","orders","Orders","prod","logical",null,"admin",NOW);}
}
