package top.egon.cola.component.yuheng.llm.architecture;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ClassPathResource;
import top.egon.cola.component.yuheng.llm.bootstrap.LlmGatewayApplication;
import top.egon.cola.component.yuheng.llm.config.LlmGatewayConfiguration;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAccessBoundaryTest {
    private static final String ROLE = "stellar-enclosure-model-access-service";

    @Test
    void ownsTheIndependentRoleCoordinates() throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        assertThat(xpath.evaluate("/project/artifactId", pom)).isEqualTo(ROLE);
        assertThat(xpath.evaluate("/project/build/finalName", pom)).isEqualTo(ROLE);
        assertThat(xpath.evaluate("/project/build/plugins/plugin[artifactId='spring-boot-maven-plugin']/configuration/classifier", pom))
                .isEqualTo("exec");
        assertThat(xpath.evaluate("/project/build/plugins/plugin[artifactId='spring-boot-maven-plugin']/configuration/mainClass", pom))
                .isEqualTo(LlmGatewayApplication.class.getName());
    }

    @Test
    void scansOnlyTheModelRoleAndItsOwnMappers() {
        assertThat(LlmGatewayApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages())
                .containsExactly("top.egon.cola.component.yuheng.llm");
        assertThat(LlmGatewayConfiguration.class.getAnnotation(MapperScan.class).value())
                .containsExactly("top.egon.cola.component.yuheng.llm.proxy.dao");
    }

    @Test
    void excludesGenericProxyAndDiscoveryFromTheActualRuntimeClasspath() throws Exception {
        var loader = getClass().getClassLoader();
        for (String forbidden : List.of(
                "top/egon/cola/component/yuheng/runtime/provider/service/ProviderDirectory.class",
                "top/egon/cola/component/yuheng/runtime/rule/service/GatewayRuleActivationApplier.class",
                "top/egon/cola/component/yuheng/runtime/config/GatewayRuntimeConfiguration.class",
                "top/egon/cola/component/yuheng/admin/bootstrap/GatewayAdminApplication.class",
                "top/egon/cola/component/yuheng/engine/GatewayEngineApplication.class",
                "top/egon/cola/component/yuheng/mcp/engine/McpGatewayEngineApplication.class",
                "top/egon/cola/component/rpc/tianshu/autoconfigure/DdcRpcAutoConfiguration.class",
                "top/egon/cola/component/tianshu/api/client/DdcServiceRegistryClient.class")) {
            assertThat(loader.getResources(forbidden).asIterator()).as(forbidden).isExhausted();
        }
    }

    @Test
    void bothProfilesTerminatePublicTlsAtTheNetworkEdge() {
        for (String profile : List.of("application.yml", "application-local.yml")) {
            var source = new YamlPropertiesFactoryBean();
            source.setResources(new ClassPathResource(profile));
            var settings = Objects.requireNonNull(source.getObject());
            assertThat(settings.getProperty("server.ssl.enabled")).as(profile).isEqualTo("false");
            assertThat(settings.getProperty("server.forward-headers-strategy")).as(profile).isEqualTo("none");
            assertThat(settings.getProperty("egon.cola.component.cache.enabled")).as(profile).isEqualTo("false");
            assertThat(settings.getProperty("egon.cola.component.id.enabled")).as(profile).isEqualTo("false");
            assertThat(settings.getProperty("egon.cola.component.rpc.enabled")).as(profile).isEqualTo("false");
            assertThat(settings.getProperty("mybatis-plus.mapper-locations")).as(profile)
                    .isEqualTo("classpath*:/mybatis/mapper/llm/*.xml");
            assertThat(settings.stringPropertyNames()).as(profile)
                    .noneMatch(key -> key.startsWith("server.ssl.key-") || key.startsWith("server.ssl.trust-"));
        }
    }
}
