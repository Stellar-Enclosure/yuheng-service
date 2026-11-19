package top.egon.cola.component.yuheng.admin.openapi.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.yuheng.admin.config.*;
import top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiSourceInventory;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.*;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import java.net.URI;import java.time.*;import java.util.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;

class GatewayOpenApiSourceConfigurationTest {
    private final GatewayOpenApiSourceConfiguration configuration=new GatewayOpenApiSourceConfiguration();
    private final GatewayPersistenceProperties persistence=new GatewayPersistenceProperties().setTenantId(7L).setIdentityTenantId(1001L).setServiceUserId("source");
    private final GatewayPersistenceContextComponent context=new GatewayPersistenceContextComponent(persistence,new EgonColaMybatisPlusProperties());
    private final GatewayOpenApiSourceInventory inventory=mock(GatewayOpenApiSourceInventory.class);
    private final IdpStarterProperties idp=new IdpStarterProperties();
    private final TrustedIssuerRegistry issuers=mock(TrustedIssuerRegistry.class);
    private final IdpServiceOAuth2Client oauth=mock(IdpServiceOAuth2Client.class);
    @AfterEach void clear(){MDC.clear();}
    @Test void exactTenantIssuerResourceAndRegisteredClientAreUsed() throws Exception {
        prepare();var supplier=configuration.tokens(inventory,context,persistence,idp,issuers,oauth,Duration.ofSeconds(2));
        when(oauth.authorizeBounded(any(),eq("https://idp.example.test/realms/1001"),eq(Duration.ofSeconds(2)))).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"synthetic",Instant.now(),Instant.now().plusSeconds(60)));
        URI resource=URI.create("https://api.example.test/orders");
        assertThat(context.call("1001",()->supplier.tokenFor(resource,"yuheng.openapi.read"))).isEqualTo("synthetic");
        var capture=org.mockito.ArgumentCaptor.forClass(IdpServiceTokenRequest.class);verify(oauth).authorizeBounded(capture.capture(),eq("https://idp.example.test/realms/1001"),eq(Duration.ofSeconds(2)));
        assertThat(capture.getValue().context()).isEqualTo(ServiceTokenContext.TENANT);assertThat(capture.getValue().tenantId()).isEqualTo("1001");
        assertThat(capture.getValue().registrationId()).isEqualTo("tenant-registration");assertThat(capture.getValue().audience()).isEqualTo(resource);
        verify(inventory).authorizeResource(resource,"yuheng.openapi.read");
    }
    @Test void foreignOwnerMissingAuthorityOrUnapprovedResourceNeverRequestsToken() throws Exception {
        prepare();var supplier=configuration.tokens(inventory,context,persistence,idp,issuers,oauth,Duration.ofSeconds(2));URI resource=URI.create("https://api.example.test/orders");
        MDC.put("tenantId","8");assertThatThrownBy(()->supplier.tokenFor(resource,"yuheng.openapi.read")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(inventory,oauth);
        doThrow(new IllegalStateException("unapproved")).when(inventory).authorizeResource(resource,"yuheng.openapi.read");
        assertThatThrownBy(()->context.call("1001",()->supplier.tokenFor(resource,"yuheng.openapi.read"))).isInstanceOf(IllegalStateException.class);verifyNoInteractions(oauth);
    }
    @Test void missingOrPlatformIssuerAndMissingRegistrationFailClosedAtAssembly() {
        prepare();idp.getServiceClient().setTenantRegistrationIds(Map.of());
        assertThatThrownBy(()->configuration.tokens(inventory,context,persistence,idp,issuers,oauth,Duration.ofSeconds(2))).isInstanceOf(RuntimeException.class);
        idp.getServiceClient().setTenantRegistrationIds(Map.of("1001","tenant-registration"));when(issuers.activeIssuers()).thenReturn(List.of());
        assertThatThrownBy(()->configuration.tokens(inventory,context,persistence,idp,issuers,oauth,Duration.ofSeconds(2))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(oauth);
    }
    @Test void disabledOpenApiDoesNotCreateAnyNewRuntimeOrDemandCredentials() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(GatewayOpenApiSourceConfiguration.class)
                .run(app->assertThat(app).hasNotFailed().doesNotHaveBean(GatewayOpenApiSourceInventory.class));
    }
    @Test void enabledSourceConfigurationBindsTheRealExactInventoryWithoutNetworking() {
        prepare();persistence.setExpectedSchemaVersion("fixture").setExpectedSchemaSha256("a".repeat(64)).setManagedDdlEnabled(false);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(GatewayOpenApiSourceConfiguration.class,
                        top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiSyncService.class,
                        top.egon.cola.component.yuheng.admin.openapi.client.GatewayProviderOpenApiClient.class,
                        top.egon.cola.component.yuheng.admin.scope.config.GatewayScopeBindingConfiguration.class,
                        top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeService.class)
                .withBean(GatewayPersistenceProperties.BEAN_NAME,GatewayPersistenceProperties.class,()->persistence)
                .withBean("gatewayPersistenceContextComponent",GatewayPersistenceContextComponent.class,()->context)
                .withBean("idpStarterProperties",IdpStarterProperties.class,()->idp).withBean("trustedIssuerRegistry",TrustedIssuerRegistry.class,()->issuers)
                .withBean("idpServiceOAuth2Client",IdpServiceOAuth2Client.class,()->oauth)
                .withBean("gatewayOpenApiClock",Clock.class,()->Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"),ZoneOffset.UTC))
                .withBean("gatewayOpenApiReadTimeout",Duration.class,()->Duration.ofSeconds(2))
                .withBean("mpGatewayApplicationRepository",top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository.class,()->mock(top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository.class))
                .withBean(top.egon.cola.component.yuheng.admin.openapi.repository.GatewayOpenApiSyncRepository.class,()->mock(top.egon.cola.component.yuheng.admin.openapi.repository.GatewayOpenApiSyncRepository.class))
                .withBean(top.egon.cola.component.yuheng.admin.openapi.repository.GatewayOpenApiSnapshotRepository.class,()->mock(top.egon.cola.component.yuheng.admin.openapi.repository.GatewayOpenApiSnapshotRepository.class))
                .withBean(top.egon.cola.component.yuheng.admin.openapi.validation.GatewayOpenApiValidationChain.class,()->mock(top.egon.cola.component.yuheng.admin.openapi.validation.GatewayOpenApiValidationChain.class))
                .withBean(top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiAggregateCoordinator.class,()->mock(top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiAggregateCoordinator.class))
                .withBean(top.egon.cola.component.yuheng.admin.config.properties.GatewayAdminOpenApiProperties.class,top.egon.cola.component.yuheng.admin.config.properties.GatewayAdminOpenApiProperties::new)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withBean("gatewayOpenApiHttpClient",java.net.http.HttpClient.class,()->java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build())
                .withBean("gatewayOpenApiDnsPolicy",top.egon.cola.component.yuheng.admin.openapi.client.GatewayOpenApiDnsPolicy.class,()->new top.egon.cola.component.yuheng.admin.openapi.client.GatewayOpenApiDnsPolicy(List.of("10.0.0.0/8")))
                .withBean("gatewayOpenApiObjectMapper",com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean("gatewayOpenApiMaximumDocumentBytes",Integer.class,()->1048576)
                .withBean("jacksonObjectMapper",com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues("yuheng.admin.logical-catalog.enabled=true",
                        "yuheng.admin.logical-catalog.origin=http://tianshu-admin-http.platform-prod.svc.cluster.local:18080",
                        "yuheng.admin.logical-catalog.resource-uri=https://api.example.test/tianshu",
                        "yuheng.admin.logical-catalog.bindings[0].binding-id=scope-orders",
                        "yuheng.admin.logical-catalog.bindings[0].biz-code=platform",
                        "yuheng.admin.logical-catalog.bindings[0].namespace=logical-docs",
                        "yuheng.admin.logical-catalog.bindings[0].env=prod",
                        "yuheng.admin.logical-catalog.bindings[0].application-id=app-id",
                        "yuheng.admin.logical-catalog.bindings[0].application-code=orders",
                        "yuheng.admin.openapi.enabled=true",
                        "yuheng.admin.openapi.sources.entries[0].source-id=ci-build-1","yuheng.admin.openapi.sources.entries[0].scope-binding-id=scope-orders",
                        "yuheng.admin.openapi.sources.entries[0].gateway-application-id=application-1","yuheng.admin.openapi.sources.entries[0].cluster-id=cluster",
                        "yuheng.admin.openapi.sources.entries[0].kubernetes-namespace=apps-prod","yuheng.admin.openapi.sources.entries[0].build-id=build-1",
                        "yuheng.admin.openapi.sources.entries[0].artifact-version=1","yuheng.admin.openapi.sources.entries[0].provider-service-name=orders-http",
                        "yuheng.admin.openapi.sources.entries[0].provider-group=default","yuheng.admin.openapi.sources.entries[0].provider-version=1",
                        "yuheng.admin.openapi.sources.entries[0].port=8080","yuheng.admin.openapi.sources.entries[0].resource-uri=https://api.example.test/orders",
                        "yuheng.admin.openapi.sources.entries[0].groups.orders="+"a".repeat(64),
                        "yuheng.admin.openapi.sources.entries[0].approved-at=2026-08-26T02:59:00Z","yuheng.admin.openapi.sources.entries[0].valid-until=2026-08-26T04:00:00Z")
                .run(app->assertThat(app).hasNotFailed().hasSingleBean(GatewayOpenApiSourceInventory.class)
                        .hasBean("gatewayOpenApiTokenSupplier")
                        .hasSingleBean(top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiSyncService.class)
                        .hasSingleBean(top.egon.cola.component.yuheng.admin.openapi.client.GatewayProviderOpenApiClient.class));
        verify(oauth,never()).authorize(any());verify(oauth,never()).authorizeBounded(any(),any(),any());
    }

    private void prepare(){
        idp.getServiceClient().setAppId("yuheng");idp.getServiceClient().setTenantRegistrationIds(Map.of("1001","tenant-registration"));
        var issuer=new TrustedIssuerRegistry.TrustedIssuerVO(URI.create("https://idp.example.test/realms/1001"),URI.create("https://idp.example.test/realms/1001/jwks"),
                "tenant-1001",TrustedIssuerRegistry.RealmType.TENANT,"1001",TrustedIssuerRegistry.Status.ACTIVE,1);
        when(issuers.activeIssuers()).thenReturn(List.of(issuer));when(issuers.findByIssuer(issuer.issuer().toASCIIString())).thenReturn(Optional.of(issuer));
    }
}
