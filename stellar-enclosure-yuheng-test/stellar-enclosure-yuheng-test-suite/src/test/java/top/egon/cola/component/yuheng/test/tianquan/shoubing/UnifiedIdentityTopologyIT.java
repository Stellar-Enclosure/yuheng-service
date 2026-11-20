package top.egon.cola.component.yuheng.test.tianquan.shoubing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnifiedIdentityTopologyIT {

    @Test
    void ciliumOriginsDisallowPrivateEndpointsAndCredentialRedirection() {
        assertThat(UnifiedIdentityLiveClient.ciliumOrigin("https://public.apps.example.test").getHost())
                .isEqualTo("public.apps.example.test");
        for (String invalid : java.util.List.of("https://127.0.0.1:28140", "http://public.apps.example.test",
                "https://actor@public.apps.example.test", "https://yuheng.namespace.svc.cluster.local",
                "https://public.apps.example.test/api/mock/read", "https://public.apps.example.test#fragment")) {
            assertThatThrownBy(() -> UnifiedIdentityLiveClient.ciliumOrigin(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ciliumRouteForwardsValidIdentityAndTheBackendRejectsMissingAndForeignCredentials() throws Exception {
        UnifiedIdentityLiveClient client = UnifiedIdentityLiveClient.enabled();
        var endpoint = UnifiedIdentityLiveClient.ciliumOrigin(client.requiredEnv("UNIFIED_IDENTITY_CILIUM_ORIGIN"))
                .resolve("/api/mock/read");
        String token = client.token("UNIFIED_IDENTITY_DEFAULT_TOKEN_FILE");
        String wrongAudience = client.token("UNIFIED_IDENTITY_WRONG_AUDIENCE_TOKEN_FILE");

        assertThat(client.get(endpoint, token)).isEqualTo(200);
        assertThat(client.get(endpoint, null)).isEqualTo(401);
        assertThat(client.get(endpoint, wrongAudience)).isEqualTo(401);
    }
}
