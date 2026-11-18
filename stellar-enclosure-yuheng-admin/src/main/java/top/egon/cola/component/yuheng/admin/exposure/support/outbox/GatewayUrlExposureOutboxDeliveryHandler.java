package top.egon.cola.component.yuheng.admin.exposure.support.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;

/** Dispatches one committed intent wakeup to the data-plane controller. */
@Component
public final class GatewayUrlExposureOutboxDeliveryHandler implements DeliveryHandler {

    private final ObjectReader events;
    private final ObjectProvider<Writer> writers;

    public GatewayUrlExposureOutboxDeliveryHandler(
            @Qualifier("jacksonObjectMapper") ObjectMapper objectMapper,
            ObjectProvider<Writer> writers) {
        this.events = objectMapper.readerFor(ExposureDesiredStateChangedEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.writers = writers;
    }

    @Override
    public String channel() {
        return ExposureDesiredStateChangedEvent.CHANNEL;
    }

    @Override
    public void validateDestination(String destination) {
        if (!ExposureDesiredStateChangedEvent.DESTINATION.equals(destination)) {
            throw new IllegalArgumentException("Unsupported URL exposure Outbox destination");
        }
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context) {
        if (!channel().equals(context.channel())
                || !ExposureDesiredStateChangedEvent.DESTINATION.equals(context.destination())
                || !ExposureDesiredStateChangedEvent.SCHEMA_VERSION.equals(context.schemaVersion())) {
            return invalid();
        }
        ExposureDesiredStateChangedEvent event;
        try {
            event = events.readValue(context.payload());
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return invalid();
        }
        if (event == null || event.eventId() == null || !event.eventId().equals(context.messageId())
                || event.ownerTenantId() <= 0 || event.exposureId() <= 0 || event.desiredGeneration() <= 0
                || event.desiredState() == null || event.occurredAt() == null) {
            return invalid();
        }
        try {
            Writer writer = writers.getIfAvailable();
            if (writer != null) {
                return java.util.Objects.requireNonNull(writer.reconcile(event), "writer result");
            }
        } catch (RuntimeException unavailable) {
            return DeliveryResult.retryableFailure("YUHENG_EXPOSURE_WRITER_UNAVAILABLE", "The URL exposure writer is unavailable");
        }
        return DeliveryResult.retryableFailure("YUHENG_EXPOSURE_WRITER_NOT_CONFIGURED", "The URL exposure writer is not ready");
    }

    private DeliveryResult invalid() {
        return DeliveryResult.permanentFailure("YUHENG_EXPOSURE_EVENT_INVALID", "The exposure intent wakeup is invalid");
    }

    @FunctionalInterface
    public interface Writer {
        /** Reads current committed intent and fences the proposed revision before touching the data plane. */
        DeliveryResult reconcile(ExposureDesiredStateChangedEvent event);
    }
}
