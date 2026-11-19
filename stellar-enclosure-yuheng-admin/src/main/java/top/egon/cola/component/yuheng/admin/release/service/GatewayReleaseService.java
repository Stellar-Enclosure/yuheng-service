package top.egon.cola.component.yuheng.admin.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Accepts immutable native releases and current control CAS in one LOCAL transaction, with no network writes. */
@Validated
@Service("gatewayReleaseService")
public class GatewayReleaseService {
    private final GatewayGroupRepository groups;
    private final GatewayDraftJpaRepository drafts;
    private final GatewayDraftService draftService;
    private final GatewayCatalogRepository catalog;
    private final GatewayReleaseRepository releases;
    private final GatewayAuditLogRepository audits;
    private final TransactionTemplate transactions;
    private final GatewayUrlExposureRepository exposures;
    private final GatewayReleasePublicationCoordinator publications;
    private final GatewayHttpRouteCompiler compiler;
    private final ObjectProvider<BindingResolver> bindings;
    private final ObjectMapper json;
    private final Clock clock;
    private final GatewayRouteDraftMapper routeMapper = new GatewayRouteDraftMapper();

    @Autowired
    public GatewayReleaseService(GatewayGroupRepository groups, GatewayDraftJpaRepository drafts,
            GatewayDraftService draftService, GatewayCatalogRepository catalog,
            @Qualifier("mpGatewayReleaseRepository") GatewayReleaseRepository releases,
            GatewayAuditLogRepository audits, @Qualifier("gatewayExposureTransactionTemplate") TransactionTemplate transactions,
            @Qualifier("mpGatewayUrlExposureRepository") GatewayUrlExposureRepository exposures,
            GatewayReleasePublicationCoordinator publications,
            @Qualifier("gatewayHttpRouteCompiler") GatewayHttpRouteCompiler compiler,
            @Qualifier("gatewayHttpRouteBindingResolver") ObjectProvider<BindingResolver> bindings,
            @Qualifier("jacksonObjectMapper") ObjectMapper json, @Qualifier("gatewayProjectionClock") Clock clock) {
        this.groups = groups; this.drafts = drafts; this.draftService = draftService; this.catalog = catalog;
        this.releases = releases; this.audits = audits; this.transactions = transactions; this.exposures = exposures;
        this.publications = publications; this.compiler = compiler; this.bindings = bindings; this.json = json; this.clock = clock;
    }

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
        this(groups, drafts, draftService, catalog, releases, audits, transactions, null, publications, null, null, null, clock);
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
            GatewayReleaseBO release = required(releaseId);
            GatewayCiliumReleaseArtifact artifact = nativeArtifact(releaseId);
            requireNativeAssembly();
            if (!Set.of(GatewayReleaseStatus.FAILED, GatewayReleaseStatus.TIMEOUT, GatewayReleaseStatus.UNKNOWN)
                    .contains(release.getStatus())) throw new IllegalStateException("YUHENG_ADMIN_RELEASE_NOT_RETRYABLE");
            int attempt = releases.nextAttempt(releaseId, clock.instant());
            releases.beginAttempt(releaseId, attempt, clock.instant());
            publications.enqueueCurrent(owner, releaseId, attempt, artifact);
            if (completedWithEvidence(owner, releaseId, attempt)) {
                drafts.findById(release.getGatewayGroupId()).ifPresent(draft -> advanceDraftBasis(draft, release, clock.instant()));
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
                    source.getId(), new RollbackSource(frozen, artifact), actor, request);
        });
        return get(id);
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
        GatewayGroupBO group = groups.findByIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GatewayAdminNotFoundException("Gateway group is unavailable"));
        if (!group.isEnabled()) throw new IllegalStateException("YUHENG_ADMIN_YUHENG_GROUP_DISABLED");
        if (releases.hasReleaseInProgress(groupId)) throw new IllegalStateException("YUHENG_ADMIN_RELEASE_IN_PROGRESS");
        GatewayDraftBO currentDraft = drafts.findById(groupId)
                .orElseThrow(() -> new GatewayAdminNotFoundException("Gateway draft is unavailable"));
        currentDraft.assertEditable(expectedRevision);
        GatewayDraftVO sourceDraft = source == null ? draftService.get(groupId) : source.draft();
        if (!sourceDraft.gatewayGroupId().equals(groupId) || (source == null && sourceDraft.revision() != expectedRevision)) throw invalid();
        String normalizedReason = requiredReason(reason);
        String releaseId = SnowflakeIdGenerator.nextId();
        List<PreparedGatewayRelease.ExposurePlan> plans = compile(owner, group, releaseId, sourceDraft, source);
        GatewayCiliumReleaseArtifact artifact = artifact(owner, groupId, releaseId, sourceDraft, plans);
        Instant now = clock.instant();
        GatewayReleaseBO release = new GatewayReleaseBO(releaseId, groupId, currentDraft.getRevision(),
                currentDraft.getBasedOnReleaseId(), rollbackOf, GatewayReleaseStatus.READY, false, null,
                Map.of("valid", true, "capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID,
                        "currentStateOverrides", overrides(plans), "warnings", deprecatedWarnings(sourceDraft)),
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
        BindingResolver resolver = bindings.getIfAvailable();
        if (resolver == null) throw invalid();
        long groupId = positive(group.getId());
        List<GatewayRuntimePolicy> policies = source.policies().stream().filter(policy -> policy.isEnabled())
                .map(policy -> new GatewayRuntimePolicy(policy.getPolicyId(), policy.getPolicyType(), policy.getPolicyScope(), policy.getContent())).toList();
        List<PreparedGatewayRelease.ExposurePlan> plans = new ArrayList<>();
        for (GatewayRouteDraftBO route : source.routes().stream().filter(GatewayRouteDraftBO::isEnabled)
                .sorted(Comparator.comparing(GatewayRouteDraftBO::getRouteId)).toList()) {
            var canonical = routeMapper.canonicalize(route.getContent());
            var runtime = routeMapper.toRuntimeRoute(route, canonical);
            if (!runtime.accessZones().contains(AccessZone.PUBLIC)) continue;
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
        if (plans.isEmpty()) throw new IllegalArgumentException("YUHENG_RELEASE_NO_PUBLIC_EXPOSURES");
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
                                                 List<PreparedGatewayRelease.ExposurePlan> plans) {
        ObjectNode canonical = json.createObjectNode().put("releaseId", releaseId).put("ownerTenantId", Long.toString(owner))
                .put("gatewayGroupId", group).put("generatedAt", clock.instant().toString());
        canonical.set("sourceContent", json.valueToTree(source));
        ObjectNode publication = canonical.putObject("exposurePublication").put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION)
                .put("target", "CILIUM_GATEWAY_API").put("gatewayClassName", "cilium")
                .put("routeControllerName", GatewayHttpRouteCompiler.CONTROLLER_NAME).put("compilerVersion", GatewayHttpRouteCompiler.COMPILER_VERSION)
                .put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID)
                .put("publicationDomainId", plans.getFirst().compiled().binding().publicationDomain().publicationDomainId())
                .put("backendAuthorizationProfile", GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE);
        ObjectNode activation = json.createObjectNode().put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION).put("releaseId", releaseId);
        ObjectNode manifest = json.createObjectNode().put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION)
                .put("compilerVersion", GatewayHttpRouteCompiler.COMPILER_VERSION).put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID);
        ArrayNode entries = publication.putArray("exposures");
        ArrayNode targets = activation.putArray("exposures");
        ArrayNode resources = manifest.putArray("resources");
        ArrayNode dependencies = manifest.putArray("dependencies");
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

    private record RollbackSource(GatewayDraftVO draft, GatewayCiliumReleaseArtifact artifact) { }
}
