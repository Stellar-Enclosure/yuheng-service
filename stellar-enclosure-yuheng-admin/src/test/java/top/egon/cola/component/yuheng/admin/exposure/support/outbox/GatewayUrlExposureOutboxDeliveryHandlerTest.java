package top.egon.cola.component.yuheng.admin.exposure.support.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayUrlExposureOutboxDeliveryHandlerTest {

    private static final String MESSAGE_ID = "f8705816-4cba-44de-90c2-0c2c9613a2d0";
    private static final Instant NOW = Instant.parse("2026-11-18T20:00:00Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExposureDesiredStateChangedEvent event = new ExposureDesiredStateChangedEvent(
            MESSAGE_ID, 2001, 810001, 8, GatewayUrlExposureStateEnum.CLOSED, NOW);

    @Test
    void missingWriterLeavesTheCommittedWakeupRetryable() throws Exception {
        var handler = handler(new StaticListableBeanFactory());
        assertThat(handler.channel()).isEqualTo(ExposureDesiredStateChangedEvent.CHANNEL);
        handler.validateDestination(ExposureDesiredStateChangedEvent.DESTINATION);
        assertThatThrownBy(() -> handler.validateDestination("unrelated-destination"))
                .isInstanceOf(IllegalArgumentException.class);
        var result = handler.deliver(context(MESSAGE_ID, "1", json.writeValueAsString(event)));
        assertThat(result.code()).isEqualTo("YUHENG_EXPOSURE_WRITER_NOT_CONFIGURED");
    }

    @Test
    void handsTheExactEventToTheWriterAndPropagatesItsOutcome() throws Exception {
        var observed = new AtomicReference<ExposureDesiredStateChangedEvent>();
        var beans = new StaticListableBeanFactory();
        var writerResult = DeliveryResult.retryableFailure("WAITING_FOR_EVIDENCE", "current generation has not converged");
        beans.addBean("writer", (GatewayUrlExposureOutboxDeliveryHandler.Writer) fact -> {
            observed.set(fact);
            return writerResult;
        });
        assertThat(handler(beans).deliver(context(MESSAGE_ID, "1", json.writeValueAsString(event))))
                .isEqualTo(writerResult);
        assertThat(observed.get()).isEqualTo(event);
    }

    @Test
    void rejectsWrongSchemaOrMessageIdentityAndIncompleteEventBeforeWriter() throws Exception {
        var beans = new StaticListableBeanFactory();
        beans.addBean("writer", (GatewayUrlExposureOutboxDeliveryHandler.Writer) fact -> {
            throw new AssertionError("Invalid events must not reach the writer");
        });
        var handler = handler(beans);
        String payload = json.writeValueAsString(event);
        assertThat(handler.deliver(context(MESSAGE_ID, "2", payload)).code()).isEqualTo("YUHENG_EXPOSURE_EVENT_INVALID");
        assertThat(handler.deliver(context("different-id", "1", payload)).code()).isEqualTo("YUHENG_EXPOSURE_EVENT_INVALID");
        var invalid = json.valueToTree(event);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("ownerTenantId", 0);
        assertThat(handler.deliver(context(MESSAGE_ID, "1", json.writeValueAsString(invalid))).code())
                .isEqualTo("YUHENG_EXPOSURE_EVENT_INVALID");
    }

    @Test
    void refusesUnboundContentTypeTenantAndEventHeadersBeforeWriter() throws Exception {
        var beans = new StaticListableBeanFactory();
        beans.addBean("writer", (GatewayUrlExposureOutboxDeliveryHandler.Writer) ignored -> {
            throw new AssertionError("An unbound envelope must not invoke a writer");
        });
        String payload = json.writeValueAsString(event);
        var handler = handler(beans);
        for (var invalid : java.util.List.of(
                envelope(payload, "text/plain", Map.of("tenant-id", "2001", "event-id", MESSAGE_ID)),
                envelope(payload, "application/json", Map.of()),
                envelope(payload, "application/json", Map.of("tenant-id", "2002", "event-id", MESSAGE_ID)),
                envelope(payload, "application/json", Map.of("tenant-id", "02001", "event-id", MESSAGE_ID)),
                envelope(payload, "application/json", Map.of("tenant-id", "2001", "event-id", "another-event")))) {
            assertThat(handler.deliver(invalid).code()).isEqualTo("YUHENG_EXPOSURE_EVENT_INVALID");
        }
    }

    @Test
    void refusesCoercedDuplicateTrailingAndOversizedJsonWithoutLeakingPayload() throws Exception {
        var beans = new StaticListableBeanFactory();
        beans.addBean("writer", (GatewayUrlExposureOutboxDeliveryHandler.Writer) ignored -> {
            throw new AssertionError("A malformed payload must not invoke a writer");
        });
        String payload = json.writeValueAsString(event);
        for (String invalid : java.util.List.of(
                payload.replace("\"ownerTenantId\":2001", "\"ownerTenantId\":\"2001\""),
                payload.replace("\"ownerTenantId\":2001", "\"ownerTenantId\":2001,\"ownerTenantId\":2001"),
                payload + " {}", " ".repeat(70_000) + payload, payload.replace("\"desiredState\":\"CLOSED\"", "\"desiredState\":0"),
                payload.replace("\"exposureId\":810001", "\"exposureId\":null"),
                payload.substring(0, payload.length() - 1) + ",\"untrusted-secret-marker\":\"" + "s".repeat(70_000) + "\"}")) {
            var result = handler(beans).deliver(context(MESSAGE_ID, "1", invalid));
            assertThat(result.code()).isEqualTo("YUHENG_EXPOSURE_EVENT_INVALID");
            assertThat(result.message()).doesNotContain("untrusted-secret-marker");
        }
    }

    private DeliveryContext envelope(String payload, String contentType, Map<String, String> headers) {
        return new DeliveryContext(MESSAGE_ID, ExposureDesiredStateChangedEvent.CHANNEL, ExposureDesiredStateChangedEvent.DESTINATION,
                payload, contentType, "1", headers, "trace-verified", 1, 4, NOW.plusSeconds(10));
    }

    private GatewayUrlExposureOutboxDeliveryHandler handler(StaticListableBeanFactory beans) {
        return new GatewayUrlExposureOutboxDeliveryHandler(GatewayUrlExposureOutboxDeliveryHandler.eventReader(json),
                beans.getBeanProvider(GatewayUrlExposureOutboxDeliveryHandler.Writer.class));
    }

    private DeliveryContext context(String id, String schema, String payload) {
        return new DeliveryContext(id, ExposureDesiredStateChangedEvent.CHANNEL, ExposureDesiredStateChangedEvent.DESTINATION,
                payload, "application/json", schema, Map.of("tenant-id", "2001", "event-id", id), "trace-verified", 1, 4, NOW.plusSeconds(10));
    }
}
