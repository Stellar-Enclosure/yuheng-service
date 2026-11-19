package top.egon.cola.component.yuheng.admin.release.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftVO;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
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
    void archivedReleaseCannotBeCompletedOrRecoveredAsNativePublication() {
        String releaseId = "810001";
        when(f.releases.loadNative(releaseId)).thenReturn(Optional.empty());

        f.tx.executeWithoutResult(status -> {
            assertThatThrownBy(() -> f.coordinator.completeNative(OWNER, releaseId, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(GatewayReleasePublicationCoordinator.LEGACY_READ_ONLY);
            assertThat(f.coordinator.recoverNative(OWNER, releaseId, 1)).isEmpty();
        });

        verifyNoInteractions(f.exposures, f.outbox);
        verify(f.releases, never()).completeCurrentNativeAttempt(anyString(), anyInt(), any(), any(Boolean.class), any());
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
        verify(f.releases, never()).completeCurrentNativeAttempt(anyString(), anyInt(), any(), any(Boolean.class), any());
    }

    @Test
    void recoveryWakeupsDeduplicateTheCurrentGenerationAcrossObservationWrites() {
        f.create();
        var row = f.onlyRow();
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueRecovery(OWNER, row.getId()));
        var first = f.messages.getLast();
        row.setVersion(8L).setUpdateTime(row.getUpdateTime().plusSeconds(2));
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueRecovery(OWNER, row.getId()));
        assertThat(f.messages.getLast().idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(f.messages.getLast().payload()).isEqualTo(first.payload());
        row.setDesiredState(GatewayUrlExposureStateEnum.WITHDRAWN).setDesiredGeneration(2L);
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueRecovery(OWNER, row.getId()));
        var withdrawn = f.messages.getLast();
        assertThat(withdrawn.idempotencyKey()).isNotEqualTo(first.idempotencyKey());
        assertThat(((ExposureDesiredStateChangedEvent) withdrawn.payload()).desiredState())
                .isEqualTo(GatewayUrlExposureStateEnum.WITHDRAWN);
        row.setReconcileStatus("SYNCED");
        int count = f.messages.size();
        f.tx.executeWithoutResult(status -> f.coordinator.enqueueRecovery(OWNER, row.getId()));
        assertThat(f.messages).hasSize(count);
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
    void persistedMcpOnlyPublicationCompletesWithoutCiliumWakeupsOrRuntimeConsumptionEvidence() {
        var artifact = mcpArtifact(false);
        f.messages.clear();
        clearInvocations(f.exposures, f.outbox, f.releases);

        f.tx.executeWithoutResult(status -> f.coordinator.enqueueCurrent(OWNER, artifact.releaseId(), 1, artifact));
        var observed = f.coordinator.observeNative(OWNER, artifact);

        assertThat(observed.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(observed.partialApplied()).isFalse();
        assertThat(observed.exposureOutcomes()).isEmpty();
        assertThat(observed.clusterTargets()).isEmpty();
        assertThat(f.messages).isEmpty();
        verifyNoInteractions(f.exposures, f.outbox);
        verify(f.releases, never()).completeCurrentNativeAttempt(anyString(), anyInt(), any(), any(Boolean.class), any());
        assertThat(f.heads.get(artifact.releaseId()).getStatus()).isEqualTo(GatewayReleaseStatus.PUBLISHING);

        var completed = f.tx.execute(status -> f.coordinator.completeNative(OWNER, artifact.releaseId(), 1));
        assertThat(completed.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        verify(f.releases).completeCurrentNativeAttempt(artifact.releaseId(), 1, GatewayReleaseStatus.SUCCESS, false, NOW);
        assertThat(f.heads.get(artifact.releaseId()).getStatus()).isEqualTo(GatewayReleaseStatus.SUCCESS);
    }

    @Test
    void mcpPublicationMustMatchThePersistedArtifactExactlyBeforeItCanSucceed() {
        var artifact = mcpArtifact(false);
        f.artifacts.clear();
        assertThatThrownBy(() -> f.coordinator.observeNative(OWNER, artifact))
                .isInstanceOf(IllegalStateException.class).hasMessage("YUHENG_RELEASE_NATIVE_BINDING_INVALID");

        var snapshot = (ObjectNode) artifact.canonicalSnapshot();
        var mappings = artifact.mcpPublication().path("mappings").deepCopy();
        ((ObjectNode) mappings.get(0).path("tool")).put("enabled", false);
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(mappings));
        var changed = new GatewayCiliumReleaseArtifact(snapshot, artifact.activationContent(), artifact.chunkManifest());
        f.artifacts.put(artifact.releaseId(), changed);
        assertThatThrownBy(() -> f.coordinator.observeNative(OWNER, artifact))
                .isInstanceOf(IllegalStateException.class).hasMessage("YUHENG_RELEASE_NATIVE_BINDING_INVALID");
        assertThat(f.coordinator.observeNative(OWNER, changed).status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        verify(f.releases, never()).completeCurrentNativeAttempt(anyString(), anyInt(), any(), any(Boolean.class), any());
    }

    @Test
    void mixedPublicationWaitsForItsRealCiliumRoutesAndPreservesTheirFailure() {
        var artifact = mcpArtifact(true);
        var pending = f.coordinator.observeNative(OWNER, artifact);
        assertThat(pending.status()).isEqualTo(GatewayReleaseStatus.PUBLISHING);
        assertThat(pending.exposureOutcomes()).hasSize(1);
        assertThat(pending.clusterTargets()).hasSize(1).allMatch(target -> !target.converged());

        f.onlyRow().setReconcileStatus("FAILED").setLastErrorCode("DATAPLANE_EVIDENCE_INCOMPLETE");
        assertThat(f.coordinator.observeNative(OWNER, artifact).status()).isEqualTo(GatewayReleaseStatus.FAILED);
        f.onlyRow().setObservedGeneration(1L).setObservedState("OPEN").setReconcileStatus("SYNCED").setLastErrorCode(null);
        var complete = f.coordinator.observeNative(OWNER, artifact);
        assertThat(complete.status()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        assertThat(complete.exposureOutcomes()).hasSize(1).allMatch(outcome -> "APPLIED".equals(outcome.result()));
    }

    @Test
    void mcpOnlyRecoveryHonorsTheCurrentAttemptCasAndDoesNotWriteOutsideATransaction() {
        var artifact = mcpArtifact(false);
        assertThatThrownBy(() -> f.coordinator.completeNative(OWNER, artifact.releaseId(), 1))
                .isInstanceOf(IllegalStateException.class).hasMessage("YUHENG_RELEASE_NATIVE_BINDING_INVALID");
        doReturn(false).when(f.releases).completeCurrentNativeAttempt(artifact.releaseId(), 1, GatewayReleaseStatus.SUCCESS, false, NOW);
        var stale = f.tx.execute(status -> f.coordinator.recoverNative(OWNER, artifact.releaseId(), 1));
        assertThat(stale).isEmpty();
        assertThat(f.heads.get(artifact.releaseId()).getStatus()).isEqualTo(GatewayReleaseStatus.PUBLISHING);

        var current = f.tx.execute(status -> f.coordinator.recoverNative(OWNER, artifact.releaseId(), 2));
        assertThat(current).isPresent().get().extracting(GatewayReleasePublicationCoordinator.NativeOutcome::status)
                .isEqualTo(GatewayReleaseStatus.SUCCESS);
        verify(f.releases).completeCurrentNativeAttempt(artifact.releaseId(), 2, GatewayReleaseStatus.SUCCESS, false, NOW);
        assertThat(f.heads.get(artifact.releaseId()).getStatus()).isEqualTo(GatewayReleaseStatus.SUCCESS);
        f.artifacts.clear();
        var missing = f.tx.execute(status -> f.coordinator.recoverNative(OWNER, artifact.releaseId(), 2));
        assertThat(missing).isEmpty();
    }

    private GatewayCiliumReleaseArtifact mcpArtifact(boolean mixed) {
        var release = f.create();
        var original = f.artifacts.get(release.releaseId());
        var snapshot = (ObjectNode) original.canonicalSnapshot();
        var activation = (ObjectNode) original.activationContent();
        var manifest = (ObjectNode) original.chunkManifest();
        if (!mixed) {
            snapshot.remove("exposurePublication");
            activation.putArray("exposures");
            manifest.putArray("resources");
            manifest.putArray("dependencies");
        }
        var mapping = f.json.createObjectNode().put("toolId", "a".repeat(64)).put("dependencyClosureDigest", "c".repeat(64));
        mapping.putObject("sourceOperation").put("operationId", "9001").put("operationKey", "orders.read")
                .put("definitionDigest", "d".repeat(64));
        mapping.putObject("siServer").put("resourceId", "9101").put("versionId", "9102").put("versionNo", "1")
                .put("digestSha256", "b".repeat(64)).put("bizCode", "business").put("namespaceCode", "orders")
                .put("envCode", "prod").put("resourceKey", "order-tools");
        var tool = mapping.putObject("tool").put("toolId", "a".repeat(64)).put("operationId", "9001")
                .put("serverCode", "order-tools").put("name", "readOrders").put("sourceType", "LOCAL_OPERATION")
                .put("operationProtocol", "HTTP").put("riskLevel", "LOW").put("idempotent", true).put("enabled", true);
        tool.putArray("requiredPermissions").add("orders:read");
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(f.json.createArrayNode().add(mapping)));
        var artifact = new GatewayCiliumReleaseArtifact(snapshot, activation, manifest);
        f.artifacts.put(release.releaseId(), artifact);
        return artifact;
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
