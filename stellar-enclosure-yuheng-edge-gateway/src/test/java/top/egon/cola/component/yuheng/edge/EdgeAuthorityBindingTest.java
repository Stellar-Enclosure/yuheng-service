package top.egon.cola.component.yuheng.edge;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityAuthorityRegistry;
import top.egon.cola.platform.tianquan.shoubing.starter.state.ReactiveIdentityAuthorityClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EdgeAuthorityBindingTest {
    @Test void verifiedIssuerCannotSelectAnotherResourcesServiceIdentity() {
        var issuer = URI.create("https://idp.example/tenants/1001");
        var resourceA = URI.create("https://a.example");
        var resourceB = URI.create("https://b.example");
        var a = mock(ReactiveIdentityAuthorityClient.class);
        var b = mock(ReactiveIdentityAuthorityClient.class);
        var registry = new EdgeIdentityAuthorityRegistry(Map.of(new EdgeIdentityAuthorityRegistry.Binding(issuer, resourceA), a,
                new EdgeIdentityAuthorityRegistry.Binding(issuer, resourceB), b), List.of());
        assertThat(registry.forResource(issuer, resourceA)).isSameAs(a);
        assertThat(registry.forResource(issuer, resourceB)).isSameAs(b);
        assertThatThrownBy(() -> registry.forResource(issuer, URI.create("https://other.example"))).hasMessage("EDGE_IDENTITY_AUTHORITY_UNAVAILABLE");
        assertThatThrownBy(() -> registry.forResource(URI.create("https://idp.example/tenants/1002"), resourceA)).hasMessage("EDGE_IDENTITY_AUTHORITY_UNAVAILABLE");
        verifyNoInteractions(a, b);
    }
}
