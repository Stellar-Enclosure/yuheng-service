package top.egon.cola.component.yuheng.test.scenario;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayScenarioCatalogTest {

    @Test
    void catalogCoversEveryRequiredLiveCapabilityWithoutMockGateway() {
        assertThat(GatewayScenarioCatalog.all())
                .extracting(GatewayScenarioCatalog.Scenario::code)
                .containsExactly(
                        "starter-report",
                        "service-discovery",
                        "http-route",
                        "rpc-gamma-route",
                        "protocol-adapter",
                        "request-weight",
                        "url-publication",
                        "quota-enforcement",
                        "resource-authorization",
                        "trace-kafka",
                        "failure-recovery",
                        "openai-http-json",
                        "sse-stream",
                        "multipart-upload",
                        "binary-stream",
                        "realtime-websocket",
                        "transport-lifecycle-safety",
                        "rpc-slot-invariant"
                )
                .noneMatch(code -> code.contains("mock"));
        assertThat(GatewayScenarioCatalog.all())
                .allMatch(
                        GatewayScenarioCatalog.Scenario::realProcessRequired
                );
    }
}
