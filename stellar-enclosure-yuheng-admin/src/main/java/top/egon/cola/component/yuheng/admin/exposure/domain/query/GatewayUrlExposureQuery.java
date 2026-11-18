package top.egon.cola.component.yuheng.admin.exposure.domain.query;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Owned detail query with no caller-supplied tenant, physical binding or resource version. */
public record GatewayUrlExposureQuery(@NotNull @Positive Long exposureId) { }
