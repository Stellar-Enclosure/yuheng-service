package top.egon.cola.component.yuheng.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshClaimResultBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshOperationBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedRefreshProofBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeRefreshOperationService;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRefreshOperationRepository;
import top.egon.cola.platform.tianquan.shoubing.contract.state.RefreshContextProof;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EdgeRefreshProtocolTest {
    @TempDir Path directory;
    private static final String BROWSER = "b".repeat(43);

    @Test void successfulRefreshWritesCredentialsOnceAndStatusRetryNeverRecoversTokens() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var proof = proof(fixture);
        var operation = operation(proof, "IN_PROGRESS");
        var operations = operations(operation);
        when(fixture.identities.requireCurrentRefresh(proof, "old.refresh")).thenReturn(Mono.empty());
        when(fixture.identities.auditRefreshProof(proof, "EDGE_REFRESH_VERIFIED")).thenReturn(Mono.empty());
        var old = proof.context();
        var next = new EdgeRefreshContextBO(old.target(), old.subject(), old.authorizationId(), old.authenticationJti(), 1,
                old.authorizationExpiresAt(), old.authenticationExpiresAt(), old.browserBindingDigest());
        when(fixture.identities.issuedContext(any(), eq(BROWSER))).thenReturn(next);
        var calls = new AtomicInteger();
        var service = new EdgeRefreshOperationService(operations, fixture.revocations, fixture.identities, fixture.cookies,
                request -> { calls.incrementAndGet(); return Mono.just(response()); }, new ObjectMapper());
        var first = exchange();
        service.refresh(first, operation.operationId(), proof).block();
        assertThat(first.getResponse().getStatusCode().value()).isEqualTo(200);
        assertThat(first.getResponse().getCookies()).containsKey("__Secure-egon_refresh_app-a");
        assertThat(first.getResponse().getBodyAsString().block()).contains("SUCCEEDED").doesNotContain("access.jwt.value", "new.refresh");
        when(operations.inspect(operation.operationId(), old, "old.refresh")).thenReturn(Mono.just(new EdgeRefreshClaimResultBO(
                EdgeRefreshClaimResultBO.Outcome.EXISTING, operation(proof, "SUCCEEDED", operation.operationId()))));
        var retry = exchange();
        service.refresh(retry, operation.operationId(), proof).block();
        assertThat(retry.getResponse().getCookies()).isEmpty();
        assertThat(retry.getResponse().getBodyAsString().block()).contains("SUCCEEDED").doesNotContain("new.refresh");
        assertThat(calls).hasValue(1);
        verify(operations, never()).closeUnknown(any(), anyString());
    }

    @Test void failedCommitClosesOperationWithoutAnyCredentialCookie() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var proof = proof(fixture); var operation = operation(proof, "IN_PROGRESS"); var operations = operations(operation);
        when(fixture.identities.requireCurrentRefresh(proof, "old.refresh")).thenReturn(Mono.empty());
        when(fixture.identities.auditRefreshProof(proof, "EDGE_REFRESH_VERIFIED")).thenReturn(Mono.empty());
        var old = proof.context();
        when(fixture.identities.issuedContext(any(), eq(BROWSER))).thenReturn(new EdgeRefreshContextBO(old.target(), old.subject(),
                old.authorizationId(), old.authenticationJti(), 1, old.authorizationExpiresAt(), old.authenticationExpiresAt(), old.browserBindingDigest()));
        when(operations.complete(any(), anyLong(), any())).thenReturn(Mono.just(false));
        var service = new EdgeRefreshOperationService(operations, fixture.revocations, fixture.identities, fixture.cookies,
                request -> Mono.just(response()), new ObjectMapper());
        var exchange = exchange();
        assertThatThrownBy(() -> service.refresh(exchange, operation.operationId(), proof).block()).hasMessageContaining("EDGE_REFRESH_RESPONSE_UNCONFIRMED");
        assertThat(exchange.getResponse().getCookies()).isEmpty();
        verify(operations).closeUnknown(eq(operation), anyString());
    }

    @Test void cancelledUpstreamExchangeClosesTheClaimedGeneration() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var proof = proof(fixture); var operation = operation(proof, "IN_PROGRESS"); var operations = operations(operation);
        when(fixture.identities.requireCurrentRefresh(proof, "old.refresh")).thenReturn(Mono.empty());
        when(fixture.identities.auditRefreshProof(proof, "EDGE_REFRESH_VERIFIED")).thenReturn(Mono.empty());
        var calls = new AtomicInteger();
        var service = new EdgeRefreshOperationService(operations, fixture.revocations, fixture.identities, fixture.cookies,
                request -> { calls.incrementAndGet(); return Mono.never(); }, new ObjectMapper());
        var pending = service.refresh(exchange(), operation.operationId(), proof).subscribe();
        assertThat(calls).hasValue(1);
        pending.dispose();
        verify(operations).closeUnknown(eq(operation), anyString());
    }

    @Test void inProgressOrClosedReadNeverDispatchesOrChecksCurrentRefreshOwner() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var proof = proof(fixture); var operation = operation(proof, "IN_PROGRESS"); var operations = operations(operation);
        when(operations.inspect(any(), any(), anyString())).thenReturn(Mono.just(new EdgeRefreshClaimResultBO(EdgeRefreshClaimResultBO.Outcome.EXISTING, operation)));
        var service = new EdgeRefreshOperationService(operations, fixture.revocations, fixture.identities, fixture.cookies,
                request -> Mono.error(new AssertionError("status must never dispatch")), new ObjectMapper());
        var exchange = exchange();
        service.refresh(exchange, operation.operationId(), proof).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(202);
        assertThat(exchange.getResponse().getCookies()).isEmpty();
        verify(fixture.identities, never()).requireCurrentRefresh(any(), anyString());
        verify(operations, never()).dispatch(any());
    }

    private EdgeRefreshOperationRepository operations(EdgeRefreshOperationBO operation) {
        var operations = mock(EdgeRefreshOperationRepository.class);
        when(operations.inspect(any(), any(), anyString())).thenReturn(Mono.just(new EdgeRefreshClaimResultBO(EdgeRefreshClaimResultBO.Outcome.MISSING, null)));
        when(operations.claim(any(), any(), anyString())).thenReturn(Mono.just(new EdgeRefreshClaimResultBO(EdgeRefreshClaimResultBO.Outcome.ACQUIRED, operation)));
        when(operations.dispatch(operation)).thenReturn(Mono.just(Optional.of(operation)));
        when(operations.complete(any(), anyLong(), any())).thenReturn(Mono.just(true));
        when(operations.closeUnknown(any(), anyString())).thenReturn(Mono.just(true));
        return operations;
    }

    private EdgeVerifiedRefreshProofBO proof(EdgeSecurityFixture fixture) {
        var context = fixture.context(BROWSER);
        var proof = new RefreshContextProof(URI.create(context.target().issuer()), context.target().tenantId(), context.subject(),
                context.target().clientId(), context.authorizationId(), context.authenticationJti(), "0", context.authorizationExpiresAt(),
                context.authenticationExpiresAt(), EdgeSecurityFixture.NOW.minusSeconds(600), EdgeSecurityFixture.NOW.minusSeconds(1));
        return new EdgeVerifiedRefreshProofBO(fixture.catalog.defaultApplication("app-a").block(), context, proof, "old.access.jwt");
    }

    private EdgeRefreshOperationBO operation(EdgeVerifiedRefreshProofBO proof, String status) { return operation(proof, status, UUID.randomUUID()); }
    private EdgeRefreshOperationBO operation(EdgeVerifiedRefreshProofBO proof, String status, UUID id) {
        return new EdgeRefreshOperationBO(id, proof.context(), "d".repeat(64), "e".repeat(64), status, UUID.randomUUID(),
                EdgeSecurityFixture.NOW.plusSeconds(20), EdgeSecurityFixture.NOW.plusSeconds(10), 1, EdgeSecurityFixture.NOW.plusSeconds(300), 1L, null, 1);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post(EdgeSecurityFixture.ORIGIN + "/apps/app-a/auth/refresh")
                .cookie(new HttpCookie("__Secure-egon_refresh_app-a", "old.refresh"), new HttpCookie("__Secure-egon_binding_app-a", BROWSER)));
    }

    private OAuth2AccessTokenResponse response() {
        return OAuth2AccessTokenResponse.withToken("access.jwt.value").tokenType(OAuth2AccessToken.TokenType.BEARER)
                .refreshToken("new.refresh").expiresIn(300).build();
    }
}
