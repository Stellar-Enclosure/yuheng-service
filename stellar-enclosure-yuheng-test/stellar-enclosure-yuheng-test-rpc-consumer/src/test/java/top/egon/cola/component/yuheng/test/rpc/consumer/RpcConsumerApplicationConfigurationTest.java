package top.egon.cola.component.yuheng.test.rpc.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class RpcConsumerApplicationConfigurationTest {

    @Test
    void excludesTheGenericRedissonAutoConfiguration() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));

        assertThat(loader.getObject().getProperty(
                "spring.autoconfigure.exclude[0]"
        )).isEqualTo(
                        "org.redisson.spring.starter."
                                + "RedissonAutoConfigurationV4"
                );
    }
    @Test
    void bindsOnlyTheFixtureMethodsToAnExplicitService() {
        var loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        var properties = loader.getObject();
        String prefix = "egon.cola.component.rpc.";

        assertThat(properties.getProperty(prefix + "transport")).isEqualTo("h2c");
        String echo = prefix + "consumer.bindings.echo-fixture.";
        String order = prefix + "consumer.bindings.order-fixture.";
        for (String binding : new String[]{echo, order}) {
            assertThat(properties.getProperty(binding + "namespace")).isEqualTo("${YUHENG_RPC_FIXTURE_NAMESPACE}");
            assertThat(properties.getProperty(binding + "service")).isEqualTo("${YUHENG_RPC_FIXTURE_SERVICE}");
            assertThat(properties.getProperty(binding + "port")).isEqualTo("${YUHENG_RPC_FIXTURE_PORT}");
        }
        assertThat(properties.getProperty(echo + "methods[0]")).isEqualTo("egon.yuheng.test.v1.EchoService/Echo");
        assertThat(properties.getProperty(echo + "methods[1]")).isNull();
        assertThat(properties.getProperty(order + "methods[0]")).isEqualTo("egon.yuheng.test.v1.OrderService/GetOrder");
        assertThat(properties.getProperty(order + "methods[1]")).isEqualTo("egon.yuheng.test.v1.OrderService/CreateOrder");
        assertThat(properties.getProperty(order + "methods[2]")).isEqualTo("egon.yuheng.test.v1.OrderService/SlowCall");
        assertThat(properties.getProperty(order + "methods[3]")).isEqualTo("egon.yuheng.test.v1.OrderService/Fail");
        assertThat(properties.getProperty(order + "methods[4]")).isNull();
        assertThat(properties.stringPropertyNames()).noneMatch(key -> key.startsWith("egon.cola.component.tianshu.")
                || key.startsWith(prefix + "consumer.yuheng"));
    }

}
