package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.Rbac3McpAuthorizationAdapter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponent;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.NativeFixture;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.*;

class McpServiceScopeAssemblyTest {
    @Test
    void managedBeanUsesExactServiceScopeMetadataAndTheNativePhysicalObservation() {
        try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
            var bindings = new ArrayList<>(List.of(new ServiceScopeBindingVO(RESOURCE, "invoice:pay", Set.of(SCOPE))));
            var properties = properties(bindings);
            bindings.clear();
            var loader = neverUserSnapshots(fixture);
            var authorization = new McpGatewayEngineConfiguration().gatewayMcpAuthorization(loader, fixture.observer,
                    fixture.metrics, fixture.validation.getValidator(), properties, fixture.clock);
            assertInstanceOf(Rbac3McpAuthorizationAdapter.class, authorization);
            fixture.token("service", true); fixture.authenticate(observation, "service");
            var allowed = fixture.request(observation, Set.of("invoice:pay"), 0, 0, 0);
            var denied = fixture.request(observation, Set.of("invoice:write"), 0, 0, 0);
            assertTrue(Mono.from(authorization.authorize(allowed)).contextWrite(Context.of(McpRequestObservationComponent.class, observation)).block().allowed());
            assertFalse(Mono.from(authorization.authorize(denied)).contextWrite(Context.of(McpRequestObservationComponent.class, observation)).block().allowed());
            assertEquals(List.of("SUCCESS", "DENIED"), fixture.events.stream().map(event -> event.result()).toList());
            assertThrows(UnsupportedOperationException.class, () -> properties.serviceScopeBindings().clear());
        }
    }

    @Test
    void missingManagedSecurityDependenciesFailAssemblyInsteadOfInstallingAnAlternativePort() {
        try (var fixture = new NativeFixture()) {
            var settings = properties(List.of());
            var configuration = new McpGatewayEngineConfiguration();
            var loader = neverUserSnapshots(fixture);
            assertThrows(NullPointerException.class, () -> configuration.gatewayMcpAuthorization(null, fixture.observer, fixture.metrics, fixture.validation.getValidator(), settings, fixture.clock));
            assertThrows(NullPointerException.class, () -> configuration.gatewayMcpAuthorization(loader, null, fixture.metrics, fixture.validation.getValidator(), settings, fixture.clock));
            assertThrows(NullPointerException.class, () -> configuration.gatewayMcpAuthorization(loader, fixture.observer, null, fixture.validation.getValidator(), settings, fixture.clock));
            assertThrows(NullPointerException.class, () -> configuration.gatewayMcpAuthorization(loader, fixture.observer, fixture.metrics, null, settings, fixture.clock));
            assertThrows(NullPointerException.class, () -> configuration.gatewayMcpAuthorization(loader, fixture.observer, fixture.metrics, fixture.validation.getValidator(), settings, null));
        }
    }

    @Test
    void authorizationFactoryKeepsItsExactBeanNameAndQualifiedSecurityDependencies() {
        var method = java.util.Arrays.stream(McpGatewayEngineConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("gatewayMcpAuthorization")).findFirst().orElseThrow();
        assertArrayEquals(new String[]{"gatewayMcpAuthorization"}, method.getAnnotation(Bean.class).value());
        assertEquals(McpAuthorizationPort.class, method.getReturnType());
        assertEquals(SingleFlightSnapshotLoader.class, method.getParameterTypes()[0]);
        for (var expected : List.of(java.util.Map.entry(1, "idpObservedVerificationComponent"),
                java.util.Map.entry(2, "idpSecurityMetricsRecorder"), java.util.Map.entry(3, "defaultValidator"), java.util.Map.entry(5, "gatewayClock"))) {
            assertEquals(expected.getValue(), method.getParameters()[expected.getKey()].getAnnotation(Qualifier.class).value());
        }
    }

    private SingleFlightSnapshotLoader neverUserSnapshots(NativeFixture fixture) {
        return new SingleFlightSnapshotLoader((code, identity) -> { throw new AssertionError("SERVICE does not acquire a USER snapshot"); }, "yuheng-mcp", fixture.clock);
    }

    private McpGatewayEngineProperties properties(List<ServiceScopeBindingVO> bindings) {
        return new McpGatewayEngineProperties("mcp-node", new McpGatewayEngineProperties.ListenerProperties(2048),
                new McpGatewayEngineProperties.OutboundProperties(Duration.ofSeconds(1), 2048, 4096), bindings);
    }
}
