package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayInfrastructureLiveIT {

    @Test
    @EnabledIfSystemProperty(named = GatewayLiveEnvironment.ENABLED, matches = "true")
    void readinessLivesOnTheApprovedInClusterManagementPort() throws Exception {
        var internal = GatewayLiveEnvironment.eastWest(GatewayLiveEnvironment.required("management-origin"));
        var publicUrl = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("mvc-provider-url"));
        var readiness = "/actuator/health/readiness";
        try (var environment = new GatewayLiveEnvironment()) {
            var management = environment.probe(internal.resolve(readiness), "GET", null);
            assertThat(management.status()).isEqualTo(200);
            assertThat(management.json().path("status").textValue()).isEqualTo("UP");
            assertThat(environment.probe(publicUrl.resolve(readiness), "GET", null).status()).isEqualTo(404);
        }
    }
}
