package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class GatewayRpcDualEngineLiveIT {

    @Test
    @EnabledIfSystemProperty(named = GatewayLiveEnvironment.ENABLED, matches = "true")
    void verifiesStableRpcServiceAndMethodWeights()
            throws Exception {
        new GatewayLiveTopologyIT().verifyRpcServiceLifecycle();
    }
}
