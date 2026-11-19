package top.egon.cola.component.yuheng.admin.mcp.service;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpMappingSeedFreezeCommand;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.routing.repository.*;
import top.egon.cola.component.yuheng.admin.shared.domain.*;
import top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.*;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

/** Explicit composition approval. No release, exposure, publisher or draft mutation port is injected. */
@Slf4j
@Validated
@RequiredArgsConstructor
public class McpMappingSeedService {
    public static final String IDEMPOTENCY_SCOPE = "YUHENG_MCP_MAPPING_SEED";
    @Qualifier("mpGatewayGroupRepository") private final GatewayGroupRepository groups;
    @Qualifier("mpGatewayDraftMetadataRepository") private final GatewayDraftJpaRepository drafts;
    @Qualifier("mpGatewayDraftRepository") private final GatewayDraftRepository policies;
    @Qualifier("mcpRegisteredSiIdentityFileSource") private final McpRegisteredSiIdentityFileSource identities;
    @Qualifier("mcpReleaseContentFactory") private final McpReleaseContentFactory compiler;
    @Qualifier("idempotencyRepository") private final IdempotencyRepository idempotency;
    @Qualifier("gatewayAuditLogRepository") private final GatewayAuditLogRepository audits;
    @Qualifier("gatewayExposureTransactionTemplate") private final TransactionTemplate transactions;
    @Qualifier("gatewayPersistenceContextComponent") private final GatewayPersistenceContextComponent context;
    @Qualifier("jacksonObjectMapper") private final ObjectMapper json;
    @Qualifier("gatewayProjectionClock") private final Clock clock;

