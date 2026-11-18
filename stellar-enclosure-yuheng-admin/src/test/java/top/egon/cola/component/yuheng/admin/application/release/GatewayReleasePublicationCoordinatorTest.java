package top.egon.cola.component.yuheng.admin.release.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.tianshu.api.client.DdcManagementClient;
import top.egon.cola.component.yuheng.admin.config.properties.GatewayAdminDdcProperties;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleasePublicationRepository;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftVO;
import top.egon.cola.component.yuheng.admin.rule.domain.vo.CompiledGatewayRelease;
import top.egon.cola.component.yuheng.admin.rule.service.GatewayDdcRulePublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseServiceTest.*;

class GatewayReleasePublicationCoordinatorTest {
    NativeFixture f;
    @BeforeAll static void ids() { SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5)); }
    @BeforeEach void setup() { MDC.put("tenantId", Long.toString(OWNER)); f = new NativeFixture(); }
    @AfterEach void clear() { MDC.clear(); }

    @Test
    void legacyArtifactCannotExecuteAnOldDdcPublicationPath() {
        var journal = mock(GatewayReleasePublicationRepository.class);
        var releases = mock(GatewayReleaseRepository.class);
        var client = mock(DdcManagementClient.class);
        var publisher = mock(GatewayDdcRulePublisher.class);
        var legacy = new GatewayReleasePublicationCoordinator(journal, releases, client, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), new GatewayAdminDdcProperties());
        assertThatThrownBy(() -> legacy.execute("810001", 1, mock(CompiledGatewayRelease.class), "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessage(GatewayReleasePublicationCoordinator.LEGACY_READ_ONLY);
        assertThatThrownBy(() -> legacy.resume("810001", 1))
                .isInstanceOf(IllegalStateException.class).hasMessage(GatewayReleasePublicationCoordinator.LEGACY_READ_ONLY);
        verifyNoInteractions(journal, releases, client, publisher);
    }

    @Test
    void duplicateWakeOfAnExactCurrentSnapshotHasIdenticalKeyIdPayloadAndHeaders() {
        var release = f.create();
        var first = f.messages.getFirst();
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueCurrent(OWNER, release.releaseId(), 1, f.artifacts.get(release.releaseId())));
        var repeated = f.messages.getLast();
        assertThat(repeated.messageId()).isEqualTo(first.messageId());
        assertThat(repeated.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(repeated.headers()).isEqualTo(first.headers());
        assertThat(repeated.payload()).isEqualTo(first.payload());
        assertThat(repeated.traceId()).isNull();
        assertThat(repeated.schemaVersion()).isEqualTo("1");
    }

    @Test
    void observedRowVersionCreatesANewWakeWithoutAdvancingDesiredGeneration() {
        var release = f.create();
        String firstKey = f.messages.getFirst().idempotencyKey();
        f.onlyRow().setVersion(1L);
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueCurrent(OWNER, release.releaseId(), 1, f.artifacts.get(release.releaseId())));
        assertThat(f.messages.getLast().idempotencyKey()).isNotEqualTo(firstKey);
        assertThat(((ExposureDesiredStateChangedEvent) f.messages.getLast().payload()).desiredGeneration()).isEqualTo(1L);
    }

    @Test
    void staleArtifactWakeCarriesCurrentTerminalFactsAndNeverItsRequestedOpen() {
        var release = f.create();
        f.onlyRow().setDesiredState(GatewayUrlExposureStateEnum.WITHDRAWN).setDesiredGeneration(4L).setVersion(8L);
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueCurrent(OWNER, release.releaseId(), 1, f.artifacts.get(release.releaseId())));
        var event = (ExposureDesiredStateChangedEvent) f.messages.getLast().payload();
        assertThat(event.desiredState()).isEqualTo(GatewayUrlExposureStateEnum.WITHDRAWN);
        assertThat(event.desiredGeneration()).isEqualTo(4L);
        var result = f.coordinator.observeNative(OWNER, f.artifacts.get(release.releaseId()));
        assertThat(result.status()).isEqualTo(GatewayReleaseStatus.SUPERSEDED);
        assertThat(result.exposureOutcomes().getFirst().result()).isEqualTo("STALE");
        assertThat(f.onlyRow().getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.WITHDRAWN);
    }

    @Test
    void pendingIntentAndTechnicalDeliveryNeverCompleteTheBusinessAttempt() {
        var release = f.create();
        assertThat(f.coordinator.observeNative(OWNER, f.artifacts.get(release.releaseId())).status()).isEqualTo(GatewayReleaseStatus.PUBLISHING);
        verify(f.releases, never()).completeAttempt(anyString(), anyInt(), any(), any(Boolean.class), any(), any(), any(), any(), any());
    }

    @Test
    void allCurrentNativeTargetsMustConvergeAndPartialFailureRemainsVisible() {
        var original = f.binding;
        var second = GatewayRouteDraftBO.builder().gatewayGroupId(GROUP).routeId("orders-other").operationId("9001").enabled(true)
                .content(Map.of("host", "api.example.com", "httpMethod", "GET", "pathPattern", "/orders-other", "accessZones", List.of("PUBLIC"))).build();
        when(f.draftService.get(GROUP)).thenReturn(new GatewayDraftVO(GROUP, 0L, null, "EDITABLE", "two targets", List.of(f.route, second), List.of(), NOW));
        when(f.resolver.resolve(OWNER, Long.parseLong(GROUP), "orders-other", "9001")).thenReturn(Optional.of(new PublicationBinding(
                original.ownerTenantId(), original.gatewayGroupId(), "orders-other", original.operationId(), original.environment(), original.logicalNamespace(),
                original.bindingId(), original.bindingVersion(), original.publicationDomain(), original.gateway(), original.routeNamespace(), original.approvedHostnames(),
                original.approvedMethods(), original.approvedPathPrefixes(), original.backends(), original.authorization())));
        var release = f.create();
        var rows = f.rows.values().stream().toList();
        rows.getFirst().setObservedGeneration(1L).setObservedState("OPEN").setReconcileStatus("SYNCED");
        var partial = f.coordinator.observeNative(OWNER, f.artifacts.get(release.releaseId()));
        assertThat(partial.status()).isEqualTo(GatewayReleaseStatus.PUBLISHING);
        assertThat(partial.partialApplied()).isTrue();
        rows.getLast().setReconcileStatus("FAILED").setLastErrorCode("DATAPLANE_EVIDENCE_INCOMPLETE");
        var failed = f.coordinator.observeNative(OWNER, f.artifacts.get(release.releaseId()));
        assertThat(failed.status()).isEqualTo(GatewayReleaseStatus.FAILED);
        assertThat(failed.partialApplied()).isTrue();
        assertThat(failed.exposureOutcomes()).extracting(value -> value.result()).contains("APPLIED", "FAILED");
        rows.getLast().setObservedGeneration(1L).setObservedState("OPEN").setReconcileStatus("SYNCED").setLastErrorCode(null);
        f.tx.executeWithoutResult(status -> f.coordinator.completeNative(OWNER, release.releaseId(), 1));
        assertThat(f.heads.get(release.releaseId()).getStatus()).isEqualTo(GatewayReleaseStatus.SUCCESS);
    }

    @Test
    void wrongTenantAndMissingWritableTransactionCannotEnqueueNativeWork() {
        var release = f.create();
        assertThatThrownBy(() -> f.coordinator.enqueueCurrent(OWNER, release.releaseId(), 1, f.artifacts.get(release.releaseId())))
                .isInstanceOf(IllegalStateException.class);
        MDC.put("tenantId", "202");
        assertThatThrownBy(() -> f.coordinator.observeNative(202L, f.artifacts.get(release.releaseId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.messages).hasSize(1);
    }
}
