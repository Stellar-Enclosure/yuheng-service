package top.egon.cola.component.yuheng.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcAutoConfiguration;
import java.util.Properties;
import static org.assertj.core.api.Assertions.*;

class GatewayAdminApplicationConfigurationTest {
    @Test void excludesGenericRedissonSpringDataAutoConfiguration(){assertThat(load("application.yml").getProperty("spring.autoconfigure.exclude[0]"))
            .isEqualTo("org.redisson.spring.starter.RedissonAutoConfigurationV4");}
    @Test void retainsResourceIdentityJacksonAndNativeRpcTransport(){var properties=load("application.yml");
        assertThat(properties.getProperty("egon.cola.platform.tianquan.shoubing.resource-server-id")).isEqualTo("${YUHENG_ADMIN_RESOURCE_SERVER_ID}");
        assertThat(properties.getProperty("egon.cola.platform.tianquan.shoubing.resource-uri")).isEqualTo("${YUHENG_ADMIN_RESOURCE_URI}");
        assertThat(properties.getProperty("spring.http.converters.preferred-json-mapper")).isEqualTo("jackson2");
        assertThat(properties.getProperty("egon.cola.component.rpc.transport")).isEqualTo("h2c");
    }
    @Test void everyExecutableProfileHasNoLegacyRpcRegistryRedisOrConfigDataGlue(){
        for(String profile:new String[]{"application.yml","application-local.yml","application-publisher.yml"}){
            var properties=load(profile);
            assertThat(properties.stringPropertyNames()).noneMatch(key->key.startsWith("egon.cola.component.tianshu.rpc.")
                    ||key.startsWith("egon.cola.component.tianshu.registry.")||key.startsWith("egon.cola.component.tianshu.redis.")
                    ||key.startsWith("yuheng.admin.tianshu."));
            assertThat(properties.stringPropertyNames().stream().filter(key->key.startsWith("spring.config.import")).map(properties::getProperty))
                    .noneMatch(value->value.startsWith("optional:tianshu:")||value.startsWith("tianshu:"));
        }
    }
    @Test void publisherRemainsIsolatedFromHttpAdminNativeRpcAndSiRoles(){var properties=load("application-publisher.yml");
        assertThat(properties.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(properties.getProperty("egon.cola.component.rpc.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("yuheng.admin.openapi.enabled")).isEqualTo("false");
        assertThat(properties.values()).contains("top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcAutoConfiguration");
    }
    @Test void actualThinSiArtifactContainsMappingButNoLegacyFactoryOrHttpRegistration(){var loader=getClass().getClassLoader();
        assertThat(loader.getResource("top/egon/cola/component/rpc/tianshu/mapping/DdcSiProtoMapper.class")).isNotNull();
        assertThat(loader.getResource("top/egon/cola/component/rpc/tianshu/client/DdcRpcClientFactory.class")).isNull();
        assertThat(loader.getResource("top/egon/cola/component/rpc/tianshu/client/DdcRpcClientHandle.class")).isNull();
        assertThat(loader.getResource("top/egon/cola/component/tianshu/http/registration/DdcHttpRegistrationRuntime.class")).isNull();
    }
    @Test void nativeSiOnlyAssemblyStartsWithoutLegacyClientsOrChannels(){new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DdcRpcAutoConfiguration.class)).run(context->{assertThat(context).hasNotFailed()
                    .hasBean("ddcSiProtoMapperImpl").doesNotHaveBean("ddcManagementClient").doesNotHaveBean("ddcSiCatalogRpcClient");});}
    @Test void nativeSiGuardRejectsRetiredRpcConfigurationRatherThanIgnoringIt(){new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DdcRpcAutoConfiguration.class))
            .withPropertyValues("egon.cola.component.tianshu.rpc.target=dns:///forbidden")
            .run(context->assertThat(context.getStartupFailure()).hasRootCauseMessage("DDC_LEGACY_RPC_CONFIGURATION_RETIRED"));}
    private static Properties load(String resource){var factory=new YamlPropertiesFactoryBean();factory.setResources(new ClassPathResource(resource));return factory.getObject();}
}
