package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRuleLifecycleLiveIT {

    @Test
    @EnabledIfSystemProperty(named = "yuheng.live.cilium.quota-enabled", matches = "true")
    void publishedQuotaIsEnforcedByTheBackendForNewCalls() throws Exception {
        var url = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("quota-url"));
        var bearer = GatewayLiveEnvironment.approvedToken("quota-token-file");
        int capacity = Integer.parseInt(GatewayLiveEnvironment.required("quota-capacity"));
        assertThat(capacity).isBetween(1, 50);
        try (var environment = new GatewayLiveEnvironment()) {
            for (int index = 0; index < capacity; index++) {
                assertThat(environment.probe(url, "GET", bearer).status()).isEqualTo(200);
            }
            assertThat(environment.probe(url, "GET", bearer).status()).isEqualTo(429);
        }
    }
}
