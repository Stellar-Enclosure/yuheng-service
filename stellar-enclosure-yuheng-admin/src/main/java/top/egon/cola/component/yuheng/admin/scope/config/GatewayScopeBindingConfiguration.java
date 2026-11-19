package top.egon.cola.component.yuheng.admin.scope.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.tianshu.api.catalog.DdcLogicalCatalogClient;
import top.egon.cola.component.tianshu.api.catalog.DdcLogicalCatalogContract;
import top.egon.cola.component.tianshu.api.catalog.HttpDdcLogicalCatalogClient;
import top.egon.cola.component.tianshu.api.catalog.HttpDdcLogicalCatalogClient.AuthorizationVO;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceProperties;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeBindingReader;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeBindingReader.DeploymentBindingVO;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayTianshuScopeBindingReader;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceTokenRequest;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Opt-in exact logical catalog transport and deployment-owned scope inventory. */
@Slf4j
@Configuration(value = "gatewayScopeBindingConfiguration", proxyBeanMethods = false)
@ConditionalOnProperty(prefix = GatewayScopeBindingConfiguration.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GatewayScopeBindingConfiguration.Properties.class)
public class GatewayScopeBindingConfiguration {
    public static final String PREFIX = "yuheng.admin.logical-catalog";
    private static final String PROPERTIES_BEAN = PREFIX + "-top.egon.cola.component.yuheng.admin.scope.config.GatewayScopeBindingConfiguration$Properties";

    @Bean("gatewayLogicalCatalogAuthorization")
    public Supplier<AuthorizationVO> authorization(
            @Qualifier(PROPERTIES_BEAN) Properties properties,
            @Qualifier(GatewayPersistenceProperties.BEAN_NAME) GatewayPersistenceProperties persistence,
            @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
            @Qualifier("idpStarterProperties") IdpStarterProperties idp,
            @Qualifier("trustedIssuerRegistry") TrustedIssuerRegistry issuers) {
        properties.validate();
        Long owner = context.installationTenantId();
        Long identity = persistence.getIdentityTenantId();
        if (owner == null || owner <= 0 || !owner.equals(persistence.getTenantId()) || identity == null || identity <= 0) { throw invalid(); }
        String identityTenant = identity.toString();
        var service = idp.getServiceClient();
        String registration = service.getTenantRegistrationIds().get(identityTenant);
        String appId = service.getAppId();
        var matching = issuers.activeIssuers().stream().filter(issuer ->
                issuer.realmType() == TrustedIssuerRegistry.RealmType.TENANT && identityTenant.equals(issuer.tenantId())).toList();
        if (registration == null || registration.isBlank() || appId == null || appId.isBlank() || matching.size() != 1) { throw invalid(); }
        AuthorizationVO authorization = new AuthorizationVO(new IdpServiceTokenRequest(registration, appId,
                properties.getResourceUri(), ServiceTokenContext.TENANT, identityTenant, Set.of(DdcLogicalCatalogContract.SCOPE)),
                matching.getFirst().issuer().toASCIIString());
        return () -> {
            if (!owner.equals(context.installationTenantId()) || !owner.equals(EgonColaTenantIdProvider.currentTenantId())) {
                throw new IllegalStateException("YUHENG_SCOPE_OWNER_CONTEXT_MISMATCH");
            }
            if (issuers.findByIssuer(authorization.issuer()).filter(matching.getFirst()::equals).isEmpty()) { throw invalid(); }
            return authorization;
        };
    }

    @Bean(name = "gatewayLogicalCatalogClient", destroyMethod = "close")
    public HttpDdcLogicalCatalogClient client(@Qualifier(PROPERTIES_BEAN) Properties properties,
            @Qualifier("idpServiceOAuth2Client") IdpServiceOAuth2Client oauth,
            @Qualifier("gatewayLogicalCatalogAuthorization") Supplier<AuthorizationVO> authorization,
            @Qualifier("jacksonObjectMapper") ObjectMapper json) {
        properties.validate();
        return new HttpDdcLogicalCatalogClient(properties.getOrigin(), oauth, authorization, json, properties.getTimeout());
    }

    @Bean("gatewayScopeBindingReader")
    public GatewayScopeBindingReader reader(@Qualifier(PROPERTIES_BEAN) Properties properties,
            @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
            @Qualifier("gatewayLogicalCatalogClient") DdcLogicalCatalogClient catalog) {
        properties.validate();
        return new GatewayTianshuScopeBindingReader(context, catalog, properties.getBindings());
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("YUHENG_LOGICAL_CATALOG_BINDING_INVALID"); }

    @Data
    @Validated
    @ConfigurationProperties(prefix = PREFIX, ignoreUnknownFields = false)
    public static class Properties {
        private boolean enabled;
        @NotNull private URI origin;
        @NotNull private URI resourceUri;
        @NotNull private Duration timeout = Duration.ofSeconds(2);
        @Valid @NotNull @Size(max = 256) private List<DeploymentBindingVO> bindings = List.of();

        public void validate() {
            if (origin == null || !"http".equals(origin.getScheme()) || origin.getHost() == null
                    || !origin.getHost().endsWith(".svc.cluster.local") || origin.getUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath())) || !origin.equals(origin.normalize())
                    || resourceUri == null || !"https".equals(resourceUri.getScheme()) || resourceUri.getHost() == null
                    || resourceUri.getUserInfo() != null || resourceUri.getRawQuery() != null || resourceUri.getRawFragment() != null
                    || !resourceUri.equals(resourceUri.normalize()) || timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0 || bindings == null || bindings.size() > 256) { throw invalid(); }
        }
    }
}
