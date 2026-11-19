package top.egon.cola.component.yuheng.admin.reporting.controller.scheduled;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.reporting.domain.dto.GatewayDefinitionLifecycleScopeDTO;
import top.egon.cola.component.yuheng.admin.reporting.repository.GatewayDefinitionLifecycleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Offline inference requires complete independent current workload evidence. No registry or HTTP import supplies it. */
@Slf4j
@Component("gatewayDefinitionLifecycleReconciler")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="yuheng.admin.exposure.role",havingValue="ADMIN",matchIfMissing=true)
public class GatewayDefinitionLifecycleReconciler {
    private final NativeObservationReader observations;
    private final GatewayApplicationRepository applications;
    private final GatewayDefinitionLifecycleRepository lifecycle;
    private final GatewayAuditLogRepository audits;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final GatewayPersistenceContextComponent persistenceContext;
    @Autowired
    public GatewayDefinitionLifecycleReconciler(@Qualifier("gatewayDefinitionNativeObservationReader") ObjectProvider<NativeObservationReader> observations,
            @Qualifier("mpGatewayApplicationRepository") GatewayApplicationRepository applications,
            @Qualifier("mpGatewayDefinitionLifecycleRepository") GatewayDefinitionLifecycleRepository lifecycle,
            @Qualifier("gatewayAuditLogRepository") GatewayAuditLogRepository audits,
            @Qualifier("gatewayExposureTransactionTemplate") TransactionTemplate transactions,
            @Qualifier("gatewayProjectionClock") Clock clock,@Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent persistenceContext){
        this(observations.getIfAvailable(),applications,lifecycle,audits,transactions,clock,persistenceContext);
    }
    GatewayDefinitionLifecycleReconciler(NativeObservationReader observations,GatewayApplicationRepository applications,
            GatewayDefinitionLifecycleRepository lifecycle,GatewayAuditLogRepository audits,TransactionTemplate transactions,
            Clock clock,GatewayPersistenceContextComponent persistenceContext){
        this.observations=observations;this.applications=applications;this.lifecycle=lifecycle;this.audits=audits;
        this.transactions=transactions;this.clock=clock;this.persistenceContext=persistenceContext;
    }
    @Scheduled(fixedDelayString="${yuheng.admin.definition-reconcile-delay:30000}")
    public void reconcile() throws Exception {
        persistenceContext.call(null,()->{
            Long owner=persistenceContext.installationTenantId();
            if(observations==null||owner==null||!owner.equals(EgonColaTenantIdProvider.currentTenantId()))return null;
            var apps=applications.findAllByDeletedFalseOrderByCreatedAtDesc();if(apps.isEmpty())return null;
            Instant now=clock.instant();Set<String> active=new LinkedHashSet<>();
            Set<GatewayDefinitionLifecycleScopeDTO> scopes=new LinkedHashSet<>();
            for(var app:apps)scopes.add(new GatewayDefinitionLifecycleScopeDTO(app.getBizCode(),app.getApplicationCode(),app.getEnv(),app.getNamespace()));
            for(var scope:scopes){
                NativeObservationVO proof;
                try{proof=observations.current(owner,scope).orElse(null);}catch(RuntimeException unavailable){return null;}
                if(proof==null||proof.ownerTenantId()!=owner||!scope.equals(proof.scope())||!proof.complete()||!proof.fresh()
                        ||proof.observedAt()==null||proof.observedAt().isAfter(now)||proof.validUntil()==null||!now.isBefore(proof.validUntil()))return null;
                active.addAll(proof.activeDefinitionSetIds());
            }
            active.addAll(lifecycle.activeOpenApiDefinitionSetIds());
            transactions.executeWithoutResult(status->{var result=lifecycle.reconcile(active,now);if(result.changed())audits.save(audit(active,result,now));});
            return null;
        });
    }
    @FunctionalInterface public interface NativeObservationReader {
        Optional<NativeObservationVO> current(long trustedOwner,GatewayDefinitionLifecycleScopeDTO scope);
    }
    public record NativeObservationVO(long ownerTenantId,GatewayDefinitionLifecycleScopeDTO scope,boolean complete,boolean fresh,
            Instant observedAt,Instant validUntil,Set<String> activeDefinitionSetIds){
        public NativeObservationVO{activeDefinitionSetIds=Set.copyOf(activeDefinitionSetIds);}
    }

    private GatewayAuditLogBO audit(
            Set<String> activeDefinitionSets,
            top.egon.cola.component.yuheng.admin.reporting.domain.vo.GatewayReconcileResultVO result,
            Instant now) {
        return new GatewayAuditLogBO(
                SnowflakeIdGenerator.nextId(),
                "yuheng-definition-reconciler",
                "SYSTEM",
                "SCHEDULED_RECONCILER",
                null,
                null,
                "DEFINITION_SET",
                "provider-active-sets",
                "RECONCILE_PROVIDER_LIFECYCLE",
                GatewayAuditLogBO.sanitized(Map.of()),
                GatewayAuditLogBO.sanitized(Map.of(
                        "activeDefinitionSetIds",
                        List.copyOf(activeDefinitionSets),
                        "activatedDefinitionSets",
                        result.activatedDefinitionSets(),
                        "retiredDefinitionSets",
                        result.retiredDefinitionSets(),
                        "activatedOperations",
                        result.activatedOperations(),
                        "offlinedOperations",
                        result.offlinedOperations()
                )),
                null,
                null,
                true,
                null,
                now
        );
    }

}
