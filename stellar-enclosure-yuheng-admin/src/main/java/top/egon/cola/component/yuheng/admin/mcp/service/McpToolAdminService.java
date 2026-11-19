package top.egon.cola.component.yuheng.admin.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.mcp.converter.McpApiMappingConverter;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpServerBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpManagedToolOverrideMutationDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpToolMutationControlDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolProjectionVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpRemoteToolVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMutationResultVO;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpServerRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpRemoteToolDraftRepository;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayPolicyDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftVO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftRepository;
import top.egon.cola.component.yuheng.admin.rule.service.GatewayRuleCanonicalizer;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminIdempotencyConflictException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminRevisionConflictException;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Managed overrides update the desired mapping policy; legacy remote tools remain a read-only archive. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Service("mcpToolAdminService")
public class McpToolAdminService {
    private static final String IDEMPOTENCY_SCOPE = "YUHENG_MCP_API_MAPPING_OVERRIDE";
    private static final List<String> RISK_LEVELS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    @Qualifier("mcpReleaseContentFactory") private final McpReleaseContentFactory contentFactory;
    @Qualifier("mpGatewayDraftRepository") private final GatewayDraftRepository policyStore;
    @Qualifier("mpGatewayCatalogRepository") private final GatewayCatalogRepository catalog;
    @Qualifier("mpMcpRemoteToolDraftRepository") private final McpRemoteToolDraftRepository remoteTools;
    @Qualifier("mcpServerRepository") private final McpServerRepository servers;
    @Qualifier("mpGatewayDraftMetadataRepository") private final GatewayDraftJpaRepository drafts;
    @Qualifier("idempotencyRepository") private final IdempotencyRepository idempotency;
    @Qualifier("gatewayAuditLogRepository") private final GatewayAuditLogRepository audits;
    @Qualifier("mcpApiMappingConverter") private final McpApiMappingConverter mappingConverter;
    @Qualifier("jacksonObjectMapper") private final ObjectMapper objectMapper;
    @Qualifier("gatewayProjectionClock") private final Clock clock;
    private final GatewayRuleCanonicalizer canonicalizer = new GatewayRuleCanonicalizer();

    @Transactional(readOnly = true)
    public List<McpManagedToolVO> managedTools(String gatewayGroupId, String serverId) {
        long owner = trustedOwner();
        GatewayDraftBO head = requiredDraft(gatewayGroupId);
        List<GatewayPolicyDraftBO> policies = mappingPolicies(head.getGatewayGroupId());
        if (serverId != null && policies.stream().noneMatch(policy ->
                intent(policy).siServer().resourceId().equals(serverId))) throw notFound("MCP mapping SI resource", serverId);
        if (policies.isEmpty()) return List.of();
        JsonNode section = contentFactory.compileMappingSection(owner, view(head, policies));
        if (section == null) throw invalidMapping();
        List<McpManagedToolVO> result = new ArrayList<>();
        for (var policy : policies) {
            var intent = intent(policy);
            if (serverId != null && !serverId.equals(intent.siServer().resourceId())) continue;
            result.add(managedView(head, policy, mappedTool(section, toolId(intent))));
        }
        return List.copyOf(result);
    }

