package top.egon.cola.component.yuheng.admin.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpServerBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpCapabilityPreviewVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpServerRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpArtifactMetadataRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpCapabilityDraftRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpRemoteProviderRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpTaskRepository;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.rule.service.GatewayRuleCanonicalizer;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminIdempotencyConflictException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only legacy MCP catalog diagnostics and mapping preview.
 * Native definitions are owned by Tianshu; this service has no native asset
 * mutation or App artifact transport. The existing task bridge stays until
 * the separately ordered execution-service migration.
 */
@Slf4j
@Service("mcpControlPlaneService")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional(readOnly = true)
public class McpControlPlaneService {

    private static final String TASK_IDEMPOTENCY_SCOPE = "YUHENG_MCP_TASK";

    @Qualifier("mcpServerRepository")
    private final McpServerRepository servers;
    @Qualifier("mpMcpCapabilityDraftRepository")
    private final McpCapabilityDraftRepository capabilities;
    @Qualifier("mpMcpRemoteProviderRepository")
    private final McpRemoteProviderRepository remote;
    @Qualifier("mpMcpArtifactMetadataRepository")
    private final McpArtifactMetadataRepository artifacts;
    @Qualifier("mpMcpTaskRepository")
    private final McpTaskRepository tasks;
    @Qualifier("idempotencyRepository")
    private final IdempotencyRepository idempotency;
    @Qualifier("gatewayAuditLogRepository")
    private final GatewayAuditLogRepository audits;
    @Qualifier("mcpReleaseContentFactory")
    private final McpReleaseContentFactory contentFactory;
    @Qualifier("mcpValidationService")
    private final McpValidationService validation;
    @Qualifier("gatewayProjectionClock")
    private final Clock clock;

    private final GatewayRuleCanonicalizer canonicalizer = new GatewayRuleCanonicalizer();

    public List<McpServerVO> listServers(String gatewayGroupId) {
        return servers
                .findAllByGatewayGroupIdAndDeletedFalseOrderByServerCode(
                        required(gatewayGroupId, "gatewayGroupId")
                )
                .stream()
                .map(this::view)
                .toList();
    }

    public McpServerVO getServer(String id) {
        return view(requiredServer(id));
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpCapabilityRecordBO> capabilities(
            String gatewayGroupId,
            String serverId,
            top.egon.cola.component.yuheng.admin.mcp.domain.enums.McpCapabilityKindEnum kind) {
        requiredServerInGroup(serverId, gatewayGroupId);
        return capabilities.load(gatewayGroupId)
                .capabilities(kind)
                .stream()
                .filter(item -> item.getServerId().equals(serverId))
                .toList();
    }

    public McpCapabilityPreviewVO preview(String gatewayGroupId) {
        McpRuleContent content = contentFactory.preview(gatewayGroupId);
        return new McpCapabilityPreviewVO(content, validation.validate(content));
    }

    public top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpValidationReportVO validate(
            String gatewayGroupId) {
        return validation.validate(contentFactory.preview(gatewayGroupId));
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteProviderDraftBO> providers(
            String gatewayGroupId) {
        return remote.providers(gatewayGroupId);
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteCapabilityBO>
            remoteCapabilities(String providerId) {
        return remote.capabilities(providerId);
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteMountDraftBO> mounts(
            String gatewayGroupId) {
        return remote.mounts(gatewayGroupId);
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpArtifactMetadataBO> artifacts(
            String gatewayGroupId) {
        return artifacts.list(gatewayGroupId);
    }

    public top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpArtifactMetadataBO artifact(String id) {
        return artifacts.find(id).orElseThrow(() -> notFound(
                "MCP App artifact",
                id
        ));
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO> tasks(
            String tenantId,
            String clientId) {
        return tasks.list(required(tenantId, "tenantId"), clientId);
    }

    public top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO task(String id) {
        return tasks.find(id).orElseThrow(() -> notFound("MCP Task", id));
    }

    // Temporary legacy task bridge; retired with its controller in Step 35.
    @Transactional
    public boolean cancelTask(
            String id,
            long expectedRevision,
            String idempotencyKey,
            AdminActor actor,
            RequestAuditContext request) {
        String scopeId = "TASK:" + required(id, "id");
        String digest = digest("CANCEL_TASK", Map.of(
                "id", id,
                "expectedRevision", expectedRevision
        ));
        Boolean replay = replayTask(scopeId, idempotencyKey, digest);
        if (replay != null) {
            return replay;
        }
        top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO task = task(id);
        Instant now = clock.instant();
        boolean cancelled = tasks.cancel(id, expectedRevision, now);
        if (!cancelled) {
            return false;
        }
        audit(
                actor,
                request,
                "MCP_TASK",
                id,
                "CANCEL",
                Map.of(
                        "tenantId", task.getTenantId(),
                        "serverCode", task.getServerCode(),
                        "toolName", task.getToolName()
                ),
                now
        );
        idempotency.save(new top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO(
                TASK_IDEMPOTENCY_SCOPE,
                scopeId,
                required(idempotencyKey, "idempotencyKey"),
                digest,
                id,
                Map.of("cancelled", true),
                now,
                now.plus(Duration.ofDays(7))
        ));
        return true;
    }

    private Boolean replayTask(
            String scopeId,
            String idempotencyKey,
            String payloadDigest) {
        String key = required(idempotencyKey, "idempotencyKey");
        top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO existing = idempotency.find(
                TASK_IDEMPOTENCY_SCOPE,
                scopeId,
                key
        ).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.getPayloadSha256().equals(payloadDigest)) {
            throw new GatewayAdminIdempotencyConflictException();
        }
        return Boolean.TRUE.equals(existing.getResponse().get("cancelled"));
    }

    private void audit(
            AdminActor actor,
            RequestAuditContext request,
            String resourceType,
            String resourceId,
            String action,
            Map<String, Object> summary,
            Instant now) {
        audits.save(new GatewayAuditLogBO(
                SnowflakeIdGenerator.nextId(),
                actor.actorId(),
                actor.actorType().name(),
                "MANAGEMENT_API",
                request.requestId(),
                request.traceId(),
                resourceType,
                resourceId,
                action,
                GatewayAuditLogBO.sanitized(null),
                GatewayAuditLogBO.sanitized(summary),
                null,
                null,
                true,
                null,
                now
        ));
    }

    private McpServerBO requiredServer(String id) {
        return servers.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> notFound("MCP Server", id));
    }

    private void requiredServerInGroup(String id, String gatewayGroupId) {
        requireGroup(gatewayGroupId, requiredServer(id).getGatewayGroupId());
    }

    private void requireGroup(String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(
                    "MCP resource belongs to another Gateway Group"
            );
        }
    }

    private String digest(String action, Object command) {
        return GatewayRuleCanonicalizer.sha256(canonicalizer.canonicalBytes(
                Map.of("action", action, "command", command)
        ));
    }

    private GatewayAdminNotFoundException notFound(
            String resource,
            String id) {
        return new GatewayAdminNotFoundException(
                resource + " " + id + " was not found"
        );
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private McpServerVO view(McpServerBO server) {
        return new McpServerVO(
                server.getId(),
                server.getGatewayGroupId(),
                server.getServerCode(),
                server.getDisplayName(),
                server.getDescription(),
                server.getInstructions(),
                server.getDialects(),
                server.getResourceUri(),
                server.getListCacheTtlSeconds(),
                server.isEnabled(),
                server.getRevision(),
                server.getCreatedAt(),
                server.getUpdatedAt()
        );
    }
}
