package top.egon.cola.component.yuheng.admin.exposure.support.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
@Component("gatewayUrlExposureOutboxDeliveryHandler")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public final class GatewayUrlExposureOutboxDeliveryHandler implements DeliveryHandler {

    private static final int MAX_EVENT_BYTES = 16 * 1024;
    @Qualifier("gatewayUrlExposureEventReader") private final ObjectReader events;
    @Qualifier("gatewayUrlExposureWriter") private final ObjectProvider<Writer> writers;

    public static ObjectReader eventReader(ObjectMapper objectMapper) {
        ObjectMapper strict = objectMapper.copy();
        strict.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(19).build());
        strict.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        return strict.readerFor(ExposureDesiredStateChangedEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
        if (context == null || !"application/json".equals(context.contentType())
                || context.payload() == null || context.payload().length() > MAX_EVENT_BYTES
                || context.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EVENT_BYTES
                || !channel().equals(context.channel())
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
                || event.eventId().length() > 128 || event.eventId().codePoints().anyMatch(Character::isISOControl)
                || !event.eventId().equals(context.headers().get("event-id"))
                || !Long.toString(event.ownerTenantId()).equals(context.headers().get("tenant-id"))
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
