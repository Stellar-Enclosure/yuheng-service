package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import jakarta.validation.Validator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.platform.tianquan.shoubing.contract.IdpPrincipal;
import top.egon.cola.platform.tianquan.shoubing.contract.IdentityPrincipal;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceIdentityPrincipal;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.security.AccessTokenVerification;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpJwtVerifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.VerifiedPrincipalConverter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.TokenAuditProofBO;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.VerifiedJwtBO;
import top.egon.cola.platform.tianquan.shoubing.starter.security.impl.DatabaseOAuthRevocationReader;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityAuthorityClientResolver;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/** Native MCP resource-server boundary. A request selects neither an issuer nor an authority destination. */
@Slf4j
public final class McpGatewayIdentityAuthenticator implements McpEngineHttpHandler.IdentityAuthenticator {
    public static final String VERIFIED_CONTEXT_ATTRIBUTE = McpGatewayIdentityAuthenticator.class.getName() + ".verified-context";
    private final TenantIssuerAuthenticationManagerComponent issuers;
    private final IdentityAuthorityClientResolver authorities;
    private final VerifiedPrincipalConverter converter;
    private final IdpObservedVerificationComponent observations;
    private final Validator validator;
    private final BiFunction<McpRuntimeServer, ContextView, Set<String>> requiredScopes;
    private final Clock clock;
    private final String identityTenantId;

