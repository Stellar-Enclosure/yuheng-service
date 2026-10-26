package top.egon.cola.component.yuheng.edge;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EdgeLoginContractTest {
    @TempDir Path directory;

    @Test void nativeLoginPkceNonceAndCallbackCompleteWithoutAnySessionAccess() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var login = fixture.login();
        assertThat(login.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(login.getResponse().getHeaders().getLocation().toString()).startsWith(EdgeSecurityFixture.ISSUER + "/oauth2/authorize?")
                .contains("code_challenge_method=S256", "resource=", "browser_binding=", "nonce=");
        var callback = fixture.callback(login);
        fixture.run(callback);
        assertThat(callback.getResponse().getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(callback.getResponse().getHeaders().getLocation().toString()).isEqualTo(EdgeSecurityFixture.ORIGIN + "/");
        assertThat(fixture.exchanged.get().<String>getAttribute(PkceParameterNames.CODE_VERIFIER)).isEqualTo(fixture.verifier.get());
        assertThat(callback.getResponse().getCookies()).containsKeys("__Secure-egon_access_app-a", "__Secure-egon_refresh_app-a")
                .doesNotContainKeys("SESSION", "__Host-egon_bff");
        assertThat(callback.getResponse().getCookies().getFirst("__Secure-egon_refresh_app-a").getPath()).isEqualTo("/apps/app-a/auth");
        verify(fixture.transactions).complete(any(), eq(true), isNull());
        assertThat(fixture.tokenCalls).hasValue(1);
    }

    @Test void callbackIsOneUseAndNonceMismatchNeverWritesCredentials() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var login = fixture.login();
        fixture.wrongNonce.set(true);
        var callback = fixture.callback(login);
        fixture.run(callback);
        assertThat(callback.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callback.getResponse().getCookies()).isEmpty();
        verify(fixture.revocations).revokeUnconfirmedExchange(any(), eq(fixture.target), anyString(), eq("refresh.value"));
        fixture.run(fixture.callback(login));
        assertThat(fixture.tokenCalls).hasValue(1);
    }

    @Test void issuerResourceReturnPathAndCookieMixingCannotBeSelectedByUntrustedRequest() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        for (String query : new String[] {"resource=https://evil.example", "issuer=https://evil.example", "returnTo=//evil.example", "returnTo=/&returnTo=/",
                "reauthenticate=false", "reauthenticate=true&reauthenticate=true", "prompt=none", "max_age=9999"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(EdgeSecurityFixture.ORIGIN + "/apps/app-a/auth/login?" + query));
            fixture.run(exchange);
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var mixed = MockServerWebExchange.from(MockServerHttpRequest.get(EdgeSecurityFixture.ORIGIN + "/apps/app-a/api/example/value")
                .header("Authorization", "Bearer other").cookie(new HttpCookie("__Secure-egon_access_app-a", "access.jwt.value")));
        fixture.run(mixed);
        assertThat(mixed.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(fixture.identities);
    }

    @Test void explicitReauthenticationUsesFixedOidcFreshnessOptionsAndPreservesThemThroughTheSqlClaim() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var login = MockServerWebExchange.from(MockServerHttpRequest.get(EdgeSecurityFixture.ORIGIN + "/apps/app-a/auth/login?reauthenticate=true"));
        fixture.run(login);
        assertThat(login.getResponse().getHeaders().getLocation().toString()).contains("prompt=login", "max_age=0");
        assertThat(fixture.reauthenticate).isTrue();
        var callback = fixture.callback(login);
        fixture.run(callback);
        assertThat(fixture.exchanged.get().getAdditionalParameters()).containsEntry("prompt", "login").containsEntry("max_age", "0");
        assertThat(fixture.tokenCalls).hasValue(1);
    }

    @Test void expiredAccessCannotReachBusinessApiAndNeverTriggersRefreshExchange() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        when(fixture.identities.access("app-a", "expired.jwt.value", true)).thenReturn(Mono.error(new EdgeOAuthStateException("EDGE_ACCESS_REJECTED")));
        var api = MockServerWebExchange.from(MockServerHttpRequest.get(EdgeSecurityFixture.ORIGIN + "/apps/app-a/api/example/value")
                .cookie(new HttpCookie("__Secure-egon_access_app-a", "expired.jwt.value")));
        fixture.run(api);
        assertThat(api.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(fixture.identities, never()).refreshProof(anyString(), anyString(), anyString());
        assertThat(fixture.tokenCalls).hasValue(0);
    }

    @Test void unsafeCookieRequestRequiresBoundCsrfAndTrustedOrigin() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var post = MockServerWebExchange.from(MockServerHttpRequest.post(EdgeSecurityFixture.ORIGIN + "/apps/app-a/api/example/value")
                .header("Origin", EdgeSecurityFixture.ORIGIN).cookie(new HttpCookie("__Secure-egon_access_app-a", "access.jwt.value")));
        fixture.run(post);
        assertThat(post.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
