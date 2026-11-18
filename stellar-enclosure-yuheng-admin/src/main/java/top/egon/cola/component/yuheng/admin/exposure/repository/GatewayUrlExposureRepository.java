package top.egon.cola.component.yuheng.admin.exposure.repository;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owner-scoped control facts. The caller owns the LOCAL transaction and verifies release/group authorization. */
@Validated
public interface GatewayUrlExposureRepository {

    GatewayUrlExposureBO reserve(@Positive long trustedOwner, @NotNull @Valid GatewayUrlExposureBO registration);

    List<GatewayUrlExposureBO> reserveAll(@Positive long trustedOwner,
                                        @NotNull List<@NotNull @Valid GatewayUrlExposureBO> registrations);

    Optional<GatewayUrlExposureBO> findOwned(@Positive long trustedOwner, @Positive long exposureId);

    Optional<GatewayUrlExposureBO> findOwnedRoute(@Positive long trustedOwner, @Positive long gatewayGroupId,
                                                String routeId);

    /** Reports domain occupancy without returning a conflicting tenant's identity or mutating its state. */
    boolean hasPublishedOverlap(@Positive long trustedOwner, @NotNull GatewayUrlExposureBO proposed);

    boolean compareAndSetDesired(long trustedOwner, long exposureId, long expectedVersion,
            long expectedGeneration, GatewayUrlExposureStateEnum oldState,
            GatewayUrlExposureStateEnum nextState, String reason, Instant at);

    boolean compareAndSetContent(long trustedOwner, long exposureId, long expectedVersion,
            long expectedGeneration, GatewayUrlExposureStateEnum oldState, long releaseId,
            String contentSha256, String compilerProfileId, String reason, Instant at);

    boolean bindRouteUid(long trustedOwner, long exposureId, long expectedVersion, String verifiedUid, Instant at);

    boolean recordObservation(long trustedOwner, long exposureId, long expectedVersion, long evaluatedGeneration,
            String verifiedUid, String observedState, long observedGeneration, String reconcileStatus,
            JsonNode observation, String lastErrorCode, Instant at);

    /** Only a platform-owned, frozen topology resolver supplies these complete publication domains. */
    @FunctionalInterface
    interface PublicationDomainResolver {
        PublicationDomainVO resolve(String clusterId, BindingVO binding);
    }

    record BindingVO(String gatewayNamespace, String gatewayName, String listenerName) {
        public BindingVO {
            bounded(gatewayNamespace, 63, "gatewayNamespace");
            bounded(gatewayName, 253, "gatewayName");
            bounded(listenerName, 253, "listenerName");
        }
    }

    record PublicationDomainVO(String clusterId, String publicationDomainId, String topologyVersion,
                               List<BindingVO> bindings, boolean complete) {
        public PublicationDomainVO {
            bounded(clusterId, 128, "clusterId");
            bounded(publicationDomainId, 128, "publicationDomainId");
            bounded(topologyVersion, 128, "topologyVersion");
            Objects.requireNonNull(bindings, "bindings");
            bindings = bindings.stream().map(binding -> Objects.requireNonNull(binding, "binding"))
                    .distinct().sorted(Comparator.comparing(BindingVO::gatewayNamespace)
                            .thenComparing(BindingVO::gatewayName).thenComparing(BindingVO::listenerName)).toList();
            if (bindings.isEmpty()) { throw new IllegalArgumentException("Publication domain bindings are required"); }
        }
    }

    private static void bounded(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException(name + " must be bounded nonblank ASCII");
        }
    }
}
