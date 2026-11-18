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

    private GatewayUrlExposureOutboxDeliveryHandler handler(StaticListableBeanFactory beans) {
        return new GatewayUrlExposureOutboxDeliveryHandler(json,
                beans.getBeanProvider(GatewayUrlExposureOutboxDeliveryHandler.Writer.class));
    }

    private DeliveryContext context(String id, String schema, String payload) {
        return new DeliveryContext(id, ExposureDesiredStateChangedEvent.CHANNEL, ExposureDesiredStateChangedEvent.DESTINATION,
                payload, "application/json", schema, Map.of(), "trace-verified", 1, 4, NOW.plusSeconds(10));
    }
}
