package top.egon.cola.component.yuheng.admin.openapi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.yuheng.admin.config.*;
import top.egon.cola.component.yuheng.admin.application.domain.bo.GatewayApplicationBO;
import top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository;
import top.egon.cola.component.yuheng.admin.scope.service.*;
import top.egon.cola.component.yuheng.admin.openapi.GatewayOpenApiValidationTestFixture;
import top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiSourceInventory.SourceVO;
import top.egon.cola.component.yuheng.admin.openapi.domain.dto.GatewayOpenApiSyncCandidateDTO;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GatewayOpenApiSourceInventoryTest {
    static final Instant NOW=Instant.parse("2026-08-26T03:00:00Z");
    private final GatewayApplicationRepository apps=mock(GatewayApplicationRepository.class);
    private final GatewayScopeService scopes=mock(GatewayScopeService.class);
    private final GatewayPersistenceContextComponent context=new GatewayPersistenceContextComponent(new GatewayPersistenceProperties()
            .setTenantId(7L).setIdentityTenantId(1001L).setServiceUserId("source-reader"),new EgonColaMybatisPlusProperties());
    @AfterEach void clear(){MDC.clear();}
    @Test void exactApprovedServiceSourceHasNoInstanceLeaseAndPreservesPhysicalLogicalSeparation() throws Exception {
        var raw="{\"openapi\":\"3.1.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var entry=source(GatewayOpenApiValidationTestFixture.sha256(raw));var inventory=inventory(entry);
        var candidate=context.call("1001",()->inventory.sources(app()).getFirst().candidates().getFirst());
        assertThat(candidate.host()).isEqualTo("orders-http.apps-prod.svc.cluster.local");
        assertThat(candidate.host()).doesNotContain("logical-docs");
        assertThat(candidate.approvedInternalHttp()).isTrue();assertThat(candidate.developmentPlaintext()).isFalse();
        assertThat(candidate.resourceUri()).isEqualTo(URI.create("https://api.example.test/orders"));
        context.call("1001",()->{inventory.verifyDocument(candidate,GatewayOpenApiValidationTestFixture.document(candidate,raw,"application/json",200));return null;});
    }
    @Test void wrongRawWholeDocumentDigestIsRejectedBeforeAnyApply() throws Exception {
        var inventory=inventory(source("a".repeat(64)));var candidate=context.call("1001",()->inventory.sources(app()).getFirst().candidates().getFirst());
        var raw="{\"openapi\":\"3.1.0\",\"paths\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->context.call("1001",()->{inventory.verifyDocument(candidate,GatewayOpenApiValidationTestFixture.document(candidate,raw,"application/json",200));return null;}))
                .hasMessage("OpenAPI source document does not match its approved digest");
    }
    @Test void candidateTargetPortBuildGroupAndResourceCannotBeChanged() throws Exception {
        var inventory=inventory(source("a".repeat(64)));var candidate=context.call("1001",()->inventory.sources(app()).getFirst().candidates().getFirst());
        for(var wrong:List.of(copy(candidate,"other.apps-prod.svc.cluster.local",8080,"build-1","orders",candidate.resourceUri()),
                copy(candidate,candidate.host(),8081,"build-1","orders",candidate.resourceUri()),
                copy(candidate,candidate.host(),8080,"build-2","orders",candidate.resourceUri()),
                copy(candidate,candidate.host(),8080,"build-1","private",candidate.resourceUri()),
                copy(candidate,candidate.host(),8080,"build-1","orders",URI.create("https://wrong.example/resource")))){
            assertThatThrownBy(()->context.call("1001",()->{inventory.authorize(wrong);return null;})).hasMessage("OpenAPI source approval is unavailable");
        }
    }
    @Test void sourceCannotBypassCurrentLogicalScopeAuthorityOrSwapBindingId() {
        var inventory=inventory(source("a".repeat(64)));
        doThrow(new IllegalStateException("denied")).when(scopes).requireEnabled(any());
        assertThatThrownBy(()->context.call("1001",()->inventory.sources(app()))).isInstanceOf(IllegalStateException.class);
        doReturn(new GatewayScopeBindingReader.BindingVO("different","platform","logical-docs","prod","app-id","orders","Orders",true))
                .when(scopes).requireEnabled(any());
        assertThatThrownBy(()->context.call("1001",()->inventory.sources(app()))).hasMessage("OpenAPI source approval is unavailable");
    }
    @Test void missingOrForeignOwnerAndExpiredApprovalNeverReachAuthority() throws Exception {
        var inventory=inventory(source("a".repeat(64)));clearInvocations(scopes);
        MDC.put("tenantId","8");assertThatThrownBy(()->inventory.sources(app())).isInstanceOf(RuntimeException.class);verifyNoInteractions(scopes);
        var expired=new GatewayOpenApiSourceInventory(context,apps,scopes,List.of(source("a".repeat(64))),Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));
        assertThatThrownBy(()->context.call("1001",()->expired.sources(app()))).isInstanceOf(RuntimeException.class);verifyNoInteractions(scopes);
    }
    @Test void groupPathInjectionUnknownPortAndEmptyApprovalSetAreRejected() {
        assertThatThrownBy(()->new GatewayOpenApiSourceInventory(context,apps,scopes,List.of(),Clock.fixed(NOW,ZoneOffset.UTC))).isInstanceOf(RuntimeException.class);
        for(String group:List.of("../private","orders%2fprivate","orders?x=1","Orders")){
            assertThatThrownBy(()->new SourceVO("source","binding","application-1","cluster","apps-prod","build","1","orders-http","default","1",8080,
                    URI.create("https://api.example.test/orders"),Map.of(group,"a".repeat(64)),NOW.minusSeconds(1),NOW.plusSeconds(60))).isInstanceOf(RuntimeException.class);
        }
    }
    private GatewayOpenApiSourceInventory inventory(SourceVO source){
        when(apps.findByIdAndDeletedFalse("application-1")).thenReturn(Optional.of(app()));
        when(scopes.requireEnabled(any())).thenReturn(new GatewayScopeBindingReader.BindingVO("binding","platform","logical-docs","prod","app-id","orders","Orders",true));
        return new GatewayOpenApiSourceInventory(context,apps,scopes,List.of(source),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    static SourceVO source(String digest){return new SourceVO("source","binding","application-1","cluster","apps-prod","build-1","1","orders-http","default","1",8080,
            URI.create("https://api.example.test/orders"),Map.of("orders",digest),NOW.minusSeconds(1),NOW.plusSeconds(60));}
    static GatewayApplicationBO app(){return new GatewayApplicationBO("application-1","platform","orders","Orders","prod","logical-docs",null,"admin",NOW);}
    private static GatewayOpenApiSyncCandidateDTO copy(GatewayOpenApiSyncCandidateDTO source,String host,int port,String build,String group,URI resource){
        return new GatewayOpenApiSyncCandidateDTO(source.applicationId(),source.bizCode(),source.applicationCode(),build,source.artifactVersion(),group,
                source.providerServiceName(),source.providerGroup(),source.providerVersion(),source.instanceId(),host,port,false,source.pathTemplate(),resource,
                source.observedAt(),source.expiresAt(),false,true);
    }
}
