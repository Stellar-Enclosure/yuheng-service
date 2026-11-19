package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import org.junit.jupiter.api.Test;
import reactor.util.context.Context;
import java.util.Map;
import java.util.Set;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditEvent;
import static top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayIdentityAuthenticatorTest {
    @Test
    void inboundSecurityDoesNotLoadTheGenericGatewayOrItsIdpAdapter() throws Exception {
        Path root = Path.of("src/main/java/top/egon/cola/component/yuheng/mcp/engine/mcp");
        List<String> forbidden = List.of("import top.egon.cola.component.yuheng.core.security.",
                "import top.egon.cola.component.yuheng.core.context.", "import top.egon.cola.component.yuheng.core.exchange.",
                "import top.egon.cola.component.yuheng.runtime.", "import top.egon.cola.platform.tianquan.shoubing.yuheng.");
        for (String name : List.of("service/McpGatewayIdentityAuthenticator.java", "service/McpRequestObservationComponent.java",
                "service/McpEngineHttpHandler.java", "adapter/security/Rbac3McpAuthorizationAdapter.java")) {
            for (String line : Files.readAllLines(root.resolve(name))) {
                assertTrue(forbidden.stream().noneMatch(line::startsWith), name + " retains a generic execution dependency: " + line);
            }
        }
    }
    @Test void nativeUserAndServiceUseVerifiedClaimsAndOneSecretFreeAudit() {
        for (boolean service : new boolean[]{false, true}) {
            try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
                fixture.token("opaque-token", service);
                var attributes = fixture.authenticate(observation, "opaque-token");
                var identity = observation.verifiedIdentity();
                assertSame(identity, attributes.get(McpGatewayIdentityAuthenticator.VERIFIED_CONTEXT_ATTRIBUTE));
                assertEquals(service ? "client-1" : "user-1", attributes.get("identity.subject"));
                assertEquals("client-1", attributes.get("identity.client-id"));
                assertEquals(ISSUER, identity.issuer()); assertEquals(RESOURCE, identity.resource());
                assertEquals(1, fixture.events.size()); assertEquals("SUCCESS", fixture.events.getFirst().result());
                assertFalse(attributes.containsKey("originalBearerToken"));
                assertFalse(identity.toString().contains("opaque-token"));
                assertFalse(fixture.events.toString().contains("opaque-token"));
                if (service) assertNull(identity.userTokenForAuthorization());
            }
        }
    }
    @Test void missingInvalidAndRevokedTokensRemain401() {
        for (String mode : java.util.List.of("missing", "invalid", "revoked")) {
            try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
                fixture.token("opaque-token", false); fixture.revoked = mode.equals("revoked");
                String token = mode.equals("missing") ? null : mode.equals("invalid") ? "bad-signature" : "opaque-token";
                var failure = assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> fixture.authenticate(observation, token));
                assertEquals(401, failure.httpStatus()); assertTrue(failure.responseHeaders().containsKey("www-authenticate"));
                assertEquals(1, fixture.events.size());
            }
        }
    }
    @Test void sameAudienceAndScopesDoNotCrossTheConfiguredIdentityTenant() {
        try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
            fixture.token("foreign", true, "1002", Set.of(SCOPE));
            assertEquals(403, assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                    () -> fixture.authenticate(observation, "foreign")).httpStatus());
            assertEquals(java.util.List.of("SUCCESS", "DENIED"), fixture.events.stream().map(TokenUsageAuditEvent::result).toList());
        }
    }
    @Test void missingDeploymentScopeOrCurrentAuthorityNeverInventsAGrant() {
        try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
            fixture.token("missing-scope", true, "1001", Set.of("other:scope"));
            assertEquals(403, assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                    () -> fixture.authenticate(observation, "missing-scope")).httpStatus());
        }
        for (boolean audit : new boolean[]{false, true}) {
            try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
                fixture.token("opaque-token", false); fixture.unavailable = !audit; fixture.auditUnavailable = audit;
                assertEquals(503, assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                        () -> fixture.authenticate(observation, "opaque-token")).httpStatus());
            }
        }
    }
    @Test void requestAttributesAndAuthorizationSyntaxCannotSelfAssertIdentity() {
        try (var fixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
            var request = new McpHttpRequest("POST", "/mcp/billing", Map.of("authorization", "Bearer token, Bearer other"), "{}",
                    Map.of("identity.subject", "administrator", "tenantId", "1001"));
            assertEquals(401, assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                    () -> fixture.authenticator.authenticate(request, server())
                            .contextWrite(Context.of(McpRequestObservationComponent.class, observation)).block()).httpStatus());
            verify(fixture.issuers, never()).authenticate(anyString());
            assertEquals(1, fixture.events.size());
        }
    }

    @Test void managementUserProofUsesExactConfiguredResourceAndPhysicalLatch() {
        try(var fixture=new NativeFixture();var observation=new McpRequestObservationComponent()) {
            fixture.token("management-user",false);
            var identity=fixture.authenticator.authenticateManagement(http("management-user"),RESOURCE,Set.of(SCOPE))
                    .contextWrite(Context.of(McpRequestObservationComponent.class,observation)).block();
            assertEquals("user-1",identity.principal().subject());assertEquals("client-1",identity.clientId());
            assertEquals(ISSUER,identity.issuer());assertEquals(RESOURCE,identity.resource());assertEquals(Set.of(SCOPE),identity.scopes());
            assertEquals(RESOURCE,identity.proof().resource());identity.requireCurrent(fixture.clock);
            assertEquals(1,fixture.events.size());assertEquals("management-user",identity.userTokenForAuthorization());
            assertFalse(identity.toString().contains("management-user"));
            observation.close();
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,identity::userTokenForAuthorization).httpStatus());
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,()->identity.requireCurrent(fixture.clock)).httpStatus());
        }
    }
    @Test void managementNeverAcceptsServiceTokensOrAlternateAudiences() {
        for(boolean service:new boolean[]{false,true}) {
            try(var fixture=new NativeFixture();var observation=new McpRequestObservationComponent()) {
                fixture.token("management",service);
                var resource=service?RESOURCE:java.net.URI.create("https://resource.example/another");
                assertEquals(service?403:401,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                        ()->fixture.authenticator.authenticateManagement(http("management"),resource,Set.of(SCOPE))
                                .contextWrite(Context.of(McpRequestObservationComponent.class,observation)).block()).httpStatus());
                assertEquals(service?2:1,fixture.events.size());
            }
        }
    }
    @Test void configuredManagementPolicyAndClosedEvidenceDoNotFallBackToPublicAudience() {
        try(var fixture=new NativeFixture();var observation=new McpRequestObservationComponent()) {
            fixture.token("management",false);
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                    ()->fixture.authenticator.authenticateManagement(http("management"),RESOURCE,Set.of())
                            .contextWrite(Context.of(McpRequestObservationComponent.class,observation)).block()).httpStatus());
            verify(fixture.issuers,never()).authenticate(anyString());
        }
    }
}
