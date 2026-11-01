package top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security;

import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationRequest;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.AuthorizationSnapshotCache;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import top.egon.cola.component.yuheng.core.security.GatewayAuthContext;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponent;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest;
import top.egon.cola.component.yuheng.runtime.security.domain.GatewaySecurityException;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;
import top.egon.cola.platform.tianquan.shoubing.yuheng.security.domain.GatewayVerifiedIdentityBO;
import top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class McpRbac3IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void strictFineDenialsShareOneOriginalProofHandoffAndAllowNeverRecordsDeny() {
        var observer = mock(IdpObservedVerificationComponent.class);
        when(observer.authorizationDenied(any(), any())).thenAnswer(call -> {
            assertTrue(Thread.currentThread().getName().startsWith("boundedElastic"));
            return true;
        });
        try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
            var denied = bearer(request(Set.of("invoice:refund"), 7, 3, 3));
            var context = McpRequestObservationComponentTest.context(denied);
            scope.bind(context);
            var adapter = configured(observer, validation.getValidator(), systemCode -> snapshot(systemCode));
            assertFalse(strict(adapter, denied, scope).allowed());
            assertFalse(strict(adapter, denied, scope).allowed());
            var allowed = bearer(request(Set.of("invoice:pay"), 7, 3, 3));
            assertTrue(strict(adapter, allowed, scope).allowed());
            verify(observer, times(1)).authorizationDenied(
                    same(((GatewayVerifiedIdentityBO) context.principal().trustedAuthenticationEvidence()).proof()), eq(context.traceId()));
            scope.requireAvailable();
        }
    }

    @Test
    void failedOrThrowingStrictHandoffsRemainUnavailableWithoutRetry() {
        for (boolean throwsFailure : new boolean[]{false, true}) {
            var observer = mock(IdpObservedVerificationComponent.class);
            if (throwsFailure) { when(observer.authorizationDenied(any(), any())).thenThrow(new IllegalStateException("controlled-audit")); }
            else { when(observer.authorizationDenied(any(), any())).thenReturn(false); }
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                var request = bearer(request(Set.of("invoice:refund"), 7, 3, 3));
                scope.bind(McpRequestObservationComponentTest.context(request));
                var adapter = configured(observer, validation.getValidator(), this::snapshot);
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                verify(observer, times(1)).authorizationDenied(any(), any());
                assertThrows(GatewaySecurityException.class, scope::requireAvailable);
            }
        }
    }

    @Test
    void unavailableSnapshotAndVersionFenceAre503WithoutInventingADeny() {
        for (boolean unavailable : new boolean[]{false, true}) {
            var observer = mock(IdpObservedVerificationComponent.class);
            var fetches = new AtomicInteger();
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                var request = bearer(request(Set.of("invoice:pay"), 8, 3, 3));
                scope.bind(McpRequestObservationComponentTest.context(request));
                var adapter = configured(observer, validation.getValidator(), systemCode -> {
                    fetches.incrementAndGet();
                    if (unavailable) { throw new Rbac3AuthorizationClient.AuthorizationUnavailableException("controlled-snapshot"); }
                    return snapshot(systemCode);
                });
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                assertEquals(1, fetches.get());
                verifyNoInteractions(observer);
            }
        }
    }

    @Test
    void missingForeignOrClosedPhysicalMarkerRejectsEvenAnAllowingSnapshot() {
        var observer = mock(IdpObservedVerificationComponent.class);
        var fetches = new AtomicInteger();
        try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent();
             var foreign = new McpRequestObservationComponent()) {
            var request = bearer(request(Set.of("invoice:pay"), 7, 3, 3));
            scope.bind(McpRequestObservationComponentTest.context(request));
            var adapter = configured(observer, validation.getValidator(), systemCode -> { fetches.incrementAndGet(); return snapshot(systemCode); });
            assertEquals(503, assertThrows(GatewaySecurityException.class,
                    () -> Mono.from(adapter.authorize(request)).contextWrite(c -> c.put(McpRequestObservationComponent.class, scope)).block()).httpStatus());
            assertEquals(503, assertThrows(GatewaySecurityException.class,
                    () -> Mono.from(adapter.authorize(request)).contextWrite(c -> c.put(McpRequestObservationComponent.class, scope)
                            .put(GatewayAuthContext.class, foreign.authorizationScope())).block()).httpStatus());
            scope.close();
            assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
            assertEquals(0, fetches.get());
            verifyNoInteractions(observer);
        }
    }

    @Test
    void foreignRequestMetadataCannotReuseTheVerifiedRequest() {
        var observer = mock(IdpObservedVerificationComponent.class);
        var fetches = new AtomicInteger();
        var original = bearer(request(Set.of("invoice:pay"), 7, 3, 3));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            for (int changed = 0; changed < 8; changed++) {
                var candidate = new McpAuthorizationRequest(changed == 0 ? "https://foreign.example" : original.issuer(),
                        changed == 1 ? "foreign-subject" : original.subjectId(), changed == 2 ? "foreign-tenant" : original.tenantId(),
                        changed == 3 ? "foreign-client" : original.clientId(), changed == 4 ? "foreign-token" : original.tokenId(),
                        changed == 5 ? "https://foreign.example/resource" : original.resourceUri(),
                        changed == 6 ? original.issuedAt().minusSeconds(1) : original.issuedAt(),
                        changed == 7 ? original.expiresAt().plusSeconds(1) : original.expiresAt(), original.requiredPermissions(),
                        7, 3, 3, original.userAccessToken());
                try (var scope = new McpRequestObservationComponent()) {
                    scope.bind(McpRequestObservationComponentTest.context(original));
                    var adapter = configured(observer, validation.getValidator(), systemCode -> { fetches.incrementAndGet(); return snapshot(systemCode); });
                    assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, candidate, scope)).httpStatus());
                }
            }
        }
        assertEquals(0, fetches.get());
        verifyNoInteractions(observer);
    }

    @Test
    void missingProofOrUnverifiedScopesFailBeforeLoadingAuthorization() {
        var observer = mock(IdpObservedVerificationComponent.class);
        var fetches = new AtomicInteger();
        var request = bearer(request(Set.of("invoice:pay"), 7, 3, 3));
        var context = McpRequestObservationComponentTest.context(request);
        var principal = context.principal();
        var valid = (GatewayVerifiedIdentityBO) principal.trustedAuthenticationEvidence();
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            for (Object evidence : new Object[]{null, new GatewayVerifiedIdentityBO(null, Set.of("yuheng:read")),
                    new GatewayVerifiedIdentityBO(valid.proof(), Set.of("openid"))}) {
                try (var scope = new McpRequestObservationComponent()) {
                    scope.bind(context.withPrincipal(new top.egon.cola.component.yuheng.core.context.GatewayPrincipal(
                            principal.principalId(), principal.principalType(), principal.tenantId(), principal.displayName(), true, principal.attributes(), evidence)));
                    var adapter = configured(observer, validation.getValidator(), systemCode -> { fetches.incrementAndGet(); return snapshot(systemCode); });
                    assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                }
            }
        }
        assertEquals(0, fetches.get()); verifyNoInteractions(observer);
    }

    @Test
    void verifiedServiceIdentityIsADenialAndNeverLoadsAUserSnapshot() {
        var observer = mock(IdpObservedVerificationComponent.class);
        when(observer.authorizationDenied(any(), any())).thenReturn(true);
        var fetches = new AtomicInteger(); var request = bearer(request(Set.of("invoice:pay"), 7, 3, 3));
        var context = McpRequestObservationComponentTest.context(request); var principal = context.principal();
        var attributes = new java.util.LinkedHashMap<>(principal.attributes());
        attributes.remove("tianquan-shoubing.audience"); attributes.put("tianquan-shoubing.client-id", request.clientId());
        try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
            scope.bind(context.withPrincipal(new top.egon.cola.component.yuheng.core.context.GatewayPrincipal(principal.principalId(),
                    "SERVICE", principal.tenantId(), null, true, attributes, principal.trustedAuthenticationEvidence())));
            var adapter = configured(observer, validation.getValidator(), systemCode -> { fetches.incrementAndGet(); return snapshot(systemCode); });
            var result = strict(adapter, request, scope);
            assertFalse(result.allowed()); assertEquals("TIANQUAN_JIANSHEN_USER_PRINCIPAL_REQUIRED", result.reasonCode());
            assertEquals(0, fetches.get()); verify(observer, times(1)).authorizationDenied(any(), eq(context.traceId()));
        }
    }

    @Test
    void verifiedServiceScopesAuthorizeEveryRequiredPermissionWithoutAUserSnapshot() {
        var observer = mock(IdpObservedVerificationComponent.class);
        var loader = mock(SingleFlightSnapshotLoader.class);
        var scopes = new java.util.HashSet<String>(Set.of("yuheng:read", "invoice:pay", "tool:pay"));
        for (int index = 0; index < 61; index++) { scopes.add("business:" + index + ":" + "r".repeat(100)); }
        var request = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
        try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
            var context = serviceContext(request, scopes, validation.getValidator());
            assertFalse(context.principal().attributes().containsKey("tianquan-shoubing.service-scopes"));
            assertFalse(context.principal().attributes().containsKey("tianquan-shoubing-auth-acr"));
            scope.bind(context);
            var bindings = new java.util.ArrayList<>(serviceBindings(request.resourceUri()));
            var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                    validation.getValidator(), bindings, Clock.fixed(NOW, ZoneOffset.UTC));
            bindings.clear();
            var decision = strict(adapter, request, scope);
            assertTrue(decision.allowed()); assertEquals(0, decision.authVersion());
            assertEquals(0, decision.contextVersion()); assertEquals(0, decision.policyVersion());
            verifyNoInteractions(loader, observer);
        }
    }

    @Test
    void serviceAuthorizationRequiresExactBindingsAndEveryVerifiedScope() {
        var request = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
        for (String missing : java.util.List.of("EMPTY", "IMPLICIT_BINDING", "EXTRA_BINDING", "RESOURCE", "IMPLICIT_SCOPE", "EXTRA_SCOPE")) {
            var observer = mock(IdpObservedVerificationComponent.class);
            when(observer.authorizationDenied(any(), any())).thenReturn(true);
            var loader = mock(SingleFlightSnapshotLoader.class);
            var bindings = new java.util.ArrayList<>(serviceBindings("RESOURCE".equals(missing) ? "https://foreign.example/resource" : request.resourceUri()));
            if ("EMPTY".equals(missing)) { bindings.clear(); }
            if ("IMPLICIT_BINDING".equals(missing)) { bindings.removeFirst(); }
            if ("EXTRA_BINDING".equals(missing)) { bindings.removeLast(); }
            var scopes = new java.util.HashSet<>(Set.of("yuheng:read", "invoice:pay", "tool:pay"));
            if ("IMPLICIT_SCOPE".equals(missing)) { scopes.remove("tool:pay"); }
            if ("EXTRA_SCOPE".equals(missing)) { scopes.remove("invoice:pay"); }
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                var context = serviceContext(request, scopes, validation.getValidator());
                var attributes = new java.util.LinkedHashMap<>(context.principal().attributes());
                attributes.put("tianquan-shoubing.service-scopes", "yuheng:read invoice:pay tool:pay");
                var principal = context.principal();
                context = context.withPrincipal(new top.egon.cola.component.yuheng.core.context.GatewayPrincipal(principal.principalId(),
                        principal.principalType(), principal.tenantId(), null, true, attributes, principal.trustedAuthenticationEvidence()));
                scope.bind(context);
                var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                        validation.getValidator(), bindings, Clock.fixed(NOW, ZoneOffset.UTC));
                var decision = strict(adapter, request, scope);
                assertFalse(decision.allowed(), missing);
                assertEquals("EMPTY".equals(missing) ? "TIANQUAN_JIANSHEN_USER_PRINCIPAL_REQUIRED" : "TIANQUAN_JIANSHEN_PERMISSION_DENIED", decision.reasonCode(), missing);
                assertFalse(strict(adapter, request, scope).allowed());
                verify(observer, times(1)).authorizationDenied(same(((GatewayVerifiedIdentityBO) context.principal().trustedAuthenticationEvidence()).proof()), eq(context.traceId()));
                verifyNoInteractions(loader);
            }
        }
    }

    @Test
    void serviceSelfClientAndInitialTokenWindowDenyWithTheOriginalProofOnce() {
        for (String invalid : java.util.List.of("SELF", "FUTURE", "EXPIRED")) {
            var request = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"),
                    "FUTURE".equals(invalid) ? NOW.plusNanos(1) : NOW.minusSeconds(30),
                    "EXPIRED".equals(invalid) ? NOW : NOW.plusSeconds(300));
            var observer = mock(IdpObservedVerificationComponent.class);
            when(observer.authorizationDenied(any(), any())).thenReturn(true);
            var loader = mock(SingleFlightSnapshotLoader.class);
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                var context = serviceContext(request, Set.of("yuheng:read", "invoice:pay", "tool:pay"), validation.getValidator());
                if ("SELF".equals(invalid)) {
                    var principal = context.principal();
                    context = context.withPrincipal(new top.egon.cola.component.yuheng.core.context.GatewayPrincipal("foreign-service", "SERVICE",
                            principal.tenantId(), null, true, principal.attributes(), principal.trustedAuthenticationEvidence()));
                    request = new McpAuthorizationRequest(request.issuer(), "foreign-service", request.tenantId(), request.clientId(), request.tokenId(),
                            request.resourceUri(), request.issuedAt(), request.expiresAt(), request.requiredPermissions(), 0, 0, 0, request.userAccessToken());
                }
                scope.bind(context);
                var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                        validation.getValidator(), serviceBindings(request.resourceUri()), Clock.fixed(NOW, ZoneOffset.UTC));
                var decision = strict(adapter, request, scope);
                assertFalse(decision.allowed(), invalid); assertEquals("TIANQUAN_JIANSHEN_PERMISSION_DENIED", decision.reasonCode());
                assertFalse(strict(adapter, request, scope).allowed());
                verify(observer, times(1)).authorizationDenied(same(((GatewayVerifiedIdentityBO) context.principal().trustedAuthenticationEvidence()).proof()), eq(context.traceId()));
                verifyNoInteractions(loader);
            }
        }
    }

    @Test
    void serviceRbacVersionMinimaAreFencedWithoutASnapshotOrDenialFact() {
        var original = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
        for (int field = 0; field < 3; field++) {
            var observer = mock(IdpObservedVerificationComponent.class); var loader = mock(SingleFlightSnapshotLoader.class);
            var request = new McpAuthorizationRequest(original.issuer(), original.subjectId(), original.tenantId(), original.clientId(), original.tokenId(),
                    original.resourceUri(), original.issuedAt(), original.expiresAt(), original.requiredPermissions(), field == 0 ? 1 : 0,
                    field == 1 ? 1 : 0, field == 2 ? 1 : 0, original.userAccessToken());
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                scope.bind(serviceContext(request, Set.of("yuheng:read", "invoice:pay", "tool:pay"), validation.getValidator()));
                var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                        validation.getValidator(), serviceBindings(request.resourceUri()), Clock.fixed(NOW, ZoneOffset.UTC));
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                verifyNoInteractions(loader, observer);
            }
        }
    }

    @Test
    void serviceAllowRechecksPhysicalAvailabilityAndTokenTimeBeforeHandoff() {
        var request = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
        for (String lost : java.util.List.of("EXPIRED", "FUTURE", "CLOSED", "UNAVAILABLE", "AUTHORIZATION_SCOPE")) {
            var observer = mock(IdpObservedVerificationComponent.class); var loader = mock(SingleFlightSnapshotLoader.class);
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                scope.bind(serviceContext(request, Set.of("yuheng:read", "invoice:pay", "tool:pay"), validation.getValidator()));
                var reads = new AtomicInteger(); var clock = mock(Clock.class);
                when(clock.instant()).thenAnswer(call -> {
                    if (reads.incrementAndGet() == 1) { return NOW; }
                    if ("CLOSED".equals(lost)) { scope.close(); }
                    if ("UNAVAILABLE".equals(lost)) { scope.unavailable(new IllegalStateException("controlled-loss")); }
                    if ("AUTHORIZATION_SCOPE".equals(lost)) { scope.authorizationScope().close(); }
                    return "EXPIRED".equals(lost) ? request.expiresAt() : "FUTURE".equals(lost) ? request.issuedAt().minusNanos(1) : NOW;
                });
                var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                        validation.getValidator(), serviceBindings(request.resourceUri()), clock);
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus(), lost);
                verifyNoInteractions(loader, observer);
            }
        }
    }

    @Test
    void serviceDenialAuditFailureRemainsUnavailableWithoutRetry() {
        var request = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
        for (boolean throwing : new boolean[]{false, true}) {
            var observer = mock(IdpObservedVerificationComponent.class); var loader = mock(SingleFlightSnapshotLoader.class);
            if (throwing) { when(observer.authorizationDenied(any(), any())).thenThrow(new IllegalStateException("controlled-audit")); }
            else { when(observer.authorizationDenied(any(), any())).thenReturn(false); }
            try (var validation = Validation.buildDefaultValidatorFactory(); var scope = new McpRequestObservationComponent()) {
                var context = serviceContext(request, Set.of("yuheng:read"), validation.getValidator()); scope.bind(context);
                var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                        validation.getValidator(), serviceBindings(request.resourceUri()), Clock.fixed(NOW, ZoneOffset.UTC));
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                assertEquals(503, assertThrows(GatewaySecurityException.class, () -> strict(adapter, request, scope)).httpStatus());
                verify(observer, times(1)).authorizationDenied(same(((GatewayVerifiedIdentityBO) context.principal().trustedAuthenticationEvidence()).proof()), eq(context.traceId()));
                verifyNoInteractions(loader);
            }
        }
    }

    @Test
    void serviceCommonTrustValidationPrecedesScopesAndNeverLoadsAUserSnapshot() {
        var request = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            for (String invalid : java.util.List.of("PROOF", "INGRESS", "FOREIGN_REQUEST", "FOREIGN_SCOPE", "MISSING_SCOPE", "CLOSED")) {
                var observer = mock(IdpObservedVerificationComponent.class); var loader = mock(SingleFlightSnapshotLoader.class);
                try (var scope = new McpRequestObservationComponent(); var foreign = new McpRequestObservationComponent()) {
                    var context = serviceContext(request, Set.of("yuheng:read", "invoice:pay", "tool:pay"), validation.getValidator());
                    if ("PROOF".equals(invalid) || "INGRESS".equals(invalid)) {
                        var principal = context.principal();
                        var evidence = (GatewayVerifiedIdentityBO) principal.trustedAuthenticationEvidence();
                        context = context.withPrincipal(new top.egon.cola.component.yuheng.core.context.GatewayPrincipal(principal.principalId(), "SERVICE", principal.tenantId(), null,
                                true, principal.attributes(), "PROOF".equals(invalid) ? null : new GatewayVerifiedIdentityBO(evidence.proof(), Set.of("invoice:pay", "tool:pay"))));
                    }
                    scope.bind(context); if ("CLOSED".equals(invalid)) { scope.close(); }
                    var adapter = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                            validation.getValidator(), serviceBindings(request.resourceUri()), Clock.fixed(NOW, ZoneOffset.UTC));
                    var candidate = "FOREIGN_REQUEST".equals(invalid) ? new McpAuthorizationRequest(request.issuer(), request.subjectId(), request.tenantId(), request.clientId(),
                            "foreign-token", request.resourceUri(), request.issuedAt(), request.expiresAt(), request.requiredPermissions(), 0, 0, 0, request.userAccessToken()) : request;
                    assertEquals(503, assertThrows(GatewaySecurityException.class, () -> Mono.from(adapter.authorize(candidate)).contextWrite(reactor -> {
                        var bound = reactor.put(McpRequestObservationComponent.class, scope);
                        return "MISSING_SCOPE".equals(invalid) ? bound : bound.put(GatewayAuthContext.class,
                                "FOREIGN_SCOPE".equals(invalid) ? foreign.authorizationScope() : scope.authorizationScope());
                    }).block(Duration.ofSeconds(5))).httpStatus(), invalid);
                    verifyNoInteractions(loader, observer);
                }
            }
        }
    }

    @Test
    void serviceFactoryBoundsAndCopiesItsExactFiniteIndex() {
        var observer = mock(IdpObservedVerificationComponent.class); var loader = mock(SingleFlightSnapshotLoader.class);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var binding = serviceBindings("https://resource.egon.top/yuheng-mcp").getFirst();
            assertThrows(IllegalArgumentException.class, () -> Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                    validation.getValidator(), java.util.Collections.nCopies(257, binding), Clock.fixed(NOW, ZoneOffset.UTC)));
            assertThrows(IllegalArgumentException.class, () -> Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                    validation.getValidator(), java.util.List.of(binding, binding), Clock.fixed(NOW, ZoneOffset.UTC)));
            assertThrows(NullPointerException.class, () -> Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                    validation.getValidator(), java.util.Arrays.asList(binding, null), Clock.fixed(NOW, ZoneOffset.UTC)));
            assertThrows(NullPointerException.class, () -> Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                    validation.getValidator(), java.util.List.of(binding), null));
            verifyNoInteractions(loader, observer);
        }
    }

    @Test
    void newServiceFactoryPreservesUserSnapshotsAndOldFactoryKeepsServiceDenied() {
        var observer = mock(IdpObservedVerificationComponent.class);
        when(observer.authorizationDenied(any(), any())).thenReturn(true);
        var loader = mock(SingleFlightSnapshotLoader.class);
        when(loader.load(any(), anyString())).thenReturn(snapshot("yuheng-mcp"));
        try (var validation = Validation.buildDefaultValidatorFactory(); var userScope = new McpRequestObservationComponent();
             var serviceScope = new McpRequestObservationComponent()) {
            var user = bearer(request(Set.of("invoice:pay"), 7, 3, 3));
            userScope.bind(McpRequestObservationComponentTest.context(user));
            var configured = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP,
                    validation.getValidator(), serviceBindings(user.resourceUri()), Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals(7, strict(configured, user, userScope).authVersion());
            verify(loader, times(1)).load(any(), eq(user.userAccessToken()));
            clearInvocations(loader);
            var service = serviceRequest(Set.of("mcp:billing:tool:pay_invoice:call", "invoice:pay"), NOW.minusSeconds(30), NOW.plusSeconds(300));
            var context = serviceContext(service, Set.of("yuheng:read", "invoice:pay", "tool:pay"), validation.getValidator()); serviceScope.bind(context);
            var legacy = Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP, validation.getValidator());
            var denied = strict(legacy, service, serviceScope);
            assertFalse(denied.allowed()); assertEquals("TIANQUAN_JIANSHEN_USER_PRINCIPAL_REQUIRED", denied.reasonCode());
            verifyNoInteractions(loader);
            verify(observer, times(1)).authorizationDenied(same(((GatewayVerifiedIdentityBO) context.principal().trustedAuthenticationEvidence()).proof()), eq(context.traceId()));
        }
    }

    private McpAuthorizationRequest serviceRequest(Set<String> permissions, Instant issuedAt, Instant expiresAt) {
        return new McpAuthorizationRequest("https://tianquan-shoubing.internal", "finance-service", "1001", "finance-service", "service-token-1",
                "https://resource.egon.top/yuheng-mcp", issuedAt, expiresAt, permissions, 0, 0, 0, "controlled-service-bearer");
    }

    private java.util.List<top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO> serviceBindings(String resourceUri) {
        return java.util.List.of(new top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO(java.net.URI.create(resourceUri),
                        "mcp:billing:tool:pay_invoice:call", Set.of("tool:pay")),
                new top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO(java.net.URI.create(resourceUri), "invoice:pay", Set.of("invoice:pay")));
    }

    private GatewayAuthContext serviceContext(McpAuthorizationRequest request, Set<String> scopes, jakarta.validation.Validator validator) {
        var principal = new top.egon.cola.platform.tianquan.shoubing.contract.ServiceIdentityPrincipal(request.subjectId(), request.tenantId(), request.clientId(), request.tokenId(),
                java.net.URI.create(request.resourceUri()), 1, scopes, "finance", "finance-service", "test", "credential-1", request.issuedAt(), request.expiresAt());
        var proof = new top.egon.cola.platform.tianquan.shoubing.starter.security.domain.TokenAuditProofBO(java.net.URI.create(request.issuer()),
                principal.resourceUri(), null, "a".repeat(64), "b".repeat(64));
        var context = new GatewayAuthContext(top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC,
                top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol.HTTP, "yuheng.mcp.billing", null, "published-policy", "/mcp/billing", "POST", Set.of("bearer"),
                top.egon.cola.component.yuheng.core.context.GatewayPrincipal.anonymous(), "127.0.0.1", "service-trace", "service-request", NOW.plusSeconds(30), "release-1",
                Map.of("tianquan-shoubing.resource-uri", request.resourceUri()), Set.of("yuheng:read"));
        var provider = new top.egon.cola.platform.tianquan.shoubing.yuheng.security.IdpIdentityAuthenticationProvider((ignored, token) -> {
            assertEquals(request.userAccessToken(), token);
            return new top.egon.cola.platform.tianquan.shoubing.yuheng.security.IdpGatewayJwtVerifier.Verification(principal,
                    top.egon.cola.component.yuheng.core.security.AuthenticationFailure.NONE, null, scopes, proof);
        }, validator);
        var decision = Mono.from(provider.authenticate(context, new top.egon.cola.component.yuheng.core.security.GatewayCredential("bearer", request.userAccessToken(), Map.of())))
                .block(Duration.ofSeconds(5));
        assertEquals(top.egon.cola.component.yuheng.core.security.SecurityDecision.ALLOW, decision.decision());
        return context.withPrincipal(decision.principal());
    }

    private Rbac3McpAuthorizationAdapter configured(IdpObservedVerificationComponent observer, jakarta.validation.Validator validator,
            java.util.function.Function<String, SystemAuthorizationSnapshot> fetcher) {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var cache = new AuthorizationSnapshotCache(new InMemoryStore(), clock, Duration.ofSeconds(5));
        var loader = new SingleFlightSnapshotLoader(cache, (code, principal) -> fetcher.apply(code), "yuheng-mcp", Duration.ofMinutes(5), clock);
        return Rbac3McpAuthorizationAdapter.createConfigured(loader, observer, IdpSecurityMetricsRecorder.NOOP, validator);
    }

    private McpAuthorizationPort.Decision strict(Rbac3McpAuthorizationAdapter adapter, McpAuthorizationRequest request,
            McpRequestObservationComponent scope) {
        return Mono.from(adapter.authorize(request)).contextWrite(c -> c.put(McpRequestObservationComponent.class, scope)
                .put(GatewayAuthContext.class, scope.authorizationScope())).block(Duration.ofSeconds(5));
    }

    private McpAuthorizationRequest bearer(McpAuthorizationRequest source) {
        return new McpAuthorizationRequest(source.issuer(), source.subjectId(), source.tenantId(), source.clientId(), source.tokenId(),
                source.resourceUri(), source.issuedAt(), source.expiresAt(), source.requiredPermissions(), source.minimumAuthVersion(),
                source.minimumContextVersion(), source.minimumPolicyVersion(), "controlled-original-bearer");
    }

    @Test
    void loadsDownstreamSnapshotAndEnforcesPermissionAndVersionFence() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AtomicInteger fetches = new AtomicInteger();
        AuthorizationSnapshotCache cache = new AuthorizationSnapshotCache(
                new InMemoryStore(),
                clock,
                Duration.ofSeconds(5)
        );
        SingleFlightSnapshotLoader loader = new SingleFlightSnapshotLoader(
                cache,
                (systemCode, principal) -> {
                    fetches.incrementAndGet();
                    return snapshot(systemCode);
                },
                "yuheng-mcp",
                Duration.ofMinutes(5),
                clock
        );
        Rbac3McpAuthorizationAdapter adapter =
                new Rbac3McpAuthorizationAdapter(loader);

        McpAuthorizationPort.Decision allowed = authorize(
                adapter,
                request(Set.of(
                        "mcp:billing:tool:pay_invoice:call",
                        "invoice:pay"
                ), 7L, 3L, 3L)
        );
        McpAuthorizationPort.Decision missingPermission = authorize(
                adapter,
                request(Set.of("invoice:refund"), 7L, 3L, 3L)
        );
        McpAuthorizationPort.Decision staleSnapshot = authorize(
                adapter,
                request(Set.of("invoice:pay"), 8L, 3L, 3L)
        );

        assertTrue(allowed.allowed());
        assertEquals(7L, allowed.authVersion());
        assertFalse(missingPermission.allowed());
        assertEquals("TIANQUAN_JIANSHEN_PERMISSION_DENIED", missingPermission.reasonCode());
        assertFalse(staleSnapshot.allowed());
        assertEquals("TIANQUAN_JIANSHEN_SNAPSHOT_FENCED", staleSnapshot.reasonCode());
        assertEquals(3, fetches.get());
    }

    private McpAuthorizationPort.Decision authorize(
            Rbac3McpAuthorizationAdapter adapter,
            McpAuthorizationRequest request) {
        return Mono.from(adapter.authorize(bearer(request))).block();
    }

    private McpAuthorizationRequest request(
            Set<String> permissions,
            long minimumAuthVersion,
            long minimumContextVersion,
            long minimumPolicyVersion) {
        return new McpAuthorizationRequest(
                "https://tianquan-shoubing.internal",
                "alice-sub",
                "1001",
                "finance-web",
                "token-1",
                "https://resource.egon.top/yuheng-mcp",
                NOW.minusSeconds(30),
                NOW.plusSeconds(300),
                permissions,
                minimumAuthVersion,
                minimumContextVersion,
                minimumPolicyVersion
        );
    }

    private SystemAuthorizationSnapshot snapshot(String systemCode) {
        Set<String> permissions=Set.of("mcp:billing:tool:pay_invoice:call","invoice:pay");
        var scope=new top.egon.cola.platform.tianquan.jianshen.contract.permission.CatalogScope("10","1001","xingyuan",systemCode,"default","test");
        var view=new top.egon.cola.platform.tianquan.jianshen.contract.permission.EffectivePermissionSnapshot("https://tianquan-shoubing.internal","alice-sub",scope,"1","7","3",
                new top.egon.cola.platform.tianquan.jianshen.contract.permission.EffectivePermissionSnapshot.ActiveRoleContext(systemCode,java.util.List.of("50"),java.util.List.of("50")),
                permissions.stream().sorted().toList(),NOW.plusSeconds(300));
        return new SystemAuthorizationSnapshot("1001","alice-sub","1",systemCode,7L,3L,java.util.List.of("50"),null,null,
                permissions,Set.of(),Map.of(),Map.of(),"snapshot-checksum",NOW,NOW.plusSeconds(300),view,
                new top.egon.cola.platform.tianquan.jianshen.contract.authorization.RuntimePolicyBinding("yuheng-mcp-java","1","a".repeat(64),"b".repeat(64),"c".repeat(64)));
    }

    private static final class InMemoryStore
            implements AuthorizationSnapshotCache.SnapshotStore {

        private final Map<AuthorizationSnapshotCache.Key,
                SystemAuthorizationSnapshot> values = new ConcurrentHashMap<>();

        @Override
        public Optional<SystemAuthorizationSnapshot> get(
                AuthorizationSnapshotCache.Key key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void put(
                AuthorizationSnapshotCache.Key key,
                SystemAuthorizationSnapshot snapshot,
                Duration ttl) {
            values.put(key, snapshot);
        }

        @Override
        public void invalidate(AuthorizationSnapshotCache.Key key) {
            values.remove(key);
        }

        @Override
        public void invalidateUser(
                String systemCode,
                String tenantId,
                String identitySub) {
            values.entrySet().removeIf(entry ->
                    entry.getKey().systemCode().equals(systemCode)
                            && entry.getKey().tenantId().equals(tenantId)
                            && entry.getValue().identitySub().equals(
                            identitySub
                    ));
        }

        @Override
        public void invalidateTenant(
                String systemCode,
                String tenantId) {
            values.keySet().removeIf(key ->
                    key.systemCode().equals(systemCode)
                            && key.tenantId().equals(tenantId)
            );
        }
    }
}
