package top.egon.cola.component.yuheng.test.scenario;

import java.util.List;

public final class GatewayScenarioCatalog {

    private GatewayScenarioCatalog() {
    }

    public static List<Scenario> all() {
        return List.of(
                scenario("starter-report", "Starter", "Admin Catalog"),
                scenario("service-discovery", "Kubernetes Service", "EndpointSlices"),
                scenario("http-route", "Cilium Gateway API", "HTTP Resource Server"),
                scenario("rpc-gamma-route", "RPC Consumer", "Approved h2c Service"),
                scenario("protocol-adapter", "Protocol Client", "Independent Adapter"),
                scenario("request-weight", "Cilium Envoy", "Stable and Canary Services"),
                scenario("url-publication", "Yuheng Publisher", "HTTPRoute Generation and Observer"),
                scenario("quota-enforcement", "Access Guard", "Redis Logical Call Receipt"),
                scenario("resource-authorization", "OAuth2 and Source Identity", "Resource Server"),
                scenario("trace-kafka", "Business Application", "Kafka and Admin"),
                scenario("failure-recovery", "Reviewed Fault Fixture", "Observer and Data Plane"),
                scenario(
                        "openai-http-json",
                        "HTTP Client",
                        "Streaming Provider"
                ),
                scenario("sse-stream", "HTTP Client", "SSE Provider"),
                scenario(
                        "multipart-upload",
                        "HTTP Client",
                        "Upload Provider"
                ),
                scenario(
                        "binary-stream",
                        "HTTP Client",
                        "Binary Provider"
                ),
                scenario(
                        "realtime-websocket",
                        "WebSocket Client",
                        "Realtime Provider"
                ),
                scenario(
                        "transport-lifecycle-safety",
                        "Client Cancellation and Timeout",
                        "Upstream and Retry Guard"
                ),
                scenario(
                        "rpc-slot-invariant",
                        "Zero One Two Slots",
                        "RPC Consumer"
                )
        );
    }

    private static Scenario scenario(
            String code,
            String source,
            String target) {
        return new Scenario(code, source, target, true);
    }

    public record Scenario(
            String code,
            String source,
            String target,
            boolean realProcessRequired
    ) {
    }
}
