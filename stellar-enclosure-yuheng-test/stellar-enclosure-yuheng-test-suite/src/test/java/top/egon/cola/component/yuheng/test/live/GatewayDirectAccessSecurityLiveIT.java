package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs with the explicitly approved acceptance Pod identity and reviewed denied destinations. */
class GatewayDirectAccessSecurityLiveIT {

    @Test
    @EnabledIfSystemProperty(named = "yuheng.live.cilium.direct-access-enabled", matches = "true")
    void approvedServiceAllowsTheCallButVersionServicesAndPodAddressesAreUnreachable() throws Exception {
        var stable = GatewayLiveEnvironment.eastWest(GatewayLiveEnvironment.required("network-allowed-origin"));
        var bearer = GatewayLiveEnvironment.approvedToken("network-token-file");
        String approvedPath = GatewayLiveEnvironment.required("network-allowed-path");
        assertThat(approvedPath).matches("/api/providers/[A-Za-z0-9_-]{1,64}");
        try (var environment = new GatewayLiveEnvironment()) {
            assertThat(environment.probe(stable.resolve(approvedPath), "GET", bearer).status()).isEqualTo(200);
        }

        String namespace = stable.getHost().split("\\.")[1];
        for (String version : new String[]{"stable", "canary"}) {
            String service = GatewayLiveEnvironment.required("network-" + version + "-service");
            assertThat(service).matches("[a-z][a-z0-9-]{0,55}-" + version);
            String host = service + "." + namespace + ".svc.cluster.local";
            var address = InetAddress.getByName(host);
            assertThat(address).isEqualTo(approvedPodAddress("network-" + version + "-service-ip"));
            assertTcpDenied(address, stable.getPort());
        }
        int podPort = Integer.parseInt(GatewayLiveEnvironment.required("network-backend-pod-port"));
        assertThat(podPort).isBetween(1, 65_535);
        assertTcpDenied(approvedPodAddress("network-backend-pod-ip"), podPort);
    }

    @Test
    @EnabledIfSystemProperty(named = "yuheng.live.cilium.foreign-test-identity-enabled", matches = "true")
    void unapprovedCallerIdentityCannotReachTheHealthyServiceOrManagementPort() throws Exception {
        var stable = GatewayLiveEnvironment.eastWest(GatewayLiveEnvironment.required("network-allowed-origin"));
        var management = GatewayAdminTestClient.reviewedServiceOrigin(GatewayLiveEnvironment.required("network-management-origin"));
        assertThat(management).isNotEqualTo(stable);
        assertTcpDenied(InetAddress.getByName(stable.getHost()), stable.getPort());
        assertTcpDenied(InetAddress.getByName(management.getHost()), management.getPort());
    }

    @Test
    @EnabledIfSystemProperty(named = "yuheng.live.cilium.public-security-enabled", matches = "true")
    void publicTrafficSeparatesRouteMissIdentityDenialAndAuthorityUnavailable() throws Exception {
        var allowed = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("security-public-url"));
        var foreignHost = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("security-unmatched-host-url"));
        var unavailable = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("security-authority-unavailable-url"));
        assertThat(foreignHost.getHost()).isNotEqualTo(allowed.getHost());
        assertThat(foreignHost.getRawPath()).isEqualTo(allowed.getRawPath());
        assertThat(unavailable).isNotEqualTo(allowed);
        var bearer = GatewayLiveEnvironment.approvedToken("security-public-token-file");
        var wrongAudience = GatewayLiveEnvironment.approvedToken("security-wrong-audience-token-file");
        var missingGrant = GatewayLiveEnvironment.approvedToken("security-missing-grant-token-file");
        var unavailableBearer = GatewayLiveEnvironment.approvedToken("security-authority-unavailable-token-file");
        try (var environment = new GatewayLiveEnvironment()) {
            assertThat(environment.probe(allowed, "GET", bearer).status()).isEqualTo(200);
            assertThat(environment.probe(allowed, "GET", wrongAudience).status()).isEqualTo(401);
            assertThat(environment.probe(allowed, "GET", missingGrant).status()).isEqualTo(403);
            assertThat(environment.probe(foreignHost, "GET", null).status()).isEqualTo(404);
            assertThat(environment.probe(unavailable, "GET", unavailableBearer).status()).isEqualTo(503);
        }
    }

    private static InetAddress approvedPodAddress(String key) throws Exception {
        String value = GatewayLiveEnvironment.required(key);
        assertThat(value).matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F:]+:[0-9a-fA-F:]+");
        var address = InetAddress.ofLiteral(value);
        assertThat(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()).isFalse();
        return address;
    }

    private static void assertTcpDenied(InetAddress address, int port) throws Exception {
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(address, port), 1500);
        } catch (SocketTimeoutException | ConnectException | NoRouteToHostException denied) {
            return;
        }
        throw new AssertionError("A protected version Service or Pod accepted the denied TCP probe");
    }
}
