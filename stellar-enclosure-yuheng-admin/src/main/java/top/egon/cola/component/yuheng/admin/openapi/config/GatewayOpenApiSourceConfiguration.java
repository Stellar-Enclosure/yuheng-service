package top.egon.cola.component.yuheng.admin.openapi.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceProperties;
import top.egon.cola.component.yuheng.admin.openapi.client.GatewayOpenApiFetchException;
import top.egon.cola.component.yuheng.admin.openapi.client.GatewayOpenApiTokenSupplier;
import top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiSourceInventory;
import top.egon.cola.component.yuheng.admin.openapi.service.GatewayOpenApiSourceInventory.SourceVO;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeService;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeBindingReader;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceTokenRequest;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Set;

/** Explicit deployment source approvals and one trusted tenant credential family. */
@Configuration(value="gatewayOpenApiSourceConfiguration",proxyBeanMethods=false)
@ConditionalOnProperty(prefix="yuheng.admin.openapi",name="enabled",havingValue="true")
@EnableConfigurationProperties(GatewayOpenApiSourceConfiguration.Properties.class)
public class GatewayOpenApiSourceConfiguration {
    private static final String PROPERTIES="yuheng.admin.openapi.sources-top.egon.cola.component.yuheng.admin.openapi.config.GatewayOpenApiSourceConfiguration$Properties";
    @Bean("gatewayOpenApiSourceInventory")
    public GatewayOpenApiSourceInventory inventory(@Qualifier(PROPERTIES) Properties properties,
            @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
            @Qualifier("mpGatewayApplicationRepository") GatewayApplicationRepository applications,
            GatewayScopeService scopes,@Qualifier("gatewayOpenApiClock") Clock clock,
            @Qualifier("gatewayScopeBindingReader") GatewayScopeBindingReader approvedLogicalCatalog) {
        return new GatewayOpenApiSourceInventory(context,applications,scopes,properties.getEntries(),clock);
    }

    @Bean("gatewayOpenApiTokenSupplier")
    public GatewayOpenApiTokenSupplier tokens(@Qualifier("gatewayOpenApiSourceInventory") GatewayOpenApiSourceInventory inventory,
            @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
            @Qualifier(GatewayPersistenceProperties.BEAN_NAME) GatewayPersistenceProperties persistence,
            @Qualifier("idpStarterProperties") IdpStarterProperties idp,
            @Qualifier("trustedIssuerRegistry") TrustedIssuerRegistry issuers,
            @Qualifier("idpServiceOAuth2Client") IdpServiceOAuth2Client oauth,
            @Qualifier("gatewayOpenApiReadTimeout") java.time.Duration timeout) {
        Long owner=context.installationTenantId();Long identity=persistence.getIdentityTenantId();
        if(owner==null||owner<=0||!owner.equals(persistence.getTenantId())||identity==null||identity<=0)throw invalid();
        String tenant=identity.toString();var client=idp.getServiceClient();String registration=client.getTenantRegistrationIds().get(tenant);
        String appId=client.getAppId();var matching=issuers.activeIssuers().stream().filter(issuer->
                issuer.realmType()==TrustedIssuerRegistry.RealmType.TENANT&&tenant.equals(issuer.tenantId())).toList();
        if(registration==null||registration.isBlank()||appId==null||appId.isBlank()||matching.size()!=1)throw invalid();
        var fixed=matching.getFirst();
        return (resource,scope)->{
            if(!owner.equals(context.installationTenantId())||!owner.equals(EgonColaTenantIdProvider.currentTenantId())
                    ||issuers.findByIssuer(fixed.issuer().toASCIIString()).filter(fixed::equals).isEmpty())throw invalid();
            inventory.authorizeResource(resource,scope);
            return oauth.authorizeBounded(new IdpServiceTokenRequest(registration,appId,resource,ServiceTokenContext.TENANT,tenant,Set.of(scope)),fixed.issuer().toASCIIString(),timeout).getTokenValue();
        };
    }
    private static GatewayOpenApiFetchException invalid(){return new GatewayOpenApiFetchException("YUHENG_OPENAPI_OAUTH_NOT_CONFIGURED",false,"OpenAPI tenant credential binding is unavailable");}
    @Data @Validated
    @ConfigurationProperties(prefix="yuheng.admin.openapi.sources",ignoreUnknownFields=false)
    public static class Properties{
        @Valid @NotEmpty @Size(max=256) private List<SourceVO> entries=List.of();
    }
}
