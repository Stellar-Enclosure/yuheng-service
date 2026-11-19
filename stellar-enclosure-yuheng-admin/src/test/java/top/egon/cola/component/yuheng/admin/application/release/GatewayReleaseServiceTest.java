package top.egon.cola.component.yuheng.admin.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.outbox.api.OutboxMessage;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposureViewConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.*;
import top.egon.cola.component.yuheng.admin.group.domain.bo.GatewayGroupBO;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpReleaseContentFactory;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayReleaseBO;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayReleaseAttemptBO;
import top.egon.cola.component.yuheng.admin.release.domain.dto.GatewayReleaseCreateCommandDTO;
import top.egon.cola.component.yuheng.admin.release.domain.dto.GatewayReleaseRollbackCommandDTO;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayPolicyDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftVO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.routing.service.GatewayDraftService;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayReleaseServiceTest {
    static final Instant NOW = Instant.parse("2026-11-18T20:00:00Z");
    static final long OWNER = 101L;
    static final String GROUP = "800001";
    static final AdminActor ADMIN = new AdminActor("admin-a", AdminActorTypeEnum.USER,
            Set.of(GatewayPermissionCodes.RELEASES_WRITE), Set.of(), Set.of(GatewayPermissionCodes.RELEASES_WRITE));
    static final RequestAuditContext REQUEST = new RequestAuditContext("request-a", "trace-a");
    NativeFixture f;

    @BeforeAll static void ids() { SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5)); }
    @BeforeEach void setup() { MDC.put("tenantId", Long.toString(OWNER)); f = new NativeFixture(); }
    @AfterEach void clear() { MDC.clear(); }

    @Test
    void legacyRetryIsReadOnlyAndCannotBeginAnotherPublicationAttempt() {
        f.heads.put("810001", f.header("810001", GatewayReleaseStatus.UNKNOWN));
        assertThatThrownBy(() -> f.service.retry("810001", ADMIN, REQUEST)).isInstanceOf(IllegalStateException.class)
                .hasMessage(GatewayReleasePublicationCoordinator.LEGACY_READ_ONLY);
        verify(f.releases, never()).nextAttempt(anyString(), any());
        verify(f.releases, never()).beginAttempt(anyString(), anyInt(), any());
        verify(f.releases, never()).loadCompiled(anyString());
    }

    @Test
    void initialPublishRegistersOneStableGenerationAndOnlyEnqueuesNativeWork() {
        var result = f.create();
        assertThat(result.status()).isEqualTo(GatewayReleaseStatus.PUBLISHING);
        assertThat(f.rows).hasSize(1);
        var row = f.onlyRow();
        assertThat(row.getDesiredGeneration()).isEqualTo(1L);
        assertThat(row.getObservedGeneration()).isZero();
        assertThat(row.getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.OPEN);
        assertThat(row.getRouteName()).isEqualTo("yuheng-exposure-" + row.getId());
        assertThat(result.exposureOutcomes()).hasSize(1);
        assertThat(result.exposureOutcomes().getFirst().result()).isNull();
        assertThat(result.clusterTargets().getFirst().converged()).isFalse();
        assertThat(f.messages).hasSize(1);
        assertThat(f.audits).hasSize(1);
        assertThat(f.artifacts.get(result.releaseId()).chunkManifest().path("resources").get(0).path("object").path("spec").path("parentRefs")).isEmpty();
        verify(f.releases, never()).insert(any(), any(), anyInt());
        verify(f.releases, never()).loadCompiled(anyString());
    }

    @Test
    void unchangedFreshlyObservedContentCanFinalizeTheNewAttemptAndAdvanceDraftBasis() {
        var first = f.create();
        f.onlyRow().setObservedState("OPEN").setObservedGeneration(1L).setReconcileStatus("SYNCED");
        f.heads.get(first.releaseId()).setStatus(GatewayReleaseStatus.SUCCESS);
        var second = f.create();
        assertThat(second.releaseId()).isNotEqualTo(first.releaseId());
        assertThat(second.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(f.onlyRow().getReleaseId()).isEqualTo(Long.valueOf(first.releaseId()));
        assertThat(f.drafts.findById(GROUP)).get().extracting(GatewayDraftBO::getBasedOnReleaseId)
                .isEqualTo(second.releaseId());
        verify(f.drafts).save(any(GatewayDraftBO.class));
    }

    @Test
    void contentPublishKeepsClosedAndAdvancesOnlyItsContentGeneration() {
        f.create();
        var row = f.onlyRow();
        long id = row.getId();
        row.setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setDesiredGeneration(2L).setVersion(2L);
        f.definition.setDefinitionVersion(2L).setDefinitionSha256("e".repeat(64));
        var next = f.create();
        assertThat(f.onlyRow().getId()).isEqualTo(id);
        assertThat(f.onlyRow().getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.onlyRow().getDesiredGeneration()).isEqualTo(3L);
        assertThat(next.exposureOutcomes().getFirst().result()).isEqualTo("CLOSED_OVERRIDE");
        assertThat(next.status()).isEqualTo(GatewayReleaseStatus.PUBLISHING);
    }

    @Test
    void terminalContentPublishNeverMutatesTheTombstoneOrAllocatesAnotherUrl() {
        f.create();
        var row = f.onlyRow();
        row.setDesiredState(GatewayUrlExposureStateEnum.WITHDRAWN).setDesiredGeneration(4L).setVersion(7L);
        String originalDigest = row.getContentSha256();
        long originalRelease = row.getReleaseId();
        f.definition.setDefinitionVersion(2L).setDefinitionSha256("e".repeat(64));
        var next = f.create();
        assertThat(f.rows).hasSize(1);
        assertThat(f.onlyRow().getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.WITHDRAWN);
        assertThat(f.onlyRow().getDesiredGeneration()).isEqualTo(4L);
        assertThat(f.onlyRow().getVersion()).isEqualTo(7L);
        assertThat(f.onlyRow().getContentSha256()).isEqualTo(originalDigest);
        assertThat(f.onlyRow().getReleaseId()).isEqualTo(originalRelease);
        assertThat(next.exposureOutcomes().getFirst().result()).isEqualTo("WITHDRAWN_OVERRIDE");
    }

    @Test
    void retryOfAnOlderGenerationIsStaleAndOnlyWakesTheCurrentClosedIntent() {
        var original = f.create();
        f.heads.get(original.releaseId()).setStatus(GatewayReleaseStatus.UNKNOWN);
        f.onlyRow().setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setDesiredGeneration(2L).setVersion(3L);
        var result = f.service.retry(original.releaseId(), ADMIN, REQUEST);
        assertThat(result.status()).isEqualTo(GatewayReleaseStatus.SUPERSEDED);
        assertThat(result.exposureOutcomes().getFirst().result()).isEqualTo("STALE");
        assertThat(f.onlyRow().getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.onlyRow().getDesiredGeneration()).isEqualTo(2L);
    }

    @Test
    void rollbackCreatesANewNativeReleaseAndCannotRestoreTheSourceOpenState() {
        var original = f.create();
        f.heads.get(original.releaseId()).setStatus(GatewayReleaseStatus.SUCCESS);
        f.onlyRow().setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setDesiredGeneration(2L).setVersion(3L);
        var rolled = f.service.rollback(GROUP, new GatewayReleaseRollbackCommandDTO(original.releaseId(), 0L, "rollback"), ADMIN, REQUEST);
        assertThat(rolled.releaseId()).isNotEqualTo(original.releaseId());
        assertThat(rolled.rollbackOfReleaseId()).isEqualTo(original.releaseId());
        assertThat(rolled.exposureOutcomes().getFirst().effectiveState()).isEqualTo("CLOSED");
        assertThat(f.onlyRow().getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
    }

    @Test
    void rollbackRejectsAMissingExactFrozenDefinitionInsteadOfSubstitutingCurrentContent() {
        var original = f.create();
        f.heads.get(original.releaseId()).setStatus(GatewayReleaseStatus.SUCCESS);
        f.definition.setDefinitionSha256("e".repeat(64));
        assertThatThrownBy(() -> f.service.rollback(GROUP,
                new GatewayReleaseRollbackCommandDTO(original.releaseId(), 0L, "rollback"), ADMIN, REQUEST))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.artifacts).hasSize(1);
        assertThat(f.rows).hasSize(1);
    }

    @Test
    void missingOrPartialTrustedBindingFailsBeforeAnyReleaseOrControlWrite() {
        when(f.resolver.resolve(anyLong(), anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(f::create).isInstanceOf(IllegalStateException.class);
        assertThat(f.heads).isEmpty();
        assertThat(f.rows).isEmpty();
        assertThat(f.messages).isEmpty();
    }

    @Test
    void mappingOnlyCreatePublishesFrozenContentWithoutHttpWork() {
        JsonNode section = f.mappingDraft(false, true, "LOW", "orders:read");

        var created = f.create();

        assertThat(created.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(created.exposureOutcomes()).isEmpty();
        assertThat(created.clusterTargets()).isEmpty();
        assertThat(f.artifacts.get(created.releaseId()).mcpPublication()).isEqualTo(section);
        assertThat(f.artifacts.get(created.releaseId()).hasExposurePublication()).isFalse();
        assertThat(created.validationReport()).containsEntry("mcpPublication",
                Map.of("contentSha256", section.path("contentSha256").textValue(), "mappingCount", 1));
        assertThat(f.rows).isEmpty();
        assertThat(f.messages).isEmpty();
        verify(f.resolver, never()).resolve(anyLong(), anyLong(), anyString(), anyString());
        var order = inOrder(f.drafts, f.releases);
        order.verify(f.drafts).lockForPublication(GROUP);
        order.verify(f.releases).hasReleaseInProgress(GROUP);
        order.verify(f.releases).insertNative(any(), any(), anyInt());
    }

    @Test
    void mixedCreatePublishesBothSectionsAndAwaitsOnlyActualHttpRoute() {
        JsonNode section = f.mappingDraft(true, true, "LOW", "orders:read");

        var created = f.create();

        assertThat(created.status()).isEqualTo(GatewayReleaseStatus.PUBLISHING);
        assertThat(created.exposureOutcomes()).hasSize(1);
        assertThat(f.artifacts.get(created.releaseId()).hasExposurePublication()).isTrue();
        assertThat(f.artifacts.get(created.releaseId()).mcpPublication()).isEqualTo(section);
        assertThat(f.messages).hasSize(1);
    }

    @Test
    void emptyDraftStillCannotPublishAndDoesNotResolveHttpOrMcpBindings() {
        when(f.draftService.get(GROUP)).thenReturn(new GatewayDraftVO(GROUP, 0L, null, "EDITABLE", "source",
                List.of(), List.of(), NOW));

        assertThatThrownBy(f::create).hasMessage("YUHENG_RELEASE_NO_PUBLIC_EXPOSURES");
        assertThat(f.heads).isEmpty();
        verify(f.resolver, never()).resolve(anyLong(), anyLong(), anyString(), anyString());
        verify(f.mcp, never()).compileMappingSection(anyLong(), any());
    }

    @Test
    void retryRevalidatesAndReusesTheExactFrozenMappingSection() {
        JsonNode section = f.mappingDraft(false, true, "LOW", "orders:read");
        var created = f.create();
        f.heads.get(created.releaseId()).setStatus(GatewayReleaseStatus.UNKNOWN);

        var retried = f.service.retry(created.releaseId(), ADMIN, REQUEST);

        verify(f.mcp).revalidateMappingSection(OWNER, GROUP, section);
        assertThat(retried.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(f.artifacts.get(retried.releaseId()).mcpPublication()).isEqualTo(section);
        assertThat(f.attempts.get(retried.releaseId())).hasSize(2);
        assertThat(f.messages).isEmpty();
    }

    @Test
    void retryWithRevokedSourceCannotBeginAnotherAttempt() {
        JsonNode section = f.mappingDraft(false, true, "LOW", "orders:read");
        var created = f.create();
        f.heads.get(created.releaseId()).setStatus(GatewayReleaseStatus.UNKNOWN);
        doThrow(new IllegalStateException("source authorization revoked"))
                .when(f.mcp).revalidateMappingSection(OWNER, GROUP, section);

        assertThatThrownBy(() -> f.service.retry(created.releaseId(), ADMIN, REQUEST))
                .hasMessage("source authorization revoked");
        verify(f.releases, never()).nextAttempt(anyString(), any());
        assertThat(f.attempts.get(created.releaseId())).hasSize(1);
        assertThat(f.messages).isEmpty();
    }

    @Test
    void mappingRetryUsesStatusAfterAcquiringTheGroupPublicationLock() {
        f.mappingDraft(false, true, "LOW", "orders:read");
        var created = f.create();
        String releaseId = created.releaseId();
        when(f.releases.find(releaseId)).thenReturn(Optional.of(f.header(releaseId, GatewayReleaseStatus.UNKNOWN)),
                Optional.of(f.header(releaseId, GatewayReleaseStatus.SUCCESS)));

        assertThatThrownBy(() -> f.service.retry(releaseId, ADMIN, REQUEST))
                .hasMessage("YUHENG_ADMIN_RELEASE_NOT_RETRYABLE");

        verify(f.releases, never()).nextAttempt(anyString(), any());
        verify(f.mcp, never()).revalidateMappingSection(anyLong(), anyString(), any());
    }

    @Test
    void rollbackCreatesNewReleaseWithTheExactFrozenMappingSection() {
        JsonNode section = f.mappingDraft(false, true, "LOW", "orders:read");
        var original = f.create();
        f.mappingDraft(false, true, "CRITICAL", "other:permission");

        var rolled = f.service.rollback(GROUP, new GatewayReleaseRollbackCommandDTO(original.releaseId(), 0L, "rollback"), ADMIN, REQUEST);

        verify(f.mcp).revalidateMappingSection(OWNER, GROUP, section);
        assertThat(rolled.releaseId()).isNotEqualTo(original.releaseId());
        assertThat(rolled.rollbackOfReleaseId()).isEqualTo(original.releaseId());
        assertThat(rolled.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(f.artifacts.get(rolled.releaseId()).mcpPublication()).isEqualTo(section);
        assertThat(f.messages).isEmpty();
    }

    @Test
    void rollbackCannotRestoreToolDisabledByNewerSuccessfulPublication() {
        f.mappingDraft(false, true, "LOW", "orders:read");
        var original = f.create();
        f.mappingDraft(false, false, "LOW", "orders:read");
        f.create();

        assertThatThrownBy(() -> f.service.rollback(GROUP,
                new GatewayReleaseRollbackCommandDTO(original.releaseId(), 0L, "rollback"), ADMIN, REQUEST))
                .hasMessage("YUHENG_RELEASE_MCP_REPLAY_NO_LONGER_AUTHORIZED");
        assertThat(f.artifacts).hasSize(2);
    }

    @Test
    void retryCannotChangeAnOlderPublicationAfterTheDraftBasisAdvances() {
        f.mappingDraft(false, true, "LOW", "orders:read");
        var original = f.create();
        f.heads.get(original.releaseId()).setStatus(GatewayReleaseStatus.UNKNOWN);
        f.mappingDraft(false, true, "HIGH", "orders:read", "orders:privileged");
        f.create();

        assertThatThrownBy(() -> f.service.retry(original.releaseId(), ADMIN, REQUEST))
                .hasMessage("YUHENG_RELEASE_MCP_REPLAY_NO_LONGER_AUTHORIZED");
        assertThat(f.attempts.get(original.releaseId())).hasSize(1);
    }

    @Test
    void rollbackCannotDropNewPermissionsOrLowerTheEffectiveRiskFloor() {
        f.mappingDraft(false, true, "LOW", "orders:read");
        var original = f.create();
        f.mappingDraft(false, true, "HIGH", "orders:read", "orders:privileged");
        f.create();

        assertThatThrownBy(() -> f.service.rollback(GROUP,
                new GatewayReleaseRollbackCommandDTO(original.releaseId(), 0L, "rollback"), ADMIN, REQUEST))
                .hasMessage("YUHENG_RELEASE_MCP_REPLAY_NO_LONGER_AUTHORIZED");
        assertThat(f.artifacts).hasSize(2);
    }

    @Test
    void rollbackCannotSkipCurrentMcpRestrictionsWhenBasisArtifactIsMissing() {
        f.mappingDraft(false, true, "LOW", "orders:read");
        var original = f.create();
        f.mappingDraft(false, false, "HIGH", "orders:read", "orders:privileged");
        var current = f.create();
        when(f.releases.loadNative(current.releaseId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> f.service.rollback(GROUP,
                new GatewayReleaseRollbackCommandDTO(original.releaseId(), 0L, "rollback"), ADMIN, REQUEST))
                .hasMessage("YUHENG_RELEASE_MCP_REPLAY_NO_LONGER_AUTHORIZED");
        verify(f.mcp, never()).revalidateMappingSection(anyLong(), anyString(), any());
        assertThat(f.artifacts).hasSize(2);
        assertThat(f.messages).isEmpty();
    }

    @Test
    void retryCannotSkipCurrentMcpRestrictionsWhenBasisArtifactIdentityDoesNotMatch() {
        f.mappingDraft(false, true, "LOW", "orders:read");
        var baseline = f.create();
        var original = f.create();
        f.heads.get(original.releaseId()).setStatus(GatewayReleaseStatus.UNKNOWN);
        f.drafts.findById(GROUP).orElseThrow().setBasedOnReleaseId(baseline.releaseId());
        when(f.releases.loadNative(baseline.releaseId())).thenReturn(Optional.of(f.artifacts.get(original.releaseId())));

        assertThatThrownBy(() -> f.service.retry(original.releaseId(), ADMIN, REQUEST))
                .hasMessage("YUHENG_RELEASE_MCP_REPLAY_NO_LONGER_AUTHORIZED");
        verify(f.releases, never()).nextAttempt(anyString(), any());
        assertThat(f.attempts.get(original.releaseId())).hasSize(1);
    }

    private static GatewayPolicyDraftBO mappingPolicy() {
        return GatewayPolicyDraftBO.builder().gatewayGroupId(GROUP).policyId("orders-tool")
                .policyType(McpApiMappingContentBO.POLICY_TYPE).policyScope(McpApiMappingContentBO.POLICY_SCOPE)
                .enabled(true).content(Map.of()).build();
    }

    @Test
    void sourceOperationMustRemainExternallyAuthorizedAndActive() {
        f.operation.setExternalAccessible(false);
        assertThatThrownBy(f::create).isInstanceOf(IllegalStateException.class);
        assertThat(f.heads).isEmpty();
        f.operation.setExternalAccessible(true).setLifecycleStatus("OFFLINE");
        assertThatThrownBy(f::create).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sourceCompileRejectsUnsupportedLegacyStreamingPolicyRatherThanDowngradingIt() {
        f.route.setContent(Map.of("host", "api.example.com", "httpMethod", "GET", "pathPattern", "/orders", "accessZones", List.of("PUBLIC"),
                "transportPolicy", Map.of("profile", "OPENAI_HTTP")));
        assertThatThrownBy(f::create).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.heads).isEmpty();
    }

    @Test
    void anOutboxFailureRollsBackReleaseContentControlAndAuditTogether() {
        doThrow(new IllegalStateException("synthetic outbox unavailable")).when(f.outbox).enqueue(any());
        assertThatThrownBy(f::create).isInstanceOf(IllegalStateException.class);
        assertThat(f.heads).isEmpty();
        assertThat(f.artifacts).isEmpty();
        assertThat(f.rows).isEmpty();
        assertThat(f.audits).isEmpty();
        assertThat(f.messages).isEmpty();
    }

    @Test
    void archiveGetPreservesTheOldProjectionWithEmptyNativeOutcomeLists() {
        f.heads.put("810001", f.header("810001", GatewayReleaseStatus.SUCCESS));
        var result = f.service.get("810001");
        assertThat(result.releaseId()).isEqualTo("810001");
        assertThat(result.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(result.exposureOutcomes()).isEmpty();
        assertThat(result.clusterTargets()).isEmpty();
        verify(f.releases, never()).loadCompiled(anyString());
    }

    /** Fixture uses the actual pure compiler and versioned JSON codec; database atomicity has separate PG evidence. */
    static final class NativeFixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final GatewayGroupRepository groups = mock(GatewayGroupRepository.class);
        final GatewayDraftJpaRepository drafts = mock(GatewayDraftJpaRepository.class);
        final GatewayDraftService draftService = mock(GatewayDraftService.class);
        final GatewayCatalogRepository catalog = mock(GatewayCatalogRepository.class);
        final GatewayReleaseRepository releases = mock(GatewayReleaseRepository.class);
        final GatewayAuditLogRepository auditStore = mock(GatewayAuditLogRepository.class);
        final GatewayUrlExposureRepository exposures = mock(GatewayUrlExposureRepository.class);
        final TransactionalOutbox outbox = mock(TransactionalOutbox.class);
        final BindingResolver resolver = mock(BindingResolver.class);
        final McpReleaseContentFactory mcp = mock(McpReleaseContentFactory.class);
        final Map<String, GatewayReleaseBO> heads = new LinkedHashMap<>();
        final Map<String, GatewayCiliumReleaseArtifact> artifacts = new LinkedHashMap<>();
        final Map<Long, GatewayUrlExposureBO> rows = new LinkedHashMap<>();
        final Map<String, List<GatewayReleaseAttemptBO>> attempts = new LinkedHashMap<>();
        final List<OutboxMessage> messages = new ArrayList<>();
        final List<GatewayAuditLogBO> audits = new ArrayList<>();
        final GatewayRouteDraftBO route = GatewayRouteDraftBO.builder().gatewayGroupId(GROUP).routeId("orders-read").operationId("9001")
                .enabled(true).content(Map.of("host", "api.example.com", "httpMethod", "GET", "pathPattern", "/orders", "accessZones", List.of("PUBLIC"))).build();
        final GatewayOperationBO operation = GatewayOperationBO.builder().id("9001").protocol("HTTP").externalAccessible(true)
                .lifecycleStatus("ACTIVE").currentDefinitionId("9101").build();
        final GatewayOperationDefinitionBO definition = GatewayOperationDefinitionBO.builder().id("9101").operationId("9001")
                .definitionVersion(1L).definitionSha256("d".repeat(64)).externalAccessible(true).build();
        final GatewayReleasePublicationCoordinator coordinator;
        final GatewayReleaseService service;
        final PublicationBinding binding = binding();
        final TransactionTemplate tx;

        NativeFixture() {
            var tm = new FixtureTransactionManager();
            tx = new TransactionTemplate(tm);
            var beans = new StaticListableBeanFactory();
            beans.addBean("gatewayHttpRouteBindingResolver", resolver);
            beans.addBean("mcpReleaseContentFactory", mcp);
            when(groups.findByIdAndDeletedFalse(GROUP)).thenReturn(Optional.of(GatewayGroupBO.builder().id(GROUP)
                    .gatewayGroupCode("orders").env("prod").namespace("business").enabled(true).build()));
            when(drafts.findById(GROUP)).thenReturn(Optional.of(new GatewayDraftBO(GROUP, "admin-a", NOW)));
            when(drafts.lockForPublication(GROUP)).thenAnswer(call -> {
                inTransaction();
                return drafts.findById(GROUP);
            });
            when(draftService.get(GROUP)).thenAnswer(call -> new GatewayDraftVO(GROUP, 0L, null, "EDITABLE", "source", List.of(route), List.of(), NOW));
            when(catalog.findOperation("9001")).thenReturn(Optional.of(operation));
            when(catalog.loadDefinitions("9001")).thenAnswer(call -> List.of(definition));
            when(resolver.resolve(OWNER, Long.parseLong(GROUP), "orders-read", "9001")).thenReturn(Optional.of(binding));
            when(releases.find(anyString())).thenAnswer(call -> Optional.ofNullable(heads.get(call.getArgument(0))));
            when(releases.loadNative(anyString())).thenAnswer(call -> Optional.ofNullable(artifacts.get(call.getArgument(0))));
            when(releases.history(GROUP)).thenAnswer(call -> heads.values().stream()
                    .sorted(Comparator.comparing(GatewayReleaseBO::getCreatedAt)
                            .thenComparingLong(head -> Long.parseLong(head.getId())).reversed()).toList());
            when(releases.attempts(anyString())).thenAnswer(call -> attempts.getOrDefault(call.getArgument(0), List.of()));
            doAnswer(call -> {
                inTransaction();
                GatewayReleaseBO head = call.getArgument(0); heads.put(head.getId(), head); artifacts.put(head.getId(), call.getArgument(1));
                attempts.put(head.getId(), new ArrayList<>(List.of(GatewayReleaseAttemptBO.builder().attemptNo(1).status("PENDING").targets(List.of()).build())));
                return null;
            }).when(releases).insertNative(any(), any(), anyInt());
            doAnswer(call -> { heads.get(call.getArgument(0)).setStatus(GatewayReleaseStatus.PUBLISHING); return null; })
                    .when(releases).beginAttempt(anyString(), anyInt(), any());
            when(releases.nextAttempt(anyString(), any())).thenAnswer(call -> {
                var list = attempts.get(call.getArgument(0)); int next = list.size() + 1;
                list.add(GatewayReleaseAttemptBO.builder().attemptNo(next).status("PENDING").targets(List.of()).build()); return next;
            });
            when(releases.completeCurrentNativeAttempt(anyString(), anyInt(), any(), any(Boolean.class), any()))
                    .thenAnswer(call -> {
                        var head = heads.get(call.getArgument(0));
                        if (head.getStatus() != GatewayReleaseStatus.PUBLISHING) return false;
                        head.setStatus(call.getArgument(2));
                        return true;
                    });
            when(exposures.findOwnedRoute(anyLong(), anyLong(), anyString())).thenAnswer(call -> rows.values().stream()
                    .filter(row -> row.getOwnerTenantId().equals(call.getArgument(0)) && row.getGatewayGroupId().equals(call.getArgument(1))
                            && row.getRouteId().equals(call.getArgument(2))).findFirst().map(this::copy));
            when(exposures.findOwned(anyLong(), anyLong())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(1)))
                    .filter(row -> row.getOwnerTenantId().equals(call.getArgument(0))).map(this::copy));
            when(exposures.reserveAll(anyLong(), any())).thenAnswer(call -> {
                inTransaction(); List<GatewayUrlExposureBO> registrations = call.getArgument(1);
                registrations.forEach(row -> rows.put(row.getId(), copy(row).setVersion(0L).setTenantId(0L)));
                return registrations;
            });
            when(exposures.compareAndSetContent(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyString(), anyString(), anyString(), any()))
                    .thenAnswer(call -> {
                        inTransaction(); var row = rows.get(call.getArgument(1));
                        if (!row.getVersion().equals(call.getArgument(2)) || !row.getDesiredGeneration().equals(call.getArgument(3))
                                || row.getDesiredState() != call.getArgument(4)) return false;
                        row.setReleaseId(call.getArgument(5)).setContentSha256(call.getArgument(6)).setCompilerProfileId(call.getArgument(7))
                                .setDesiredGeneration(row.getDesiredGeneration() + 1).setVersion(row.getVersion() + 1).setReconcileStatus("PENDING").setUpdateTime(NOW);
                        return true;
                    });
            when(auditStore.save(any())).thenAnswer(call -> { inTransaction(); GatewayAuditLogBO row = call.getArgument(0); audits.add(row); return row; });
            when(outbox.enqueue(any())).thenAnswer(call -> { inTransaction(); OutboxMessage message = call.getArgument(0); messages.add(message);
                return new OutboxReceipt(message.messageId(), message.idempotencyKey(), true); });
            var converter = mock(GatewayUrlExposureViewConverter.class);
            when(converter.toView(any())).thenAnswer(call -> {
                GatewayUrlExposureBO row = call.getArgument(0);
                return new GatewayUrlExposureVO().setExposureId(row.getId().toString()).setDesiredState(row.getDesiredState())
                        .setDesiredGeneration(row.getDesiredGeneration().toString()).setObservedGeneration(row.getObservedGeneration().toString())
                        .setReconcileStatus(row.getReconcileStatus()).setObservedState(row.getObservedState()).setLastErrorCode(row.getLastErrorCode());
            });
            coordinator = new GatewayReleasePublicationCoordinator(releases, exposures, outbox, converter, json, Clock.fixed(NOW, ZoneOffset.UTC));
            service = new GatewayReleaseService(groups, drafts, draftService, catalog, releases, auditStore, tx, exposures,
                    coordinator, new GatewayHttpRouteCompiler(json), beans.getBeanProvider(BindingResolver.class),
                    beans.getBeanProvider(McpReleaseContentFactory.class), json, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        JsonNode mappingDraft(boolean publicRoute, boolean enabled, String risk, String... permissions) {
            var draft = new GatewayDraftVO(GROUP, 0L, null, "EDITABLE", "source",
                    publicRoute ? List.of(route) : List.of(), List.of(mappingPolicy()), NOW);
            when(draftService.get(GROUP)).thenReturn(draft);
            String toolId = "b".repeat(64);
            var entry = json.createObjectNode().put("toolId", toolId).put("dependencyClosureDigest", "c".repeat(64));
            entry.putObject("sourceOperation").put("operationId", "9001").put("operationKey", "orders.read")
                    .put("definitionDigest", "a".repeat(64));
            entry.putObject("siServer").put("resourceId", "901").put("versionId", "902").put("versionNo", "3")
                    .put("digestSha256", "d".repeat(64)).put("bizCode", "commerce").put("namespaceCode", "orders")
                    .put("envCode", "prod").put("ownerAppCode", "orders").put("resourceKey", "order-tools");
            var tool = entry.putObject("tool").put("toolId", toolId).put("operationId", "9001")
                    .put("serverCode", "order-tools").put("name", "readOrders").put("description", "Read an order")
                    .put("sourceType", "LOCAL_OPERATION").put("operationProtocol", "HTTP").putNull("remoteMountId")
                    .put("inputSchema", "{\"type\":\"object\"}").put("outputSchema", "{\"type\":\"object\"}")
                    .put("riskLevel", risk).put("idempotent", true).put("enabled", enabled);
            tool.putObject("annotations");
            var permissionArray = tool.putArray("requiredPermissions");
            for (String permission : permissions) permissionArray.add(permission);
            JsonNode section = GatewayCiliumReleaseArtifact.mcpPublication(json.createArrayNode().add(entry));
            when(mcp.compileMappingSection(OWNER, draft)).thenReturn(section);
            return section;
        }

        top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO create() {
            return service.create(GROUP, new GatewayReleaseCreateCommandDTO(0L, "native publication"), ADMIN, REQUEST);
        }
        GatewayUrlExposureBO onlyRow() { return rows.values().iterator().next(); }
        GatewayUrlExposureBO copy(GatewayUrlExposureBO row) { return json.convertValue(json.valueToTree(row), GatewayUrlExposureBO.class); }
        GatewayReleaseBO header(String id, GatewayReleaseStatus status) { return new GatewayReleaseBO(id, GROUP, 0L, null, null, status,
                false, null, Map.of(), Map.of(), "archive", NOW, "admin-a", NOW); }
        static void inTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
        static PublicationBinding binding() {
            var domain = new PublicationDomainVO("cluster-main", "public-https-main", "topology-1",
                    List.of(new BindingVO("edge-system", "public-apps", "https-api")), true);
            var backend = new BackendReference("apps-prod", "orders-public", "service-uid", "7", 80, 8080, 100, "HTTP_1_1",
                    new GrantReference("apps-prod", "allow-orders-public", "grant-uid", "4", "yuheng-routes", "orders-public"),
                    "a".repeat(64), Set.of("pod-a:container-a"), true);
            return new PublicationBinding(OWNER, Long.parseLong(GROUP), "orders-read", "9001", "prod", "business", "orders-prod", 1L,
                    domain, new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true), "yuheng-routes",
                    Set.of("api.example.com"), Set.of("GET"), Set.of(), List.of(backend),
                    new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "https://resource.example.com/orders", "1", "b".repeat(64), true, List.of()));
        }

        final class FixtureTransactionManager extends AbstractPlatformTransactionManager {
            Map<String, GatewayReleaseBO> savedHeads;
            Map<String, GatewayCiliumReleaseArtifact> savedArtifacts;
            Map<Long, GatewayUrlExposureBO> savedRows;
            Map<String, List<GatewayReleaseAttemptBO>> savedAttempts;
            int savedAuditCount; int savedMessageCount;
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) {
                savedHeads = new LinkedHashMap<>(); heads.forEach((id, head) -> savedHeads.put(id, json.convertValue(json.valueToTree(head), GatewayReleaseBO.class)));
                savedArtifacts = new LinkedHashMap<>(artifacts); savedRows = new LinkedHashMap<>(); rows.forEach((id, row) -> savedRows.put(id, copy(row)));
                savedAttempts = new LinkedHashMap<>(); attempts.forEach((id, list) -> savedAttempts.put(id, new ArrayList<>(list)));
                savedAuditCount = audits.size(); savedMessageCount = messages.size();
            }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) {
                heads.clear(); heads.putAll(savedHeads); artifacts.clear(); artifacts.putAll(savedArtifacts); rows.clear(); rows.putAll(savedRows);
                attempts.clear(); attempts.putAll(savedAttempts);
                while (audits.size() > savedAuditCount) audits.removeLast(); while (messages.size() > savedMessageCount) messages.removeLast();
            }
        }
    }
}
