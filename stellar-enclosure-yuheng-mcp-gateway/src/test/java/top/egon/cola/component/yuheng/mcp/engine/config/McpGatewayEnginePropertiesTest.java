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

    private static final String PREFIX = "egon.cola.component.yuheng.mcp-engine.";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsImmutableBootstrapDefaultsWithoutRemappingLegacyMcpKeys() {
        runner.withPropertyValues(validValues())
                .withPropertyValues("egon.cola.component.yuheng.engine.mcp.issuer=https://issuer.example.test")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(McpGatewayEngineProperties.class);
                    assertEquals(18084, properties.listener().port());
                    assertEquals(18085, properties.managementPort());
                    assertEquals("mcp-node-1", properties.nodeId());
                    assertEquals(Duration.ofSeconds(5), properties.outbound().requestTimeout());
                    assertNull(properties.outbound().httpTls());
                    assertTrue(McpGatewayEngineProperties.class.isRecord());
                    assertEquals("data/mcp-node-1", properties.dataDirectory());
                    assertEquals(List.of(), properties.serviceScopeBindings());
                });
    }

    @Test
    void rejectsMissingIdentityBeforeAnyListenerCanStart() {
        for (String identity : List.of("yuheng-group-code", "env", "namespace",
                "node-id", "instance-id", "data-directory")) {
            runner.withPropertyValues(validValues()).withPropertyValues(PREFIX + identity + "=")
                    .run(context -> assertNotNull(context.getStartupFailure(), identity));
        }
        runner.withPropertyValues(PREFIX + "yuheng-group-code=orders",
                        PREFIX + "env=local", PREFIX + "namespace=default",
                        PREFIX + "instance-id=instance-1", PREFIX + "data-directory=data/mcp",
                        PREFIX + "listener.tls.development-plaintext=true",
                        PREFIX + "outbound.rpc-tls.development-plaintext=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void rejectsUnsafePathsPortsDurationsAndConfigurableRoles() {
        for (String invalid : List.of(
                "data-directory=/", "data-directory=.", "data-directory=../outside",
                "listener.port=-1", "management-port=65536", "management-port=18084",
                "listener.drain-timeout=0s", "outbound.request-timeout=-1s",
                "outbound.max-connections=0", "active-health.maximum-concurrency=0",
                "role=API_RPC")) {
            runner.withPropertyValues(validValues()).withPropertyValues(PREFIX + invalid)
                    .run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }

    @Test
    void requiresExplicitPlaintextOrCompleteTlsConfiguration() {
        runner.withPropertyValues(validValues())
                .withPropertyValues(PREFIX + "listener.tls.development-plaintext=false")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues(validValues())
                .withPropertyValues(PREFIX + "listener.tls.enabled=true",
                        PREFIX + "listener.tls.development-plaintext=false")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void bindsOptionalHttpUpstreamIdentityWithoutChangingRpcTransport() {
        runner.withPropertyValues(validValues()).withPropertyValues(
                PREFIX + "outbound.http-tls.enabled=true",
                PREFIX + "outbound.http-tls.certificate-chain-path=certificate.pem",
                PREFIX + "outbound.http-tls.private-key-path=private-key.pem",
                PREFIX + "outbound.http-tls.trust-certificate-collection-path=ca.pem",
                PREFIX + "outbound.http-tls.client-certificate-required=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var outbound = context.getBean(McpGatewayEngineProperties.class).outbound();
                    assertTrue(outbound.httpTls().enabled());
                    assertEquals("certificate.pem", outbound.httpTls().certificateChainPath());
                    assertEquals("ca.pem", outbound.httpTls().trustCertificateCollectionPath());
                    assertTrue(outbound.rpcTls().developmentPlaintext());
                });
    }

    @Test
    void defaultsOptionalServiceScopeBindingsAndPreservesTheOldConstructor() {
        assertEquals(List.of(), propertiesWithBindings(null).serviceScopeBindings());
        var legacy = new McpGatewayEngineProperties("orders", "local", "default", "mcp-node-1",
                "mcp-instance-1", "data/mcp-node-1", null, 18085, null, null);
        assertEquals(List.of(), legacy.serviceScopeBindings());
        assertEquals(legacy, propertiesWithBindings(List.of()));
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
        for (boolean listenerEnabled : List.of(true, false)) {
            runner.withPropertyValues(validValues()).withPropertyValues(validBindingValues())
                    .withPropertyValues(PREFIX + "listener.enabled=" + listenerEnabled)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var properties = context.getBean(McpGatewayEngineProperties.class);
                        assertEquals(List.of(
                                new ServiceScopeBindingVO(URI.create("https://mcp.example.test/orders"),
                                        "orders:read", Set.of("orders:read", "orders:inspect")),
                                new ServiceScopeBindingVO(URI.create("https://mcp.example.test/orders"),
                                        "orders:write", Set.of("orders:write"))), properties.serviceScopeBindings());
                        assertEquals(listenerEnabled, properties.listener().enabled());
                        assertEquals(18084, properties.listener().port());
                        assertTrue(properties.outbound().rpcTls().developmentPlaintext());
                    });
        }
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
        for (boolean listenerEnabled : List.of(true, false)) {
            runner.withPropertyValues(validValues()).withPropertyValues(validBindingValues())
                    .withPropertyValues(PREFIX + "listener.enabled=" + listenerEnabled,
                            PREFIX + "service-scope-bindings[" + index + "].operator-override=true")
                    .run(context -> assertNotNull(context.getStartupFailure()));
        }
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
        return new McpGatewayEngineProperties("orders", "local", "default", "mcp-node-1", "mcp-instance-1",
                "data/mcp-node-1", null, 18085, null, null, bindings);
    }

    private String[] validValues() {
        return new String[]{
                PREFIX + "yuheng-group-code=orders", PREFIX + "env=local",
                PREFIX + "namespace=default", PREFIX + "node-id=mcp-node-1",
                PREFIX + "instance-id=mcp-instance-1", PREFIX + "data-directory=./data/mcp-node-1",
                PREFIX + "listener.tls.development-plaintext=true",
                PREFIX + "outbound.rpc-tls.development-plaintext=true"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpGatewayEngineProperties.class)
    static class PropertiesConfiguration {
    }
}
