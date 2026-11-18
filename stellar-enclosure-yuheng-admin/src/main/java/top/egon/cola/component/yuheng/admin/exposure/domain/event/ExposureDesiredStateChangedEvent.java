package top.egon.cola.component.yuheng.admin.exposure.domain.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.time.Instant;

/** A committed desired-state fact; delivery wakes the single writer, which rereads current authority. */
public record ExposureDesiredStateChangedEvent(
        @NotBlank String eventId,
        @Positive long ownerTenantId,
        @Positive long exposureId,
        @Positive long desiredGeneration,
        @NotNull GatewayUrlExposureStateEnum desiredState,
        @NotNull Instant occurredAt) {

    public static final String CHANNEL = "yuheng-exposure";
    public static final String DESTINATION = "exposure-desired-state-changed";
    public static final String SCHEMA_VERSION = "1";
}
