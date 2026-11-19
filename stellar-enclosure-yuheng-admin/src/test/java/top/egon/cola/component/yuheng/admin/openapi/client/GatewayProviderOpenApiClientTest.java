package top.egon.cola.component.yuheng.admin.openapi.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.openapi.GatewayOpenApiValidationTestFixture;
import top.egon.cola.component.yuheng.admin.openapi.domain.dto.GatewayOpenApiDocumentDTO;
import top.egon.cola.component.yuheng.admin.openapi.domain.dto.GatewayOpenApiSyncCandidateDTO;
import top.egon.cola.component.yuheng.contract.reporting.openapi.GatewayOpenApiGroupManifestDTO;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayProviderOpenApiClientTest {

    private static final Instant NOW =
            GatewayOpenApiValidationTestFixture.NOW;

    @Test
    void deniedFirstDnsResolutionFailsBeforeTokenOrHttp() {
        GatewayOpenApiTokenSupplier tokenSupplier = mock(
                GatewayOpenApiTokenSupplier.class
        );
        GatewayOpenApiDnsPolicy dnsPolicy = mock(GatewayOpenApiDnsPolicy.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(dnsPolicy.resolveAndValidate("provider.internal"))
                .thenThrow(new GatewayOpenApiFetchException(
                        "YUHENG_OPENAPI_TARGET_FORBIDDEN",
                        false,
                        "provider target is not allowlisted"
                ));
        GatewayProviderOpenApiClient client = client(
                tokenSupplier,
                dnsPolicy,
                httpClient
        );

        assertThatThrownBy(() -> client.fetch(
                GatewayOpenApiValidationTestFixture.candidate()
        )).isInstanceOf(GatewayOpenApiFetchException.class)
                .satisfies(failure -> assertThat(
                        ((GatewayOpenApiFetchException) failure).errorCode()
                ).isEqualTo("YUHENG_OPENAPI_TARGET_FORBIDDEN"));
        verifyNoInteractions(tokenSupplier, httpClient);
    }

    @Test
    void secondDnsResolutionIsRequiredImmediatelyBeforeHttp() throws Exception {
        GatewayOpenApiTokenSupplier tokenSupplier = mock(
                GatewayOpenApiTokenSupplier.class
        );
        GatewayOpenApiDnsPolicy dnsPolicy = mock(GatewayOpenApiDnsPolicy.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(dnsPolicy.resolveAndValidate("provider.internal"))
                .thenReturn(List.of(loopbackAddress()))
                .thenThrow(new GatewayOpenApiFetchException(
                        "YUHENG_OPENAPI_TARGET_REBOUND",
                        false,
                        "provider target changed during resolution"
                ));
        when(tokenSupplier.tokenFor(any(URI.class), anyString()))
                .thenReturn("opaque-token");
        GatewayProviderOpenApiClient client = client(
                tokenSupplier,
                dnsPolicy,
                httpClient
        );

        assertThatThrownBy(() -> client.fetch(
                GatewayOpenApiValidationTestFixture.candidate()
        )).isInstanceOf(GatewayOpenApiFetchException.class)
                .satisfies(failure -> assertThat(
                        ((GatewayOpenApiFetchException) failure).errorCode()
                ).isEqualTo("YUHENG_OPENAPI_TARGET_REBOUND"));
        verify(tokenSupplier).tokenFor(
                eq(URI.create("https://provider.example/resource")),
                eq("yuheng.openapi.read")
        );
        verifyNoInteractions(httpClient);
    }

    @Test
    void tokenHeaderInjectionIsRejectedBeforeHttp() throws Exception {
        GatewayOpenApiTokenSupplier tokenSupplier = mock(
                GatewayOpenApiTokenSupplier.class
        );
        GatewayOpenApiDnsPolicy dnsPolicy = mock(GatewayOpenApiDnsPolicy.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(dnsPolicy.resolveAndValidate("provider.internal"))
                .thenReturn(List.of(loopbackAddress()));
        when(tokenSupplier.tokenFor(any(URI.class), anyString()))
                .thenReturn("token\ninjected");
        GatewayProviderOpenApiClient client = client(
                tokenSupplier,
                dnsPolicy,
                httpClient
        );

        assertThatThrownBy(() -> client.fetch(
                GatewayOpenApiValidationTestFixture.candidate()
        )).isInstanceOf(GatewayOpenApiFetchException.class)
                .satisfies(failure -> assertThat(
                        ((GatewayOpenApiFetchException) failure).errorCode()
                ).isEqualTo("YUHENG_OPENAPI_OAUTH_FAILED"));
        verifyNoInteractions(httpClient);
    }

    @Test
    void successfulResponseIsBoundedParsedAndHasNoRedirectPolicy() throws Exception {
        GatewayOpenApiTokenSupplier tokenSupplier = mock(
                GatewayOpenApiTokenSupplier.class
        );
        GatewayOpenApiDnsPolicy dnsPolicy = mock(GatewayOpenApiDnsPolicy.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(dnsPolicy.resolveAndValidate("provider.internal"))
                .thenReturn(List.of(loopbackAddress()));
        when(tokenSupplier.tokenFor(any(URI.class), anyString()))
                .thenReturn("opaque-token");
        HttpResponse<InputStream> response = response(
                200,
                "application/json; charset=utf-8",
                "{\"openapi\":\"3.1.0\"}"
        );
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(response);
        GatewayProviderOpenApiClient client = client(
                tokenSupplier,
                dnsPolicy,
                httpClient
        );

        GatewayOpenApiDocumentDTO document = client.fetch(
                GatewayOpenApiValidationTestFixture.candidate()
        );

        assertThat(document.documentSha256()).isEqualTo(
                GatewayOpenApiValidationTestFixture.sha256(
                        "{\"openapi\":\"3.1.0\"}".getBytes()
                )
        );
        assertThat(document.documentJson().path("openapi").asText())
                .isEqualTo("3.1.0");
        verify(httpClient).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        );
    }

    @Test
    void redirectAndOversizedResponsesAreRejectedWithoutDocumentHandoff()
            throws Exception {
        GatewayOpenApiTokenSupplier tokenSupplier = mock(
                GatewayOpenApiTokenSupplier.class
        );
        GatewayOpenApiDnsPolicy dnsPolicy = mock(GatewayOpenApiDnsPolicy.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(dnsPolicy.resolveAndValidate(anyString()))
                .thenReturn(List.of(loopbackAddress()));
        when(tokenSupplier.tokenFor(any(URI.class), anyString()))
                .thenReturn("opaque-token");
        HttpResponse<InputStream> redirectResponse = response(
                302,
                "application/json",
                "redirect"
        );
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(redirectResponse);
        GatewayProviderOpenApiClient redirectClient = client(
                tokenSupplier,
                dnsPolicy,
                httpClient
        );

        assertThatThrownBy(() -> redirectClient.fetch(
                GatewayOpenApiValidationTestFixture.candidate()
        )).isInstanceOf(GatewayOpenApiFetchException.class)
                .satisfies(failure -> assertThat(
                        ((GatewayOpenApiFetchException) failure).errorCode()
                ).isEqualTo("YUHENG_OPENAPI_HTTP_STATUS"));

        HttpClient oversizedHttpClient = mock(HttpClient.class);
        HttpResponse<InputStream> oversizedResponse = response(
                200,
                "application/json",
                "{\"a\":1}"
        );
        when(oversizedHttpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(oversizedResponse);
        GatewayProviderOpenApiClient oversizedClient = client(
                tokenSupplier,
                dnsPolicy,
                oversizedHttpClient,
                5
        );
        assertThatThrownBy(() -> oversizedClient.fetch(
                GatewayOpenApiValidationTestFixture.candidate()
        )).isInstanceOf(GatewayOpenApiFetchException.class)
                .satisfies(failure -> assertThat(
                        ((GatewayOpenApiFetchException) failure).errorCode()
                ).isEqualTo("YUHENG_OPENAPI_DOCUMENT_TOO_LARGE"));
    }

    @Test
    void productionInternalHttpRequiresExactServiceFqdnAndAnApprovedSourceGuard() {
        var candidate = internalCandidate("orders.apps-prod.svc.cluster.local");
        var tokens=mock(GatewayOpenApiTokenSupplier.class);var dns=mock(GatewayOpenApiDnsPolicy.class);var http=mock(HttpClient.class);
        assertThatThrownBy(()->client(tokens,dns,http).fetch(candidate)).isInstanceOf(GatewayOpenApiFetchException.class);
        verifyNoInteractions(tokens,dns,http);
        assertThatThrownBy(()->internalCandidate("attacker.example" )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->internalCandidate("127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void developmentHttpStillRequiresAnExplicitLocalOptIn() {
        assertThatThrownBy(()->new GatewayOpenApiSyncCandidateDTO("app","trade","orders","build","1","orders",
                "orders","default","1","fixture","localhost",8080,false,"/v3/api-docs/{group}",
                URI.create("https://canonical.example/resource"),NOW.minusSeconds(1),NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        var candidate=new GatewayOpenApiSyncCandidateDTO("app","trade","orders","build","1","orders",
                "orders","default","1","fixture","localhost",8080,false,"/v3/api-docs/{group}",
                URI.create("https://canonical.example/resource"),NOW.minusSeconds(1),NOW.plusSeconds(60),true);
        assertThat(candidate.developmentPlaintext()).isTrue();assertThat(candidate.approvedInternalHttp()).isFalse();
    }

    private GatewayOpenApiSyncCandidateDTO internalCandidate(String host) {
        return new GatewayOpenApiSyncCandidateDTO("app","trade","orders","build","1","orders","orders","default","1",
                "source-1",host,8080,false,"/v3/api-docs/{group}",URI.create("https://canonical.example/resource"),
                NOW.minusSeconds(1),NOW.plusSeconds(60),false,true);
    }

    @Test
    void dnsPolicyRejectsMixedAllowedAndDeniedAnswers() throws Exception {
        GatewayOpenApiDnsPolicy policy = new GatewayOpenApiDnsPolicy(
                List.of("10.0.0.0/8"),
                ignored -> List.of(
                        address("provider.internal", new byte[]{10, 0, 0, 1}),
                        loopbackAddress()
                )
        );

        assertThatThrownBy(() -> policy.resolveAndValidate(
                "provider.internal"
        )).isInstanceOf(GatewayOpenApiFetchException.class)
                .satisfies(failure -> assertThat(
                        ((GatewayOpenApiFetchException) failure).errorCode()
                ).isEqualTo("YUHENG_OPENAPI_TARGET_FORBIDDEN"));
    }

    private GatewayProviderOpenApiClient client(
            GatewayOpenApiTokenSupplier tokenSupplier,
            GatewayOpenApiDnsPolicy dnsPolicy,
            HttpClient httpClient) {
        return client(tokenSupplier, dnsPolicy, httpClient, 5 * 1024 * 1024);
    }

    private GatewayProviderOpenApiClient client(
            GatewayOpenApiTokenSupplier tokenSupplier,
            GatewayOpenApiDnsPolicy dnsPolicy,
            HttpClient httpClient,
            int maximumDocumentBytes) {
        return new GatewayProviderOpenApiClient(
                tokenSupplier,
                dnsPolicy,
                httpClient,
                new ObjectMapper(),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                Duration.ofSeconds(10),
                maximumDocumentBytes
        );
    }

    private static HttpResponse<InputStream> response(
            int status,
            String contentType,
            String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(
                java.net.http.HttpHeaders.of(
                        java.util.Map.of("Content-Type", List.of(contentType)),
                        (name, value) -> true
                )
        );
        when(response.body()).thenReturn(new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)
        ));
        return response;
    }

    private static InetAddress loopbackAddress() {
        return address("provider.internal", new byte[]{127, 0, 0, 1});
    }

    private static InetAddress address(String host, byte[] bytes) {
        try {
            return InetAddress.getByAddress(
                    host,
                    bytes
            );
        } catch (java.net.UnknownHostException failure) {
            throw new AssertionError(failure);
        }
    }
}
