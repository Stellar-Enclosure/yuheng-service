package top.egon.cola.component.yuheng.admin.mcp.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.yuheng.admin.mcp.service.McpNativeSiReader;
import top.egon.cola.component.yuheng.admin.mcp.service.McpPublishedSiResolver;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties.RpcConsumerModeEnum;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientTenantResolver;

import java.net.URI;
import java.util.Set;

/** Opt-in authority for one fixed publication domain; no transport or principal is synthesized here. */
@Configuration(value = "mcpNativeSiReaderConfiguration", proxyBeanMethods = false)
@ConditionalOnProperty(prefix = McpNativeSiReaderConfiguration.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpNativeSiReaderConfiguration.Properties.class)
public class McpNativeSiReaderConfiguration {
    public static final String PREFIX = "yuheng.admin.mcp.si-reader";
    private static final String PROPERTIES_BEAN = PREFIX + "-" +
            "top.egon.cola.component.yuheng.admin.mcp.config.McpNativeSiReaderConfiguration$Properties";

    @Bean("mcpNativeSiReaderBinding")
    public McpNativeSiReader.BindingVO mcpNativeSiReaderBinding(
            @Qualifier(PROPERTIES_BEAN) Properties properties,
            @Qualifier("rpcReferenceDefinitionResolver") RpcReferenceDefinitionResolver definitions,
            @Qualifier("rpcProcessIdentity") RpcProcessIdentity process,
            @Qualifier("idpStarterProperties") IdpStarterProperties idp) {
        if (properties.getAuthorizationMode() != AuthorizationModeEnum.LOCAL_AUTHORIZED_PUBLICATION) { throw invalid(); }
        RpcServiceTargetVO target = definitions.resolve(properties.getBindingId(), new RpcServiceIdentity(
                McpNativeSiReader.SERVICE, McpNativeSiReader.GROUP, McpNativeSiReader.VERSION),
                Set.of(McpNativeSiReader.METHOD)).target();
        if (target.version() != properties.getBindingVersion() || !idp.getRpc().isEnabled()
                || !idp.getRpc().getConsumer().isEnabled()) { throw invalid(); }
        var credentials = idp.getRpc().getConsumer().getBindings().values().stream().filter(binding ->
                McpNativeSiReader.SERVICE.equals(binding.getServiceName())
                        && McpNativeSiReader.GROUP.equals(binding.getGroup())
                        && McpNativeSiReader.VERSION.equals(binding.getVersion())
                        && McpNativeSiReader.METHOD.equals(binding.getFullMethodName())
                        && binding.getProcessIdentity() != null
                        && process.applicationName().equals(binding.getProcessIdentity().get("applicationName"))
                        && process.env().equals(binding.getProcessIdentity().get("env"))).toList();
        if (credentials.size() != 1) { throw invalid(); }
        var credential = credentials.getFirst();
        if (credential.getMode() != RpcConsumerModeEnum.SERVICE || credential.getScopeContext() != ServiceTokenContext.TENANT
                || !target.bindingId().equals(credential.getTransportBindingId())
                || !Long.valueOf(target.version()).equals(credential.getTransportBindingVersion())
                || !properties.getResourceUri().equals(credential.getResourceUri())
                || !properties.getRequiredScopes().equals(credential.getScopes())
                || credential.getTenantRegistrationIds() == null
                || credential.getTenantRegistrationIds().get(properties.getIdentityTenantId()) == null) { throw invalid(); }
        return new McpNativeSiReader.BindingVO(target, properties.getOwnerTenantId(), properties.getIdentityTenantId(),
                properties.getCallerAppCode(), properties.getBizCode(), properties.getEnvCode(),
                properties.getNamespaceCodes(), properties.getTimeoutMs());
    }

    @Bean("mcpNativeSiReaderProtoMapper")
    public DdcSiProtoMapper mcpNativeSiReaderProtoMapper() { return Mappers.getMapper(DdcSiProtoMapper.class); }

    @Bean("mcpNativeSiReader")
    public McpNativeSiReader mcpNativeSiReader(
            @Qualifier("rpcGenericInvoker") RpcGenericInvoker invoker,
            @Qualifier("mcpNativeSiReaderProtoMapper") DdcSiProtoMapper mapper,
            @Qualifier("mcpNativeSiReaderBinding") McpNativeSiReader.BindingVO binding,
            @Qualifier("rpcProcessIdentity") RpcProcessIdentity process,
            @Qualifier("idpRpcClientCredentialInterceptorFactory") IdpRpcClientCredentialInterceptorFactory credentials) {
        credentials.validateConfiguration();
        return new McpNativeSiReader(invoker, mapper, binding, process);
    }

    @Bean("idpRpcClientTenantResolver")
    public IdpRpcClientTenantResolver idpRpcClientTenantResolver(
            @Qualifier("mcpNativeSiReader") ObjectProvider<McpNativeSiReader> readers) {
        // Lazy lookup prevents a factory -> resolver -> invoker -> factory bean cycle.
        return (invocation, authentication) -> readers.getObject().resolveIdentityTenant(invocation);
    }

    @Bean("mcpPublishedSiResolver")
    public McpPublishedSiResolver mcpPublishedSiResolver(@Qualifier("mcpNativeSiReader") McpNativeSiReader reader) {
        return new McpPublishedSiResolver(reader);
    }

    private static IllegalStateException invalid() { return new IllegalStateException("YUHENG_MCP_SI_DEPLOYMENT_BINDING_INVALID"); }

    public enum AuthorizationModeEnum { LOCAL_AUTHORIZED_PUBLICATION }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Accessors(chain = true)
    @Validated
    @ConfigurationProperties(prefix = PREFIX, ignoreUnknownFields = false)
    public static class Properties {
        private boolean enabled;
        @NotNull private AuthorizationModeEnum authorizationMode;
        @NotBlank private String bindingId;
        @Positive private long bindingVersion;
        @Positive private long ownerTenantId;
        @NotBlank private String identityTenantId;
        @NotBlank private String callerAppCode;
        @NotBlank private String bizCode;
        @NotBlank private String envCode;
        @NotEmpty @Size(max = 64) private Set<@NotBlank String> namespaceCodes;
        @NotNull private URI resourceUri;
        @NotEmpty @Size(max = 64) private Set<@NotBlank String> requiredScopes;
        @Positive @Max(5000) private long timeoutMs;

        @AssertTrue
        public boolean isCanonicalResourceValid() {
            return resourceUri != null && "https".equals(resourceUri.getScheme()) && resourceUri.getHost() != null
                    && resourceUri.getUserInfo() == null && resourceUri.getRawQuery() == null && resourceUri.getRawFragment() == null;
        }
    }
}
