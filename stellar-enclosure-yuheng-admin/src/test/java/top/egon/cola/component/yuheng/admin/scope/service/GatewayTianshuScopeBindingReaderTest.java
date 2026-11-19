package top.egon.cola.component.yuheng.admin.scope.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.tianshu.api.catalog.DdcLogicalCatalogClient;
import top.egon.cola.component.tianshu.model.management.DdcManagementApp;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceProperties;
import top.egon.cola.component.yuheng.admin.scope.domain.dto.GatewayScopeQueryDTO;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeBindingReader.DeploymentBindingVO;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayTianshuScopeBindingReaderTest {
    private final DdcLogicalCatalogClient catalog = mock(DdcLogicalCatalogClient.class);
    private final GatewayPersistenceProperties properties = new GatewayPersistenceProperties()
            .setTenantId(7L).setIdentityTenantId(1001L).setServiceUserId("scope-reader");
    private final GatewayPersistenceContextComponent context = new GatewayPersistenceContextComponent(
            properties, new EgonColaMybatisPlusProperties());
    private static final GatewayScopeQueryDTO ALL = new GatewayScopeQueryDTO(null,null,null,null);

    @AfterEach void cleanContext() { MDC.clear(); }

    @Test void exactLiveAppResolvesTwoApprovedNamespacesAndRestoresTheTrustedContext() throws Exception {
        when(catalog.findApplication("app-1")).thenReturn(Optional.of(app(true,true)));
        var source = new ArrayList<>(List.of(binding("one","default"),binding("two","ops")));
        var reader = new GatewayTianshuScopeBindingReader(context,catalog,source);
        source.clear();
        var result = context.call("1001", () -> reader.bindings(ALL));
        assertThat(result).extracting(GatewayScopeBindingReader.BindingVO::namespaceCode).containsExactly("default","ops");
        assertThat(result).extracting(GatewayScopeBindingReader.BindingVO::appName).containsOnly("Order from authority");
        verify(catalog,times(1)).findApplication("app-1");
        assertThat(MDC.get("tenantId")).isNull();
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unapprovedScopeReturnsNoBindingAndMakesNoAuthorityCall() throws Exception {
        var reader = reader();
        assertThat(context.call("1001", () -> reader.bindings(new GatewayScopeQueryDTO("retail","unknown","prod","order"))))
                .isEmpty();
        verifyNoInteractions(catalog);
    }

    @Test void missingOrForeignMdcCannotChooseAnotherInstallationOrIssueHttp() {
        var reader = reader();
        assertThatThrownBy(() -> reader.bindings(ALL)).isInstanceOf(IllegalStateException.class);
        MDC.put("tenantId","8");
        assertThatThrownBy(() -> reader.bindings(ALL)).hasMessage("YUHENG_SCOPE_OWNER_CONTEXT_MISMATCH");
        MDC.put("tenantId","0");
        assertThatThrownBy(() -> reader.bindings(ALL)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(catalog);
    }

    @Test void foreignVerifiedIdentityCannotEnterFixedInstallation() {
        var reader = reader();
        assertThatThrownBy(() -> context.call("1002", () -> reader.bindings(ALL))).isInstanceOf(top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException.class)
                .hasMessage("PERSISTENCE_IDENTITY_TENANT_MISMATCH");
        verifyNoInteractions(catalog);
    }

    @Test void disabledAppOrBusinessAndAuthorizedAbsenceNeverProduceAnEnabledBinding() throws Exception {
        var reader = reader();
        when(catalog.findApplication("app-1")).thenReturn(Optional.of(app(false,true)),
                Optional.of(app(true,false)),Optional.empty());
        for(int i=0;i<3;i++) { assertThat(context.call("1001",()->reader.bindings(ALL))).isEmpty(); }
        verify(catalog,times(3)).findApplication("app-1");
    }

    @Test void currentAuthorizationFailureNeverFallsBackToPreviouslySuccessfulMetadata() throws Exception {
        var reader = reader();
        when(catalog.findApplication("app-1")).thenReturn(Optional.of(app(true,true)));
        assertThat(context.call("1001",()->reader.bindings(ALL))).hasSize(1);
        doThrow(new SecurityException("synthetic denied secret")).when(catalog).findApplication("app-1");
        assertThatThrownBy(() -> context.call("1001",()->reader.bindings(ALL)))
                .hasMessage("YUHENG_SCOPE_CATALOG_UNAVAILABLE").hasNoCause();
    }

    @Test void exactApplicationIdBusinessAndAppCodeAreAllAuthorityBound() {
        var reader = reader();
        for(DdcManagementApp wrong:List.of(new DdcManagementApp("other","biz-id","retail","order","Order",true,true),
                new DdcManagementApp("app-1","biz-id","other","order","Order",true,true),
                new DdcManagementApp("app-1","biz-id","retail","other","Order",true,true))) {
            doReturn(Optional.of(wrong)).when(catalog).findApplication("app-1");
            assertThatThrownBy(() -> context.call("1001",()->reader.bindings(ALL)))
                    .hasMessage("YUHENG_SCOPE_BINDING_INVALID");
        }
    }

    @Test void duplicateAndOversizedInventoriesFailBeforeAnyLookup() {
        assertThatThrownBy(() -> new GatewayTianshuScopeBindingReader(context,catalog,
                List.of(binding("same","default"),binding("same","ops")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayTianshuScopeBindingReader(context,catalog,
                List.of(binding("one","default"),binding("two","default")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayTianshuScopeBindingReader(context,catalog,
                Collections.nCopies(257,binding("one","default")))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(catalog);
    }

    private GatewayTianshuScopeBindingReader reader() {
        return new GatewayTianshuScopeBindingReader(context,catalog,List.of(binding("one","default")));
    }
    private static DeploymentBindingVO binding(String id,String ns) {
        return new DeploymentBindingVO(id,"retail",ns,"prod","app-1","order");
    }
    private static DdcManagementApp app(boolean enabled,boolean bizEnabled) {
        return new DdcManagementApp("app-1","biz-id","retail","order","Order from authority",enabled,bizEnabled);
    }
}
