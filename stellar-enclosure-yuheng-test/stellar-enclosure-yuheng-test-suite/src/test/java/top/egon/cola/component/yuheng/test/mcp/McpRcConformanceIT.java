package top.egon.cola.component.yuheng.test.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Draft/RC 2026-07-28 scenario gate, including per-request metadata.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpRcConformanceIT {

    private static final TypeReference<List<Scenario>> SCENARIOS =
            new TypeReference<>() {
            };

    private RemoteMcpFixtureServer server;

    private RcMcpTestClient client;

    @BeforeAll
    void startFixture() {
        server = RemoteMcpFixtureServer.start();
        client = new RcMcpTestClient(server.rcEndpoint(), null);
    }

    @AfterAll
    void stopFixture() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = McpTestHttpClient.CILIUM_ENABLED, matches = "true")
    void publishedDraftMcpUsesTheApprovedCiliumEndpoint() throws Exception {
        try (var live = new RcMcpTestClient(McpTestHttpClient.ciliumEndpoint("rc"), McpTestHttpClient.ciliumToken())) {
            verifyPinnedScenarios(live);
        }
    }

    @Test
    void rcDialectPassesThePinnedScenarioCorpus() throws Exception {
        verifyPinnedScenarios(client);
    }

    private void verifyPinnedScenarios(RcMcpTestClient target) throws Exception {
        for (Scenario scenario : scenarios()) {
            Map<String, Object> response = target.call(
                    scenario.method(),
                    scenario.params()
            );
            assertEquals(
                    scenario.expected(),
                    value(response, scenario.resultPath()),
                    scenario.method()
            );
        }
    }

    private List<Scenario> scenarios() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/mcp/rc-scenarios.json"
        )) {
            assertNotNull(input);
            return new ObjectMapper().readValue(input, SCENARIOS);
        }
    }

    private Object value(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                current = list.get(Integer.parseInt(segment));
            } else {
                return null;
            }
        }
        return current;
    }

    private record Scenario(
            String method,
            Map<String, Object> params,
            String resultPath,
            Object expected
    ) {

        private Scenario {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
