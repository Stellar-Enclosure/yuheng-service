package top.egon.cola.component.yuheng.admin.rule.service;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayMcpRuleCompilerTest {

    private final McpRuleCompiler compiler = new McpRuleCompiler();

    @Test
    void exposesThePinnedLocalOperationThroughTheIndependentMcpCatalogue() {
        var catalogue = compiler.compile(mcp("orders"), Set.of("orders"));

        assertEquals("orders", catalogue.server("orders").orElseThrow().serverCode());
        assertEquals("orders", catalogue.tool("orders", "orders.get").orElseThrow().operationId());
    }

    @Test
    void rejectsLocalMcpToolThatReferencesAnUnknownOperation() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(mcp("missing-operation"), Set.of("orders"))
        );

        assertTrue(failure.getMessage().contains("references unknown operation"));
    }

    private McpRuleContent mcp(String operationId) {
        return new McpRuleContent(
                List.of(new McpRuntimeServer(
                        "server-1",
                        "orders",
                        "Orders",
                        "Order capabilities",
                        "Use approved order operations.",
                        Set.of(McpProtocolDialect.STABLE_2025_11_25),
                        "https://resource.egon.top/yuheng-mcp",
                        30,
                        true
                )),
                List.of(new McpRuntimeTool(
                        "tool-1",
                        "orders",
                        "orders.get",
                        "Get an order",
                        "LOCAL_OPERATION",
                        operationId,
                        "HTTP",
                        null,
                        "{}",
                        "{}",
                        Map.of(),
                        Set.of("mcp:orders:tool:orders.get:call"),
                        "LOW",
                        true,
                        true
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
