package top.egon.cola.component.yuheng.mcp.engine.mcp.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.extension.EgonColaRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpManagementAuditDAO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpManagementAuditRecordPO;

/**
 * 中文说明：MCP 管理写入与领域审计共享同一受守卫本地事务，只接受明确字段，不接收任意日志正文。
 * English summary: Appends bounded management audit facts in the mutation's guarded local transaction, never tokens
 * or arbitrary request payloads. Schema ownership remains with the control-plane migration owner.
 */
@Slf4j
@Repository("mcpManagementAuditRepository")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class McpManagementAuditRepository extends EgonColaRepository<McpManagementAuditDAO, McpManagementAuditRecordPO>
    implements BaseForwardConverter<McpManagementAuditRepository.AuditCommand, McpManagementAuditRecordPO> {
    private static final AuditMapping MAPPING = Mappers.getMapper(AuditMapping.class);

    @Qualifier("mcpManagementAuditDAO") private final McpManagementAuditDAO mapper;
    @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure."
        + "EgonColaMybatisPlusProperties")
    private final EgonColaMybatisPlusProperties properties;

    @Override
    public McpManagementAuditDAO getBaseMapper() {
        return mapper;
    }
    @Override
    protected EgonColaMybatisPlusProperties getProperties() {
        return properties;
    }

    /**
     * 中文说明：审计失败使整个管理写事务失败。 English summary: An audit failure rolls back the management mutation.
     */
    public void record(AuditCommand command) {
        Objects.requireNonNull(command, "command");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("MCP_MANAGEMENT_WRITE_TRANSACTION_REQUIRED");
        }
        if (!save(toTarget(command)))
            throw new IllegalStateException("MCP_MANAGEMENT_AUDIT_UNAVAILABLE");
    }

    @Override
    public McpManagementAuditRecordPO toTarget(AuditCommand command) {
        McpManagementAuditRecordPO row = MAPPING.toRow(Objects.requireNonNull(command, "command"));
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("schemaVersion", 1);
        summary.put("identityTenantId", command.identityTenantId());
        summary.put("clientId", command.clientId());
        summary.put("issuer", command.issuer().toASCIIString());
        summary.put("resource", command.resource().toASCIIString());
        summary.put("serverCode", command.serverCode());
        summary.put("toolName", command.toolName());
        if (command.argumentDigest() != null)
            summary.put("argumentDigest", command.argumentDigest());
        if (command.expectedRevision() != null)
            summary.put("expectedRevision", command.expectedRevision().toString());
        row.setAfterSummary(summary);
        return row;
    }

    /**
     * 中文说明：固定成功审计合同；不包含原始 token、参数、依赖绑定或自由文本。
     * English summary: Fixed successful audit facts, without raw tokens, arguments, bindings or free-form messages.
     */
    public record AuditCommand(String actorId, String identityTenantId, String clientId, URI issuer, URI resource,
        String requestId, String traceId, String resourceType, String resourceId, String action, String serverCode,
        String toolName, String argumentDigest, Long expectedRevision, Instant occurredAt) {
        public AuditCommand {
            actorId = required(actorId, 128);
            identityTenantId = required(identityTenantId, 128);
            clientId = required(clientId, 128);
            requestId = optional(requestId, 128);
            traceId = optional(traceId, 128);
            resourceId = required(resourceId, 128);
            serverCode = required(serverCode, 128);
            toolName = required(toolName, 256);
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (!issuer.isAbsolute() || !resource.isAbsolute()
                || !("MCP_APPROVAL".equals(resourceType) && "ISSUE".equals(action)
                    || "MCP_TASK".equals(resourceType) && "CANCEL".equals(action))
                || argumentDigest != null && !argumentDigest.matches("[0-9a-f]{64}")
                || expectedRevision != null && expectedRevision < 0) {
                throw new IllegalArgumentException("MCP_MANAGEMENT_AUDIT_INVALID");
            }
        }
        private static String optional(String value, int max) {
            return value == null ? null : required(value, max);
        }
        private static String required(String value, int max) {
            if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("MCP_MANAGEMENT_AUDIT_INVALID");
            }
            return value;
        }
    }

    @Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, builder = @Builder(disableBuilder = true))
    interface AuditMapping {
        @BeanMapping(ignoreByDefault = true)
        @Mapping(target = "actorId", source = "actorId")
        @Mapping(target = "actorType", constant = "USER")
        @Mapping(target = "source", constant = "MANAGEMENT_API")
        @Mapping(target = "requestId", source = "requestId")
        @Mapping(target = "traceId", source = "traceId")
        @Mapping(target = "resourceType", source = "resourceType")
        @Mapping(target = "resourceId", source = "resourceId")
        @Mapping(target = "action", source = "action")
        @Mapping(target = "successful", constant = "true")
        @Mapping(target = "occurredAt", source = "occurredAt")
        McpManagementAuditRecordPO toRow(AuditCommand command);
    }
}
