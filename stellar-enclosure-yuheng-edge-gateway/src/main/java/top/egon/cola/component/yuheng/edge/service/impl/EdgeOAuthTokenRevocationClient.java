package top.egon.cola.component.yuheng.edge.service.impl;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRevocationClaimBO;

/** Sends only the claimed credential to its original, fixed client/issuer revocation endpoint. */
@Component
public class EdgeOAuthTokenRevocationClient {
    private final EdgeOAuthApplicationCatalog catalog;
    private final EdgeOAuthStateProperties properties;
    private final WebClient webClient;

    public EdgeOAuthTokenRevocationClient(EdgeOAuthApplicationCatalog catalog, EdgeOAuthStateProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
        this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .followRedirect(false).responseTimeout(properties.getExchangeTimeout())))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16_384)).build();
    }

    public Mono<Void> revoke(EdgeRevocationClaimBO claim) {
        var target = claim.intent().target();
        return catalog.application(target.appAlias(), target.issuer()).flatMap(application -> {
            if (!target.equals(application.target())) return Mono.error(new EdgeOAuthStateException("EDGE_REVOCATION_TARGET_CHANGED"));
            var registration = application.registration();
            Object metadata = registration.getProviderDetails().getConfigurationMetadata().get("revocation_endpoint");
            if (!(metadata instanceof String endpoint) || !endpoint.equals(target.issuer() + "/oauth2/revoke")
                    || !EdgeOAuthApplicationCatalog.sameOrigin(URI.create(target.issuer()), URI.create(endpoint))) {
                return Mono.error(new EdgeOAuthStateException("EDGE_REVOCATION_ENDPOINT_INVALID"));
            }
            return webClient.post().uri(endpoint).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> headers.setBasicAuth(URLEncoder.encode(registration.getClientId(), StandardCharsets.UTF_8),
                            URLEncoder.encode(registration.getClientSecret(), StandardCharsets.UTF_8)))
                    .body(BodyInserters.fromFormData("token", claim.tokenValue()).with("token_type_hint", "refresh_token"))
                    .exchangeToMono(response -> response.releaseBody().then(response.statusCode().equals(HttpStatus.OK)
                            ? Mono.<Void>empty() : Mono.error(new EdgeOAuthStateException("EDGE_REVOCATION_UNCONFIRMED"))))
                    .timeout(properties.getExchangeTimeout());
        });
    }
}
