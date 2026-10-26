package top.egon.cola.component.yuheng.edge.service.impl;

import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthProperties;
import top.egon.cola.component.yuheng.edge.config.domain.bo.EdgeRegistrationResourceBindingBO;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;

/** The deployment catalog, never request data, chooses issuer, client, audience and callback. */
@Component
public class EdgeOAuthApplicationCatalog {
    private final EdgeOAuthProperties properties;
    private final ReactiveClientRegistrationRepository registrations;

    public EdgeOAuthApplicationCatalog(@Qualifier("edgeOAuthProperties") EdgeOAuthProperties properties,
                                       ReactiveClientRegistrationRepository registrations) {
        this.properties = properties;
        this.registrations = registrations;
    }

    public EdgeRegistrationResourceBindingBO resource(String alias) {
        if (alias == null || !alias.matches("[A-Za-z0-9_-]{1,128}")) throw invalid("EDGE_APPLICATION_UNKNOWN");
        var resource = properties.getResources().get(alias);
        if (resource == null || !resource.isBindingValid()) throw invalid("EDGE_APPLICATION_UNKNOWN");
        return resource;
    }

    public Mono<Application> defaultApplication(String alias) {
        var resource = resource(alias);
        return load(alias, resource, resource.getDefaultRegistrationId());
    }

    public Mono<Application> application(String alias, String trustedIssuer) {
        var resource = resource(alias);
        String registrationId = resource.getIssuerRegistrationIds().get(trustedIssuer);
        if (registrationId == null) return Mono.error(invalid("EDGE_ISSUER_UNTRUSTED"));
        return load(alias, resource, registrationId).filter(value -> value.target().issuer().equals(trustedIssuer))
                .switchIfEmpty(Mono.error(invalid("EDGE_CLIENT_CONFIGURATION_INVALID")));
    }

    private Mono<Application> load(String alias, EdgeRegistrationResourceBindingBO resource, String id) {
        return registrations.findByRegistrationId(id)
                .switchIfEmpty(Mono.error(invalid("EDGE_CLIENT_CONFIGURATION_INVALID")))
                .map(registration -> {
                    String issuer = registration.getProviderDetails().getIssuerUri();
                    String callback = properties.getPublicOrigin().resolve("/apps/" + alias + "/auth/callback").toASCIIString();
                    if (!id.equals(resource.getIssuerRegistrationIds().get(issuer))
                            || !AuthorizationGrantType.AUTHORIZATION_CODE.equals(registration.getAuthorizationGrantType())
                            || !ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())
                            || registration.getClientSecret() == null || registration.getClientSecret().isBlank()
                            || !callback.equals(registration.getRedirectUri())
                            || !registration.getScopes().containsAll(resource.getScopes())
                            || !sameOrigin(properties.getUpstreams().getAuthorizationServer(), URI.create(issuer))
                            || !registration.getProviderDetails().getAuthorizationUri().equals(issuer + "/oauth2/authorize")
                            || !registration.getProviderDetails().getTokenUri().equals(issuer + "/oauth2/token")) {
                        throw invalid("EDGE_CLIENT_CONFIGURATION_INVALID");
                    }
                    URI issuerUri = URI.create(issuer);
                    String tenant = "/platform".equals(issuerUri.getPath()) ? "PLATFORM"
                            : issuerUri.getPath().startsWith("/tenants/") ? issuerUri.getPath().substring("/tenants/".length()) : "";
                    var target = new EdgeOAuthTargetBO(alias, id, issuer, tenant, registration.getClientId(),
                            resource.getResourceUri().toASCIIString());
                    return new Application(target, registration, resource, callback);
                });
    }

    public String returnPath(Application application, String requested) {
        String value = requested == null ? application.resource().getReturnPath() : requested;
        if (value == null || !properties.getAllowedReturnPaths().contains(value)) throw invalid("EDGE_RETURN_PATH_INVALID");
        return value;
    }

    public void requireBrowserOrigin(ServerWebExchange exchange) {
        if (!sameOrigin(properties.getPublicOrigin(), exchange.getRequest().getURI())) {
            throw invalid("EDGE_PUBLIC_ORIGIN_INVALID");
        }
    }

    public String aliasForDirectPath(String path) {
        var matcher = new org.springframework.util.AntPathMatcher();
        var matches = properties.getResources().entrySet().stream().flatMap(entry -> entry.getValue().getRequestPaths().stream()
                .filter(pattern -> matcher.match(pattern, path)).map(pattern -> new MapEntry(entry.getKey(), pattern)))
                .sorted((left, right) -> matcher.getPatternComparator(path).compare(left.pattern(), right.pattern())).toList();
        if (matches.isEmpty()) throw invalid("EDGE_APPLICATION_UNKNOWN");
        if (matches.size() > 1 && !matches.getFirst().alias().equals(matches.get(1).alias())
                && matcher.getPatternComparator(path).compare(matches.getFirst().pattern(), matches.get(1).pattern()) == 0) {
            throw invalid("EDGE_ROUTE_AMBIGUOUS");
        }
        return matches.getFirst().alias();
    }

    public static String appAlias(ServerWebExchange exchange) {
        String[] segments = exchange.getRequest().getPath().value().split("/", -1);
        if (segments.length < 4 || !"apps".equals(segments[1]) || !segments[2].matches("[A-Za-z0-9_-]{1,128}")) {
            throw invalid("EDGE_APPLICATION_UNKNOWN");
        }
        return segments[2];
    }

    public static boolean sameOrigin(URI left, URI right) {
        return left != null && right != null && "https".equals(left.getScheme()) && "https".equals(right.getScheme())
                && left.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost())
                && (left.getPort() < 0 ? 443 : left.getPort()) == (right.getPort() < 0 ? 443 : right.getPort());
    }

    private static EdgeOAuthStateException invalid(String code) { return new EdgeOAuthStateException(code); }

    public record Application(EdgeOAuthTargetBO target, ClientRegistration registration,
                              EdgeRegistrationResourceBindingBO resource, String callbackUri) { }

    private record MapEntry(String alias, String pattern) { }
}