    public McpMappingSeedArtifactVO freeze(@NotBlank String groupId,
        @NotNull @Valid McpMappingSeedFreezeCommand command, @NotBlank @Size(max = 128) String idempotencyKey,
        @NotNull AdminActor actor, @NotNull RequestAuditContext request) {
        authorize(actor);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CommonException(503, "YUHENG_MCP_MAPPING_SEED_INDEPENDENT_TRANSACTION_REQUIRED",
                "Mapping evidence must be committed by an independent operator command", true, null);
        }
        long owner = context.installationTenantId();
        if (owner <= 0 || !Objects.equals(EgonColaTenantIdProvider.currentTenantId(), owner))
            throw denied();
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException(McpMappingSeedFreezeCommand.INVALID);
        String fingerprint = McpMappingSeedArtifactVO.sha256(McpMappingSeedArtifactVO.bytes(json.valueToTree(command)));
        try {
            return Objects.requireNonNull(transactions.execute(
                status -> accept(owner, groupId, command, idempotencyKey, fingerprint, actor, request)));
        } catch (CommonException | IllegalArgumentException | GatewayAdminNotFoundException
            | GatewayAdminIdempotencyConflictException
            | top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            log.warn("MCP seed acceptance failed code=YUHENG_MCP_MAPPING_SEED_STORE_UNAVAILABLE");
            throw new CommonException(503, "YUHENG_MCP_MAPPING_SEED_STORE_UNAVAILABLE",
                "The mapping approval could not be confirmed", true, null);
        }
    }
    private McpMappingSeedArtifactVO accept(long owner, String groupId, McpMappingSeedFreezeCommand command, String key,
        String fingerprint, AdminActor actor, RequestAuditContext request) {
        var group = groups.findByIdAndDeletedFalse(groupId).orElseThrow(
            () -> new GatewayAdminNotFoundException("Gateway group is unavailable"));
        if (!group.isEnabled() || group.isDeleted())
            throw new GatewayAdminNotFoundException("Gateway group is unavailable");
        var head = drafts.lockForPublication(groupId).orElseThrow(
            () -> new GatewayAdminNotFoundException("Gateway draft is unavailable"));
        var identity = identities.require(owner, groupId, command.registrationId());
        if (!identity.namespaceCode().equals(group.getNamespace()) || !identity.envCode().equals(group.getEnv()))
            throw denied();
        // A seed cannot be used to bypass an existing version-bound policy or its independently disabled state.
        for (var policy : policies.policies(groupId)) {
            if (!McpApiMappingContentBO.POLICY_TYPE.equals(policy.getPolicyType()))
                continue;
            var bound = McpApiMappingContentBO.fromContent(policy.getContent());
            if (identity.resourceId().equals(bound.siServer().resourceId())
                || identity.resourceKey().equals(bound.siServer().resourceKey()))
                throw new CommonException(
                    409, "YUHENG_MCP_MAPPING_SEED_ALREADY_BOUND", "Use the existing fixed-version mapping publication");
        }
        var previous = idempotency.find(IDEMPOTENCY_SCOPE, groupId, key).orElse(null);
        Instant now = clock.instant();
        if (previous != null && !fingerprint.equals(previous.getPayloadSha256()))
            throw new GatewayAdminIdempotencyConflictException();
        if (previous != null && (previous.getExpiresAt() == null || !now.isBefore(previous.getExpiresAt())))
            throw new CommonException(409, "YUHENG_MCP_MAPPING_SEED_RECEIPT_EXPIRED", "The export receipt has expired");
        JsonNode compiled = compiler.compileRegisteredIdentityMappings(owner, groupId, identity, command.mappings());
        if (previous != null) {
            Object encoded = previous.getResponse().get("canonicalArtifact");
            if (!(encoded instanceof String bytes) || bytes.length() > McpMappingSeedArtifactVO.MAX_BYTES) {
                throw new IllegalStateException("YUHENG_MCP_MAPPING_SEED_RECEIPT_CORRUPT");
            }
            final McpMappingSeedArtifactVO artifact;
            try {
                artifact = new McpMappingSeedArtifactVO(json.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .readTree(bytes));
            } catch (java.io.IOException malformed) {
                throw new IllegalStateException("YUHENG_MCP_MAPPING_SEED_RECEIPT_CORRUPT");
            }
            if (!java.util.Arrays.equals(
                    bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8), artifact.canonicalBytes())) {
                throw new IllegalStateException("YUHENG_MCP_MAPPING_SEED_RECEIPT_CORRUPT");
            }
            JsonNode body = artifact.document();
            if (!Long.toString(owner).equals(body.path("ownerTenantId").asText())
                || !groupId.equals(body.path("gatewayGroupId").asText())
                || !identity.evidence().equals(body.path("registeredSi"))
                || !McpMappingSeedArtifactVO.canonical(compiled).equals(body.path("mappings")))
                throw denied();
            return artifact;
        }
        if (head.getRevision() != Long.parseLong(command.expectedDraftRevision()))
            throw new CommonException(409, "YUHENG_MCP_MAPPING_SEED_DRAFT_CONFLICT", "The draft revision has changed");
        var artifact = McpMappingSeedArtifactVO.create(
            SnowflakeIdGenerator.nextId(), owner, groupId, head.getRevision(), now, identity, compiled);
        Map<String, Object> receipt =
            Map.of("canonicalArtifact", new String(artifact.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8));
        idempotency.save(new IdempotencyBO(IDEMPOTENCY_SCOPE, groupId, key, fingerprint, artifact.publicationId(),
            receipt, now, now.plus(Duration.ofDays(7))));
        audits.save(new GatewayAuditLogBO(SnowflakeIdGenerator.nextId(), actor.actorId(), actor.actorType().name(),
            "MANAGEMENT_API", request.requestId(), request.traceId(), "MCP_MAPPING_SEED", artifact.publicationId(),
            "FREEZE_MAPPING_SEED", Map.of(),
            Map.of("contentSha256", artifact.contentSha256(), "artifactSha256", artifact.artifactSha256(),
                "registrationId", identity.registrationId(), "changeReason", command.changeReason()),
            head.getRevision(), null, true, null, now));
        return artifact;
    }
    private static void authorize(AdminActor actor) {
        if (actor == null || !actor.oauthScopes().contains(GatewayPermissionCodes.MCP_WRITE)
            || !(actor.scopes().contains(GatewayPermissionCodes.MCP_WRITE) || actor.scopes().contains("*")))
            throw denied();
    }
    private static CommonException denied() {
        return new CommonException(403, "YUHENG_MCP_MAPPING_SEED_DENIED", "The mapping approval is not authorized");
    }
}
