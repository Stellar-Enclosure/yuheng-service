package top.egon.cola.component.yuheng.admin.release.service;

import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.CompiledExposure;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;

import java.util.List;
import java.util.Objects;

/** Immutable compilation and precise prior control fences, applied only in its accepting LOCAL transaction. */
public record PreparedGatewayRelease(String releaseId, long ownerTenantId, int attemptNo,
                                     GatewayCiliumReleaseArtifact artifact, List<ExposurePlan> exposures) {
    public PreparedGatewayRelease {
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(artifact, "artifact");
        exposures = List.copyOf(exposures);
    }

    public record ExposurePlan(long exposureId, String routeId, boolean newRegistration, long expectedVersion,
                               long expectedGeneration, GatewayUrlExposureStateEnum effectiveState,
                               boolean contentChanged, CompiledExposure compiled) { }
}
