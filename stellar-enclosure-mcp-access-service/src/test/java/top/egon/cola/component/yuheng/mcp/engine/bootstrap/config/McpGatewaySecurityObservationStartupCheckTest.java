package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import jakarta.validation.Validation;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import top.egon.cola.platform.tianquan.shoubing.starter.audit.OAuthUsageObservationProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.observability.IdpSecurityObservationReceiverEnum;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpGatewaySecurityObservationStartupCheckTest {
    private static final URI ISSUER = URI.create("https://idp.example/tenants/1001");
    private static final String APP_TLS = "server.ssl.enabled";
    private static final String RPC_TRANSPORT = "egon.cola.component.rpc.transport";

    @Test
    void finiteHttpsTrustStartsWithPlaintextServiceTransportWithoutAuthorizationRedis() {
        var configuration = new McpGatewayManagementSecurityConfiguration();
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var identity = identity(); identity.getRevocation().setPrimaryEndpoint(null);
            assertThatCode(() -> configuration.securityObservationStartupCheck(usage(), () -> true, identity, validators.getValidator(), environment())).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsPlaintextIssuerJwksAndResourceAndMissingFiniteIssuers() {
        var configuration = new McpGatewayManagementSecurityConfiguration();
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            for (String setting : List.of("issuer", "jwks", "resource", "missing")) {
                var identity = identity(); var issuer=identity.getTrustedIssuers().get("controlled");
                if (setting.equals("issuer")) issuer.setIssuer("http://idp.example/tenants/1001");
                if (setting.equals("jwks")) issuer.setJwksUri("http://idp.example/jwks");
                if (setting.equals("resource")) identity.setResourceUri(URI.create("http://resource.example"));
                if (setting.equals("missing")) identity.setTrustedIssuers(Map.of());
                assertThatThrownBy(() -> configuration.securityObservationStartupCheck(usage(), () -> true, identity, validators.getValidator(), environment())).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void requiresDurableAuditAndRejectsPerPodTls() {
        var configuration = new McpGatewayManagementSecurityConfiguration();
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            assertThatThrownBy(() -> configuration.securityObservationStartupCheck(usage(), () -> false, identity(), validators.getValidator(), environment()))
                    .hasMessage("YUHENG_SECURITY_OBSERVATION_NOT_READY");
            assertThatThrownBy(() -> configuration.securityObservationStartupCheck(usage().setEnabled(false), () -> true, identity(), validators.getValidator(), environment()))
                    .hasMessage("YUHENG_SECURITY_OBSERVATION_NOT_READY");
            for (var setting : Map.of(APP_TLS, "true", RPC_TRANSPORT, "tls",
                    "management.server.ssl.enabled", "true", "server.forward-headers-strategy", "framework").entrySet()) {
                var environment = environment().withProperty(setting.getKey(), setting.getValue());
                assertThatThrownBy(() -> configuration.securityObservationStartupCheck(usage(), () -> true, identity(), validators.getValidator(), environment))
                        .hasMessage("YUHENG_SECURITY_OBSERVATION_NOT_READY");
            }
        }
    }

    @Test
    void requiresASeparateManagementPort() {
        var configuration = new McpGatewayManagementSecurityConfiguration();
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var shared = environment().withProperty("management.server.port", "18084");
            assertThatThrownBy(() -> configuration.securityObservationStartupCheck(
                    usage(), () -> true, identity(), validators.getValidator(), shared))
                    .hasMessage("MCP_MANAGEMENT_PORT_NOT_SEPARATE");
        }
    }

    private static OAuthUsageObservationProperties usage() {
        return new OAuthUsageObservationProperties(true, ISSUER, true, IdpSecurityObservationReceiverEnum.MCP);
    }

    private static MockEnvironment environment() {
        var environment = new MockEnvironment().withProperty("DEPLOYMENT_ENV", "local")
                .withProperty("server.port", "18084").withProperty("management.server.port", "18085")
                .withProperty(APP_TLS, "false").withProperty(RPC_TRANSPORT, "h2c");
        environment.setActiveProfiles("operations", "local");
        return environment;
    }

    private static IdpStarterProperties identity() {
        var properties = new IdpStarterProperties();
        properties.setResourceServerId("controlled-resource");
        properties.setResourceUri(URI.create("https://resource.example"));
        var issuer = new IdpStarterProperties.TrustedIssuerSettings();
        issuer.setIssuer(ISSUER.toASCIIString());
        issuer.setJwksUri(ISSUER + "/.well-known/jwks.json");
        issuer.setRealmKey("tenant:1001");
        issuer.setRealmType("TENANT");
        issuer.setTenantId("1001");
        properties.setTrustedIssuers(Map.of("controlled", issuer));
        return properties;
    }
}
