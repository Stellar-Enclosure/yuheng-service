package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayLiveEnvironmentTest {

    @Test
    void northboundFixtureRequiresTheReviewedTlsOriginAndAbsolutePath() {
        assertThat(GatewayLiveEnvironment.northbound("https://public.apps.example.test/api/providers/reviewed").getPath())
                .isEqualTo("/api/providers/reviewed");
        for (String value : List.of("http://public.apps.example.test/api", "https://127.0.0.1:28180/api",
                "https://token@public.apps.example.test/api", "https://public.apps.example.test:18080/api",
                "https://public.apps.example.test/api?redirect=other", "https://public.apps.example.test/api#section",
                "https://public.apps.example.test/api/../other")) {
            assertThatThrownBy(() -> GatewayLiveEnvironment.northbound(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void eastWestFixtureCanOnlyUseTheStableMeshServiceAndExplicitPort() {
        assertThat(GatewayLiveEnvironment.eastWest("http://rpc-consumer.fixtures.svc.cluster.local:18080").getHost())
                .isEqualTo("rpc-consumer.fixtures.svc.cluster.local");
        for (String value : List.of("https://rpc-consumer.fixtures.svc.cluster.local:18080",
                "http://rpc-consumer.fixtures.svc.cluster.local", "http://rpc-consumer.fixtures.svc.cluster.local:19099",
                "http://rpc-consumer-stable.fixtures.svc.cluster.local:18080",
                "http://rpc-consumer-canary.fixtures.svc.cluster.local:18080", "http://10.244.1.7:18080",
                "http://rpc-consumer.fixtures.svc.cluster.local:18080/other",
                "http://rpc-consumer.fixtures.svc.cluster.local:18080?other=1")) {
            assertThatThrownBy(() -> GatewayLiveEnvironment.eastWest(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