    public McpGatewayIdentityAuthenticator(TenantIssuerAuthenticationManagerComponent issuers,
            IdentityAuthorityClientResolver authorities, VerifiedPrincipalConverter converter,
            IdpObservedVerificationComponent observations, Validator validator,
            BiFunction<McpRuntimeServer, ContextView, Set<String>> requiredScopes, Clock clock, String identityTenantId) {
        this.issuers = Objects.requireNonNull(issuers, "issuers");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.requiredScopes = Objects.requireNonNull(requiredScopes, "requiredScopes");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (identityTenantId == null || !identityTenantId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("MCP identity tenant is required");
        try { Long.parseLong(identityTenantId); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("MCP identity tenant is invalid"); }
        this.identityTenantId = identityTenantId;
    }

    @Override
    public Mono<Map<String, Object>> authenticate(McpHttpRequest request, McpRuntimeServer server) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(server, "server");
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
            var observation = observation(context);
            final Set<String> scopes;
            try { scopes = Set.copyOf(requiredScopes.apply(server, context)); }
            catch (RuntimeException unavailable) { throw observation.unavailable(unavailable); }
            var identity = verify(request, URI.create(server.resourceUri()), scopes, server, context);
            var attributes = new LinkedHashMap<>(identity.attributes());
            attributes.put(VERIFIED_CONTEXT_ATTRIBUTE, identity);
            return Map.copyOf(attributes);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** Authenticates only the deployment-selected adapter-management audience and USER identity. */
    public Mono<VerifiedManagementIdentityVO> authenticateManagement(McpHttpRequest request, URI configuredCanonicalAdapterResource,
            Set<String> requiredScopes) {
        Objects.requireNonNull(request, "request");
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
            var identity = verify(request, configuredCanonicalAdapterResource, requiredScopes, null, context);
            if (!(identity.principal() instanceof IdentityPrincipal)) {
                identity.owner().confirmDenial();
                throw SecurityFailure.forbidden();
            }
            var management = new VerifiedManagementIdentityVO(identity);
            management.requireCurrent(clock);
            return management;
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private McpRequestObservationComponent observation(ContextView context) {
        McpRequestObservationComponent observation = context.getOrDefault(McpRequestObservationComponent.class, null);
        if (observation == null || !observations.enabled()) throw SecurityFailure.unavailable();
        observation.requireAvailable();
        return observation;
    }

    private VerifiedIdentityVO verify(McpHttpRequest request, URI configuredResource, Set<String> configuredScopes,
            McpRuntimeServer server, ContextView context) {
        McpRequestObservationComponent observation = observation(context);
        final Set<String> scopes;
        final URI resource;
        try {
            scopes = Set.copyOf(configuredScopes);
            if (scopes.isEmpty() || scopes.size() > 64 || scopes.stream().anyMatch(scope -> !scope.matches("[A-Za-z0-9:._-]{1,128}"))) {
                throw SecurityFailure.unavailable();
            }
            resource = Objects.requireNonNull(configuredResource, "configuredResource");
            if (!"https".equals(resource.getScheme()) || resource.getHost() == null || resource.getRawUserInfo() != null
                    || resource.getRawQuery() != null || resource.getRawFragment() != null
                    || !resource.equals(resource.normalize()) || !resource.toASCIIString().equals(resource.toString())) {
                throw SecurityFailure.unavailable();
            }
        } catch (RuntimeException invalidConfiguration) {
            throw observation.unavailable(invalidConfiguration);
        }
        String trace = trace(request);
        String credential = request.header("authorization");
        if (credential == null || !credential.matches("(?i)Bearer [^\\s,]+")) {
            var invalid = observations.<IdpPrincipal>observe(capture -> new AccessTokenVerification.Invalid<>("AUTHORIZATION_HEADER_INVALID"), resource, trace);
            if (invalid instanceof AccessTokenVerification.Unavailable<?>) throw observation.unavailable(null);
            throw SecurityFailure.unauthenticated();
        }
        String bearer = credential.substring(7);
        AtomicReference<VerifiedJwtBO> captured = new AtomicReference<>();
        final AccessTokenVerification<IdpPrincipal> result;
        try {
            var verifier = IdpJwtVerifier.createDatabaseConfigured(resource, clock, issuers,
                    new DatabaseOAuthRevocationReader(authorities, resource), converter, observations);
            // The capture overload does not observe. This outer call owns the one physical authentication handoff.
            result = observations.observe(capture -> verifier.verifyAny(bearer, verified -> {
                captured.set(verified);
                capture.accept(verified);
            }), resource, trace);
            if (result instanceof AccessTokenVerification.Unavailable<?>) throw observation.unavailable(null);
            if (!(result instanceof AccessTokenVerification.Valid<IdpPrincipal> valid)) throw SecurityFailure.unauthenticated();
            VerifiedJwtBO verified = captured.get();
            if (verified == null || valid.proof() == null || valid.verifiedIssuer() == null) throw observation.unavailable(null);
            new ValidationUtils(validator).validate(valid.proof());
            String clientId = verified.getJwt().getClaimAsString("client_id");
            VerifiedIdentityVO identity = new VerifiedIdentityVO(observation, server == null ? null : server.serverId(), server == null ? null : server.serverCode(),
                    valid.principal(), required(clientId), valid.verifiedIssuer(), resource, Set.copyOf(valid.scopes()),
                    scopes, valid.proof(), observations, bearer, trace);
            observation.bind(identity);
            try {
                identity.requireCurrent(clock);
                if (!identityTenantId.equals(identity.tenantId()) || valid.principal() instanceof ServiceIdentityPrincipal service
                        && service.scopeContext() != ServiceTokenContext.TENANT) {
                    throw SecurityFailure.forbidden();
                }
            } catch (SecurityFailure failure) {
                if (failure.httpStatus() == 403) observation.confirmDenial();
                throw failure;
            }
            return identity;
        } catch (SecurityFailure failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw observation.unavailable(unavailable);
        } finally {
            captured.set(null);
        }
    }

    private static String trace(McpHttpRequest request) {
        String value = request.header("x-egon-request-id");
        return value != null && value.matches("[A-Za-z0-9._~-]{1,128}") ? value : UUID.randomUUID().toString();
    }
    private static String required(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) throw SecurityFailure.unavailable();
        return value;
    }

    /** Only the native verifier above can create this non-serializable, request-owned evidence. */
    @JsonIgnoreType
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    @ToString(onlyExplicitlyIncluded = true)
    public static final class VerifiedIdentityVO {
        private final McpRequestObservationComponent owner;
        private final String serverId;
        @ToString.Include private final String serverCode;
        private final IdpPrincipal principal;
        private final String clientId;
        @ToString.Include private final URI issuer;
        @ToString.Include private final URI resource;
        private final Set<String> scopes;
        private final Set<String> requiredScopes;
        private final TokenAuditProofBO proof;
        @Getter(AccessLevel.NONE) private final IdpObservedVerificationComponent observations;
        @Getter(AccessLevel.NONE) private final String bearer;
        private final String trace;

        public String subject() { return principal instanceof IdentityPrincipal user ? user.subject() : ((ServiceIdentityPrincipal) principal).subject(); }
        public String tenantId() { return principal instanceof IdentityPrincipal user ? user.tenantId() : ((ServiceIdentityPrincipal) principal).tenantId(); }
        public String tokenId() { return principal instanceof IdentityPrincipal user ? user.tokenId() : ((ServiceIdentityPrincipal) principal).tokenId(); }
        public Instant issuedAt() { return principal instanceof IdentityPrincipal user ? user.issuedAt() : ((ServiceIdentityPrincipal) principal).issuedAt(); }
        public Instant expiresAt() { return principal instanceof IdentityPrincipal user ? user.expiresAt() : ((ServiceIdentityPrincipal) principal).expiresAt(); }
        public String userTokenForAuthorization() {
            owner.requireAvailable();
            if (owner.verifiedIdentity() != this) throw owner.unavailable(null);
            return principal instanceof IdentityPrincipal ? bearer : null;
        }
        boolean belongsTo(McpRequestObservationComponent value) { return owner == value; }
        boolean observeDenied() { return observations.enabled() && observations.authorizationDenied(proof, trace); }

        public void requireCurrent(Clock clock) {
            owner.requireAvailable();
            if (owner.verifiedIdentity() != this) throw owner.unavailable(null);
            if (!(principal instanceof IdentityPrincipal) && !(principal instanceof ServiceIdentityPrincipal)) throw SecurityFailure.unavailable();
            if (!issuer.equals(proof.issuer()) || !resource.equals(proof.resource())) throw SecurityFailure.unavailable();
            if (!expiresAt().isAfter(clock.instant()) || issuedAt().isAfter(clock.instant())) throw SecurityFailure.unauthenticated();
            if (tenantId() == null || tenantId().isBlank() || !scopes.containsAll(requiredScopes)) throw SecurityFailure.forbidden();
            if (principal instanceof IdentityPrincipal user && !user.audience().contains(resource.toASCIIString())) throw SecurityFailure.unavailable();
            if (principal instanceof ServiceIdentityPrincipal service && (!resource.equals(service.resourceUri())
                    || !clientId.equals(service.clientId()) || !service.scopes().equals(scopes))) throw SecurityFailure.unavailable();
        }

        Map<String, Object> attributes() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("identity.issuer", issuer.toASCIIString());
            values.put("identity.subject", subject()); values.put("callerId", subject());
            values.put("identity.tenant-id", tenantId()); values.put("tenantId", tenantId());
            values.put("identity.client-id", clientId); values.put("tianquan-shoubing.client-id", clientId);
            values.put("identity.token-id", tokenId()); values.put("tianquan-shoubing.token-id", tokenId());
            values.put("identity.resource-uri", resource.toASCIIString());
            values.put("identity.issued-at", issuedAt().toString()); values.put("identity.expires-at", expiresAt().toString());
            values.put("identity.principal-type", principal.principalType().name());
            return Map.copyOf(values);
        }
    }