    @Transactional
    public McpMutationResultVO putOverride(String toolId, McpManagedToolOverrideMutationDTO command,
            String idempotencyKey, AdminActor actor, RequestAuditContext request) {
        requireWrite(actor);
        long owner = trustedOwner();
        GatewayDraftBO head = requiredDraft(command.gatewayGroupId());
        GatewayPolicyDraftBO policy = requiredMapping(head.getGatewayGroupId(), toolId);
        var original = intent(policy);
        String selected = optional(command.serverId());
        if (selected != null && !selected.equals(original.siServer().resourceId())) {
            throw new IllegalArgumentException("serverId must identify the exact approved mapping SI resource; retarget through the mapping policy");
        }
        JsonNode current = contentFactory.compileMappingSection(owner, view(head, List.of(policy)));
        McpManagedToolVO managed = managedView(head, policy, mappedTool(current, toolId));
        String digest = digest("PUT_MAPPING_TOOL_OVERRIDE", Map.of("toolId", toolId, "command", command));
        McpMutationResultVO replay = replay(head.getGatewayGroupId(), idempotencyKey, digest);
        if (replay != null) return replay;
        assertRevision(head, command.expectedRevision(), command.expectedDraftRevision());
        if (Boolean.TRUE.equals(command.enabled())) {
            throw new IllegalArgumentException("managed Tool override can only disable a Tool");
        }
        Set<String> additions = clean(command.additionalPermissions());
        if (!additions.containsAll(original.additionalPermissions())) {
            throw new IllegalArgumentException("additionalPermissions cannot remove existing permissions");
        }
        String minimumRisk = optional(command.minimumRiskLevel());
        if (minimumRisk != null && risk(minimumRisk) < risk(managed.codeRiskLevel())) {
            throw new IllegalArgumentException("minimumRiskLevel cannot lower annotation riskLevel");
        }
        if (original.minimumRiskLevel() != null
                && (minimumRisk == null || risk(minimumRisk) < risk(original.minimumRiskLevel()))) {
            throw new IllegalArgumentException("minimumRiskLevel cannot lower existing minimum risk");
        }
        if (original.disabled() && !Boolean.FALSE.equals(command.enabled())) {
            throw new IllegalArgumentException("disabled managed Tool can only be restored by deleting its override");
        }
        var updated = new McpApiMappingContentBO(original.sourceOperation(), original.siServer(), additions,
                minimumRisk, original.disabled() || Boolean.FALSE.equals(command.enabled()));
        writePolicy(owner, head, policy, updated, actor);
        return finish(head, toolId, "MCP_MANAGED_TOOL_OVERRIDE", "UPDATE", command.changeReason(),
                idempotencyKey, digest, actor, request,
                Map.of("operationId", original.sourceOperation().operationId(), "policyId", policy.getPolicyId()), clock.instant());
    }

    /** Explicit reset removes only operator restrictions after current source and SI authorization. */
    @Transactional
    public McpMutationResultVO deleteOverride(String toolId, McpToolMutationControlDTO control,
            String idempotencyKey, AdminActor actor, RequestAuditContext request) {
        requireWrite(actor);
        long owner = trustedOwner();
        GatewayDraftBO head = requiredDraft(control.gatewayGroupId());
        GatewayPolicyDraftBO policy = requiredMapping(head.getGatewayGroupId(), toolId);
        var original = intent(policy);
        JsonNode current = contentFactory.compileMappingSection(owner, view(head, List.of(policy)));
        managedView(head, policy, mappedTool(current, toolId));
        String digest = digest("DELETE_MAPPING_TOOL_OVERRIDE", Map.of("toolId", toolId, "control", control));
        McpMutationResultVO replay = replay(head.getGatewayGroupId(), idempotencyKey, digest);
        if (replay != null) return replay;
        assertRevision(head, control.expectedRevision(), control.expectedDraftRevision());
        var reset = new McpApiMappingContentBO(original.sourceOperation(), original.siServer(), Set.of(), null, false);
        writePolicy(owner, head, policy, reset, actor);
        return finish(head, toolId, "MCP_MANAGED_TOOL_OVERRIDE", "DELETE", control.changeReason(),
                idempotencyKey, digest, actor, request,
                Map.of("operationId", original.sourceOperation().operationId(), "policyId", policy.getPolicyId(), "explicitReset", true), clock.instant());
    }

    private void writePolicy(long owner, GatewayDraftBO head, GatewayPolicyDraftBO original,
            McpApiMappingContentBO content, AdminActor actor) {
        GatewayPolicyDraftBO updated = new GatewayPolicyDraftBO(head.getGatewayGroupId(), original.getPolicyId(),
                McpApiMappingContentBO.POLICY_TYPE, McpApiMappingContentBO.POLICY_SCOPE,
                content.content(), original.isEnabled(), clock.instant(), actor.actorId());
        // The candidate is freshly authorized before persistence; the aggregate CAS fences concurrent writers.
        mappedTool(contentFactory.compileMappingSection(owner, view(head, List.of(updated))), toolId(content));
        policyStore.upsertPolicy(updated);
    }

    private McpManagedToolVO managedView(GatewayDraftBO head, GatewayPolicyDraftBO policy, McpRuntimeTool tool) {
        var intent = intent(policy);
        var sources = catalog.loadCurrentOperationDefinitions(head.getGatewayGroupId()).stream()
                .filter(source -> intent.sourceOperation().operationId().equals(source.operation().getId())
                        && intent.sourceOperation().operationKey().equals(source.operation().getOperationKey())
                        && intent.sourceOperation().definitionDigest().equals(source.definition().getDefinitionSha256()))
                .toList();
        if (sources.size() != 1 || !(sources.getFirst().definition().getAttributes().get("mcpExposure") instanceof Map<?, ?> exposure)) {
            throw invalidMapping();
        }
        Set<String> codePermissions = clean(exposure.get("requiredPermissions"));
        String codeRisk = defaulted(optionalText(exposure.get("riskLevel")), "LOW");
        return mappingConverter.toTarget(new McpManagedToolProjectionVO(
                head.getGatewayGroupId(), intent.sourceOperation().operationKey(),
                intent.siServer().resourceId(), intent.siServer().resourceKey(), intent.siServer().resourceId(),
                codePermissions, intent.additionalPermissions(), codeRisk, intent.minimumRiskLevel(),
                head.getRevision(), tool));
    }

