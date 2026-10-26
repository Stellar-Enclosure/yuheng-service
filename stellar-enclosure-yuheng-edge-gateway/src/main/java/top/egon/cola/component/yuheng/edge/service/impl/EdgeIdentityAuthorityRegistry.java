package top.egon.cola.component.yuheng.edge.service.impl;

import java.net.URI;
import java.util.List;
import java.util.Map;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.state.ReactiveIdentityAuthorityClient;

/** A verified issuer selects a prebuilt SERVICE binding, never a caller-provided authority URL. */
public final class EdgeIdentityAuthorityRegistry implements AutoCloseable {
    private final Map<Binding, ReactiveIdentityAuthorityClient> authorities;
    private final List<IdpServiceOAuth2Client> serviceClients;

    public EdgeIdentityAuthorityRegistry(Map<Binding, ReactiveIdentityAuthorityClient> authorities,
                                         List<IdpServiceOAuth2Client> serviceClients) {
        if (authorities == null || authorities.isEmpty()) throw new IllegalArgumentException("EDGE authorities are required");
        this.authorities = Map.copyOf(authorities);
        this.serviceClients = List.copyOf(serviceClients);
    }

    public ReactiveIdentityAuthorityClient forResource(URI issuer, URI resource) {
        var authority = issuer == null || resource == null ? null : authorities.get(new Binding(issuer, resource));
        if (authority == null) throw new EdgeOAuthStateException("EDGE_IDENTITY_AUTHORITY_UNAVAILABLE");
        return authority;
    }

    public record Binding(URI issuer, URI sourceResource) { }

    @Override
    public void close() { serviceClients.forEach(IdpServiceOAuth2Client::close); }
}
