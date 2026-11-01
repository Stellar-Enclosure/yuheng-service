package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationRequest;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate;
import top.egon.cola.component.yuheng.mcp.common.telemetry.McpTelemetry;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.RedisMcpSessionStore;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.Rbac3McpAuthorizationAdapter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.McpRuntimeProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.remote.service.McpRemoteClientPool;
import top.egon.cola.component.yuheng.runtime.operation.service.EngineGatewayOperationInvoker;
import top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier;
import top.egon.cola.component.yuheng.runtime.security.domain.GatewaySecurityException;
import top.egon.cola.component.yuheng.runtime.security.service.GatewaySecurityCapabilityRegistry;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpServiceScopeAssemblyTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");
    @TempDir Path artifacts;

    @Test
    void managedFactoryPassesExactServiceScopeBindingsAndClock() {
        var fixture = new Fixture();
        try (var validation = Validation.buildDefaultValidatorFactory();
             var adapters = mockStatic(Rbac3McpAuthorizationAdapter.class, CALLS_REAL_METHODS);
             var gates = mockConstruction(McpSecurityGate.class);
             var handlers = mockConstruction(McpEngineHttpHandler.class)) {
            var validator = validation.getValidator();
            var handler = fixture.invoke(validator, "none");
            assertEquals(1, handlers.constructed().size()); assertSame(handlers.constructed().getFirst(), handler);
            assertEquals(1, gates.constructed().size());
            adapters.verify(() -> Rbac3McpAuthorizationAdapter.createConfigured(same(fixture.snapshots),
                    same(fixture.observer), same(fixture.metrics), same(validator), same(fixture.settings.serviceScopeBindings()), same(fixture.clock)));
            verifyNoInteractions(fixture.snapshots, fixture.observer);
        }
    }

    @Test
    void missingManagedSecurityDependenciesKeepTheUnavailablePort() {
        for (String missing : List.of("snapshots", "observer", "metrics", "validator")) {
            var fixture = new Fixture(); var ports = new ArrayList<McpAuthorizationPort>();
            try (var validation = Validation.buildDefaultValidatorFactory();
                 var adapters = mockStatic(Rbac3McpAuthorizationAdapter.class, CALLS_REAL_METHODS);
                 var gates = mockConstruction(McpSecurityGate.class,
                         (gate, construction) -> ports.add((McpAuthorizationPort) construction.arguments().getFirst()));
                 var handlers = mockConstruction(McpEngineHttpHandler.class)) {
                assertNotNull(fixture.invoke(validation.getValidator(), missing));
                assertEquals(1, ports.size());
                var request = new McpAuthorizationRequest("https://idp.example.test/20", "machine", "20", "machine", "token",
                        "https://mcp.example.test/orders", NOW, NOW.plusSeconds(30), Set.of("mcp:orders:tool:lookup:call"), 0, 0, 0);
                var failure = assertThrows(GatewaySecurityException.class, () -> Mono.from(ports.getFirst().authorize(request)).block());
                assertEquals(503, failure.httpStatus()); adapters.verifyNoInteractions();
                verifyNoInteractions(fixture.snapshots, fixture.observer);
            }
        }
    }

    private final class Fixture {
        final SingleFlightSnapshotLoader snapshots = mock(SingleFlightSnapshotLoader.class);
        final IdpObservedVerificationComponent observer = mock(IdpObservedVerificationComponent.class);
        final IdpSecurityMetricsRecorder metrics = IdpSecurityMetricsRecorder.NOOP;
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final List<ServiceScopeBindingVO> bindings = List.of(new ServiceScopeBindingVO(
                URI.create("https://mcp.example.test/orders"), "mcp:orders:tool:lookup:call", Set.of("orders:read")));
        final McpGatewayEngineProperties settings = properties();

        @SuppressWarnings("unchecked")
        McpEngineHttpHandler invoke(Validator validator, String missing) {
            GatewayRuleActivationApplier<McpGatewayCompiledRulesDTO> activation = mock(GatewayRuleActivationApplier.class);
            var runtime = new McpRuntimeProperties(); runtime.setArtifactRoot(artifacts.toString());
            return new McpGatewayEngineConfiguration().gatewayMcpHttpHandler(activation,
                    mock(GatewaySecurityCapabilityRegistry.class), mock(EngineGatewayOperationInvoker.class), mock(RedisMcpSessionStore.class),
                    provider(null), provider(missing.equals("snapshots") ? null : snapshots),
                    provider(missing.equals("observer") ? null : observer), provider(missing.equals("metrics") ? null : metrics),
                    provider(missing.equals("validator") ? null : validator), provider(null), provider(null),
                    mock(McpRemoteClientPool.class), mock(McpTelemetry.class), new ObjectMapper(), clock, settings, runtime);
        }

        McpGatewayEngineProperties properties() {
            var tls = new McpGatewayEngineProperties.TlsProperties(false, true, null, null, null, false);
            var listener = new McpGatewayEngineProperties.ListenerProperties(false, "127.0.0.1", 0, 2048, Duration.ofSeconds(1), tls);
            var outbound = new McpGatewayEngineProperties.OutboundProperties(2, 2, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    2048, 4096, Duration.ofSeconds(1), tls);
            var health = new McpGatewayEngineProperties.ActiveHealthProperties(false, Duration.ofSeconds(10), 0,
                    Duration.ofSeconds(1), 1, 1, 1, "GET", "/health", List.of(200), "", true);
            return new McpGatewayEngineProperties("orders", "test", "default", "mcp-node", "mcp-instance",
                    artifacts.toString(), listener, 0, outbound, health, bindings);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(value); return provider;
    }
}
