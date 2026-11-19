package top.egon.cola.component.yuheng.admin.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.*;
import top.egon.cola.component.yuheng.admin.group.domain.bo.GatewayGroupBO;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.enums.McpPersistentRiskLevelEnum;
import top.egon.cola.component.yuheng.admin.mcp.service.McpReleaseContentFactory;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayReleaseBO;
import top.egon.cola.component.yuheng.admin.release.domain.dto.GatewayReleaseCreateCommandDTO;
import top.egon.cola.component.yuheng.admin.release.domain.dto.GatewayReleaseRollbackCommandDTO;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftVO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.routing.service.GatewayDraftService;
import top.egon.cola.component.yuheng.admin.routing.service.GatewayRouteDraftMapper;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimePolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Accepts immutable native releases and current control CAS in one LOCAL transaction, with no network writes. */
@Validated
@Slf4j
@Service("gatewayReleaseService")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GatewayReleaseService {
    @Qualifier("mpGatewayGroupRepository")
    private final GatewayGroupRepository groups;
    @Qualifier("mpGatewayDraftMetadataRepository")
    private final GatewayDraftJpaRepository drafts;
    @Qualifier("gatewayDraftService")
    private final GatewayDraftService draftService;
    @Qualifier("mpGatewayCatalogRepository")
    private final GatewayCatalogRepository catalog;
    @Qualifier("mpGatewayReleaseRepository")
    private final GatewayReleaseRepository releases;
    @Qualifier("gatewayAuditLogRepository")
    private final GatewayAuditLogRepository audits;
    @Qualifier("gatewayExposureTransactionTemplate")
    private final TransactionTemplate transactions;
    @Qualifier("mpGatewayUrlExposureRepository")
    private final GatewayUrlExposureRepository exposures;
    @Qualifier("gatewayReleasePublicationCoordinator")
    private final GatewayReleasePublicationCoordinator publications;
    @Qualifier("gatewayHttpRouteCompiler")
    private final GatewayHttpRouteCompiler compiler;
    @Qualifier("gatewayHttpRouteBindingResolver")
    private final ObjectProvider<BindingResolver> bindings;
    @Qualifier("mcpReleaseContentFactory")
    private final ObjectProvider<McpReleaseContentFactory> mcp;
    @Qualifier("jacksonObjectMapper")
    private final ObjectMapper json;
    @Qualifier("gatewayProjectionClock")
    private final Clock clock;
    private final GatewayRouteDraftMapper routeMapper = new GatewayRouteDraftMapper();

    /** Historical source-compatible assembly: archive reads remain available; native writes cannot use old publishers. */
    public GatewayReleaseService(GatewayGroupRepository groups, GatewayDraftJpaRepository drafts,
            GatewayDraftService draftService, GatewayCatalogRepository catalog, GatewayReleaseRepository releases,
            GatewayAuditLogRepository audits, TransactionTemplate transactions,
            ObjectProvider<GatewayReleasePublicationCoordinator> publications, ObjectProvider<McpReleaseContentFactory> mcp) {
        this(groups, drafts, draftService, catalog, releases, audits, transactions, publications.getIfAvailable(), Clock.systemUTC());
    }

    GatewayReleaseService(GatewayGroupRepository groups, GatewayDraftJpaRepository drafts, GatewayDraftService draftService,
            GatewayCatalogRepository catalog, GatewayReleaseRepository releases, GatewayAuditLogRepository audits,
            TransactionTemplate transactions, GatewayReleasePublicationCoordinator publications, Clock clock) {
        this(groups, drafts, draftService, catalog, releases, audits, transactions, null, publications, null, null, null, null, clock);
    }

    GatewayReleaseService(GatewayGroupRepository groups, GatewayDraftJpaRepository drafts, GatewayDraftService draftService,
            GatewayCatalogRepository catalog, GatewayReleaseRepository releases, GatewayAuditLogRepository audits,
            TransactionTemplate transactions, GatewayReleasePublicationCoordinator publications, McpReleaseContentFactory mcp, Clock clock) {
        this(groups, drafts, draftService, catalog, releases, audits, transactions, publications, clock);
    }

    public GatewayReleaseVO create(String gatewayGroupId, @Valid GatewayReleaseCreateCommandDTO command,
                                   AdminActor actor, RequestAuditContext request) {
        authorize(actor);
        long owner = owner();
        String id = transactions.execute(status -> accept(owner, gatewayGroupId, command.expectedDraftRevision(),
                command.changeReason(), null, null, actor, request));
        return get(id);
    }

    public GatewayReleaseVO retry(String releaseId, AdminActor actor, RequestAuditContext request) {
        authorize(actor);
        long owner = owner();
        transactions.executeWithoutResult(status -> {
            GatewayReleaseBO initial = required(releaseId);
            GatewayCiliumReleaseArtifact artifact = nativeArtifact(releaseId);
            requireNativeAssembly();
            GatewayDraftBO locked = null;
            if (artifact.hasMcpPublication()) {
                requireEnabledGroup(initial.getGatewayGroupId());
                locked = lockPublicationDraft(initial.getGatewayGroupId());
                requireNoPublicationInFlight(initial.getGatewayGroupId());
            }
            // A concurrent attempt may have committed while we waited for the draft lock.
            GatewayReleaseBO release = locked == null ? initial : required(releaseId);
            if (!Set.of(GatewayReleaseStatus.FAILED, GatewayReleaseStatus.TIMEOUT, GatewayReleaseStatus.UNKNOWN)
                    .contains(release.getStatus())) throw new IllegalStateException("YUHENG_ADMIN_RELEASE_NOT_RETRYABLE");
            if (locked != null) {
                if (!Objects.equals(locked.getBasedOnReleaseId(), release.getBasedOnReleaseId())
                        && !Objects.equals(locked.getBasedOnReleaseId(), releaseId)) throw mcpReplayRejected();
                revalidateMcpReplay(owner, release, artifact, locked);
            }
            int attempt = releases.nextAttempt(releaseId, clock.instant());
            releases.beginAttempt(releaseId, attempt, clock.instant());
            publications.enqueueCurrent(owner, releaseId, attempt, artifact);
            if (completedWithEvidence(owner, releaseId, attempt)) {
                if (locked != null) advanceDraftBasis(locked, release, clock.instant());
                else drafts.findById(release.getGatewayGroupId()).ifPresent(draft -> advanceDraftBasis(draft, release, clock.instant()));
            }
            audit(actor, request, release.getGatewayGroupId(), releaseId, "RETRY", release.getDraftRevision());
        });
        return get(releaseId);
    }

    public GatewayReleaseVO rollback(String gatewayGroupId, @Valid GatewayReleaseRollbackCommandDTO command,
                                     AdminActor actor, RequestAuditContext request) {
        authorize(actor);
        long owner = owner();
        String id = transactions.execute(status -> {
            GatewayReleaseBO source = required(command.sourceReleaseId());
            if (!source.getGatewayGroupId().equals(gatewayGroupId) || source.getStatus() != GatewayReleaseStatus.SUCCESS) {
                throw new IllegalArgumentException("YUHENG_RELEASE_ROLLBACK_SOURCE_INVALID");
            }
            GatewayCiliumReleaseArtifact artifact = nativeArtifact(source.getId());
            GatewayDraftVO frozen = json.convertValue(artifact.canonicalSnapshot().path("sourceContent"), GatewayDraftVO.class);
            if (frozen == null || !gatewayGroupId.equals(frozen.gatewayGroupId())) throw invalid();
            return accept(owner, gatewayGroupId, command.expectedDraftRevision(), command.changeReason(),
                    source.getId(), new RollbackSource(frozen, artifact, source), actor, request);
        });
        return get(id);
    }

    /** Read-only operator proof. Selectors select frozen facts; they cannot supply tenant or SI authority. */
    public top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingPublicationEvidenceVO exportBoundMapping(
            @jakarta.validation.constraints.Positive long trustedOwner,
            @jakarta.validation.constraints.NotBlank String groupId,
            @jakarta.validation.constraints.NotBlank String releaseId,
            @jakarta.validation.constraints.NotBlank String resourceId,
            @jakarta.validation.constraints.NotBlank String versionId,
            @jakarta.validation.constraints.NotBlank String versionNo,
            @jakarta.validation.constraints.NotNull AdminActor actor) {
        if (actor == null || !actor.oauthScopes().contains(GatewayPermissionCodes.MCP_WRITE)
                || !(actor.scopes().contains(GatewayPermissionCodes.MCP_WRITE) || actor.scopes().contains("*"))) {
            throw new top.egon.cola.component.common.core.exception.CommonException(403,
                    "YUHENG_MCP_MAPPING_PUBLICATION_DENIED", "The mapping export is not authorized");
        }
        if (trustedOwner <= 0 || owner() != trustedOwner) throw new top.egon.cola.component.common.core.exception.CommonException(403,
                "YUHENG_MCP_MAPPING_PUBLICATION_DENIED", "The mapping export is not authorized");
        try { positive(groupId); positive(releaseId); positive(resourceId); positive(versionId); positive(versionNo); }
        catch (RuntimeException badSelector) { throw new IllegalArgumentException("YUHENG_MCP_MAPPING_PUBLICATION_SELECTOR_INVALID"); }
        requireEnabledGroup(groupId);
        GatewayReleaseBO source = required(releaseId);
        if (!groupId.equals(source.getGatewayGroupId()) || source.getStatus() != GatewayReleaseStatus.SUCCESS) {
            throw new IllegalStateException("YUHENG_MCP_MAPPING_PUBLICATION_SUCCESS_REQUIRED");
        }
        GatewayCiliumReleaseArtifact artifact = nativeArtifact(releaseId);
        requireMcpArtifactIdentity(artifact, trustedOwner, groupId, releaseId);
        if (!artifact.hasMcpPublication()) throw mcpReplayRejected();
        ArrayNode selected = json.createArrayNode();
        for (JsonNode entry : artifact.mcpPublication().path("mappings")) {
            JsonNode server = entry.path("siServer");
            if (resourceId.equals(server.path("resourceId").asText())
                    && versionId.equals(server.path("versionId").asText())
                    && versionNo.equals(server.path("versionNo").asText())) selected.add(entry);
        }
        if (selected.isEmpty()) throw new GatewayAdminNotFoundException("The fixed SI mapping is unavailable");
        JsonNode section = GatewayCiliumReleaseArtifact.mcpPublication(selected);
        var evidence = top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingPublicationEvidenceVO.create(artifact, selected);
        GatewayDraftBO current = drafts.findById(groupId)
                .orElseThrow(() -> new GatewayAdminNotFoundException("Gateway draft is unavailable"));
        String basis = current.getBasedOnReleaseId();
        long revision = current.getRevision();
        if (basis == null) throw mcpReplayRejected();
        GatewayReleaseBO effectiveHead = required(basis);
        if (!groupId.equals(effectiveHead.getGatewayGroupId()) || effectiveHead.getStatus() != GatewayReleaseStatus.SUCCESS) throw mcpReplayRejected();
        GatewayCiliumReleaseArtifact effective = releases.loadNative(basis)
                .orElseThrow(GatewayReleaseService::mcpReplayRejected);
        requireMcpArtifactIdentity(effective, trustedOwner, groupId, basis);
        verifyCurrentMcpRestrictions(section, effective.mcpPublication());
        try {
            mappingFactory().revalidateMappingSection(trustedOwner, groupId, section);
        } catch (top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException unavailable) {
            if ("YUHENG_MCP_API_MAPPING_SI_UNAVAILABLE".equals(unavailable.code())) {
                throw new top.egon.cola.component.common.core.exception.CommonException(503,
                        "YUHENG_MCP_MAPPING_PUBLICATION_AUTHORITY_UNAVAILABLE", "The current SI authority is unavailable", true, null);
            }
            throw unavailable;
        }
        // Re-read the effective pointer after current SI/source authorization, refusing a moving publication basis.
        GatewayDraftBO confirmed = drafts.findById(groupId).orElseThrow(GatewayReleaseService::mcpReplayRejected);
        if (!Objects.equals(basis, confirmed.getBasedOnReleaseId())
                || revision != confirmed.getRevision()) throw mcpReplayRejected();
        return evidence;
    }

    public Optional<String> artifactSha256(String releaseId) { return releases.findArtifactSha256(releaseId); }

    public GatewayReleaseVO get(String releaseId) {
        return transactions.execute(status -> view(required(releaseId)));
    }

    public List<GatewayReleaseVO> history(String gatewayGroupId) {
        return transactions.execute(status -> releases.history(gatewayGroupId).stream().map(this::view).toList());
    }

    public Map<String, Object> diff(String releaseId) { return required(releaseId).getStructuredDiff(); }

    private String accept(long owner, String groupId, long expectedRevision, String reason, String rollbackOf,
                          RollbackSource source, AdminActor actor, RequestAuditContext request) {
        requireNativeAssembly();
        GatewayGroupBO group = requireEnabledGroup(groupId);
        GatewayDraftBO currentDraft = lockPublicationDraft(groupId);
        requireNoPublicationInFlight(groupId);
        currentDraft.assertEditable(expectedRevision);
        GatewayDraftVO sourceDraft = source == null ? draftService.get(groupId) : source.draft();
        if (!sourceDraft.gatewayGroupId().equals(groupId) || (source == null && sourceDraft.revision() != expectedRevision)) throw invalid();
        String normalizedReason = requiredReason(reason);
        String releaseId = SnowflakeIdGenerator.nextId();
        List<PreparedGatewayRelease.ExposurePlan> plans = compile(owner, group, releaseId, sourceDraft, source);
        JsonNode mcpPublication = compileMcpPublication(owner, currentDraft, sourceDraft, source);
        if (plans.isEmpty() && mcpPublication == null) {
            throw new IllegalArgumentException("YUHENG_RELEASE_NO_PUBLIC_EXPOSURES");
        }
        GatewayCiliumReleaseArtifact artifact = artifact(owner, groupId, releaseId, sourceDraft, plans, mcpPublication);
        Instant now = clock.instant();
        var validation = new LinkedHashMap<String, Object>();
        validation.put("valid", true);
        validation.put("currentStateOverrides", overrides(plans));
        validation.put("warnings", deprecatedWarnings(sourceDraft));
        if (!plans.isEmpty()) validation.put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID);
        if (mcpPublication != null) {
            validation.put("mcpPublication", Map.of("contentSha256", mcpPublication.path("contentSha256").textValue(),
                    "mappingCount", mcpPublication.path("mappings").size()));
        }
        GatewayReleaseBO release = new GatewayReleaseBO(releaseId, groupId, currentDraft.getRevision(),
                currentDraft.getBasedOnReleaseId(), rollbackOf, GatewayReleaseStatus.READY, false, null,
                Map.copyOf(validation),
                Map.of("basedOnReleaseId", currentDraft.getBasedOnReleaseId() == null ? "" : currentDraft.getBasedOnReleaseId(),
                        "rollbackOfReleaseId", rollbackOf == null ? "" : rollbackOf, "exposureCount", plans.size(),
                        "currentStateOverrides", overrides(plans)),
                normalizedReason, now, actor.actorId(), now);
        releases.insertNative(release, artifact, 1); // Parent content exists before the exposure FK is inserted.
        apply(owner, releaseId, normalizedReason, plans, now);
        releases.beginAttempt(releaseId, 1, now);
        audit(actor, request, groupId, releaseId, rollbackOf == null ? "CREATE_RELEASE" : "ROLLBACK", currentDraft.getRevision());
        publications.enqueueCurrent(owner, releaseId, 1, artifact);
        if (completedWithEvidence(owner, releaseId, 1)) advanceDraftBasis(currentDraft, release, now);
        return releaseId;
    }

    private GatewayGroupBO requireEnabledGroup(String groupId) {
        GatewayGroupBO group = groups.findByIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GatewayAdminNotFoundException("Gateway group is unavailable"));
        if (!group.isEnabled()) throw new IllegalStateException("YUHENG_ADMIN_YUHENG_GROUP_DISABLED");
        return group;
    }

    private GatewayDraftBO lockPublicationDraft(String groupId) {
        return drafts.lockForPublication(groupId)
                .orElseThrow(() -> new GatewayAdminNotFoundException("Gateway draft is unavailable"));
    }

    private void requireNoPublicationInFlight(String groupId) {
        if (releases.hasReleaseInProgress(groupId)) throw new IllegalStateException("YUHENG_ADMIN_RELEASE_IN_PROGRESS");
    }

    private JsonNode compileMcpPublication(long owner, GatewayDraftBO current, GatewayDraftVO draft, RollbackSource source) {
        boolean hasMappingPolicy = draft.policies().stream()
                .anyMatch(policy -> McpApiMappingContentBO.POLICY_TYPE.equals(policy.getPolicyType()));
        if (source != null) {
            if (hasMappingPolicy && !source.artifact().hasMcpPublication()) throw mcpReplayRejected();
            return revalidateMcpReplay(owner, source.release(), source.artifact(), current);
        }
        if (!hasMappingPolicy) return null;
        JsonNode section = mappingFactory().compileMappingSection(owner, draft);
        if (section == null) throw invalid();
        return section;
    }

    private JsonNode revalidateMcpReplay(long owner, GatewayReleaseBO release, GatewayCiliumReleaseArtifact artifact,
                                         GatewayDraftBO current) {
        JsonNode section = artifact.mcpPublication();
        if (section == null) return null;
        String group = release.getGatewayGroupId();
        requireMcpArtifactIdentity(artifact, owner, group, release.getId());
        String basis = current.getBasedOnReleaseId();
        if (basis != null && !basis.equals(release.getId())) {
            GatewayCiliumReleaseArtifact effective = releases.loadNative(basis)
                    .orElseThrow(GatewayReleaseService::mcpReplayRejected);
            requireMcpArtifactIdentity(effective, owner, group, basis);
            verifyCurrentMcpRestrictions(section, effective.mcpPublication());
        }
        mappingFactory().revalidateMappingSection(owner, group, section);
        return section;
    }

    private static void requireMcpArtifactIdentity(
            GatewayCiliumReleaseArtifact artifact, long owner, String group, String releaseId) {
        if (!Long.toString(owner).equals(artifact.ownerTenantId())
                || !group.equals(artifact.gatewayGroupId())
                || !releaseId.equals(artifact.releaseId())) {
            log.debug("MCP release replay rejected because the frozen artifact identity does not match");
            throw mcpReplayRejected();
        }
    }

    private static void verifyCurrentMcpRestrictions(JsonNode frozen, JsonNode current) {
        var currentById = new HashMap<String, JsonNode>();
        if (current != null) {
            current.path("mappings").forEach(entry -> currentById.put(entry.path("toolId").textValue(), entry));
        }
        for (JsonNode entry : frozen.path("mappings")) {
            JsonNode tool = entry.path("tool");
            if (!tool.path("enabled").booleanValue()) continue;
            JsonNode latest = currentById.get(entry.path("toolId").textValue());
            if (latest == null || !sameMcpBinding(entry, latest) || !preservesMcpRestrictions(tool, latest.path("tool"))) {
                throw mcpReplayRejected();
            }
        }
    }

    private static boolean sameMcpBinding(JsonNode frozen, JsonNode current) {
        return sameFields(frozen.path("sourceOperation"), current.path("sourceOperation"), "operationId", "operationKey")
                && sameFields(frozen.path("siServer"), current.path("siServer"),
                "resourceId", "resourceKey", "bizCode", "namespaceCode", "envCode", "ownerAppCode");
    }

    private static boolean sameFields(JsonNode first, JsonNode second, String... fields) {
        for (String field : fields) {
            if (!first.path(field).equals(second.path(field))) return false;
        }
        return true;
    }

    private static boolean preservesMcpRestrictions(JsonNode frozen, JsonNode current) {
        if (!current.path("enabled").booleanValue()
                || (frozen.path("idempotent").booleanValue() && !current.path("idempotent").booleanValue())
                || McpPersistentRiskLevelEnum.fromWire(frozen.path("riskLevel").textValue()).getCode()
                    < McpPersistentRiskLevelEnum.fromWire(current.path("riskLevel").textValue()).getCode()) return false;
        var permissions = new HashSet<JsonNode>();
        frozen.path("requiredPermissions").forEach(permissions::add);
        for (JsonNode permission : current.path("requiredPermissions")) {
            if (!permissions.contains(permission)) return false;
        }
        return true;
    }

    private McpReleaseContentFactory mappingFactory() {
        McpReleaseContentFactory factory = mcp == null ? null : mcp.getIfAvailable();
        if (factory == null) throw invalid();
        return factory;
    }

    private static IllegalStateException mcpReplayRejected() {
        return new IllegalStateException("YUHENG_RELEASE_MCP_REPLAY_NO_LONGER_AUTHORIZED");
    }

    private boolean completedWithEvidence(long owner, String releaseId, int attemptNo) {
        return publications.recoverNative(owner, releaseId, attemptNo)
                .filter(outcome -> outcome.status() == GatewayReleaseStatus.SUCCESS).isPresent();
    }

    private void advanceDraftBasis(GatewayDraftBO draft, GatewayReleaseBO release, Instant at) {
        if (Objects.equals(draft.getBasedOnReleaseId(), release.getId())
                || !Objects.equals(draft.getBasedOnReleaseId(), release.getBasedOnReleaseId())) return;
        draft.baseOn(release.getId(), "gateway_release_reconciler", at);
        drafts.save(draft);
    }

    private List<PreparedGatewayRelease.ExposurePlan> compile(long owner, GatewayGroupBO group, String releaseId,
                                                             GatewayDraftVO source, RollbackSource rollback) {
        long groupId = positive(group.getId());
        List<GatewayRuntimePolicy> policies = source.policies().stream().filter(policy -> policy.isEnabled())
                .filter(policy -> !McpApiMappingContentBO.POLICY_TYPE.equals(policy.getPolicyType()))
                .map(policy -> new GatewayRuntimePolicy(policy.getPolicyId(), policy.getPolicyType(), policy.getPolicyScope(), policy.getContent())).toList();
        List<PreparedGatewayRelease.ExposurePlan> plans = new ArrayList<>();
        for (GatewayRouteDraftBO route : source.routes().stream().filter(GatewayRouteDraftBO::isEnabled)
                .sorted(Comparator.comparing(GatewayRouteDraftBO::getRouteId)).toList()) {
            var canonical = routeMapper.canonicalize(route.getContent());
            var runtime = routeMapper.toRuntimeRoute(route, canonical);
            if (!runtime.accessZones().contains(AccessZone.PUBLIC)) continue;
            BindingResolver resolver = bindings.getIfAvailable();
            if (resolver == null) throw invalid();
            GatewayOperationBO operation = catalog.findOperation(route.getOperationId())
                    .orElseThrow(() -> new GatewayAdminNotFoundException("Gateway operation is unavailable"));
            if (!operation.isExternalAccessible() || !Set.of("ACTIVE", "DEPRECATED").contains(operation.getLifecycleStatus())) throw invalid();
            GatewayOperationDefinitionBO definition = definition(operation, route.getRouteId(), rollback);
            PublicationBinding binding = resolver.resolve(owner, groupId, route.getRouteId(), operation.getId()).orElseThrow(GatewayReleaseService::invalid);
            if (!Objects.equals(binding.environment(), group.getEnv()) || !Objects.equals(binding.logicalNamespace(), group.getNamespace())) throw invalid();
            GatewayUrlExposureBO current = exposures.findOwnedRoute(owner, groupId, route.getRouteId()).orElse(null);
            long id = current == null ? SnowflakeIdGenerator.nextLongId() : current.getId();
            long generation = current == null ? 1L : current.getDesiredGeneration();
            List<ResourceReference> refs = List.of(new ResourceReference("BUSINESS_API", operation.getId(),
                    Long.toString(definition.getDefinitionVersion()), definition.getDefinitionSha256()));
            var input = new ExposureInput(owner, groupId, releaseId, id, generation, runtime,
                    GatewayProtocol.valueOf(operation.getProtocol()), definition.isExternalAccessible(), canonical, policies, refs);
            CompiledExposure compiled = compiler.compile(input, binding);
            boolean changed = current != null && (!Objects.equals(current.getContentSha256(), compiled.contentSha256())
                    || !GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID.equals(current.getCompilerProfileId()));
            if (current != null) {
                assertFixedBinding(current, compiled);
                if (changed && !current.getDesiredState().isTerminal()) compiled = compiler.compile(new ExposureInput(owner, groupId,
                        releaseId, id, Math.addExact(generation, 1L), runtime, input.operationProtocol(), input.externalAccessible(),
                        canonical, policies, refs), binding);
            }
            plans.add(new PreparedGatewayRelease.ExposurePlan(id, route.getRouteId(), current == null,
                    current == null ? 0L : current.getVersion(), current == null ? 0L : current.getDesiredGeneration(),
                    current == null ? GatewayUrlExposureStateEnum.OPEN : current.getDesiredState(), changed, compiled));
        }
        return List.copyOf(plans);
    }

    private static List<Map<String, String>> overrides(List<PreparedGatewayRelease.ExposurePlan> plans) {
        return plans.stream().filter(plan -> plan.effectiveState() != GatewayUrlExposureStateEnum.OPEN)
                .map(plan -> Map.of("exposureId", Long.toString(plan.exposureId()), "state", plan.effectiveState().wireValue())).toList();
    }

    private List<Map<String, String>> deprecatedWarnings(GatewayDraftVO draft) {
        return draft.routes().stream().filter(GatewayRouteDraftBO::isEnabled)
                .filter(route -> catalog.findOperation(route.getOperationId())
                        .map(operation -> "DEPRECATED".equals(operation.getLifecycleStatus())).orElse(false))
                .map(route -> Map.of("path", "routes." + route.getRouteId() + ".operationId", "code", "OPERATION_DEPRECATED",
                        "message", "Deprecated externally approved operation remains publishable")).toList();
    }

    private GatewayOperationDefinitionBO definition(GatewayOperationBO operation, String routeId, RollbackSource source) {
        List<GatewayOperationDefinitionBO> definitions = catalog.loadDefinitions(operation.getId());
        if (source == null) return definitions.stream().filter(definition -> Objects.equals(definition.getId(), operation.getCurrentDefinitionId()))
                .findFirst().filter(GatewayOperationDefinitionBO::isExternalAccessible).orElseThrow(GatewayReleaseService::invalid);
        JsonNode reference = null;
        for (JsonNode entry : source.artifact().canonicalSnapshot().path("exposurePublication").path("exposures")) {
            if (routeId.equals(entry.path("routeId").asText())) for (JsonNode candidate : entry.path("resourceRefs")) {
                if ("BUSINESS_API".equals(candidate.path("kind").asText()) && operation.getId().equals(candidate.path("resourceId").asText())) reference = candidate;
            }
        }
        if (reference == null) throw invalid();
        String version = reference.path("version").asText();
        String digest = reference.path("contentSha256").asText();
        return definitions.stream().filter(definition -> Long.toString(definition.getDefinitionVersion()).equals(version)
                && Objects.equals(definition.getDefinitionSha256(), digest)).findFirst()
                .filter(GatewayOperationDefinitionBO::isExternalAccessible).orElseThrow(GatewayReleaseService::invalid);
    }

    private GatewayCiliumReleaseArtifact artifact(long owner, String group, String releaseId, GatewayDraftVO source,
                                                 List<PreparedGatewayRelease.ExposurePlan> plans, JsonNode mcpPublication) {
        ObjectNode canonical = json.createObjectNode().put("releaseId", releaseId).put("ownerTenantId", Long.toString(owner))
                .put("gatewayGroupId", group).put("generatedAt", clock.instant().toString());
        canonical.set("sourceContent", json.valueToTree(source));
        if (mcpPublication != null) canonical.set("mcpPublication", mcpPublication);
        ObjectNode activation = json.createObjectNode().put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION).put("releaseId", releaseId);
        ObjectNode manifest = json.createObjectNode().put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION)
                .put("compilerVersion", GatewayHttpRouteCompiler.COMPILER_VERSION).put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID);
        ArrayNode targets = activation.putArray("exposures");
        ArrayNode resources = manifest.putArray("resources");
        ArrayNode dependencies = manifest.putArray("dependencies");
        if (plans.isEmpty()) return new GatewayCiliumReleaseArtifact(canonical, activation, manifest);
        ObjectNode publication = canonical.putObject("exposurePublication").put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION)
                .put("target", "CILIUM_GATEWAY_API").put("gatewayClassName", "cilium")
                .put("routeControllerName", GatewayHttpRouteCompiler.CONTROLLER_NAME).put("compilerVersion", GatewayHttpRouteCompiler.COMPILER_VERSION)
                .put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID)
                .put("publicationDomainId", plans.getFirst().compiled().binding().publicationDomain().publicationDomainId())
                .put("backendAuthorizationProfile", GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE);
        ArrayNode entries = publication.putArray("exposures");
        for (var plan : plans.stream().sorted(Comparator.comparingLong(PreparedGatewayRelease.ExposurePlan::exposureId)).toList()) {
            if (!publication.path("publicationDomainId").asText().equals(plan.compiled().binding().publicationDomain().publicationDomainId())) throw invalid();
            entries.add(plan.compiled().snapshotEntry());
            ObjectNode target = targets.addObject().put("exposureId", Long.toString(plan.exposureId()))
                    .put("desiredGeneration", plan.compiled().snapshotEntry().path("expectedDesiredGeneration").asText())
                    .put("effectiveState", plan.effectiveState().wireValue()).put("contentSha256", plan.compiled().contentSha256());
            if (plan.newRegistration()) target.putNull("precondition");
            else target.putObject("precondition").put("version", Long.toString(plan.expectedVersion()))
                    .put("desiredGeneration", Long.toString(plan.expectedGeneration())).put("desiredState", plan.effectiveState().wireValue());
            ObjectNode resource = resources.addObject().put("exposureId", Long.toString(plan.exposureId())).put("contentSha256", plan.compiled().contentSha256());
            resource.set("object", plan.compiled().routeObject());
            resource.set("approvedParent", plan.compiled().approvedParent());
            plan.compiled().dependencies().forEach(dependencies::add);
        }
        return new GatewayCiliumReleaseArtifact(canonical, activation, manifest);
    }

    private void apply(long owner, String releaseId, String reason, List<PreparedGatewayRelease.ExposurePlan> plans, Instant now) {
        List<GatewayUrlExposureBO> registrations = plans.stream().filter(PreparedGatewayRelease.ExposurePlan::newRegistration)
                .map(plan -> registration(owner, releaseId, reason, plan, now)).toList();
        if (!registrations.isEmpty()) exposures.reserveAll(owner, registrations);
        for (var plan : plans) {
            if (plan.newRegistration()) continue;
            GatewayUrlExposureBO row = exposures.findOwned(owner, plan.exposureId()).orElseThrow(GatewayReleaseService::invalid);
            if (row.getVersion() != plan.expectedVersion() || row.getDesiredGeneration() != plan.expectedGeneration()
                    || row.getDesiredState() != plan.effectiveState()) throw GatewayUrlExposureException.preconditionFailed(row.getVersion());
            if (plan.contentChanged() && !plan.effectiveState().isTerminal()
                    && !exposures.compareAndSetContent(owner, plan.exposureId(), plan.expectedVersion(), plan.expectedGeneration(),
                    plan.effectiveState(), positive(releaseId), plan.compiled().contentSha256(), GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID, reason, now)) {
                throw GatewayUrlExposureException.preconditionFailed(exposures.findOwned(owner, plan.exposureId()).orElseThrow(GatewayReleaseService::invalid).getVersion());
            }
        }
    }

    private GatewayUrlExposureBO registration(long owner, String releaseId, String reason, PreparedGatewayRelease.ExposurePlan plan, Instant now) {
        var compiled = plan.compiled(); var binding = compiled.binding(); var gateway = binding.gateway();
        return GatewayUrlExposureBO.builder().id(plan.exposureId()).ownerTenantId(owner).gatewayGroupId(binding.gatewayGroupId())
                .routeId(plan.routeId()).clusterId(binding.publicationDomain().clusterId()).gatewayNamespace(gateway.namespace())
                .gatewayName(gateway.name()).gatewayUid(gateway.uid()).listenerName(gateway.listenerName()).hostname(compiled.hostname())
                .httpMethod(compiled.httpMethod()).pathMatchType(compiled.pathMatchType().name()).pathValue(compiled.pathValue())
                .routeKind("HTTPRoute").routeNamespace(binding.routeNamespace()).routeName("yuheng-exposure-" + plan.exposureId())
                .desiredState(GatewayUrlExposureStateEnum.OPEN).desiredGeneration(1L).observedState("UNKNOWN").observedGeneration(0L)
                .reconcileStatus("PENDING").releaseId(positive(releaseId)).contentSha256(compiled.contentSha256())
                .compilerProfileId(GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID).changeReason(reason).updateTime(now).build();
    }

    private static void assertFixedBinding(GatewayUrlExposureBO row, CompiledExposure compiled) {
        var binding = compiled.binding(); var gateway = binding.gateway();
        if (!Objects.equals(row.getClusterId(), binding.publicationDomain().clusterId())
                || !Objects.equals(row.getGatewayNamespace(), gateway.namespace()) || !Objects.equals(row.getGatewayName(), gateway.name())
                || !Objects.equals(row.getGatewayUid(), gateway.uid()) || !Objects.equals(row.getListenerName(), gateway.listenerName())
                || !Objects.equals(row.getHostname(), compiled.hostname()) || !Objects.equals(row.getHttpMethod(), compiled.httpMethod())
                || !Objects.equals(row.getPathMatchType(), compiled.pathMatchType().name()) || !Objects.equals(row.getPathValue(), compiled.pathValue())
                || !"HTTPRoute".equals(row.getRouteKind()) || !Objects.equals(row.getRouteNamespace(), binding.routeNamespace())
                || !Objects.equals(row.getRouteName(), "yuheng-exposure-" + row.getId())) throw GatewayUrlExposureException.bindingConflict();
    }

    private GatewayReleaseVO view(GatewayReleaseBO release) {
        var nativeArtifact = releases.loadNative(release.getId());
        if (nativeArtifact.isEmpty()) return new GatewayReleaseVO(release.getId(), release.getGatewayGroupId(), release.getDraftRevision(),
                release.getBasedOnReleaseId(), release.getRollbackOfReleaseId(), release.getStatus(), release.isPartialApplied(), release.getChangeId(),
                release.getValidationReport(), release.getStructuredDiff(), release.getChangeReason(), release.getCreatedAt(), release.getUpdatedAt(), releases.attempts(release.getId()));
        requireNativeAssembly();
        var outcome = publications.observeNative(owner(), nativeArtifact.orElseThrow());
        return new GatewayReleaseVO(release.getId(), release.getGatewayGroupId(), release.getDraftRevision(), release.getBasedOnReleaseId(),
                release.getRollbackOfReleaseId(), outcome.status(), outcome.partialApplied(), null, release.getValidationReport(), release.getStructuredDiff(),
                release.getChangeReason(), release.getCreatedAt(), release.getUpdatedAt(), releases.attempts(release.getId()), outcome.exposureOutcomes(), outcome.clusterTargets());
    }

    private GatewayReleaseBO required(String id) { return releases.find(id).orElseThrow(() -> new GatewayAdminNotFoundException("Gateway release is unavailable")); }
    private GatewayCiliumReleaseArtifact nativeArtifact(String id) { return releases.loadNative(id).orElseThrow(() -> new IllegalStateException(GatewayReleasePublicationCoordinator.LEGACY_READ_ONLY)); }
    private void requireNativeAssembly() { if (exposures == null || compiler == null || bindings == null || publications == null || json == null) throw invalid(); }
    private static long owner() { Long owner = EgonColaTenantIdProvider.currentTenantId(); if (owner == null || owner <= 0L) throw invalid(); return owner; }
    private static long positive(String value) { try { long result = Long.parseLong(value); if (result > 0L && value.equals(Long.toString(result))) return result; } catch (RuntimeException ignored) { } throw invalid(); }
    private static void authorize(AdminActor actor) { if (actor == null || !actor.oauthScopes().contains(GatewayPermissionCodes.RELEASES_WRITE)
            || (!actor.scopes().contains(GatewayPermissionCodes.RELEASES_WRITE) && !actor.scopes().contains("*"))) throw GatewayUrlExposureException.accessDenied(); }
    private static String requiredReason(String reason) { if (reason == null || reason.trim().isEmpty() || reason.trim().length() > 1024) throw new IllegalArgumentException("YUHENG_RELEASE_CHANGE_REASON_INVALID"); return reason.trim(); }
    private static IllegalStateException invalid() { return new IllegalStateException("YUHENG_RELEASE_NATIVE_BINDING_INVALID"); }

    private void audit(AdminActor actor, RequestAuditContext request, String groupId, String releaseId, String action, long revision) {
        audits.save(GatewayAuditLogBO.builder().actorId(actor.actorId()).actorType(actor.actorType().wireValue()).source("MANAGEMENT_API")
                .requestId(request.requestId()).traceId(request.traceId()).resourceType("YUHENG_RELEASE").resourceId(releaseId).action(action)
                .beforeSummary(Map.of()).afterSummary(Map.of("gatewayGroupId", groupId)).draftRevision(revision).releaseId(releaseId)
                .successful(true).occurredAt(clock.instant()).build());
    }

    private record RollbackSource(GatewayDraftVO draft, GatewayCiliumReleaseArtifact artifact, GatewayReleaseBO release) { }
}