    /** A verifier-owned management proof; it cannot be created by binding HTTP attributes. */
    @JsonIgnoreType
    @ToString(onlyExplicitlyIncluded = true)
    public static final class VerifiedManagementIdentityVO {
        private final VerifiedIdentityVO identity;
        private VerifiedManagementIdentityVO(VerifiedIdentityVO identity) { this.identity = identity; }
        public IdentityPrincipal principal() { return (IdentityPrincipal) identity.principal(); }
        public String clientId() { return identity.clientId(); }
        @ToString.Include public URI issuer() { return identity.issuer(); }
        @ToString.Include public URI resource() { return identity.resource(); }
        public Set<String> scopes() { return identity.scopes(); }
        public TokenAuditProofBO proof() { return identity.proof(); }
        /** Only a same-authorized Jianshen policy request may consume this transient credential. */
        public String userTokenForAuthorization() { return identity.userTokenForAuthorization(); }
        public void requireCurrent(Clock clock) {
            identity.owner().requireAvailable();
            if (identity.owner().verifiedIdentity() != identity || !(identity.principal() instanceof IdentityPrincipal)) {
                throw identity.owner().unavailable(null);
            }
            identity.requireCurrent(Objects.requireNonNull(clock, "clock"));
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class SecurityFailure extends RuntimeException {
        private final int httpStatus;
        private final String code;
        private final Map<String, java.util.List<String>> responseHeaders;
        private SecurityFailure(int status, String code) {
            super(code);
            this.httpStatus = status;
            this.code = code;
            this.responseHeaders = status == 401 ? Map.of("www-authenticate", java.util.List.of("Bearer")) : Map.of();
        }
        public static SecurityFailure unauthenticated() { return new SecurityFailure(401, "MCP_UNAUTHENTICATED"); }
        public static SecurityFailure forbidden() { return new SecurityFailure(403, "MCP_FORBIDDEN"); }
        public static SecurityFailure unavailable() { return new SecurityFailure(503, "MCP_SECURITY_UNAVAILABLE"); }
    }
}
