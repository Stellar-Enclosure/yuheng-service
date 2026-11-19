package top.egon.cola.component.yuheng.admin.mcp.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static top.egon.cola.component.yuheng.admin.mcp.application.McpMappingSeedArtifactTest.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.transaction.support.*;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.*;
import top.egon.cola.component.yuheng.admin.catalog.domain.vo.GatewayCurrentOperationDefinitionVO;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.group.domain.bo.GatewayGroupBO;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpMappingSeedFreezeCommand;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.mcp.repository.*;
import top.egon.cola.component.yuheng.admin.mcp.service.*;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.*;
import top.egon.cola.component.yuheng.admin.routing.repository.*;
import top.egon.cola.component.yuheng.admin.shared.domain.*;
import top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

class McpMappingSeedServiceTest {
    @TempDir Path dir;
    GatewayGroupRepository groups;
    GatewayDraftJpaRepository drafts;
    GatewayDraftRepository policies;
    IdempotencyRepository receipts;
    GatewayAuditLogRepository audits;
    GatewayCatalogRepository catalog;
    GatewayPersistenceContextComponent context;
    TransactionTemplate tx;
    McpMappingSeedService service;
    GatewayDraftBO head;
    GatewayCurrentOperationDefinitionVO source;
    AtomicReference<IdempotencyBO> stored = new AtomicReference<>();
    String previous;
    static final AdminActor ACTOR = new AdminActor(
        "operator", AdminActorTypeEnum.USER, Set.of("yuheng:mcp:write"), Set.of(), Set.of("yuheng:mcp:write"));
    static final RequestAuditContext AUDIT = new RequestAuditContext("r1", "t1");
    @BeforeAll
    static void ids() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }
    @BeforeEach
    void setup() throws Exception {
        previous = MDC.get("tenantId");
        MDC.put("tenantId", "7");
        groups = mock(GatewayGroupRepository.class);
        drafts = mock(GatewayDraftJpaRepository.class);
        policies = mock(GatewayDraftRepository.class);
        receipts = mock(IdempotencyRepository.class);
        audits = mock(GatewayAuditLogRepository.class);
        catalog = mock(GatewayCatalogRepository.class);
        context = mock(GatewayPersistenceContextComponent.class);
        tx = mock(TransactionTemplate.class);
        when(context.installationTenantId()).thenReturn(7L);
        when(groups.findByIdAndDeletedFalse("10"))
            .thenReturn(
                Optional.of(new GatewayGroupBO().setId("10").setEnv("test").setNamespace("default").setEnabled(true)));
        head = new GatewayDraftBO("10", "operator", NOW).setRevision(3);
        when(drafts.lockForPublication("10")).thenReturn(Optional.of(head));
        when(policies.policies("10")).thenReturn(List.of());
        when(receipts.find(anyString(), anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(0));
            return null;
        })
            .when(receipts)
            .save(any());
        doAnswer(i
            -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus()))
            .when(tx)
            .execute(any());
        var operation = new GatewayOperationBO()
                            .setId("101")
                            .setOperationKey(KEY)
                            .setProtocol("HTTP")
                            .setSourceType("OPENAPI31")
                            .setLifecycleStatus("ACTIVE")
                            .setCurrentDefinitionId("401");
        var definition =
            new GatewayOperationDefinitionBO()
                .setId("401")
                .setOperationId("101")
                .setDefinitionSha256("a".repeat(64))
                .setRequestSchema(Map.of("type", "object"))
                .setResponseSchema(Map.of("type", "object"))
                .setAttributes(Map.of("mcpExposure",
                    Map.of("registerMcp", true, "mcpServerCode", "orders", "mcpName", "orders.get",
                        "requiredPermissions", List.of("mcp:orders:read"), "riskLevel", "MEDIUM", "idempotent", true)));
        source = new GatewayCurrentOperationDefinitionVO(operation, definition);
        when(catalog.loadCurrentOperationDefinitions("10")).thenReturn(List.of(source));
        Path file = dir.resolve("registration.json");
        byte[] bytes = JSON.writeValueAsBytes(descriptor());
        Files.write(file, bytes);
        var identities = new McpRegisteredSiIdentityFileSource(file, McpMappingSeedArtifactVO.sha256(bytes), 7, JSON);
        var compiler = new McpReleaseContentFactory(mock(McpServerRepository.class),
            mock(McpCapabilityDraftRepository.class), mock(McpManagedToolOverrideRepository.class),
            mock(McpRemoteToolDraftRepository.class), mock(McpRemoteProviderRepository.class),
            mock(McpArtifactMetadataRepository.class), drafts, catalog, mock(McpValidationService.class), JSON);
        service = new McpMappingSeedService(groups, drafts, policies, identities, compiler, receipts, audits, tx,
            context, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach
    void restore() {
        if (previous == null)
            MDC.remove("tenantId");
        else
            MDC.put("tenantId", previous);
    }
    McpMappingSeedArtifactVO freeze() {
        return service.freeze("10", command(), "k1", ACTOR, AUDIT);
    }
    @Test
    void firstRegisteredMappingProducesApprovedImmutableBytesAndNoDraftMutation() {
        var result = freeze();
        assertThat(result.document().path("registeredSi").path("resourceId").asText()).isEqualTo("201");
        assertThat(result.document().path("mappings").get(0).path("tool").path("requiredPermissions"))
            .contains(JSON.getNodeFactory().textNode("mcp:orders:read"));
        assertThat(result.document().path("mappings").get(0).path("tool").path("riskLevel").asText()).isEqualTo("HIGH");
        verify(receipts).save(any());
        var audit = org.mockito.ArgumentCaptor.forClass(
            top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO.class);
        verify(audits).save(audit.capture());
        assertThat(audit.getValue().getAfterSummary()).containsEntry("changeReason", command().changeReason());
        verify(drafts, never()).save(any());
        verify(policies, never()).upsertPolicy(any());
        assertThat(head.getBasedOnReleaseId()).isNull();
        assertThat(head.getRevision()).isEqualTo(3);
    }
    @Test
    void identicalReplayUsesExactCommittedBytesBeforeStaleDraftRevisionButReauthorizes() {
        var first = freeze();
        head.setRevision(4);
        assertThat(freeze().canonicalBytes()).isEqualTo(first.canonicalBytes());
        verify(audits, times(1)).save(any());
        var denied = new AdminActor("operator", AdminActorTypeEnum.USER, Set.of("*"), Set.of(), Set.of());
        assertThatThrownBy(() -> service.freeze("10", command(), "k1", denied, AUDIT))
            .hasMessageContaining("not authorized");
    }
    @Test
    void changedSameKeyWrongRevisionAndExpiredReceiptDoNotCreateEvidence() {
        head.setRevision(4);
        assertThatThrownBy(this::freeze).hasMessageContaining("draft revision");
        verify(receipts, never()).save(any());
        head.setRevision(3);
        freeze();
        stored.get().setExpiresAt(NOW);
        assertThatThrownBy(this::freeze).hasMessageContaining("expired");
        stored.get().setExpiresAt(NOW.plusSeconds(10));
        stored.get().setPayloadSha256("f".repeat(64));
        assertThatThrownBy(this::freeze)
            .isInstanceOf(top.egon.cola.component.yuheng.admin.shared.domain.exception
                    .GatewayAdminIdempotencyConflictException.class);
    }
    @Test
    void staleOrUnapprovedSourceCannotDebitOrUseReceipt() {
        freeze();
        source.definition().setDefinitionSha256("b".repeat(64));
        assertThatThrownBy(this::freeze).hasMessageContaining("unavailable");
        verify(receipts, times(1)).save(any());
        source.definition().setDefinitionSha256("a".repeat(64)).setAttributes(Map.of());
        assertThatThrownBy(this::freeze).hasMessageContaining("unavailable");
    }
    @Test
    void fixedOwnerAndNamespaceAreCheckedBeforeAnyWrite() {
        MDC.put("tenantId", "8");
        assertThatThrownBy(this::freeze).hasMessageContaining("not authorized");
        verifyNoInteractions(receipts, audits);
        MDC.put("tenantId", "7");
        when(groups.findByIdAndDeletedFalse("10"))
            .thenReturn(
                Optional.of(new GatewayGroupBO().setId("10").setEnv("prod").setNamespace("default").setEnabled(true)));
        assertThatThrownBy(this::freeze).hasMessageContaining("not authorized");
        verifyNoInteractions(receipts, audits);
    }
    @Test
    void noTemplateCommitSuccessMeansNoExportEvenIfCallbackRan() {
        doAnswer(i -> {
            ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus());
            throw new org.springframework.transaction.TransactionSystemException("private driver payload");
        })
            .when(tx)
            .execute(any());
        assertThatThrownBy(this::freeze)
            .hasMessageContaining("could not be confirmed")
            .hasMessageNotContaining("private driver");
        doAnswer(i
            -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus()))
            .when(tx)
            .execute(any());
        assertThat(freeze().publicationId()).isEqualTo(stored.get().getResourceId());
    }
    @Test
    void existingBoundMappingCannotBeReplacedByRegistrationOnlyEvidence() {
        var bound = new top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO(
            command().mappings().getFirst().source(),
            new top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO.SiServerVersionVO(
                "201", "501", "1", "d".repeat(64), "commerce", "default", "test", null, "orders"),
            Set.of(), null, true);
        when(policies.policies("10"))
            .thenReturn(List.of(new GatewayPolicyDraftBO(
                "10", "p1", "MCP_API_MAPPING", "OPERATION", bound.content(), false, NOW, "operator")));
        assertThatThrownBy(this::freeze).hasMessageContaining("existing fixed-version");
        verifyNoInteractions(receipts, audits);
    }
    @Test
    void disabledSeedStaysDisabledWithoutWeakeningCodePermissions() {
        var input = command().mappings().getFirst();
        var disabled = new McpMappingSeedFreezeCommand("3", "orders-registration",
            List.of(new McpMappingSeedFreezeCommand.MappingInputVO(
                input.operationId(), input.operationKey(), input.definitionDigest(), Set.of(), null, true)),
            "Keep tool disabled");
        var artifact = service.freeze("10", disabled, "k1", ACTOR, AUDIT);
        assertThat(artifact.document().path("mappings").get(0).path("tool").path("enabled").booleanValue()).isFalse();
        assertThat(artifact.document().path("mappings").get(0).path("tool").path("riskLevel").textValue())
            .isEqualTo("MEDIUM");
    }
    @Test
    void nullableOwnerSurvivesTheActualReceiptJsonBoundaryWithNonNullInclusion() throws Exception {
        var first = freeze();
        var serializer = new com.fasterxml.jackson.databind.ObjectMapper().setSerializationInclusion(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        Map<String, Object> jsonbRoundTrip =
            serializer.readValue(serializer.writeValueAsBytes(stored.get().getResponse()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(jsonbRoundTrip).containsOnlyKeys("canonicalArtifact");
        stored.get().setResponse(jsonbRoundTrip);
        assertThat(freeze().canonicalBytes()).isEqualTo(first.canonicalBytes());
        assertThat(freeze().document().path("registeredSi").path("ownerAppCode").isNull()).isTrue();
    }

    @Test
    void anOuterUncommittedTransactionCannotReceiveExportableBytes() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(this::freeze).hasMessageContaining("independent operator command");
            verifyNoInteractions(tx, groups, drafts, policies, receipts, audits);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void seedWriterHasNoRouteReleaseOrOutboxPorts() {
        assertThat(Arrays.stream(McpMappingSeedService.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
            .noneMatch(n
                -> n.contains("Exposure") || n.contains("ReleaseRepository") || n.contains("Outbox")
                    || n.contains("Kubernetes"));
    }
}
