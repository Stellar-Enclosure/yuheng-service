package top.egon.cola.component.yuheng.edge.filter;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent;

/** A small protocol boundary: exception text, response bodies and credentials never become client error strings. */
public final class EdgeProtocolErrors {
    private EdgeProtocolErrors() { }

    public static Mono<Void> write(ServerWebExchange exchange, Throwable failure) {
        return write(exchange, status(failure), code(failure));
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code) {
        if (exchange.getResponse().isCommitted()) return Mono.error(new EdgeOAuthStateException("EDGE_RESPONSE_ALREADY_COMMITTED"));
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (status == HttpStatus.UNAUTHORIZED && !exchange.getRequest().getPath().value().startsWith("/apps/")) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        String safe = code != null && code.matches("[A-Za-z0-9_]{1,128}") ? code : "EDGE_REQUEST_FAILED";
        String error = status == HttpStatus.SERVICE_UNAVAILABLE ? "temporarily_unavailable"
                : status == HttpStatus.UNAUTHORIZED ? "invalid_token" : status == HttpStatus.FORBIDDEN ? "access_denied" : "invalid_request";
        byte[] body = ("{\"error\":\"" + error + "\",\"code\":\"" + safe + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    public static HttpStatus status(Throwable failure) {
        String code = code(failure);
        if (Set.of("EDGE_CSRF_INVALID", "EDGE_PUBLIC_ORIGIN_INVALID", "EDGE_CSRF_CONTEXT_EXPIRED").contains(code)) return HttpStatus.FORBIDDEN;
        if (Set.of("EDGE_APPLICATION_UNKNOWN", "EDGE_OPERATION_NOT_FOUND").contains(code)) return HttpStatus.NOT_FOUND;
        if (Set.of("EDGE_REFRESH_IN_PROGRESS", "EDGE_REFRESH_OPERATION_CONFLICT").contains(code)) return HttpStatus.CONFLICT;
        if (Set.of("EDGE_REQUEST_INVALID", "EDGE_LOGIN_REQUEST_INVALID", "EDGE_RETURN_PATH_INVALID", "EDGE_MIXED_CREDENTIALS",
                "EDGE_COOKIE_AMBIGUOUS", "EDGE_COOKIE_VALUE_INVALID", "EDGE_COOKIE_BUDGET_EXCEEDED", "invalid_request").contains(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        if (Set.of("EDGE_ACCESS_REJECTED", "EDGE_REFRESH_REQUIRED", "EDGE_BROWSER_BINDING_INVALID", "EDGE_REFRESH_CONTEXT_REJECTED",
                "EDGE_ISSUER_UNTRUSTED", "invalid_token", "invalid_grant", "invalid_nonce", "access_denied",
                "authorization_request_not_found").contains(code)) return HttpStatus.UNAUTHORIZED;
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    public static String code(Throwable failure) {
        if (failure instanceof EdgeOAuthStateException state) return state.getCode();
        if (failure instanceof OAuth2AuthenticationException authentication) return authentication.getError().getErrorCode();
        if (failure instanceof OAuth2AuthorizationException authorization) return authorization.getError().getErrorCode();
        if (failure instanceof TenantIssuerAuthenticationManagerComponent.InvalidIssuerException
                || failure instanceof BadCredentialsException || failure instanceof org.springframework.security.oauth2.jwt.JwtException) return "invalid_token";
        if (failure instanceof org.springframework.web.server.ServerWebInputException
                || failure instanceof org.springframework.core.io.buffer.DataBufferLimitException) return "EDGE_REQUEST_INVALID";
        return "EDGE_IDENTITY_AUTHORITY_UNAVAILABLE";
    }
}