    private List<GatewayPolicyDraftBO> mappingPolicies(String group) {
        List<GatewayPolicyDraftBO> result = policyStore.policies(group).stream()
                .filter(policy -> McpApiMappingContentBO.POLICY_TYPE.equals(policy.getPolicyType())).toList();
        Set<String> ids = new java.util.HashSet<>();
        for (var policy : result) {
            if (!group.equals(policy.getGatewayGroupId()) || !ids.add(toolId(intent(policy)))) throw invalidMapping();
        }
        return result;
    }

    private GatewayPolicyDraftBO requiredMapping(String group, String id) {
        return mappingPolicies(group).stream().filter(policy -> toolId(intent(policy)).equals(required(id, "toolId")))
                .findFirst().orElseThrow(() -> notFound("MCP mapping", id));
    }

    private McpApiMappingContentBO intent(GatewayPolicyDraftBO policy) {
        if (!McpApiMappingContentBO.POLICY_SCOPE.equals(policy.getPolicyScope())) throw invalidMapping();
        return McpApiMappingContentBO.fromContent(policy.getContent());
    }

    private String toolId(McpApiMappingContentBO content) {
        return McpReleaseContentFactory.managedToolId(content.siServer().resourceKey(), content.sourceOperation().operationKey());
    }

    private McpRuntimeTool mappedTool(JsonNode section, String id) {
        if (section == null || !section.path("mappings").isArray()) throw invalidMapping();
        List<JsonNode> matches = new ArrayList<>();
        section.path("mappings").forEach(entry -> { if (id.equals(entry.path("toolId").textValue())) matches.add(entry); });
        if (matches.size() != 1) throw invalidMapping();
        try { return objectMapper.treeToValue(matches.getFirst().path("tool"), McpRuntimeTool.class); }
        catch (JsonProcessingException invalid) { throw invalidMapping(); }
    }

    private GatewayDraftBO requiredDraft(String group) {
        return drafts.findById(required(group, "gatewayGroupId")).orElseThrow(() -> notFound("Gateway Draft", group));
    }

    private GatewayDraftVO view(GatewayDraftBO head, List<GatewayPolicyDraftBO> policies) {
        return new GatewayDraftVO(head.getGatewayGroupId(), head.getRevision(), head.getBasedOnReleaseId(),
                head.getStatus(), head.getChangeSummary(), List.of(), policies, head.getUpdatedAt());
    }

    private void assertRevision(GatewayDraftBO head, long expectedResource, long expectedDraft) {
        head.assertEditable(expectedDraft);
        if (expectedResource != head.getRevision()) throw new GatewayAdminRevisionConflictException(head.getRevision());
    }

    private long trustedOwner() {
        Long owner = EgonColaTenantIdProvider.currentTenantId();
        if (owner == null || owner <= 0) throw new IllegalStateException("YUHENG_MCP_OWNER_CONTEXT_REQUIRED");
        return owner;
    }

    private void requireWrite(AdminActor actor) {
        if (actor == null || !actor.oauthScopes().contains(GatewayPermissionCodes.MCP_WRITE)
                || !(actor.scopes().contains(GatewayPermissionCodes.MCP_WRITE) || actor.scopes().contains("*"))) {
            throw new AccessDeniedException("YUHENG_MCP_WRITE_AUTHORIZATION_REQUIRED");
        }
    }

    private McpValidationException invalidMapping() {
        return new McpValidationException(McpApiMappingContentBO.INVALID, "mapping", "The desired MCP mapping is unavailable");
    }

