package top.egon.cola.component.yuheng.edge.service.impl;

import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedAccessBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedRefreshProofBO;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRefreshOperationRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;
import top.egon.cola.platform.tianquan.shoubing.contract.PrincipalType;
import top.egon.cola.platform.tianquan.shoubing.contract.state.IdentitySecurityStateRequest;
import top.egon.cola.platform.tianquan.shoubing.contract.state.IdentitySecurityStateResponse;
import top.egon.cola.platform.tianquan.shoubing.contract.state.IdentityStatePurpose;
import top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditEvent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.VerifiedJwtBO;

@Service
public class EdgeIdentityStateService {
    public static final String ACCESS_ATTRIBUTE = EdgeIdentityStateService.class.getName() + ".access";
    public static final String REFRESH_PROOF_ATTRIBUTE = EdgeIdentityStateService.class.getName() + ".refreshProof";
    private final TenantIssuerAuthenticationManagerComponent verifier;
    private final EdgeIdentityAuthorityRegistry authorities;
    private final EdgeOAuthApplicationCatalog catalog;
    private final EdgeRevocationIntentRepository revocations;
    private final EdgeRefreshOperationRepository refreshOperations;
    private final EdgeOAuthStateCipher references;
    private final Scheduler scheduler;
    private final Clock clock;

