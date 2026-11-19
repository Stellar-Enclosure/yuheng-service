package top.egon.cola.component.yuheng.admin.exposure.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;
import top.egon.cola.component.yuheng.admin.exposure.support.outbox.GatewayUrlExposureOutboxDeliveryHandler;

/** A publisher-only adapter. Wakeup fields never replace current database intent or deployment tenancy. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public final class ExposureDesiredStateDeliveryHandler implements GatewayUrlExposureOutboxDeliveryHandler.Writer {
    @Qualifier("gatewayPersistenceContextComponent") private final GatewayPersistenceContextComponent context;
    @Qualifier("gatewayUrlExposureReconciler") private final GatewayUrlExposureReconciler reconciler;

    @Override
    public DeliveryResult reconcile(ExposureDesiredStateChangedEvent event) {
        if (event == null || event.ownerTenantId() <= 0 || event.exposureId() <= 0
                || !Long.valueOf(event.ownerTenantId()).equals(context.installationTenantId())) {
            return DeliveryResult.permanentFailure("YUHENG_EXPOSURE_EVENT_INVALID", "The exposure intent wakeup is invalid");
        }
        try {
            return context.call(null, () -> reconciler.reconcile(event.ownerTenantId(), event.exposureId()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return DeliveryResult.retryableFailure("YUHENG_EXPOSURE_RECONCILE_UNAVAILABLE", "Current exposure authority is unavailable");
        } catch (Exception unavailable) {
            return DeliveryResult.retryableFailure("YUHENG_EXPOSURE_RECONCILE_UNAVAILABLE", "Current exposure authority is unavailable");
        }
    }
}
