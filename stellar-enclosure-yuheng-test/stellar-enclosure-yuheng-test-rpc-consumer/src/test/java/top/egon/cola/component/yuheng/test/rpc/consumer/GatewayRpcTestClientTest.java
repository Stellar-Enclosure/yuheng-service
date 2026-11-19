package top.egon.cola.component.yuheng.test.rpc.consumer;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GatewayRpcTestClientTest {

    @Test
    void consumerReferencesBusinessContractsThroughRpcComponent()
            throws Exception {
        EgonRpcReference echo = GatewayRpcTestClient.class
                .getDeclaredField("echoRpc")
                .getAnnotation(EgonRpcReference.class);
        EgonRpcReference order = GatewayRpcTestClient.class
                .getDeclaredField("orderRpc")
                .getAnnotation(EgonRpcReference.class);

        assertNotNull(echo);
        assertNotNull(order);
        assertEquals("echo-fixture", echo.bindingId());
        assertEquals(0, echo.retries());
        assertEquals("order-fixture", order.bindingId());
        assertEquals(0, order.retries());
        assertEquals(3000, echo.timeoutMs());
        assertEquals(3000, order.timeoutMs());
    }
}
