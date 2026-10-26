package top.egon.cola.component.yuheng.edge.service.domain.bo;

import java.net.URI;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/** Adds the fixed Resource to Spring's native Refresh grant without changing its protocol encoding. */
public final class EdgeResourceRefreshTokenGrantRequest extends OAuth2RefreshTokenGrantRequest {
    private final URI resource;
    private final String browserBindingDigest;

    public EdgeResourceRefreshTokenGrantRequest(ClientRegistration client, OAuth2AccessToken access,
            OAuth2RefreshToken refresh, URI resource, String browserBindingDigest) {
        super(client, access, refresh);
        EdgeOAuthTargetBO.requireCanonicalHttps(resource.toASCIIString());
        this.resource = resource;
        top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests.requireDigest(browserBindingDigest);
        this.browserBindingDigest = browserBindingDigest;
    }

    public URI getResource() { return resource; }
    public String getBrowserBindingDigest() { return browserBindingDigest; }
}