    public EdgeIdentityStateService(TenantIssuerAuthenticationManagerComponent verifier,
            EdgeIdentityAuthorityRegistry authorities, EdgeOAuthApplicationCatalog catalog,
            EdgeRevocationIntentRepository revocations, EdgeRefreshOperationRepository refreshOperations,
            EdgeOAuthStateCipher references, @Qualifier("edgeOAuthAuthorityScheduler") Scheduler scheduler,
            @Qualifier("edgeClock") Clock clock) {
        this.verifier = verifier;
        this.authorities = authorities;
        this.catalog = catalog;
        this.revocations = revocations;
        this.refreshOperations = refreshOperations;
        this.references = references;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public Mono<EdgeVerifiedAccessBO> access(String alias, String token, boolean browser) {
        URI resource = catalog.resource(alias).getResourceUri();
        return Mono.fromCallable(() -> verifier.authenticate(token, resource)).subscribeOn(scheduler)
                .flatMap(proof -> browserTarget(alias, proof, browser).flatMap(target -> {
                    String clientId = proof.getJwt().getClaimAsString("client_id");
                    if (clientId == null || clientId.isBlank()) return Mono.error(invalid());
                    var request = IdentitySecurityStateRequest.builder().purpose(IdentityStatePurpose.ACCESS_VALIDATION)
                            .accessToken(token).expectedResource(resource).expectedClientId(clientId).build();
                    return authorities.forResource(proof.getTrustedIssuer().issuer(), resource).resolve(request)
                            .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_IDENTITY_AUTHORITY_UNAVAILABLE"))).flatMap(state -> {
                        requireAccessState(state, proof, resource, clientId);
                        Mono<Boolean> closed = target.isPresent() ? revocations.isClosed(target.orElseThrow(), proof.getAuthorizationId())
                                : Mono.just(false);
                        return closed.flatMap(denied -> denied ? Mono.error(invalid())
                                : audit(proof, resource).thenReturn(new EdgeVerifiedAccessBO(alias, resource, proof, state, target.orElse(null))));
                    });
                }));
    }

    public Mono<EdgeVerifiedRefreshProofBO> refreshProof(String alias, String accessToken, String browserBinding) {
        if (browserBinding == null || !browserBinding.matches("[A-Za-z0-9_-]{43}")) return Mono.error(invalid());
        return Mono.defer(() -> catalog.application(alias, unverifiedIssuer(accessToken)))
                .flatMap(application -> Mono.fromCallable(() -> verifier.verifyRefreshContextProof(accessToken,
                        URI.create(application.target().resource()), application.target().clientId())).subscribeOn(scheduler)
                        .map(proof -> {
                            if (!proof.issuer().toASCIIString().equals(application.target().issuer())
                                    || !proof.tenantId().equals(application.target().tenantId())) throw invalid();
                            var context = new EdgeRefreshContextBO(application.target(), proof.subject(), proof.authorizationId(),
                                    proof.authenticationJti(), Long.parseLong(proof.refreshGeneration()), proof.authorizationExpiresAt(),
                                    proof.authenticationExpiresAt(), EdgeOAuthStateDigests.sha256(browserBinding));
                            return new EdgeVerifiedRefreshProofBO(application, context, proof, accessToken);
                        }));
    }

    public Mono<Void> requireCurrentRefresh(EdgeVerifiedRefreshProofBO proof, String refreshToken) {
        var context = proof.context();
        var request = IdentitySecurityStateRequest.builder().purpose(IdentityStatePurpose.REFRESH_CONTEXT)
                .accessToken(proof.accessToken()).expectedResource(URI.create(context.target().resource()))
                .expectedClientId(context.target().clientId()).refreshTokenDigest(EdgeOAuthStateDigests.sha256(refreshToken))
                .browserBindingDigest(context.browserBindingDigest()).build();
        return authorities.forResource(proof.proof().issuer(), URI.create(proof.context().target().resource())).resolve(request)
                .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_IDENTITY_AUTHORITY_UNAVAILABLE"))).flatMap(state -> {
            if (state == null || state.getPurpose() != IdentityStatePurpose.REFRESH_CONTEXT || !state.isEligible()
                    || !context.target().issuer().equals(state.getIssuer()) || !context.target().resource().equals(state.getResource())
                    || !context.target().clientId().equals(state.getClientId()) || !context.subject().equals(state.getSubject())
                    || !context.authorizationId().equals(state.getAuthorizationId()) || !context.authenticationJti().equals(state.getAuthenticationJti())
                    || !Long.toString(context.generation()).equals(state.getRefreshGeneration())
                    || !Objects.equals(context.authorizationExpiresAt().getEpochSecond(), state.getAuthorizationExpiresAt())
                    || !Objects.equals(context.authenticationExpiresAt().getEpochSecond(), state.getAuthenticationExpiresAt())) {
                return Mono.error(new EdgeOAuthStateException("EDGE_REFRESH_CONTEXT_REJECTED"));
            }
            return Mono.empty();
        });
    }

    /** A bound spent credential can obtain a CSRF proof or close its family, never a new token. */
    public Mono<Void> requireBoundCredential(EdgeVerifiedRefreshProofBO proof, String refreshToken) {
        return refreshOperations.hasBoundCredential(proof.context(), EdgeOAuthStateDigests.sha256(refreshToken))
                .defaultIfEmpty(false)
                .flatMap(bound -> bound ? Mono.empty() : requireCurrentRefresh(proof, refreshToken));
    }

    public EdgeRefreshContextBO issuedContext(EdgeVerifiedAccessBO access, String browserBinding) {
        var jwt = access.proof().getJwt();
        var state = access.state();
        String generation = jwt.getClaimAsString("refresh_generation");
        if (access.browserTarget() == null || browserBinding == null || !browserBinding.matches("[A-Za-z0-9_-]{43}")
                || generation == null || !generation.matches("0|[1-9][0-9]{0,18}")
                || !generation.equals(state.getRefreshGeneration())
                || !(jwt.getClaims().get("authorization_expires_at") instanceof Number authorizationExpiry)
                || !(jwt.getClaims().get("authn_expires_at") instanceof Number authenticationExpiry)
                || !Objects.equals(authorizationExpiry.longValue(), state.getAuthorizationExpiresAt())
                || !Objects.equals(authenticationExpiry.longValue(), state.getAuthenticationExpiresAt())) throw invalid();
        return new EdgeRefreshContextBO(access.browserTarget(), access.proof().getSubject(), access.proof().getAuthorizationId(),
                state.getAuthenticationJti(), Long.parseLong(generation), Instant.ofEpochSecond(state.getAuthorizationExpiresAt()),
                Instant.ofEpochSecond(state.getAuthenticationExpiresAt()), EdgeOAuthStateDigests.sha256(browserBinding));
    }

    private Mono<Optional<EdgeOAuthTargetBO>> browserTarget(String alias, VerifiedJwtBO proof, boolean browser) {
        String issuer = proof.getTrustedIssuer().issuer().toASCIIString();
        String clientId = proof.getJwt().getClaimAsString("client_id");
        if (browser && proof.getPrincipalType() != PrincipalType.USER) return Mono.error(invalid());
        if (proof.getPrincipalType() != PrincipalType.USER || !catalog.resource(alias).getIssuerRegistrationIds().containsKey(issuer)) {
            return browser ? Mono.error(invalid()) : Mono.just(Optional.empty());
        }
        return catalog.application(alias, issuer).flatMap(application -> {
            if (!application.target().clientId().equals(clientId)) return browser ? Mono.error(invalid()) : Mono.just(Optional.empty());
            return Mono.just(Optional.of(application.target()));
        });
    }

    private static void requireAccessState(IdentitySecurityStateResponse state, VerifiedJwtBO proof, URI resource, String clientId) {
        if (state == null || state.getPurpose() != IdentityStatePurpose.ACCESS_VALIDATION || !state.isEligible()
                || !proof.getTrustedIssuer().issuer().toASCIIString().equals(state.getIssuer())
                || !resource.toASCIIString().equals(state.getResource()) || !clientId.equals(state.getClientId())
                || !proof.getSubject().equals(state.getSubject()) || !proof.getAuthorizationId().equals(state.getAuthorizationId())
                || !Objects.equals(proof.getSecurityVersion(), state.getSecurityVersion())) throw invalid();
        if (proof.getPrincipalType() == PrincipalType.USER
                && (!Objects.equals(proof.getJwt().getClaimAsString("authn_jti"), state.getAuthenticationJti())
                    || state.getAuthorizationExpiresAt() == null || state.getAuthenticationExpiresAt() == null)) throw invalid();
    }

    private Mono<Void> audit(VerifiedJwtBO proof, URI resource) {
        var event = new TokenUsageAuditEvent(UUID.randomUUID().toString(), 1, proof.getTrustedIssuer().issuer(),
                resource.toASCIIString(), EdgeOAuthStateDigests.sha256(proof.getSubject()),
                EdgeOAuthStateDigests.sha256(proof.getJwt().getClaimAsString("client_id")),
                references.auditReference(proof.getTrustedIssuer().issuer().toASCIIString(), proof.getTokenId()),
                "SUCCESS", "EDGE_ACCESS_VERIFIED", Instant.now(clock), null);
        return authorities.forResource(proof.getTrustedIssuer().issuer(), resource).appendAudit(event)
                .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_AUDIT_UNCONFIRMED")))
                .flatMap(receipt -> "STORED".equals(receipt.outcome()) && event.eventId().equals(receipt.eventId())
                        && event.resource().equals(receipt.resource())
                        && top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditFingerprint.of(event).equals(receipt.eventFingerprint())
                        && receipt.confirmedAt() != null ? Mono.<Void>empty()
                        : Mono.error(new EdgeOAuthStateException("EDGE_AUDIT_UNCONFIRMED")));
    }

    public Mono<Void> auditRefreshProof(EdgeVerifiedRefreshProofBO proof, String reasonCode) {
        return Mono.defer(() -> {
            String tokenId;
            try { tokenId = SignedJWT.parse(proof.accessToken()).getJWTClaimsSet().getJWTID(); }
            catch (ParseException invalid) { return Mono.error(invalid()); }
            if (tokenId == null) return Mono.error(invalid());
            var context = proof.context();
            var event = new TokenUsageAuditEvent(UUID.randomUUID().toString(), 1, proof.proof().issuer(), context.target().resource(),
                    EdgeOAuthStateDigests.sha256(context.subject()), EdgeOAuthStateDigests.sha256(context.target().clientId()),
                    references.auditReference(context.target().issuer(), tokenId), "SUCCESS", reasonCode, clock.instant(), null);
            return authorities.forResource(proof.proof().issuer(), URI.create(proof.context().target().resource())).appendAudit(event)
                    .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_AUDIT_UNCONFIRMED")))
                    .flatMap(receipt -> "STORED".equals(receipt.outcome()) && event.eventId().equals(receipt.eventId())
                            && event.resource().equals(receipt.resource())
                            && top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditFingerprint.of(event).equals(receipt.eventFingerprint())
                            && receipt.confirmedAt() != null ? Mono.<Void>empty()
                            : Mono.error(new EdgeOAuthStateException("EDGE_AUDIT_UNCONFIRMED")));
        });
    }

    private static String unverifiedIssuer(String token) {
        if (token == null || token.length() > 16384) throw invalid();
        try {
            String issuer = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
            if (issuer == null) throw invalid();
            return issuer;
        }
        catch (ParseException malformed) { throw invalid(); }
    }

    private static EdgeOAuthStateException invalid() { return new EdgeOAuthStateException("EDGE_ACCESS_REJECTED"); }
}
