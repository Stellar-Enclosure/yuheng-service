package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GatewayEngineRbac3ConfigurationTest {

    @Test
    void declaresTheMcpDownstreamAuthorizationClient() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertEquals("${YUHENG_MCP_TIANQUAN_JIANSHEN_ENABLED:true}", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.enabled"
        ));
        assertEquals("false", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.register-filter"
        ));
        assertEquals("${YUHENG_MCP_TIANQUAN_JIANSHEN_SYSTEM_CODE}", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.system-code"
        ));
        org.junit.jupiter.api.Assertions.assertNull(value(properties,"egon.cola.platform.tianquan.jianshen.runtime.redis-address"));
        assertEquals("${TIANQUAN_SHOUBING_AUTHORITY_BASE_URI}",value(properties,"egon.cola.platform.tianquan.shoubing.authority.base-uri"));
        assertEquals("${YUHENG_MCP_TIANQUAN_JIANSHEN_AUTHORIZATION_ENDPOINT}", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.authorization.endpoint"
        ));
    }

    @Test
    void declaresDefaultEmptyServiceScopeBindingsInBothProfiles() {
        for (String profile : List.of("application.yml", "application-operations.yml")) {
            var loader = new YamlMapFactoryBean(); loader.setResources(new ClassPathResource(profile));
            var values = loader.getObject();
            assertEquals("classpath*:/mybatis/mapper/mcp/*.xml",
                    assertInstanceOf(Map.class, values.get("mybatis-plus"), profile).get("mapper-locations"));
            Object current = values;
            for (String key : List.of("egon", "cola", "component", "yuheng", "mcp-engine", "runtime")) {
                current = assertInstanceOf(Map.class, current, profile).get(key);
            }
            var bootstrap = assertInstanceOf(Map.class, current, profile);
            assertEquals(List.of(), assertInstanceOf(List.class, bootstrap.get("service-scope-bindings"), profile));
            assertEquals("${YUHENG_MCP_ENGINE_MAXIMUM_REQUEST_BYTES:2097152}",
                    assertInstanceOf(Map.class, bootstrap.get("listener"), profile).get("maximum-request-bytes"));
        }
    }

    private String value(Properties properties, String key) {
        return properties.getProperty(key);
    }
}
