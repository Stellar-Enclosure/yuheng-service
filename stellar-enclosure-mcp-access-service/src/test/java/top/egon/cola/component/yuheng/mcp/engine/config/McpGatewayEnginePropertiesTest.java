package top.egon.cola.component.yuheng.mcp.engine.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class McpGatewayEnginePropertiesTest {

    private static final String PREFIX = "egon.cola.component.yuheng.mcp-engine.runtime.";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsNativeRuntimeWithoutApplicationTlsAlongsideSiblingSettings() {
        runner.withPropertyValues(PREFIX + "node-id=pod-1",
                        "egon.cola.component.yuheng.mcp-engine.publication.caller-app-code=mcp-access",
                        "egon.cola.component.yuheng.mcp-engine.mvc-async.concurrent-streams=2")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(McpGatewayEngineProperties.class);
                    assertEquals("pod-1", properties.nodeId());
                    assertEquals(2097152, properties.listener().maximumRequestBytes());
                });
    }

    @Test
    void bindsImmutableDefaultsAndDoesNotRemapProtocolSettings() {
        runner.withPropertyValues(validValues())
                .withPropertyValues("egon.cola.component.yuheng.engine.mcp.maximum-request-bytes=1024")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var settings = context.getBean(McpGatewayEngineProperties.class);
                    assertEquals("mcp-node-1", settings.nodeId());
                    assertEquals(2_097_152, settings.listener().maximumRequestBytes());
                    assertEquals(Duration.ofSeconds(5), settings.outbound().requestTimeout());
                    assertEquals(4_194_304, settings.outbound().maximumResponseBytes());
                    assertEquals(List.of(), settings.serviceScopeBindings());
                });
    }

    @Test
    void rejectsMissingOrInvalidNodeAndLimits() {
        runner.run(context -> assertNotNull(context.getStartupFailure()));
        for (String invalid : List.of(
                "node-id=", "listener.maximum-request-bytes=0", "listener.maximum-request-bytes=2147483648",
                "outbound.request-timeout=-1s", "outbound.maximum-request-bytes=0",
                "outbound.maximum-response-bytes=2147483648")) {
            runner.withPropertyValues(validValues()).withPropertyValues(PREFIX + invalid)
                    .run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }

    @Test
    void runtimeDoesNotAcceptRetiredListenerAndProxyOptions() {
        for (String retired : List.of("listener.tls.enabled=true", "outbound.rpc-tls.enabled=true",
                "outbound.http-tls.enabled=true", "active-health.enabled=true", "instance-id=obsolete")) {
            runner.withPropertyValues(validValues()).withPropertyValues(PREFIX + retired)
                    .run(context -> assertNotNull(context.getStartupFailure(), retired));
        }
    }

    @Test
    void defaultsOptionalServiceScopeBindings() {
        assertEquals(List.of(), propertiesWithBindings(null).serviceScopeBindings());
        assertEquals(propertiesWithBindings(null), propertiesWithBindings(List.of()));
    }

    @Test
    void copiesServiceScopeBindingsAndScopesBeforeCallerMutation() {
        var scopes = new HashSet<>(Set.of("orders:read"));
        var binding = new ServiceScopeBindingVO(URI.create("https://mcp.example.test/orders"),
                "orders:read", scopes);
        var bindings = new ArrayList<>(List.of(binding));
        var properties = propertiesWithBindings(bindings);
        scopes.clear();
        bindings.clear();
        assertEquals(Set.of("orders:read"), binding.scopes());
        assertEquals(List.of(binding), properties.serviceScopeBindings());
        assertThrows(UnsupportedOperationException.class, () -> binding.scopes().add("orders:write"));
        assertThrows(UnsupportedOperationException.class,
                () -> properties.serviceScopeBindings().add(binding));
    }

    @Test
    void boundsServiceScopeBindingsAndRejectsNullOrDuplicateKeys() {
        var maximum = IntStream.range(0, 256).mapToObj(index -> new ServiceScopeBindingVO(
                URI.create("https://mcp.example.test/orders"), "orders:read:" + index,
                Set.of("orders:read"))).toList();
        assertEquals(256, propertiesWithBindings(maximum).serviceScopeBindings().size());
        var excessive = new ArrayList<>(maximum);
        excessive.add(new ServiceScopeBindingVO(URI.create("https://mcp.example.test/orders"),
                "orders:read:256", Set.of("orders:read")));
        assertThrows(IllegalArgumentException.class, () -> propertiesWithBindings(excessive));
        var withNull = new ArrayList<>(maximum);
        withNull.set(0, null);
        assertThrows(IllegalArgumentException.class, () -> propertiesWithBindings(withNull));
        var binding = maximum.getFirst();
        var sameKeyDifferentScopes = new ServiceScopeBindingVO(binding.resourceUri(), binding.permission(),
                Set.of("orders:write"));
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithBindings(List.of(binding, binding)));
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithBindings(List.of(binding, sameKeyDifferentScopes)));
        assertEquals(2, propertiesWithBindings(List.of(binding, new ServiceScopeBindingVO(
                URI.create("https://other.example.test/orders"), binding.permission(), binding.scopes())))
                .serviceScopeBindings().size());
    }

    @Test
    void requiresExactNormalizedAsciiHttpsServiceResources() {
        var resource = URI.create("https://mcp.example.test/orders");
        assertEquals(resource, new ServiceScopeBindingVO(resource, "orders:read", Set.of("orders:read"))
                .resourceUri());
        String resourcePrefix = "https://mcp.example.test/";
        var maximum = URI.create(resourcePrefix + "a".repeat(2048 - resourcePrefix.length()));
        assertEquals(maximum, new ServiceScopeBindingVO(maximum, "orders:read", Set.of("orders:read"))
                .resourceUri());
        for (String invalid : List.of("http://mcp.example.test/orders", "/orders", "https:orders",
                "https:///orders", "https://user@mcp.example.test/orders", "https://mcp.example.test/orders?q=1",
                "https://mcp.example.test/orders#fragment", "https://mcp.example.test/a/../orders",
                "https://mcp.example.test/a/./orders", "https://mcp.example.test/订单",
                resourcePrefix + "a".repeat(2049 - resourcePrefix.length()))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServiceScopeBindingVO(URI.create(invalid), "orders:read", Set.of("orders:read")),
                    invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceScopeBindingVO(null, "orders:read", Set.of("orders:read")));
    }

    @Test
    void requiresExactGraphicAsciiServicePermissionsWithoutAliases() {
        var resource = URI.create("https://mcp.example.test/orders");
        String maximum = "!" + "p".repeat(511);
        assertEquals(maximum, new ServiceScopeBindingVO(resource, maximum, Set.of("orders:read")).permission());
        for (String invalid : List.of("", " ", " orders:read", "orders:read ", "orders read",
                "\torders:read", "orders:read\n", "orders:\u007fread", "订单:read", "p".repeat(513))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServiceScopeBindingVO(resource, invalid, Set.of("orders:read")), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceScopeBindingVO(resource, null, Set.of("orders:read")));
    }

    @Test
    void boundsBusinessScopesAndRejectsIdentityScopesOrInvalidTokens() {
        var resource = URI.create("https://mcp.example.test/orders");
        var maximum = IntStream.range(0, 64).mapToObj(index -> "orders:read:" + index)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(maximum, new ServiceScopeBindingVO(resource, "orders:read", maximum).scopes());
        assertEquals(Set.of("a".repeat(128)),
                new ServiceScopeBindingVO(resource, "orders:read", Set.of("a".repeat(128))).scopes());
        assertEquals(Set.of("orders._:-READ"),
                new ServiceScopeBindingVO(resource, "orders:read", Set.of("orders._:-READ")).scopes());
        for (String invalid : List.of("", "orders read", " orders:read", "orders:read\n", "订单:read",
                "a".repeat(129), "orders/read", "*", "openid", "profile", "email", "phone", "address",
                "offline_access")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServiceScopeBindingVO(resource, "orders:read", Set.of(invalid)), invalid);
        }
        var excessive = new HashSet<>(maximum);
        excessive.add("orders:read:64");
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceScopeBindingVO(resource, "orders:read", excessive));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceScopeBindingVO(resource, "orders:read", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceScopeBindingVO(resource, "orders:read", null));
        var withNull = new HashSet<>(Set.of("orders:read"));
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceScopeBindingVO(resource, "orders:read", withNull));
    }

    @Test
    void bindsExactServiceScopeTranslationsThroughTheCanonicalConstructor() {
        runner.withPropertyValues(validValues()).withPropertyValues(validBindingValues())
                .withPropertyValues(PREFIX + "listener.maximum-request-bytes=4096")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var settings = context.getBean(McpGatewayEngineProperties.class);
                    assertEquals(List.of(
                            new ServiceScopeBindingVO(URI.create("https://mcp.example.test/orders"),
                                    "orders:read", Set.of("orders:read", "orders:inspect")),
                            new ServiceScopeBindingVO(URI.create("https://mcp.example.test/orders"),
                                    "orders:write", Set.of("orders:write"))), settings.serviceScopeBindings());
                    assertEquals(4096, settings.listener().maximumRequestBytes());
                });
    }

    @Test
    void rejectsUnknownFieldsInTheFirstServiceScopeBinding() {
        assertUnknownServiceScopeBindingFieldRejected(0);
    }

    @Test
    void rejectsUnknownFieldsInTheSecondServiceScopeBinding() {
        assertUnknownServiceScopeBindingFieldRejected(1);
    }

    @Test
    void rejectsUnknownServiceScopeEnableFlagsWithNoBindings() {
        runner.withPropertyValues(validValues())
                .withPropertyValues(PREFIX + "service-scope-bindings-enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private void assertUnknownServiceScopeBindingFieldRejected(int index) {
        runner.withPropertyValues(validValues()).withPropertyValues(validBindingValues())
                .withPropertyValues(PREFIX + "service-scope-bindings[" + index + "].operator-override=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private String[] validBindingValues() {
        return new String[]{
                PREFIX + "service-scope-bindings[0].resource-uri=https://mcp.example.test/orders",
                PREFIX + "service-scope-bindings[0].permission=orders:read",
                PREFIX + "service-scope-bindings[0].scopes[0]=orders:read",
                PREFIX + "service-scope-bindings[0].scopes[1]=orders:inspect",
                PREFIX + "service-scope-bindings[1].resource-uri=https://mcp.example.test/orders",
                PREFIX + "service-scope-bindings[1].permission=orders:write",
                PREFIX + "service-scope-bindings[1].scopes[0]=orders:write"
        };
    }

    private McpGatewayEngineProperties propertiesWithBindings(List<ServiceScopeBindingVO> bindings) {
        return new McpGatewayEngineProperties("mcp-node-1", null, null, bindings);
    }

    private String[] validValues() {
        return new String[]{PREFIX + "node-id=mcp-node-1"};
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpGatewayEngineProperties.class)
    static class PropertiesConfiguration {
    }
}
