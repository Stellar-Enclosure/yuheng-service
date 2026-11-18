package top.egon.cola.component.yuheng.admin.exposure.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.command.GatewayUrlExposureStateCommand;
import top.egon.cola.component.yuheng.admin.exposure.domain.query.GatewayUrlExposureQuery;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;

/** Local control command and primary-store query; neither operation writes Kubernetes resources. */
@Validated
public interface GatewayUrlExposureService {

    CommandResult putState(@NotNull AdminActor actor, @NotNull @Valid GatewayUrlExposureStateCommand command);

    QueryResult get(@NotNull AdminActor actor, @NotNull @Valid GatewayUrlExposureQuery query);

    record CommandResult(int status, GatewayUrlExposureVO view, String etag) { }

    record QueryResult(GatewayUrlExposureVO view, String etag) { }

    /** Trusted current binding checks for explicit OPEN; an absent implementation always rejects reopening. */
    @FunctionalInterface
    interface ReopenValidator {
        void validate(long trustedOwner, GatewayUrlExposureBO current);
    }
}
