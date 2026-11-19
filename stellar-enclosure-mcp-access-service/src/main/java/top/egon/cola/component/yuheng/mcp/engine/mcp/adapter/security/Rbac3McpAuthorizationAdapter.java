package top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security;

import jakarta.validation.Validator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationRequest;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.SecurityFailure;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedIdentityVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponent;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient;
import top.egon.cola.platform.tianquan.shoubing.contract.IdentityPrincipal;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceIdentityPrincipal;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;
import top.egon.cola.platform.tianquan.shoubing.core.observability.SecurityMetricObservationBO;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authorizes the native verified USER against current Jianshen SQL, or an exact deployment SERVICE scope binding. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Rbac3McpAuthorizationAdapter implements McpAuthorizationPort {
    @Qualifier("singleFlightSnapshotLoader") private final SingleFlightSnapshotLoader snapshotLoader;
    @Qualifier("idpObservedVerificationComponent") private final IdpObservedVerificationComponent observations;
    @Qualifier("idpSecurityMetricsRecorder") private final IdpSecurityMetricsRecorder metrics;
    private final Map<List<String>, Set<String>> serviceScopeIndex;
    @Qualifier("gatewayClock") private final Clock clock;

    public static Rbac3McpAuthorizationAdapter createConfigured(SingleFlightSnapshotLoader snapshots,
            IdpObservedVerificationComponent observations, IdpSecurityMetricsRecorder metrics, Validator validator) {
        return createConfigured(snapshots, observations, metrics, validator, List.of(), Clock.systemUTC());
    }

    public static Rbac3McpAuthorizationAdapter createConfigured(SingleFlightSnapshotLoader snapshots,
            IdpObservedVerificationComponent observations, IdpSecurityMetricsRecorder metrics, Validator validator,
            List<ServiceScopeBindingVO> bindings, Clock clock) {
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.size() > 256) throw new IllegalArgumentException("MCP service scope bindings exceed maximum size");
        var validation = new ValidationUtils(Objects.requireNonNull(validator, "validator"));
        Map<List<String>, Set<String>> index = new HashMap<>();
        for (var binding : bindings) {
            validation.validate(Objects.requireNonNull(binding, "binding"));
            if (index.putIfAbsent(List.of(binding.resourceUri().toASCIIString(), binding.permission()), Set.copyOf(binding.scopes())) != null) {
                throw new IllegalArgumentException("MCP service scope binding must have a unique resource and permission");
            }
        }
        return new Rbac3McpAuthorizationAdapter(Objects.requireNonNull(snapshots, "snapshots"),
                Objects.requireNonNull(observations, "observations"), Objects.requireNonNull(metrics, "metrics"),
                Map.copyOf(index), Objects.requireNonNull(clock, "clock"));
    }

    @Override
    public Publisher<Decision> authorize(McpAuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.deferContextual(context -> {
            McpRequestObservationComponent observation = context.getOrDefault(McpRequestObservationComponent.class, null);
            if (observation == null) return Mono.error(SecurityFailure.unavailable());
            return Mono.fromCallable(() -> verified(request, observation)).flatMap(identity -> Mono.fromCallable(() -> {
                        if (identity.principal() instanceof ServiceIdentityPrincipal service) return decideService(request, identity, service);
                        if (!(identity.principal() instanceof IdentityPrincipal user)) return denied();
                        return decide(request, identity, snapshotLoader.load(user, identity.userTokenForAuthorization()));
                    }).onErrorResume(Rbac3AuthorizationClient.AuthorizationDeniedException.class, denied -> Mono.just(denied()))
                    .flatMap(decision -> {
                        observation.requireAvailable();
                        if (observation.verifiedIdentity() != identity) return Mono.error(observation.unavailable(null));
                        identity.requireCurrent(clock);
                        if (decision.allowed()) {
                            record("ALLOW");
                            return Mono.just(decision);
                        }
                        if ("TIANQUAN_JIANSHEN_SNAPSHOT_FENCED".equals(decision.reasonCode())) return Mono.error(observation.unavailable(null));
                        observation.confirmDenial();
                        record("DENY");
                        return Mono.just(decision);
                    }))
                    .onErrorMap(failure -> {
                        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                        record("INDETERMINATE");
                        return failure instanceof SecurityFailure security ? observation.fail(security) : observation.unavailable(failure);
                    }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    private VerifiedIdentityVO verified(McpAuthorizationRequest request, McpRequestObservationComponent observation) {
        var identity = observation.verifiedIdentity();
        identity.requireCurrent(clock);
        if (!observations.enabled() || !identity.issuer().toASCIIString().equals(request.issuer())
                || !identity.resource().toASCIIString().equals(request.resourceUri())
                || !identity.subject().equals(request.subjectId()) || !Objects.equals(identity.tenantId(), request.tenantId())
                || !identity.clientId().equals(request.clientId()) || !identity.tokenId().equals(request.tokenId())
                || !identity.issuedAt().equals(request.issuedAt()) || !identity.expiresAt().equals(request.expiresAt())
                || request.userAccessToken() != null && !request.userAccessToken().equals(identity.userTokenForAuthorization())) {
            throw observation.unavailable(null);
        }
        return identity;
    }

    private Decision decideService(McpAuthorizationRequest request, VerifiedIdentityVO identity, ServiceIdentityPrincipal service) {
        if (service.scopeContext() != ServiceTokenContext.TENANT || !service.clientId().equals(request.clientId())
                || request.requiredPermissions().size() > 256) return denied();
        if (request.minimumAuthVersion() != 0 || request.minimumContextVersion() != 0 || request.minimumPolicyVersion() != 0) {
            return Decision.denied("TIANQUAN_JIANSHEN_SNAPSHOT_FENCED", 0, 0, 0);
        }
        Set<String> required = new HashSet<>();
        for (String permission : request.requiredPermissions()) {
            var scopes = serviceScopeIndex.get(List.of(request.resourceUri(), permission));
            if (scopes == null || scopes.isEmpty()) return denied();
            required.addAll(scopes);
            if (required.size() > 64) return denied();
        }
        return identity.scopes().containsAll(required) ? Decision.allowed(0, 0, 0) : denied();
    }

    private Decision decide(McpAuthorizationRequest request, VerifiedIdentityVO identity, SystemAuthorizationSnapshot snapshot) {
        if (snapshot == null || snapshot.permissionSnapshot() == null || snapshot.policyBinding() == null
                || !identity.subject().equals(snapshot.identitySub()) || !identity.tenantId().equals(snapshot.tenantId())
                || !snapshot.expiresAt().isAfter(clock.instant()) || snapshot.expiresAt().isAfter(identity.expiresAt())) {
            throw SecurityFailure.unavailable();
        }
        if (snapshot.authVersion() < request.minimumAuthVersion() || snapshot.policyVersion() < request.minimumContextVersion()
                || snapshot.policyVersion() < request.minimumPolicyVersion()) {
            return Decision.denied("TIANQUAN_JIANSHEN_SNAPSHOT_FENCED", snapshot.authVersion(), snapshot.policyVersion(), snapshot.policyVersion());
        }
        return snapshot.permissions().containsAll(request.requiredPermissions())
                ? Decision.allowed(snapshot.authVersion(), snapshot.policyVersion(), snapshot.policyVersion())
                : Decision.denied("TIANQUAN_JIANSHEN_PERMISSION_DENIED", snapshot.authVersion(), snapshot.policyVersion(), snapshot.policyVersion());
    }

    private static Decision denied() { return Decision.denied("TIANQUAN_JIANSHEN_PERMISSION_DENIED", 0, 0, 0); }
    private void record(String result) {
        try { metrics.record(SecurityMetricObservationBO.builder().signal("rbac.authorization").result(result).build()); }
        catch (RuntimeException unavailable) { log.debug("MCP authorization metric unavailable; failureType={}", unavailable.getClass().getSimpleName()); }
    }
}
