package top.egon.cola.component.yuheng.admin.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.security.access.AccessDeniedException;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO;
import top.egon.cola.component.yuheng.admin.catalog.domain.vo.GatewayCurrentOperationDefinitionVO;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.mcp.converter.McpApiMappingConverter;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteToolDraftBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpServerBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpManagedToolOverrideMutationDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpToolMutationControlDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException;
import top.egon.cola.component.yuheng.admin.mcp.repository.*;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayPolicyDraftBO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminRevisionConflictException;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolAdminServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private static final String TOOL = "59da10910192faf61cf5c56d05d572c4440858ded75f4350b65807e873c55d21";
    private static final AdminActor ACTOR = new AdminActor("admin-1", AdminActorTypeEnum.USER,
            Set.of(GatewayPermissionCodes.MCP_WRITE), Set.of(), Set.of(GatewayPermissionCodes.MCP_WRITE));
    private static final RequestAuditContext AUDIT = new RequestAuditContext("request-1", "trace-1");
    private GatewayDraftRepository policyStore;
    private GatewayDraftJpaRepository drafts;
    private GatewayCatalogRepository catalog;
    private McpRemoteToolDraftRepository remoteTools;
    private McpServerRepository servers;
    private IdempotencyRepository idempotency;
    private GatewayAuditLogRepository audits;
    private McpPublishedSiResolver resolver;
    private McpToolAdminService service;
    private GatewayCurrentOperationDefinitionVO source;
    private GatewayDraftBO head;
    private final AtomicReference<GatewayPolicyDraftBO> policy = new AtomicReference<>();
    private final AtomicReference<IdempotencyBO> receipt = new AtomicReference<>();
    private String previousTenant;

    @BeforeAll static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @BeforeEach void setUp() {
        previousTenant = MDC.get("tenantId"); MDC.put("tenantId", "7");
        policyStore = mock(GatewayDraftRepository.class); drafts = mock(GatewayDraftJpaRepository.class);
        catalog = mock(GatewayCatalogRepository.class); remoteTools = mock(McpRemoteToolDraftRepository.class);
        servers = mock(McpServerRepository.class); idempotency = mock(IdempotencyRepository.class);
        audits = mock(GatewayAuditLogRepository.class); resolver = mock(McpPublishedSiResolver.class);
        var json = new ObjectMapper();
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("mcpPublishedSiResolver", resolver);
        var factory = new McpReleaseContentFactory(servers, mock(McpCapabilityDraftRepository.class),
                mock(McpManagedToolOverrideRepository.class), remoteTools, mock(McpRemoteProviderRepository.class),
                mock(McpArtifactMetadataRepository.class), drafts, catalog, mock(McpValidationService.class), json,
                beans.getBeanProvider(McpPublishedSiResolver.class));
        service = new McpToolAdminService(factory, policyStore, catalog, remoteTools, servers, drafts,
                idempotency, audits, new McpApiMappingConverter(json), json, Clock.fixed(NOW, ZoneOffset.UTC));
        head = new GatewayDraftBO("group-1", ACTOR.actorId(), NOW).setRevision(3);
        when(drafts.findById("group-1")).thenAnswer(invocation -> Optional.of(head));
        when(drafts.save(any())).thenAnswer(invocation -> {
            GatewayDraftBO candidate = invocation.getArgument(0);
            return candidate.setRevision(candidate.getRevision() + 1);
        });
        when(policyStore.policies("group-1")).thenAnswer(invocation ->
                policy.get() == null ? List.of() : List.of(policy.get()));
        doAnswer(invocation -> { policy.set(invocation.getArgument(0)); return null; })
                .when(policyStore).upsertPolicy(any());
        when(idempotency.find(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(receipt.get()));
        doAnswer(invocation -> { receipt.set(invocation.getArgument(0)); return null; })
                .when(idempotency).save(any());
        source = new GatewayCurrentOperationDefinitionVO(
                new GatewayOperationBO("101", "app-1", "interface-1", "http:orders:GET:/orders/{id}", "HTTP",
                        "GET /orders/{id}", false, Map.of(), "OPENAPI31", "ACTIVE", "definition-1", 0, NOW, NOW),
                new GatewayOperationDefinitionBO("definition-1", "101", 1, "a".repeat(64), "Get one order", List.of(),
                        Map.of("type", "object"), Map.of("type", "object"), List.of(), null,
                        Map.of("mcpExposure", Map.of("registerMcp", true, "mcpServerCode", "orders", "mcpName", "orders.get",
                                "requiredPermissions", List.of("mcp:orders:read"), "riskLevel", "LOW", "idempotent", true)),
                        false, NOW, "provider"));
        when(catalog.loadCurrentOperationDefinitions("group-1")).thenReturn(List.of(source));
        when(resolver.resolveClosure(eq(7L), any())).thenReturn("c".repeat(64));
    }

    @AfterEach void restoreTenant() {
        if (previousTenant == null) MDC.remove("tenantId"); else MDC.put("tenantId", previousTenant);
    }

    @Test void legacyFkProjectionCannotBecomeAManagedToolWithoutDesiredMapping() {
        when(servers.findAllByGatewayGroupIdAndDeletedFalseOrderByServerCode("group-1"))
                .thenReturn(List.of(archivedServer()));
        assertThat(service.managedTools("group-1", null)).isEmpty();
        verifyNoInteractions(servers, catalog, resolver);
    }

    @Test void nativeRemoteToolRoutesExposeOnlyHistoricalGet() {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new top.egon.cola.component.yuheng.admin.mcp.controller.McpToolAdminController(mock(McpToolAdminService.class))).build();
        var mappings = mvc.getDispatcherServlet().getWebApplicationContext().getBean(
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var methods = mappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().stream().anyMatch(path -> path.endsWith("/remote-tools")
                        || path.endsWith("/remote-tools/{toolId}")))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream())
                .map(Enum::name).collect(java.util.stream.Collectors.toSet());
        assertThat(methods).containsExactly("GET");
    }

    @Test void nativeRemoteToolWriteAndReplayHaveNoServiceEntryPoint() {
        assertThat(java.util.Arrays.stream(McpToolAdminService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)).doesNotContain("putRemoteTool", "deleteRemoteTool");
    }

    @Test void managedMappingProjectionHasADedicatedForwardConverter() throws ClassNotFoundException {
        String converterPackage = "top.egon.cola.component.yuheng.admin.mcp.converter";
        String converterClass = converterPackage + ".McpApiMappingConverter";
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        assertThat(scanner.findCandidateComponents(converterPackage))
                .extracting(candidate -> candidate.getBeanClassName())
                .contains(converterClass);
        assertThat(Class.forName(converterClass)).isAssignableTo(BaseForwardConverter.class);
    }

    @Test void managedListUsesExactSiResourceAndAggregateRevisionWithoutLegacyFkLookup() {
        prepareMapping(Set.of("mcp:orders:audit"), "HIGH", true, true);
        assertThat(service.managedTools("group-1", "201")).singleElement().satisfies(tool -> {
            assertThat(tool.toolId()).isEqualTo(TOOL); assertThat(tool.serverId()).isEqualTo("201");
            assertThat(tool.codeServerId()).isEqualTo("201"); assertThat(tool.overrideRevision()).isEqualTo(3);
            assertThat(tool.codeServerCode()).isEqualTo("orders");
            assertThat(tool.serverCode()).isEqualTo("orders");
            assertThat(tool.operationId()).isEqualTo("101");
            assertThat(tool.operationKey()).isEqualTo("http:orders:GET:/orders/{id}");
            assertThat(tool.inputSchema()).isInstanceOf(Map.class);
            assertThat(tool.outputSchema()).isEqualTo(Map.of("type", "object"));
            assertThat(tool.codePermissions()).containsExactly("mcp:orders:read");
            assertThat(tool.additionalPermissions()).containsExactly("mcp:orders:audit");
            assertThat(tool.effectivePermissions()).containsExactlyInAnyOrder("mcp:orders:read", "mcp:orders:audit");
            assertThat(tool.codeRiskLevel()).isEqualTo("LOW");
            assertThat(tool.minimumRiskLevel()).isEqualTo("HIGH");
            assertThat(tool.effectiveRiskLevel()).isEqualTo("HIGH");
            assertThat(tool.idempotent()).isTrue();
            assertThat(tool.enabled()).isTrue();
        });
        verifyNoInteractions(servers, remoteTools);
    }

    @Test void numericOrLegacyServerIdIsNeverAliasedToTheApprovedSiReference() {
        prepareMapping(Set.of(), null, true, true);
        assertThatThrownBy(() -> service.managedTools("group-1", "server-1")).isInstanceOf(GatewayAdminNotFoundException.class);
        assertThatThrownBy(() -> service.managedTools("group-1", "202")).isInstanceOf(GatewayAdminNotFoundException.class);
        verifyNoInteractions(servers, resolver);
    }

    @Test void putUpdatesOnlyDesiredPolicyAndReplaysWithoutSecondWriteButWithFreshAuthorization() {
        prepareMapping(Set.of(), null, true, true);
        var before = intent();
        var command = command(Set.of("mcp:orders:audit"), "HIGH", false, "201", 3, 3);
        var first = service.putOverride(TOOL, command, "tighten-1", ACTOR, AUDIT);
        var repeated = service.putOverride(TOOL, command, "tighten-1", ACTOR, AUDIT);
        assertThat(first.replayed()).isFalse(); assertThat(repeated.replayed()).isTrue();
        assertThat(first.draftRevision()).isEqualTo(4); assertThat(first.resourceRevision()).isEqualTo(4);
        assertThat(repeated.resourceRevision()).isEqualTo(4);
        assertThat(intent().sourceOperation()).isEqualTo(before.sourceOperation());
        assertThat(intent().siServer()).isEqualTo(before.siServer());
        assertThat(intent().disabled()).isTrue();
        verify(policyStore).upsertPolicy(any()); verify(drafts).save(any()); verify(audits).save(any());
        assertThat(receipt.get().getScopeType()).isEqualTo("YUHENG_MCP_API_MAPPING_OVERRIDE");
        verify(resolver, times(3)).resolveClosure(7L, before.siServer());
        verifyNoInteractions(servers, remoteTools);
    }

    @Test void bothRevisionFieldsFenceTheSameAggregateBeforePolicyWrites() {
        prepareMapping(Set.of(), null, true, true);
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), null, null, null, 2, 3), "stale-a", ACTOR, AUDIT))
                .isInstanceOf(GatewayAdminRevisionConflictException.class);
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), null, null, null, 3, 2), "stale-b", ACTOR, AUDIT))
                .isInstanceOf(GatewayAdminRevisionConflictException.class);
        verify(policyStore, never()).upsertPolicy(any()); verify(drafts, never()).save(any());
    }

    @Test void rejectsRemovingExistingManagedToolPermissions() {
        prepareMapping(Set.of("mcp:orders:audit"), "HIGH", true, true);
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), "HIGH", null, null, 3, 3), "permissions", ACTOR, AUDIT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot remove existing permissions");
        verify(policyStore, never()).upsertPolicy(any());
    }

    @Test void rejectsLoweringExistingManagedToolRisk() {
        prepareMapping(Set.of(), "HIGH", true, true);
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), "MEDIUM", null, null, 3, 3), "risk", ACTOR, AUDIT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot lower existing minimum risk");
        verify(policyStore, never()).upsertPolicy(any());
    }

    @Test void rejectsReenablingManagedToolWithoutDeletingOverride() {
        prepareMapping(Set.of(), null, false, true);
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), null, null, null, 3, 3), "enable", ACTOR, AUDIT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("restored by deleting");
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), null, true, null, 3, 3), "enable-true", ACTOR, AUDIT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only disable");
        verify(policyStore, never()).upsertPolicy(any());
    }

    @Test void overrideCannotRetargetAnSiVersionUsingOnlyAnId() {
        prepareMapping(Set.of(), null, true, true);
        assertThatThrownBy(() -> service.putOverride(TOOL, command(Set.of(), null, null, "202", 3, 3), "retarget", ACTOR, AUDIT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact approved mapping SI resource");
        verify(policyStore, never()).upsertPolicy(any()); verifyNoInteractions(servers);
    }

    @Test void explicitDeleteResetsOperatorRestrictionsAndRetainsMappingAndSi() {
        prepareMapping(Set.of("mcp:orders:audit"), "HIGH", false, true);
        var before = intent();
        var result = service.deleteOverride(TOOL, control(), "reset", ACTOR, AUDIT);
        assertThat(result.resourceRevision()).isEqualTo(4);
        assertThat(intent().sourceOperation()).isEqualTo(before.sourceOperation());
        assertThat(intent().siServer()).isEqualTo(before.siServer());
        assertThat(intent().additionalPermissions()).isEmpty(); assertThat(intent().minimumRiskLevel()).isNull();
        assertThat(intent().disabled()).isFalse(); assertThat(policy.get().isEnabled()).isTrue();
        assertThat(service.managedTools("group-1", "201").getFirst().enabled()).isTrue();
        verify(policyStore, never()).deletePolicy(anyString(), anyString()); verify(audits).save(any());
    }

    @Test void explicitDeleteNeverEnablesAnIndependentlyDisabledPolicy() {
        prepareMapping(Set.of("mcp:orders:audit"), "HIGH", false, false);
        service.deleteOverride(TOOL, control(), "reset-disabled-policy", ACTOR, AUDIT);
        assertThat(policy.get().isEnabled()).isFalse();
        assertThat(service.managedTools("group-1", "201").getFirst().enabled()).isFalse();
    }

    @Test void explicitResetCannotIgnoreEitherAggregateRevision() {
        prepareMapping(Set.of("mcp:orders:audit"), "HIGH", false, true);
        assertThatThrownBy(() -> service.deleteOverride(TOOL,
                new McpToolMutationControlDTO("group-1", 2, 3, "stale resource revision"), "reset-stale-a", ACTOR, AUDIT))
                .isInstanceOf(GatewayAdminRevisionConflictException.class);
        assertThatThrownBy(() -> service.deleteOverride(TOOL,
                new McpToolMutationControlDTO("group-1", 3, 2, "stale draft revision"), "reset-stale-b", ACTOR, AUDIT))
                .isInstanceOf(GatewayAdminRevisionConflictException.class);
        verify(policyStore, never()).upsertPolicy(any()); verify(drafts, never()).save(any());
        verifyNoInteractions(audits);
    }

    @Test void resetRequiresBothAuthenticatedScopeAndCapability() {
        prepareMapping(Set.of(), null, false, true);
        var noScope = new AdminActor("admin", AdminActorTypeEnum.USER, Set.of("*"), Set.of(), Set.of());
        assertThatThrownBy(() -> service.deleteOverride(TOOL, control(), "no-scope", noScope, AUDIT)).isInstanceOf(AccessDeniedException.class);
        var noCapability = new AdminActor("admin", AdminActorTypeEnum.USER, Set.of(), Set.of(), Set.of(GatewayPermissionCodes.MCP_WRITE));
        assertThatThrownBy(() -> service.deleteOverride(TOOL, control(), "no-cap", noCapability, AUDIT)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policyStore, drafts, catalog, resolver, idempotency, audits);
    }

    @Test void resetRejectsCodeThatNoLongerApprovesMcp() {
        prepareMapping(Set.of(), null, false, true);
        source.definition().setAttributes(Map.of("mcpExposure", Map.of("registerMcp", false)));
        assertThatThrownBy(() -> service.deleteOverride(TOOL, control(), "code-disabled", ACTOR, AUDIT)).isInstanceOf(McpValidationException.class);
        verify(policyStore, never()).upsertPolicy(any()); verifyNoInteractions(idempotency, audits);
    }

    @Test void aPriorSuccessfulReceiptDoesNotBypassCurrentSiAuthorization() {
        prepareMapping(Set.of(), null, false, true);
        service.deleteOverride(TOOL, control(), "reset-once", ACTOR, AUDIT);
        doThrow(new SecurityException("denied")).when(resolver).resolveClosure(eq(7L), any());
        assertThatThrownBy(() -> service.deleteOverride(TOOL, control(), "reset-once", ACTOR, AUDIT)).isInstanceOf(SecurityException.class);
        verify(policyStore, times(1)).upsertPolicy(any()); verify(audits, times(1)).save(any());
    }

    @Test void missingTrustedOwnerCannotReadOrMutateMappings() {
        MDC.remove("tenantId");
        assertThatThrownBy(() -> service.managedTools("group-1", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.deleteOverride(TOOL, control(), "owner", ACTOR, AUDIT)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(policyStore, drafts, catalog, resolver);
    }

    @Test void historicalRemoteToolsNeverWriteOrReplayNativeMutation() {
        when(servers.findAllByGatewayGroupIdAndDeletedFalseOrderByServerCode("group-1")).thenReturn(List.of(archivedServer()));
        when(remoteTools.load("group-1")).thenReturn(List.of(McpRemoteToolDraftBO.normalized("tool-archived", "group-1", "server-1",
                "remote.orders.get", "mount-1", Map.of("riskLevel", "HIGH"), false, 7)));
        var first = service.remoteTools("group-1", "server-1");
        assertThat(service.remoteTools("group-1", "server-1")).isEqualTo(first);
        assertThat(first).singleElement().satisfies(tool -> { assertThat(tool.id()).isEqualTo("tool-archived"); assertThat(tool.enabled()).isFalse(); });
        verify(remoteTools, never()).save(any(), any(Long.class), any(), any());
        verify(remoteTools, never()).softDelete(anyString(), any(Long.class), any(), any());
        verifyNoInteractions(policyStore, drafts, catalog, resolver, idempotency, audits);
    }

    @Test void historicalRemoteToolsRejectServerOutsideRequestedGroupBeforeReadingTools() {
        when(servers.findAllByGatewayGroupIdAndDeletedFalseOrderByServerCode("group-1")).thenReturn(List.of());
        assertThatThrownBy(() -> service.remoteTools("group-1", "foreign-server")).isInstanceOf(GatewayAdminNotFoundException.class);
        verifyNoInteractions(remoteTools, policyStore, drafts, catalog, resolver, idempotency, audits);
    }

    private void prepareMapping(Set<String> additional, String minimumRisk, boolean enabled, boolean policyEnabled) {
        var intent = new McpApiMappingContentBO(new McpApiMappingContentBO.SourceOperationVO("101", "http:orders:GET:/orders/{id}", "a".repeat(64)),
                new McpApiMappingContentBO.SiServerVersionVO("201", "301", "3", "d".repeat(64), "commerce", "default", "test", "orders-app", "orders"),
                additional, minimumRisk, !enabled);
        policy.set(new GatewayPolicyDraftBO("group-1", "mapping-1", McpApiMappingContentBO.POLICY_TYPE,
                McpApiMappingContentBO.POLICY_SCOPE, intent.content(), policyEnabled, NOW, ACTOR.actorId()));
    }

    private McpApiMappingContentBO intent() { return McpApiMappingContentBO.fromContent(policy.get().getContent()); }
    private McpManagedToolOverrideMutationDTO command(Set<String> permissions, String risk, Boolean enabled, String serverId, long revision, long draftRevision) {
        return new McpManagedToolOverrideMutationDTO("group-1", enabled, serverId, permissions, risk, revision, draftRevision, "tighten managed Tool");
    }
    private McpToolMutationControlDTO control() { return new McpToolMutationControlDTO("group-1", 3, 3, "explicit approved reset"); }
    private McpServerBO archivedServer() { return new McpServerBO("server-1", "group-1", "orders", "Orders", null, null,
            Set.of("STABLE_2025_11_25"), "https://resource.egon.top/yuheng-mcp", 30, ACTOR, NOW); }
}
