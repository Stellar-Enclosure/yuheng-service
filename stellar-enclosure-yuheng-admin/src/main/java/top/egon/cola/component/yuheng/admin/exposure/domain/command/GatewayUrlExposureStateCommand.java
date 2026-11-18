package top.egon.cola.component.yuheng.admin.exposure.domain.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

/** Immutable state command; tenant and actor are obtained independently from trusted server context. */
public record GatewayUrlExposureStateCommand(
        @NotNull @Positive Long exposureId,
        String ifMatch,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._-]+") String idempotencyKey,
        @NotNull GatewayUrlExposureStateEnum state,
        @NotBlank @Size(min = 1, max = 1024) String changeReason) {

    public GatewayUrlExposureStateCommand {
        changeReason = changeReason == null ? null : changeReason.trim();
    }
}