    @Transactional(readOnly = true)
    public List<McpRemoteToolVO> remoteTools(
            String gatewayGroupId,
            String serverId) {
        String groupId = required(gatewayGroupId, "gatewayGroupId");
        Map<String, McpServerBO> serverById = servers
                .findAllByGatewayGroupIdAndDeletedFalseOrderByServerCode(
                        groupId
                ).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        McpServerBO::getId,
                        java.util.function.Function.identity()
                ));
        if (serverId != null && !serverById.containsKey(serverId)) {
            throw notFound("MCP Server", serverId);
        }
        return remoteTools.load(groupId).stream()
                .filter(item -> serverId == null
                        || serverId.equals(item.getServerId()))
                .map(item -> remoteView(item, serverById.get(item.getServerId())))
                .toList();
    }

    private McpRemoteToolVO remoteView(
            top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteToolDraftBO value,
            McpServerBO server) {
        if (server == null) {
            throw notFound("MCP Server", value.getServerId());
        }
        Map<String, Object> content = value.getContent();
        return new McpRemoteToolVO(
                value.getId(),
                value.getGatewayGroupId(),
                value.getServerId(),
                server.getServerCode(),
                value.getName(),
                optionalText(content.get("description")),
                value.getRemoteMountId(),
                content.get("inputSchema"),
                content.get("outputSchema"),
                stringMap(content.get("annotations")),
                clean(content.get("requiredPermissions")),
                defaulted(optionalText(content.get("riskLevel")), "LOW"),
                flag(content.get("idempotent")),
                value.isEnabled(),
                value.getRevision()
        );
    }

    private top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMutationResultVO finish(
            GatewayDraftBO draft,
            String resourceId,
            String resourceType,
            String action,
            String changeReason,
            String idempotencyKey,
            String payloadDigest,
            AdminActor actor,
            RequestAuditContext request,
            Map<String, Object> summary,
            Instant now) {
        draft.touch(required(changeReason, "changeReason"), actor.actorId(), now);
        draft = drafts.save(draft);
        var result = new top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMutationResultVO(
                draft.getRevision(),
                resourceId,
                draft.getRevision(),
                false
        );
        idempotency.save(new top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO(
                IDEMPOTENCY_SCOPE,
                draft.getGatewayGroupId(),
                required(idempotencyKey, "idempotencyKey"),
                payloadDigest,
                resourceId,
                Map.of(
                        "draftRevision", result.draftRevision(),
                        "resourceRevision", result.resourceRevision()
                ),
                now,
                now.plus(Duration.ofDays(7))
        ));
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
        return result;
    }

    private top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMutationResultVO replay(
            String gatewayGroupId,
            String idempotencyKey,
            String payloadDigest) {
        top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO existing = idempotency.find(
                IDEMPOTENCY_SCOPE,
                gatewayGroupId,
                required(idempotencyKey, "idempotencyKey")
        ).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.getPayloadSha256().equals(payloadDigest)) {
            throw new GatewayAdminIdempotencyConflictException();
        }
        return new top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMutationResultVO(
                number(existing.getResponse(), "draftRevision"),
                existing.getResourceId(),
                number(existing.getResponse(), "resourceRevision"),
                true
        );
    }

    private String digest(String action, Object command) {
        return GatewayRuleCanonicalizer.sha256(canonicalizer.canonicalBytes(
                Map.of("action", action, "command", command)
        ));
    }

    private int risk(String value) {
        int level = RISK_LEVELS.indexOf(value);
        if (level < 0) {
            throw new IllegalArgumentException(
                    "unsupported MCP Tool risk level " + value
            );
        }
        return level;
    }

    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(
                key.toString(),
                Objects.toString(item, "")
        ));
        return Map.copyOf(result);
    }

    private Set<String> clean(Object value) {
        if (value == null) {
            return Set.of();
        }
        Iterable<?> values = value instanceof Iterable<?> iterable
                ? iterable
                : List.of(value);
        Set<String> result = new LinkedHashSet<>();
        values.forEach(item -> {
            String text = optionalText(item);
            if (text != null) {
                result.add(text);
            }
        });
        return Set.copyOf(result);
    }

    private boolean flag(Object value) {
        return value instanceof Boolean bool
                ? bool
                : Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private long number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof Number number
                ? number.longValue()
                : Long.parseLong(item.toString());
    }

    private String required(String value, String field) {
        String result = optional(value);
        if (result == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return result;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String optionalText(Object value) {
        return value == null ? null : optional(value.toString());
    }

    private String defaulted(String value, String defaultValue) {
        String result = optional(value);
        return result == null ? defaultValue : result;
    }

    private GatewayAdminNotFoundException notFound(
            String resource,
            String id) {
        return new GatewayAdminNotFoundException(
                resource + " " + id + " was not found"
        );
    }
}
