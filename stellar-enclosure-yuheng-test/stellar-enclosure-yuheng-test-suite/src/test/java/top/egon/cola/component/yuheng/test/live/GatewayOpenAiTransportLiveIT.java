package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayOpenAiTransportLiveIT {

    @Test
    @EnabledIfSystemProperty(named = "yuheng.live.cilium.websocket-enabled", matches = "true")
    void publishedRealtimeServicePreservesFramesThroughCiliumAndRequiresOAuth() throws Exception {
        URI https = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("websocket-fixture-url"));
        var url = URI.create("wss" + https.toASCIIString().substring("https".length()));
        String bearer = GatewayLiveEnvironment.approvedToken("websocket-token-file");
        byte[] binary = {0x00, (byte) 0xff, 0x31};
        byte[] ping = {0x01, 0x02};

        try (var client = new GatewayWebSocketTestClient()) {
            assertThatThrownBy(() -> client.exchange(url, Map.of(), "fixture", binary, ping, Duration.ofSeconds(10)))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class)
                    .satisfies(failure -> assertThat(((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode()).isEqualTo(401));
            var transcript = client.exchange(url, Map.of("Authorization", "Bearer " + bearer),
                    "openai-realtime", binary, ping, Duration.ofSeconds(10));

            assertThat(transcript.textFrames()).contains("openai-realtime");
            assertThat(transcript.binaryFrames()).anyMatch(frame -> Arrays.equals(frame, binary));
            assertThat(transcript.pongFrames()).anyMatch(frame -> Arrays.equals(frame, ping));
            assertThat(transcript.closeCode()).isEqualTo(1000);
        }
    }
}
