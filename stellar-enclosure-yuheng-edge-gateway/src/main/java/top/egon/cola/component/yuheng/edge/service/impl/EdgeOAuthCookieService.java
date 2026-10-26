package top.egon.cola.component.yuheng.edge.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthProperties;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;

/** Cookies transport credentials; no value is a pointer to a server-side principal or token store. */
@Component
public class EdgeOAuthCookieService {
    private static final String PREFIX = "__Secure-egon_";
    private static final int MAX_COOKIE_VALUE = 3800;
    private final EdgeOAuthProperties properties;
    private final EdgeOAuthStateProperties stateProperties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EdgeOAuthCookieService(@Qualifier("edgeOAuthProperties") EdgeOAuthProperties properties,
                                   EdgeOAuthStateProperties stateProperties, @Qualifier("edgeClock") Clock clock) {
        this.properties = properties;
        this.stateProperties = stateProperties;
        this.clock = clock;
    }

    public Optional<String> access(ServerWebExchange exchange, String alias) { return read(exchange, "access", alias); }
    public Optional<String> refresh(ServerWebExchange exchange, String alias) { return read(exchange, "refresh", alias); }
    public Optional<String> csrf(ServerWebExchange exchange, String alias) { return read(exchange, "csrf", alias); }

    public Optional<String> browserBinding(ServerWebExchange exchange, String alias) {
        return read(exchange, "binding", alias).map(value -> {
            if (!value.matches("[A-Za-z0-9_-]{43}")) throw new EdgeOAuthStateException("EDGE_BROWSER_BINDING_INVALID");
            return value;
        });
    }

    public String ensureBrowserBinding(ServerWebExchange exchange, String alias) {
        return browserBinding(exchange, alias).orElseGet(() -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            write(exchange, "binding", alias, value, true, clock.instant().plus(stateProperties.getMaximumAuthorizationLifetime()));
            return value;
        });
    }

    public void writeCredentials(ServerWebExchange exchange, String alias, String accessToken,
                                  String refreshToken, String browserBinding, Instant authorizationExpiry) {
        writePrepared(exchange, prepareCredentials(alias, accessToken, refreshToken, browserBinding, authorizationExpiry));
    }

    public List<ResponseCookie> prepareCredentials(String alias, String accessToken, String refreshToken,
                                                    String browserBinding, Instant authorizationExpiry) {
        requireValue(accessToken);
        requireValue(refreshToken);
        if (browserBinding == null || !browserBinding.matches("[A-Za-z0-9_-]{43}")) {
            throw new EdgeOAuthStateException("EDGE_BROWSER_BINDING_INVALID");
        }
        Duration age = lifetime(authorizationExpiry);
        // The carrier outlives Access.exp only so a dedicated refresh branch can verify the old signed proof.
        List<ResponseCookie> values = List.of(cookie("access", alias, accessToken, false, age),
                cookie("refresh", alias, refreshToken, true, age), cookie("binding", alias, browserBinding, true, age));
        if (values.stream().anyMatch(value -> value.toString().length() > 4096)
                || values.stream().mapToInt(value -> value.getName().length() + value.getValue().length() + 3).sum() > 7680) {
            throw new EdgeOAuthStateException("EDGE_COOKIE_BUDGET_EXCEEDED");
        }
        return values;
    }

    public void writePrepared(ServerWebExchange exchange, List<ResponseCookie> cookies) {
        if (exchange.getResponse().isCommitted()) throw new EdgeOAuthStateException("EDGE_RESPONSE_ALREADY_COMMITTED");
        cookies.forEach(exchange.getResponse()::addCookie);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
    }

    public void writeCsrf(ServerWebExchange exchange, String alias, String proof, Instant expiresAt) {
        write(exchange, "csrf", alias, proof, false, expiresAt);
    }

    public void clearCredentials(ServerWebExchange exchange, String alias) {
        clear(exchange, "access", alias, false);
        clear(exchange, "refresh", alias, true);
        clear(exchange, "binding", alias, true);
        clear(exchange, "csrf", alias, false);
    }

    public boolean containsCredentialCookie(ServerWebExchange exchange) {
        return exchange.getRequest().getCookies().keySet().stream().anyMatch(name -> name.startsWith(PREFIX)
                || "__Host-egon_bff".equals(name));
    }

    public void rejectAmbientAuthorization(ServerWebExchange exchange) {
        if (exchange.getRequest().getHeaders().containsHeader("Authorization")) {
            throw new EdgeOAuthStateException("EDGE_MIXED_CREDENTIALS");
        }
    }

    private Optional<String> read(ServerWebExchange exchange, String kind, String alias) {
        String name = name(kind, alias);
        List<HttpCookie> values = exchange.getRequest().getCookies().get(name);
        if (values == null || values.isEmpty()) return Optional.empty();
        if (values.size() != 1) throw new EdgeOAuthStateException("EDGE_COOKIE_AMBIGUOUS");
        String value = values.getFirst().getValue();
        requireValue(value);
        long bytes = exchange.getRequest().getHeaders().getOrEmpty("Cookie").stream().mapToLong(String::length).sum();
        if (bytes > 8192) throw new EdgeOAuthStateException("EDGE_COOKIE_BUDGET_EXCEEDED");
        return Optional.of(value);
    }

    private void write(ServerWebExchange exchange, String kind, String alias, String value, boolean authOnly, Instant expiry) {
        requireValue(value);
        exchange.getResponse().addCookie(cookie(kind, alias, value, authOnly, lifetime(expiry)));
        exchange.getResponse().getHeaders().setCacheControl("no-store");
    }

    private ResponseCookie cookie(String kind, String alias, String value, boolean authOnly, Duration age) {
        return ResponseCookie.from(name(kind, alias), value).path(path(alias, authOnly))
                .httpOnly(true).secure(true).sameSite("Lax").maxAge(age).build();
    }

    private void clear(ServerWebExchange exchange, String kind, String alias, boolean authOnly) {
        exchange.getResponse().addCookie(ResponseCookie.from(name(kind, alias), "").path(path(alias, authOnly))
                .httpOnly(true).secure(true).sameSite("Lax").maxAge(Duration.ZERO).build());
        exchange.getResponse().getHeaders().setCacheControl("no-store");
    }

    private String name(String kind, String alias) {
        if (alias == null || !alias.matches("[A-Za-z0-9_-]{1,128}") || !properties.getResources().containsKey(alias)) {
            throw new EdgeOAuthStateException("EDGE_APPLICATION_UNKNOWN");
        }
        return PREFIX + kind + "_" + alias;
    }

    private static String path(String alias, boolean authOnly) { return "/apps/" + alias + (authOnly ? "/auth" : "/"); }

    private Duration lifetime(Instant expiry) {
        if (expiry == null) throw new EdgeOAuthStateException("EDGE_COOKIE_EXPIRY_INVALID");
        Duration duration = Duration.between(clock.instant(), expiry);
        if (duration.isNegative() || duration.isZero()
                || duration.compareTo(stateProperties.getMaximumAuthorizationLifetime().plus(stateProperties.getClockSkew())) > 0) {
            throw new EdgeOAuthStateException("EDGE_COOKIE_EXPIRY_INVALID");
        }
        return duration;
    }

    private static void requireValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_COOKIE_VALUE
                || !value.matches("[A-Za-z0-9._~-]+")) {
            throw new EdgeOAuthStateException("EDGE_COOKIE_VALUE_INVALID");
        }
    }
}
