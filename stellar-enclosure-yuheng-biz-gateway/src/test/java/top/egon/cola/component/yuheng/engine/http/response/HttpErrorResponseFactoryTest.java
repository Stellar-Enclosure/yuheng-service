package top.egon.cola.component.yuheng.engine.http.response;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.runtime.traffic.domain.GatewayTrafficRejectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpErrorResponseFactoryTest {
    @Test
    void retainsErrorEnvelopeAndAdditionalSecurityHeaders() {
        var response = HttpErrorResponseFactory.error(401, "DENIED", "trace-1",
                Map.of("WWW-Authenticate", List.of("Bearer")));
        assertEquals(401, response.status());
        assertEquals(List.of("Bearer"), response.headers().get("www-authenticate"));
        assertEquals("{\"success\":false,\"code\":\"DENIED\",\"traceId\":\"trace-1\"}",
                response.body().blockFirst().toString(StandardCharsets.UTF_8));
    }

    @Test
    void roundsRetryAfterUpToWholeSeconds() {
        var rejected = new GatewayTrafficRejectedException("LIMITED", 429, "RESOURCE_EXHAUSTED", 1001);
        var response = HttpErrorResponseFactory.trafficError(rejected, "trace-1");
        assertEquals(429, response.status());
        assertEquals(List.of("2"), response.headers().get("retry-after"));
    }
}
