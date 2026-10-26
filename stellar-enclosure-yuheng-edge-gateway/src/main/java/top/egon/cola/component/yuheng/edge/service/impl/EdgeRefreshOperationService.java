package top.egon.cola.component.yuheng.edge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.controller.domain.EdgeRefreshOperationResult;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshClaimResultBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshOperationBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeResourceRefreshTokenGrantRequest;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedRefreshProofBO;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRefreshOperationRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;

@Service
public class EdgeRefreshOperationService {
    private final EdgeRefreshOperationRepository operations;
    private final EdgeRevocationIntentRepository revocations;
    private final EdgeIdentityStateService identities;
    private final EdgeOAuthCookieService cookies;
    private final ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> tokens;
    private final ObjectMapper mapper;

    public EdgeRefreshOperationService(EdgeRefreshOperationRepository operations, EdgeRevocationIntentRepository revocations,
            EdgeIdentityStateService identities, EdgeOAuthCookieService cookies,
            @Qualifier("edgeRefreshTokenResponseClient") ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> tokens,
            @Qualifier("jacksonObjectMapper") ObjectMapper mapper) {
        this.operations = operations;
        this.revocations = revocations;
        this.identities = identities;
        this.cookies = cookies;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    public Mono<Void> refresh(ServerWebExchange exchange, UUID operationId, EdgeVerifiedRefreshProofBO proof) {
        String token = cookies.refresh(exchange, proof.context().target().appAlias())
                .orElseThrow(() -> new EdgeOAuthStateException("EDGE_REFRESH_REQUIRED"));
        return operations.inspect(operationId, proof.context(), token).flatMap(existing -> {
            if (existing.outcome() != EdgeRefreshClaimResultBO.Outcome.MISSING) return result(exchange, existing);
            return identities.requireCurrentRefresh(proof, token)
                    .then(identities.auditRefreshProof(proof, "EDGE_REFRESH_VERIFIED"))
                    .then(operations.claim(operationId, proof.context(), token))
                    .flatMap(claim -> claim.outcome() == EdgeRefreshClaimResultBO.Outcome.ACQUIRED
                            ? execute(exchange, proof, token, claim.operation()) : result(exchange, claim));
        });
    }

    private Mono<Void> execute(ServerWebExchange exchange, EdgeVerifiedRefreshProofBO proof, String oldRefresh,
                                EdgeRefreshOperationBO acquired) {
        return operations.dispatch(acquired).flatMap(dispatched -> {
            if (dispatched.isEmpty()) return Mono.error(new EdgeOAuthStateException("EDGE_REFRESH_CONTEXT_REJECTED"));
            var operation = dispatched.orElseThrow();
            var attempt = new Attempt(operation, proof, oldRefresh, new AtomicBoolean());
            return Mono.usingWhen(Mono.just(attempt), current -> exchange(exchange, current),
                    current -> current.completed().get() ? Mono.empty() : close(current),
                    (current, failure) -> close(current), this::close);
        });
    }

    private Mono<Void> exchange(ServerWebExchange exchange, Attempt attempt) {
        var proof = attempt.proof();
        String alias = proof.context().target().appAlias();
        var previousAccess = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, proof.accessToken(),
                proof.proof().accessIssuedAt(), proof.proof().accessExpiresAt());
        var request = new EdgeResourceRefreshTokenGrantRequest(proof.application().registration(), previousAccess,
                new OAuth2RefreshToken(attempt.oldRefresh(), proof.proof().accessIssuedAt()), URI.create(proof.context().target().resource()),
                proof.context().browserBindingDigest());
        return tokens.getTokenResponse(request).switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_REFRESH_RESPONSE_INVALID")))
                .flatMap(response -> {
                    if (response.getRefreshToken() == null || response.getAccessToken() == null
                            || !OAuth2AccessToken.TokenType.BEARER.equals(response.getAccessToken().getTokenType())
                            || EdgeOAuthStateDigests.equal(response.getRefreshToken().getTokenValue(), attempt.oldRefresh())) {
                        return Mono.error(new EdgeOAuthStateException("EDGE_REFRESH_RESPONSE_INVALID"));
                    }
                    String browser = cookies.browserBinding(exchange, alias)
                            .orElseThrow(() -> new EdgeOAuthStateException("EDGE_BROWSER_BINDING_INVALID"));
                    return identities.access(alias, response.getAccessToken().getTokenValue(), true)
                            .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_ACCESS_REJECTED"))).flatMap(access -> {
                        var next = identities.issuedContext(access, browser);
                        if (!successor(proof.context(), next)) return Mono.error(new EdgeOAuthStateException("EDGE_REFRESH_IDENTITY_MISMATCH"));
                        var prepared = cookies.prepareCredentials(alias, response.getAccessToken().getTokenValue(),
                                response.getRefreshToken().getTokenValue(), browser, next.authorizationExpiresAt());
                        var result = new EdgeRefreshOperationResult(attempt.operation().operationId().toString(), "SUCCEEDED",
                                access.proof().getExpiresAt().getEpochSecond(), next.authorizationExpiresAt().getEpochSecond(), null);
                        byte[] body = json(result);
                        return operations.complete(attempt.operation(), next.generation(), access.proof().getExpiresAt()).flatMap(committed -> {
                            if (!committed) return Mono.error(new EdgeOAuthStateException("EDGE_REFRESH_RESPONSE_UNCONFIRMED"));
                            cookies.writePrepared(exchange, prepared);
                            return write(exchange, HttpStatus.OK, body).doOnSuccess(ignored -> attempt.completed().set(true));
                        });
                    });
                });
    }

    private Mono<Void> close(Attempt attempt) {
        return operations.closeUnknown(attempt.operation(), "EDGE_REFRESH_RESPONSE_UNCONFIRMED")
                .flatMap(closed -> closed ? Mono.empty()
                        : revocations.revoke(attempt.proof().context(), attempt.oldRefresh(), "REPLAY", "EDGE_REFRESH_RESPONSE_UNCONFIRMED").then());
    }

    private Mono<Void> result(ServerWebExchange exchange, EdgeRefreshClaimResultBO result) {
        if (result.outcome() == EdgeRefreshClaimResultBO.Outcome.EXISTING && result.operation() != null) {
            return write(exchange, "IN_PROGRESS".equals(result.operation().status()) ? HttpStatus.ACCEPTED : HttpStatus.OK,
                    json(EdgeRefreshOperationResult.from(result.operation())));
        }
        return Mono.error(new EdgeOAuthStateException(switch (result.outcome()) {
            case IN_PROGRESS -> "EDGE_REFRESH_IN_PROGRESS";
            case CONFLICT -> "EDGE_REFRESH_OPERATION_CONFLICT";
            default -> "EDGE_REFRESH_CONTEXT_REJECTED";
        }));
    }

    private static boolean successor(EdgeRefreshContextBO previous, EdgeRefreshContextBO next) {
        return previous.target().equals(next.target()) && previous.subject().equals(next.subject())
                && previous.authorizationId().equals(next.authorizationId()) && previous.authenticationJti().equals(next.authenticationJti())
                && previous.authorizationExpiresAt().equals(next.authorizationExpiresAt())
                && previous.authenticationExpiresAt().equals(next.authenticationExpiresAt())
                && previous.browserBindingDigest().equals(next.browserBindingDigest()) && next.generation() == previous.generation() + 1;
    }

    private byte[] json(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new EdgeOAuthStateException("EDGE_RESPONSE_ENCODING_FAILED"); }
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, byte[] body) {
        if (exchange.getResponse().isCommitted()) return Mono.error(new EdgeOAuthStateException("EDGE_RESPONSE_ALREADY_COMMITTED"));
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private record Attempt(EdgeRefreshOperationBO operation, EdgeVerifiedRefreshProofBO proof, String oldRefresh, AtomicBoolean completed) {
        @Override public String toString() { return "Attempt[operationId=" + operation.operationId() + ", credentials=<redacted>]"; }
    }
}
