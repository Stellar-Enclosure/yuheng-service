package top.egon.cola.component.yuheng.admin.scope.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.tianshu.api.catalog.HttpDdcLogicalCatalogClient;
import top.egon.cola.component.tianshu.api.catalog.DdcLogicalCatalogContract;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceProperties;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeBindingReader;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayScopeBindingConfigurationTest {
    private final GatewayScopeBindingConfiguration configuration = new GatewayScopeBindingConfiguration();
    private final GatewayPersistenceProperties persistence = new GatewayPersistenceProperties()
            .setTenantId(7L).setIdentityTenantId(1001L).setServiceUserId("scope-reader")
            .setExpectedSchemaVersion("fixture-v1").setExpectedSchemaSha256("a".repeat(64)).setManagedDdlEnabled(false);
    private final GatewayPersistenceContextComponent context = new GatewayPersistenceContextComponent(
            persistence,new EgonColaMybatisPlusProperties());
    private final IdpStarterProperties idp = new IdpStarterProperties();
    private final TrustedIssuerRegistry issuers = mock(TrustedIssuerRegistry.class);

    @AfterEach void cleanup() { MDC.clear(); }

    @Test void actualHttpClientAndNativeReaderStartWithoutLegacyManagementBeansOrNetwork() {
        idp.getServiceClient().setAppId("yuheng");
        idp.getServiceClient().setTenantRegistrationIds(Map.of("1001","tenant-registration"));
        when(issuers.activeIssuers()).thenReturn(List.of(issuer()));
        runner()
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(HttpDdcLogicalCatalogClient.class)
                            .hasSingleBean(GatewayScopeBindingReader.class);
                    assertThat(application.getBeanDefinitionNames()).doesNotContain("ddcManagementClient","gatewayDdcManagementClientHandle");
                });
    }

    @Test void supplierUsesExistingTrustedIdentityMappingAndExactTenantIssuer() throws Exception {
        var supplier = authorization();
        var request = context.call("1001",()->supplier.get().request());
        assertThat(request.tenantId()).isEqualTo("1001");
        assertThat(request.context()).isEqualTo(ServiceTokenContext.TENANT);
        assertThat(request.registrationId()).isEqualTo("tenant-registration");
        assertThat(request.appId()).isEqualTo("yuheng");
        assertThat(request.audience()).isEqualTo(URI.create("https://api.example.test/tianshu"));
        assertThat(request.scopes()).containsExactly(DdcLogicalCatalogContract.SCOPE);
        assertThat(context.call(null,()->supplier.get().issuer())).isEqualTo("https://idp.example.test/realms/tenant-1001");
    }

    @Test void absentForeignAndZeroContextsCannotRequestCredential() {
        var supplier=authorization();
        for(String value:List.of("8","0","1001")) {
            MDC.put("tenantId",value);
            assertThatThrownBy(supplier::get).hasMessage("YUHENG_SCOPE_OWNER_CONTEXT_MISMATCH");
        }
        MDC.clear();assertThatThrownBy(supplier::get).isInstanceOf(IllegalStateException.class);
    }

    @Test void ownerMappingRegistrationOrExactIssuerAbsenceFailsClosedAtAssembly() {
        var settings=properties();
        idp.getServiceClient().setAppId("yuheng");
        when(issuers.activeIssuers()).thenReturn(List.of(issuer()));
        assertThatThrownBy(()->configuration.authorization(settings,persistence,context,idp,issuers)).isInstanceOf(IllegalArgumentException.class);
        idp.getServiceClient().setTenantRegistrationIds(Map.of("1001","tenant-registration"));
        when(issuers.activeIssuers()).thenReturn(List.of());
        assertThatThrownBy(()->configuration.authorization(settings,persistence,context,idp,issuers)).isInstanceOf(IllegalArgumentException.class);
        when(issuers.activeIssuers()).thenReturn(List.of(issuer(),issuer()));
        assertThatThrownBy(()->configuration.authorization(settings,persistence,context,idp,issuers)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void arbitraryOriginAndNonCanonicalAudienceAreRejectedWithoutHttp() {
        for(String origin:List.of("http://attacker.example:8080","https://tianshu-admin-http.platform-prod.svc.cluster.local",
                "http://tianshu-admin-http.platform-prod.svc.cluster.local:18080/override","http://user@tianshu-admin-http.platform-prod.svc.cluster.local")) {
            var settings=properties();settings.setOrigin(URI.create(origin));
            assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class);
        }
        var settings=properties();settings.setResourceUri(URI.create("http://canonical.example/resource"));
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unknownDeploymentFieldsDoNotSilentlyExpandContract() {
        idp.getServiceClient().setAppId("yuheng");
        idp.getServiceClient().setTenantRegistrationIds(Map.of("1001","tenant-registration"));
        when(issuers.activeIssuers()).thenReturn(List.of(issuer()));
        runner().withPropertyValues("yuheng.admin.logical-catalog.arbitrary-origin=http://attacker.example")
                .run(application -> assertThat(application.getStartupFailure())
                        .hasRootCauseInstanceOf(org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException.class));
    }

    @Test void removedIssuerTrustCannotReuseACapturedAuthorization() throws Exception {
        var supplier=authorization();
        assertThat(context.call("1001",supplier::get)).isNotNull();
        when(issuers.findByIssuer(issuer().issuer().toASCIIString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> context.call("1001",supplier::get))
                .hasMessage("YUHENG_LOGICAL_CATALOG_BINDING_INVALID");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(GatewayScopeBindingConfiguration.class)
                .withBean(GatewayPersistenceProperties.BEAN_NAME,GatewayPersistenceProperties.class,()->persistence)
                .withBean("gatewayPersistenceContextComponent",GatewayPersistenceContextComponent.class,()->context)
                .withBean("idpStarterProperties",IdpStarterProperties.class,()->idp)
                .withBean("trustedIssuerRegistry",TrustedIssuerRegistry.class,()->issuers)
                .withBean("idpServiceOAuth2Client",IdpServiceOAuth2Client.class,()->mock(IdpServiceOAuth2Client.class))
                .withBean("jacksonObjectMapper",ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("yuheng.admin.logical-catalog.enabled=true",
                        "yuheng.admin.logical-catalog.origin=http://tianshu-admin-http.platform-prod.svc.cluster.local:18080",
                        "yuheng.admin.logical-catalog.resource-uri=https://api.example.test/tianshu");
    }

    private java.util.function.Supplier<HttpDdcLogicalCatalogClient.AuthorizationVO> authorization() {
        idp.getServiceClient().setAppId("yuheng");
        idp.getServiceClient().setTenantRegistrationIds(Map.of("1001","tenant-registration"));
        when(issuers.activeIssuers()).thenReturn(List.of(issuer()));
        when(issuers.findByIssuer(issuer().issuer().toASCIIString())).thenReturn(Optional.of(issuer()));
        return configuration.authorization(properties(),persistence,context,idp,issuers);
    }
    private static GatewayScopeBindingConfiguration.Properties properties() {
        var settings = new GatewayScopeBindingConfiguration.Properties();settings.setEnabled(true);
        settings.setOrigin(URI.create("http://tianshu-admin-http.platform-prod.svc.cluster.local:18080"));
        settings.setResourceUri(URI.create("https://api.example.test/tianshu"));return settings;
    }
    private static TrustedIssuerRegistry.TrustedIssuerVO issuer() {
        return new TrustedIssuerRegistry.TrustedIssuerVO(URI.create("https://idp.example.test/realms/tenant-1001"),
                URI.create("https://idp.example.test/realms/tenant-1001/jwks"),"tenant-1001",TrustedIssuerRegistry.RealmType.TENANT,
                "1001",TrustedIssuerRegistry.Status.ACTIVE,1);
    }
}
