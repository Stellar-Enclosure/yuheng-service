package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class GatewayHttpProvidersLiveIT {

    @Test
    @EnabledIfSystemProperty(named = GatewayLiveEnvironment.ENABLED, matches = "true")
    void verifiesMvcAndWebFluxThroughCilium() throws Exception {
        new GatewayLiveTopologyIT().verifyHttpProvidersLifecycle();
    }
    @Test
    @EnabledIfSystemProperty(named = "yuheng.live.cilium.gamma-http-enabled", matches = "true")
    void verifiesInternalAndNorthboundHttpRequestWeights() throws Exception {
        new GatewayLiveTopologyIT().verifyHttpWeightLifecycle();
    }

}
