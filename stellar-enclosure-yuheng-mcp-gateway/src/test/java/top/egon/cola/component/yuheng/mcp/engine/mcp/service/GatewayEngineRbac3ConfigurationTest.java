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

        assertEquals("${YUHENG_MCP_TIANQUAN_JIANSHEN_ENABLED:false}", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.enabled"
        ));
        assertEquals("false", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.register-filter"
        ));
        assertEquals("${YUHENG_MCP_TIANQUAN_JIANSHEN_SYSTEM_CODE:mock-backend}", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.system-code"
        ));
        org.junit.jupiter.api.Assertions.assertNull(value(properties,"egon.cola.platform.tianquan.jianshen.runtime.redis-address"));
        assertEquals("${TIANQUAN_SHOUBING_AUTHORITY_BASE_URI}",value(properties,"egon.cola.platform.tianquan.shoubing.authority.base-uri"));
        assertEquals("${YUHENG_MCP_TIANQUAN_JIANSHEN_AUTHORIZATION_ENDPOINT:}", value(
                properties,
                "egon.cola.platform.tianquan.jianshen.authorization.endpoint"
        ));
    }

    @Test
    void declaresDefaultEmptyServiceScopeBindingsInBothProfiles() {
        for (String profile : List.of("application.yml", "application-operations.yml")) {
            var loader = new YamlMapFactoryBean(); loader.setResources(new ClassPathResource(profile));
            Object current = loader.getObject();
            for (String key : List.of("egon", "cola", "component", "yuheng", "mcp-engine")) {
                current = assertInstanceOf(Map.class, current, profile).get(key);
            }
            var bootstrap = assertInstanceOf(Map.class, current, profile);
            assertEquals(List.of(), assertInstanceOf(List.class, bootstrap.get("service-scope-bindings"), profile));
            assertEquals(Boolean.TRUE, assertInstanceOf(Map.class, bootstrap.get("listener"), profile).get("enabled"));
        }
    }

    private String value(Properties properties, String key) {
        return properties.getProperty(key);
    }
}
