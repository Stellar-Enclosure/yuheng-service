package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.util.context.Context;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationRequest;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.platform.tianquan.shoubing.contract.*;
import top.egon.cola.platform.tianquan.shoubing.contract.state.*;
import top.egon.cola.platform.tianquan.shoubing.core.audit.*;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;
import top.egon.cola.platform.tianquan.shoubing.starter.audit.*;
import top.egon.cola.platform.tianquan.shoubing.starter.observability.IdpSecurityObservationReceiverEnum;
import top.egon.cola.platform.tianquan.shoubing.starter.security.*;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.*;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.converter.TokenUsageAuditEventConverterImpl;
import top.egon.cola.platform.tianquan.shoubing.starter.state.*;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class McpRequestObservationComponentTest {
    public static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    public static final URI ISSUER = URI.create("https://issuer.example/tenants/1001");
    public static final URI RESOURCE = URI.create("https://resource.example/mcp/billing");
    public static final String SCOPE = "mcp:billing:read";

    /** Real native verifier/converter/revocation/audit pipeline; only remote authorities and signature manager are fixtures. */
    public static final class NativeFixture implements AutoCloseable {
        public final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
        public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        public final TenantIssuerAuthenticationManagerComponent issuers = mock(TenantIssuerAuthenticationManagerComponent.class);
        public final IdentityAuthorityClientResolver authorities = mock(IdentityAuthorityClientResolver.class);
        public final IdentityAuthorityClient authority = mock(IdentityAuthorityClient.class);
        public final IdpSecurityMetricsRecorder metrics = mock(IdpSecurityMetricsRecorder.class);
        public final List<TokenUsageAuditEvent> events = new CopyOnWriteArrayList<>();
        public final Map<String, VerifiedJwtBO> tokens = new java.util.concurrent.ConcurrentHashMap<>();
        public final IdpObservedVerificationComponent observer;
        public final McpGatewayIdentityAuthenticator authenticator;
        public volatile boolean unavailable;
        public volatile boolean revoked;
        public volatile boolean auditUnavailable;
        public volatile boolean denialUnavailable;

        public NativeFixture() {
            when(issuers.isConfigured()).thenReturn(true);
            when(issuers.authenticate(anyString())).thenAnswer(call -> {
                var value = tokens.get(call.getArgument(0, String.class));
                if (value == null) throw new JwtException("controlled-invalid-signature");
                return value;
            });
            when(authorities.forResource(any(), any())).thenAnswer(call -> {
                if (unavailable) throw new IllegalStateException("controlled-authority-unavailable");
                return authority;
            });
            when(authority.resolve(any())).thenAnswer(call -> {
                IdentitySecurityStateRequest request = call.getArgument(0);
                var value = tokens.get(request.getAccessToken());
                if (revoked) return IdentitySecurityStateResponse.denied(IdentityStatePurpose.ACCESS_VALIDATION, "REVOKED");
                return IdentitySecurityStateResponse.builder().purpose(IdentityStatePurpose.ACCESS_VALIDATION).eligible(true)
                        .reasonCode("ELIGIBLE").issuer(value.getTrustedIssuer().issuer().toASCIIString())
                        .resource(request.getExpectedResource().toASCIIString()).clientId(request.getExpectedClientId())
                        .subject(value.getSubject()).authorizationId(value.getAuthorizationId())
                        .authorizationExpiresAt(value.getExpiresAt().getEpochSecond()).securityVersion(value.getSecurityVersion()).build();
            });
            when(authority.appendAudit(any())).thenAnswer(call -> {
                TokenUsageAuditEvent event = call.getArgument(0);
                if (auditUnavailable || denialUnavailable && event.result().equals("DENIED")) throw new IllegalStateException("controlled-audit-unavailable");
                events.add(event);
                return new IdentityAuditReceipt(UUID.randomUUID().toString(), event.eventId(), TokenUsageAuditFingerprint.of(event),
                        event.issuer().toASCIIString(), "receiver-client", event.resource(), "STORED", NOW);
            });
            var producer = new TokenUsageAuditComponent(authorities, clock, validation.getValidator(), metrics);
            observer = new IdpObservedVerificationComponent(producer, new TokenUsageAuditEventConverterImpl(),
                    new OAuthUsageObservationProperties(true, ISSUER, false, IdpSecurityObservationReceiverEnum.MCP),
                    metrics, clock, validation.getValidator(), RESOURCE);
            authenticator = new McpGatewayIdentityAuthenticator(issuers, authorities, new VerifiedPrincipalConverterImpl(), observer,
                    validation.getValidator(), (server, context) -> Set.of(SCOPE), clock, "1001");
        }

        public VerifiedJwtBO token(String value, boolean service) { return token(value, service, "1001", Set.of(SCOPE)); }
        public VerifiedJwtBO token(String value, boolean service, String tenant, Set<String> scopes) {
            var jwt = Jwt.withTokenValue(value).header("alg", "RS256").header("kid", "key-1").header("typ", "at+jwt")
                    .issuedAt(NOW.minusSeconds(30)).notBefore(NOW.minusSeconds(30)).expiresAt(NOW.plusSeconds(300))
                    .issuer(ISSUER.toASCIIString()).subject(service ? "client-1" : "user-1")
                    .audience(List.of(RESOURCE.toASCIIString())).claim("client_id", "client-1").claim("tenant_id", tenant)
                    .claim("jti", "11").claim("scope", String.join(" ", scopes));
            if (service) jwt.claim("resource_version", 1L).claim("scope_context", "TENANT")
                    .claim("source_biz", "billing").claim("source_app", "consumer").claim("source_env", "prod")
                    .claim("credential_id", "19").claim("app_id", "23");
            var built = jwt.build();
            var verified = new VerifiedJwtBO().setJwt(built).setTrustedIssuer(new TrustedIssuerRegistry.TrustedIssuerVO(
                    ISSUER, URI.create("https://issuer.example/tenants/1001/jwks"), "tenant-1001", TrustedIssuerRegistry.RealmType.TENANT,
                    tenant, TrustedIssuerRegistry.Status.ACTIVE, 1L)).setSubject(built.getSubject()).setTenantId(tenant)
                    .setTokenId("11").setKeyId("key-1").setAuthorizationId("13").setClientRegistrationId("17")
                    .setPrincipalType(service ? PrincipalType.SERVICE : PrincipalType.USER).setExpiresAt(built.getExpiresAt())
                    .setSecurityVersion(new OAuthSecurityVersionBO(1L, 1L, service ? null : 1L));
            tokens.put(value, verified);
            return verified;
        }
        public Map<String,Object> authenticate(McpRequestObservationComponent observation, String token) {
            return authenticator.authenticate(http(token), server()).contextWrite(Context.of(McpRequestObservationComponent.class, observation)).block();
        }
        public McpAuthorizationRequest request(McpRequestObservationComponent observation, Set<String> permissions, long auth, long context, long policy) {
            var identity = observation.verifiedIdentity();
            return new McpAuthorizationRequest(identity.issuer().toASCIIString(), identity.subject(), identity.tenantId(), identity.clientId(),
                    identity.tokenId(), identity.resource().toASCIIString(), identity.issuedAt(), identity.expiresAt(), permissions, auth, context, policy, null);
        }
        @Override public void close() { validation.close(); }
    }

    public static McpRuntimeServer server() { return new McpRuntimeServer("server-1", "billing", "Billing", "billing", "billing", Set.of(top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect.RC_2026_07_28, top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect.STABLE_2025_11_25), RESOURCE.toASCIIString(), 60, true); }
    public static McpHttpRequest http(String token) {
        return new McpHttpRequest("POST", "/mcp/billing/rc", token == null ? Map.of() : Map.of("authorization", "Bearer " + token), "{}", Map.of());
    }

    @Test void bindsOnlyOneNativeIdentityAndClosesTheBorrowedScope() {
        try (var fixture = new NativeFixture(); var observed = new McpRequestObservationComponent()) {
            fixture.token("opaque-user", false); fixture.authenticate(observed, "opaque-user");
            assertTrue(observed.active()); var identity = observed.verifiedIdentity();
            assertEquals(503, assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> observed.bind(identity)).httpStatus());
            observed.close(); observed.close(); assertFalse(observed.active());
            assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, observed::verifiedIdentity);
        }
    }
    @Test void foreignOwnerAndMissingIdentityCannotAuthorize() {
        try (var fixture = new NativeFixture(); var first = new McpRequestObservationComponent(); var foreign = new McpRequestObservationComponent()) {
            fixture.token("opaque-user", false); fixture.authenticate(first, "opaque-user");
            assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> foreign.bind(first.verifiedIdentity()));
            assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, new McpRequestObservationComponent()::verifiedIdentity);
        }
    }
    @Test void denialUsesTheOriginalNativeProofExactlyOnce() {
        try (var fixture = new NativeFixture(); var observed = new McpRequestObservationComponent()) {
            fixture.token("opaque-user", false); fixture.authenticate(observed, "opaque-user");
            observed.confirmDenial(); observed.confirmDenial(); assertTrue(observed.denialConfirmed());
            assertEquals(List.of("SUCCESS", "DENIED"), fixture.events.stream().map(TokenUsageAuditEvent::result).toList());
            assertEquals(fixture.events.getFirst().tokenRef(), fixture.events.getLast().tokenRef());
            assertFalse(fixture.events.toString().contains("opaque-user"));
        }
    }
    @Test void dependencyFailureCannotBeErasedByAProtocolWrapperOrRetried() {
        try (var fixture = new NativeFixture(); var observed = new McpRequestObservationComponent()) {
            fixture.token("opaque-user", false); fixture.authenticate(observed, "opaque-user"); fixture.denialUnavailable = true;
            var failure = assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, observed::confirmDenial);
            assertEquals(503, failure.httpStatus());
            assertSame(failure, observed.preserveSecurityFailure(new IllegalArgumentException("protocol-wrapper")));
            assertSame(failure, assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, observed::confirmDenial));
            verify(fixture.authority, times(2)).appendAudit(any());
        }
    }
}
