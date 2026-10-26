package top.egon.cola.component.yuheng.edge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthProperties;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.config.domain.bo.EdgeRegistrationResourceBindingBO;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCookieService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeCookieTransportTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    void retainsSignedAccessCarrierUntilFamilyExpiryAndRestrictsRefreshToAuthPaths() {
        var cookies = cookies();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("https://edge.example/apps/orders/auth/callback"));
        cookies.writeCredentials(exchange, "orders", "header.payload.signature", "opaque-refresh",
                "b".repeat(43), NOW.plus(Duration.ofHours(8)));
        var access = exchange.getResponse().getCookies().getFirst("__Secure-egon_access_orders");
        var refresh = exchange.getResponse().getCookies().getFirst("__Secure-egon_refresh_orders");
        assertThat(access.getPath()).isEqualTo("/apps/orders/");
        assertThat(refresh.getPath()).isEqualTo("/apps/orders/auth");
        assertThat(access.getMaxAge()).isEqualTo(Duration.ofHours(8));
        for (var cookie : exchange.getResponse().getCookies().values().stream().flatMap(java.util.List::stream).toList()) {
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.isSecure()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo("Lax");
            assertThat(cookie.getDomain()).isNull();
        }
        assertThat(exchange.getResponse().getCookies()).doesNotContainKeys("SESSION", "JSESSIONID", "__Host-egon_bff");
    }

    @Test
    void rejectsAmbiguousCookiesMixedCredentialsAndUnknownApplications() {
        var cookies = cookies();
        var duplicate = MockServerWebExchange.from(MockServerHttpRequest.get("https://edge.example/apps/orders/auth/identity")
                .cookie(new HttpCookie("__Secure-egon_access_orders", "a"), new HttpCookie("__Secure-egon_access_orders", "b")));
        assertThatThrownBy(() -> cookies.access(duplicate, "orders")).isInstanceOf(EdgeOAuthStateException.class)
                .hasMessage("EDGE_COOKIE_AMBIGUOUS");
        var mixed = MockServerWebExchange.from(MockServerHttpRequest.get("https://edge.example/apps/orders/auth/identity")
                .header("Authorization", "Bearer other"));
        assertThatThrownBy(() -> cookies.rejectAmbientAuthorization(mixed)).hasMessage("EDGE_MIXED_CREDENTIALS");
        assertThatThrownBy(() -> cookies.access(mixed, "other")).hasMessage("EDGE_APPLICATION_UNKNOWN");
    }

    @Test
    void clearsExactlyTheSameCredentialPathsAndDoesNotWriteAnExpiredCredential() {
        var cookies = cookies();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("https://edge.example/apps/orders/auth/logout"));
        cookies.clearCredentials(exchange, "orders");
        assertThat(exchange.getResponse().getCookies().values().stream().flatMap(java.util.List::stream))
                .allSatisfy(cookie -> assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO));
        var expired = MockServerWebExchange.from(MockServerHttpRequest.get("https://edge.example/apps/orders/auth/callback"));
        assertThatThrownBy(() -> cookies.writeCredentials(expired, "orders", "jwt", "refresh", "b".repeat(43), NOW))
                .hasMessage("EDGE_COOKIE_EXPIRY_INVALID");
        assertThat(expired.getResponse().getCookies()).isEmpty();
    }

    private static EdgeOAuthCookieService cookies() {
        var properties = new EdgeOAuthProperties();
        properties.setResources(Map.of("orders", new EdgeRegistrationResourceBindingBO()));
        return new EdgeOAuthCookieService(properties, new EdgeOAuthStateProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
