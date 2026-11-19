package top.egon.cola.component.yuheng.admin.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Clock;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.mcp.service.*;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.routing.repository.*;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

/** Explicit operator opt-in. Publisher processes cannot acquire registration or seed-writing authority. */
@Slf4j
@Configuration(value = "mcpMappingSeedConfiguration", proxyBeanMethods = false)
@ConditionalOnProperty(prefix = McpMappingSeedConfiguration.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpMappingSeedConfiguration.Properties.class)
public class McpMappingSeedConfiguration {
    public static final String PREFIX = "yuheng.admin.mcp.mapping-seed";
    private static final String PROPERTIES =
        PREFIX + "-top.egon.cola.component.yuheng.admin.mcp.config.McpMappingSeedConfiguration$Properties";
    @Bean("mcpRegisteredSiIdentityFileSource")
    McpRegisteredSiIdentityFileSource identities(@Qualifier(PROPERTIES) Properties properties,
        @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
        @Qualifier("jacksonObjectMapper") ObjectMapper mapper, Environment environment) {
        if (!"ADMIN".equals(environment.getProperty("yuheng.admin.exposure.role", "ADMIN")))
            throw new IllegalStateException("YUHENG_MCP_MAPPING_SEED_ADMIN_ROLE_REQUIRED");
        return new McpRegisteredSiIdentityFileSource(Path.of(properties.getRegistrationDescriptorPath()),
            properties.getRegistrationDescriptorSha256(), context.installationTenantId(), mapper);
    }
    @Bean("mcpMappingSeedService")
    McpMappingSeedService service(@Qualifier("mpGatewayGroupRepository") GatewayGroupRepository groups,
        @Qualifier("mpGatewayDraftMetadataRepository") GatewayDraftJpaRepository drafts,
        @Qualifier("mpGatewayDraftRepository") GatewayDraftRepository policies,
        @Qualifier("mcpRegisteredSiIdentityFileSource") McpRegisteredSiIdentityFileSource identities,
        @Qualifier("mcpReleaseContentFactory") McpReleaseContentFactory compiler,
        @Qualifier("idempotencyRepository") IdempotencyRepository idempotency,
        @Qualifier("gatewayAuditLogRepository") GatewayAuditLogRepository audits,
        @Qualifier("gatewayExposureTransactionTemplate") TransactionTemplate transactions,
        @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
        @Qualifier("jacksonObjectMapper") ObjectMapper mapper, @Qualifier("gatewayProjectionClock") Clock clock) {
        return new McpMappingSeedService(
            groups, drafts, policies, identities, compiler, idempotency, audits, transactions, context, mapper, clock);
    }
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Validated
    @ConfigurationProperties(prefix = PREFIX, ignoreUnknownFields = false)
    public static class Properties {
        private boolean enabled;
        @NotBlank private String registrationDescriptorPath;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String registrationDescriptorSha256;
    }
}
